package com.example.piliai.playback.backend

import android.net.Uri
import android.view.Surface
import kotlinx.coroutines.flow.StateFlow

/**
 * 播放后端统一接口。
 *
 * Media3 ExoPlayer 是默认实现；GpuNextBackend 走 mpv 的 vo=gpu-next
 * （libplacebo）。两个后端同一时间只运行一个，调用方通过 [id] 区分。
 * 弹幕、字幕、手势与历史进度不在这里，由播放器层统一处理。
 */
interface PlaybackBackend {

    val id: String

    /** 播放状态流，用于 UI 订阅 */
    val isPlayingFlow: StateFlow<Boolean>

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

/** 播放事件监听器，后端通过此接口向 ViewModel 报告状态变化 */
interface PlaybackEventListener {
    fun onIsPlayingChanged(isPlaying: Boolean)
    fun onIsLoadingChanged(isLoading: Boolean)
    fun onBuffering(isBuffering: Boolean)
    fun onDurationChanged(durationMs: Long)
    fun onPositionChanged(positionMs: Long)
    fun onPlaybackEnded()
    fun onError(message: String)
    fun onVideoSizeChanged(width: Int, height: Int)
}

/**
 * PlaybackBackends: 后端选择与回退逻辑。
 */
object PlaybackBackends {
    /**
     * 标准化后端 ID：gpu-next 不可用时自动回退到 media3。
     */
    fun resolve(requested: String): String = when (requested) {
        "gpu-next" -> if (GpuNextBackend.isAvailable()) "gpu-next" else "media3"
        else -> "media3"
    }
}
