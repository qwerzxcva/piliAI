#!/usr/bin/env python3
"""Compile the production subscription-link classifier and assert its decisions.

No network, Room, player, or torrent client is involved.
"""
from pathlib import Path
import os
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
source = (ROOT / 'android/app/src/main/kotlin/com/example/piliai/ui/subscribe/SubscribeScreen.kt').read_text(encoding='utf-8')
start = source.index('internal enum class SubscriptionLinkKind')
end = source.index('@Composable\nprivate fun EmptySubscribeHint', start)
classifier = source[start:end].replace('withoutQuery.toUri().path', 'SyntheticUri.path(withoutQuery)')
program = r'''
object SyntheticUri {
    fun path(url: String): String? {
        val body = url.substringAfter("://", url).substringBefore('#').substringBefore('?')
        val slash = body.indexOf('/')
        return if (slash < 0) "" else body.substring(slash)
    }
}
''' + classifier + r'''
fun main() {
    val cases = mapOf(
        "" to SubscriptionLinkKind.EMPTY,
        "   " to SubscriptionLinkKind.EMPTY,
        "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567" to SubscriptionLinkKind.BITTORRENT,
        "MAGNET:?dn=Demo&xt=urn:btih:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA&tr=udp://tracker.test" to SubscriptionLinkKind.BITTORRENT,
        "magnet:?xt=urn:btih:not-a-hash" to SubscriptionLinkKind.WEB_PAGE,
        "magnet:?xt=urn:btmh:0123456789abcdef0123456789abcdef01234567" to SubscriptionLinkKind.WEB_PAGE,
        "https://fixture.test/release.torrent?token=1" to SubscriptionLinkKind.BITTORRENT,
        "https://fixture.test/page.torrent.html" to SubscriptionLinkKind.WEB_PAGE,
        "https://fixture.test/video.mp4?id=1" to SubscriptionLinkKind.DIRECT_MEDIA,
        "https://fixture.test/list.m3u8" to SubscriptionLinkKind.DIRECT_MEDIA,
        "https://fixture.test/manifest.mpd" to SubscriptionLinkKind.DIRECT_MEDIA,
        "https://fixture.test/watch/123" to SubscriptionLinkKind.WEB_PAGE,
        "https://fixture.test/player.php" to SubscriptionLinkKind.WEB_PAGE
    )
    for ((url, expected) in cases) {
        val actual = classifySubscriptionLink(url)
        check(actual == expected) { "$url classified as $actual, expected $expected" }
    }
    println("PASS production subscription link classifier: magnet, torrent, media and web page")
}
'''
configured = os.environ.get('KOTLIN_LIB')
candidates = sorted(Path.home().glob('.gradle/wrapper/dists/gradle-*/*/gradle-*/lib'))
candidates = [p for p in candidates if list(p.glob('kotlin-compiler-embeddable-*.jar'))]
if not configured and not candidates:
    raise RuntimeError('Set KOTLIN_LIB to an installed compiler library directory')
lib = Path(configured) if configured else candidates[-1]
stdlib = next(lib.glob('kotlin-stdlib-*.jar'))
with tempfile.TemporaryDirectory(prefix='piliai-subscription-link-') as directory:
    temporary = Path(directory)
    test = temporary / 'SubscriptionLinkRegression.kt'
    test.write_text(program, encoding='utf-8')
    classes = temporary / 'classes'
    subprocess.run([
        'java', '-cp', str(lib / '*'), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
        '-no-stdlib', '-no-reflect', '-classpath', str(stdlib),
        '-d', str(classes), str(test)
    ], check=True, timeout=60)
    subprocess.run([
        'java', '-cp', str(classes) + os.pathsep + str(stdlib),
        'SubscriptionLinkRegressionKt'
    ], check=True, timeout=20)
