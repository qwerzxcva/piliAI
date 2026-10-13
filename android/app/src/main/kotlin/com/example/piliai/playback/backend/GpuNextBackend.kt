package com.example.piliai.playback.backend

import android.app.ActivityManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Surface
import com.example.piliai.utils.RendererPrefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * GPU-next 后端（libmpv `vo=gpu-next`，libplacebo 渲染）。
 *
 * 渲染由 libmpv 完成；本类只把 Kotlin 侧的播放控制翻译成 mpv 选项/属性/命令。
 * libmpv 由 [RustGpuNextBridge] 提供；未打包时 [isAvailable] 为 false，
 * 调用方（[PlaybackBackends.resolve]）应回退到 Media3。
 *
 * ## 事件循环（审核237 修复）
 * libmpv 的事件必须被 `mpv_wait_event` 持续取走，否则：
 * - 文件加载/结束、seek、重配（video-reconfig）都不会被通知；
 * - 已 `observe_property` 的属性变化会堆在事件队列里直到溢出；
 * - 表现就是「起播后一直转圈 / 进度不更新 / 播完不连播」。
 *
 * bv 的做法是 mpv-android 的事件线程回调（player/.../mpv/MpvMediaPlayer.kt:130-137 的
 * `MPVLib.EventObserver.event(eventId)`）；本项目没有 native 事件线程，因此用
 * 一个 Handler 轮询 [RustGpuNextBridge.pollEvent] 把事件取走并分派给监听器。
 */
