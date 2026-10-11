package com.example.piliai.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.piliai.utils.StorageManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 设置 ViewModel（批次：设置页扩展）——全部真实持久化到 DataStore
 */
class SettingsViewModel(context: Context) : ViewModel() {

    // 审核27：同上，持有 applicationContext 防 Activity 泄漏
    private val storage = StorageManager(context.applicationContext)

    private val _state = MutableStateFlow(SettingsState())
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    data class SettingsState(
        // 播放
        val autoPlay: Boolean = false,
        val playbackBackend: String = "media3",
        val completionAction: String = "next",
        val lowLatencyAudio: Boolean = false,  // Kazumi：低延迟音频
        val renderer: Int = 0,                 // Kazumi：渲染器 0=SurfaceView 1=TextureView
        val videoQuality: String = "auto",   // auto/1080p/720p/480p
        val playbackSpeed: Float = 1.0f,
        val bufferDurationMs: Int = 16_000,  // VOD 缓冲时长（LoadControl，移植自 piliplus）
        val hardwareDecodeMode: String = "auto",  // bv P2：硬件解码 auto/hardware/software
        val superResolutionMode: String = "disable",  // 审核轮210：超分辨率（disable/efficiency/quality）
        val audioNormalization: Boolean = false,  // 审核轮210：音量归一化
        // HDR 色调映射（移植自 piliplus，配置通道已就位，实际 GL shader 待 Media3 1.5+）
        val hdrEnabled: Boolean = false,
        val hdrAlgorithm: Int = 0,           // 0=Reinhard, 1=ACES, 2=Mobius
        val hdrHighlightProtect: Float = 0.5f,
        val hdrDynamicRangeExpand: Float = 0.5f,
        // 弹幕源：弹弹play (DanDan) 补充弹幕（移植自 animeko-upstream；需在 BuildConfig 配置凭据）
        val dandanDanmakuEnabled: Boolean = false,
        // 弹幕
        val danmakuEnabled: Boolean = true,
        val danmakuOpacity: Float = 1.0f,
        val danmakuFontSize: Int = 25,
        val danmakuSpeed: Float = 1.0f,
        // 主题
        val themeMode: String = "system",     // system/light/dark
        val accentColor: String = "",         // 十六进制，空=默认绿
        val amoled: Boolean = false,          // AMOLED 纯黑（仅暗色生效）
        // 审核216：第一批 Flutter 高频设置项
        val autoEnterFullscreen: Boolean = false,  // 进入播放页自动全屏
        val pauseOnMinimize: Boolean = false,      // 切后台自动暂停
        val showOnlineTotal: Boolean = true,       // 显示实时在线人数
        val audioQa: String = "0",                 // 审核219：默认音质（0=最高）
        val preferCodec: String = "avc",           // 审核220：解码器偏好
        // 审核221：蜂窝网络覆盖项（空=跟随 WiFi 设置）
        val videoQaCellular: String = "",
        val audioQaCellular: String = "",
        val preferCodecCellular: String = "",
        val dmCountEnabled: Boolean = false,   // 审核222：播放器显示弹幕总数
        val skipIntroSec: Int = 0,
        val skipOutroSec: Int = 0,
        // 审核233：弹幕细节（PiliPlus 对齐）
        val dmShowArea: Float = 0.5f,
        val dmStrokeWidth: Float = 1.5f,
        val dmFontWeight: Int = 5,
        val dmLineHeight: Float = 1.6f,
        val dmMassiveMode: Boolean = false,
        val showVipDanmaku: Boolean = true,
        val mergeDanmaku: Boolean = false,
        val enableTapDanmaku: Boolean = true,
        // 审核233：播放行为
        val continuePlayingPart: Boolean = true,
        val continuePlayInBackground: Boolean = false,
        val directExitOnBack: Boolean = false,
        val enableAutoLongPressSpeed: Boolean = false,
        val longPressSpeedDefault: Float = 3.0f,
        // 审核233：推荐过滤
        val minDurationForRcmd: Int = 0,
        val minPlayForRcmd: Int = 0,
        val minLikeRatioForRecommend: Int = 0,
        val exemptFilterForFollowed: Boolean = true,
        val applyFilterToRelatedVideos: Boolean = true,
        val banWordForRecommend: String = "",
        // 审核235：图片质量 + 字幕细节
        val picQuality: Int = 100,
        val subtitleScale: Float = 1f,
        val subtitlePaddingH: Float = 10f,
        val subtitlePaddingB: Float = 64f,
        val subtitleBgOpacity: Float = 0.6f,
        val subtitleStroke: Float = 0.5f,
        val subtitleBold: Boolean = false,
    )

