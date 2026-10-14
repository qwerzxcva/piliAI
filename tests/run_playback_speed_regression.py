#!/usr/bin/env python3
"""Compile production playback-speed methods and test controlled coroutine ordering.

Only synthetic preferences and JVM player/lifecycle stubs are used. These checks
 do not verify Android lifecycle, real DataStore persistence, decoding, or PiP.
"""
from pathlib import Path
import os
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'android/app/src/main/kotlin/com/example/piliai/playback/VideoPlayerViewModel.kt'
source = SOURCE.read_text(encoding='utf-8')


def method(declaration):
    start = source.index('    ' + declaration)
    end = source.index('\n    }', start) + len('\n    }')
    return source[start:end]


fields = '\n'.join(
    next(line for line in source.splitlines() if line.strip().startswith(declaration))
    for declaration in (
        'private var playbackSpeedRestoreJob:',
        'private var playbackSpeedGeneration =',
    )
)
production = '\n\n'.join(method(declaration) for declaration in (
    'fun setPlaybackSpeed(',
    'private fun normalizePlaybackSpeed(',
    'private fun applyPlaybackSpeed(',
    'fun restorePlaybackSpeed(',
    'override fun onCleared(',
))

stubs = {
    'ViewModel.kt': '''package androidx.lifecycle
open class ViewModel {
    protected open fun onCleared() {}
}
''',
    'VideoSeekBus.kt': '''package com.example.piliai.playback

/**
 * 审核302：onCleared 会注销空降助手的 seek 通道（审核295 加的）。
 * 回归脚本只编译抽取出来的方法，不引入整个工程，所以要给这个符号一个
 * stub——否则 onCleared 编译不过，CI 的 Build Debug APK step 会红。
 */
object VideoSeekBus {
    var unregisterCalls = 0
    fun unregister() {
        unregisterCalls += 1
    }
}
''',
    'PlaybackParameters.kt': '''package androidx.media3.common
class PlaybackParameters(val speed: Float, val pitch: Float = 1.0f)
''',
    'Log.kt': '''package android.util
object Log {
    val warnings = mutableListOf<String>()
    fun w(tag: String, message: String, error: Throwable): Int {
        warnings.add(message)
        return 0
    }
}
''',
    'StorageManager.kt': '''package com.example.piliai.utils
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class StorageManager {
    var pendingRead = CompletableDeferred<Float>()
    var reads = 0
    val writes = mutableListOf<Float>()
    val playbackSpeedFlow: Flow<Float> = flow {
        reads += 1
        val response = pendingRead
        emit(response.await())
    }
    suspend fun setPlaybackSpeed(speed: Float) {
        writes.add(speed)
    }
    companion object {
        lateinit var instance: StorageManager
        fun getInstance(context: Any): StorageManager = instance
    }
}
''',
}

