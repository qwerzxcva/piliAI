#!/usr/bin/env python3
"""Exercise production JSON-path configuration and parsing on the JVM.

This does not verify Android UI, HTTP requests, playback, or GPU-next.
"""
from pathlib import Path
import os
import re
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'android/app/src/main/kotlin/com/example/piliai/data/repository/AnimekoScraper.kt'
source = SOURCE.read_text(encoding='utf-8')


def production_method(name):
    declaration = re.search(rf'(?m)^    (?:private )?fun {re.escape(name)}\(', source)
    if declaration is None:
        raise RuntimeError(f'Production method not found: {name}')
    following = re.search(r'(?m)^    (?:private )?fun \w+\(', source[declaration.end():])
    if following is None:
        raise RuntimeError(f'Cannot determine production method boundary: {name}')
    return source[declaration.start():declaration.end() + following.start()]


program = source[:source.index('    /**\n     * 第一步：搜索页')]
for name in (
    'parseSubjectsByJsonPath',
    'jsonPathField',
    'jsonPathFields',
    'firstString',
    'applyLinkTemplate',
    'isHttpUrl',
    'resolveUrl',
):
    program += production_method(name) + '\n'
program += r'''
    fun regressionParse(text: String, base: String, cfg: SearchConfig): List<Subject> =
        parseSubjectsByJsonPath(text, base, cfg)
}

fun main() {
    val base = "https://source.test/catalog/search.json"
    val cfg = AnimekoScraper.SearchConfig(
        subjectFormatId = "json-path-indexed",
        selectNamesJsonPath = "\$[*].name",
        selectLinksJsonPath = "\$[*].url"
    )
    val rows = """[
        {"name":"First","url":"../show/1"},
        {"name":"Second","url":"/show/2"},
        {"name":"Third","url":"//cdn.test/show/3"},
        {"name":"Duplicate","url":"/show/2"},
        {"name":"Invalid","url":"javascript:alert(1)"},
        {"name":"Credentials","url":"https://user:pass@cdn.test/show/4"},
        {"name":"","url":"/show/5"},
        {"name":"Missing URL"},
        {"name":123,"url":"/show/6"},
        {"name":"Object URL","url":{"value":"/show/7"}},
        null, 42
    ]"""
    val expected = listOf(
        AnimekoScraper.Subject("First", "https://source.test/show/1"),
        AnimekoScraper.Subject("Second", "https://source.test/show/2"),
        AnimekoScraper.Subject("Third", "https://cdn.test/show/3")
    )
    check(AnimekoScraper.regressionParse(rows, base, cfg) == expected)
    check(AnimekoScraper.regressionParse(rows, base, cfg.copy(
        selectNamesJsonPath = "\$[*]['name']",
        selectLinksJsonPath = "\$[*]['url']"
    )) == expected)
    check(AnimekoScraper.regressionParse("[]", base, cfg).isEmpty())
    println("PASS production JSON-path: paired rows, relative URLs, deduplication, invalid entries and bracket fields")

    for (path in listOf("\$.data[*].name", "\$..name", "")) {
        check(runCatching {
            AnimekoScraper.regressionParse(rows, base, cfg.copy(selectNamesJsonPath = path))
        }.isFailure)
    }
    check(runCatching {
        AnimekoScraper.regressionParse(rows, base, cfg.copy(selectLinksJsonPath = "\$..url"))
    }.isFailure)
    for (invalid in listOf("{malformed", """{"data":[]}""")) {
        check(runCatching { AnimekoScraper.regressionParse(invalid, base, cfg) }.isFailure)
    }
    println("PASS production JSON-path: unsupported paths, malformed JSON and wrong document roots")

    val configuration = """{
        "exportedMediaSourceDataList":{"mediaSources":[{
            "factoryId":"web-selector",
            "arguments":{"name":"Controlled fixture","searchConfig":{
                "subjectFormatId":"json-path-indexed",
                "selectorSubjectFormatJsonPathIndexed":{
                    "selectNames":"$[*].name","selectLinks":"$[*].url"
                }
            }}
        }]}
    }"""
    val decoded = AnimekoScraper.parseConfig(configuration).single().cfg
    check(decoded.subjectFormatId == cfg.subjectFormatId)
    check(decoded.selectNamesJsonPath == cfg.selectNamesJsonPath)
    check(decoded.selectLinksJsonPath == cfg.selectLinksJsonPath)
    check(AnimekoScraper.regressionParse(rows, base, decoded) == expected)
    println("PASS production configuration: JSON-path selectors reach the parser")

    // 审核238：多键路径 $[*]['url','link'] —— 第一个键缺失时回退第二个键
    val multiKey = """[
        {"name":"甲","link":"https://a.test/1"},
        {"name":"乙","url":"https://b.test/2"},
        {"name":"丙"}
    ]"""
    val multiCfg = cfg.copy(
        selectNamesJsonPath = "$[*]['title','name']",
        selectLinksJsonPath = "$[*]['url','link']"
    )
    check(AnimekoScraper.regressionParse(multiKey, base, multiCfg).map { it.name } ==
        listOf("甲", "乙"))
    println("PASS production JSON-path: multi-key selectors fall back per row")

    // 审核238：linkTemplate 把裸 id 拼成详情页 URL
    val idRows = """[{"name":"丁","id":"10086"}]"""
    val templateCfg = cfg.copy(
        selectLinksJsonPath = "$[*].id",
        linkTemplateJsonPathIndexed = "https://source.test/detail/{value}.html"
    )
    check(AnimekoScraper.regressionParse(idRows, base, templateCfg).single().url ==
        "https://source.test/detail/10086.html")
    println("PASS production JSON-path: linkTemplate expands bare identifiers")

    // 无 {value} 占位符的模板不生效：回退为把原值当相对 URL 解析
    val brokenTemplate = cfg.copy(
        selectLinksJsonPath = "$[*].id",
        linkTemplateJsonPathIndexed = "https://source.test/detail/"
    )
    check(AnimekoScraper.regressionParse(idRows, base, brokenTemplate).single().url ==
        "https://source.test/catalog/10086")
    println("PASS production JSON-path: templates without a placeholder are ignored")
}
'''