    init {
        viewModelScope.launch {
            storage.autoEnterFullscreenFlow.collect { _state.value = _state.value.copy(autoEnterFullscreen = it) }
        }
        viewModelScope.launch {
            storage.pauseOnMinimizeFlow.collect { _state.value = _state.value.copy(pauseOnMinimize = it) }
        }
        viewModelScope.launch {
            storage.showOnlineTotalFlow.collect { _state.value = _state.value.copy(showOnlineTotal = it) }
        }
        viewModelScope.launch {
            storage.audioQaFlow.collect { _state.value = _state.value.copy(audioQa = it) }
        }
        viewModelScope.launch {
            storage.preferCodecFlow.collect { _state.value = _state.value.copy(preferCodec = it) }
        }
        viewModelScope.launch {
            storage.videoQaCellularFlow.collect { _state.value = _state.value.copy(videoQaCellular = it) }
        }
        viewModelScope.launch {
            storage.audioQaCellularFlow.collect { _state.value = _state.value.copy(audioQaCellular = it) }
        }
        viewModelScope.launch {
            storage.preferCodecCellularFlow.collect { _state.value = _state.value.copy(preferCodecCellular = it) }
        }
        viewModelScope.launch {
            storage.dmCountEnabledFlow.collect { _state.value = _state.value.copy(dmCountEnabled = it) }
        }
        viewModelScope.launch {
            storage.skipIntroSecFlow.collect { _state.value = _state.value.copy(skipIntroSec = it) }
        }
        viewModelScope.launch {
            storage.skipOutroSecFlow.collect { _state.value = _state.value.copy(skipOutroSec = it) }
        }
        viewModelScope.launch {
            storage.autoPlayFlow.collect { _state.value = _state.value.copy(autoPlay = it) }
        }
        viewModelScope.launch {
            storage.lowLatencyAudioFlow.collect { _state.value = _state.value.copy(lowLatencyAudio = it) }
        }
        viewModelScope.launch {
            storage.rendererFlow.collect { _state.value = _state.value.copy(renderer = it) }
        }
        viewModelScope.launch {
            storage.playbackBackendFlow.collect { _state.value = _state.value.copy(playbackBackend = it) }
        }
        viewModelScope.launch {
            storage.completionActionFlow.collect { _state.value = _state.value.copy(completionAction = it) }
        }
        viewModelScope.launch {
            storage.videoQualityFlow.collect { _state.value = _state.value.copy(videoQuality = it) }
        }
        viewModelScope.launch {
            storage.danmakuEnabledFlow.collect { _state.value = _state.value.copy(danmakuEnabled = it) }
        }
        viewModelScope.launch {
            storage.danmakuOpacityFlow.collect { _state.value = _state.value.copy(danmakuOpacity = it) }
        }
        viewModelScope.launch {
            storage.danmakuFontSizeFlow.collect { _state.value = _state.value.copy(danmakuFontSize = it) }
        }
        viewModelScope.launch {
            storage.danmakuSpeedFlow.collect { _state.value = _state.value.copy(danmakuSpeed = it) }
        }
        viewModelScope.launch {
            storage.themeModeFlow.collect { _state.value = _state.value.copy(themeMode = it) }
        }
        viewModelScope.launch {
            storage.accentColorFlow.collect { _state.value = _state.value.copy(accentColor = it) }
        }
        viewModelScope.launch {
            storage.amoledFlow.collect { _state.value = _state.value.copy(amoled = it) }
        }
        viewModelScope.launch {
            storage.bufferDurationFlow.collect { _state.value = _state.value.copy(bufferDurationMs = it) }
            storage.superResolutionFlow.collect { _state.value = _state.value.copy(superResolutionMode = it) }
            storage.audioNormFlow.collect { _state.value = _state.value.copy(audioNormalization = it) }
        }
        viewModelScope.launch {
            storage.hardwareDecodeFlow.collect { _state.value = _state.value.copy(hardwareDecodeMode = it) }
        }
        viewModelScope.launch {
            storage.hdrEnabledFlow.collect { _state.value = _state.value.copy(hdrEnabled = it) }
        }
        viewModelScope.launch {
            storage.hdrAlgorithmFlow.collect { _state.value = _state.value.copy(hdrAlgorithm = it) }
        }
        viewModelScope.launch {
            storage.hdrHighlightProtectFlow.collect { _state.value = _state.value.copy(hdrHighlightProtect = it) }
        }
        viewModelScope.launch {
            storage.hdrDynamicRangeExpandFlow.collect { _state.value = _state.value.copy(hdrDynamicRangeExpand = it) }
        }
        viewModelScope.launch {
            storage.dandanDanmakuFlow.collect { _state.value = _state.value.copy(dandanDanmakuEnabled = it) }
        }
        // ===== 审核233 collect =====
        viewModelScope.launch {
            storage.dmShowAreaFlow.collect { _state.value = _state.value.copy(dmShowArea = it) }
        }
        viewModelScope.launch {
            storage.dmStrokeWidthFlow.collect { _state.value = _state.value.copy(dmStrokeWidth = it) }
        }
        viewModelScope.launch {
            storage.dmFontWeightFlow.collect { _state.value = _state.value.copy(dmFontWeight = it) }
        }
        viewModelScope.launch {
            storage.dmLineHeightFlow.collect { _state.value = _state.value.copy(dmLineHeight = it) }
        }
        viewModelScope.launch {
            storage.dmMassiveModeFlow.collect { _state.value = _state.value.copy(dmMassiveMode = it) }
        }
        viewModelScope.launch {
            storage.showVipDanmakuFlow.collect { _state.value = _state.value.copy(showVipDanmaku = it) }
        }
        viewModelScope.launch {
            storage.mergeDanmakuFlow.collect { _state.value = _state.value.copy(mergeDanmaku = it) }
        }
        viewModelScope.launch {
            storage.enableTapDanmakuFlow.collect { _state.value = _state.value.copy(enableTapDanmaku = it) }
        }
        viewModelScope.launch {
            storage.continuePlayingPartFlow.collect { _state.value = _state.value.copy(continuePlayingPart = it) }
        }
        viewModelScope.launch {
            storage.continuePlayInBackgroundFlow.collect { _state.value = _state.value.copy(continuePlayInBackground = it) }
        }
        viewModelScope.launch {
            storage.directExitOnBackFlow.collect { _state.value = _state.value.copy(directExitOnBack = it) }
        }
        viewModelScope.launch {
            storage.enableAutoLongPressSpeedFlow.collect { _state.value = _state.value.copy(enableAutoLongPressSpeed = it) }
        }
        viewModelScope.launch {
            storage.longPressSpeedDefaultFlow.collect { _state.value = _state.value.copy(longPressSpeedDefault = it) }
        }
        viewModelScope.launch {
            storage.minDurationForRcmdFlow.collect { _state.value = _state.value.copy(minDurationForRcmd = it) }
        }
        viewModelScope.launch {
            storage.minPlayForRcmdFlow.collect { _state.value = _state.value.copy(minPlayForRcmd = it) }
        }
        viewModelScope.launch {
            storage.minLikeRatioForRecommendFlow.collect { _state.value = _state.value.copy(minLikeRatioForRecommend = it) }
        }
        viewModelScope.launch {
            storage.exemptFilterForFollowedFlow.collect { _state.value = _state.value.copy(exemptFilterForFollowed = it) }
        }
        viewModelScope.launch {
            storage.applyFilterToRelatedVideosFlow.collect { _state.value = _state.value.copy(applyFilterToRelatedVideos = it) }
        }
        viewModelScope.launch {
            storage.banWordForRecommendFlow.collect { _state.value = _state.value.copy(banWordForRecommend = it) }
        }
        // ===== 审核235 collect =====
        viewModelScope.launch {
            storage.picQualityFlow.collect {
                com.example.piliai.utils.ImageQuality.qualityPercent = if (it == 0) 100 else it
                _state.value = _state.value.copy(picQuality = it)
            }
        }
        viewModelScope.launch {
            storage.subtitleScaleFlow.collect { _state.value = _state.value.copy(subtitleScale = it) }
        }
        viewModelScope.launch {
            storage.subtitlePaddingHFlow.collect { _state.value = _state.value.copy(subtitlePaddingH = it) }
        }
        viewModelScope.launch {
            storage.subtitlePaddingBFlow.collect { _state.value = _state.value.copy(subtitlePaddingB = it) }
        }
        viewModelScope.launch {
            storage.subtitleBgOpacityFlow.collect { _state.value = _state.value.copy(subtitleBgOpacity = it) }
        }
        viewModelScope.launch {
            storage.subtitleStrokeFlow.collect { _state.value = _state.value.copy(subtitleStroke = it) }
        }
        viewModelScope.launch {
            storage.subtitleBoldFlow.collect { _state.value = _state.value.copy(subtitleBold = it) }
        }
    }

