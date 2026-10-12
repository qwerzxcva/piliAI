package com.example.piliai.ui.subscribe

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.piliai.database.SubscribeItemEntity
import com.example.piliai.database.SubscribeSourceEntity
import com.example.piliai.data.repository.AnimekoScraper
import com.example.piliai.data.repository.SubscribeParser
import com.example.piliai.data.repository.SubscribeRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * 订阅页 ViewModel（Animeko「订阅」移植）
 *
 * 审核要点：
 * 1. 不持有 Activity/Context —— 数据库 DAO 由外部注入，避免配置变更泄漏。
 * 2. 所有协程走 viewModelScope，onCleared 自动取消，无 GlobalScope。
 * 3. 刷新逐源 try：单源失败不影响其它源，错误汇总到 errorMessage。
 * 4. 并发刷新用 joinAll 等待，isRefreshing 只在真正结束时置 false（避免闪烁）。
 */
class SubscribeViewModel(
    private val repository: SubscribeRepository
) : ViewModel() {

    companion object {
        /** 审核轮15：同时拉取的订阅源上限（防风控限流 + 内存峰值） */
        private const val MAX_CONCURRENT_SYNC = 4

        /** 审核240：追番筛选项。0=全部，1-5 与 Bangumi 收藏类型同编码，6=只看更新。
         *  注意：必须与 SubscribeTitleEntity 的 COLLECTION_* 分开编号，
         *  否则「搁置(4)」永远被「只看更新」分支吃掉（子代理调研发现的真实缺陷）。 */
        const val TITLE_FILTER_ALL = 0
        const val TITLE_FILTER_UNWATCHED = 6
    }

    data class UiState(
        val sources: List<SubscribeSourceEntity> = emptyList(),
        val items: List<SubscribeItemEntity> = emptyList(),
        val isRefreshing: Boolean = false,
        val errorMessage: String? = null,
        val infoMessage: String? = null,
        val isSearching: Boolean = false,
        val bangumiCandidates: List<com.example.piliai.data.remote.BangumiApi.Subject> = emptyList()
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** Animeko 数据源内搜索的结果（临时视图，不落库） */
    private val _searchResults = MutableStateFlow<List<SubscribeItemEntity>>(emptyList())
    val searchResults: StateFlow<List<SubscribeItemEntity>> = _searchResults.asStateFlow()

    private val _libraryQuery = MutableStateFlow("")
    val libraryQuery: StateFlow<String> = _libraryQuery.asStateFlow()

    // ==================== 追番（animeko「收藏」移植，审核240）====================

    /** 追番列表（本地 Room，随数据库自动刷新） */
    private val _titles = MutableStateFlow<List<com.example.piliai.database.SubscribeTitleEntity>>(emptyList())
    val titles: StateFlow<List<com.example.piliai.database.SubscribeTitleEntity>> = _titles.asStateFlow()

    /**
     * 追番筛选：0=全部 1=想看 2=在看 3=看过 4=只看更新。
     * 与 animeko `COLLECTION_TABS_SORTED` 对齐（animeko 收藏页分段按钮）。
     */
    private val _titleFilter = MutableStateFlow(TITLE_FILTER_ALL)
    val titleFilter: StateFlow<Int> = _titleFilter.asStateFlow()

    fun setTitleFilter(filter: Int) {
        _titleFilter.value = filter.coerceIn(TITLE_FILTER_ALL, TITLE_FILTER_UNWATCHED)
    }

    /** 当前筛选下的追番列表（UI 直接订阅） */
    val visibleTitles: StateFlow<List<com.example.piliai.database.SubscribeTitleEntity>> =
        _titleFilter.combine(_titles) { filter, list ->
            when (filter) {
                TITLE_FILTER_ALL -> list
                TITLE_FILTER_UNWATCHED -> list.filter { it.hasUnwatched }
                else -> list.filter { it.collectionType == filter }
            }
        }.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 未看集数总和（追番入口角标） */
    val unwatchedTotal: StateFlow<Int> = _titles.map { list -> list.sumOf { it.unwatchedCount } }
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5_000), 0)

    init {
        // 追番列表
        viewModelScope.launch {
            repository.observeTitles()
                .catch { e -> _state.value = _state.value.copy(errorMessage = e.message) }
                .collect { list -> _titles.value = list }
        }
    }

    /**
     * 追番 / 取消追番。
     *
     * @param sourceKey 源内稳定标识：web-selector 源用作品页 URL，RSS 源用条目 link
     */
    fun followTitle(
        sourceId: Long,
        sourceKey: String,
        name: String,
        cover: String = "",
        intro: String = "",
        latestEpisode: Int = 0,
        bangumiId: Long = 0L
    ) {
        viewModelScope.launch {
            repository.followTitle(sourceId, sourceKey, name, cover, intro, latestEpisode, bangumiId)
                .onSuccess {
                    _state.value = _state.value.copy(infoMessage = "已追番：$name", errorMessage = null)
                }
                .onFailure { e ->
                    _state.value = _state.value.copy(errorMessage = e.message ?: "追番失败")
                }
        }
    }

    fun unfollowTitle(id: Long) {
        viewModelScope.launch {
            repository.unfollowTitle(id)
                .onSuccess {
                    _state.value = _state.value.copy(infoMessage = "已取消追番")
                }
        }
    }

    /** 按 (sourceId, sourceKey) 取消追番（卡片上的实心爱心用） */
    fun unfollowByKey(sourceId: Long, sourceKey: String) {
        viewModelScope.launch {
            val id = repository.getTitleByKey(sourceId, sourceKey)?.id ?: return@launch
            repository.unfollowTitle(id)
                .onSuccess {
                    _state.value = _state.value.copy(infoMessage = "已取消追番")
                }
        }
    }

    fun setTitleCollectionType(id: Long, type: Int) {
        viewModelScope.launch { repository.setTitleCollectionType(id, type) }
    }

    fun setTitleWatched(id: Long, episode: Int) {
        viewModelScope.launch {
            repository.setTitleWatched(id, episode)
                .onSuccess {
                    _state.value = _state.value.copy(infoMessage = "已标记看到第 ${episode} 集")
                }
        }
    }

    /** 当前作品是否已追番（列表项图标用） */
    fun isFollowed(sourceId: Long, sourceKey: String): Boolean =
        _titles.value.any { it.sourceId == sourceId && it.sourceKey == sourceKey }

    private var browseJob: Job? = null
    private var browseGeneration = 0L
    private var bangumiLookupJob: Job? = null

    private var refreshJob: Job? = null
    /** 审核轮103：搜索防重入（快速连点搜索按钮会并发打出多个请求） */
    private var searchJob: Job? = null
    /** 审核240：Bangumi 收藏同步任务（防重入） */
    private var bangumiSyncJob: Job? = null

    init {
        // 源列表
        viewModelScope.launch {
            repository.observeSources()
                .catch { e -> _state.value = _state.value.copy(errorMessage = e.message) }
                .collect { list ->
                    _state.value = _state.value.copy(sources = list)
                }
        }
        // 条目列表
        viewModelScope.launch {
            repository.observeItems()
                .catch { e -> _state.value = _state.value.copy(errorMessage = e.message) }
                .collect { list ->
                    _state.value = _state.value.copy(items = list)
                }
        }
    }

    /** 添加订阅源链接 */
    fun addSource(url: String, name: String, type: Int) {
        viewModelScope.launch {
            repository.addSource(url, name, type)
                .onSuccess {
                    _state.value = _state.value.copy(
                        infoMessage = "已添加订阅源",
                        errorMessage = null
                    )
                    refreshAll()
                }
                .onFailure { e ->
                    _state.value = _state.value.copy(errorMessage = e.message ?: "添加失败")
                }
        }
    }

    /** 刷新全部启用的源 */
    fun refreshAll() {
        if (refreshJob?.isActive == true) return // 防重复触发
        refreshJob = viewModelScope.launch {
            _state.value = _state.value.copy(isRefreshing = true, errorMessage = null)
            val sources = _state.value.sources.filter { it.enabled }
            if (sources.isEmpty()) {
                _state.value = _state.value.copy(
                    isRefreshing = false,
                    infoMessage = "还没有订阅源，请先添加"
                )
                return@launch
            }
            val errors = mutableListOf<String>()
            // 审核轮25：用 try/finally 保证 isRefreshing 一定会被复位。
            // 否则协程被取消（ViewModel 清除 / 页面退出）时不会走到末尾的
            // copy(isRefreshing=false)，下拉刷新圈会永远转下去。
            try {
                // 审核轮15：限制并发（最多 4 个源同时拉）。
                // 源很多时全并发会瞬间打出大量请求：既容易触发对端风控/限流，
                // 也会同时持有多个响应体造成内存峰值。这里用 Semaphore 限流，
                // 仍保持并发（不至于串行太慢）。
                val semaphore = kotlinx.coroutines.sync.Semaphore(MAX_CONCURRENT_SYNC)
                val jobs = sources.map { s ->
                    launch {
                        semaphore.withPermit {
                            repository.syncSource(s).onFailure { e ->
                                synchronized(errors) {
                                    errors.add("${s.name}: ${e.message ?: "同步失败"}")
                                }
                            }
                        }
                    }
                }
                jobs.forEach { it.join() }   // 等全部结束再收尾，避免 isRefreshing 闪烁
                _state.value = _state.value.copy(
                    isRefreshing = false,
                    errorMessage = errors.takeIf { it.isNotEmpty() }?.joinToString("\n"),
                    infoMessage = if (errors.isEmpty()) "刷新完成" else null
                )
            } finally {
                // 无论成功 / 失败 / 取消，都复位刷新态
                if (_state.value.isRefreshing) {
                    _state.value = _state.value.copy(isRefreshing = false)
                }
            }
        }
    }

    /** 删除源及其条目 */
    fun removeSource(id: Long) {
        viewModelScope.launch {
            repository.removeSource(id)
                .onFailure { e ->
                    _state.value = _state.value.copy(errorMessage = e.message ?: "删除失败")
                }
        }
    }

    /** 启/停用源 */
    fun toggleSource(id: Long, enabled: Boolean) {
        viewModelScope.launch { repository.setEnabled(id, enabled) }
    }

    /** 磁力或种子来自订阅源，但当前没有缓存与做种控制，不能当网页去解析。 */
    fun reportBitTorrent(item: SubscribeItemEntity) {
        browseJob?.cancel()
        searchJob?.cancel()
        ++browseGeneration
        val context = com.example.piliai.AppContext.get()
        val source = _state.value.sources.firstOrNull {
            it.id == item.sourceId && it.enabled
        }
        _state.value = _state.value.copy(
            errorMessage = context.getString(
                if (source == null) com.example.piliai.R.string.subscribe_source_unavailable
                else com.example.piliai.R.string.subscribe_bittorrent_needs_cache
            ),
            isRefreshing = refreshJob?.isActive == true
        )
    }

    /**
     * 审核轮8：条目无可播放直链时的提示
     * （避免把网页 URL 丢进播放器造成「点了没反应」）
     */
    fun reportNotPlayable(item: SubscribeItemEntity) {
        browseJob?.cancel()
        searchJob?.cancel()
        val generation = ++browseGeneration
        val context = com.example.piliai.AppContext.get()
        val source = _state.value.sources.firstOrNull {
            it.id == item.sourceId && it.enabled
        }
        if (source == null) {
            _state.value = _state.value.copy(
                errorMessage = context.getString(com.example.piliai.R.string.subscribe_source_unavailable),
                isRefreshing = refreshJob?.isActive == true
            )
            return
        }
        browseJob = viewModelScope.launch {
            _state.value = _state.value.copy(isRefreshing = true, errorMessage = null)
            try {
                val names = repository.searchableWebSourceNames(source.url)
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                if (generation != browseGeneration) return@launch
                if (!_state.value.sources.any {
                        it.id == source.id && it.url == source.url && it.enabled
                    }) {
                    _state.value = _state.value.copy(
                        errorMessage = context.getString(com.example.piliai.R.string.subscribe_source_unavailable)
                    )
                    return@launch
                }
                val name = names.firstOrNull { it == item.sourceName }
                    ?: names.singleOrNull()
                if (name == null) {
                    _state.value = _state.value.copy(
                        errorMessage = context.getString(
                            if (names.isEmpty()) com.example.piliai.R.string.subscribe_no_matching_resolver
                            else com.example.piliai.R.string.subscribe_choose_resolver
                        )
                    )
                    return@launch
                }
                searchWebSource(source.url, name, item.title)
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (_: Exception) {
                if (generation == browseGeneration) {
                    _state.value = _state.value.copy(
                        errorMessage = context.getString(com.example.piliai.R.string.subscribe_resolver_load_failed)
                    )
                }
            } finally {
                if (generation == browseGeneration) {
                    _state.value = _state.value.copy(isRefreshing = refreshJob?.isActive == true)
                }
            }
        }
    }

    /**
     * Bangumi 登录（Animeko 移植）
     *
     * 真实调用 api.bgm.tv/v0/me 校验令牌；失败不写入凭据。
     */
    fun loginBangumi(token: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                com.example.piliai.data.remote.BangumiSession.loginWithToken(
                    com.example.piliai.AppContext.get(),
                    token
                )
            }.onSuccess { name ->
                _state.value = _state.value.copy(
                    infoMessage = "Bangumi 登录成功：$name",
                    errorMessage = null
                )
                // 审核240：登录后立刻拉一次收藏，让「我的追番」有内容
                syncBangumi()
            }.onFailure { e ->
                _state.value = _state.value.copy(
                    errorMessage = e.message ?: "Bangumi 登录失败"
                )
            }
        }
    }

    /** 审核240：拉取 Bangumi 收藏到本地追番表 */
    fun syncBangumi() {
        if (bangumiSyncJob?.isActive == true) return
        bangumiSyncJob = viewModelScope.launch {
            if (!com.example.piliai.data.remote.BangumiSession.isLogin) return@launch
            repository.syncBangumiCollections()
                .onSuccess { count ->
                    if (count > 0) {
                        _state.value = _state.value.copy(
                            infoMessage = "已同步 Bangumi 收藏 $count 部",
                            errorMessage = null
                        )
                    }
                }
                .onFailure { e ->
                    // 不同步不阻塞订阅页：只提示，不动本地数据
                    _state.value = _state.value.copy(
                        errorMessage = "Bangumi 收藏同步失败：${e.message ?: "网络错误"}"
                    )
                }
        }
    }

    /** Bangumi 退出登录（清除本地 token） */
    fun logoutBangumi() {
        com.example.piliai.data.remote.BangumiSession.logout(
            com.example.piliai.AppContext.get()
        )
        _state.value = _state.value.copy(infoMessage = "已退出 Bangumi 登录")
    }

    /**
     * 在指定 Animeko 数据源里按关键词搜索（仅 rss 型可用）
     *
     * 结果写入 _searchResults，由 UI 展示；不落库（搜索是临时视图）。
     */
    fun searchInSource(
        searchUrl: String,
        keyword: String,
        factoryId: String,
        sourceId: Long
    ) {
        val source = _state.value.sources.firstOrNull { it.id == sourceId && it.enabled }
        if (source == null) {
            _state.value = _state.value.copy(
                errorMessage = com.example.piliai.AppContext.get().getString(
                    com.example.piliai.R.string.subscribe_source_unavailable
                )
            )
            return
        }
        browseJob?.cancel()
        searchJob?.cancel()
        val generation = ++browseGeneration
        activeSourceUrl = ""
        activeSourceName = ""
        _episodes.value = emptyList()
        _currentSubject.value = null
        _searchResults.value = emptyList()
        _state.value = _state.value.copy(isSearching = true)
        searchJob = viewModelScope.launch {
            _state.value = _state.value.copy(
                isRefreshing = true, errorMessage = null, infoMessage = null
            )
            try {
                val result = SubscribeParser.searchAnimekoSource(searchUrl, keyword, factoryId)
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                if (generation != browseGeneration) return@launch
                require(_state.value.sources.any {
                    it.id == sourceId && it.url == source.url && it.enabled
                }) {
                    com.example.piliai.AppContext.get().getString(
                        com.example.piliai.R.string.subscribe_source_unavailable
                    )
                }
                val parsed = result.getOrThrow()
                _searchResults.value = parsed.items.map { item ->
                    SubscribeItemEntity(
                        sourceId = sourceId,
                        title = item.title,
                        cover = item.cover,
                        link = item.link,
                        desc = item.desc,
                        episode = item.episode,
                        sourceName = source.name
                    )
                }
                if (parsed.items.isEmpty()) {
                    _state.value = _state.value.copy(infoMessage = "没有搜到结果")
                }
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                if (generation == browseGeneration) {
                    _state.value = _state.value.copy(errorMessage = error.message ?: "搜索失败")
                }
            } finally {
                if (generation == browseGeneration) {
                    _state.value = _state.value.copy(isRefreshing = refreshJob?.isActive == true)
                }
            }
        }
    }

    /** 清空搜索结果（返回订阅列表视图） */
    fun clearSearch() {
        _state.value = _state.value.copy(isSearching = false)
        ++browseGeneration
        browseJob?.cancel()
        searchJob?.cancel()
        activeSourceUrl = ""
        activeSourceName = ""
        _episodes.value = emptyList()
        _currentSubject.value = null
        _searchResults.value = emptyList()
        _state.value = _state.value.copy(isRefreshing = refreshJob?.isActive == true)
    }

    // ==================== Animeko 网页刮削（第二步/第三步）====================

    /** 当前正在浏览的作品（用于返回时恢复） */
    private val _currentSubject = MutableStateFlow<String?>(null)
    val currentSubject: StateFlow<String?> = _currentSubject.asStateFlow()

    /** 当前剧集列表 */
    private val _episodes = MutableStateFlow<List<AnimekoScraper.Episode>>(emptyList())
    val episodes: StateFlow<List<AnimekoScraper.Episode>> = _episodes.asStateFlow()

    /** 当前正在使用的源（配置 URL + 源名），供后续两步使用 */
    private var activeSourceUrl: String = ""
    private var activeSourceName: String = ""

    /**
     * 网页刮削搜索（第一步）
     * @param sourceUrl 订阅配置文件 URL（all.json/css.json 等）
     * @param sourceName 源名（如"酱紫社(修复)"）
     */
    fun searchWebSource(sourceUrl: String, sourceName: String, keyword: String) {
        val source = _state.value.sources.firstOrNull { it.url == sourceUrl && it.enabled }
        if (source == null) {
            _state.value = _state.value.copy(
                errorMessage = com.example.piliai.AppContext.get().getString(
                    com.example.piliai.R.string.subscribe_source_unavailable
                )
            )
            return
        }
        browseJob?.cancel()
        searchJob?.cancel()
        val generation = ++browseGeneration
        activeSourceUrl = sourceUrl
        activeSourceName = sourceName
        _currentSubject.value = null
        _episodes.value = emptyList()
        _searchResults.value = emptyList()
        _state.value = _state.value.copy(isSearching = true)
        browseJob = viewModelScope.launch {
            _state.value = _state.value.copy(
                isRefreshing = true, errorMessage = null, infoMessage = null
            )
            try {
                val result = repository.searchAnimekoWeb(sourceUrl, sourceName, keyword)
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                if (generation != browseGeneration) return@launch
                require(_state.value.sources.any {
                    it.id == source.id && it.url == sourceUrl && it.enabled
                }) {
                    com.example.piliai.AppContext.get().getString(
                        com.example.piliai.R.string.subscribe_source_unavailable
                    )
                }
                _searchResults.value = result.getOrThrow().map {
                    SubscribeItemEntity(
                        sourceId = source.id,
                        title = it.name,
                        link = it.url,
                        sourceName = "subject"
                    )
                }
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                if (generation == browseGeneration) {
                    _state.value = _state.value.copy(errorMessage = error.message ?: "搜索失败")
                }
            } finally {
                if (generation == browseGeneration) {
                    _state.value = _state.value.copy(isRefreshing = refreshJob?.isActive == true)
                }
            }
        }
    }

    /** 第二步：打开作品 → 取剧集 */
    fun openSubject(subjectUrl: String, subjectName: String) {
        val sourceUrl = activeSourceUrl
        val sourceName = activeSourceName
        val source = _state.value.sources.firstOrNull {
            it.url == sourceUrl && it.enabled
        }
        if (sourceUrl.isBlank() || source == null) {
            _state.value = _state.value.copy(
                errorMessage = com.example.piliai.AppContext.get().getString(
                    com.example.piliai.R.string.subscribe_source_unavailable
                )
            )
            return
        }
        browseJob?.cancel()
        searchJob?.cancel()
        val generation = ++browseGeneration
        _currentSubject.value = subjectName
        _episodes.value = emptyList()
        browseJob = viewModelScope.launch {
            _state.value = _state.value.copy(isRefreshing = true, errorMessage = null)
            try {
                val result = repository.fetchEpisodes(sourceUrl, sourceName, subjectUrl)
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                if (generation != browseGeneration) return@launch
                require(_state.value.sources.any {
                    it.id == source.id && it.url == sourceUrl && it.enabled
                }) {
                    com.example.piliai.AppContext.get().getString(
                        com.example.piliai.R.string.subscribe_source_unavailable
                    )
                }
                _episodes.value = result.getOrThrow()
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                if (generation == browseGeneration) {
                    _state.value = _state.value.copy(
                        errorMessage = error.message ?: "获取剧集失败"
                    )
                }
            } finally {
                if (generation == browseGeneration) {
                    _state.value = _state.value.copy(
                        isRefreshing = refreshJob?.isActive == true
                    )
                }
            }
        }
    }

    /** 第三步：播放某一集 → 取直链后交给播放器 */
    fun playEpisode(
        episodeUrl: String,
        onReady: (com.example.piliai.playback.SourcePlaybackRequest) -> Unit
    ) {
        val sourceUrl = activeSourceUrl
        val sourceName = activeSourceName
        val source = _state.value.sources.firstOrNull {
            it.url == sourceUrl && it.enabled
        }
        if (sourceUrl.isBlank() || source == null) {
            _state.value = _state.value.copy(
                errorMessage = com.example.piliai.AppContext.get().getString(
                    com.example.piliai.R.string.subscribe_source_unavailable
                )
            )
            return
        }
        browseJob?.cancel()
        searchJob?.cancel()
        val generation = ++browseGeneration
        browseJob = viewModelScope.launch {
            _state.value = _state.value.copy(isRefreshing = true, errorMessage = null)
            try {
                val result = repository.resolvePlaybackRequest(
                    source.id, sourceName, episodeUrl
                )
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                if (generation != browseGeneration) return@launch
                val request = result.getOrThrow()
                require(_state.value.sources.any {
                    it.id == request.sourceId && it.url == sourceUrl && it.enabled
                }) {
                    com.example.piliai.AppContext.get().getString(
                        com.example.piliai.R.string.subscribe_source_unavailable
                    )
                }
                onReady(request)
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                if (generation == browseGeneration) {
                    _state.value = _state.value.copy(
                        errorMessage = error.message ?: "提取播放地址失败"
                    )
                }
            } finally {
                if (generation == browseGeneration) {
                    _state.value = _state.value.copy(
                        isRefreshing = refreshJob?.isActive == true
                    )
                }
            }
        }
    }

    /** 返回到作品列表（清剧集） */
    fun backToSubjects() {
        ++browseGeneration
        browseJob?.cancel()
        searchJob?.cancel()
        _episodes.value = emptyList()
        _currentSubject.value = null
        _state.value = _state.value.copy(isRefreshing = refreshJob?.isActive == true)
    }

    fun lookupBangumi(title: String, onFound: (Long) -> Unit) {
        bangumiLookupJob?.cancel()
        val query = title.trim()
        if (query.isBlank()) return
        bangumiLookupJob = viewModelScope.launch {
            val context = com.example.piliai.AppContext.get()
            try {
                val candidates = com.example.piliai.data.remote.BangumiApi.searchSubjects(query)
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                val exact = candidates.firstOrNull {
                    it.title.equals(query, ignoreCase = true) ||
                        it.name.equals(query, ignoreCase = true)
                }
                when {
                    exact != null -> onFound(exact.id)
                    candidates.isEmpty() -> _state.value = _state.value.copy(
                        bangumiCandidates = emptyList(),
                        infoMessage = context.getString(
                            com.example.piliai.R.string.bangumi_no_exact_match
                        )
                    )
                    else -> _state.value = _state.value.copy(
                        bangumiCandidates = candidates,
                        infoMessage = null
                    )
                }
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (_: Exception) {
                _state.value = _state.value.copy(
                    errorMessage = context.getString(
                        com.example.piliai.R.string.bangumi_load_failed
                    )
                )
            }
        }
    }

    fun dismissBangumiCandidates() {
        _state.value = _state.value.copy(bangumiCandidates = emptyList())
    }

    fun setLibraryQuery(query: String) {
        _libraryQuery.value = query
    }

    fun clearError() {
        _state.value = _state.value.copy(errorMessage = null)
    }

    fun clearInfo() {
        _state.value = _state.value.copy(infoMessage = null)
    }
}