program = r'''
import androidx.media3.common.PlaybackParameters
import com.example.piliai.utils.StorageManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

object BackendTracker { var releasedCount = 0 }
class FakeBackend {
    var speed: Float = 1.0f
    @JvmName("applySpeed")
    fun setSpeed(speed: Float) { this.speed = speed }
    fun release() { BackendTracker.releasedCount += 1 }
}
class FakePlayer(initialSpeed: Float) {
    var playbackParameters = PlaybackParameters(initialSpeed, 0.8f)
    var released = false
    fun removeListener(listener: Any) {}
    fun release() { released = true }
}
class FakeExecutor {
    var shutDown = false
    fun shutdown() { shutDown = true }
}
class FakeDispatcher {
    var cancelled = false
    val executorService = FakeExecutor()
    fun cancelAll() { cancelled = true }
}
class FakeConnectionPool {
    var evicted = false
    fun evictAll() { evicted = true }
}
class FakeHttpClient {
    val dispatcher = FakeDispatcher()
    val connectionPool = FakeConnectionPool()
}
data class SpeedState(val playbackSpeed: Float)

class SpeedHarness(initialSpeed: Float = 1.0f) : androidx.lifecycle.ViewModel() {
    private val owner = SupervisorJob()
    val viewModelScope = CoroutineScope(owner + Dispatchers.Unconfined)
    private val appContext = Any()
    val fixturePlayer = FakePlayer(initialSpeed)
    private var _player: FakePlayer? = fixturePlayer
    var backend: FakeBackend? = FakeBackend()
    private var clockJob: Job? = null
    private val _state = MutableStateFlow(SpeedState(initialSpeed))
    private var completionJob: Job? = null
    private var loadJob: Job? = null
    private var subtitleListJob: Job? = null
    private var subtitleBodyJob: Job? = null
    val sourceHttpClient = FakeHttpClient()
''' + fields + r'''
    val speed: Float get() = _state.value.playbackSpeed
    val restoreJob: Job? get() = playbackSpeedRestoreJob
    fun clearForRegression() {
        onCleared()
        owner.cancel()
    }
''' + production + r'''
}

fun fixture(initialSpeed: Float = 1.0f): Pair<StorageManager, SpeedHarness> {
    val storage = StorageManager()
    StorageManager.instance = storage
    return storage to SpeedHarness(initialSpeed)
}
fun assertSpeed(harness: SpeedHarness, expected: Float) {
    check(harness.speed == expected) {
        "UI speed ${harness.speed}, expected $expected"
    }
    harness.backend?.let { check(it.speed == expected) { "Backend speed differs from expected $expected" } }
    check(harness.fixturePlayer.playbackParameters.pitch == 0.8f) {
        "Changing speed unexpectedly changed pitch"
    }
}

fun main() = runBlocking {
    for ((stored, expected) in listOf(1.5f to 1.5f, 0.25f to 0.5f, 3.0f to 2.0f, 1.0f to 1.0f)) {
        val (storage, harness) = fixture(initialSpeed = 2.0f)
        try {
            harness.restorePlaybackSpeed()
            check(storage.reads == 1)
            storage.pendingRead.complete(stored)
            harness.restoreJob!!.join()
            assertSpeed(harness, expected)
            check(storage.writes.isEmpty()) { "Restoring preferences must not write preferences" }
        } finally { harness.clearForRegression() }
    }
    println("PASS production restore: normalized persisted values, including 1x, apply without writes")

    run {
        val (storage, harness) = fixture()
        try {
            harness.restorePlaybackSpeed()
            val originalJob = harness.restoreJob!!
            harness.restorePlaybackSpeed()
            check(harness.restoreJob === originalJob && storage.reads == 1) {
                "Concurrent restore requests started another preference read"
            }
            storage.pendingRead.complete(1.5f)
            originalJob.join()
            assertSpeed(harness, 1.5f)
            check(storage.writes.isEmpty())
        } finally { harness.clearForRegression() }
    }
    println("PASS production restore: duplicate pending requests share one read")

    for (selected in listOf(1.0f, 2.0f)) {
        val (storage, harness) = fixture()
        try {
            harness.restorePlaybackSpeed()
            val oldJob = harness.restoreJob!!
            harness.setPlaybackSpeed(selected)
            check(oldJob.isCancelled) { "Manual choice did not cancel pending restoration" }
            storage.pendingRead.complete(1.5f)
            oldJob.join()
            yield()
            assertSpeed(harness, selected)
            check(storage.writes == listOf(selected)) {
                "Expected only the explicit manual preference write: ${storage.writes}"
            }
            harness.restorePlaybackSpeed()
            check(storage.reads == 1) { "Later restoration ignored an authoritative manual choice" }
        } finally { harness.clearForRegression() }
    }
    println("PASS production manual choice: delayed restoration cannot overwrite it, including manual 1x")

    run {
        val (storage, harness) = fixture(initialSpeed = 1.5f)
        try {
            harness.restorePlaybackSpeed()
            val oldJob = harness.restoreJob!!
            harness.setPlaybackSpeed(3.0f, raw = true)
            check(oldJob.isCancelled)
            storage.pendingRead.complete(2.0f)
            oldJob.join()
            yield()
            assertSpeed(harness, 3.0f)
            check(storage.writes.isEmpty()) { "Temporary 3x was persisted" }
            harness.setPlaybackSpeed(1.5f, raw = true)
            yield()
            assertSpeed(harness, 1.5f)
            check(storage.writes.isEmpty()) { "Finishing temporary speed wrote preferences" }
            harness.restorePlaybackSpeed()
            check(storage.reads == 1)
        } finally { harness.clearForRegression() }
    }
    println("PASS production temporary speed: pending restore is cancelled; 3x and raw restoration never persist")

    run {
        val (storage, harness) = fixture()
        try {
            harness.setPlaybackSpeed(1.5f)
            harness.restorePlaybackSpeed()
            check(storage.reads == 0) { "Restoration started after a speed operation" }
            assertSpeed(harness, 1.5f)
            check(storage.writes == listOf(1.5f))
        } finally { harness.clearForRegression() }
    }
    println("PASS production ordering: a speed operation before restoration prevents the initial read")

    run {
        val (storage, harness) = fixture()
        try {
            val warningsBefore = android.util.Log.warnings.size
            harness.restorePlaybackSpeed()
            storage.pendingRead.completeExceptionally(IllegalStateException("Controlled preference read failure"))
            harness.restoreJob!!.join()
            assertSpeed(harness, 1.0f)
            check(storage.writes.isEmpty())
            check(android.util.Log.warnings.size == warningsBefore + 1)
            storage.pendingRead = CompletableDeferred()
            harness.restorePlaybackSpeed()
            check(storage.reads == 2)
            storage.pendingRead.complete(1.5f)
            harness.restoreJob!!.join()
            assertSpeed(harness, 1.5f)
            check(storage.writes.isEmpty())
        } finally { harness.clearForRegression() }
    }
    println("PASS production preference failure: leaves speed intact, reports failure, and allows retry")

    run {
        val (storage, harness) = fixture()
        val warningsBefore = android.util.Log.warnings.size
        harness.restorePlaybackSpeed()
        val pendingJob = harness.restoreJob!!
        harness.clearForRegression()
        check(pendingJob.isCancelled) { "Cleanup did not cancel pending restoration" }
        storage.pendingRead.complete(2.0f)
        pendingJob.join()
        yield()
        assertSpeed(harness, 1.0f)
        check(storage.writes.isEmpty())
        check(android.util.Log.warnings.size == warningsBefore) {
            "Cancellation was incorrectly reported as a preference failure: " + android.util.Log.warnings.drop(warningsBefore)
        }
        check(harness.backend == null)
        check(BackendTracker.releasedCount > 0) { "Backend was not released" }
    }
    println("PASS production cleanup: pending restoration is cancelled without late application or persistence")
    println("Controlled JVM checks only; real DataStore, Android lifecycle, gestures and PiP remain unverified.")
}
'''