    // ===== 审核233 setters =====
    fun setDmShowArea(v: Float) = viewModelScope.launch { storage.setDmShowArea(v) }
    fun setDmStrokeWidth(v: Float) = viewModelScope.launch { storage.setDmStrokeWidth(v) }
    fun setDmFontWeight(v: Int) = viewModelScope.launch { storage.setDmFontWeight(v) }
    fun setDmLineHeight(v: Float) = viewModelScope.launch { storage.setDmLineHeight(v) }
    fun setDmMassiveMode(v: Boolean) = viewModelScope.launch { storage.setDmMassiveMode(v) }
    fun setShowVipDanmaku(v: Boolean) = viewModelScope.launch { storage.setShowVipDanmaku(v) }
    fun setMergeDanmaku(v: Boolean) = viewModelScope.launch { storage.setMergeDanmaku(v) }
    fun setEnableTapDanmaku(v: Boolean) = viewModelScope.launch { storage.setEnableTapDanmaku(v) }
    fun setContinuePlayingPart(v: Boolean) = viewModelScope.launch { storage.setContinuePlayingPart(v) }
    fun setContinuePlayInBackground(v: Boolean) = viewModelScope.launch { storage.setContinuePlayInBackground(v) }
    fun setDirectExitOnBack(v: Boolean) = viewModelScope.launch { storage.setDirectExitOnBack(v) }
    fun setEnableAutoLongPressSpeed(v: Boolean) = viewModelScope.launch { storage.setEnableAutoLongPressSpeed(v) }
    fun setLongPressSpeedDefault(v: Float) = viewModelScope.launch { storage.setLongPressSpeedDefault(v) }
    fun setMinDurationForRcmd(v: Int) = viewModelScope.launch { storage.setMinDurationForRcmd(v) }
    fun setMinPlayForRcmd(v: Int) = viewModelScope.launch { storage.setMinPlayForRcmd(v) }
    fun setMinLikeRatioForRecommend(v: Int) = viewModelScope.launch { storage.setMinLikeRatioForRecommend(v) }
    fun setExemptFilterForFollowed(v: Boolean) = viewModelScope.launch { storage.setExemptFilterForFollowed(v) }
    fun setApplyFilterToRelatedVideos(v: Boolean) = viewModelScope.launch { storage.setApplyFilterToRelatedVideos(v) }
    fun setBanWordForRecommend(v: String) = viewModelScope.launch { storage.setBanWordForRecommend(v) }
    // ===== 审核235 =====
    fun setPicQuality(v: Int) = viewModelScope.launch {
        com.example.piliai.utils.ImageQuality.qualityPercent = if (v == 0) 100 else v
        storage.setPicQuality(v)
    }
    fun setSubtitleScale(v: Float) = viewModelScope.launch { storage.setSubtitleScale(v) }
    fun setSubtitlePaddingH(v: Float) = viewModelScope.launch { storage.setSubtitlePaddingH(v) }
    fun setSubtitlePaddingB(v: Float) = viewModelScope.launch { storage.setSubtitlePaddingB(v) }
    fun setSubtitleBgOpacity(v: Float) = viewModelScope.launch { storage.setSubtitleBgOpacity(v) }
    fun setSubtitleStroke(v: Float) = viewModelScope.launch { storage.setSubtitleStroke(v) }
    fun setSubtitleBold(v: Boolean) = viewModelScope.launch { storage.setSubtitleBold(v) }

