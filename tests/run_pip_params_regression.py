#!/usr/bin/env python3
"""Compile production PiP helpers and event wiring against controlled JVM stubs.

These checks exercise the extracted production effect, not Compose itself.
Android lifecycle ordering, Surface rendering, PiP transitions and audiovisual
continuity still require device verification.
"""
from pathlib import Path
import os
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'android/app/src/main/kotlin/com/example/piliai/playback/VideoPlayerScreen.kt'
source = SOURCE.read_text(encoding='utf-8')
effect_marker = '    DisposableEffect(activity, playerViewRef, pipPlayer, pipLifecycleOwner, isCurrentDestination) {'
start = source.index(effect_marker) + len(effect_marker)
end = source.index('    DisposableEffect(componentActivity, viewModel) {', start)
effect = source[start:end].rstrip()
if not effect.endswith('    }'):
    raise RuntimeError('Production PiP effect boundary changed; review the extractor')
effect = effect[:-len('    }')]
helper_start = source.index('private fun PlayerView?.pipAspectRatio(): Rational {')
helper_end = source.index('/**', helper_start)
helpers = source[helper_start:helper_end].replace(
    '@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)\n', ''
)

stubs = {
    'Rational.kt': '''package android.util
class Rational(val numerator: Int, val denominator: Int) {
    init { require(denominator != 0) }
    fun asDouble(): Double = numerator.toDouble() / denominator
}
''',
    'Rect.kt': '''package android.graphics
data class Rect(val left: Int, val top: Int, val right: Int, val bottom: Int)
''',
    'PictureInPictureParams.kt': '''package android.app
import android.graphics.Rect
import android.util.Rational
class PictureInPictureParams(
    val aspectRatio: Rational?,
    val autoEnterEnabled: Boolean?,
    val sourceRectHint: Rect?
) {
    class Builder {
        private var ratio: Rational? = null
        private var autoEnter: Boolean? = null
        private var hint: Rect? = null
        fun setAspectRatio(value: Rational) = apply { ratio = value }
        fun setAutoEnterEnabled(value: Boolean) = apply { autoEnter = value }
        fun setSourceRectHint(value: Rect) = apply { hint = value }
        fun build() = PictureInPictureParams(ratio, autoEnter, hint)
    }
}
open class Activity {
    var isInPictureInPictureMode = false
    val updates = mutableListOf<PictureInPictureParams>()
    var manualEntries = 0
    open fun setPictureInPictureParams(params: PictureInPictureParams) {
        updates.add(params)
    }
    open fun enterPictureInPictureMode(params: PictureInPictureParams): Boolean {
        manualEntries += 1
        setPictureInPictureParams(params)
        return true
    }
}
''',
    'Consumer.kt': '''package androidx.core.util
fun interface Consumer<T> { fun accept(value: T) }
''',
    'PictureInPictureModeChangedInfo.kt': '''package androidx.core.app
class PictureInPictureModeChangedInfo(val isInPictureInPictureMode: Boolean)
''',
    'ComponentActivity.kt': '''package androidx.activity
import androidx.core.app.PictureInPictureModeChangedInfo
import androidx.core.util.Consumer
class ComponentActivity : android.app.Activity() {
    val modeListeners: java.util.LinkedHashSet<Consumer<PictureInPictureModeChangedInfo>> = java.util.LinkedHashSet()
    fun addOnPictureInPictureModeChangedListener(listener: Consumer<PictureInPictureModeChangedInfo>) {
        modeListeners.add(listener)
    }
    fun removeOnPictureInPictureModeChangedListener(listener: Consumer<PictureInPictureModeChangedInfo>) {
        modeListeners.remove(listener)
    }
    fun changeMode(value: Boolean) {
        isInPictureInPictureMode = value
        modeListeners.toList().forEach { it.accept(PictureInPictureModeChangedInfo(value)) }
    }
}
''',
    'Lifecycle.kt': '''package androidx.lifecycle
fun interface LifecycleEventObserver {
    fun onStateChanged(owner: Any, event: Any)
}
class Lifecycle {
    enum class State {
        DESTROYED, INITIALIZED, CREATED, STARTED, RESUMED;
        fun isAtLeast(state: State): Boolean = ordinal >= state.ordinal
    }
    var currentState = State.RESUMED
    val observers = linkedSetOf<LifecycleEventObserver>()
    fun addObserver(observer: LifecycleEventObserver) { observers.add(observer) }
    fun removeObserver(observer: LifecycleEventObserver) { observers.remove(observer) }
    fun changeState(state: State) {
        currentState = state
        observers.toList().forEach { it.onStateChanged(this, state) }
    }
}
class TestLifecycleOwner(val lifecycle: Lifecycle = Lifecycle())
''',
    'View.kt': '''package android.view
open class View {
    var width = 640
    var height = 360
    var isAttachedToWindow = true
    var windowX = 24
    var windowY = 48
    val layoutListeners = linkedSetOf<OnLayoutChangeListener>()
    val attachListeners = linkedSetOf<OnAttachStateChangeListener>()
    fun interface OnLayoutChangeListener {
        fun onLayoutChange(view: View, left: Int, top: Int, right: Int, bottom: Int,
                           oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int)
    }
    interface OnAttachStateChangeListener {
        fun onViewAttachedToWindow(view: View)
        fun onViewDetachedFromWindow(view: View)
    }
    fun getLocationInWindow(result: IntArray) {
        result[0] = windowX
        result[1] = windowY
    }
    fun addOnLayoutChangeListener(listener: OnLayoutChangeListener) { layoutListeners.add(listener) }
    fun removeOnLayoutChangeListener(listener: OnLayoutChangeListener) { layoutListeners.remove(listener) }
    fun addOnAttachStateChangeListener(listener: OnAttachStateChangeListener) { attachListeners.add(listener) }
    fun removeOnAttachStateChangeListener(listener: OnAttachStateChangeListener) { attachListeners.remove(listener) }
    fun dispatchLayout() {
        layoutListeners.toList().forEach {
            it.onLayoutChange(this, 0, 0, width, height, 0, 0, width, height)
        }
    }
    fun changeAttachment(attached: Boolean) {
        isAttachedToWindow = attached
        attachListeners.toList().forEach {
            if (attached) it.onViewAttachedToWindow(this) else it.onViewDetachedFromWindow(this)
        }
    }
}
''',
    'Player.kt': '''package androidx.media3.common
class VideoSize(
    val width: Int,
    val height: Int,
    val unappliedRotationDegrees: Int = 0,
    val pixelWidthHeightRatio: Float = 1.0f
)
interface Player {
    val isPlaying: Boolean
    val videoSize: VideoSize
    fun addListener(listener: Listener)
    fun removeListener(listener: Listener)
    interface Listener { fun onEvents(player: Player, events: Events) {} }
    class Events(private vararg val values: Int) {
        fun containsAny(vararg candidates: Int): Boolean = candidates.any { it in values }
    }
    companion object {
        const val EVENT_IS_PLAYING_CHANGED = 1
        const val EVENT_VIDEO_SIZE_CHANGED = 2
        const val EVENT_MEDIA_ITEM_TRANSITION = 3
    }
}
''',
    'PlayerView.kt': '''package androidx.media3.ui
class PlayerView(surface: android.view.View? = android.view.View()) : android.view.View() {
    var player: androidx.media3.common.Player? = null
    val videoSurfaceView: android.view.View? = surface
}
''',
}

