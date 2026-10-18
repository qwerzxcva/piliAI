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

    /** 审核296：暴露 applicationContext 供更新检查读 versionName */
    internal val appContext: Context get() = context.applicationContext

    companion object {
        private val THEME_MODE_KEY = stringPreferencesKey("themeMode")
        private val ACCENT_COLOR_KEY = stringPreferencesKey("accentColor")
        private val VIDEO_QUALITY_KEY = stringPreferencesKey("defaultVideoQa")
        private val AUTO_PLAY_KEY = booleanPreferencesKey("autoPlayEnable")
        // 审核252：订阅源 RSS 只保留能解析出集数的条目
        // （animeko ContainsAnyEpisodeInfo，RssMediaSourceEngine.kt:157-159）
        private val RSS_EPISODE_ONLY_KEY = booleanPreferencesKey("rssEpisodeOnly")
        // 审核259：弹幕每秒上限（bv DanmakuRateLimiter）。0 = 不限
        private val DM_MAX_PER_SEC_KEY = intPreferencesKey("danmakuMaxPerSecond")
        private val SUPER_RES_KEY = stringPreferencesKey("superResolutionMode")
        private val AUDIO_NORM_KEY = booleanPreferencesKey("audioNormalization")
        private val LANGUAGE_KEY = stringPreferencesKey("language")
        private val DARK_MODE_KEY = booleanPreferencesKey("darkMode")
        private val AMOLED_KEY = booleanPreferencesKey("amoledBlack")
        // Kazumi 特性：视频渲染器（0=SurfaceView默认 1=TextureView）
        private val RENDERER_KEY = intPreferencesKey("videoRenderer")
        private val PLAYBACK_BACKEND_KEY = stringPreferencesKey("playbackBackend")
        // 审核279：首页数据源偏好（auto=自动降级 / recommend=推荐流 / popular=热门）。
        // 某个接口被风控时用户可手动切到能用的，不用干等。
        private val HOME_FEED_SOURCE_KEY = stringPreferencesKey("homeFeedSource")
        // 审核368：评论过滤（关键词/屏蔽用户/带货）
        private val REPLY_BAN_WORDS_KEY = stringPreferencesKey("replyBanWords")
        private val REPLY_BLOCKED_MIDS_KEY = stringPreferencesKey("replyBlockedMids")
        private val REPLY_HIDE_GOODS_KEY = booleanPreferencesKey("replyHideGoods")
        // 审核322：底栏配置，逗号分隔稳定 tab id；默认全量显示。
        private val BOTTOM_TABS_KEY = stringPreferencesKey("bottomTabs")
        private val COMPLETION_ACTION_KEY = stringPreferencesKey("completionAction")
        // Kazumi 特性：低延迟音频（缩短 AudioTrack 缓冲，代价是弱网易卡顿）
        private val LOW_LATENCY_AUDIO_KEY = booleanPreferencesKey("lowLatencyAudio")
        private val NOTIFICATION_ENABLED_KEY = booleanPreferencesKey("notificationEnabled")
        // 审核291：buvid3/buvid4 匿名风控指纹持久化。B 站对无 buvid 的请求
        // 容易 -352/-412（搜索/点赞/推荐流尤其明显）。原来只在内存里，进程一杀
        // 就丢，重启后 B 站视为「新设备」，等于每次冷启动都重新攒信任。
        private val BUVID3_KEY = stringPreferencesKey("buvid3")
        private val BUVID4_KEY = stringPreferencesKey("buvid4")
        
        // Video settings
        private val PLAYBACK_SPEED_KEY = floatPreferencesKey("playbackSpeed")
        // 审核313：倍速档位列表（逗号分隔的浮点数字符串）。原来是播放器里
        // 硬编码 listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)，用户想加
        // 3.0x 或去掉不用的档位只能改代码。
        private val PLAYBACK_SPEED_LIST_KEY = stringPreferencesKey("playbackSpeedList")

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
        // bv P2：硬件解码开关（auto/hardware/software）
        private val HW_DECODE_KEY = stringPreferencesKey("hardwareDecodeMode")
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
        // 审核233：弹幕细节（PiliPlus danmakuShowArea/danmakuStrokeWidth/danmakuFontWeight/danmakuLineHeight/danmakuMassiveMode/showVipDanmaku/mergeDanmaku/enableTapDm）
        private val DM_SHOW_AREA_KEY = floatPreferencesKey("danmakuShowArea")
        private val DM_STROKE_WIDTH_KEY = floatPreferencesKey("danmakuStrokeWidth")
        private val DM_FONT_WEIGHT_KEY = intPreferencesKey("danmakuFontWeight")
        private val DM_LINE_HEIGHT_KEY = floatPreferencesKey("danmakuLineHeight")
        private val DM_MASSIVE_MODE_KEY = booleanPreferencesKey("danmakuMassiveMode")
        private val DM_VIP_KEY = booleanPreferencesKey("showVipDanmaku")
        private val DM_MERGE_KEY = booleanPreferencesKey("mergeDanmaku")
        private val DM_TAP_KEY = booleanPreferencesKey("enableTapDm")
        // 审核233：播放行为（PiliPlus continuePlayingPart/continuePlayInBackground/directExitOnBack/enableAutoLongPressSpeed/longPressSpeedDefault）
        private val CONTINUE_PART_KEY = booleanPreferencesKey("continuePlayingPart")
        private val BG_PLAY_KEY = booleanPreferencesKey("continuePlayInBackground")
        private val DIRECT_EXIT_KEY = booleanPreferencesKey("directExitOnBack")
        private val LONG_PRESS_SPEED_KEY = booleanPreferencesKey("enableAutoLongPressSpeed")
        private val LONG_PRESS_SPEED_VAL_KEY = floatPreferencesKey("longPressSpeedDefault")
        // 审核233：推荐过滤（PiliPlus recommend_filter 全套）
        private val MIN_DURATION_RCMD_KEY = intPreferencesKey("minDurationForRcmd")
        private val MIN_PLAY_RCMD_KEY = intPreferencesKey("minPlayForRcmd")
        private val MIN_LIKE_RATIO_RCMD_KEY = intPreferencesKey("minLikeRatioForRecommend")
        private val EXEMPT_FOLLOWED_KEY = booleanPreferencesKey("exemptFilterForFollowed")
        private val FILTER_RELATED_KEY = booleanPreferencesKey("applyFilterToRelatedVideos")
        private val BAN_WORD_RCMD_KEY = stringPreferencesKey("banWordForRecommend")
        // 审核370：首页 UP 主屏蔽/白名单（mid 逗号分隔）
        private val BLOCKED_UPS_KEY = stringPreferencesKey("blockedUps")
        private val WHITELIST_UPS_KEY = stringPreferencesKey("whitelistUps")
        // 审核235：图片质量（PiliPlus defaultPicQa；0=100原图 2/4/10 压缩档）+字幕细节6键
        private val PIC_QUALITY_KEY = intPreferencesKey("defaultPicQa")
        private val SUB_SCALE_KEY = floatPreferencesKey("subtitleFontScale")
        private val SUB_PAD_H_KEY = floatPreferencesKey("subtitlePaddingH")
        private val SUB_PAD_B_KEY = floatPreferencesKey("subtitlePaddingB")
        private val SUB_BG_OPACITY_KEY = floatPreferencesKey("subtitleBgOpacity")
        private val SUB_STROKE_KEY = floatPreferencesKey("subtitleStrokeWidth")
        private val SUB_BOLD_KEY = booleanPreferencesKey("subtitleFontWeight")
        
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

        /** 审核313：默认倍速档位 */
        val DEFAULT_SPEED_LIST = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)

        /** 审核313：解析存储的档位串；非法/为空回退默认 */
        fun parseSpeedList(raw: String?): List<Float> {
            if (raw.isNullOrBlank()) return DEFAULT_SPEED_LIST
            val parsed = raw.split(',').mapNotNull { it.trim().toFloatOrNull() }
                .filter { it in SPEED_MIN..SPEED_MAX }
            return if (parsed.isEmpty()) DEFAULT_SPEED_LIST else normalizeSpeedList(parsed)
        }

        /** 审核313：排序去重，并保证含 1.0x（正常速） */
        fun normalizeSpeedList(speeds: List<Float>): List<Float> {
            val valid = speeds.filter { it in SPEED_MIN..SPEED_MAX }
            return (valid + 1.0f).distinct().sorted()
        }

        /** 审核313：档位取值边界（mpv/ExoPlayer 安全范围） */
        const val SPEED_MIN = 0.25f
        const val SPEED_MAX = 5.0f

        /** 审核313：序列化单个档位（去掉多余的 0，避免 1.0000001 之类） */
        fun formatSpeed(v: Float): String {
            val r = Math.round(v * 100f) / 100f
            return if (r % 1.0f == 0.0f) r.toInt().toString() else r.toString()
        }
    }

        // Account settings
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

    /** 审核279：首页数据源偏好（auto/recommend/popular） */
    val homeFeedSourceFlow: Flow<String> = context.dataStore.data
        .map { preferences -> preferences[HOME_FEED_SOURCE_KEY] ?: "auto" }

    /** 审核368：评论过滤配置打包（关键词 + 屏蔽用户 + 带货） */
    val replyFilterFlow: Flow<com.example.piliai.utils.ReplyFilter.Config> =
        context.dataStore.data.map { p ->
            com.example.piliai.utils.ReplyFilter.Config(
                banWords = p[REPLY_BAN_WORDS_KEY] ?: "",
                blockedMids = p[REPLY_BLOCKED_MIDS_KEY] ?: "",
                hideGoodsComments = p[REPLY_HIDE_GOODS_KEY] ?: false,
            )
        }

    suspend fun setReplyBanWords(v: String) {
        context.dataStore.edit { it[REPLY_BAN_WORDS_KEY] = v }
    }

    suspend fun setReplyBlockedMids(v: String) {
        context.dataStore.edit { it[REPLY_BLOCKED_MIDS_KEY] = v }
    }

    suspend fun setReplyHideGoods(v: Boolean) {
        context.dataStore.edit { it[REPLY_HIDE_GOODS_KEY] = v }
    }

    val bottomTabsFlow: Flow<String> = context.dataStore.data
        .map { preferences -> preferences[BOTTOM_TABS_KEY] ?: "home,dynamics,live,mine" }

    suspend fun setBottomTabs(ids: List<String>) {
        context.dataStore.edit { preferences -> preferences[BOTTOM_TABS_KEY] = ids.joinToString(",") }
    }

    /**
     * 审核291：持久化的 buvid3（匿名风控指纹）。
     *
     * 空串表示尚未获取过——AccountSession.ensureBuvid 会先读这里，
     * 取不到才请求 finger/spi，拿到后回写。这样重启 App 后仍是同一
     * 设备指纹，不会每次冷启动都被 B 站当成新设备。
     */
    val buvid3Flow: Flow<String> = context.dataStore.data
        .map { preferences -> preferences[BUVID3_KEY] ?: "" }

    /** 审核291：持久化的 buvid4 */
    val buvid4Flow: Flow<String> = context.dataStore.data
        .map { preferences -> preferences[BUVID4_KEY] ?: "" }

    /** Kazumi：低延迟音频（AudioTrack 缓冲最小化） */
    val lowLatencyAudioFlow: Flow<Boolean> = context.dataStore.data
        .map { preferences -> preferences[LOW_LATENCY_AUDIO_KEY] ?: false }
    
    val autoPlayFlow: Flow<Boolean> = context.dataStore.data
        .map { preferences -> preferences[AUTO_PLAY_KEY] ?: false }

    /** 审核252：RSS 是否只保留能解析出集数的条目。默认 false（老行为） */
    val rssEpisodeOnlyFlow: Flow<Boolean> = context.dataStore.data
        .map { preferences -> preferences[RSS_EPISODE_ONLY_KEY] ?: false }

    suspend fun setRssEpisodeOnly(enabled: Boolean) {
        context.dataStore.edit { it[RSS_EPISODE_ONLY_KEY] = enabled }
    }

    /** 审核259：弹幕每秒上限，0 = 不限 */
    val danmakuMaxPerSecondFlow: Flow<Int> = context.dataStore.data
        .map { preferences -> preferences[DM_MAX_PER_SEC_KEY] ?: 0 }

    suspend fun setDanmakuMaxPerSecond(count: Int) {
        context.dataStore.edit { it[DM_MAX_PER_SEC_KEY] = count.coerceIn(0, 200) }
    }
    
    // Video playback
    val playbackSpeedFlow: Flow<Float> = context.dataStore.data
        .map { preferences -> preferences[PLAYBACK_SPEED_KEY] ?: 1.0f }

    /**
     * 审核313：倍速档位列表（升序去重）。空串/非法值回退默认档位。
     */
    val playbackSpeedListFlow: Flow<List<Float>> = context.dataStore.data
        .map { prefs -> parseSpeedList(prefs[PLAYBACK_SPEED_LIST_KEY]) }

    /** 审核313：设置倍速档位（会排序去重，并保证含 1.0x） */
    suspend fun setPlaybackSpeedList(speeds: List<Float>) {
        val normalized = normalizeSpeedList(speeds)
        context.dataStore.edit { prefs ->
            prefs[PLAYBACK_SPEED_LIST_KEY] = normalized.joinToString(",") { formatSpeed(it) }
        }
    }
    
    // Danmaku
    val danmakuEnabledFlow: Flow<Boolean> = context.dataStore.data
        .map { preferences -> preferences[DANMAKU_ENABLED_KEY] ?: true }
    
    val danmakuOpacityFlow: Flow<Float> = context.dataStore.data
        .map { preferences -> preferences[DANMAKU_OPACITY_KEY] ?: 1.0f }

    val danmakuFontSizeFlow: Flow<Int> = context.dataStore.data
        .map { preferences -> preferences[DANMAKU_FONT_SIZE_KEY] ?: 25 }

    val danmakuSpeedFlow: Flow<Float> = context.dataStore.data
        .map { preferences -> preferences[DANMAKU_SPEED_KEY] ?: 1.0f }
    
    // Account（登录态以 AccountSession 为唯一来源；这三个 Flow 与 login/logout
    // 都无消费方，已删——见审核352）
    
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

    /**
     * 审核291：持久化匿名风控指纹（buvid3/buvid4）。
     *
     * 由 AccountSession.ensureBuvid 拿到新值后回写，重启 App 时先读这里，
     * 避免每次冷启动都被 B 站当成新设备。
     */
    suspend fun saveBuvid(buvid3: String, buvid4: String) {
        if (buvid3.isBlank()) return
        context.dataStore.edit { preferences ->
            preferences[BUVID3_KEY] = buvid3
            if (buvid4.isNotBlank()) preferences[BUVID4_KEY] = buvid4
        }
    }

    suspend fun setCompletionAction(action: String) {
        context.dataStore.edit { preferences ->
            preferences[COMPLETION_ACTION_KEY] = action
        }
    }

    /** 审核279：设置首页数据源（auto/recommend/popular） */
    suspend fun setHomeFeedSource(source: String) {
        context.dataStore.edit { preferences ->
            preferences[HOME_FEED_SOURCE_KEY] = source
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

    /**
     * 硬件解码模式（bv P2）："auto"（默认，交给 Media3）/ "hardware"（强制优先硬解）/
     * "software"（强制软解）。作用于 ExoPlayer 的 MediaCodecSelector，需重建播放器生效。
     */
    val hardwareDecodeFlow: Flow<String> = context.dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { it[HW_DECODE_KEY] ?: "auto" }

    suspend fun setHardwareDecodeMode(mode: String) {
        context.dataStore.edit { it[HW_DECODE_KEY] = mode }
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

    // ===== 审核233：弹幕细节（PiliPlus 对齐）=====
    val dmShowAreaFlow: Flow<Float> = context.dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { it[DM_SHOW_AREA_KEY] ?: 0.5f }

    suspend fun setDmShowArea(v: Float) {
        context.dataStore.edit { it[DM_SHOW_AREA_KEY] = v.coerceIn(0.1f, 1f) }
    }

    val dmStrokeWidthFlow: Flow<Float> = context.dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { it[DM_STROKE_WIDTH_KEY] ?: 1.5f }

    suspend fun setDmStrokeWidth(v: Float) {
        context.dataStore.edit { it[DM_STROKE_WIDTH_KEY] = v.coerceIn(0f, 5f) }
    }

    val dmFontWeightFlow: Flow<Int> = context.dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { it[DM_FONT_WEIGHT_KEY] ?: 5 }

    suspend fun setDmFontWeight(v: Int) {
        context.dataStore.edit { it[DM_FONT_WEIGHT_KEY] = v.coerceIn(1, 9) }
    }

    val dmLineHeightFlow: Flow<Float> = context.dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { it[DM_LINE_HEIGHT_KEY] ?: 1.6f }

    suspend fun setDmLineHeight(v: Float) {
        context.dataStore.edit { it[DM_LINE_HEIGHT_KEY] = v.coerceIn(1f, 3f) }
    }

    val dmMassiveModeFlow: Flow<Boolean> = context.dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { it[DM_MASSIVE_MODE_KEY] ?: false }

    suspend fun setDmMassiveMode(enabled: Boolean) {
        context.dataStore.edit { it[DM_MASSIVE_MODE_KEY] = enabled }
    }

    val showVipDanmakuFlow: Flow<Boolean> = context.dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { it[DM_VIP_KEY] ?: true }

    suspend fun setShowVipDanmaku(enabled: Boolean) {
        context.dataStore.edit { it[DM_VIP_KEY] = enabled }
    }

    val mergeDanmakuFlow: Flow<Boolean> = context.dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { it[DM_MERGE_KEY] ?: false }

    suspend fun setMergeDanmaku(enabled: Boolean) {
        context.dataStore.edit { it[DM_MERGE_KEY] = enabled }
    }

    val enableTapDanmakuFlow: Flow<Boolean> = context.dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { it[DM_TAP_KEY] ?: true }

    suspend fun setEnableTapDanmaku(enabled: Boolean) {
        context.dataStore.edit { it[DM_TAP_KEY] = enabled }
    }

    // ===== 审核233：播放行为 =====
    val continuePlayingPartFlow: Flow<Boolean> = context.dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { it[CONTINUE_PART_KEY] ?: true }

    suspend fun setContinuePlayingPart(enabled: Boolean) {
        context.dataStore.edit { it[CONTINUE_PART_KEY] = enabled }
    }

    val continuePlayInBackgroundFlow: Flow<Boolean> = context.dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { it[BG_PLAY_KEY] ?: false }

    suspend fun setContinuePlayInBackground(enabled: Boolean) {
        context.dataStore.edit { it[BG_PLAY_KEY] = enabled }
    }

    val directExitOnBackFlow: Flow<Boolean> = context.dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { it[DIRECT_EXIT_KEY] ?: false }

    suspend fun setDirectExitOnBack(enabled: Boolean) {
        context.dataStore.edit { it[DIRECT_EXIT_KEY] = enabled }
    }

    val enableAutoLongPressSpeedFlow: Flow<Boolean> = context.dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { it[LONG_PRESS_SPEED_KEY] ?: false }

    suspend fun setEnableAutoLongPressSpeed(enabled: Boolean) {
        context.dataStore.edit { it[LONG_PRESS_SPEED_KEY] = enabled }
    }

    val longPressSpeedDefaultFlow: Flow<Float> = context.dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { it[LONG_PRESS_SPEED_VAL_KEY] ?: 3.0f }

    suspend fun setLongPressSpeedDefault(v: Float) {
        context.dataStore.edit { it[LONG_PRESS_SPEED_VAL_KEY] = v.coerceIn(1.5f, 5f) }
    }

    // ===== 审核233：推荐过滤（PiliPlus recommend_filter 全套）=====
    val minDurationForRcmdFlow: Flow<Int> = context.dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { it[MIN_DURATION_RCMD_KEY] ?: 0 }

    suspend fun setMinDurationForRcmd(sec: Int) {
        context.dataStore.edit { it[MIN_DURATION_RCMD_KEY] = sec.coerceIn(0, 3600) }
    }

    val minPlayForRcmdFlow: Flow<Int> = context.dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { it[MIN_PLAY_RCMD_KEY] ?: 0 }

    suspend fun setMinPlayForRcmd(n: Int) {
        context.dataStore.edit { it[MIN_PLAY_RCMD_KEY] = n.coerceIn(0, 100_000_000) }
    }

    val minLikeRatioForRecommendFlow: Flow<Int> = context.dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { it[MIN_LIKE_RATIO_RCMD_KEY] ?: 0 }

    suspend fun setMinLikeRatioForRecommend(pct: Int) {
        context.dataStore.edit { it[MIN_LIKE_RATIO_RCMD_KEY] = pct.coerceIn(0, 100) }
    }

    val exemptFilterForFollowedFlow: Flow<Boolean> = context.dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { it[EXEMPT_FOLLOWED_KEY] ?: true }

    suspend fun setExemptFilterForFollowed(enabled: Boolean) {
        context.dataStore.edit { it[EXEMPT_FOLLOWED_KEY] = enabled }
    }

    val applyFilterToRelatedVideosFlow: Flow<Boolean> = context.dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { it[FILTER_RELATED_KEY] ?: true }

    suspend fun setApplyFilterToRelatedVideos(enabled: Boolean) {
        context.dataStore.edit { it[FILTER_RELATED_KEY] = enabled }
    }

    val banWordForRecommendFlow: Flow<String> = context.dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { it[BAN_WORD_RCMD_KEY] ?: "" }

    /** 审核370：屏蔽 UP 主 mid 列表 */
    val blockedUpsFlow: Flow<String> = context.dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { it[BLOCKED_UPS_KEY] ?: "" }

    suspend fun setBlockedUps(v: String) {
        context.dataStore.edit { it[BLOCKED_UPS_KEY] = v }
    }

    val whitelistUpsFlow: Flow<String> = context.dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { it[WHITELIST_UPS_KEY] ?: "" }

    suspend fun setWhitelistUps(v: String) {
        context.dataStore.edit { it[WHITELIST_UPS_KEY] = v }
    }

    suspend fun setBanWordForRecommend(words: String) {
        context.dataStore.edit { it[BAN_WORD_RCMD_KEY] = words }
    }

    // ===== 审核235：图片质量 + 字幕细节 =====
    val picQualityFlow: Flow<Int> = context.dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { it[PIC_QUALITY_KEY] ?: 100 }

    suspend fun setPicQuality(q: Int) {
        context.dataStore.edit { it[PIC_QUALITY_KEY] = q.coerceIn(0, 100) }
    }

    val subtitleScaleFlow: Flow<Float> = context.dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { it[SUB_SCALE_KEY] ?: 1f }

    suspend fun setSubtitleScale(v: Float) {
        context.dataStore.edit { it[SUB_SCALE_KEY] = v.coerceIn(0.5f, 2f) }
    }

    val subtitlePaddingHFlow: Flow<Float> = context.dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { it[SUB_PAD_H_KEY] ?: 10f }

    suspend fun setSubtitlePaddingH(v: Float) {
        context.dataStore.edit { it[SUB_PAD_H_KEY] = v.coerceIn(0f, 40f) }
    }

    val subtitlePaddingBFlow: Flow<Float> = context.dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { it[SUB_PAD_B_KEY] ?: 64f }

    suspend fun setSubtitlePaddingB(v: Float) {
        context.dataStore.edit { it[SUB_PAD_B_KEY] = v.coerceIn(0f, 200f) }
    }

    val subtitleBgOpacityFlow: Flow<Float> = context.dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { it[SUB_BG_OPACITY_KEY] ?: 0.6f }

    suspend fun setSubtitleBgOpacity(v: Float) {
        context.dataStore.edit { it[SUB_BG_OPACITY_KEY] = v.coerceIn(0f, 1f) }
    }

    val subtitleStrokeFlow: Flow<Float> = context.dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { it[SUB_STROKE_KEY] ?: 0.5f }

    suspend fun setSubtitleStroke(v: Float) {
        context.dataStore.edit { it[SUB_STROKE_KEY] = v.coerceIn(0f, 3f) }
    }

    val subtitleBoldFlow: Flow<Boolean> = context.dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { it[SUB_BOLD_KEY] ?: false }

    suspend fun setSubtitleBold(v: Boolean) {
        context.dataStore.edit { it[SUB_BOLD_KEY] = v }
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

    suspend fun clearAll() {
        context.dataStore.edit { it.clear() }
    }
}