    fun setAutoPlay(v: Boolean) = viewModelScope.launch { storage.setAutoPlay(v) }
    fun setLowLatencyAudio(v: Boolean) = viewModelScope.launch { storage.setLowLatencyAudio(v) }
    fun setRenderer(v: Int) = viewModelScope.launch {
        com.example.piliai.utils.RendererPrefs.update(v)
        storage.setRenderer(v)
    }
    fun setPlaybackBackend(id: String) = viewModelScope.launch {
        com.example.piliai.utils.RendererPrefs.updatePlaybackBackend(id)
        storage.setPlaybackBackend(id)
    }
    fun setCompletionAction(action: String) = viewModelScope.launch {
        storage.setCompletionAction(action)
    }
    fun setBufferDuration(v: Int) = viewModelScope.launch {
        com.example.piliai.utils.RendererPrefs.updateBufferDuration(v)
        storage.setBufferDuration(v)
    }
    /** bv P2：切换硬件解码模式（重建播放器生效） */
    fun setHardwareDecodeMode(mode: String) = viewModelScope.launch {
        com.example.piliai.utils.RendererPrefs.updateHardwareDecodeMode(mode)
        storage.setHardwareDecodeMode(mode)
    }
    /** 审核轮210：切换音量归一化 */
    fun setAudioNormalization(v: Boolean) = viewModelScope.launch {
        com.example.piliai.utils.RendererPrefs.updateAudioNormalization(v)
        storage.setAudioNormalization(v)
    }

