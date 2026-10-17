package com.example.piliai.playback

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.piliai.utils.toHttpsUrl
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import com.example.piliai.data.model.formatCount
import com.example.piliai.data.model.toParsed
import com.example.piliai.danmaku.toParsedDanmaku
import com.example.piliai.data.remote.AccountSession
import com.example.piliai.data.remote.BiliApiClient
import com.example.piliai.data.repository.VideoRepository
import com.example.piliai.playback.backend.PlaybackBackend
import com.example.piliai.playback.backend.PlaybackEventListener
import com.example.piliai.playback.backend.PlaybackRequest

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class VideoPlayerViewModel(context: Context) : ViewModel(), PlaybackEventListener {
    companion object {
        // 审核77：Gson 线程安全，全 VM 复用单实例
        private val GSON = com.google.gson.Gson()
    }
    // 审核27：ViewModel 生命周期长于 Activity，持有 applicationContext 防内存泄漏
    private val appContext: Context = context.applicationContext
    
    private var backend: PlaybackBackend? = null
    private val sourceHttpClient = okhttp3.OkHttpClient.Builder()
        .connectTimeout(15L, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(20L, java.util.concurrent.TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(false)
        .build()
    private var loadJob: Job? = null
    private var completionJob: Job? = null
    private var playbackSpeedRestoreJob: Job? = null
    private var playbackSpeedGeneration = 0L
    private var loadGeneration = 0L
    private var clockJob: Job? = null
    private var introSkippedForGeneration = -1L
    private var outroSkippedForGeneration = -1L
    /** 审核242：解码器过载提示只报一次（换片/切画质后重置） */
    private var decoderOverloadReported = false
    private var pendingGpuSurface: android.view.Surface? = null
    
    /** 兼容层：UI 与 PlayerView 仍需要 ExoPlayer 实例，仅当后端为 Media3 时非空 */
    val player: androidx.media3.exoplayer.ExoPlayer?
        get() = (backend as? com.example.piliai.playback.backend.Media3Backend)?.nativePlayer()

    /** PlayerView 在 factory 时 player 还是 null；这个计数在后端建好后加一，逼界面重新绑定。 */
    private val _playerEpoch = MutableStateFlow(0)
    val playerEpoch: StateFlow<Int> = _playerEpoch.asStateFlow()

    /** 标题、简介、UP 主写在普通字段上，简介页读不到变化。这个计数在详情就绪后加一。 */
    private val _detailEpoch = MutableStateFlow(0)
    val detailEpoch: StateFlow<Int> = _detailEpoch.asStateFlow()
    
    val activeBackend: String
        get() = com.example.piliai.playback.backend.PlaybackBackends.resolve(
            com.example.piliai.utils.RendererPrefs.playbackBackend
        )
    
    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    /**
     * 审核311：弹幕控制（从本类提取）。队列维护、屏蔽过滤、热力曲线、
     * 时间窗查询都归它，本类只通过它读写弹幕。
     */
    private val danmakuCtrl = DanmakuController()

    /** 弹幕热力曲线（高能进度条），透传 DanmakuController */
    val heatCurve: StateFlow<List<Float>> = danmakuCtrl.heatCurve
    
    data class PlayerState(
        val isPlaying: Boolean = false,
        val currentTime: Long = 0L,
        val duration: Long = 0L,
        val volume: Float = 1.0f,
        val playbackSpeed: Float = 1.0f,
        val isMuted: Boolean = false,
        val isBuffering: Boolean = false,
        val error: String? = null,
        val position: Int = 0,
        // 审核312：isLiked/isFavorited/coinCount/likeCount/coinCountTotal/favCount
        // 已移到 EngagementController（本类顶部同名 StateFlow）。
        // 原来是死字段——互动逻辑搬走后没人给它们赋值，UI 读到的永远是
        // 初始值（点赞后计数不动）。删掉避免双状态源。
        // 批次A：播放器补全
        val currentPart: Int = 1,
        val currentPartTitle: String = "",
        val partCount: Int = 1,
        val danmakuOn: Boolean = true,
        val danmakuAlpha: Float = 1f,
        val danmakuScale: Float = 1f,
        // 审核244（P0-1/P0-2）：弹幕字号与行高。此前设置页这两个滑块
        // 只写 DataStore，播放器从未读，用户拖了没反应。
        val danmakuFontSize: Int = 25,
        val danmakuLineHeight: Float = 1.6f,
        // 审核249：弹幕字重（0=细 1=常规 2=中粗 3=粗）。
        // 此前 dmFontWeightFlow 只被 SettingsViewModel collect，播放层零消费者。
        val danmakuFontWeight: Int = 1,
        // 审核249：点击弹幕交互。此前 enableTapDanmakuFlow 同样零消费者。
        val danmakuTapInteract: Boolean = false,
        // 审核259：弹幕每秒上限（0=不限）
        val danmakuMaxPerSecond: Int = 0,
        val danmakuSpeed: Float = 1f,
        // 审核233：弹幕细节（PiliPlus 对齐）
        val danmakuShowArea: Float = 1f,
        val danmakuStrokeWidth: Float = 1.5f,
        val danmakuMerge: Boolean = false,
        val danmakuMassiveMode: Boolean = false,
        // 审核295：大会员弹幕过滤（mode=8）。关闭时不渲染大会员专属弹幕。
        val showVipDanmaku: Boolean = true,
        // 审核235：字幕细节
        val subtitleScale: Float = 1f,
        val subtitlePaddingH: Float = 10f,
        val subtitlePaddingB: Float = 64f,
        val subtitleBgOpacity: Float = 0.6f,
        val subtitleStroke: Float = 0.5f,
        val subtitleBold: Boolean = false,
        // 审核237：手势调节时间戳（递增 tick，UI 据此显示浮标 1s）
        val volumeTouchedTick: Long = 0L,
        val brightnessTouchedTick: Long = 0L,
        val brightness: Float = 0.5f,
        val gestureSeekDeltaMs: Long = 0L,
        val qualities: List<QualityOption> = emptyList(),
        val currentQn: Int = 80,
        val related: List<RelatedItem> = emptyList(),
        // 批次L40：实时在线人数
        val onlineCount: Long = 0L,
        val dmTotalCount: Int = 0,  // 审核222：弹幕总数（enableDmCount）
        // Kazumi 特性：低延迟音频 + 视频渲染器（0=SurfaceView 1=TextureView）
        val lowLatencyAudio: Boolean = false,
        val renderer: Int = 0,
        // 弹幕源：是否启用 DanDan（弹弹play）弹幕作为补充源
        val danmakuDandanEnabled: Boolean = false
    )
    
    /** 审核235：字幕偏好打包（combine 上限5个 Flow） */
    private data class SubtitlePrefs(
        val scale: Float, val padH: Float, val padB: Float, val bg: Float, val stroke: Float,
    )

    // 审核233：相关视频过滤配置（combine 收集，DataStore 持久化）
    private var relatedApplyFilter: Boolean = true
    // 审核244（P0-3）：「自动连播分P」开关的进程内缓存。
    // DataStore 的 continuePlayingPartFlow 此前 0 个消费者，是纯摆设。
    private var continuePlayingPart: Boolean = true
    private var relatedMinDuration: Int = 0
    private var relatedBanWords: String = ""

    init {
        // 审核301：播放内核切换热生效。ensureBackend 有
        // `if (backend != null) return`——用户先以 Media3 进播放器、再去设置切
        // GPU-next 时 backend 已存在，直接复用，切换**完全不生效**
        // （用户反馈「GPU-next 没做出来」的根因）。这里监听设置变化：
        // 内核确实换了就重建 backend 并用当前流重新起播。
        viewModelScope.launch {
            val storage = com.example.piliai.utils.StorageManager.getInstance(appContext)
            var lastBackend = com.example.piliai.utils.RendererPrefs.playbackBackend
            storage.playbackBackendFlow.collect { id ->
                if (id == lastBackend) return@collect
                lastBackend = id
                com.example.piliai.utils.RendererPrefs.updatePlaybackBackend(id)
                val current = backend ?: return@collect
                val pos = current.positionMs()
                val playing = _state.value.isPlaying
                val video = currentUri
                if (video.isBlank()) return@collect
                runCatching { current.release() }
                backend = null
                ensureBackend()
                startPlayback(video, cachedAudioUrl, resumePositionMs = pos, playWhenReady = playing)
            }
        }
        // 审核295：空降助手——注册 seek 通道，让评论页点时间戳能跳到这里
        com.example.piliai.playback.VideoSeekBus.register { ms -> seekTo(ms) }
        startPositionClock()
        // 读取 DataStore 持久化设置：默认清晰度 + 弹幕开关（真实作用于播放链路）
        val storage = com.example.piliai.utils.StorageManager.getInstance(appContext)
        viewModelScope.launch {
            storage.videoQualityFlow.collect { q ->
                currentQn = when (q) {
                    "1080p" -> 80; "720p" -> 64; "480p" -> 32; else -> currentQn
                }
            }
        }
        viewModelScope.launch {
            storage.danmakuEnabledFlow.collect { on ->
                _state.value = _state.value.copy(danmakuOn = on)
            }
        }
        viewModelScope.launch {
            storage.danmakuOpacityFlow.collect { a ->
                _state.value = _state.value.copy(danmakuAlpha = a)
            }
        }
        viewModelScope.launch {
            storage.danmakuScaleFlow.collect { s ->
                _state.value = _state.value.copy(danmakuScale = s)
            }
        }
        viewModelScope.launch {
            storage.danmakuSpeedFlow.collect { speed ->
                _state.value = _state.value.copy(danmakuSpeed = speed)
            }
        }
        // 审核233：弹幕显示区域 + 描边宽度
        viewModelScope.launch {
            storage.dmShowAreaFlow.collect { area ->
                _state.value = _state.value.copy(danmakuShowArea = area)
            }
        }
        // 审核244（P0-2）：弹幕行高此前只写进 SettingsViewModel state，
        // 播放器从未读 → 滑块完全无效。
        viewModelScope.launch {
            storage.dmLineHeightFlow.collect { h ->
                _state.value = _state.value.copy(danmakuLineHeight = h)
            }
        }
        // 审核249：弹幕字重。同行高，此前渲染层零消费者。
        viewModelScope.launch {
            storage.dmFontWeightFlow.collect { w ->
                _state.value = _state.value.copy(danmakuFontWeight = w)
            }
        }
        // 审核249：点击弹幕交互。
        viewModelScope.launch {
            storage.enableTapDanmakuFlow.collect { on ->
                _state.value = _state.value.copy(danmakuTapInteract = on)
            }
        }
        // 审核259：弹幕每秒上限。
        viewModelScope.launch {
            storage.danmakuMaxPerSecondFlow.collect { n ->
                _state.value = _state.value.copy(danmakuMaxPerSecond = n)
            }
        }
        // 审核244（P0-1）：弹幕字号。设置页写 danmakuFontSize（12~40），
        // 播放器读 danmakuScale（0.5~2.0）——两个键各写各的，滑块无效。
        // 把字号映射到 scale 一并下发，保证老键也有消费者。
        viewModelScope.launch {
            storage.danmakuFontSizeFlow.collect { size ->
                val scale = (size / 25f).coerceIn(0.5f, 2.5f)
                _state.value = _state.value.copy(
                    danmakuFontSize = size,
                    danmakuScale = scale
                )
            }
        }
        viewModelScope.launch {
            storage.dmStrokeWidthFlow.collect { sw ->
                _state.value = _state.value.copy(danmakuStrokeWidth = sw)
            }
        }
        viewModelScope.launch {
            storage.mergeDanmakuFlow.collect { on ->
                _state.value = _state.value.copy(danmakuMerge = on)
            }
        }
        viewModelScope.launch {
            storage.dmMassiveModeFlow.collect { on ->
                _state.value = _state.value.copy(danmakuMassiveMode = on)
            }
        }
        // 审核298：监听超分设置变化，播放中改动立即生效（原来只有
        // onVideoSizeChanged 一条路径，改设置后不重播就不生效）。
        viewModelScope.launch {
            storage.superResolutionFlow.collect { mode ->
                com.example.piliai.utils.RendererPrefs.updateSuperResolution(mode)
                applySuperResolutionEffect()
            }
        }
        // 审核235：字幕细节
        viewModelScope.launch {
            kotlinx.coroutines.flow.combine(
                storage.subtitleScaleFlow, storage.subtitlePaddingHFlow,
                storage.subtitlePaddingBFlow, storage.subtitleBgOpacityFlow,
                storage.subtitleStrokeFlow,
            ) { a, b, c, d, e -> SubtitlePrefs(a, b, c, d, e) }
                .collect { sp ->
                    _state.value = _state.value.copy(
                        subtitleScale = sp.scale, subtitlePaddingH = sp.padH,
                        subtitlePaddingB = sp.padB, subtitleBgOpacity = sp.bg,
                        subtitleStroke = sp.stroke,
                    )
                }
        }
        // 审核313：倍速档位列表（设置页可编辑，播放器倍速菜单用它）
        viewModelScope.launch {
            storage.playbackSpeedListFlow.collect { list ->
                _speedList.value = list
            }
        }
        // 审核295：大会员弹幕过滤。showVipDanmakuFlow 此前有完整数据链路
        // （state/collect/setter/键）但既无 UI 开关也无消费方——纯半成品。
        // 接入：关闭时过滤掉 mode=8 的大会员弹幕。
        viewModelScope.launch {
            storage.showVipDanmakuFlow.collect { on ->
                _state.value = _state.value.copy(showVipDanmaku = on)
            }
        }
        viewModelScope.launch {
            storage.subtitleBoldFlow.collect { _state.value = _state.value.copy(subtitleBold = it) }
        }
        // 审核233：相关视频过滤配置收集
        viewModelScope.launch {
            kotlinx.coroutines.flow.combine(
                storage.applyFilterToRelatedVideosFlow,
                storage.minDurationForRcmdFlow,
                storage.banWordForRecommendFlow,
            ) { apply, minDur, ban -> Triple(apply, minDur, ban) }
                .collect { (apply, minDur, ban) ->
                    relatedApplyFilter = apply
                    relatedMinDuration = minDur
                    relatedBanWords = ban
                }
        }
        // 审核244（P0-3）：自动连播分P。此前 continuePlayingPartFlow 全库
        // 0 个消费者，设置页那个开关是纯摆设。
        viewModelScope.launch {
            storage.continuePlayingPartFlow.collect { on ->
                continuePlayingPart = on
            }
        }
        // 审核247：顶部/底部弹幕开关此前只写不回读（重启丢失）。
        // 同时喂 cached*（弹幕分发热路径直接读，避免每次 collect Flow）与
        // _dmShow*（UI 状态源）。
        viewModelScope.launch {
            storage.danmakuShowTopFlow.collect { on ->
                cachedDmShowTop = on
                _dmShowTop.value = on
            }
        }
        viewModelScope.launch {
            storage.danmakuShowBottomFlow.collect { on ->
                cachedDmShowBottom = on
                _dmShowBottom.value = on
            }
        }
        // Kazumi：低延迟音频 + 渲染器（持久化设置，实时作用于播放链路）
        viewModelScope.launch {
            storage.lowLatencyAudioFlow.collect { on ->
                _state.value = _state.value.copy(lowLatencyAudio = on)
            }
        }
        viewModelScope.launch {
            storage.rendererFlow.collect { r ->
                _state.value = _state.value.copy(renderer = r)
            }
        }
    }

    /**
     * Kazumi 特性：低延迟音频开关。
     *
     * 实现说明（诚实标注）：Media3 1.3.1 中 AudioAttributes 只能在
     * ExoPlayer.Builder 上设置（javap 已核实，ExoPlayer 本身无 setAudioAttributes），
     * 因此**无法对已创建的播放器热切换**。
     *
     * 本实现：写 DataStore + 更新进程内缓存 RendererPrefs，
     * 下一次创建播放器（进入播放页）时由 Builder 应用该设置生效。
     * 低延迟模式关闭音频焦点让位（handleAudioFocus=false），
     * 避免焦点切换导致的输出重开与缓冲重置，从而降低音画延迟。
     */
    fun setLowLatencyAudio(enabled: Boolean) {
        com.example.piliai.utils.RendererPrefs.updateLowLatency(enabled)
        viewModelScope.launch {
            com.example.piliai.utils.StorageManager.getInstance(appContext).setLowLatencyAudio(enabled)
        }
    }

    /** 设置视频渲染器（0=SurfaceView 1=TextureView） */
    fun setRenderer(v: Int) {
        // 同步更新进程内缓存，使下次创建 PlayerView 立即生效
        com.example.piliai.utils.RendererPrefs.update(v)
        viewModelScope.launch {
            com.example.piliai.utils.StorageManager.getInstance(appContext).setRenderer(v)
        }
    }
    
    fun loadVideo(
        uri: String,
        bvid: String = "",
        cid: Long = 0L,
        epId: Long = 0L,
        local: Boolean = false,
        sourceRequest: SourcePlaybackRequest? = null
    ) {
        completionJob?.cancel()
        loadJob?.cancel()
        resetSubtitleState()
        val generation = ++loadGeneration
        loadJob = viewModelScope.launch {
            try {
                // 离线播放（批次I）：本地 video.m4s + audio.m4s 合流播放
                if (local && bvid.isNotEmpty()) {
                    val playback = com.example.piliai.data.repository.DownloadManager
                        .getLocalPlayback(appContext, bvid)
                    if (playback != null) {
                        _state.value = _state.value.copy(isBuffering = true, error = null)
                        // 离线弹幕（批次L11）：有本地缓存则用缓存，否则尝试在线拉取
                        val dm = com.example.piliai.data.repository.DownloadManager
                            .localDanmaku(playback.videoPath)
                        if (dm.isNotEmpty()) {
                            addDanmakuEvents(dm.map { m ->
                                DanmakuEvent(
                                    id = "local", timestamp = (m["t"] as? Number)?.toLong() ?: 0L,
                                    content = m["c"]?.toString() ?: "",
                                    color = (m["col"] as? Number)?.toInt() ?: 0xFF000000.toInt(),
                                    fontSize = (m["fs"] as? Number)?.toInt() ?: 25
                                )
                            })
                        } else {
                            // 审核244（P1-7）：优先用路由传入的 cid（离线缓存页
                            // 现在会带 cid 跳转），查不到再回退查数据库。
                            // 此前只查 DB 且路由不传 cid → 离线视频大概率无弹幕。
                            val effectiveCid = cid.takeIf { it > 0L }
                                ?: runCatching {
                                    com.example.piliai.database.PiliNaraDatabase
                                        .getDatabase(appContext).downloadItemDao()
                                        .getByBvid(bvid)?.cid
                                }.getOrNull() ?: 0L
                            if (effectiveCid > 0L) loadDanmakuFor(effectiveCid)
                        }
                        startPlayback(playback.videoPath, playback.audioPath.takeIf { it.isNotEmpty() })
                        // 审核310：离线播放补齐能力加载。原来只有弹幕——
                        // 离线看时没 CC 字幕、进度条没有缩略图预览、点赞收藏
                        // 状态与计数不同步（与在线路径不对称）。
                        val offlineCid = cid.takeIf { it > 0L }
                            ?: runCatching {
                                com.example.piliai.database.PiliNaraDatabase
                                    .getDatabase(appContext).downloadItemDao()
                                    .getByBvid(bvid)?.cid
                            }.getOrNull() ?: 0L
                        if (offlineCid > 0L) {
                            loadSubtitles(bvid, offlineCid)
                            loadVideoShot(bvid, offlineCid)
                        }
                        runCatching {
                            VideoRepository(BiliApiClient()).getVideoDetail(bvid).getOrNull()?.data
                        }.getOrNull()?.let { d ->
                            aid = d.aid
                            if (aid > 0L) loadEngagement(aid, bvid)
                        }
                    } else {
                        setError("离线缓存不存在或未完成")
                    }
                    return@launch
                }
                // 番剧模式：pgc playurl（Rust 已兼容 result.dash）
                if (epId > 0L) {
                    loadPgcEpisode(epId, generation)
                    return@launch
                }
                // 无 URI：走真实解析链路 —— 详情拿 cid → playurl → Rust 选流 → DASH
                if (uri.isEmpty() && bvid.isNotEmpty()) {
                    _state.value = _state.value.copy(isBuffering = true, error = null)
                    resolveAndPlay(bvid, cid, generation)
                } else {
                    require(uri.isNotBlank()) { "Playback URL is empty" }
                    if (sourceRequest != null) {
                        require(sourceRequest.videoUrl == uri && bvid.isEmpty() &&
                            epId == 0L && !local) {
                            "Source playback request does not match the selected media"
                        }
                        val source = withContext(Dispatchers.IO) {
                            com.example.piliai.database.PiliNaraDatabase
                                .getDatabase(appContext).subscribeSourceDao()
                                .getById(sourceRequest.sourceId)
                        }
                        ensureCurrent(generation)
                        require(source?.enabled == true) {
                            appContext.getString(
                                com.example.piliai.R.string.subscribe_source_unavailable
                            )
                        }
                    }
                    val autoPlay = com.example.piliai.utils.StorageManager
                        .getInstance(appContext).autoPlayFlow.first()
                    ensureCurrent(generation)
                    val mediaItem = MediaItem.Builder()
                        .setUri(uri.toUri())
                        .setMediaId("$bvid:$cid")
                        .build()
                    // Inspect each manifest, segment and redirect destination separately.
                    val httpClient = sourceHttpClient.newBuilder()
                        .addNetworkInterceptor { chain ->
                            val outgoing = chain.request()
                            val headers = sourceRequest?.headersFor(outgoing.url.toString())
                                ?: emptyMap()
                            val builder = outgoing.newBuilder()
                                .removeHeader("Cookie")
                                .removeHeader("Authorization")
                                .removeHeader("Proxy-Authorization")
                            for ((name, value) in headers) builder.header(name, value)
                            chain.proceed(builder.build())
                        }
                        .build()
                    val httpFactory = androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(
                        httpClient
                    )
                    val dataSourceFactory = androidx.media3.datasource.DefaultDataSource.Factory(
                        appContext, httpFactory
                    )
                    // 审核263：订阅源直链也走 URL 形态分派。
                    // 原来用 DefaultMediaSourceFactory 靠 Content-Type sniffing 推断类型，
                    // 而 Media3Backend.prepare 已有按 URL 后缀分派 HLS/DASH 的
                    // buildMediaSource（审核237 加的）—— 两条路径不一致，
                    // 无后缀的 m3u8 / mpd 在订阅源这条路上会解析失败。
                    // Media3Backend.buildMediaSource 是 private，这里用同规则的内联实现。
                    val mediaSource = buildSourceMediaSource(dataSourceFactory, mediaItem)
                    if (bvid.isEmpty()) {
                        currentBvid = ""
                        this@VideoPlayerViewModel.epId = 0L
                        pages = emptyList()
                        currentPartIndex = 0
                        cachedAudioUrl = null
                        dashVideos = emptyList()
                        availableQualities = emptyList()
                        danmakuCtrl.clear()
                        _state.value = _state.value.copy(dmTotalCount = 0)
                    }
                    _state.value = _state.value.copy(
                        isPlaying = false, isBuffering = true,
                        error = null, currentTime = 0L, duration = 0L,
                        related = if (bvid.isEmpty()) emptyList() else _state.value.related,
                        qualities = availableQualities,
                        partCount = if (bvid.isEmpty()) 1 else _state.value.partCount,
                        currentPart = if (bvid.isEmpty()) 1 else _state.value.currentPart,
                        currentPartTitle = if (bvid.isEmpty()) "" else _state.value.currentPartTitle
                    )
                    // 订阅源播放通过 Media3Backend
                    ensureBackend()
                    val media3 = backend as? com.example.piliai.playback.backend.Media3Backend
                    media3?.prepareMediaSource(mediaSource, autoPlay)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 审核302：中文提示（原来是 "Failed to load video: ..."，
                // 用户在中文 App 里看到英文报错）
                setError("视频加载失败：${e.message ?: "未知错误"}")
            }
        }
    }

    /**
     * 审核263：按 URL 形态选 MediaSource（与 Media3Backend.buildMediaSource 同规则）。
     *
     * 订阅源直链的 URL 常不带正确的 Content-Type，纯靠 sniffing 会把
     * m3u8/mpd 判成 progressive 而解析失败。这里显式分派。
     */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun buildSourceMediaSource(
        factory: androidx.media3.datasource.DataSource.Factory,
        item: MediaItem
    ): androidx.media3.exoplayer.source.MediaSource {
        val path = item.localConfiguration?.uri?.path?.lowercase().orEmpty()
        val query = item.localConfiguration?.uri?.query?.lowercase().orEmpty()
        val hint = if (path.isNotEmpty()) path else query
        return when {
            hint.endsWith(".m3u8") || hint.endsWith(".m3u") ||
                hint.contains("/m3u8") || hint.contains("m3u8?") ||
                hint.contains("type=m3u8") || hint.contains("type=hls") ->
                androidx.media3.exoplayer.hls.HlsMediaSource.Factory(factory).createMediaSource(item)
            hint.endsWith(".mpd") || hint.contains("type=mpd") ||
                hint.contains("type=dash") ->
                androidx.media3.exoplayer.dash.DashMediaSource.Factory(factory).createMediaSource(item)
            else -> androidx.media3.exoplayer.source.ProgressiveMediaSource.Factory(factory)
                .createMediaSource(item)
        }
    }

    private suspend fun ensureCurrent(generation: Long) {
        currentCoroutineContext().ensureActive()
        if (generation != loadGeneration) throw CancellationException("stale playback request")
    }

    /** 番剧剧集播放链路（批次D 补全）：pgc season 详情取 cid + 标题/集数 → pgc playurl → Rust 选流 */
    private suspend fun loadPgcEpisode(epId: Long, generation: Long) {
        val repo = VideoRepository(BiliApiClient())
        ensureCurrent(generation)
        _state.value = _state.value.copy(isBuffering = true, error = null)

        val api = BiliApiClient()
        val season = api.getPgcSeason(epId = epId).getOrNull()?.result
        ensureCurrent(generation)
        val episode = season?.episodes?.firstOrNull { it.id == epId }
            ?: season?.episodes?.firstOrNull()
        val cid = episode?.cid ?: 0L
        if (cid <= 0L) {
            setError("无法获取剧集 cid（pgc 详情失败）")
            return
        }
        val episodeAid = episode?.bvid?.let { repo.getVideoDetail(it).getOrNull()?.data?.aid } ?: 0L
        ensureCurrent(generation)
        aid = episodeAid

        // 同番剧全部分集作为“分P”面板
        pages = season?.episodes.orEmpty().mapIndexed { idx, ep ->
        PartInfo(
            cid = ep.cid,
            epId = ep.id,
            page = idx + 1,
            part = listOfNotNull(ep.title.ifBlank { null }, ep.longTitle.ifBlank { null })
                .joinToString(" ").ifBlank { "第${idx + 1}集" },
            durationSec = ep.duration
        )
        }
        currentPartIndex = season?.episodes?.indexOfFirst { it.id == epId }?.coerceAtLeast(0) ?: 0
        _state.value = _state.value.copy(
            partCount = pages.size,
            currentPart = currentPartIndex + 1,
            currentPartTitle = pages.getOrNull(currentPartIndex)?.part ?: ""
        )

        this.epId = epId
        this.currentBvid = "ep$epId"
        // 审核309：互动状态（点赞/投币/收藏 + 真实计数 + 默认收藏夹）。
        // 番剧链路原来漏了 loadEngagement——播放页的点赞收藏状态与计数
        // 全是初始值，用户点了也不更新。episode.bvid 是真实 bvid。
        if (aid > 0L) loadEngagement(aid, episode?.bvid.orEmpty())
        // 番剧弹幕需要 cid
        loadDanmakuFor(cid)
        // 弹弹play 弹幕补充源（启用时按番剧标题拉取，静默降级）
        val danmakuTitle = pages.getOrNull(currentPartIndex)?.part
            ?: episode?.title ?: episode?.longTitle ?: ""
        val dandanToggle = com.example.piliai.utils.StorageManager.getInstance(appContext)
        viewModelScope.launch {
            val enabled = dandanToggle.dandanDanmakuFlow.first()
            if (enabled) loadDandanDanmaku(danmakuTitle)
        }

        val playResult = repo.getPgcPlayUrlWithCandidates(epId, cid, qn = currentQn)
            .getOrElse { setError("番剧 playurl 失败: ${it.message}"); return }
        val resp = playResult.first
        val videoCand = playResult.second.first
        val audioCand = playResult.second.second
        ensureCurrent(generation)
        if (videoCand?.primary.isNullOrEmpty()) {
            setError("未解析到番剧流地址（可能为大会员专享）")
            return
        }
        val primaryVideo = videoCand!!.primary
        cachedAudioUrl = audioCand?.primary
        // 审核308：番剧也要带 CDN 候选列表（普通播放已有，番剧漏了）
        videoUrlCandidates = videoCand.all
        audioUrlCandidates = audioCand?.all.orEmpty()
        dashVideos = resp.result?.dash?.video.orEmpty()
        // 审核308（真 bug）：原来这里没有填 availableQualities——只有普通视频
        // 路径填了。结果番剧播放时清晰度菜单是空的，用户根本切不了画质。
        val accepted = resp.result?.acceptDescription.orEmpty()
        val acceptQn = resp.result?.acceptQuality.orEmpty()
        availableQualities = accepted.zip(acceptQn).map { (desc, q) -> QualityOption(q, desc) }
        currentQn = qnOf(resp)
        startPlayback(primaryVideo, cachedAudioUrl)
        // 审核309：补齐番剧链路缺失的能力加载。原来这里只有
        // loadDanmakuFor，普通视频路径有的字幕/缩略图/在线人数/互动状态
        // 全都没有——番剧播放时没字幕、进度条没预览图、点赞收藏状态不同步。
        loadSubtitles(currentBvid, cid)
        loadVideoShot(currentBvid, cid)
    }

    private fun qnOf(resp: com.example.piliai.data.model.PgcPlayUrlResponse): Int =
        resp.result?.quality ?: currentQn

    private suspend fun loadDanmakuFor(cid: Long) {
        // 审核（卡顿根因）：GSON 序列化几千条弹幕 + JNI 调 Rust merge + JSONObject
        // 解析都是重 CPU，原在主线程（viewModelScope 默认 Main）执行，进播放器卡。
        // 挪到 Default；只有最终 addDanmakuEvents（写 UI 状态）需要回主线程。
        val withUid = withContext(Dispatchers.Default) {
            val api = BiliApiClient()
            // 审核293：getDanmaku 现在直接返回弹幕列表（内部已解压 XML）
            val raw = api.getDanmaku(cid).getOrNull() ?: return@withContext null
            // 审核295：大会员弹幕过滤（mode=8）。设置关闭时直接不进入渲染管线。
            val filtered = if (_state.value.showVipDanmaku) raw
            else raw.filter { it.toParsed().mode != 8 }
            val mergedEvents = runCatching {
                val lib = com.example.piliai.danmaku.DanmakuNativeLib()
                val ptr = lib.create(com.example.piliai.danmaku.DanmakuNativeLib.DanmakuConfig())
                try {
                    val sources = listOf(filtered.map { d ->
                        val p = d.toParsed()
                        mapOf(
                            "id" to 0L, "mode" to p.mode, "fontsize" to p.fontSize,
                            "color" to (p.color and 0xFFFFFF), "timestamp" to p.timestamp.toDouble(),
                            "pool" to p.pool, "content" to p.content, "uid" to p.uid
                        )
                    })
                    val srcJson = GSON.toJson(sources)
                    val out = lib.merge(ptr, srcJson) ?: return@runCatching null
                    val obj = org.json.JSONObject(out)
                    val arr = obj.getJSONArray("entries")
                    (0 until arr.length()).map { i ->
                        val e = arr.getJSONObject(i)
                        Triple((e.optDouble("timestamp") * 1000).toLong(),
                            e.optString("content"),
                            (e.optInt("color") and 0xFFFFFF) or 0xFF000000.toInt())
                    }
                } finally {
                    lib.destroy(ptr)
                }
            }.getOrNull()
            val list = mergedEvents?.map { (ts, content, color) ->
                DanmakuEvent(id = "", timestamp = ts, content = content,
                    color = color, fontSize = 25)
            } ?: filtered.map { d ->
                val p = d.toParsed()
                DanmakuEvent(
                    id = "",
                    timestamp = (p.timestamp * 1000).toLong(),
                    content = p.content,
                    color = p.color or 0xFF000000.toInt(),
                    fontSize = p.fontSize,
                    uid = p.uid,
                    mode = p.mode
                )
            }
            val uidByTs = filtered.map { d -> d.toParsed() }.associate { (it.timestamp * 1000).toLong() to it.uid }
            if (mergedEvents != null) list.map { e -> e.copy(uid = uidByTs[e.timestamp] ?: 0L) } else list
        }
        withUid?.let { addDanmakuEvents(it) }
    }

    /**
     * 弹弹play (DanDan) 弹幕补充源（移植自 piliplus）。
     * 按视频标题搜索 DanDan 番剧，取第一集弹幕作为 B 站弹幕的补充。
     * 仅在启用 + 配置了 API 凭据时生效；未命中静默降级（不影响 B 站弹幕）。
     */
    private suspend fun loadDandanDanmaku(title: String) {
        if (title.isBlank()) return
        if (!com.example.piliai.danmaku.DandanApi.isEnabled) return
        val events = withContext(Dispatchers.Default) {
            runCatching {
                val animes = com.example.piliai.danmaku.DandanApi.searchAnime(title)
                val target = animes.firstOrNull() ?: return@runCatching emptyList<com.example.piliai.data.model.ParsedDanmaku>()
                // 取第一集（episode 1）弹幕；DanDan episodeId = animeId + 4 位零填充集数
                val episodeId = target.animeId * 10000 + 1
                val comments = com.example.piliai.danmaku.DandanApi.getComments(episodeId)
                comments.map { it.toParsedDanmaku() }
            }.getOrDefault(emptyList())
        }
        if (events.isNotEmpty()) {
            val danmakuEvents = events.map { p ->
                DanmakuEvent(
                    id = "dandan",
                    timestamp = (p.timestamp * 1000).toLong(),
                    content = p.content,
                    color = p.color or 0xFF000000.toInt(),
                    fontSize = p.fontSize,
                    mode = p.mode,
                )
            }
            addDanmakuEvents(danmakuEvents, replace = false)
        }
    }

    // ==================== 字幕（批次K） ====================


    /**
     * 审核311：字幕控制（从本类提取）。CC 字幕轨道/正文/看点/当前文本
     * 都归它，本类只透出 Flow 与操作入口。
     */
    private val subtitle = SubtitleController(scope = viewModelScope)

    /** 当前加载代次，供字幕控制器丢弃过期响应 */
    val subtitleGeneration: Long get() = loadGeneration

    val subtitleTracks: StateFlow<List<Triple<Long, String, String>>> = subtitle.tracks
    val viewPoints: StateFlow<List<com.example.piliai.data.model.ViewPoint>> = subtitle.viewPoints
    val currentSubtitle: StateFlow<String> = subtitle.current
    val selectedSubtitleId: StateFlow<Long> = subtitle.selectedId

    private fun resetSubtitleState() {
        subtitle.generation = loadGeneration
        subtitle.reset()
        // 审核311：热力曲线归 DanmakuController 清空
        danmakuCtrl.clear()
    }

    private fun loadSubtitles(bvid: String, cid: Long) {
        subtitle.generation = loadGeneration
        subtitle.load(bvid, cid)
    }

    fun selectSubtitle(id: Long) = subtitle.select(id)

    /** 播放器每帧/每秒调：更新当前字幕文本 */
    fun updateSubtitleAt(currentTimeMs: Long) = subtitle.updateAt(currentTimeMs)

    /**
     * 真实播放链路：
     * 1) cid 缺失时拉 /x/web-interface/view 取 cid
     * 2) /x/player/wbi/playurl 拿 DASH（Rust 侧 selectStreams 选最优流）
     * 3) video/audio 双流喂给 ExoPlayer（MediaItem sideloaded manifests 不适合分开的 DASH，
     *    这里用 DataSource 级拼接不可行，因此取 ExoPlayer 原生 DASH 需 manifest；
     *    折中方案：优先用 Rust 选出的 video baseUrl 直接播（无声），并单独 track 音频 ——
     *    Media3 支持多 MediaItem 拼接播放（ConcatenatingMediaSource2）
     */
    private suspend fun resolveAndPlay(
        bvid: String,
        cidIn: Long,
        generation: Long
    ) {
        val repo = VideoRepository(BiliApiClient())
        ensureCurrent(generation)
        // 审核215：DASH CDN 强依赖 buvid3 cookie，播放前确保已获取
        runCatching { com.example.piliai.data.remote.AccountSession.ensureBuvid() }
        // 1) 补 cid + 分P列表 + aid
        val detail = repo.getVideoDetail(bvid).getOrNull()?.data
        val effectiveCid = cidIn.takeIf { it > 0L }
            ?: detail?.cid
            ?: run {
                setError("无法获取视频 cid（详情接口失败）")
                return
            }
        ensureCurrent(generation)
        aid = detail?.aid ?: 0L
        videoTitle = detail?.title.orEmpty()
        videoCover = detail?.pic.orEmpty()
        videoOwner = detail?.owner?.name.orEmpty()
        videoOwnerMid = detail?.owner?.mid ?: 0L
        videoOwnerFace = detail?.owner?.face.orEmpty()
        loadOwnerFollowState(bvid)
        // 批次L18：简介/标签/日期（简介面板数据源）
        videoDesc = detail?.desc.orEmpty()
        videoTags = detail?.tags?.takeIf { it.isNotEmpty() }
            ?.mapNotNull { it.tagName } ?: emptyList()
        videoPubdate = detail?.pubdate ?: 0L
        videoView = detail?.stat?.play ?: 0L
        videoDanmaku = detail?.stat?.danmaku ?: 0L
        videoReply = detail?.stat?.reply ?: 0L
        _detailEpoch.value = _detailEpoch.value + 1
        // 分P 列表（单P视频 pages 只有 1 项）
        pages = (detail?.pages.orEmpty()).map {
            PartInfo(cid = it.cid, page = it.page, part = it.part, durationSec = it.duration)
        }.ifEmpty { listOf(PartInfo(effectiveCid, 1, "", 0L)) }
        currentPartIndex = pages.indexOfFirst { it.cid == effectiveCid }.coerceAtLeast(0)
        _state.value = _state.value.copy(
            partCount = pages.size,
            currentPart = pages[currentPartIndex].page,
            currentPartTitle = pages[currentPartIndex].part
        )
        loadEngagement(aid, bvid)
        loadRelated(bvid)

        // 2) playurl + Rust 流选择
        val playResult = repo.getPlayUrlWithCandidates(bvid, effectiveCid, qn = currentQn)
            .getOrElse {
                setError("playurl 获取失败: ${it.message}")
                return
            }
        val resp = playResult.first
        val videoCand = playResult.second.first
        val audioCand = playResult.second.second
        ensureCurrent(generation)
        this.effectiveCid = effectiveCid
        this.currentBvid = bvid
        val videoUrl2 = videoCand?.primary
        val audioUrl2 = audioCand?.primary
        cachedAudioUrl = audioUrl2
        // 审核290：保存 CDN 候选列表（baseUrl + backupUrl）。B 站分配的首个节点
        // 可能 TLS 握手超时（实测遇到过），有备用节点才能切换重试。
        videoUrlCandidates = videoCand?.all.orEmpty()
        audioUrlCandidates = audioCand?.all.orEmpty()
        loadOnlineCount(bvid, effectiveCid)   // 批次L40：实时在线人数
        if (videoUrl2.isNullOrEmpty()) {
            setError("未解析到视频流地址（可能需要登录后观看）")
            return
        }

        // 3) 缓存清晰度列表（accept_quality + 当前 dash 可选流）
        dashVideos = resp.data?.dash?.video.orEmpty()
        val accepted = resp.data?.acceptDescription.orEmpty()
        val acceptQn = resp.data?.acceptQuality?.map { it.quality } ?: emptyList()
        availableQualities = accepted.zip(acceptQn).map { (desc, q) -> QualityOption(q, desc) }
        currentQn = resp.data?.quality ?: currentQn

        startPlayback(videoUrl2, audioUrl2)
        loadSubtitles(bvid, effectiveCid)
        loadVideoShot(bvid, effectiveCid)
    }

    /** 批次L3：加载进度条缩略图雪碧图（非阻断，失败静默） */
    private val _videoShot = kotlinx.coroutines.flow.MutableStateFlow<com.example.piliai.data.model.VideoShotData?>(null)
    val videoShot: kotlinx.coroutines.flow.StateFlow<com.example.piliai.data.model.VideoShotData?> = _videoShot

    private fun loadVideoShot(bvid: String, cid: Long) {
        _videoShot.value = null
        if (bvid.isEmpty() || bvid.startsWith("ep")) return
        viewModelScope.launch {
            BiliApiClient().getVideoShot(bvid, cid).onSuccess { resp ->
                if (resp.code == 0) _videoShot.value = resp.data
            }
        }
    }

    /** 按当前秒数取缩略图帧 (url, col, row) */
    fun shotFrameAt(second: Long): Triple<String, Int, Int>? = _videoShot.value?.frameAt(second)

    /** 播放进度自己走，片头片尾只在每集各跳一次 */
    private fun startPositionClock() {
        clockJob?.cancel()
        clockJob = viewModelScope.launch {
            while (true) {
                delay(400L)
                val pos = backend?.positionMs() ?: continue
                val dur = backend?.durationMs()?.takeIf { it > 0L } ?: _state.value.duration
                if (pos != _state.value.currentTime || (dur > 0L && dur != _state.value.duration)) {
                    _state.value = _state.value.copy(currentTime = pos, duration = dur)
                }
                maybeSkipIntroOutro(pos, dur)
            }
        }
    }

    private fun maybeSkipIntroOutro(pos: Long, dur: Long) {
        val introMs = com.example.piliai.utils.RendererPrefs.skipIntroSec * 1000L
        val outroMs = com.example.piliai.utils.RendererPrefs.skipOutroSec * 1000L
        if (introMs > 0L && pos in 0L until introMs && introSkippedForGeneration != loadGeneration) {
            introSkippedForGeneration = loadGeneration
            seekTo(introMs)
            return
        }
        if (outroMs > 0L && dur > introMs + outroMs + 5_000L &&
            pos >= dur - outroMs && outroSkippedForGeneration != loadGeneration
        ) {
            outroSkippedForGeneration = loadGeneration
            seekTo(dur)
        }
    }

    /** GPU-next 的 Surface 由播放器页持有，Media3 仍走 PlayerView */
    fun attachVideoSurface(surface: android.view.Surface) {
        pendingGpuSurface = surface
        backend?.attachSurface(surface)
    }

    fun detachVideoSurface() {
        pendingGpuSurface = null
        backend?.detachSurface()
    }

    /** 组装 MergingMediaSource 并启动播放（可带恢复进度） */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    /**
     * 创建播放后端（根据设置选择 Media3 或 GPU-next）。
     * 每次起播前调用，确保使用最新配置。
     */
    private fun ensureBackend() {
        if (backend != null) return  // 已创建，复用
        
        val backendId = com.example.piliai.playback.backend.PlaybackBackends.resolve(
            com.example.piliai.utils.RendererPrefs.playbackBackend
        )
        
        backend = when (backendId) {
            "gpu-next" -> com.example.piliai.playback.backend.GpuNextBackend(appContext, this)
            else -> com.example.piliai.playback.backend.Media3Backend(appContext, this)
        }
        // 审核318：向 PlaybackRegistry 注册，让 PlaybackService 的 MediaSession
        // 能拿到本 player，系统媒体控制（锁屏/蓝牙/车载）才能控制播放。
        // 只对 Media3 后端注册——GpuNextBackend 不实现 Player 接口。
        (backend as? com.example.piliai.playback.backend.Media3Backend)?.nativePlayer()
            ?.let { com.example.piliai.playback.PlaybackRegistry.register(appContext, it, videoTitle) }
        _playerEpoch.value = _playerEpoch.value + 1
    }
    
    /** 审核267：当前播放地址的 Uri（无内容时返回 null） */
    fun currentPlaybackUri(): android.net.Uri? =
        currentUri.takeIf { it.isNotBlank() }?.toUri()

    private fun startPlayback(
        videoUrl: String,
        audioUrl: String?,
        resumePositionMs: Long = 0L,
        playWhenReady: Boolean = true
    ) {
        // 审核267：记录当前播放地址，供取帧使用
        currentUri = videoUrl
        // 审核215：DASH CDN 风控升级——实测无 buvid3 cookie 一律 403（连标准 UA 都拒）。
        // 必须：桌面 Chrome UA + Referer + AccountSession 的 buvid3/SESSDATA cookie。
        val cookie = runCatching {
            com.example.piliai.data.remote.AccountSession.cookieHeader()
        }.getOrNull().orEmpty()
        val headers = buildMap {
            // 审核245：WEB/DASH 地址必须带 web Referer（否则 CDN 403，审核215 实测），
            // 但 APP 签名地址带 web Referer 反而被拒（bv PlaybackRequestHeaders.kt）。
            // 按 URL 判定，两类都不会 403。
            val referer = com.example.piliai.playback.PlaybackRequestHeaders
                .refererFor("https://www.bilibili.com", videoUrl, audioUrl)
            referer?.let { put("Referer", it) }
            if (referer != null) put("Origin", "https://www.bilibili.com")
            if (cookie.isNotEmpty()) put("Cookie", cookie)
        }
        // 审核315：记住请求头与 UA，供取帧复用
        currentHeaders = headers
        val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        currentUserAgent = userAgent
        val dataSourceFactory = androidx.media3.datasource.DefaultHttpDataSource.Factory()
            .setUserAgent(userAgent)
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(headers)

        val isLocal = !videoUrl.startsWith("http") && !videoUrl.startsWith("//")
        val videoUri = if (videoUrl.startsWith("/")) Uri.fromFile(java.io.File(videoUrl)) else videoUrl.toUri()
        val videoItem = MediaItem.Builder().setUri(videoUri).build()
        val localFactory = androidx.media3.datasource.DefaultDataSource.Factory(appContext)
        val videoSource: androidx.media3.exoplayer.source.MediaSource =
            if (isLocal) {
                androidx.media3.exoplayer.source.ProgressiveMediaSource.Factory(localFactory)
                    .createMediaSource(videoItem)
            } else {
                androidx.media3.exoplayer.source.ProgressiveMediaSource.Factory(dataSourceFactory)
                    .createMediaSource(videoItem)
            }

        val merged = if (!audioUrl.isNullOrEmpty()) {
            val audioUri = if (audioUrl.startsWith("/")) Uri.fromFile(java.io.File(audioUrl)) else audioUrl.toUri()
            val audioItem = MediaItem.Builder().setUri(audioUri).build()
            val audioSource: androidx.media3.exoplayer.source.MediaSource =
                androidx.media3.exoplayer.source.ProgressiveMediaSource.Factory(
                    if (isLocal) localFactory else dataSourceFactory
                ).createMediaSource(audioItem)
            androidx.media3.exoplayer.source.MergingMediaSource(videoSource, audioSource)
        } else {
            videoSource
        }

        ensureBackend()
        val current = backend
        val playbackRequest = PlaybackRequest(
            videoUri = videoUri,
            audioUri = audioUrl?.takeIf { it.isNotEmpty() }?.let { raw ->
                if (raw.startsWith("/")) Uri.fromFile(java.io.File(raw)) else raw.toUri()
            },
            headers = headers,
            userAgent = userAgent,
            resumePositionMs = resumePositionMs,
            playWhenReady = playWhenReady,
        )
        current?.prepare(playbackRequest)
        if (current is com.example.piliai.playback.backend.GpuNextBackend) {
            pendingGpuSurface?.let { current.attachSurface(it) }
        }
        _state.value = _state.value.copy(
            isPlaying = playWhenReady, isBuffering = true,
            error = null, duration = 0L
        )
        // 审核298：起播后主动应用一次超分 effect。原来只在 onVideoSizeChanged
        // 里应用——那个回调只在**尺寸变化**时触发，如果用户在设置里开了超分
        // 再进播放器、或播放中改设置不重播，就永远不生效（用户看到「按钮等于
        // 摆设」）。这里是兜底；尺寸真正就绪后 onVideoSizeChanged 还会再应用
        // 一次（那时才有真实分辨率，算出的目标更准）。
        applySuperResolutionEffect()
    }

    /** 审核298：按当前超分设置应用 GL effect（Media3 内核） */
    private fun applySuperResolutionEffect() {
        val media3 = backend as? com.example.piliai.playback.backend.Media3Backend
            ?: return
        val size = media3.nativePlayer()?.videoSize
        if (size == null || size.width <= 0 || size.height <= 0) return
        val effects = com.example.piliai.piliplus.Media3SuperResolutionApplier
            .resolve(size.width, size.height).orEmpty()
        media3.setVideoEffects(effects)
    }

    /** 审核216：pauseOnMinimize——Screen 的 ON_PAUSE/ON_RESUME 回调 */
    fun onAppBackground() {
        viewModelScope.launch {
            // 审核249：两个开关合成一个决策。
            // - pauseOnMinimize（切后台暂停，默认关）
            // - continuePlayInBackground（后台继续播放，默认开）
            // 语义上是互补的一对，但都是独立键，用户可能只改其中一个。
            // 规则：任一「要暂停」的信号生效就暂停；只有两个都不要求暂停才继续播。
            val storage = com.example.piliai.utils.StorageManager.getInstance(appContext)
            val pauseOnMinimize = runCatching { storage.pauseOnMinimizeFlow.first() }
                .getOrDefault(false)
            val continueInBackground = runCatching { storage.continuePlayInBackgroundFlow.first() }
                .getOrDefault(true)
            val shouldPause = pauseOnMinimize || !continueInBackground
            if (shouldPause && backend?.isPlaying() == true) {
                backend?.pause()
                _state.value = _state.value.copy(isPlaying = false)
            }
        }
    }

    /** 审核216：enableAutoEnter——首次加载完成自动进入全屏，由 Screen 观察 state 触发 */
    suspend fun shouldAutoEnterFullscreen(): Boolean = runCatching {
        com.example.piliai.utils.StorageManager.getInstance(appContext)
            .autoEnterFullscreenFlow.first()
    }.getOrDefault(false)

    fun play() {
        viewModelScope.launch {
            backend?.play()
            _state.value = _state.value.copy(isPlaying = true)
            // 审核318：同步播放态到 MediaSession，锁屏/通知栏的状态才准确
            com.example.piliai.playback.PlaybackRegistry.refreshPlaybackState()
        }
    }
    
    fun pause() {
        viewModelScope.launch {
            backend?.pause()
            _state.value = _state.value.copy(isPlaying = false)
            com.example.piliai.playback.PlaybackRegistry.refreshPlaybackState()
        }
    }
    
    fun seekTo(positionMs: Long) {
        viewModelScope.launch {
            backend?.seekTo(positionMs)
            _state.value = _state.value.copy(currentTime = positionMs)
        }
    }
    
    fun setVolume(volume: Float) {
        val clamped = volume.coerceIn(0f, 1f)
        viewModelScope.launch {
            backend?.setVolume(clamped)
            _state.value = _state.value.copy(
                volume = clamped, isMuted = clamped == 0f
            )
        }
    }
    
    /**
     * 设置播放速度。
     * @param raw true 时不做档位归一化，允许长按临时 3x 等越界值。
     */
    fun setPlaybackSpeed(speed: Float, raw: Boolean = false) {
        ++playbackSpeedGeneration
        playbackSpeedRestoreJob?.cancel()
        val normalizedSpeed = normalizePlaybackSpeed(speed, raw)
        viewModelScope.launch {
            applyPlaybackSpeed(normalizedSpeed)
            if (!raw) {
                com.example.piliai.utils.StorageManager.getInstance(appContext)
                    .setPlaybackSpeed(normalizedSpeed)
            }
        }
    }

    private fun normalizePlaybackSpeed(speed: Float, raw: Boolean): Float = when {
        raw -> speed.coerceIn(0.25f, 4.0f)
        speed <= 0.5f -> 0.5f
        speed <= 1.0f -> 1.0f
        speed <= 2.0f -> speed
        else -> 2.0f
    }

    private fun applyPlaybackSpeed(speed: Float) {
        backend?.setSpeed(speed)
        _state.value = _state.value.copy(playbackSpeed = speed)
    }

    fun restorePlaybackSpeed() {
        if (playbackSpeedGeneration != 0L || playbackSpeedRestoreJob?.isActive == true) return
        val generation = playbackSpeedGeneration
        playbackSpeedRestoreJob = viewModelScope.launch {
            try {
                val speed = com.example.piliai.utils.StorageManager.getInstance(appContext)
                    .playbackSpeedFlow.first()
                currentCoroutineContext().ensureActive()
                if (generation != playbackSpeedGeneration) return@launch
                applyPlaybackSpeed(normalizePlaybackSpeed(speed, raw = false))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                android.util.Log.w("PiliPlayer", "Could not restore playback speed", error)
            }
        }
    }
    
    fun toggleMute() {
        viewModelScope.launch {
            val newMuted = !_state.value.isMuted
            backend?.setVolume(if (newMuted) 0f else 1f)
            _state.value = if (newMuted) {
                _state.value.copy(isMuted = true, volume = 0f)
            } else {
                _state.value.copy(isMuted = false, volume = 1f)
            }
        }
    }
    
    fun togglePlayPause() {
        if (_state.value.isPlaying) pause() else play()
    }
    
    fun seekRelative(offsetMs: Long) {
        val current = _state.value.currentTime
        val duration = _state.value.duration
        seekTo((current + offsetMs).coerceIn(0L, duration))
    }
    
    fun setError(error: String?) {
        if (error != null) {
            // 审核276：错误码翻译成人话（用户原来看到裸的 "-352"/"-403"）
            _state.value = _state.value.copy(
                error = com.example.piliai.data.remote.BiliErrorTranslator.translate(error) ?: error
            )
        }
    }

    /**
     * 分享反馈（复制链接成功提示）。
     * 审核241：必须走 toast 通道——此前用 setError 会触发全屏红色错误覆盖层
     * 并隐藏播放控制条，用户分享一次就像播放器崩了。
     */
    fun notifyShared(link: String) {
        showToast("链接已复制")
    }

    /** 批次L40：实时在线人数（匿名，失败静默） */
    private fun loadOnlineCount(bvid: String, cid: Long) {
        viewModelScope.launch {
            val n = BiliApiClient().getOnlineTotal(bvid, cid).getOrNull() ?: 0L
            if (n > 0L) _state.value = _state.value.copy(onlineCount = n)
        }
    }

    fun addDanmakuEvents(events: List<DanmakuEvent>, replace: Boolean = true) {
        // 审核311：实现移到 DanmakuController，这里只同步 UI 侧总数
        danmakuCtrl.addEvents(events, replace)
        _state.value = _state.value.copy(dmTotalCount = danmakuCtrl.totalCount)
    }

    fun getDanmakuAtTime(currentTimeMs: Long): List<DanmakuEvent> =
        danmakuCtrl.eventsAt(currentTimeMs)
    
    // 审核轮210：超分辨率真接入（Media3 1.3.1 已有 setVideoEffects，javap 验证；
    // ==================== PlaybackEventListener 实现 ====================

    /**
     * 审核242：解码器过载提示（bv ExoMediaPlayer.kt:71-90 的丢帧上报）。
     * 只提示一次，由用户决定是否降档；不自动改画质（会打断播放）。
     */
    override fun onDecoderOverloaded() {
        if (decoderOverloadReported) return
        decoderOverloadReported = true
        // 审核328：关闭态的字符串是 disable 不是 off，这里写成 off 会把「关闭」
        // 判成开启，卡顿提示永远让用户去关一个没开的功能。
        val superResOn = com.example.piliai.utils.RendererPrefs.superResolutionMode != "disable"
        val message = if (superResOn) {
            "播放卡顿：建议关闭超分辨率"
        } else {
            "播放卡顿：建议降低画质"
        }
        showToast(message)
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        _state.value = _state.value.copy(isPlaying = isPlaying)
    }
    
    override fun onIsLoadingChanged(isLoading: Boolean) {
        _state.value = _state.value.copy(isBuffering = isLoading)
    }
    
    override fun onBuffering(isBuffering: Boolean) {
        _state.value = _state.value.copy(isBuffering = isBuffering)
    }
    
    override fun onDurationChanged(durationMs: Long) {
        _state.value = _state.value.copy(duration = durationMs)
    }
    
    override fun onPositionChanged(positionMs: Long) {
        _state.value = _state.value.copy(currentTime = positionMs)
    }
    
    override fun onPlaybackEnded() {
        viewModelScope.launch {
            onPlaybackEndedInternal()
        }
    }
    
    override fun onError(message: String) {
        // 审核290：CDN 节点故障自动切换。B 站 playurl 返回的**首个节点可能
        // TLS 握手超时**（实测遇到过 mcdn 节点抖动，同一流的 backupUrl 节点
        // 却完全正常）。原来这里直接报错，用户看到「播放失败」却无法自救。
        if (trySwitchCdnNode(message)) return
        setError(message)
    }

    /**
     * 审核290：当前流播放失败时尝试切换到下一个 CDN 节点。
     * @return true = 已切换并重启播放（调用方不应再报错）
     */
    private fun trySwitchCdnNode(message: String): Boolean {
        // 只在网络/IO 类错误时切换；解码错误换节点也没用
        val lower = message.lowercase()
        val isNetworkError = lower.contains("unable to connect") ||
            lower.contains("timeout") ||
            lower.contains("timed out") ||
            lower.contains("connection") ||
            lower.contains("ssl") ||
            lower.contains("handshake") ||
            lower.contains("network") ||
            lower.contains("ioexception") ||
            lower.contains("eof") ||
            lower.contains("403") ||
            lower.contains("404")
        if (!isNetworkError) return false

        val currentVideo = currentUri
        val idx = videoUrlCandidates.indexOfFirst { it == currentVideo }
        // 已经试过最后一个节点，或压根没有候选列表
        if (idx < 0 || idx >= videoUrlCandidates.lastIndex) return false

        val nextVideo = videoUrlCandidates[idx + 1]
        // 音频同步切到同序号的备用节点（没有则沿用主音频）
        val nextAudio = audioUrlCandidates.getOrNull(idx + 1)
            ?: audioUrlCandidates.firstOrNull()
            ?: cachedAudioUrl
        if (nextVideo.isEmpty()) return false

        android.util.Log.i(
            "PiliPlayer",
            "CDN 节点切换：${idx} -> ${idx + 1}/${videoUrlCandidates.size}（原因：$message）"
        )
        showToast("网络不佳，正在切换线路…")
        startPlayback(nextVideo, nextAudio)
        return true
    }
    
    override fun onVideoSizeChanged(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        (backend as? com.example.piliai.playback.backend.Media3Backend)?.let { media3 ->
            // 审核318：HDR 检测真正接入 effect 链。此前这里只有一句 Log.i
            // （「tone-mapping 待 Media3 1.5+」）——设置页的开关点下去毫无效果。
            // 现在 isHdrContent 命中且开关打开时，Media3SuperResolutionApplier
            // 会在 effect 列表里追加 RgbMatrix 高光压缩矩阵。
            val isHdr = media3.nativePlayer()?.videoFormat?.let { fmt ->
                com.example.piliai.piliplus.isHdrContent(fmt.colorInfo)
            } == true
            com.example.piliai.piliplus.Media3SuperResolutionApplier.hdrToneMappingEnabled =
                com.example.piliai.utils.RendererPrefs.hdrToneMappingEnabled && isHdr
            val effects = com.example.piliai.piliplus.Media3SuperResolutionApplier
                .resolve(width, height).orEmpty()
            media3.setVideoEffects(effects)
        }
    }
    
    override fun onCleared() {
        // 审核295：空降助手——注销 seek 通道，避免播放器销毁后评论页还能往
        // 已销毁的实例发跳转请求（虽然 handler 是 lambda 不会泄漏 Activity，
        // 但会静默失效）。
        com.example.piliai.playback.VideoSeekBus.unregister()
        ++playbackSpeedGeneration
        clockJob?.cancel()
        playbackSpeedRestoreJob?.cancel()
        completionJob?.cancel()
        loadJob?.cancel()
        // 审核311：字幕与弹幕的协程改由各自 Controller 的 scope 托管，
        // 不再需要在 onCleared 逐个 cancel
        try {
            // 审核318：先反注册再 release——顺序反了会让 MediaSession 在
            // player 已 release 后仍持有它，系统媒体控制回调打到死 player 上。
            com.example.piliai.playback.PlaybackRegistry.unregister()
            backend?.release()
        } finally {
            backend = null
            sourceHttpClient.dispatcher.cancelAll()
            sourceHttpClient.connectionPool.evictAll()
            sourceHttpClient.dispatcher.executorService.shutdown()
            super.onCleared()
        }
    }
    
    fun getCurrentTime(): Long = _state.value.currentTime
    fun getDuration(): Long = _state.value.duration
    fun getPositionPercent(): Int {
        val duration = _state.value.duration
        return if (duration == 0L) 0 else ((_state.value.currentTime * 100) / duration).toInt()
    }

    // ========== 互动（点赞/投币/收藏/历史上报） ==========

    var aid: Long = 0L
        private set

    /** 当前视频标题/封面（供下载等外部操作，批次I 接线） */
    var videoTitle: String = ""
        private set
    var videoCover: String = ""
        private set
    var videoOwner: String = ""
    var videoOwnerMid: Long = 0L
        private set
    var videoOwnerFace: String = ""
        private set
    // 批次L18：简介/标签/发布时间
    var videoDesc: String = ""
    var videoTags: List<String> = emptyList()
    var videoPubdate: Long = 0L
        private set
    /** 审核330：简介页统计（播放/弹幕/评论） */
    var videoView: Long = 0L
        private set
    var videoDanmaku: Long = 0L
        private set
    var videoReply: Long = 0L
        private set
    // 批次L22：AI 总结
    var aiSummary: String = ""
        private set
    var aiOutline: List<Pair<Long, String>> = emptyList()  // (timestamp, title)
        private set

    /** 审核328：开机时查一次关注状态（未登录静默） */
    private fun loadOwnerFollowState(bvid: String) {
        val mid = videoOwnerMid
        if (mid <= 0L) return
        viewModelScope.launch {
            _isFollowingOwner.value = BiliApiClient().isFollowing(mid)
        }
    }

    /** 审核328：是否已关注当前 UP 主（播放页关注按钮） */
    private val _isFollowingOwner = MutableStateFlow(false)
    val isFollowingOwner: StateFlow<Boolean> = _isFollowingOwner.asStateFlow()

    /** 审核328：关注/取关当前视频 UP 主 */
    fun followOwner(mid: Long) {
        if (mid <= 0L) return
        viewModelScope.launch {
            val target = !_isFollowingOwner.value
            BiliApiClient().modifyFollow(mid, target).onSuccess {
                _isFollowingOwner.value = target
                showToast(if (target) "已关注" else "已取消关注")
            }.onFailure {
                showToast("操作失败，请检查登录状态")
            }
        }
    }

    /** 批次L22：加载 AI 总结（需登录，失败静默显示无摘要） */
    fun loadAiConclusion() {
        viewModelScope.launch {
            val resp = BiliApiClient().getAiConclusion(currentBvid, effectiveCid).getOrNull() ?: return@launch
            val mr = resp.data?.model_result
            if (resp.data?.code == 0 && mr != null && mr.result_type > 0) {
                aiSummary = mr.summary
                aiOutline = mr.outline.map { Pair(it.timestamp, it.title) }
            }
        }
    }

    /** 发起离线下载（下载按钮 → DownloadManager 队列） */
    fun downloadCurrent(appContext: android.content.Context) {
        val bv = currentBvid
        if (bv.isEmpty()) return
        // 审核315：番剧原来直接 return 无提示——用户点下载没反应，
        // 以为是按钮坏了。给明确说明（番剧缓存走分P面板的逐项下载）。
        if (bv.startsWith("ep")) {
            showToast("番剧请从分P面板选择要缓存的集数")
            return
        }
        com.example.piliai.data.repository.DownloadManager.download(
            appContext, bv,
            title = videoTitle, cover = videoCover, ownerName = videoOwner
        )
    }

    /** 批量下载：下载指定分P（分P面板逐项触发，批次L10/L13 支持番剧） */
    fun downloadPart(appContext: android.content.Context, partIndex: Int) {
        val p = pages.getOrNull(partIndex) ?: return
        val bv = currentBvid
        if (bv.isEmpty()) return
        if (bv.startsWith("ep")) {
            // 番剧模式：ep{id} 虚拟键 → pgc playurl
            val ep = bv.removePrefix("ep").toLongOrNull() ?: return
            com.example.piliai.data.repository.DownloadManager.downloadPgcPart(
                appContext, ep, p.cid, p.page,
                pageLabel = p.part.ifBlank { "第${p.page}集" },
                durationSec = p.durationSec,
                title = p.part.ifBlank { videoTitle },
                cover = videoCover, ownerName = videoOwner
            )
            return
        }
        com.example.piliai.data.repository.DownloadManager.downloadPart(
            appContext, bv, p.cid, p.page,
            pageLabel = "P${p.page}",
            durationSec = p.durationSec,
            title = "${videoTitle} ${p.part}".trim(),
            cover = videoCover, ownerName = videoOwner
        )
    }

    fun pagesSnapshot(): List<PartInfo> = pages.toList()
    private var epId: Long = 0L
    private var effectiveCid: Long = 0L
    private var currentBvid: String = ""
    private var defaultFavFolderId: Long = 0L
    private val repo by lazy { VideoRepository(BiliApiClient()) }

    /**
     * 登录态下加载互动状态：已赞/已投/已藏（archive/relation）+ 默认收藏夹 id + stat 计数。
     * 失败静默（未登录时 relation 会 -101，直接跳过）。
     */

    /**
     * 审核313：倍速档位列表（设置页可编辑）。
     * 原来是播放器里硬编码 listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)。
     */
    private val _speedList = MutableStateFlow(
        com.example.piliai.utils.StorageManager.DEFAULT_SPEED_LIST
    )
    val speedList: StateFlow<List<Float>> = _speedList.asStateFlow()

    /** 审核313：保存倍速档位（设置页调用） */
    fun setSpeedList(speeds: List<Float>) {
        val normalized = com.example.piliai.utils.StorageManager.normalizeSpeedList(speeds)
        _speedList.value = normalized
        viewModelScope.launch {
            com.example.piliai.utils.StorageManager
                .getInstance(appContext).setPlaybackSpeedList(normalized)
        }
    }

    /**
     * 审核312：互动控制（从本类提取）。点赞/投币/收藏/稍后再看/历史上报
     * 都归它；本类只把状态透出去 + 转发错误与 toast。
     */
    private val engagement = EngagementController(
        scope = viewModelScope,
        aidProvider = { aid },
        onError = { setError(it) },
        onToast = { showToast(it) },
    )

    val isLiked: StateFlow<Boolean> = engagement.isLiked
    val isFavorited: StateFlow<Boolean> = engagement.isFavorited
    // 审核317：coinCount 已删——UI 只展示 coinCountTotal（投币总数），
    // 单个视频的已投状态从不展示，留着是死 StateFlow。
    val likeCount: StateFlow<Long> = engagement.likeCount
    val coinCountTotal: StateFlow<Long> = engagement.coinCountTotal
    val favCount: StateFlow<Long> = engagement.favCount
    val favFolders: StateFlow<List<Triple<Long, String, Int>>> = engagement.favFolders

    /** 加载互动状态（普通视频/番剧/离线三条链路共用） */
    private fun loadEngagement(aid: Long, bvid: String) = engagement.load(aid, bvid)

    /** 发送视频弹幕（批次L5：需登录，插入当前进度点） */
    fun sendDanmaku(text: String, mode: Int = 1, color: Int = 16777215) {
        if (text.isBlank()) return
        viewModelScope.launch {
            if (!AccountSession.isLogin) { setError("请先登录后发弹幕"); return@launch }
            val cid = effectiveCid
            if (cid == 0L || currentBvid.isEmpty()) { setError("弹幕发送失败（视频未就绪）"); return@launch }
            val progress = backend?.positionMs() ?: 0L
            BiliApiClient().sendVideoDanmaku(cid, currentBvid, text.trim(), progress, mode, color)
                .onSuccess { ok ->
                    if (ok) {
                        // 本地立即插入一条，反馈真实感
                        val danmaku = DanmakuEvent(
                            id = "local_${System.currentTimeMillis()}",
                            timestamp = progress,
                            content = text.trim(),
                            color = color,
                            fontSize = 25,
                            mode = mode
                        )
                        // 审核311：本地插入走 DanmakuController
                        danmakuCtrl.addLocal(danmaku)
                        // 审核304（真 bug）：原来是 setError("弹幕发送成功")——
                        // setError 会触发全屏红色错误覆盖层并隐藏播放控制条，
                        // 用户发一条弹幕就像播放器崩了。与审核262 修
                        // 「稍后再看」是同一个 bug 模式，这里漏了。成功走 toast。
                        showToast("弹幕发送成功")
                    } else setError("弹幕发送失败（可能需要登录或被风控）")
                }
                .onFailure { setError("弹幕发送失败: ${it.message}") }
        }
    }

    fun loadFavFolders() = engagement.loadFavFolders()
    fun favoriteTo(mediaId: Long) = engagement.favoriteTo(mediaId)
    fun toggleFavorite() = engagement.toggleFavorite()
    fun toggleLike() = engagement.toggleLike()
    fun coinOnce(multiply: Int = 1) = engagement.coinOnce(multiply)
    fun addToWatchLater() = engagement.addToWatchLater()

    /** 上报历史进度（播放中每 15 秒调一次） */
    fun reportProgress() {
        engagement.reportProgress(aid, effectiveCid, _state.value.currentTime / 1000)
    }

    private suspend fun ensureAid() {
        if (aid > 0L) return
        // 番剧 ep 模式：bvid 是 "ep{id}" 虚拟值，直接用 pgc 详情里的真实 aid
        if (currentBvid.startsWith("ep")) return
        aid = repo.getVideoDetail(currentBvid).getOrNull()?.data?.aid ?: 0L
    }

    // ========== 批次A：播放器补全（多P/清晰度/相关视频/弹幕设置） ==========

    /** 视频分P列表（来自详情接口 pages[]） */
    data class PartInfo(
        val cid: Long,
        val page: Int,
        val part: String,
        val durationSec: Long,
        /** PGC 分集 ID；普通视频分P为 0。 */
        val epId: Long = 0L
    )

    /** 可选清晰度 */
    data class QualityOption(val qn: Int, val label: String)

    /** 相关视频 */
    data class RelatedItem(
        val bvid: String, val cid: Long, val title: String, val pic: String,
        val author: String, val durationText: String, val viewText: String
    )

    private var pages: List<PartInfo> = emptyList()
    private var currentPartIndex: Int = 0
    private var availableQualities: List<QualityOption> = emptyList()
    private var currentQn: Int = 80
    private var dashVideos: List<com.example.piliai.data.model.StreamInfo> = emptyList()

    /** 当前分P 页码（1-based，单P视频恒为 1） */
    val currentPage: Int get() = pages.getOrNull(currentPartIndex)?.page ?: 1
    fun partList(): List<PartInfo> = pages
    val hasMultipleParts: Boolean get() = pages.size > 1

    /** 弹幕开关 + 透明度（UI 直接读写） */
    var danmakuEnabled: Boolean
        get() = _state.value.danmakuOn
        set(v) { _state.value = _state.value.copy(danmakuOn = v) }

    /**
     * 切换分P：重走播放链路（不重取详情）。
     */
    fun playPart(pageIndex: Int) {
        val p = pages.getOrNull(pageIndex) ?: return
        currentPartIndex = pageIndex
        _state.value = _state.value.copy(currentPart = p.page, currentPartTitle = p.part)
        completionJob?.cancel()
        loadJob?.cancel()
        resetSubtitleState()
        val generation = ++loadGeneration
        loadJob = viewModelScope.launch {
            if (p.epId > 0L) {
                // 番剧必须走 PGC 链路，不能把 ep{id} 当作普通 BVID。
                loadPgcEpisode(p.epId, generation)
            } else {
                resolveAndPlay(currentBvid, p.cid, generation)
            }
        }
    }

    /**
     * 切换清晰度：从已缓存的 dash.video[] 中按 qn 找流，无缝换源（保持进度）。
     * dash.video[].id 即 qn。
     */
    fun switchQuality(qn: Int) {
        if (qn == currentQn) return
        val target = dashVideos.firstOrNull { it.id == qn } ?: return
        currentQn = qn
        val pos = backend?.positionMs() ?: 0L
        val playing = _state.value.isPlaying
        viewModelScope.launch {
            // 审核239 B1：同步 state.currentQn——原实现只改私有 var，清晰度菜单
            // 高亮与顶栏文字都不更新（用户看不出切换是否生效）。
            _state.value = _state.value.copy(currentQn = qn)
            // 审核239 B2：重新取一个有效音频 URL——原实现直接复用 cachedAudioUrl，
            // B站音频 URL 有时效，切换清晰度时旧 URL 可能已过期（403/无声）。
            val audio = loadFreshAudio(effectiveCid)
            val videoUrl = target.baseUrl
            if (videoUrl == null) {
                setError("该清晰度暂不可用，请换一档")
                return@launch
            }
            // 审核300：切清晰度也要带 CDN 候选列表。原来只传 baseUrl 单个地址，
            // 而普通播放已有审核290 的故障转移——一遇到抖动节点，切清晰度
            // 必然失败（用户看到「该清晰度暂不可用」但其实是网络问题）。
            videoUrlCandidates = listOf(videoUrl.toHttpsUrl()) +
                target.backupUrl.map { it.toHttpsUrl() }
            audioUrlCandidates = audio?.let { listOf(it) }.orEmpty()
            startPlayback(videoUrl, audio, resumePositionMs = pos, playWhenReady = playing)
        }
    }

    /** 审核239 B2：重新请求 playurl 拿新鲜音频流（不走缓存） */
    private suspend fun loadFreshAudio(cid: Long): String? {
        if (currentBvid.startsWith("ep")) return cachedAudioUrl  // PGC 链路结构不同，维持缓存
        return repo.getPlayUrl(currentBvid, cid, qn = currentQn)
            .getOrNull()?.third
            ?.also { cachedAudioUrl = it }
            ?: cachedAudioUrl  // 失败退回缓存（聊胜于无）
    }

    private var cachedAudioUrl: String? = null

    /**
     * 审核290：当前流的 CDN 候选列表（baseUrl + backupUrl）。
     *
     * B 站 playurl 给每条流返回多个 CDN 节点，实测中遇到过**首个节点 TLS
     * 握手超时、备用节点完全正常**的情况。原来的实现只取 baseUrl，一遇到
     * 这种节点整个播放就失败。保存候选列表供播放失败时切换节点重试。
     */
    private var videoUrlCandidates: List<String> = emptyList()
    private var audioUrlCandidates: List<String> = emptyList()
    /** 审核267：当前播放地址（取帧用）；本地文件为 file:// Uri */
    private var currentUri: String = ""

    /**
     * 审核315：当前播放的请求头与 UA，供取帧（VideoFrameCapture）复用。
     * 在线 DASH 取帧要走同一条 CDN，缺 Referer/Cookie 会 403。
     */
    private var currentHeaders: Map<String, String> = emptyMap()
    private var currentUserAgent: String = ""

    /** 审核315：取帧用——当前播放的请求头 */
    fun currentPlaybackHeaders(): Map<String, String> = currentHeaders

    /** 审核315：取帧用——当前播放的 UA */
    fun currentPlaybackUserAgent(): String = currentUserAgent

    /** 加载相关视频列表 */
    private fun loadRelated(bvid: String) {
        viewModelScope.launch {
            runCatching {
                val list = BiliApiClient().getRelatedVideos(bvid).getOrNull().orEmpty()
                // 审核233：相关视频应用推荐过滤（PiliPlus applyFilterToRelatedVideos；
                // RelatedItem 无点赞数字段，只做时长+标题屏蔽词两维过滤）
                val config = com.example.piliai.utils.RecommendFilter.Config(
                    minDurationSec = relatedMinDuration, banWords = relatedBanWords,
                )
                val filtered = if (relatedApplyFilter && config.enabled)
                    list.filter { v -> !config.filterDuration(v.duration) && !config.filterTitle(v.title) }
                else list
                _state.value = _state.value.copy(
                    related = filtered.map {
                        RelatedItem(
                            bvid = it.bvid, cid = it.cid, title = it.title, pic = it.pic,
                            author = it.owner?.name ?: "",
                            durationText = it.duration.let { s -> "%d:%02d".format(s / 60, s % 60) },
                            viewText = formatCount(it.stat?.view ?: 0L)
                        )
                    }
                )
            }
        }
    }

    /** 播放相关视频（重置状态换源） */
    fun playRelated(item: RelatedItem) {
        _state.value = _state.value.copy(related = emptyList(), error = null)
        completionJob?.cancel()
        loadJob?.cancel()
        resetSubtitleState()
        val generation = ++loadGeneration
        loadJob = viewModelScope.launch {
            currentBvid = item.bvid
            resolveAndPlay(item.bvid, item.cid, generation)
        }
    }

    /** 弹幕透明度 0..1 */
    fun setDanmakuAlpha(alpha: Float) {
        _state.value = _state.value.copy(danmakuAlpha = alpha.coerceIn(0f, 1f))
    }

    /** 弹幕大小倍率 0.5..2.0（持久化到 DataStore） */
    fun setDanmakuScale(scale: Float) {
        _state.value = _state.value.copy(danmakuScale = scale.coerceIn(0.5f, 2f))
        viewModelScope.launch {
            runCatching {
                com.example.piliai.utils.StorageManager.getInstance(appContext)
                    .setDanmakuScale(scale.coerceIn(0.5f, 2f))
            }
        }
    }

    /** 亮度是否被用户手动调整过（避免一进页面强设系统亮度） */
    var brightnessTouched: Boolean = false
        private set

    /**
     * 顶部弹幕开关（顶部弹幕可能被遮挡，用户可关闭）。
     *
     * 审核247（真 bug）：原来是硬编码初始值 true + persist* 只写不回读，
     * 用户关掉顶部弹幕后重启 App 又回到开启。现在由 init 里 collect
     * StorageManager.danmakuShowTopFlow 填充，与其余弹幕设置同一套模式。
     */
    var cachedDmShowTop: Boolean = true
        private set

    /** 底部弹幕开关。同 [cachedDmShowTop]，持久化生效。 */
    var cachedDmShowBottom: Boolean = true
        private set

    /**
     * 审核247：顶部弹幕开关的 UI 状态源。
     * 由 DataStore 驱动，UI 只读不写副本（单一数据源），
     * 修改一律走 persistDmShowTop()。
     */
    private val _dmShowTop = MutableStateFlow(cachedDmShowTop)
    val dmShowTop: StateFlow<Boolean> = _dmShowTop.asStateFlow()

    /** 审核247：底部弹幕开关的 UI 状态源。同 [dmShowTop]。 */
    private val _dmShowBottom = MutableStateFlow(cachedDmShowBottom)
    val dmShowBottom: StateFlow<Boolean> = _dmShowBottom.asStateFlow()

    fun persistDmShowTop(enabled: Boolean) {
        viewModelScope.launch {
            runCatching {
                com.example.piliai.utils.StorageManager.getInstance(appContext).setDanmakuShowTop(enabled)
            }
        }
    }

    fun persistDmShowBottom(enabled: Boolean) {
        viewModelScope.launch {
            runCatching {
                com.example.piliai.utils.StorageManager.getInstance(appContext).setDanmakuShowBottom(enabled)
            }
        }
    }

    private fun onPlaybackEndedInternal() {
        completionJob?.cancel()
        val generation = loadGeneration
        val partIndex = currentPartIndex
        _state.value = _state.value.copy(isPlaying = false, isBuffering = false)
        completionJob = viewModelScope.launch {
            val action = try {
                com.example.piliai.utils.StorageManager.getInstance(appContext)
                    .completionActionFlow.first()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                "stop"
            }
            if (generation != loadGeneration || currentPartIndex != partIndex) return@launch
            when (action) {
                "repeat" -> {
                    seekTo(0L)
                    play()
                    _state.value = _state.value.copy(currentTime = 0L)
                }
                "next" -> when {
                    // 审核244（P0-3）：「自动连播分P」开关此前是假的——
                    // DataStore 键 0 个消费者，播完逻辑只读 completionAction。
                    // 这里补上：关掉时不自动下一 P，只提示。
                    continuePlayingPart && partIndex + 1 in pages.indices -> playNextPart()
                    continuePlayingPart -> _state.value.related.firstOrNull()?.let(::playRelated)
                    partIndex + 1 in pages.indices -> showToast("播放完毕，还有下一 P")
                }
            }
        }
    }

    /** 播放下一 P（多P视频）；已是最后一 P 时提示。 */
    fun playNextPart() {
        val next = currentPartIndex + 1
        if (next !in pages.indices) {
            showToast("已经是最后一P")
            return
        }
        playPart(next)
    }

    /**
     * 一键三连（长按点赞）：点赞 + 投币 1 枚 + 收藏，需登录。
     *
     * 审核289：原来三个请求发完就立刻 showToast("已一键三连")，但它们都是
     * 异步的——投币可能因余额不足、收藏可能因未取到默认收藏夹而失败，
     * 于是用户同时看到「已一键三连」和「投币失败」，自相矛盾。
     * 改为不预设成功提示，各操作的成败由它们自己的 setError/showToast 反馈。
     */
    fun likeCoinFav() {
        if (!AccountSession.isLogin) {
            setError("请先登录后再一键三连")
            return
        }
        // 未点赞时先点赞，避免重复点赞导致取消。
        // 审核312：isLiked 改读 EngagementController（PlayerState 的同名字段已删）
        if (!engagement.isLiked.value) toggleLike()
        coinOnce(1)
        toggleFavorite()
    }

    /** 轻量提示（替代全屏红字错误） */
    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

    private var toastJob: Job? = null

    fun showToast(message: String) {
        _toast.value = message
        toastJob?.cancel()
        toastJob = viewModelScope.launch {
            kotlinx.coroutines.delay(2000L)
            if (_toast.value == message) _toast.value = null
        }
    }

    /** 手势进度（UI 层渲染提示条用）：deltaX 横滑快进 */
    fun onGestureSeek(deltaX: Float, widthPx: Float) {
        if (widthPx <= 0f || duration() <= 0L) return
        val ratio = deltaX / widthPx
        val deltaMs = (ratio * duration() * 2).toLong()  // 全屏横滑≈2倍时长
        _state.value = _state.value.copy(gestureSeekDeltaMs = deltaMs)
    }

    fun cancelGestureSeek() {
        _state.value = _state.value.copy(gestureSeekDeltaMs = 0L)
    }

    fun commitGestureSeek() {
        val d = _state.value.gestureSeekDeltaMs
        if (d != 0L) {
            seekTo((_state.value.currentTime + d).coerceIn(0L, duration()))
            _state.value = _state.value.copy(gestureSeekDeltaMs = 0L)
        }
    }

    /** 左半屏竖滑调系统亮度（0..1），右半屏调音量 */
    fun onVerticalDrag(leftSide: Boolean, deltaY: Float, heightPx: Float) {
        if (heightPx <= 0f) return
        val delta = -deltaY / heightPx
        if (leftSide) {
            val nv = (_state.value.brightness + delta).coerceIn(0.05f, 1f)
            _state.value = _state.value.copy(
                brightness = nv,
                brightnessTouchedTick = _state.value.brightnessTouchedTick + 1,
            )
            brightnessTouched = true
        } else {
            val nv = (_state.value.volume + delta).coerceIn(0f, 1f)
            viewModelScope.launch { backend?.setVolume(nv) }
            _state.value = _state.value.copy(
                volume = nv, isMuted = nv == 0f,
                volumeTouchedTick = _state.value.volumeTouchedTick + 1,
            )
        }
    }

    /** 审核237：手势浮标显示完毕后清 tick */
    fun clearGestureTicks() {
        _state.value = _state.value.copy(volumeTouchedTick = 0L, brightnessTouchedTick = 0L)
    }

    private fun duration(): Long = backend?.durationMs() ?: _state.value.duration
}

/**
 * 审核311：弹幕事件类型别名。
 *
 * 实现已移到 [DanmakuController.Event]（从本类提取，见 DanmakuController.kt）。
 * 保留别名是为了不改动 20 余处引用点；放在顶层而非类内——类内 typealias
 * 会让 Room 的 KSP 崩溃（IllegalStateException，无错误信息）。
 */
typealias DanmakuEvent = DanmakuController.Event
