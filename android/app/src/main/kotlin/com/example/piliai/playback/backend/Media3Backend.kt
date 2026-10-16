package com.example.piliai.playback.backend

import android.content.Context
import android.net.Uri
import android.view.Surface
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import com.example.piliai.piliplus.AudioNormalizationConfiguration
import com.example.piliai.piliplus.AudioNormalizationProcessor
import com.example.piliai.piliplus.Media3LoadControlFactory
import com.example.piliai.utils.RendererPrefs
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

    /**
     * 审核320：音量归一化处理器。
     *
     * 此前 AudioNormalizationProcessor 只有 GpuNextBackend 用（mpv 的
     * loudnorm 滤镜），默认的 Media3 后端完全不接——设置页那个开关对
     * 绝大多数用户没有任何效果。现在通过 ExoPlayer 的
     * setAudioProcessors 接入，两条后端体验对齐。
     */
    private val audioNormalization = AudioNormalizationProcessor()

    init {
        // 审核：AudioAttributes 必须在 Builder 设置（setter 已移除）
        val audioAttributes = androidx.media3.common.AudioAttributes.Builder()
            .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MOVIE)
            .setUsage(androidx.media3.common.C.USAGE_MEDIA)
            .build()

        // 审核轮（bv P1）：此前 LoadControl 是孤儿代码，设置页的「缓冲时长」从未生效。
        // 现在把 Media3LoadControlFactory 真正接到 Builder；与 AudioAttributes 同为
        // Builder 期设置，运行中修改缓冲偏好需重建播放器才生效。
        // 审核244（P1-5）：低延迟音频开关此前只写 state 缓存，播放器
        // 硬编码 handleAudioFocus=true → 开关在默认 media3 后端无效。
        // 低延迟模式关掉焦点让位，避免焦点切换导致输出重开与缓冲重置。
        player = ExoPlayer.Builder(context, buildRenderersFactory())
            .setAudioAttributes(audioAttributes, !RendererPrefs.lowLatencyAudio)
            .setLoadControl(
                Media3LoadControlFactory.create(bufferDurationMs = RendererPrefs.bufferDurationMs)
            )
            .build()
            .apply {
                addListener(ExoPlayerListener())
                // 审核320：按偏好刷一次归一化参数（处理器挂在 buildRenderersFactory
                // 覆写的 buildAudioSink 上；关闭时 configuration=null 直通）。
                applyAudioNormalization()
                // 审核242：丢帧检测（bv ExoMediaPlayer.kt:71-90/643 的
                // onDroppedVideoFrames 监听）。持续丢帧说明当前画质/超分
                // 超出设备解码能力，提示用户降档比默默卡死好。
                addAnalyticsListener(object : androidx.media3.exoplayer.analytics.AnalyticsListener {
                    private var consecutiveDrops = 0
                    private var reported = false

                    override fun onDroppedVideoFrames(
                        eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                        droppedFrames: Int,
                        elapsedMs: Long
                    ) {
                        // 单位时间丢帧超过 5% 才算一次「掉帧事件」
                        if (elapsedMs <= 0L || droppedFrames * 20 < elapsedMs / 50) {
                            return
                        }
                        consecutiveDrops += 1
                        // 连续 2 次掉帧才提示，避免偶发单次误报
                        if (consecutiveDrops >= 2 && !reported) {
                            reported = true
                            listener.onDecoderOverloaded()
                        }
                    }

                    override fun onRenderedFirstFrame(
                        eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                        output: Any,
                        renderTimeMs: Long
                    ) {
                        consecutiveDrops = 0
                        reported = false
                    }
                })
            }
    }

    override fun prepare(request: PlaybackRequest) {
        val p = player ?: return

        // 审核320：每次起播都按最新偏好刷一次音量归一化。用户在设置里
        // 切换开关后 RendererPrefs 已同步，但播放器实例不知道——起播时
        // 补齐，避免「改了设置要杀进程才生效」。
        applyAudioNormalization()

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

        // 审核237：按媒体类型选 MediaSource。此前一律用 ProgressiveMediaSource，
        // 导致订阅源里的 m3u8 / mpd 直链必然解析失败（用户反馈「订阅源视频播不了」）。
        val videoSource = buildMediaSource(dataSourceFactory, request.videoUri)

        // 音频流（可选，DASH 分离流）
        val finalSource = if (request.audioUri != null) {
            val audioItem = MediaItem.Builder().setUri(request.audioUri).build()
            val audioSource = buildMediaSource(dataSourceFactory, request.audioUri)
            MergingMediaSource(videoSource, audioSource)
        } else {
            videoSource
        }

        p.setMediaSource(finalSource, request.resumePositionMs)
        p.playWhenReady = request.playWhenReady
        p.prepare()
    }

    /**
     * 审核237：按 URL 形态选择 MediaSource 工厂。
     *
     * - `.m3u8` / `m3u` → HlsMediaSource（直播/切片清单）
     * - `.mpd` → DashMediaSource（DASH 清单）
     * - `.mp4`/`.flv`/`.mkv`/`.m4s`/无扩展名的裸流 → ProgressiveMediaSource
     *
     * 无法从扩展名判断时用 Progressive：Media3 对未知内容类型仍会尝试
     * 渐进式解析，且 ExtractorsFactory 会按 sniff 结果选择抽取器。
     */
    private fun buildMediaSource(
        factory: androidx.media3.datasource.DataSource.Factory,
        uri: Uri
    ): androidx.media3.exoplayer.source.MediaSource {
        val item = MediaItem.Builder().setUri(uri).build()
        val path = uri.path?.lowercase().orEmpty()
        val query = uri.query?.lowercase().orEmpty()
        val hint = if (path.isNotEmpty()) path else query
        return when {
            hint.endsWith(".m3u8") || hint.endsWith(".m3u") ||
                hint.contains("/m3u8") || hint.contains("m3u8?") ||
                hint.contains("type=m3u8") || hint.contains("type=hls") ->
                HlsMediaSource.Factory(factory).createMediaSource(item)
            hint.endsWith(".mpd") || hint.contains("type=mpd") ||
                hint.contains("type=dash") ->
                DashMediaSource.Factory(factory).createMediaSource(item)
            else -> ProgressiveMediaSource.Factory(factory).createMediaSource(item)
        }
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

    /**
     * 审核320：按当前偏好刷新音量归一化。
     *
     * 设置页切换开关时调用（RendererPrefs 已同步）。configuration 为 null
     * 时处理器直通，因此关闭开关只是去掉效果，不会中断音频管线。
     */
    fun applyAudioNormalization() {
        audioNormalization.setConfiguration(
            if (RendererPrefs.audioNormalization) {
                AudioNormalizationConfiguration(
                    // 与 GpuNextBackend 的 loudnorm 参数对齐：
                    // I=-16 LUFS、TP=-1.5 dBTP
                    targetRmsDb = -16.0,
                    maxGain = 10.0,
                    dynamic = true,
                )
            } else {
                null
            }
        )
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

    /**
     * bv P2：硬件解码模式。
     * - auto（默认）：交给 Media3 默认选择
     * - hardware：优先硬件解码器
     * - software：优先软件解码器；无可用软件解码器时仍可使用硬件解码器
     * 通过自定义 MediaCodecSelector 重排解码器优先级；Builder 期设置，重建播放器生效。
     */
    private fun buildRenderersFactory(): DefaultRenderersFactory {
        val mode = RendererPrefs.hardwareDecodeMode
        val preferHardware = (mode == "hardware")
        val selector = object : MediaCodecSelector {
            override fun getDecoderInfos(
                mimeType: String,
                requiresSecureDecoder: Boolean,
                requiresTunnelingDecoder: Boolean
            ): MutableList<MediaCodecInfo> {
                val base = MediaCodecSelector.DEFAULT.getDecoderInfos(
                    mimeType, requiresSecureDecoder, requiresTunnelingDecoder
                )
                // hardware=true 表示平台硬解；按偏好把目标组排到前面，保持组内原顺序
                return base.sortedBy { it.hardwareAccelerated != preferHardware }.toMutableList()
            }
        }
        return object : DefaultRenderersFactory(context) {
            override fun buildVideoRenderers(
                context: Context,
                extensionRendererMode: Int,
                mediaCodecSelector: MediaCodecSelector,
                enableDecoderFallback: Boolean,
                eventHandler: android.os.Handler,
                eventListener: androidx.media3.exoplayer.video.VideoRendererEventListener,
                allowedVideoJoiningTimeMs: Long,
                out: java.util.ArrayList<androidx.media3.exoplayer.Renderer>
            ) {
                super.buildVideoRenderers(
                    context, extensionRendererMode, selector, enableDecoderFallback,
                    eventHandler, eventListener, allowedVideoJoiningTimeMs, out
                )
            }

            /**
             * 审核320：把音量归一化处理器挂进音频管线。
             *
             * 这是 Media3 1.3.1 接入 AudioProcessor 的唯一入口
             * （ExoPlayer.Builder 既没有 setAudioProcessors 也没有
             * setAudioSink，两者都在 DefaultRenderersFactory 上，javap 已核实）。
             * 保持父类其余参数不变，只替换 processors 数组。
             */
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): androidx.media3.exoplayer.audio.AudioSink {
                return DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioProcessors(arrayOf(audioNormalization))
                    .build()
            }
        }
    }

    companion object {
        const val ID = "media3"
    }
}