class GpuNextBackend(
    private val context: android.content.Context,
    private val listener: PlaybackEventListener
) : PlaybackBackend {

    override val id: String = ID

    private val _isPlaying = MutableStateFlow(false)
    override val isPlayingFlow: StateFlow<Boolean> = _isPlaying

    // 实际使用单例 RustGpuNextBridge（所有操作最终走 JNI）
    private val bridge: GpuNextBridge = RustGpuNextBridge as GpuNextBridge

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 事件泵是否在跑（release 后必须停，否则轮询已销毁的实例会抛异常） */
    @Volatile
    private var eventPumpRunning = false

    /** 当前媒体的起播游标：收到 START_FILE 后清零，供 resume 判定 */
    private var pendingStartMs = 0L

    /** 上一次取到的位置，避免每 250ms 都回调造成无谓重组 */
    private var lastReportedPositionMs = -1L

    /** 结束事件去重：mpv 的 END_FILE 可能连来两次（stop + eof） */
    private var endDispatched = false

    override fun prepare(request: PlaybackRequest) {
        bridge.open()
        // 审核237：bv 的 configureBeforeInit（MpvMediaPlayer.kt:498-548）——
        // Android 上 gpu-context 固定 android，并显式关掉用不上的内置 Lua 脚本；
        // profile=fast 关掉 mpv 默认加载的 auto-profiles，避免宿主配置覆盖我们的设置。
        bridge.setOption("config", "no")
        bridge.setOption("profile", "fast")
        bridge.setOption("osc", "no")
        bridge.setOption("load-stats-overlay", "no")
        bridge.setOption("load-console", "no")
        bridge.setOption("load-auto-profiles", "no")
        bridge.setOption("load-select", "no")
        bridge.setOption("load-commands", "no")
        bridge.setOption("load-positioning", "no")
        bridge.setOption("gpu-context", "android")
        bridge.setOption("opengl-es", "yes")
        bridge.setOption("force-window", "no")
        bridge.setOption("idle", "yes")
        bridge.setOption("ytdl", "no")
        // 审核237：vo 用「gpu-next,gpu」优先级链——GLES2.0 老设备上 libplacebo
        // 初始化失败时自动回退 gpu，而不是黑屏（bv resolveVideoOutputMode:630-636）
        bridge.setOption("vo", "gpu-next,gpu")
        // 审核237：hwdec 从 auto-safe 改为 mediacodec 组合（bv MobileMpvOptions.kt:33-42）。
        // Android 上 auto-safe 仍可能落到 av1/hevc 软解或拷贝路径，明确指定才能
        // 稳定拿到 MediaCodec 零拷贝；不带 copy 后缀可在老芯片上失败时直接报错。
        bridge.setOption("hwdec", "mediacodec,mediacodec-copy")
        bridge.setOption("hwdec-codecs", "h264,hevc,mpeg4,mpeg2video,vp8,vp9,av1")
        // demuxer 缓存：不配置时 mpv 默认只有几秒，弱网必卡。
        // 审核242：按设备内存分档（bv MpvCachePolicy.kt:31-86）。B站 DASH 用
        // audio-files 播两条流，最坏是配置值两倍；1GB 盒子上固定 64MiB
        // 可达 ~190MiB 被系统杀进程，4GB 设备上对 4K 又偏小。
        val cache = MpvCachePolicy.resolve(context)
        bridge.setOption("demuxer-max-bytes", cache.maxBytes)
        bridge.setOption("demuxer-max-back-bytes", cache.maxBackBytes)
        bridge.setOption("cache", "yes")
        bridge.setOption("cache-secs", cache.cacheSecs.toString())
        bridge.setOption("cache-pause-wait", "%.1f".format(java.util.Locale.US, cache.pauseWait))
        // 音频后端：Android 上 audiotrack 优先（opensles 兼容老设备）
        bridge.setOption("ao", "audiotrack,opensles")
        // 审核224：把 piliAI 渲染设置映射到 mpv/libplacebo（与 Media3 路径的
        // setVideoEffects/音量归一化等价——两条后端体验对齐）
        runCatching {
            val prefs = RendererPrefs
            // 音量归一化：mpv 动态范围压缩 + loudnorm
            if (prefs.audioNormalization) {
                bridge.setOption("af", "loudnorm=I=-16:TP=-1.5:LRA=11")
            }
            // 超分：libplacebo 自带的上采样（同档位映射）
            when (prefs.superResolutionMode) {
                "efficiency" -> bridge.setOption("scale", "spline36")
                "quality" -> {
                    bridge.setOption("scale", "ewa_lanczossharp")
                    bridge.setOption("scale-blur", "sjam")
                }
            }
            // HDR 色调映射（libplacebo 强项：BT.2446a + 峰值检测）
            if (prefs.hdrToneMappingEnabled) {
                bridge.setOption("tone-mapping", "bt.2446a")
                bridge.setOption("hdr-compute-peak", "yes")
            }
        }
        if (request.userAgent.isNotBlank()) bridge.setOption("user-agent", request.userAgent)
        // 审核245：与 Media3 路径同一套规则——APP 签名地址不带 web Referer。
        val referer = com.example.piliai.playback.PlaybackRequestHeaders
            .refererFor("https://www.bilibili.com", request.videoUri.toString())
        if (referer != null) {
            bridge.setOption("referrer", referer)
        } else {
            // mpv 默认会继承上次的 referrer，显式清掉避免串味
            bridge.setOption("referrer", "")
        }
        // DASH 分离音轨：audio-files 让 mpv 把它当外部音轨加载
        request.audioUri?.let { bridge.setOption("audio-files", it.toString()) }
        bridge.initialize()
        // 审核237：属性观察必须在起播前注册，否则 mpv 不会推送变化
        observePlaybackProperties()
        // 起播前先暂停，等 FILE_LOADED 再按 playWhenReady 决定播放
        bridge.setProperty("pause", "yes")
        pendingStartMs = request.resumePositionMs
        endDispatched = false
        lastReportedPositionMs = -1L
        bridge.command("loadfile", request.videoUri.toString(), "replace")
        if (request.resumePositionMs > 0L) {
            // start 选项已过 initialize，改用在 FILE_LOADED 后 seek 的方式
            pendingStartMs = request.resumePositionMs
        }
        _isPlaying.value = request.playWhenReady
        startEventPump()
    }

    /**
     * 注册需要监听的属性（bv observePlaybackProperties，MpvMediaPlayer.kt:760-786）。
     * 只有注册过的属性变化才会进事件队列。
     */
    private fun observePlaybackProperties() {
        runCatching {
            bridge.observeProperty("time-pos", FORMAT_DOUBLE)
            bridge.observeProperty("duration", FORMAT_DOUBLE)
            bridge.observeProperty("pause", FORMAT_STRING)
            bridge.observeProperty("paused-for-cache", FORMAT_STRING)
            bridge.observeProperty("eof-reached", FORMAT_STRING)
            bridge.observeProperty("seeking", FORMAT_STRING)
            bridge.observeProperty("core-idle", FORMAT_STRING)
            bridge.observeProperty("video-params/w", FORMAT_INT64)
            bridge.observeProperty("video-params/h", FORMAT_INT64)
            bridge.observeProperty("hwdec-current", FORMAT_STRING)
            bridge.observeProperty("video-codec", FORMAT_STRING)
            bridge.observeProperty("audio-codec", FORMAT_STRING)
            bridge.observeProperty("demuxer-cache-time", FORMAT_DOUBLE)
        }
    }

    /** 事件泵：每 250ms 取一次 mpv 事件并驱动 UI 状态 */
    private fun startEventPump() {
        eventPumpRunning = true
        mainHandler.removeCallbacks(eventPumpRunnable)
        mainHandler.post(eventPumpRunnable)
    }

    private val eventPumpRunnable = object : Runnable {
        override fun run() {
            if (!eventPumpRunning) return
            runCatching { pumpEvents() }
            if (eventPumpRunning) mainHandler.postDelayed(this, PUMP_INTERVAL_MS)
        }
    }

    /**
     * 取走一批 mpv 事件（非阻塞轮询：timeout=0 只处理已入队的事件），
     * 然后主动读一次位置/时长用于进度条。
     */
    private fun pumpEvents() {
        // 事件可能连来多个，全部取干净再退（每次 timeout 0 = 非阻塞）
        var guard = 0
        while (guard++ < MAX_EVENTS_PER_TICK) {
            val eventId = runCatching { bridge.pollEvent(0) }.getOrNull() ?: return
            if (eventId == EVENT_NONE) break
            handleEvent(eventId)
        }
        // 位置/时长推进（mpv 的 time-pos 变化也走事件，但轮询读更简单可靠）
        runCatching {
            val pos = bridge.longProperty("time-pos")
            if (pos > 0.0) {
                val posMs = (pos * 1000).toLong()
                if (posMs != lastReportedPositionMs) {
                    lastReportedPositionMs = posMs
                    listener.onPositionChanged(posMs)
                }
            }
            val dur = bridge.longProperty("duration")
            if (dur > 0.0) listener.onDurationChanged((dur * 1000).toLong())
        }
        runCatching {
            val playing = bridge.stringProperty("pause") == "no"
            if (playing != _isPlaying.value) {
                _isPlaying.value = playing
                listener.onIsPlayingChanged(playing)
            }
        }
    }

    private fun handleEvent(eventId: Int) {
        when (eventId) {
            EVENT_START_FILE -> {
                endDispatched = false
                listener.onBuffering(true)
            }
            EVENT_FILE_LOADED -> {
                listener.onBuffering(false)
                // 恢复进度：mpv 的 start 选项已过期，加载完再 seek
                if (pendingStartMs > 0L) {
                    val seekTo = pendingStartMs
                    pendingStartMs = 0L
                    seekTo(seekTo)
                }
                if (_isPlaying.value) {
                    bridge.setProperty("pause", "no")
                    listener.onIsPlayingChanged(true)
                }
            }
            EVENT_PLAYBACK_RESTART -> {
                listener.onBuffering(false)
                runCatching {
                    val w = bridge.longProperty("video-params/w")
                    val h = bridge.longProperty("video-params/h")
                    if (w > 0 && h > 0) listener.onVideoSizeChanged(w.toInt(), h.toInt())
                }
            }
            EVENT_VIDEO_RECONFIG -> {
                runCatching {
                    val w = bridge.longProperty("video-params/w")
                    val h = bridge.longProperty("video-params/h")
                    if (w > 0 && h > 0) listener.onVideoSizeChanged(w.toInt(), h.toInt())
                }
            }
            EVENT_SEEK -> Unit
            EVENT_END_FILE -> {
                if (endDispatched) return
                endDispatched = true
                // eof-reached 为真才是自然播完；否则是切源/出错
                val eof = runCatching { bridge.stringProperty("eof-reached") }.getOrNull() == "yes"
                _isPlaying.value = false
                listener.onIsPlayingChanged(false)
                if (eof) listener.onPlaybackEnded()
            }
            EVENT_SHUTDOWN -> {
                eventPumpRunning = false
                mainHandler.removeCallbacks(eventPumpRunnable)
            }
            else -> Unit
        }
    }

    override fun play() {
        runCatching { bridge.setProperty("pause", "no") }
        _isPlaying.value = true
        listener.onIsPlayingChanged(true)
    }

    override fun pause() {
        runCatching { bridge.setProperty("pause", "yes") }
        _isPlaying.value = false
        listener.onIsPlayingChanged(false)
    }

    override fun seekTo(positionMs: Long) {
        runCatching { bridge.command("seek", (positionMs / 1000.0).toString(), "absolute") }
        lastReportedPositionMs = positionMs
        listener.onPositionChanged(positionMs)
    }

    override fun setSpeed(speed: Float) {
        runCatching { bridge.setProperty("speed", speed.toString()) }
    }

    override fun setVolume(volume: Float) {
        runCatching {
            bridge.setProperty("volume", (volume.coerceIn(0f, 1f) * 100f).toInt().toString())
        }
    }

    override fun attachSurface(surface: Surface) {
        runCatching { bridge.attachSurface(surface) }
    }

    override fun detachSurface() {
        runCatching { bridge.detachSurface() }
    }

    override fun release() {
        eventPumpRunning = false
        mainHandler.removeCallbacks(eventPumpRunnable)
        runCatching { bridge.release() }
        _isPlaying.value = false
    }

    override fun positionMs(): Long =
        runCatching { (bridge.longProperty("time-pos") * 1000).toLong() }.getOrDefault(0L)

    override fun durationMs(): Long =
        runCatching { (bridge.longProperty("duration") * 1000).toLong() }.getOrDefault(0L)

    override fun isPlaying(): Boolean =
        runCatching { bridge.stringProperty("pause") == "no" }.getOrDefault(false)

    companion object {
        const val ID = "gpu-next"

        /** mpv 属性格式（include/mpv/client.h） */
        private const val FORMAT_STRING = 1
        private const val FORMAT_INT64 = 4
        private const val FORMAT_DOUBLE = 5

        /** mpv 事件 id（client.h） */
        private const val EVENT_NONE = 0
        private const val EVENT_SHUTDOWN = 1
        private const val EVENT_START_FILE = 6
        private const val EVENT_END_FILE = 7
        private const val EVENT_FILE_LOADED = 8
        private const val EVENT_VIDEO_RECONFIG = 17
        private const val EVENT_SEEK = 20
        private const val EVENT_PLAYBACK_RESTART = 21

        private const val PUMP_INTERVAL_MS = 250L
        private const val MAX_EVENTS_PER_TICK = 16

        fun isAvailable(): Boolean = RustGpuNextBridge.libraryLoaded
    }
}

