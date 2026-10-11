package com.example.piliai.utils

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.catch
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * App-wide storage manager
 * Replaces Flutter Hive CE storage
 */
val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class StorageManager(private val context: Context) {
    
    companion object {
        private val THEME_MODE_KEY = stringPreferencesKey("themeMode")
        private val ACCENT_COLOR_KEY = stringPreferencesKey("accentColor")
        private val VIDEO_QUALITY_KEY = stringPreferencesKey("defaultVideoQa")
        private val AUTO_PLAY_KEY = booleanPreferencesKey("autoPlayEnable")
        private val SUPER_RES_KEY = stringPreferencesKey("superResolutionMode")
        private val AUDIO_NORM_KEY = booleanPreferencesKey("audioNormalization")
        private val FULLSCREEN_MODE_KEY = stringPreferencesKey("fullScreenMode")
        private val UI_SCALE_KEY = floatPreferencesKey("uiScale")
        private val LANGUAGE_KEY = stringPreferencesKey("language")
        private val DARK_MODE_KEY = booleanPreferencesKey("darkMode")
        private val AMOLED_KEY = booleanPreferencesKey("amoledBlack")
        // Kazumi 特性：视频渲染器（0=SurfaceView默认 1=TextureView）
        private val RENDERER_KEY = intPreferencesKey("videoRenderer")
        private val PLAYBACK_BACKEND_KEY = stringPreferencesKey("playbackBackend")
        private val COMPLETION_ACTION_KEY = stringPreferencesKey("completionAction")
        // Kazumi 特性：低延迟音频（缩短 AudioTrack 缓冲，代价是弱网易卡顿）
        private val LOW_LATENCY_AUDIO_KEY = booleanPreferencesKey("lowLatencyAudio")
        private val NOTIFICATION_ENABLED_KEY = booleanPreferencesKey("notificationEnabled")
        
        // Video settings
        private val PLAYBACK_SPEED_KEY = floatPreferencesKey("playbackSpeed")
        private val VOLUME_KEY = floatPreferencesKey("volume")
        private val SUBTITLE_ENABLED_KEY = booleanPreferencesKey("subtitleEnabled")
        private val SUBTITLE_FONT_SIZE_KEY = intPreferencesKey("subtitleFontSize")
        
        // Danmaku settings
        private val DANMAKU_ENABLED_KEY = booleanPreferencesKey("enableShowDanmaku")
        private val DANMAKU_OPACITY_KEY = floatPreferencesKey("danmakuOpacity")
        private val DANMAKU_SCALE_KEY = floatPreferencesKey("danmakuScale")
        private val DANMAKU_FONT_SIZE_KEY = intPreferencesKey("danmakuFontSize")
        private val DANMAKU_SPEED_KEY = floatPreferencesKey("danmakuSpeed")
        private val DANMAKU_SHOW_TOP_KEY = booleanPreferencesKey("danmakuShowTop")
        private val DANMAKU_SHOW_BOTTOM_KEY = booleanPreferencesKey("danmakuShowBottom")
        private val TODAY_WATCH_MODE_KEY = stringPreferencesKey("todayWatchMode")
        private val TODAY_WATCH_STRATEGY_KEY = stringPreferencesKey("todayWatchStrategy")
        private val BUFFER_DURATION_KEY = intPreferencesKey("bufferDurationMs")
        private val DANDAN_DANMAKU_KEY = booleanPreferencesKey("dandanDanmakuEnabled")
        // 审核216：第一批 Flutter 高频设置项（PiliPlus enableAutoEnter/pauseOnMinimize/enableOnlineTotal）
        private val AUTO_ENTER_KEY = booleanPreferencesKey("enableAutoEnter")
        private val PAUSE_ON_MINIMIZE_KEY = booleanPreferencesKey("pauseOnMinimize")
        private val ONLINE_TOTAL_KEY = booleanPreferencesKey("enableOnlineTotal")
        // 审核219：默认音质（Flutter defaultAudioQa 对齐；B站音频流 id）
        private val AUDIO_QA_KEY = stringPreferencesKey("defaultAudioQa")
        // 审核220：编解码偏好（Flutter preferCodecs 对齐；avc/hevc/av1）
        private val PREFER_CODEC_KEY = stringPreferencesKey("preferCodecs")
        // 审核221：蜂窝网络下的画质/音质/编码偏好（Flutter *Cellular 键对齐）
        private val VIDEO_QA_CELLULAR_KEY = stringPreferencesKey("defaultVideoQaCellular")
        private val AUDIO_QA_CELLULAR_KEY = stringPreferencesKey("defaultAudioQaCellular")
        private val PREFER_CODEC_CELLULAR_KEY = stringPreferencesKey("preferCodecsCellular")
        // 审核222：播放器显示弹幕总数（Flutter enableDmCount）
        private val DM_COUNT_KEY = booleanPreferencesKey("enableDmCount")
        // 片头片尾跳过（秒，0 关闭）
        private val SKIP_INTRO_KEY = intPreferencesKey("skipIntroSec")
        private val SKIP_OUTRO_KEY = intPreferencesKey("skipOutroSec")
        
        // HDR 色调映射设置
        private val HDR_ENABLED_KEY = booleanPreferencesKey("hdrToneMappingEnabled")
        private val HDR_ALGORITHM_KEY = intPreferencesKey("hdrAlgorithm")
        private val HDR_HIGHLIGHT_PROTECT_KEY = floatPreferencesKey("hdrHighlightProtect")
        private val HDR_DYNAMIC_RANGE_EXPAND_KEY = floatPreferencesKey("hdrDynamicRangeExpand")
        
        @Volatile
        private var instance: StorageManager? = null

        /** 兼容旧调用点：全局单例（进程内复用同一个 DataStore）。 */
        fun getInstance(context: android.content.Context): StorageManager =
            instance ?: synchronized(this) {
                instance ?: StorageManager(context.applicationContext).also { instance = it }
            }
    }

        // Account settings
        private val IS_LOGGED_IN_KEY = booleanPreferencesKey("isLoggedIn")
        private val USER_MID_KEY = longPreferencesKey("userMid")
        private val USER_NAME_KEY = stringPreferencesKey("userName")
        private val ACCESS_TOKEN_KEY = stringPreferencesKey("accessToken")
        private val REFRESH_TOKEN_KEY = stringPreferencesKey("refreshToken")
    // Theme
    val themeModeFlow: Flow<String> = context.dataStore.data
        .map { preferences -> preferences[THEME_MODE_KEY] ?: "system" }

    /** 主题强调色（十六进制字符串，空 = 默认粉/B站绿） */
    val accentColorFlow: Flow<String> = context.dataStore.data
        .map { preferences -> preferences[ACCENT_COLOR_KEY] ?: "" }
    
    /** AMOLED 纯黑主题（暗色下 surface/background = #000000），默认 false */
    val amoledFlow: Flow<Boolean> = context.dataStore.data
        .map { preferences -> preferences[AMOLED_KEY] ?: false }

    val videoQualityFlow: Flow<String> = context.dataStore.data
        .map { preferences -> preferences[VIDEO_QUALITY_KEY] ?: "auto" }

    /** Kazumi：视频渲染器（0=SurfaceView 1=TextureView） */
    val rendererFlow: Flow<Int> = context.dataStore.data
        .map { preferences -> preferences[RENDERER_KEY] ?: 0 }

    val playbackBackendFlow: Flow<String> = context.dataStore.data
        .map { preferences -> preferences[PLAYBACK_BACKEND_KEY] ?: "media3" }

    val completionActionFlow: Flow<String> = context.dataStore.data
        .map { preferences -> preferences[COMPLETION_ACTION_KEY] ?: "next" }

    /** Kazumi：低延迟音频（AudioTrack 缓冲最小化） */
    val lowLatencyAudioFlow: Flow<Boolean> = context.dataStore.data
        .map { preferences -> preferences[LOW_LATENCY_AUDIO_KEY] ?: false }
    
    val autoPlayFlow: Flow<Boolean> = context.dataStore.data
        .map { preferences -> preferences[AUTO_PLAY_KEY] ?: false }
    
    // Video playback
    val playbackSpeedFlow: Flow<Float> = context.dataStore.data
        .map { preferences -> preferences[PLAYBACK_SPEED_KEY] ?: 1.0f }
    
    val volumeFlow: Flow<Float> = context.dataStore.data
        .map { preferences -> preferences[VOLUME_KEY] ?: 1.0f }
    
    val subtitleEnabledFlow: Flow<Boolean> = context.dataStore.data
        .map { preferences -> preferences[SUBTITLE_ENABLED_KEY] ?: true }
    
    // Danmaku
    val danmakuEnabledFlow: Flow<Boolean> = context.dataStore.data
        .map { preferences -> preferences[DANMAKU_ENABLED_KEY] ?: true }
    
    val danmakuOpacityFlow: Flow<Float> = context.dataStore.data
        .map { preferences -> preferences[DANMAKU_OPACITY_KEY] ?: 1.0f }

    val danmakuFontSizeFlow: Flow<Int> = context.dataStore.data
        .map { preferences -> preferences[DANMAKU_FONT_SIZE_KEY] ?: 25 }

    val danmakuSpeedFlow: Flow<Float> = context.dataStore.data
        .map { preferences -> preferences[DANMAKU_SPEED_KEY] ?: 1.0f }
    
    // Account
    val isLoggedInFlow: Flow<Boolean> = context.dataStore.data
        .map { preferences -> preferences[IS_LOGGED_IN_KEY] ?: false }
    
    val userMidFlow: Flow<Long> = context.dataStore.data
        .map { preferences -> preferences[USER_MID_KEY] ?: 0L }
    
    val userNameFlow: Flow<String> = context.dataStore.data
        .map { preferences -> preferences[USER_NAME_KEY] ?: "" }
    
    // Methods
    suspend fun setThemeMode(mode: String) {
        context.dataStore.edit { preferences ->
            preferences[THEME_MODE_KEY] = mode
        }
    }

    suspend fun setAccentColor(color: String) {
        context.dataStore.edit { preferences ->
            preferences[ACCENT_COLOR_KEY] = color
        }
    }
    
    suspend fun setAmoled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[AMOLED_KEY] = enabled
        }
    }

    /** Kazumi：设置视频渲染器（0=SurfaceView 1=TextureView） */
    suspend fun setRenderer(v: Int) {
        context.dataStore.edit { preferences ->
            preferences[RENDERER_KEY] = v
        }
    }

    suspend fun setPlaybackBackend(id: String) {
        context.dataStore.edit { preferences ->
            preferences[PLAYBACK_BACKEND_KEY] = id
        }
    }

    suspend fun setCompletionAction(action: String) {
        context.dataStore.edit { preferences ->
            preferences[COMPLETION_ACTION_KEY] = action
        }
    }

    /** Kazumi：设置低延迟音频 */
    suspend fun setLowLatencyAudio(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[LOW_LATENCY_AUDIO_KEY] = enabled
        }
    }

    suspend fun setVideoQuality(quality: String) {
        context.dataStore.edit { preferences ->
            preferences[VIDEO_QUALITY_KEY] = quality
        }
    }
    
    suspend fun setAutoPlay(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[AUTO_PLAY_KEY] = enabled
        }
    }
    
    suspend fun setPlaybackSpeed(speed: Float) {
        context.dataStore.edit { preferences ->
            preferences[PLAYBACK_SPEED_KEY] = speed
        }
    }
    
    suspend fun setVolume(volume: Float) {
        context.dataStore.edit { preferences ->
            preferences[VOLUME_KEY] = volume
        }
    }
    
    suspend fun setSubtitleEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[SUBTITLE_ENABLED_KEY] = enabled
        }
    }
    
    suspend fun setDanmakuEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[DANMAKU_ENABLED_KEY] = enabled
        }
    }
    
    suspend fun setDanmakuOpacity(opacity: Float) {
        context.dataStore.edit { preferences ->
            preferences[DANMAKU_OPACITY_KEY] = opacity
        }
    }

    val danmakuScaleFlow: Flow<Float> = context.dataStore.data
        .map { preferences -> preferences[DANMAKU_SCALE_KEY] ?: 1.0f }

    suspend fun setDanmakuScale(scale: Float) {
        context.dataStore.edit { preferences -> preferences[DANMAKU_SCALE_KEY] = scale }
    }

    suspend fun setDanmakuFontSize(size: Int) {
        context.dataStore.edit { preferences -> preferences[DANMAKU_FONT_SIZE_KEY] = size }
    }

    suspend fun setDanmakuSpeed(speed: Float) {
        context.dataStore.edit { preferences -> preferences[DANMAKU_SPEED_KEY] = speed }
    }

    val danmakuShowTopFlow: Flow<Boolean> = context.dataStore.data
        .map { preferences -> preferences[DANMAKU_SHOW_TOP_KEY] ?: true }

    suspend fun setDanmakuShowTop(enabled: Boolean) {
        context.dataStore.edit { preferences -> preferences[DANMAKU_SHOW_TOP_KEY] = enabled }
    }

    val danmakuShowBottomFlow: Flow<Boolean> = context.dataStore.data
        .map { preferences -> preferences[DANMAKU_SHOW_BOTTOM_KEY] ?: true }

    suspend fun setDanmakuShowBottom(enabled: Boolean) {
        context.dataStore.edit { preferences -> preferences[DANMAKU_SHOW_BOTTOM_KEY] = enabled }
    }

    /** 今日推荐模式（relaxed/learn） */
    val todayWatchModeFlow: Flow<String> = context.dataStore.data
        .map { preferences -> preferences[TODAY_WATCH_MODE_KEY] ?: "relaxed" }

    suspend fun setTodayWatchMode(mode: String) {
        context.dataStore.edit { preferences -> preferences[TODAY_WATCH_MODE_KEY] = mode }
    }

    /** 今日推荐策略（balanced/affinity/explore） */
    val todayWatchStrategyFlow: Flow<String> = context.dataStore.data
        .map { preferences -> preferences[TODAY_WATCH_STRATEGY_KEY] ?: "balanced" }

    suspend fun setTodayWatchStrategy(strategy: String) {
        context.dataStore.edit { preferences -> preferences[TODAY_WATCH_STRATEGY_KEY] = strategy }
    }

    /**
     * VOD 缓冲时长（毫秒）。默认 16000（piliplus 上游默认）。
     * 作用于 ExoPlayer 的 LoadControl（见 Media3LoadControlFactory）。
     */
    val bufferDurationFlow: Flow<Int> = context.dataStore.data
        .map { preferences -> preferences[BUFFER_DURATION_KEY] ?: 16_000 }

    // 审核轮210：超分辨率模式（disable/efficiency/quality）
    val superResolutionFlow: Flow<String> = context.dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { it[SUPER_RES_KEY] ?: "disable" }

    suspend fun setSuperResolutionMode(mode: String) {
        context.dataStore.edit { it[SUPER_RES_KEY] = mode }
    }

    // 审核轮210：音量归一化（AudioNormalizationProcessor 实时流处理）
    val audioNormFlow: Flow<Boolean> = context.dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { it[AUDIO_NORM_KEY] ?: false }

    suspend fun setAudioNormalization(enabled: Boolean) {
        context.dataStore.edit { it[AUDIO_NORM_KEY] = enabled }
    }
    suspend fun setBufferDuration(ms: Int) {
        context.dataStore.edit { preferences -> preferences[BUFFER_DURATION_KEY] = ms }
    }

    // 审核216：自动全屏（进入播放页即全屏，PiliPlus enableAutoEnter）
    val autoEnterFullscreenFlow: Flow<Boolean> = context.dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { it[AUTO_ENTER_KEY] ?: false }

    suspend fun setAutoEnterFullscreen(enabled: Boolean) {
        context.dataStore.edit { it[AUTO_ENTER_KEY] = enabled }
    }

    // 审核216：切后台自动暂停（PiliPlus pauseOnMinimize）
    val pauseOnMinimizeFlow: Flow<Boolean> = context.dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { it[PAUSE_ON_MINIMIZE_KEY] ?: false }

    suspend fun setPauseOnMinimize(enabled: Boolean) {
        context.dataStore.edit { it[PAUSE_ON_MINIMIZE_KEY] = enabled }
    }

    // 审核216：显示实时在线人数（PiliPlus enableOnlineTotal；loadOnlineCount 已有数据源）
    val showOnlineTotalFlow: Flow<Boolean> = context.dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { it[ONLINE_TOTAL_KEY] ?: true }

    suspend fun setShowOnlineTotal(enabled: Boolean) {
        context.dataStore.edit { it[ONLINE_TOTAL_KEY] = enabled }
    }

    // 审核219：默认音质选择（"0"=最高/30216=64k/30232=132k/30280=192k/30251=Hi-Res）
    val audioQaFlow: Flow<String> = context.dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { it[AUDIO_QA_KEY] ?: "0" }

    suspend fun setAudioQa(qn: String) {
        context.dataStore.edit { it[AUDIO_QA_KEY] = qn }
    }

    // 审核220：解码器偏好（avc 省电兼容 / hevc 高压缩 / av1 高效）
    val preferCodecFlow: Flow<String> = context.dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { it[PREFER_CODEC_KEY] ?: "avc" }

    suspend fun setPreferCodec(codec: String) {
        context.dataStore.edit { it[PREFER_CODEC_KEY] = codec }
    }

    // 审核221：蜂窝网络覆盖项（空串=跟随 WiFi 设置）
    val videoQaCellularFlow: Flow<String> = context.dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { it[VIDEO_QA_CELLULAR_KEY] ?: "" }

    val audioQaCellularFlow: Flow<String> = context.dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { it[AUDIO_QA_CELLULAR_KEY] ?: "" }

    val preferCodecCellularFlow: Flow<String> = context.dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { it[PREFER_CODEC_CELLULAR_KEY] ?: "" }

    suspend fun setVideoQaCellular(qn: String) {
        context.dataStore.edit { it[VIDEO_QA_CELLULAR_KEY] = qn }
    }

    suspend fun setAudioQaCellular(qn: String) {
        context.dataStore.edit { it[AUDIO_QA_CELLULAR_KEY] = qn }
    }

    suspend fun setPreferCodecCellular(codec: String) {
        context.dataStore.edit { it[PREFER_CODEC_CELLULAR_KEY] = codec }
    }

    // 审核222：播放器显示弹幕总数
    val dmCountEnabledFlow: Flow<Boolean> = context.dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { it[DM_COUNT_KEY] ?: false }

    suspend fun setDmCountEnabled(enabled: Boolean) {
        context.dataStore.edit { it[DM_COUNT_KEY] = enabled }
    }

    val skipIntroSecFlow: Flow<Int> = context.dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { it[SKIP_INTRO_KEY] ?: 0 }

    val skipOutroSecFlow: Flow<Int> = context.dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { it[SKIP_OUTRO_KEY] ?: 0 }

    suspend fun setSkipIntroSec(sec: Int) {
        context.dataStore.edit { it[SKIP_INTRO_KEY] = sec.coerceIn(0, 180) }
    }

    suspend fun setSkipOutroSec(sec: Int) {
        context.dataStore.edit { it[SKIP_OUTRO_KEY] = sec.coerceIn(0, 180) }
    }

    /**
     * 是否启用 DanDan（弹弹play）弹幕源作为 B 站弹幕的补充。
     * 默认 false。启用后番剧页会额外拉取 DanDan 弹幕（需配置 API 凭据）。
     */
    val dandanDanmakuFlow: Flow<Boolean> = context.dataStore.data
        .map { preferences -> preferences[DANDAN_DANMAKU_KEY] ?: false }

    suspend fun setDandanDanmaku(enabled: Boolean) {
        context.dataStore.edit { preferences -> preferences[DANDAN_DANMAKU_KEY] = enabled }
    }

    /**
     * HDR 色调映射开关。默认 false（关闭）。
     * 启用后对 SDR 内容应用色调映射效果。
     */
    val hdrEnabledFlow: Flow<Boolean> = context.dataStore.data
        .map { preferences -> preferences[HDR_ENABLED_KEY] ?: false }

    suspend fun setHdrEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences -> preferences[HDR_ENABLED_KEY] = enabled }
    }

    /**
     * HDR 色调映射算法（0=Reinhard, 1=ACES, 2=Mobius）。
     * 默认 0（Reinhard，平衡性能与效果）。
     */
    val hdrAlgorithmFlow: Flow<Int> = context.dataStore.data
        .map { preferences -> preferences[HDR_ALGORITHM_KEY] ?: 0 }

    suspend fun setHdrAlgorithm(algorithm: Int) {
        context.dataStore.edit { preferences -> preferences[HDR_ALGORITHM_KEY] = algorithm }
    }

    /**
     * HDR 高光保护强度（0.0~1.0）。防止过曝。
     * 默认 0.5。
     */
    val hdrHighlightProtectFlow: Flow<Float> = context.dataStore.data
        .map { preferences -> preferences[HDR_HIGHLIGHT_PROTECT_KEY] ?: 0.5f }

    suspend fun setHdrHighlightProtect(value: Float) {
        context.dataStore.edit { preferences -> preferences[HDR_HIGHLIGHT_PROTECT_KEY] = value }
    }

    /**
     * HDR 动态范围扩展强度（0.0~1.0）。
     * 默认 0.5。
     */
    val hdrDynamicRangeExpandFlow: Flow<Float> = context.dataStore.data
        .map { preferences -> preferences[HDR_DYNAMIC_RANGE_EXPAND_KEY] ?: 0.5f }

    suspend fun setHdrDynamicRangeExpand(value: Float) {
        context.dataStore.edit { preferences -> preferences[HDR_DYNAMIC_RANGE_EXPAND_KEY] = value }
    }

    suspend fun setSubtitleFontSize(size: Int) {
        context.dataStore.edit { preferences -> preferences[SUBTITLE_FONT_SIZE_KEY] = size }
    }

    suspend fun clearAll() {
        context.dataStore.edit { it.clear() }
    }
    
    suspend fun login(mid: Long, name: String, accessToken: String, refreshToken: String) {
        context.dataStore.edit { preferences ->
            preferences[IS_LOGGED_IN_KEY] = true
            preferences[USER_MID_KEY] = mid
            preferences[USER_NAME_KEY] = name
            preferences[ACCESS_TOKEN_KEY] = accessToken
            preferences[REFRESH_TOKEN_KEY] = refreshToken
        }
    }
    
    suspend fun logout() {
        context.dataStore.edit { preferences ->
            preferences[IS_LOGGED_IN_KEY] = false
            preferences[USER_MID_KEY] = 0L
            preferences[USER_NAME_KEY] = ""
            preferences[ACCESS_TOKEN_KEY] = ""
            preferences[REFRESH_TOKEN_KEY] = ""
        }
    }
}