configured = os.environ.get('KOTLIN_LIB')
candidates = sorted(Path.home().glob('.gradle/wrapper/dists/gradle-*/*/gradle-*/lib'))
candidates = [p for p in candidates if list(p.glob('kotlin-compiler-embeddable-*.jar'))]
if configured:
    compiler = Path(configured)
elif candidates:
    compiler = candidates[-1]
else:
    raise RuntimeError('Set KOTLIN_LIB to an installed Kotlin compiler library directory')
stdlibs = sorted(compiler.glob('kotlin-stdlib-*.jar'))
if not stdlibs:
    raise RuntimeError(f'Kotlin standard library is missing from {compiler}')
dependencies = [stdlibs[-1]]
cache = Path.home() / '.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlinx'
for artifact in ('kotlinx-serialization-json-jvm', 'kotlinx-serialization-core-jvm'):
    jars = sorted((cache / artifact / '1.7.3').glob('*/*.jar'))
    if not jars:
        raise RuntimeError(f'Missing cached project dependency: {artifact}:1.7.3')
    dependencies.append(jars[0])
classpath = os.pathsep.join(map(str, dependencies))

with tempfile.TemporaryDirectory(prefix='piliai-jsonpath-') as directory:
    temporary = Path(directory)
    test = temporary / 'JsonPathRegression.kt'
    test.write_text(program, encoding='utf-8')
    classes = temporary / 'classes'
    subprocess.run([
        'java', '-cp', str(compiler / '*'),
        'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
        '-no-stdlib', '-no-reflect', '-classpath', classpath,
        '-d', str(classes), str(test)
    ], check=True, timeout=60)
    subprocess.run([
        'java', '-cp', str(classes) + os.pathsep + classpath,
        'com.example.piliai.data.repository.JsonPathRegressionKt'
    ], check=True, timeout=30)