/** mpv 事件/属性常量（供 [GpuNextBackend] 与测试共用） */
internal object MpvConstants {
    const val FORMAT_STRING = 1
    const val FORMAT_INT64 = 4
    const val FORMAT_DOUBLE = 5
    const val EVENT_NONE = 0
    const val EVENT_SHUTDOWN = 1
    const val EVENT_START_FILE = 6
    const val EVENT_END_FILE = 7
    const val EVENT_FILE_LOADED = 8
    const val EVENT_VIDEO_RECONFIG = 17
    const val EVENT_SEEK = 20
    const val EVENT_PLAYBACK_RESTART = 21
}

/**
 * 审核242：mpv demuxer 缓存分档（移植 bv `MpvCachePolicy.kt:31-86`）。
 *
 * 为什么要分档：B 站 DASH 走 `--audio-file` 播视频+音频两条流，实际占用
 * 最坏是配置值的两倍。bv 实测 1GB 盒子上固定 64/32 MiB 可达 ~190 MiB
 * 被系统杀进程，而 4GB 设备上同一配置对 4K 又偏小。
 */
internal data class MpvCacheConfig(
    val maxBytes: String,
    val maxBackBytes: String,
    val cacheSecs: Int,
    val pauseWait: Double
)

internal object MpvCachePolicy {

