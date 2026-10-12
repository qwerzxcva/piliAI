package com.example.piliai.playback

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
    
    val activeBackend: String
        get() = com.example.piliai.playback.backend.PlaybackBackends.resolve(
            com.example.piliai.utils.RendererPrefs.playbackBackend
        )
    
    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()
    
    // 审核13：弹幕队列被 WS 线程/主线程/渲染帧并发读写 → 线程安全容器
    private val _danmakuQueue = java.util.concurrent.CopyOnWriteArrayList<DanmakuEvent>()
    
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
        val isLiked: Boolean = false,
        val isFavorited: Boolean = false,
        val coinCount: Int = 0,
        val likeCount: Long = 0L,
        val coinCountTotal: Long = 0L,
        val favCount: Long = 0L,
        // 批次A：播放器补全
        val currentPart: Int = 1,
        val currentPartTitle: String = "",
        val partCount: Int = 1,
        val danmakuOn: Boolean = true,
        val danmakuAlpha: Float = 1f,
        val danmakuScale: Float = 1f,
        val danmakuSpeed: Float = 1f,
        // 审核233：弹幕细节（PiliPlus 对齐）
        val danmakuShowArea: Float = 1f,
        val danmakuStrokeWidth: Float = 1.5f,
        val danmakuMerge: Boolean = false,
        val danmakuMassiveMode: Boolean = false,
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
    
    data class DanmakuEvent(
        val id: String,
        val timestamp: Long,
        val content: String,
        val color: Int,
        val fontSize: Int,
        val uid: Long = 0L,   // 批次audit25：携带发送者 uid 供 UID 屏蔽规则使用
        /** 弹幕类型：1=滚动 4=底部 5=顶部（用于按类型开关过滤） */
        val mode: Int = 1
    )
    
    /** 审核235：字幕偏好打包（combine 上限5个 Flow） */
    private data class SubtitlePrefs(
        val scale: Float, val padH: Float, val padB: Float, val bg: Float, val stroke: Float,
    )

    // 审核233：相关视频过滤配置（combine 收集，DataStore 持久化）
    private var relatedApplyFilter: Boolean = true
    private var relatedMinDuration: Int = 0
    private var relatedBanWords: String = ""

    init {
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
                            runCatching {
                                val cid = com.example.piliai.database.PiliNaraDatabase
                                    .getDatabase(appContext).downloadItemDao().getByBvid(bvid)?.cid ?: 0L
                                if (cid > 0L) loadDanmakuFor(cid)
                            }
                        }
                        startPlayback(playback.videoPath, playback.audioPath.takeIf { it.isNotEmpty() })
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
                    val mediaSource = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(
                        dataSourceFactory
                    ).createMediaSource(mediaItem)
                    if (bvid.isEmpty()) {
                        currentBvid = ""
                        this@VideoPlayerViewModel.epId = 0L
                        pages = emptyList()
                        currentPartIndex = 0
                        cachedAudioUrl = null
                        dashVideos = emptyList()
                        availableQualities = emptyList()
                        _danmakuQueue.clear()
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
                setError("Failed to load video: ${e.message}")
            }
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

        val (resp, video, audio) = repo.getPgcPlayUrl(epId, cid, qn = currentQn)
            .getOrElse { setError("番剧 playurl 失败: ${it.message}"); return }
        ensureCurrent(generation)
        if (video.isNullOrEmpty()) {
            setError("未解析到番剧流地址（可能为大会员专享）")
            return
        }
        cachedAudioUrl = audio
        dashVideos = resp.result?.dash?.video.orEmpty()
        currentQn = qnOf(resp)
        startPlayback(video, audio)
    }

    private fun qnOf(resp: com.example.piliai.data.model.PgcPlayUrlResponse): Int =
        resp.result?.quality ?: currentQn

    private suspend fun loadDanmakuFor(cid: Long) {
        // 审核（卡顿根因）：GSON 序列化几千条弹幕 + JNI 调 Rust merge + JSONObject
        // 解析都是重 CPU，原在主线程（viewModelScope 默认 Main）执行，进播放器卡。
        // 挪到 Default；只有最终 addDanmakuEvents（写 UI 状态）需要回主线程。
        val withUid = withContext(Dispatchers.Default) {
            val api = BiliApiClient()
            val resp = api.getDanmaku(cid).getOrNull() ?: return@withContext null
            val raw = resp.data.orEmpty()
            val mergedEvents = runCatching {
                val lib = com.example.piliai.danmaku.DanmakuNativeLib()
                val ptr = lib.create(com.example.piliai.danmaku.DanmakuNativeLib.DanmakuConfig())
                try {
                    val sources = listOf(raw.map { d ->
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
            } ?: raw.map { d ->
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
            val uidByTs = raw.map { d -> d.toParsed() }.associate { (it.timestamp * 1000).toLong() to it.uid }
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

    private var subtitleListJob: Job? = null
    private var subtitleBodyJob: Job? = null
    private var subtitleCues: List<com.example.piliai.data.model.SubtitleCue> = emptyList()
    // 可选字幕列表（批次L9：字幕选择 UI）——(id, 语言名, url)
    private val _subtitleTracks = MutableStateFlow<List<Triple<Long, String, String>>>(emptyList())
    val subtitleTracks: StateFlow<List<Triple<Long, String, String>>> = _subtitleTracks.asStateFlow()
    // 批次L41：视频章节
    private val _viewPoints = MutableStateFlow<List<com.example.piliai.data.model.ViewPoint>>(emptyList())
    val viewPoints: StateFlow<List<com.example.piliai.data.model.ViewPoint>> = _viewPoints.asStateFlow()
    // 批次L42：弹幕密度热力曲线（Rust 计算，归一化 0..1）
    private val _heatCurve = MutableStateFlow<List<Float>>(emptyList())
    val heatCurve: StateFlow<List<Float>> = _heatCurve.asStateFlow()

    private val _currentSubtitle = MutableStateFlow("")
    val currentSubtitle: StateFlow<String> = _currentSubtitle.asStateFlow()

    // 当前选中的字幕 id（批次L9）
    private val _selectedSubtitleId = MutableStateFlow(-1L)
    val selectedSubtitleId: StateFlow<Long> = _selectedSubtitleId.asStateFlow()

    private fun resetSubtitleState() {
        subtitleListJob?.cancel()
        subtitleBodyJob?.cancel()
        subtitleCues = emptyList()
        _subtitleTracks.value = emptyList()
        _selectedSubtitleId.value = -1L
        _currentSubtitle.value = ""
        _viewPoints.value = emptyList()
        _heatCurve.value = emptyList()
    }

    private fun loadSubtitles(bvid: String, cid: Long) {
        subtitleListJob?.cancel()
        if (bvid.isEmpty() || cid <= 0L || bvid.startsWith("ep")) return
        val generation = loadGeneration
        subtitleListJob = viewModelScope.launch {
            try {
                val resp = BiliApiClient().getPlayerV2(bvid, cid).getOrNull()?.data
                ensureCurrent(generation)
                val subs = resp?.subtitle?.subtitles.orEmpty()
                _subtitleTracks.value = subs.map { Triple(it.id, it.langDoc, it.subtitleUrl) }
                _viewPoints.value = resp?.viewPoints.orEmpty()
                val pick = subs.firstOrNull { !it.isLock } ?: return@launch
                selectSubtitle(pick.id)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // Optional subtitles must not interrupt video playback.
            }
        }
    }

    fun selectSubtitle(id: Long) {
        val url = if (id < 0L) null else
            _subtitleTracks.value.firstOrNull { it.first == id }?.third ?: return
        subtitleBodyJob?.cancel()
        _selectedSubtitleId.value = id
        subtitleCues = emptyList()
        _currentSubtitle.value = ""
        if (url == null) return
        val generation = loadGeneration
        subtitleBodyJob = viewModelScope.launch {
            try {
                val body = BiliApiClient().fetchSubtitleBody(url).getOrNull()
                ensureCurrent(generation)
                if (_selectedSubtitleId.value != id || body == null) return@launch
                subtitleCues = body.body.sortedBy { it.from }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // Keep failed subtitle requests out of the active timeline.
            }
        }
    }

    /** 播放器每帧/每秒调：更新当前字幕文本 */
    fun updateSubtitleAt(currentTimeMs: Long) {
        val sec = currentTimeMs / 1000f
        val cue = subtitleCues.firstOrNull { sec >= it.from && sec <= it.to }
        val text = cue?.content.orEmpty().replace("<br/>", "\n")
        if (text != _currentSubtitle.value) _currentSubtitle.value = text
    }

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
        // 批次L18：简介/标签/日期（简介面板数据源）
        videoDesc = detail?.desc.orEmpty()
        videoTags = detail?.tags?.takeIf { it.isNotEmpty() }
            ?.mapNotNull { it.tagName } ?: emptyList()
        videoPubdate = detail?.pubdate ?: 0L
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
        val (resp, videoUrl2, audioUrl2) = repo.getPlayUrl(bvid, effectiveCid, qn = currentQn)
            .getOrElse {
                setError("playurl 获取失败: ${it.message}")
                return
            }
        ensureCurrent(generation)
        this.effectiveCid = effectiveCid
        this.currentBvid = bvid
        cachedAudioUrl = audioUrl2
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
    }
    
    private fun startPlayback(
        videoUrl: String,
        audioUrl: String?,
        resumePositionMs: Long = 0L,
        playWhenReady: Boolean = true
    ) {
        // 审核215：DASH CDN 风控升级——实测无 buvid3 cookie 一律 403（连标准 UA 都拒）。
        // 必须：桌面 Chrome UA + Referer + AccountSession 的 buvid3/SESSDATA cookie。
        val cookie = runCatching {
            com.example.piliai.data.remote.AccountSession.cookieHeader()
        }.getOrNull().orEmpty()
        val headers = buildMap {
            put("Referer", "https://www.bilibili.com")
            put("Origin", "https://www.bilibili.com")
            if (cookie.isNotEmpty()) put("Cookie", cookie)
        }
        val dataSourceFactory = androidx.media3.datasource.DefaultHttpDataSource.Factory()
            .setUserAgent(
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
            )
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
        if (current is com.example.piliai.playback.backend.GpuNextBackend) {
            val request = PlaybackRequest(
                videoUri = videoUri,
                audioUri = audioUrl?.takeIf { it.isNotEmpty() }?.let { raw ->
                    if (raw.startsWith("/")) Uri.fromFile(java.io.File(raw)) else raw.toUri()
                },
                headers = headers,
                userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
                resumePositionMs = resumePositionMs,
                playWhenReady = playWhenReady
            )
            current.prepare(request)
            pendingGpuSurface?.let { current.attachSurface(it) }
        } else {
            (current as? com.example.piliai.playback.backend.Media3Backend)
                ?.prepareMediaSource(merged, playWhenReady)
            if (resumePositionMs > 0L) current?.seekTo(resumePositionMs)
        }
        _state.value = _state.value.copy(
            isPlaying = playWhenReady, isBuffering = true,
            error = null, duration = 0L
        )
    }

    /**
     * 订阅源专用播放方法（已构建好 MediaSource）。
     * 只支持 Media3Backend，GPU-next 不支持此路径。
     */
    private fun startPlaybackWithMediaSource(
        mediaSource: androidx.media3.exoplayer.source.MediaSource,
        autoPlay: Boolean = true
    ) {
        ensureBackend()
        val media3 = backend as? com.example.piliai.playback.backend.Media3Backend
            ?: run {
                setError("订阅源播放仅支持 Media3 后端")
                return
            }
        media3.prepareMediaSource(mediaSource, autoPlay)
        _state.value = _state.value.copy(
            isPlaying = autoPlay, isBuffering = true,
            error = null, duration = 0L
        )
    }

    /** 审核216：pauseOnMinimize——Screen 的 ON_PAUSE/ON_RESUME 回调 */
    fun onAppBackground() {
        viewModelScope.launch {
            val pause = runCatching {
                com.example.piliai.utils.StorageManager.getInstance(appContext)
                    .pauseOnMinimizeFlow.first()
            }.getOrDefault(false)
            if (pause && backend?.isPlaying() == true) {
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
        }
    }
    
    fun pause() {
        viewModelScope.launch {
            backend?.pause()
            _state.value = _state.value.copy(isPlaying = false)
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
        if (error != null) _state.value = _state.value.copy(error = error)
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
        // 批次audit25：屏蔽规则批量过滤优先走 Rust（万条×多规则 CPU 密集下沉），失败回退 Kotlin 逐条
        val filtered = runCatching {
            val lib = com.example.piliai.danmaku.DanmakuNativeLib()
            val entries = events.map { mapOf("content" to it.content, "uid" to it.uid) }
            val rules = mapOf(
                "keywords" to com.example.piliai.ui.settings.DanmakuBlockViewModel.cachedKeywords,
                "regexes" to com.example.piliai.ui.settings.DanmakuBlockViewModel.cachedRegexStrings,
                "uids" to com.example.piliai.ui.settings.DanmakuBlockViewModel.cachedUids.toList()
            )
            val out = lib.filterBlock(GSON.toJson(entries), GSON.toJson(rules)) ?: return@runCatching null
            val obj = org.json.JSONObject(out)
            val kept = obj.getJSONArray("kept_indices")
            (0 until kept.length()).map { i -> events[kept.getInt(i)] }
        }.getOrNull()
            ?: events.filterNot { com.example.piliai.ui.settings.DanmakuBlockViewModel.shouldBlock(it.content, it.uid) }
        if (replace) {
            _danmakuQueue.clear()
        }
        _danmakuQueue.addAll(filtered)
        // 总数跟队列走，不能在清空后又按入参累加（DanDan 补充源会覆盖主弹幕）
        _state.value = _state.value.copy(dmTotalCount = _danmakuQueue.size)
        // 批次L42：计算弹幕密度热力曲线（下沉 Rust，失败保留旧曲线）
        computeHeatCurve(_danmakuQueue.toList())
    }

    /** 批次L42：调用 Rust 计算高能进度条曲线 */
    private fun computeHeatCurve(events: List<DanmakuEvent>) {
        if (events.isEmpty()) { _heatCurve.value = emptyList(); return }
        runCatching {
            val lib = com.example.piliai.danmaku.DanmakuNativeLib()
            val pts = events.map { mapOf("t" to it.timestamp.toDouble(), "w" to 1.0) }
            // 审核36：弹幕加载早于播放器就绪时 duration 为 0 → 用弹幕最大时间兜底（Rust 侧已处理）
            val dur = duration().toDouble()
            val out = lib.heatMap(GSON.toJson(pts), dur, 120) ?: return@runCatching
            val arr = org.json.JSONObject(out).getJSONArray("buckets")
            _heatCurve.value = (0 until arr.length()).map { arr.getDouble(it).toFloat() }
        }
    }
    
    fun getDanmakuAtTime(currentTimeMs: Long): List<DanmakuEvent> {
        return _danmakuQueue.filter { event ->
            event.timestamp <= currentTimeMs && 
            event.timestamp > currentTimeMs - 3000L
        }
    }
    
    // 审核轮210：超分辨率真接入（Media3 1.3.1 已有 setVideoEffects，javap 验证；
    // ==================== PlaybackEventListener 实现 ====================

    /**
     * 审核242：解码器过载提示（bv ExoMediaPlayer.kt:71-90 的丢帧上报）。
     * 只提示一次，由用户决定是否降档；不自动改画质（会打断播放）。
     */
    override fun onDecoderOverloaded() {
        if (decoderOverloadReported) return
        decoderOverloadReported = true
        val superResOn = com.example.piliai.utils.RendererPrefs.superResolutionMode != "off"
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
        setError(message)
    }
    
    override fun onVideoSizeChanged(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        val effects = com.example.piliai.piliplus.Media3SuperResolutionApplier
            .resolve(width, height).orEmpty()
        // 审核轮214：HDR 内容检测（Media3 特定）
        (backend as? com.example.piliai.playback.backend.Media3Backend)?.let { media3 ->
            media3.nativePlayer()?.videoFormat?.let { fmt ->
                if (com.example.piliai.utils.RendererPrefs.hdrToneMappingEnabled &&
                    com.example.piliai.piliplus.isHdrContent(fmt.colorInfo)
                ) {
                    android.util.Log.i("PiliPlayer", "HDR 内容（tone-mapping 待 Media3 1.5+）")
                }
            }
            media3.setVideoEffects(effects)
        }
    }
    
    override fun onCleared() {
        ++playbackSpeedGeneration
        clockJob?.cancel()
        playbackSpeedRestoreJob?.cancel()
        completionJob?.cancel()
        loadJob?.cancel()
        subtitleListJob?.cancel()
        subtitleBodyJob?.cancel()
        try {
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

    private var aid: Long = 0L

    /** 当前视频标题/封面（供下载等外部操作，批次I 接线） */
    var videoTitle: String = ""
        private set
    var videoCover: String = ""
        private set
    var videoOwner: String = ""
    // 批次L18：简介/标签/发布时间
    var videoDesc: String = ""
    var videoTags: List<String> = emptyList()
    var videoPubdate: Long = 0L
        private set
    // 批次L20：收藏夹选择
    var favFolders: List<Triple<Long, String, Int>> = emptyList()
        private set
    // 批次L22：AI 总结
    var aiSummary: String = ""
        private set
    var aiOutline: List<Pair<Long, String>> = emptyList()  // (timestamp, title)
        private set

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
        if (bv.isEmpty() || bv.startsWith("ep")) return
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
    private fun loadEngagement(aid: Long, bvid: String) {
        viewModelScope.launch {
            if (!AccountSession.isLogin) return@launch
            // 1) 交互状态
            BiliApiClient().getVideoRelation(aid).onSuccess { (liked, coined, fav) ->
                _state.value = _state.value.copy(
                    isLiked = liked,
                    coinCount = if (coined) 1 else 0,
                    isFavorited = fav
                )
            }
            // 2) 默认收藏夹（第一个）
            runCatching {
                defaultFavFolderId = BiliApiClient().getMyFavFolders().getOrNull()
                    ?.firstOrNull()?.id ?: 0L
            }
            // 3) 真实计数（点赞/投币/收藏数）
            runCatching {
                repo.getVideoDetail(bvid).getOrNull()?.data?.stat?.let { s ->
                    _state.value = _state.value.copy(
                        likeCount = s.like, coinCountTotal = s.coin, favCount = s.favorite
                    )
                }
            }
        }
    }

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
                        _danmakuQueue.add(danmaku)
                        _danmakuQueue.sortBy { it.timestamp }
                        setError("弹幕发送成功")
                    } else setError("弹幕发送失败（可能需要登录或被风控）")
                }
                .onFailure { setError("弹幕发送失败: ${it.message}") }
        }
    }

    /** 加入稍后再看（需登录） */
    fun addToWatchLater() {
        viewModelScope.launch {
            if (!AccountSession.isLogin) { setError("请先登录"); return@launch }
            ensureAid()
            if (aid == 0L) { setError("无法获取视频 aid"); return@launch }
            BiliApiClient().addToView(aid)
                .onSuccess { ok -> setError(if (ok) "已加入稍后再看" else "添加失败") }
                .onFailure { setError("添加失败: ${it.message}") }
        }
    }

    /** 点赞（需登录） */
    fun toggleLike() {        viewModelScope.launch {
            if (!AccountSession.isLogin) {
                setError("请先登录后再点赞")
                return@launch
            }
            ensureAid()
            if (aid == 0L) { setError("无法获取视频 aid"); return@launch }
            val liked = _state.value.isLiked
            repo.likeVideo(aid, if (liked) 2 else 1)
                .onSuccess { ok ->
                    if (ok) _state.value = _state.value.copy(
                        isLiked = !liked,
                        likeCount = _state.value.likeCount + if (liked) -1 else 1
                    )
                    else setError("点赞失败（接口返回非 0）")
                }
                .onFailure { setError("点赞失败: ${it.message}") }
        }
    }

    /** 投币（需登录，可指定枚数 1/2） */
    fun coinOnce(multiply: Int = 1) {
        viewModelScope.launch {
            if (!AccountSession.isLogin) {
                setError("请先登录后再投币")
                return@launch
            }
            ensureAid()
            if (aid == 0L) { setError("无法获取视频 aid"); return@launch }
            repo.coinVideo(aid, multiply)
                .onSuccess { ok ->
                    if (ok) _state.value = _state.value.copy(
                        coinCount = _state.value.coinCount + multiply,
                        coinCountTotal = _state.value.coinCountTotal + multiply
                    )
                    else setError("投币失败（余额不足或已投满）")
                }
                .onFailure { setError("投币失败: ${it.message}") }
        }
    }

    /** 收藏（需登录，使用用户默认收藏夹） */
    /** 批次L20：加载收藏夹列表供选择 */
    fun loadFavFolders() {
        viewModelScope.launch {
            runCatching {
                BiliApiClient().getMyFavFolders().getOrNull().orEmpty()
            }.onSuccess { folders ->
                favFolders = folders.mapNotNull { f ->
                    f.id.takeIf { it > 0 }?.let { Triple(it, f.title, f.media_count) }
                }
            }
        }
    }

    /** 批次L20：收藏到指定收藏夹（含默认夹取消收藏） */
    fun favoriteTo(mediaId: Long) {
        viewModelScope.launch {
            if (!AccountSession.isLogin) { setError("请先登录后再收藏"); return@launch }
            ensureAid()
            if (aid == 0L) { setError("无法获取视频 aid"); return@launch }
            val fav = _state.value.isFavorited
            repo.favoriteVideo(aid, mediaId, if (fav) 2 else 1)
                .onSuccess { ok ->
                    if (ok) _state.value = _state.value.copy(
                        isFavorited = !fav,
                        favCount = _state.value.favCount + if (fav) -1 else 1
                    )
                    else setError("收藏操作失败")
                }
                .onFailure { setError("收藏失败: ${it.message}") }
        }
    }

    fun toggleFavorite() {
        viewModelScope.launch {
            if (!AccountSession.isLogin) {
                setError("请先登录后再收藏")
                return@launch
            }
            ensureAid()
            if (aid == 0L) { setError("无法获取视频 aid"); return@launch }
            val fav = _state.value.isFavorited
            val mediaId = defaultFavFolderId
            if (mediaId == 0L && !fav) { setError("未获取到默认收藏夹"); return@launch }
            repo.favoriteVideo(aid, mediaId, if (fav) 2 else 1)
                .onSuccess { ok ->
                    if (ok) _state.value = _state.value.copy(
                        isFavorited = !fav,
                        favCount = _state.value.favCount + if (fav) -1 else 1
                    )
                    else setError("收藏失败（接口返回非 0）")
                }
                .onFailure { setError("收藏失败: ${it.message}") }
        }
    }

    /** 上报历史进度（播放中每 15 秒调一次） */
    fun reportProgress() {
        viewModelScope.launch {
            if (!AccountSession.isLogin || aid == 0L || effectiveCid == 0L) return@launch
            repo.reportHistory(aid, effectiveCid, _state.value.currentTime / 1000)
        }
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

    private fun bestAudio(): String? = cachedAudioUrl

    private var cachedAudioUrl: String? = null

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
        _state.value = _state.value.copy(related = _state.value.related, error = null)
        completionJob?.cancel()
        loadJob?.cancel()
        resetSubtitleState()
        val generation = ++loadGeneration
        loadJob = viewModelScope.launch {
            currentBvid = item.bvid
            resolveAndPlay(item.bvid, item.cid, generation)
            loadEngagement(aid, item.bvid)
            loadRelated(item.bvid)
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

    /** 顶部弹幕开关（顶部弹幕可能被遮挡，用户可关闭） */
    var cachedDmShowTop: Boolean = true

    /** 底部弹幕开关 */
    var cachedDmShowBottom: Boolean = true

    fun persistDmShowTop(enabled: Boolean) {
        cachedDmShowTop = enabled
        viewModelScope.launch {
            runCatching {
                com.example.piliai.utils.StorageManager.getInstance(appContext).setDanmakuShowTop(enabled)
            }
        }
    }

    fun persistDmShowBottom(enabled: Boolean) {
        cachedDmShowBottom = enabled
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
                    partIndex + 1 in pages.indices -> playNextPart()
                    else -> _state.value.related.firstOrNull()?.let(::playRelated)
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

    /** 一键三连（长按点赞）：点赞 + 投币 1 枚 + 收藏，需登录。 */
    fun likeCoinFav() {
        if (!AccountSession.isLogin) {
            setError("请先登录后再一键三连")
            return
        }
        // 未点赞时先点赞，避免重复点赞导致取消。
        if (!_state.value.isLiked) toggleLike()
        coinOnce(1)
        toggleFavorite()
        showToast("已一键三连")
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