    /** 审核轮210：切换超分辨率模式 */
    fun setSuperResolutionMode(mode: String) = viewModelScope.launch {
        com.example.piliai.utils.RendererPrefs.updateSuperResolution(mode)
        storage.setSuperResolutionMode(mode)
    }

    fun setVideoQuality(q: String) = viewModelScope.launch { storage.setVideoQuality(q) }
    fun setDanmakuEnabled(v: Boolean) = viewModelScope.launch { storage.setDanmakuEnabled(v) }
    fun setDanmakuOpacity(v: Float) = viewModelScope.launch { storage.setDanmakuOpacity(v) }
    fun setDanmakuFontSize(v: Int) = viewModelScope.launch { storage.setDanmakuFontSize(v) }
    fun setDanmakuSpeed(v: Float) = viewModelScope.launch { storage.setDanmakuSpeed(v) }
    fun setThemeMode(m: String) = viewModelScope.launch { storage.setThemeMode(m) }
    fun setAccentColor(hex: String) = viewModelScope.launch { storage.setAccentColor(hex) }
    fun setAmoled(v: Boolean) = viewModelScope.launch { storage.setAmoled(v) }

    // HDR 色调映射设置
    fun setHdrEnabled(v: Boolean) = viewModelScope.launch {
        com.example.piliai.utils.RendererPrefs.updateHdrToneMapping(v)
        storage.setHdrEnabled(v)
    }
    fun setHdrAlgorithm(v: Int) = viewModelScope.launch {
        com.example.piliai.utils.RendererPrefs.updateHdrAlgorithm(v)
        storage.setHdrAlgorithm(v)
    }
    fun setHdrHighlightProtect(v: Float) = viewModelScope.launch {
        com.example.piliai.utils.RendererPrefs.updateHdrHighlightProtect(v.toDouble())
        storage.setHdrHighlightProtect(v)
    }
    fun setHdrDynamicRangeExpand(v: Float) = viewModelScope.launch {
        com.example.piliai.utils.RendererPrefs.updateHdrDynamicRangeExpand(v.toDouble())
        storage.setHdrDynamicRangeExpand(v)
    }
    /** 弹弹play 弹幕源开关（配置了凭据后在番剧页生效） */
    fun setDandanDanmakuEnabled(v: Boolean) = viewModelScope.launch { storage.setDandanDanmaku(v) }

    // 审核216：第一批 Flutter 高频设置项
    fun setAutoEnterFullscreen(v: Boolean) = viewModelScope.launch { storage.setAutoEnterFullscreen(v) }
    fun setPauseOnMinimize(v: Boolean) = viewModelScope.launch { storage.setPauseOnMinimize(v) }
    fun setShowOnlineTotal(v: Boolean) = viewModelScope.launch { storage.setShowOnlineTotal(v) }
    fun setAudioQa(v: String) = viewModelScope.launch { storage.setAudioQa(v) }
    fun setPreferCodec(v: String) = viewModelScope.launch { storage.setPreferCodec(v) }
    fun setVideoQaCellular(v: String) = viewModelScope.launch { storage.setVideoQaCellular(v) }
    fun setAudioQaCellular(v: String) = viewModelScope.launch { storage.setAudioQaCellular(v) }
    fun setPreferCodecCellular(v: String) = viewModelScope.launch { storage.setPreferCodecCellular(v) }
    fun setDmCountEnabled(v: Boolean) = viewModelScope.launch { storage.setDmCountEnabled(v) }
    fun setSkipIntroSec(v: Int) = viewModelScope.launch { storage.setSkipIntroSec(v) }
    fun setSkipOutroSec(v: Int) = viewModelScope.launch { storage.setSkipOutroSec(v) }
}
