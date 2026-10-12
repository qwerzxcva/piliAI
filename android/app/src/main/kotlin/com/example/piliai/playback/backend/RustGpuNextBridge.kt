package com.example.piliai.playback.backend

import android.os.Looper
import android.view.Surface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * libmpv client-API bridge. Kotlin only sees an opaque registry token.
 *
 * Loading libpilinara_native is not libmpv linkage and is not GPU-next.
 * [GpuNextBackend.isAvailable] stays false. This class does not set vo=gpu-next
 * and does not claim that a surface can be rendered.
 *
 * Synchronous native work runs on a dedicated worker with a finite wait.
 * [nativePollEvent] always receives a non-negative timeout capped at
 * [MAX_WAIT_MS]. A failed create throws and does not keep a token.
 */
class RustGpuNextBridge : GpuNextBridge {
    private val token = AtomicReference(0L)

    override fun setOption(name: String, value: String) {
        call("setOption") { nativeSetOption(requireToken(), name, value) }
    }

    override fun setProperty(name: String, value: String) {
        call("setProperty") { nativeSetProperty(requireToken(), name, value) }
    }

    override fun stringProperty(name: String): String {
        val value = call("stringProperty") { nativeStringProperty(requireToken(), name) }
        if (value == null) {
            throw IllegalStateException("libmpv returned no string for $name")
        }
        return value
    }

    override fun longProperty(name: String): Double {
        val value = call("longProperty") { nativeDoubleProperty(requireToken(), name) }
        if (value.isNaN()) {
            throw IllegalStateException("libmpv returned no number for $name")
        }
        return value
    }

    override fun command(vararg args: String) {
        if (args.isEmpty() || args.any { it.isEmpty() }) {
            throw IllegalArgumentException("command")
        }
        call("command") { nativeCommand(requireToken(), args) }
    }

    override fun attachSurface(surface: Surface) {
        if (!surface.isValid) {
            throw IllegalArgumentException("surface")
        }
        call("attachSurface") { nativeAttachSurface(requireToken(), surface) }
    }

    override fun detachSurface() {
        call("detachSurface") { nativeDetachSurface(requireToken()) }
    }

    /**
     * One bounded event read. Negative timeouts are rejected. The native side
     * also rejects a non-finite or negative wait, so this cannot block forever.
     */
    override fun pollEvent(timeoutMs: Int): Int {
        if (timeoutMs < 0) {
            throw IllegalArgumentException("timeout")
        }
        val bounded = timeoutMs.coerceAtMost(MAX_WAIT_MS)
        return call("pollEvent") { nativePollEvent(requireToken(), bounded) }
    }

    /**
     * `mpv_observe_property`。format 必须是 client.h 的 MPV_FORMAT_* 常量。
     * 必须在 [initialize] 之后调用：libmpv 在 mpv_initialize 前会拒绝 observe。
     */
    override fun observeProperty(name: String, format: Int) {
        if (name.isEmpty()) {
            throw IllegalArgumentException("name")
        }
        call("observeProperty") { nativeObserveProperty(requireToken(), name, format) }
    }

    override fun release() {
        val current = token.get()
        if (current == 0L) {
            return
        }
        // The worker timeout does not cancel nativeRelease. Clear the Kotlin
        // token only after nativeRelease returns. A thrown failure keeps it.
        call("release") { nativeRelease(current) }
        token.compareAndSet(current, 0L)
    }

    /**
     * `mpv_create` only. Renderer and other options must be set with
     * [setOption] before [initialize]. This does not set vo=gpu-next.
     */
    override fun open() {
        if (token.get() != 0L) {
            throw IllegalStateException("mpv instance is already open")
        }
        if (!libraryLoaded) {
            throw IllegalStateException("pilinara_native is not loaded")
        }
        val created = call("open") { nativeCreate() }
        if (created == 0L) {
            throw IllegalStateException("libmpv create failed")
        }
        if (!token.compareAndSet(0L, created)) {
            throw IllegalStateException("mpv instance is already open")
        }
    }

    /** `mpv_initialize`. Options set after this are rejected by the registry. */
    override fun initialize() {
        call("initialize") { nativeInitialize(requireToken()) }
    }

    private fun requireToken(): Long {
        val current = token.get()
        if (current == 0L) {
            throw IllegalStateException("mpv instance is not open")
        }
        return current
    }

    private fun <T> call(operation: String, body: () -> T): T {
        val failure = AtomicReference<Throwable>()
        val value = AtomicReference<T>()
        val done = CountDownLatch(1)
        workerExecutor().execute {
            try {
                value.set(body())
            } catch (error: Throwable) {
                failure.set(error)
            } finally {
                done.countDown()
            }
        }
        if (!done.await(CALL_WAIT_MS, TimeUnit.MILLISECONDS)) {
            // The native call is still running and still owns its token and
            // Surface. This timeout does not cancel, destroy, or drop it.
            throw IllegalStateException(
                "$operation still running after ${CALL_WAIT_MS}ms; native state was not released"
            )
        }
        failure.get()?.let { throw it }
        return value.get()
    }

    private external fun nativeCreate(): Long

    private external fun nativeInitialize(token: Long)

    private external fun nativeSetOption(token: Long, name: String, value: String)

    private external fun nativeSetProperty(token: Long, name: String, value: String)

    private external fun nativeStringProperty(token: Long, name: String): String?

    private external fun nativeDoubleProperty(token: Long, name: String): Double

    private external fun nativeCommand(token: Long, args: Array<out String>)

    private external fun nativeAttachSurface(token: Long, surface: Surface)

    private external fun nativeDetachSurface(token: Long)

    private external fun nativeRelease(token: Long)

    private external fun nativePollEvent(token: Long, timeoutMs: Int): Int

    private external fun nativeObserveProperty(token: Long, name: String, format: Int)

    companion object {
        const val MAX_WAIT_MS = 2_000

        /**
         * How long the Kotlin caller waits for the worker. This is not a
         * cancellation deadline for libmpv.
         */
        private const val CALL_WAIT_MS = 5_000L

        /**
         * True only after System.loadLibrary returns. A loaded pilinara_native
         * still does not prove that libmpv is packaged or that GPU-next renders.
         */
        @Volatile
        var libraryLoaded: Boolean = false
            private set

        private val executorRef = AtomicReference<ExecutorService?>()

        init {
            libraryLoaded = runCatching {
                System.loadLibrary("pilinara_native")
                true
            }.getOrDefault(false)
        }

        private fun workerExecutor(): ExecutorService {
            executorRef.get()?.let { return it }
            val created = Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "gpu-next-bridge").apply { isDaemon = true }
            }
            if (!executorRef.compareAndSet(null, created)) {
                created.shutdown()
            }
            return executorRef.get() ?: created
        }

        /** Test hook: the bridge worker must not be the Android main looper. */
        internal fun workerIsMainThread(): Boolean {
            val answer = AtomicReference(false)
            val done = CountDownLatch(1)
            workerExecutor().execute {
                answer.set(Looper.myLooper() == Looper.getMainLooper())
                done.countDown()
            }
            check(done.await(MAX_WAIT_MS.toLong(), TimeUnit.MILLISECONDS)) {
                "bridge worker did not start"
            }
            return answer.get()
        }
    }
}
