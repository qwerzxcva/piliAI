#!/usr/bin/env python3
"""Exercise production sync-failure translation on the JVM.

No network, Android lifecycle or playback is verified.
"""
from pathlib import Path
import os
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'android/app/src/main/kotlin/com/example/piliai/data/repository/SubscribeRepository.kt'
source = SOURCE.read_text(encoding='utf-8')

# 提取 describeSyncFailure 本体（它是纯函数：String + Throwable -> String）
start = source.index('    fun describeSyncFailure(url: String, error: Throwable): String {')
end = source.index('\n    }\n', start) + len('\n    }\n')
body = source[start:end]

MAIN = r'''
fun main() {
    fun translate(msg: String?): String {
        val error = if (msg == null) RuntimeException() else RuntimeException(msg)
        return describeSyncFailure("https://fixture.test/feed", error)
    }

    // HTTP 状态码分支
    check(translate("Request failed with status code: 403").contains("403"))
    check(translate("Request failed with status code: 403").contains("风控"))
    check(translate("Request failed with status code: 401").contains("登录"))
    check(translate("Request failed with status code: 404").contains("404"))
    check(translate("Request failed with status code: 404").contains("检查"))
    check(translate("Request failed with status code: 429").contains("频繁"))
    check(translate("Request failed with status code: 500").contains("服务器错误"))
    check(translate("Request failed with status code: 502").contains("服务器错误"))
    check(translate("Request failed with status code: 302").contains("异常状态码"))
    println("PASS sync failure: HTTP status branches")

    // 网络层分支
    check(translate("Request timeout").contains("超时"))
    check(translate("timed out").contains("超时"))
    check(translate("Unable to resolve host").contains("解析域名"))
    check(translate("UnknownHostException").contains("解析域名"))
    check(translate("Connection reset by peer").contains("连接被拒绝"))
    check(translate("Connection refused").contains("连接被拒绝"))
    println("PASS sync failure: network branches")

    // 解析层分支
    check(translate("订阅源返回为空").contains("空内容"))
    check(translate("未识别格式").contains("不是有效的订阅源"))
    check(translate("Animeko 源配置为空或格式不符").contains("不是有效的订阅源"))
    println("PASS sync failure: parse branches")

    // 兜底：原文透传 + null message
    check(translate("某种没见过的错误") == "某种没见过的错误")
    check(translate(null).contains("未知原因"))
    println("PASS sync failure: fallback passthrough and null message")
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

with tempfile.TemporaryDirectory(prefix='piliai-syncdiag-') as directory:
    temporary = Path(directory)
    test = temporary / 'SyncDiagRegression.kt'
    test.write_text(
        'package com.example.piliai.data.repository\n\n'
        'private class Holder {\n' + body + '\n}\n\n'
        'internal fun describeSyncFailure(url: String, error: Throwable): String =\n'
        '    Holder().describeSyncFailure(url, error)\n' + MAIN,
        encoding='utf-8',
    )
    classes = temporary / 'classes'
    subprocess.run([
        'java', '-cp', str(lib / '*'),
        'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
        '-no-stdlib', '-no-reflect', '-classpath', classpath,
        '-d', str(classes), str(test),
    ], check=True, timeout=120)
    subprocess.run([
        'java', '-cp', str(classes) + os.pathsep + classpath,
        'com.example.piliai.data.repository.SyncDiagRegressionKt',
    ], check=True, timeout=30)