configured = os.environ.get('KOTLIN_LIB')
candidates = sorted(Path.home().glob('.gradle/wrapper/dists/gradle-*/*/gradle-*/lib'))
candidates = [path for path in candidates if list(path.glob('kotlin-compiler-embeddable-*.jar'))]
if configured:
    compiler = Path(configured)
elif candidates:
    compiler = candidates[-1]
else:
    raise RuntimeError('Set KOTLIN_LIB to an installed Kotlin compiler library directory')
stdlibs = sorted(compiler.glob('kotlin-stdlib-*.jar'))
if not stdlibs:
    raise RuntimeError(f'Kotlin standard library is missing from {compiler}')
coroutines = sorted(compiler.glob('kotlinx-coroutines-core-jvm-*.jar'))
if not coroutines:
    coroutines = sorted((Path.home() / '.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm').glob('*/*/*.jar'))
if not coroutines:
    raise RuntimeError('Coroutine JVM library is not cached')
classpath = os.pathsep.join(map(str, (stdlibs[-1], coroutines[-1])))
intermediates = ROOT / '.aharou/native-build'
intermediates.mkdir(parents=True, exist_ok=True)
with tempfile.TemporaryDirectory(prefix='playback-speed-regression-', dir=intermediates) as directory:
    temporary = Path(directory)
    files = []
    for name, content in stubs.items():
        path = temporary / name
        path.write_text(content, encoding='utf-8')
        files.append(path)
    test = temporary / 'PlaybackSpeedRegression.kt'
    test.write_text(program, encoding='utf-8')
    open('/tmp/gen_real.kt','w').write(program)
    files.append(test)
    classes = temporary / 'classes'
    subprocess.run([
        'java', '-cp', str(compiler / '*'), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
        '-no-stdlib', '-no-reflect', '-classpath', classpath,
        '-d', str(classes), *map(str, files),
    ], check=True, timeout=60)
    subprocess.run([
        'java', '-cp', str(classes) + os.pathsep + classpath,
        'PlaybackSpeedRegressionKt',
    ], check=True, timeout=20)
