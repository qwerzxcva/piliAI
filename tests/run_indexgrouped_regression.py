#!/usr/bin/env python3
"""Exercise production index-grouped episode parsing (line containers) on the JVM.

This does not verify Android UI, HTTP requests, playback, or GPU-next.
"""
from pathlib import Path
import os
import re
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
source = (ROOT / 'android/app/src/main/kotlin/com/example/piliai/data/repository/AnimekoScraper.kt').read_text(encoding='utf-8')


def production_method(name):
    declaration = re.search(rf'(?m)^    (?:private )?fun {re.escape(name)}\(', source)
    if declaration is None:
        raise RuntimeError(f'Production method not found: {name}')
    following = re.search(r'(?m)^    (?:private )?fun \w+\(', source[declaration.end():])
    if following is None:
        raise RuntimeError(f'Cannot determine production method boundary: {name}')
    return source[declaration.start():declaration.end() + following.start()]


# Everything before the first step keeps the data classes and the config parser.
program = source[:source.index('    /**\n     * 第一步：搜索页')]
for name in (
    'parseEpisodes',
    'absUrl',
    'episodeNum',
    'epNumFrom',
    'compileWithNamedEp',
    'compileWithNamedGroup',
    'isHttpUrl',
    'resolveUrl',
):
    program += production_method(name) + '\n'
program += r'''
    fun regressionEpisodes(html: String, base: String, cfg: SearchConfig): List<Episode> =
        parseEpisodes(html, base, cfg)
}
'''

MAIN = r'''
fun main() {
    val base = "https://site.test/vod/detail/1.html"
    val cfg = AnimekoScraper.SearchConfig(
        channelFormatId = "index-grouped",
        selectEpisodeLists = ".module-tab-item",
        selectChannelNames = ".module-tab-item h3",
        selectEpisodesFromList = ".module-list a",
        selectEpisodes = ".module-list a"
    )
    val episodes = AnimekoScraper.regressionEpisodes(MAIN_HTML, base, cfg)
    check(episodes.size == 4) { "expected 4 episodes, got ${episodes.size}: $episodes" }
    check(episodes.map { it.channel } == listOf("A1", "A1", "B1", "B1")) {
        "line assignment wrong: ${episodes.map { it.channel }}"
    }
    check(episodes.map { it.url } == listOf(
        "https://site.test/play/1.html",
        "https://site.test/play/2.html",
        "https://site.test/play/3.html",
        "https://site.test/play/4.html"
    ))
    check(episodes.map { it.name } == listOf("EP1", "EP2", "EP1", "EP2"))
    println("PASS production index-grouped: line containers keep their own episodes")

    // Episode and link selectors can point at different elements; they align by index.
    val sepCfg = cfg.copy(
        selectEpisodeLists = ".tab",
        selectChannelNames = ".tab span",
        selectEpisodesFromList = ".eps li",
        selectEpisodeLinksFromList = ".links a"
    )
    val sep = AnimekoScraper.regressionEpisodes(SEPARATED_HTML, base, sepCfg)
    check(sep.size == 2)
    check(sep.map { it.url } == listOf("https://site.test/l/1", "https://site.test/l/2"))
    check(sep.all { it.channel == "LineA" })
    println("PASS production index-grouped: separated episode and link selectors align by index")

    // No container configured: fall back to flat selection instead of returning nothing.
    val flatCfg = cfg.copy(selectEpisodeLists = "", selectEpisodesFromList = "")
    val flatOut = AnimekoScraper.regressionEpisodes(FLAT_HTML, base, flatCfg)
    check(flatOut.size == 2) { "flat size: ${flatOut.size} urls=${flatOut.map { it.url }}" }
    check(flatOut.map { it.url } == listOf("https://site.test/f/1", "https://site.test/f/2")) {
        "flat urls: ${flatOut.map { it.url }}"
    }
    println("PASS production index-grouped: flat fallback still resolves")

    // Only the from-list selector is configured: still resolves (blank selectEpisodes
    // used to short-circuit to an empty list before any selection happened).
    val onlyFromList = cfg.copy(selectEpisodeLists = "", selectEpisodes = "")
    val onlyOut = AnimekoScraper.regressionEpisodes(MAIN_HTML, base, onlyFromList)
    check(onlyOut.size == 4) { "onlyFromList size: ${onlyOut.size}" }
    println("PASS production index-grouped: from-list selector alone still resolves")

    // Episode sorting applies to container results too.
    val sortedCfg = cfg.copy(filterByEpisodeSort = true)
    val sorted = AnimekoScraper.regressionEpisodes(MAIN_HTML, base, sortedCfg)
    check(sorted.map { it.name } == listOf("EP1", "EP1", "EP2", "EP2"))
    println("PASS production index-grouped: episode sort applies to container results")
}
'''

