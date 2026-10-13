#!/usr/bin/env python3
"""Exercise production Referer selection rules (bv PlaybackRequestHeaders port) on the JVM.

This does not verify Android UI, HTTP requests, playback, or GPU-next.
"""
from pathlib import Path
import os
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'android/app/src/main/kotlin/com/example/piliai/playback/PlaybackRequestHeaders.kt'
source = SOURCE.read_text(encoding='utf-8')

MAIN = r'''
fun main() {
    val referer = "https://www.bilibili.com"

    // WEB/DASH 地址：必须带 web Referer（否则 CDN 403）
    check(PlaybackRequestHeaders.refererFor(referer, "https://cdn.test/a.m4s?bvid=BV1&expires=1") == referer)
    println("PASS Referer kept for plain WEB/DASH media URLs")

    // APP 签名地址：platform=android → 不能带 web Referer
    check(PlaybackRequestHeaders.refererFor(referer, "https://cdn.test/a.m4s?platform=android&expires=1") == null)
    check(PlaybackRequestHeaders.refererFor(referer, "https://cdn.test/a.m4s?platform=android_b?expires=1") == null)
    println("PASS Referer dropped for APP-signed media URLs")

    // 任意一个 URL 命中 APP 签名就整体不带（bv: urls.any(...)）
    check(
        PlaybackRequestHeaders.refererFor(
            referer,
            "https://cdn.test/a.m4s",
            "https://cdn.test/b.m4s?platform=android"
        ) == null
    )
    println("PASS Referer dropped when any URL is APP-signed")

    // 嵌套 url= 参数也识别（bv nestingDepth <= 1）
    check(
        PlaybackRequestHeaders.refererFor(
            referer,
            "https://player.test/?url=https%3A%2F%2Fcdn.test%2Fa.m4s%3Fplatform%3Dandroid"
        ) == null
    )
    println("PASS Referer dropped through nested url= parameter")

    // 超过一层嵌套不再递归（bv nestingDepth >= 1 直接返回 false）
    check(
        PlaybackRequestHeaders.refererFor(
            referer,
            "https://p.test/?url=https%3A%2F%2Fq.test%2F%3Furl%3Dhttps%253A%252F%252Fcdn.test%252F%3Fplatform%253Dandroid"
        ) == referer
    )
    println("PASS Referer kept beyond one nesting level")

    // 大小写不敏感
    check(PlaybackRequestHeaders.refererFor(referer, "https://cdn.test/a?Platform=Android") == null)
    println("PASS Referer rule is case-insensitive")

    // 未配置 Referer 时返回 null（不凭空造）
    check(PlaybackRequestHeaders.refererFor("", "https://cdn.test/a") == null)
    check(PlaybackRequestHeaders.refererFor(null, "https://cdn.test/a") == null)
    println("PASS Referer null when unconfigured")

    // 无 query 的 URL 不炸
    check(PlaybackRequestHeaders.refererFor(referer, "https://cdn.test/a.m3u8") == referer)
    check(PlaybackRequestHeaders.refererFor(referer, "not-a-url") == referer)
    println("PASS Referer rule tolerates queryless and malformed URLs")
}
'''

configured = os.environ.get('KOTLIN_LIB')
candidates = sorted(Path.home().glob('.gradle/wrapper/dists/gradle-*/*/gradle-*/lib'))
candidates = [p for p in candidates if list(p.glob('kotlin-compiler-embeddable-*.jar'))]
lib = Path(configured) if configured else (candidates[-1] if candidates else None)
if lib is None:
    raise RuntimeError('Set KOTLIN_LIB to an installed Kotlin compiler library directory')
stdlib = next(lib.glob('kotlin-stdlib-*.jar'))
classpath = str(stdlib)

with tempfile.TemporaryDirectory(prefix='piliai-referer-') as directory:
    temporary = Path(directory)
    test = temporary / 'RefererRegression.kt'
    test.write_text(source + MAIN, encoding='utf-8')
    classes = temporary / 'classes'
    subprocess.run([
        'java', '-cp', str(lib / '*'),
        'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
        '-no-stdlib', '-no-reflect', '-classpath', classpath,
        '-d', str(classes), str(test),
    ], check=True, timeout=120)
    subprocess.run([
        'java', '-cp', str(classes) + os.pathsep + classpath,
        'com.example.piliai.playback.RefererRegressionKt',
    ], check=True, timeout=30)
