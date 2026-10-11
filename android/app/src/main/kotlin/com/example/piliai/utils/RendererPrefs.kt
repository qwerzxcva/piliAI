package com.example.piliai.utils

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 渲染器偏好的进程内同步缓存（Kazumi「渲染器切换」移植）
 *
 * 背景：DataStore 只能异步读，而 PlayerView 的 factory 在主线程同步执行，
 * 在 factory 里 runBlocking 会卡帧（尤其冷启动时 DataStore 首次读涉及磁盘 IO）。
 *
 * 方案：用一个进程级单例缓存，由 Application 启动时异步预热一次；
 * 之后设置变更时由 ViewModel 调用 setRenderer 更新缓存，保证
 * 「下次进入播放器即生效」且主线程零阻塞。
 */
object RendererPrefs {

    @Volatile
    var useTextureView: Boolean = false
        private set

    @Volatile
    var playbackBackend: String = "media3"
        private set

    /** Kazumi：低延迟音频（同步缓存，供 ExoPlayer.Builder 读取） */
    @Volatile
    var lowLatencyAudio: Boolean = false
        private set

    /**
     * VOD 缓冲时长（毫秒），供 ExoPlayer.Builder 的 LoadControl 读取。
     * 移植自 piliplus 缓冲策略；默认 16s（上游 DEFAULT_BUFFER_DURATION_MS）。
     */
    @Volatile
    var bufferDurationMs: Int = 16_000
        private set

    /** bv P2：硬件解码模式（auto/hardware/software），供 ExoPlayer RenderersFactory 读取 */
    @Volatile
    var hardwareDecodeMode: String = "auto"
        private set

    @Volatile
    var skipIntroSec: Int = 0
        private set

    @Volatile
    var skipOutroSec: Int = 0
        private set

    /** 审核轮210：超分辨率模式（disable/efficiency/quality），供播放器 setVideoEffects 读取 */
    @Volatile
    var superResolutionMode: String = "disable"
        private set

    /** 审核轮210：音量归一化（AudioNormalizationProcessor 接入 DefaultAudioSink） */
    @Volatile
    var audioNormalization: Boolean = false
        private set

    /**
     * HDR 色调映射开关（移植自 piliplus SDR→HDR 功能）
     * 默认关闭，用户可在设置页启用
     */
    @Volatile
    var hdrToneMappingEnabled: Boolean = false
        private set

    /**
     * HDR 色调映射算法（0=Reinhard, 1=ACES, 2=Mobius）
     * 默认 Reinhard（平衡性能与效果）
     */
    @Volatile
    var hdrAlgorithm: Int = 0
        private set

    /**
     * HDR 高光保护强度（0.0~1.0），防止过曝
     */
    @Volatile
    var hdrHighlightProtect: Double = 0.5
        private set

    /**
     * HDR 动态范围扩展强度（0.0~1.0）
     */
    @Volatile
    var hdrDynamicRangeExpand: Double = 0.5
        private set

    private var inited = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Application 启动时调用：异步预热 + 持续同步 */
    fun init(context: android.content.Context) {
        if (inited) return
        inited = true
        val storage = StorageManager(context.applicationContext)
        scope.launch {
            storage.rendererFlow.collectLatest { v ->
                useTextureView = (v == 1)
            }
        }
        scope.launch {
            storage.playbackBackendFlow.collectLatest { v ->
                playbackBackend = v
            }
        }
        scope.launch {
            storage.lowLatencyAudioFlow.collectLatest { v ->
                lowLatencyAudio = v
            }
        }
        scope.launch {
            storage.bufferDurationFlow.collectLatest { v ->
                bufferDurationMs = v
            }
        }
        scope.launch {
            storage.hardwareDecodeFlow.collectLatest { v ->
                hardwareDecodeMode = v
            }
        }
        scope.launch {
            storage.skipIntroSecFlow.collectLatest { skipIntroSec = it }
        }
        scope.launch {
            storage.skipOutroSecFlow.collectLatest { skipOutroSec = it }
        }
        scope.launch {
            storage.superResolutionFlow.collectLatest { v ->
                superResolutionMode = v
                updateSuperResolution(v)
            }
        }
        scope.launch {
            storage.audioNormFlow.collectLatest { v ->
                audioNormalization = v
            }
        }
        scope.launch {
            storage.hdrEnabledFlow.collectLatest { v ->
                hdrToneMappingEnabled = v
            }
        }
        scope.launch {
            storage.hdrAlgorithmFlow.collectLatest { v ->
                hdrAlgorithm = v
            }
        }
        scope.launch {
            storage.hdrHighlightProtectFlow.collectLatest { v ->
                hdrHighlightProtect = v.toDouble()
            }
        }
        scope.launch {
            storage.hdrDynamicRangeExpandFlow.collectLatest { v ->
                hdrDynamicRangeExpand = v.toDouble()
            }
        }
    }

    /** 设置变更时同步更新缓存（下次创建 PlayerView 生效） */
    fun update(v: Int) {
        useTextureView = (v == 1)
    }

    fun updatePlaybackBackend(id: String) {
        playbackBackend = id
    }

    /** 更新低延迟音频缓存 */
    fun updateLowLatency(v: Boolean) {
        lowLatencyAudio = v
    }

    /** 更新缓冲时长缓存（下次进入播放器生效） */
    fun updateBufferDuration(ms: Int) {
        bufferDurationMs = ms
    }

    /** bv P2：更新硬件解码模式缓存（下次进入播放器生效） */
    fun updateHardwareDecodeMode(mode: String) {
        hardwareDecodeMode = mode
    }

    /** 审核轮210：更新音量归一化缓存 */
    fun updateAudioNormalization(v: Boolean) {
        audioNormalization = v
    }

    /** 审核轮210：更新超分辨率缓存 */
    fun updateSuperResolution(mode: String) {
        superResolutionMode = mode
        com.example.piliai.piliplus.Media3SuperResolutionApplier.mode =
            try { com.example.piliai.piliplus.Media3SuperResolutionMode.fromName(mode) }
            catch (_: Exception) { com.example.piliai.piliplus.Media3SuperResolutionMode.DISABLE }
    }

    /** 更新 HDR 色调映射开关 */
    fun updateHdrToneMapping(enabled: Boolean) {
        hdrToneMappingEnabled = enabled
    }

    /** 更新 HDR 算法（0=Reinhard, 1=ACES, 2=Mobius） */
    fun updateHdrAlgorithm(algorithm: Int) {
        hdrAlgorithm = algorithm.coerceIn(0, 2)
    }

    /** 更新 HDR 高光保护强度（0.0~1.0） */
    fun updateHdrHighlightProtect(value: Double) {
        hdrHighlightProtect = value.coerceIn(0.0, 1.0)
    }

    /** 更新 HDR 动态范围扩展强度（0.0~1.0） */
    fun updateHdrDynamicRangeExpand(value: Double) {
        hdrDynamicRangeExpand = value.coerceIn(0.0, 1.0)
    }
}