MAIN_HTML = (
    '<div class="player-list">'
    '<div class="module-tab-item"><h3>A1</h3>'
    '<div class="module-list"><a href="/play/1.html">EP1</a>'
    '<a href="/play/2.html">EP2</a></div></div>'
    '<div class="module-tab-item"><h3>B1</h3>'
    '<div class="module-list"><a href="/play/3.html">EP1</a>'
    '<a href="/play/4.html">EP2</a></div></div>'
    '</div>'
)
SEPARATED_HTML = (
    '<div class="tab"><span>LineA</span>'
    '<ul class="eps"><li data-src="/s/1">EP1</li><li data-src="/s/2">EP2</li></ul>'
    '<ul class="links"><a href="/l/1"></a><a href="/l/2"></a></ul>'
    '</div>'
)
FLAT_HTML = (
    '<div class="module-list"><a href="/f/1">EP1</a>'
    '<a href="/f/2">EP2</a></div>'
)


def kotlin_literal(text: str) -> str:
    escaped = text.replace('\\', '\\\\').replace('"', '\\"')
    return '"' + escaped + '"'


main = (MAIN
        .replace('MAIN_HTML', kotlin_literal(MAIN_HTML))
        .replace('SEPARATED_HTML', kotlin_literal(SEPARATED_HTML))
        .replace('FLAT_HTML', kotlin_literal(FLAT_HTML)))

configured = os.environ.get('KOTLIN_LIB')
candidates = sorted(Path.home().glob('.gradle/wrapper/dists/gradle-*/*/gradle-*/lib'))
candidates = [p for p in candidates if list(p.glob('kotlin-compiler-embeddable-*.jar'))]
lib = Path(configured) if configured else (candidates[-1] if candidates else None)
if lib is None:
    raise RuntimeError('Set KOTLIN_LIB to an installed Kotlin compiler library directory')
stdlib = next(lib.glob('kotlin-stdlib-*.jar'))
cache = Path.home() / '.gradle/caches/modules-2/files-2.1'
deps = [str(stdlib)]
for artifact, version in (
    ('org.jsoup/jsoup', '1.18.1'),
    ('org.jetbrains.kotlinx/kotlinx-serialization-json-jvm', '1.7.3'),
    ('org.jetbrains.kotlinx/kotlinx-serialization-core-jvm', '1.7.3'),
):
    jars = sorted((cache / artifact / version).glob('*/*.jar'))
    if not jars:
        raise RuntimeError(f'Missing cached project dependency: {artifact}:{version}')
    deps.append(str(jars[0]))
classpath = os.pathsep.join(deps)

with tempfile.TemporaryDirectory(prefix='piliai-indexgrouped-') as directory:
    temporary = Path(directory)
    test = temporary / 'IndexGroupedRegression.kt'
    test.write_text(program + main, encoding='utf-8')
    classes = temporary / 'classes'
    subprocess.run([
        'java', '-cp', str(lib / '*'),
        'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
        '-no-stdlib', '-no-reflect', '-classpath', classpath,
        '-d', str(classes), str(test),
    ], check=True, timeout=120)
    subprocess.run([
        'java', '-cp', str(classes) + os.pathsep + classpath,
        'com.example.piliai.data.repository.IndexGroupedRegressionKt',
    ], check=True, timeout=30)
