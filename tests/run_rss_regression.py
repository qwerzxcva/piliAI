#!/usr/bin/env python3
"""提取生产 Kotlin RSS 解析函数，编译并在 JVM 运行；不是 Python 重写解析器。"""
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
# 审核314：parseRss/readText/parseDate/guessEpisode 已随 SubscribeParser
# 拆到独立文件（原在 SubscribeRepository.kt 里，拆后此脚本按字符串
# 找不到函数，报 ValueError: substring not found）。
source = (ROOT / 'android/app/src/main/kotlin/com/example/piliai/data/repository/SubscribeParser.kt').read_text()

def function(name):
    start = source.index('    private fun ' + name + '(')
    end = source.find('\n    }', start) + len('\n    }')
    assert end > start
    return source[start:end]

prefix = '''import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
object Parser {
private const val MAX_ITEMS_PER_SOURCE = 500
data class ParsedItem(val title: String, val cover: String, val link: String, val desc: String, val pubAt: Long, val episode: String)
data class ParsedSource(val name: String, val cover: String, val items: List<ParsedItem>)
fun parse(s: String) = parseRss(s)
'''
tests = '''
}
fun main() {
    val rss = """<rss xmlns:x="http://search.yahoo.com/mrss/" xmlns:i="http://www.itunes.com/dtds/podcast-1.0.dtd"><channel><image><url>https://img/feed.jpg</url></image><title>Feed</title>
    <item><title>A</title><link>https://page/a</link><enclosure url="https://cdn/a.mp4" type="video/mp4"/><x:thumbnail url="https://img/a.jpg"/><description><![CDATA[desc]]></description></item>
    <item><title>B</title><enclosure url="magnet:?xt=urn:btih:demo" type="application/x-bittorrent"/><i:image href="https://img/b.jpg"/></item>
    <item><title>C</title><enclosure url="https://cdn/c.mp3" type="audio/mpeg"/></item>
    </channel></rss>"""
    val r = Parser.parse(rss)
    check(r.name == "Feed" && r.cover == "https://img/feed.jpg")
    check(r.items.size == 3)
    check(r.items[0].link == "https://cdn/a.mp4" && r.items[0].cover == "https://img/a.jpg")
    check(r.items[0].desc == "desc")
    check(r.items[1].link.startsWith("magnet:") && r.items[1].cover == "https://img/b.jpg")
    check(r.items[2].cover.isEmpty())
    println("PASS RSS arbitrary namespace prefixes, cover, enclosure, CDATA")
    for (reverse in listOf(false, true)) {
        val links = listOf("<link rel=\\"alternate\\" href=\\"https://page/1\\"/>", "<link rel=\\"enclosure\\" href=\\"https://cdn/1.mp4\\"/>")
        val atom = """<feed xmlns="http://www.w3.org/2005/Atom"><title>Atom</title><entry><title>One</title>${(if(reverse) links.reversed() else links).joinToString("")}<link rel="self" href="https://api/self"/><content type="xhtml"><div xmlns="http://www.w3.org/1999/xhtml">Hello <b>world</b></div></content></entry><entry><title>Two</title><link href="https://page/2"/></entry></feed>"""
        val a = Parser.parse(atom)
        check(a.items.size == 2)
        check(a.items[0].link == "https://cdn/1.mp4")
        check(a.items[0].desc == "Hello world")
        check(a.items[1].title == "Two" && a.items[1].link == "https://page/2")
    }
    println("PASS Atom rel order, self ignored, nested XHTML, next entry")
    val blank = Parser.parse("<rss><channel><item><title/><link>https://x/a</link></item></channel></rss>")
    check(blank.items.single().title == "https://x/a")
    check(runCatching { Parser.parse("<rss><channel><item><title>broken") }.isFailure)
    println("PASS empty title and malformed XML failure")
}
'''
# ParseDate and guessEpisode are copied verbatim, preserving production semantics.
program = prefix + '\n'.join(function(n) for n in ['parseRss', 'readText', 'parseDate', 'guessEpisode']) + tests
lib = Path(os.environ.get('KOTLIN_LIB') or (sorted(Path.home().glob('.gradle/wrapper/dists/gradle-8.14*-bin/*/gradle-8.14*/lib')) or [None])[-1] or Path('/opt/gradle-8.14.2/lib'))
configured_kxml = os.environ.get('KXML2_JAR')
if configured_kxml:
    kxml = Path(configured_kxml)
else:
    candidates = sorted(Path.home().glob('.gradle/caches/modules-2/files-2.1/net.sf.kxml/kxml2/*/*/kxml2-*.jar'))
    for variable in ('ANDROID_HOME', 'ANDROID_SDK_ROOT'):
        sdk = os.environ.get(variable)
        if sdk:
            candidates.extend(sorted(Path(sdk).glob('cmdline-tools/*/lib/external/net/sf/kxml/kxml2/*/kxml2-*.jar')))
    candidates = [path for path in candidates if path.is_file()]
    if not candidates:
        raise RuntimeError('Set KXML2_JAR or ANDROID_HOME/ANDROID_SDK_ROOT to an installed SDK containing kxml2')
    kxml = candidates[-1]
if not kxml.is_file():
    raise RuntimeError(f'XML parser library does not exist: {kxml}')
stdlib = next(lib.glob('kotlin-stdlib-*.jar'))
cp = f'{stdlib}:{kxml}'
with tempfile.TemporaryDirectory(prefix='pilinara-rss-') as directory:
    d = Path(directory)
    kt = d / 'RssRegression.kt'
    kt.write_text(program)
    subprocess.run(['java', '-cp', str(lib / '*'), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect', '-classpath', cp, '-d', str(d / 'classes'), str(kt)], check=True)
    subprocess.run(['java', '-cp', f'{d / "classes"}:{cp}', 'RssRegressionKt'], check=True)
