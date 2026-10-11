package com.example.piliai.playback.backend

import android.net.Uri
import android.view.Surface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * GPU-next 后端。
 *
 * 渲染由 libmpv 的 `vo=gpu-next` 完成，依赖 libplacebo（第三方 C/C++，
 * 只编译 arm64-v8a）。本类只负责把 Kotlin 侧的播放控制翻译成 mpv 属性与
 * 命令；libmpv 本身由 [RustGpuNextBridge] 提供，未打包时 [isAvailable] 为 false，
 * 调用方应回退到 Media3。
 */
class GpuNextBackend(
    private val listener: PlaybackEventListener
) : PlaybackBackend {

    override val id: String = ID

    private val _isPlaying = MutableStateFlow(false)
    override val isPlayingFlow: StateFlow<Boolean> = _isPlaying

    // 实际使用单例 RustGpuNextBridge（所有操作最终走 JNI）
    private val bridge: GpuNextBridge = RustGpuNextBridge as GpuNextBridge

    override fun prepare(request: PlaybackRequest) {
        bridge.open()
        bridge.setOption("vo", "gpu-next")
        bridge.setOption("hwdec", "auto-safe")
        bridge.setOption("gpu-context", "android")
        if (request.userAgent.isNotBlank()) bridge.setOption("user-agent", request.userAgent)
        request.headers["Referer"]?.let { bridge.setOption("referrer", it) }
        request.audioUri?.let { bridge.setOption("audio-files", it.toString()) }
        if (request.resumePositionMs > 0L) {
            bridge.setOption("start", (request.resumePositionMs / 1000.0).toString())
        }
        bridge.initialize()
        bridge.command("loadfile", request.videoUri.toString(), "replace")
        val shouldPause = !request.playWhenReady
        bridge.setProperty("pause", if (shouldPause) "yes" else "no")
        _isPlaying.value = request.playWhenReady
        if (request.playWhenReady) {
            listener.onIsPlayingChanged(true)
        }
    }

    override fun play() {
        bridge.setProperty("pause", "no")
        _isPlaying.value = true
        listener.onIsPlayingChanged(true)
    }

    override fun pause() {
        bridge.setProperty("pause", "yes")
        _isPlaying.value = false
        listener.onIsPlayingChanged(false)
    }

    override fun seekTo(positionMs: Long) {
        bridge.command("seek", (positionMs / 1000.0).toString(), "absolute")
        listener.onPositionChanged(positionMs)
    }

    override fun setSpeed(speed: Float) {
        bridge.setProperty("speed", speed.toString())
    }

    override fun setVolume(volume: Float) {
        bridge.setProperty("volume", (volume.coerceIn(0f, 1f) * 100f).toInt().toString())
    }

    override fun attachSurface(surface: Surface) {
        bridge.attachSurface(surface)
    }

    override fun detachSurface() {
        bridge.detachSurface()
    }

    override fun release() {
        bridge.release()
    }

    override fun positionMs(): Long = bridge.longProperty("time-pos").times(1000.0).toLong()

    override fun durationMs(): Long = bridge.longProperty("duration").times(1000.0).toLong()

    override fun isPlaying(): Boolean = bridge.stringProperty("pause") == "no"

    companion object {
        const val ID = "gpu-next"

        fun isAvailable(): Boolean = RustGpuNextBridge.libraryLoaded
    }
}