program = r'''
import android.app.PictureInPictureParams
import android.graphics.Rect
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.TestLifecycleOwner
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.ui.PlayerView

class TestPlayer : Player {
    override var isPlaying = true
    override var videoSize = VideoSize(1920, 1080)
    val listeners = linkedSetOf<Player.Listener>()
    override fun addListener(listener: Player.Listener) { listeners.add(listener) }
    override fun removeListener(listener: Player.Listener) { listeners.remove(listener) }
    fun emit(vararg events: Int) {
        listeners.toList().forEach { it.onEvents(this, Player.Events(*events)) }
    }
}

fun mountProductionEffect(
    activity: ComponentActivity?,
    playerViewRef: PlayerView?,
    pipLifecycleOwner: TestLifecycleOwner,
    isCurrentDestination: () -> Boolean = { true }
): () -> Unit {
    val componentActivity = activity
    val pipPlayer = playerViewRef?.player
    var cleanup: (() -> Unit)? = null
    fun onDispose(block: () -> Unit) { cleanup = block }
''' + effect + r'''
    return checkNotNull(cleanup)
}
''' + helpers + r'''

fun assertRatio(params: PictureInPictureParams, expected: Double) {
    val ratio = checkNotNull(params.aspectRatio)
    check(ratio.denominator > 0)
    check(ratio.numerator.toLong() * 239 >= ratio.denominator.toLong() * 100) {
        "Final PiP rational is below the legal minimum: ${ratio.numerator}/${ratio.denominator}"
    }
    check(ratio.numerator.toLong() * 100 <= ratio.denominator.toLong() * 239) {
        "Final PiP rational is above the legal maximum: ${ratio.numerator}/${ratio.denominator}"
    }
    val actual = ratio.asDouble()
    check(kotlin.math.abs(actual - expected) <= 0.00011) {
        "Ratio $actual differs from expected $expected"
    }
}
fun expectedHint(view: android.view.View) = Rect(
    view.windowX, view.windowY,
    view.windowX + view.width, view.windowY + view.height
)

fun main() {
    val player = TestPlayer()
    val view = PlayerView().also { it.player = player }
    val surface = checkNotNull(view.videoSurfaceView)
    val activity = ComponentActivity()
    val owner = TestLifecycleOwner()
    val cleanup = mountProductionEffect(activity, view, owner)
    try {
        check(activity.updates.last().autoEnterEnabled == true)
        assertRatio(activity.updates.last(), 16.0 / 9.0)
        check(activity.updates.last().sourceRectHint == expectedHint(surface))
        check(player.listeners.size == 1 && owner.lifecycle.observers.size == 1)
        check(activity.modeListeners.size == 1)
        check(view.layoutListeners.size == 1 && surface.layoutListeners.size == 1)
        println("PASS production PiP setup: playing page enables auto-enter with video-window bounds")

        val before = activity.updates.size
        player.emit(999)
        check(activity.updates.size == before) { "Unrelated event rebuilt PiP parameters" }
        player.videoSize = VideoSize(1080, 1920)
        player.emit(Player.EVENT_VIDEO_SIZE_CHANGED)
        check(activity.updates.size == before + 1)
        assertRatio(activity.updates.last(), 1080.0 / 1920.0)
        player.videoSize = VideoSize(720, 576, pixelWidthHeightRatio = 1.2f)
        player.emit(Player.EVENT_VIDEO_SIZE_CHANGED)
        assertRatio(activity.updates.last(), 1.5)
        player.videoSize = VideoSize(1920, 1080, unappliedRotationDegrees = 90)
        player.emit(Player.EVENT_VIDEO_SIZE_CHANGED)
        assertRatio(activity.updates.last(), 9.0 / 16.0)
        player.videoSize = VideoSize(1000, 1000)
        player.emit(Player.EVENT_MEDIA_ITEM_TRANSITION)
        assertRatio(activity.updates.last(), 1.0)
        println("PASS production player events: metadata and media changes update ratio without layout changes")

        player.isPlaying = false
        player.emit(Player.EVENT_IS_PLAYING_CHANGED)
        check(activity.updates.last().autoEnterEnabled == false)
        player.isPlaying = true
        player.emit(Player.EVENT_IS_PLAYING_CHANGED)
        check(activity.updates.last().autoEnterEnabled == true)
        owner.lifecycle.changeState(Lifecycle.State.CREATED)
        check(activity.updates.last().autoEnterEnabled == false)
        owner.lifecycle.changeState(Lifecycle.State.STARTED)
        check(activity.updates.last().autoEnterEnabled == true)
        println("PASS production qualification: pause and inactive lifecycle disable automatic entry")

        activity.changeMode(true)
        check(activity.updates.last().sourceRectHint == null) {
            "Entering PiP rewrote sourceRectHint"
        }
        player.videoSize = VideoSize(800, 600)
        player.emit(Player.EVENT_VIDEO_SIZE_CHANGED)
        assertRatio(activity.updates.last(), 4.0 / 3.0)
        check(activity.updates.last().sourceRectHint == null)
        surface.windowX = 100
        surface.windowY = 200
        surface.dispatchLayout()
        check(activity.updates.last().sourceRectHint == null) {
            "PiP layout changes rewrote sourceRectHint"
        }
        player.isPlaying = false
        player.emit(Player.EVENT_IS_PLAYING_CHANGED)
        check(activity.updates.last().autoEnterEnabled == false)
        activity.changeMode(false)
        check(activity.updates.last().autoEnterEnabled == false)
        check(activity.updates.last().sourceRectHint == expectedHint(surface))
        player.isPlaying = true
        player.emit(Player.EVENT_IS_PLAYING_CHANGED)
        check(activity.updates.last().autoEnterEnabled == true)
        println("PASS production PiP mode: ratio and pause update without rewriting hints; exit recomputes qualification")

        surface.changeAttachment(false)
        check(activity.updates.last().autoEnterEnabled == false)
        check(activity.updates.last().sourceRectHint == null)
        surface.changeAttachment(true)
        check(activity.updates.last().autoEnterEnabled == true)
        surface.width = 0
        surface.dispatchLayout()
        check(activity.updates.last().autoEnterEnabled == false)
        check(activity.updates.last().sourceRectHint == null)
        surface.width = 640
        surface.dispatchLayout()
        check(activity.updates.last().autoEnterEnabled == true)
        println("PASS production view events: detached and zero-sized surfaces disable auto-enter")
    } finally {
        cleanup()
    }
    check(activity.updates.last().autoEnterEnabled == false)
    check(player.listeners.isEmpty() && owner.lifecycle.observers.isEmpty())
    check(activity.modeListeners.isEmpty())
    check(view.layoutListeners.isEmpty() && view.attachListeners.isEmpty())
    check(surface.layoutListeners.isEmpty() && surface.attachListeners.isEmpty())
    val afterCleanup = activity.updates.size
    player.emit(Player.EVENT_VIDEO_SIZE_CHANGED, Player.EVENT_IS_PLAYING_CHANGED)
    surface.dispatchLayout()
    surface.changeAttachment(false)
    owner.lifecycle.changeState(Lifecycle.State.DESTROYED)
    activity.changeMode(true)
    check(activity.updates.size == afterCleanup) { "Disposed listeners still updated PiP" }
    check(view.player === player && view.videoSurfaceView === surface)
    println("PASS production cleanup: all listeners removed, auto-enter disabled, player and surface retained")

    for ((size, expected) in listOf(
        VideoSize(0, 1080) to (16.0 / 9.0),
        VideoSize(1920, 0) to (16.0 / 9.0),
        VideoSize(1920, 1080, pixelWidthHeightRatio = Float.NaN) to (16.0 / 9.0),
        VideoSize(1920, 1080, pixelWidthHeightRatio = Float.POSITIVE_INFINITY) to (16.0 / 9.0),
        VideoSize(1920, 1080, pixelWidthHeightRatio = 0.0f) to (16.0 / 9.0),
        VideoSize(10000, 100) to 2.39,
        VideoSize(100, 10000) to (1.0 / 2.39),
        VideoSize(1920, 1080, unappliedRotationDegrees = 270) to (9.0 / 16.0)
    )) {
        player.videoSize = size
        assertRatio(view.buildPipParams(true, true), expected)
    }
    val missingView: PlayerView? = null
    val missingParams = missingView.buildPipParams(true, true)
    assertRatio(missingParams, 16.0 / 9.0)
    check(missingParams.autoEnterEnabled == false && missingParams.sourceRectHint == null)
    println("PASS production ratio boundaries: invalid metadata, rotation, extremes and absent view")

    val fallback = PlayerView(null).also { it.player = player }
    val fallbackParams = fallback.buildPipParams(true, true)
    check(fallbackParams.sourceRectHint == expectedHint(fallback))
    val sameViewHost = PlayerView()
        val sameView = PlayerView(surface = sameViewHost).also { it.player = player }
    val sameActivity = ComponentActivity()
    val sameOwner = TestLifecycleOwner()
    val sameCleanup = mountProductionEffect(sameActivity, sameView, sameOwner)
    check(sameView.layoutListeners.size == 1 && sameView.attachListeners.size == 1)
    sameCleanup()
    check(sameView.layoutListeners.isEmpty() && sameView.attachListeners.isEmpty())
    println("PASS production view selection: fallback bounds and deduplicated view listeners")

    // Navigation away must disable auto-enter even while the page lifecycle is
    // still STARTED (transitions keep it at STARTED briefly). Entering PiP on
    // the same page leaves it current, so auto-enter stays enabled there.
    run {
        var current = true
        val navPlayer = TestPlayer()
        val navView = PlayerView().also { it.player = navPlayer }
        val navActivity = ComponentActivity()
        val navOwner = TestLifecycleOwner()
        val navCleanup = mountProductionEffect(
            navActivity, navView, navOwner
        ) { current }
        try {
            check(navActivity.updates.last().autoEnterEnabled == true) {
                "Current playing destination should enable auto-enter"
            }
            current = false
            navPlayer.emit(Player.EVENT_IS_PLAYING_CHANGED)
            check(navActivity.updates.last().autoEnterEnabled == false) {
                "Navigating away must disable auto-enter even while lifecycle is STARTED"
            }
            current = true
            navPlayer.emit(Player.EVENT_VIDEO_SIZE_CHANGED)
            check(navActivity.updates.last().autoEnterEnabled == true) {
                "Returning to the destination must re-enable auto-enter"
            }
        } finally {
            navCleanup()
        }
    }
    println("PASS production navigation qualification: leaving the current destination disables auto-enter")

    surface.isAttachedToWindow = true
    player.videoSize = VideoSize(800, 600)
    player.isPlaying = false
    val manualActivity = ComponentActivity()
    manualActivity.enterPiP(view)
    check(manualActivity.manualEntries == 1)
    assertRatio(manualActivity.updates.last(), 4.0 / 3.0)
    check(manualActivity.updates.last().sourceRectHint == expectedHint(surface))
    check(manualActivity.updates.last().autoEnterEnabled == false)
    manualActivity.isInPictureInPictureMode = true
    manualActivity.enterPiP(view)
    check(manualActivity.updates.last().sourceRectHint == null)
    println("PASS production manual entry: shared ratio and bounds, paused button preserved, no PiP hint rewrite")
    println("Controlled JVM checks only; Compose, Android lifecycle ordering, actual surfaces and PiP continuity remain unverified.")
}
'''

