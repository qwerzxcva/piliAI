package com.example.piliai.playback.backend

import android.content.Context
import android.net.Uri
import android.view.Surface
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Media3 ExoPlayer 后端实现。
 * 
 * 包装现有 ExoPlayer 逻辑，实现统一的 PlaybackBackend 接口。
 * 硬件解码、低功耗、兼容性最佳。
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class Media3Backend(
    private val context: Context,
    private val listener: PlaybackEventListener
) : PlaybackBackend {

    override val id: String = ID

    private var player: ExoPlayer? = null
    private val _isPlaying = MutableStateFlow(false)
    override val isPlayingFlow: StateFlow<Boolean> = _isPlaying

    init {
        // 审核：AudioAttributes 必须在 Builder 设置（setter 已移除）
        val audioAttributes = androidx.media3.common.AudioAttributes.Builder()
            .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MOVIE)
            .setUsage(androidx.media3.common.C.USAGE_MEDIA)
            .build()

        player = ExoPlayer.Builder(context)
            .setAudioAttributes(audioAttributes, true)
            .build()
            .apply {
                addListener(ExoPlayerListener())
            }
    }

    override fun prepare(request: PlaybackRequest) {
        val p = player ?: return

        val isLocal = !request.videoUri.toString().startsWith("http")
        val localFactory = DefaultDataSource.Factory(context)

        // HTTP 数据源：自定义 headers
        val httpFactory = DefaultHttpDataSource.Factory().apply {
            if (request.userAgent.isNotEmpty()) {
                setUserAgent(request.userAgent)
            }
            setAllowCrossProtocolRedirects(true)
            if (request.headers.isNotEmpty()) {
                setDefaultRequestProperties(request.headers)
            }
        }

        val dataSourceFactory = if (isLocal) localFactory else DefaultDataSource.Factory(context, httpFactory)

        // 视频流
        val videoItem = MediaItem.Builder().setUri(request.videoUri).build()
        val videoSource = ProgressiveMediaSource.Factory(dataSourceFactory)
            .createMediaSource(videoItem)

        // 音频流（可选，DASH 分离流）
        val finalSource = if (request.audioUri != null) {
            val audioItem = MediaItem.Builder().setUri(request.audioUri).build()
            val audioSource = ProgressiveMediaSource.Factory(dataSourceFactory)
                .createMediaSource(audioItem)
            MergingMediaSource(videoSource, audioSource)
        } else {
            videoSource
        }

        p.setMediaSource(finalSource, request.resumePositionMs)
        p.playWhenReady = request.playWhenReady
        p.prepare()
    }

    override fun play() {
        player?.play()
        _isPlaying.value = true
    }

    override fun pause() {
        player?.pause()
        _isPlaying.value = false
    }

    override fun seekTo(positionMs: Long) {
        player?.seekTo(positionMs)
    }

    override fun setSpeed(speed: Float) {
        player?.playbackParameters = PlaybackParameters(speed)
    }

    override fun setVolume(volume: Float) {
        player?.volume = volume.coerceIn(0f, 1f)
    }

    override fun attachSurface(surface: Surface) {
        player?.setVideoSurface(surface)
    }

    override fun detachSurface() {
        player?.clearVideoSurface()
    }

    override fun release() {
        player?.release()
        player = null
    }

    override fun positionMs(): Long = player?.currentPosition ?: 0L

    override fun durationMs(): Long = player?.duration?.takeIf { it > 0L } ?: 0L

    override fun isPlaying(): Boolean = player?.isPlaying ?: false

    /** 设置视频效果（超分辨率、色调映射等） */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun setVideoEffects(effects: List<androidx.media3.common.Effect>) {
        player?.setVideoEffects(effects)
    }

    /** Media3 原生 Player 实例（用于 PlayerView 绑定） */
    fun nativePlayer(): ExoPlayer? = player

    /**
     * 订阅源专用播放方法（已构建好 MediaSource）。
     * 用于兼容旧的订阅源播放链路（已构建好 MergingMediaSource）。
     */
    fun prepareMediaSource(mediaSource: androidx.media3.exoplayer.source.MediaSource, playWhenReady: Boolean) {
        val p = player ?: return
        p.setMediaSource(mediaSource)
        p.playWhenReady = playWhenReady
        p.prepare()
    }

    private inner class ExoPlayerListener : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _isPlaying.value = isPlaying
            listener.onIsPlayingChanged(isPlaying)
        }

        override fun onIsLoadingChanged(isLoading: Boolean) {
            listener.onIsLoadingChanged(isLoading)
        }

        override fun onPlaybackStateChanged(state: Int) {
            when (state) {
                Player.STATE_BUFFERING -> listener.onBuffering(true)
                Player.STATE_READY -> {
                    listener.onBuffering(false)
                    listener.onDurationChanged(player?.duration ?: 0L)
                }
                Player.STATE_ENDED -> listener.onPlaybackEnded()
                Player.STATE_IDLE -> {
                    listener.onIsPlayingChanged(false)
                    listener.onBuffering(false)
                }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            listener.onError(error.message ?: "Playback error")
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            listener.onPositionChanged(newPosition.positionMs)
        }

        @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
        override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
            if (videoSize.width <= 0 || videoSize.height <= 0) return
            listener.onVideoSizeChanged(videoSize.width, videoSize.height)
        }
    }

    companion object {
        const val ID = "media3"
    }
}
