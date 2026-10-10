package com.example.piliai.playback.backend

import android.net.Uri
import android.view.Surface

/**
 * 播放后端统一接口。
 *
 * Media3 ExoPlayer 是默认实现；GpuNextBackend 走 mpv 的 vo=gpu-next
 * （libplacebo）。两个后端同一时间只运行一个，调用方通过 [id] 区分。
 * 弹幕、字幕、手势与历史进度不在这里，由播放器层统一处理。
 */
interface PlaybackBackend {

    val id: String

    fun prepare(request: PlaybackRequest)

    fun play()

    fun pause()

    fun seekTo(positionMs: Long)

    fun setSpeed(speed: Float)

    fun setVolume(volume: Float)

    fun attachSurface(surface: Surface)

    fun detachSurface()

    fun release()

    fun positionMs(): Long

    fun durationMs(): Long

    fun isPlaying(): Boolean
}

/** 一次起播需要的全部信息，两个后端共用。 */
data class PlaybackRequest(
    val videoUri: Uri,
    val audioUri: Uri? = null,
    val headers: Map<String, String> = emptyMap(),
    val userAgent: String = "",
    val resumePositionMs: Long = 0L,
    val playWhenReady: Boolean = true
)

/**
 * GPU-next 后端。
 *
 * 渲染由 libmpv 的 `vo=gpu-next` 完成，依赖 libplacebo（第三方 C/C++，
 * 只编译 arm64-v8a）。本类只负责把 Kotlin 侧的播放控制翻译成 mpv 属性与
 * 命令；libmpv 本身由 [GpuNextBridge] 提供，未打包时 [isAvailable] 为 false，
 * 调用方应回退到 Media3。
 */
class GpuNextBackend(private val bridge: GpuNextBridge) : PlaybackBackend {

    override val id: String = ID

    override fun prepare(request: PlaybackRequest) {
        bridge.open()
        bridge.setOption("vo", "gpu-next")
        bridge.setOption("hwdec", "auto-safe")
        bridge.setOption("gpu-context", "android")
        if (request.userAgent.isNotBlank()) bridge.setOption("user-agent", request.userAgent)
        request.headers["Referer"]?.let { bridge.setOption("referrer", it) }
        bridge.initialize()
        bridge.command("loadfile", request.videoUri.toString(), "replace")
        if (request.resumePositionMs > 0L) {
            bridge.setProperty("start", (request.resumePositionMs / 1000.0).toString())
        }
        bridge.setProperty("pause", if (request.playWhenReady) "no" else "yes")
    }

    override fun play() = bridge.setProperty("pause", "no")

    override fun pause() = bridge.setProperty("pause", "yes")

    override fun seekTo(positionMs: Long) =
        bridge.command("seek", (positionMs / 1000.0).toString(), "absolute")

    override fun setSpeed(speed: Float) = bridge.setProperty("speed", speed.toString())

    override fun setVolume(volume: Float) =
        bridge.setProperty("volume", (volume.coerceIn(0f, 1f) * 100f).toInt().toString())

    override fun attachSurface(surface: Surface) = bridge.attachSurface(surface)

    override fun detachSurface() = bridge.detachSurface()

    override fun release() = bridge.release()

    override fun positionMs(): Long = bridge.longProperty("time-pos").times(1000.0).toLong()

    override fun durationMs(): Long = bridge.longProperty("duration").times(1000.0).toLong()

    override fun isPlaying(): Boolean = bridge.stringProperty("pause") == "no"

    companion object {
        const val ID = "gpu-next"

        // A loadable library does not prove bridge initialization or renderer support.
        // Keep disabled until the native backend and surface lifecycle are integrated.
        fun isAvailable(): Boolean = false
    }
}

/**
 * libmpv 的最小控制面。真正的 JNI 绑定在 libmpv 编进工程后实现；
 * 现在给出的是明确的契约，避免播放器层直接依赖 mpv 的 C API。
 */
interface GpuNextBridge {
    /** Creates mpv before pre-initialize options are applied. */
    fun open()
    /** Initializes mpv after renderer and Android context options are set. */
    fun initialize()
    fun setOption(name: String, value: String)
    fun setProperty(name: String, value: String)
    fun stringProperty(name: String): String
    fun longProperty(name: String): Double
    fun command(vararg args: String)
    fun attachSurface(surface: Surface)
    fun detachSurface()
    fun release()
}

/** 按持久化的后端 id 选择实现；gpu-next 不可用时回退 Media3。 */
object PlaybackBackends {
    const val MEDIA3 = "media3"

    fun resolve(requested: String): String =
        if (requested == GpuNextBackend.ID && GpuNextBackend.isAvailable()) {
            GpuNextBackend.ID
        } else {
            MEDIA3
        }
}