configured = os.environ.get('KOTLIN_LIB')
# 生产代码用 Kotlin 2.x（K2）编译；回归必须用同一代编译器——K1 拒绝
# K2 数据流智能转换（VideoPlayerScreen buildPipParams 的 surface 依赖它）。
candidates = sorted(
    Path.home().glob('.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-compiler-embeddable/2.*/*/kotlin-compiler-embeddable-2*.jar'),
    reverse=True,
)
if not configured and not candidates:
    wrapper = sorted(Path.home().glob('.gradle/wrapper/dists/gradle-*/*/gradle-*/lib'))
    wrapper = [path for path in wrapper if list(path.glob('kotlin-compiler-embeddable-*.jar'))]
    if wrapper:
        candidates = [wrapper[-1] / 'kotlin-compiler-embeddable-1.9.23.jar']
if configured:
    compiler = Path(configured)
elif candidates:
    compiler = candidates[0]
else:
    raise RuntimeError('Set KOTLIN_LIB to an installed Kotlin compiler library directory')
stdlibs = sorted(compiler.glob('kotlin-stdlib-*.jar'))
if not stdlibs:
    stdlib_version = compiler.name.rsplit('-', 1)[-1].removesuffix('.jar')
    stdlibs = sorted(Path.home().glob(f'.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib/{stdlib_version}/*/kotlin-stdlib-{stdlib_version}.jar'), reverse=True)