    /** 直播缓存更小：追帧比重播更重要（bv MpvCachePolicy.kt 直播档） */
    fun live(base: MpvCacheConfig): MpvCacheConfig = MpvCacheConfig(
        maxBytes = "${maxOf(base.mib() / 2, 8)}MiB",
        maxBackBytes = "4MiB",
        cacheSecs = 30,
        pauseWait = 1.5
    )

    /** 用户手动「加大缓冲」：bv expandBuffer 档 */
    fun expanded(base: MpvCacheConfig): MpvCacheConfig = MpvCacheConfig(
        maxBytes = "${minOf(base.mib() * 4, 256)}MiB",
        maxBackBytes = "${base.mib()}MiB",
        cacheSecs = 300,
        pauseWait = 3.0
    )

    fun resolve(context: android.content.Context): MpvCacheConfig {
        val activityManager = context.getSystemService(android.content.Context.ACTIVITY_SERVICE)
            as? android.app.ActivityManager
        val memInfo = android.app.ActivityManager.MemoryInfo()
        activityManager?.getMemoryInfo(memInfo)
        val totalBytes = activityManager?.let { am ->
            val info = android.app.ActivityManager.MemoryInfo()
            am.getMemoryInfo(info)
            info.totalMem
        } ?: 0L
        val lowRam = memInfo.lowMemory
        val totalMiB = (totalBytes / (1024 * 1024)).toInt()
        return when {
            lowRam || totalMiB in 1 until 1536 -> MpvCacheConfig("16MiB", "8MiB", 120, 3.0)
            totalMiB in 1536 until 3072 -> MpvCacheConfig("32MiB", "16MiB", 120, 3.0)
            totalMiB >= 3072 -> MpvCacheConfig("64MiB", "32MiB", 120, 3.0)
            // totalMem 取不到时的保守默认（中端档）
            else -> MpvCacheConfig("32MiB", "16MiB", 120, 3.0)
        }
    }

    private fun MpvCacheConfig.mib(): Int = maxBytes.removeSuffix("MiB").toIntOrNull() ?: 32
}