if not stdlibs:
    stdlibs = sorted(Path.home().glob('.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib/2.*/*/kotlin-stdlib-2*.jar'), reverse=True)
if not stdlibs:
    stdlibs = sorted(Path.home().glob('.gradle/wrapper/dists/gradle-*/*/gradle-*/lib/kotlin-stdlib-*.jar'))
if not stdlibs:
    raise RuntimeError(f'Kotlin standard library is missing from {compiler}')
stdlib = stdlibs[-1]
intermediates = ROOT / '.aharou/native-build'
intermediates.mkdir(parents=True, exist_ok=True)
with tempfile.TemporaryDirectory(prefix='pip-params-regression-', dir=intermediates) as directory:
    temporary = Path(directory)
    files = []
    for name, content in stubs.items():
        path = temporary / name
        path.write_text(content, encoding='utf-8')
        files.append(path)
    test = temporary / 'PipParamsRegression.kt'
    test.write_text(program, encoding='utf-8')
    open('/tmp/gen_pip.kt','w').write(program)
    files.append(test)
    classes = temporary / 'classes'
    coroutines_compile = sorted(Path.home().glob('.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/*/*/kotlinx-coroutines-core-jvm-*.jar'))
    if not coroutines_compile:
        raise RuntimeError('Coroutine JVM library is not cached')
    subprocess.run([
        'java', '-cp', os.pathsep.join(
            ([str(compiler)] if compiler.is_file() else [str(compiler / '*')])
            + [str(stdlib)]
            + [str(jar) for jar in sorted(Path.home().glob('.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-reflect/2.*/*/kotlin-reflect-2*.jar'), reverse=True)[:1]]
            + [str(coroutines_compile[-1])]
            + [str(jar) for jar in sorted(Path.home().glob('.gradle/caches/modules-2/files-2.1/org.jetbrains/annotations/*/*/annotations-*.jar'), reverse=True)[:1]]
        ), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
        '-no-stdlib', '-no-reflect', '-classpath', os.pathsep.join([str(stdlib), str(coroutines_compile[-1])]),
        '-d', str(classes), *map(str, files),
    ], check=True, timeout=60)
    coroutines = sorted(Path.home().glob('.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/*/*/kotlinx-coroutines-core-jvm-*.jar'))
    if not coroutines:
        raise RuntimeError('Coroutine JVM library is not cached')
    subprocess.run([
        'java', '-cp', os.pathsep.join(
            [str(classes), str(stdlib), str(coroutines[-1])]
        ),
        'PipParamsRegressionKt',
    ], check=True, timeout=20)
