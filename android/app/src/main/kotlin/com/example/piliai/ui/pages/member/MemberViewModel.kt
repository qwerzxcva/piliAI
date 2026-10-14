package com.example.piliai.ui.pages.member

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.piliai.data.model.RelationStat
import com.example.piliai.data.model.SpaceInfo
import com.example.piliai.data.model.SpaceVideoItem
import com.example.piliai.data.remote.AccountSession
import com.example.piliai.data.remote.BiliApiClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * UP 主空间 ViewModel（批次C）
 * 空间信息 + 粉丝/关注数 + 投稿列表（分页） + 关注切换
 */
class MemberViewModel(private val mid: Long) : ViewModel() {

    private val api = BiliApiClient()

    private val _state = MutableStateFlow(MemberState(mid = mid))
    val state: StateFlow<MemberState> = _state.asStateFlow()

    data class MemberState(
        val mid: Long = 0L,
        val info: SpaceInfo? = null,
        val stat: RelationStat? = null,
        // 批次L38：代表作
        val masterpieces: List<com.example.piliai.data.model.MasterpieceArc> = emptyList(),
        // 批次L43：空间公告
        val notice: String = "",
        // 批次L45：UP主课程
        val cheeses: List<com.example.piliai.data.model.CheeseItem> = emptyList(),
        val videos: List<SpaceVideoItem> = emptyList(),
        val page: Int = 1,
        val hasMore: Boolean = false,
        val order: String = "pubdate",   // pubdate / click
        val isFollowing: Boolean = false,
        val isLoading: Boolean = false,
        val isLoadingMore: Boolean = false,
        val error: String? = null,
        // 批次L24：专栏
        val articles: List<com.example.piliai.data.model.SpaceArticleItem> = emptyList(),
        val articlePage: Int = 1,
        val articleHasMore: Boolean = false,
        val articlesLoading: Boolean = false,
        // 批次L27：合集/系列
        val seasons: List<com.example.piliai.data.model.SeasonSeriesEntry> = emptyList(),
        val seasonsLoading: Boolean = false,
        // 当前打开合集的视频（展开显示）
        val seasonVideos: List<com.example.piliai.data.model.SeasonArchiveItem> = emptyList(),
        val seasonVideosTotal: Int = 0,
        val openSeasonMeta: com.example.piliai.data.model.SeasonSeriesMeta? = null,
        val seasonVideoPage: Int = 1,
        val seasonVideosLoading: Boolean = false
    )

    init {
        loadInfo()
        loadVideos(1)
        loadMasterpieces()
        loadNotice()
        loadCheese()
    }

    /** 批次L45：UP主课程（匿名，失败静默） */
    fun loadCheese() {
        viewModelScope.launch {
            val list = api.getMemberCheese(mid).getOrNull()?.data?.items.orEmpty()
            if (list.isNotEmpty()) _state.value = _state.value.copy(cheeses = list)
        }
    }

    /** 批次L43：空间公告（匿名，失败静默） */
    fun loadNotice() {
        viewModelScope.launch {
            val n = api.getSpaceNotice(mid).getOrNull().orEmpty()
            if (n.isNotBlank()) _state.value = _state.value.copy(notice = n)
        }
    }

    fun loadInfo() {
        viewModelScope.launch {
            // 审核292：原来 getSpaceInfo 失败时没有 onFailure 分支，而它内部把
            // 风控失败转成了 error(...) 抛出——于是用户看到的是默认空态，
            // 没有任何提示（空间页头部白茫茫一片）。现在补上失败反馈。
            api.getSpaceInfo(mid).onSuccess { resp ->
                if (resp.code == 0) {
                    _state.value = _state.value.copy(info = resp.data)
                } else {
                    _state.value = _state.value.copy(
                        error = com.example.piliai.data.remote.BiliErrorTranslator
                            .translate(resp.message) ?: resp.message.ifEmpty { "空间信息加载失败" }
                    )
                }
            }.onFailure { e ->
                _state.value = _state.value.copy(
                    error = com.example.piliai.data.remote.BiliErrorTranslator
                        .translate(e.message) ?: e.message ?: "空间信息加载失败（匿名访问受限）"
                )
            }
            api.getRelationStat(mid).onSuccess { resp ->
                if (resp.code == 0) {
                    _state.value = _state.value.copy(stat = resp.data)
                }
            }
        }
    }

    fun loadVideos(page: Int) {
        if (page == 1) {
            _state.value = _state.value.copy(isLoading = true, error = null)
        } else {
            _state.value = _state.value.copy(isLoadingMore = true)
        }
        viewModelScope.launch {
            api.getSpaceArchives(mid, page, _state.value.order).onSuccess { resp ->
                if (resp.code == 0) {
                    val list = resp.data?.list?.vlist.orEmpty()
                    val total = resp.data?.page?.count ?: 0L
                    val merged = if (page == 1) list else _state.value.videos + list
                    _state.value = _state.value.copy(
                        isLoading = false, isLoadingMore = false,
                        videos = merged, page = page,
                        hasMore = merged.size < total
                    )
                } else {
                    _state.value = _state.value.copy(
                        isLoading = false, isLoadingMore = false,
                        // 审核292：过一遍错误码翻译，否则用户看到裸的 "-352"
                        error = com.example.piliai.data.remote.BiliErrorTranslator
                            .translate(resp.message) ?: resp.message.ifEmpty { "投稿加载失败" }
                    )
                }
            }.onFailure { e ->
                _state.value = _state.value.copy(
                    isLoading = false, isLoadingMore = false,
                    error = com.example.piliai.data.remote.BiliErrorTranslator
                        .translate(e.message) ?: e.message ?: "投稿加载失败"
                )
            }
        }
    }

    fun loadMore() {
        val s = _state.value
        if (s.isLoading || s.isLoadingMore || !s.hasMore) return
        loadVideos(s.page + 1)
    }

    /** 批次L38：代表作（匿名可用，失败静默不打扰主流程） */
    fun loadMasterpieces() {
        if (_state.value.masterpieces.isNotEmpty()) return
        viewModelScope.launch {
            val list = api.getMasterpiece(mid).getOrNull().orEmpty()
            _state.value = _state.value.copy(masterpieces = list)
        }
    }

    /** 批次L24：加载专栏列表（分页） */
    fun loadArticles(page: Int = 1) {
        if (_state.value.articlesLoading) return
        viewModelScope.launch {
            _state.value = _state.value.copy(articlesLoading = true)
            api.getSpaceArticles(mid, page)
                .onSuccess { resp ->
                    if (resp.code == 0) {
                        val list = resp.data?.articles.orEmpty()
                        val merged = if (page == 1) list else _state.value.articles + list
                        _state.value = _state.value.copy(
                            articles = merged, articlePage = page,
                            articleHasMore = merged.size < (resp.data?.count ?: 0),
                            articlesLoading = false
                        )
                    } else _state.value = _state.value.copy(
                        articlesLoading = false,
                        // 审核292：原来失败完全静默，只停掉 loading——用户看到
                        // 空列表分不清「没有专栏」还是「加载失败」
                        error = com.example.piliai.data.remote.BiliErrorTranslator
                            .translate(resp.message) ?: resp.message
                    )
                }
                .onFailure { e ->
                    _state.value = _state.value.copy(
                        articlesLoading = false,
                        error = com.example.piliai.data.remote.BiliErrorTranslator
                            .translate(e.message) ?: e.message ?: "专栏加载失败"
                    )
                }
        }
    }

    /** 批次L27：加载合集/系列列表 */
    fun loadSeasons() {
        if (_state.value.seasonsLoading) return
        viewModelScope.launch {
            _state.value = _state.value.copy(seasonsLoading = true)
            api.getSeasonsSeries(mid)
                .onSuccess { resp ->
                    if (resp.code == 0) {
                        val all = (resp.data?.itemsLists?.seasonsList.orEmpty() +
                            resp.data?.itemsLists?.seriesList.orEmpty())
                        _state.value = _state.value.copy(seasons = all, seasonsLoading = false)
                    } else _state.value = _state.value.copy(
                        seasonsLoading = false,
                        error = com.example.piliai.data.remote.BiliErrorTranslator
                            .translate(resp.message) ?: resp.message
                    )
                }
                .onFailure { e ->
                    _state.value = _state.value.copy(
                        seasonsLoading = false,
                        error = com.example.piliai.data.remote.BiliErrorTranslator
                            .translate(e.message) ?: e.message ?: "合集列表加载失败"
                    )
                }
        }
    }

    /** 批次L27：打开合集（拉取视频列表第一页） */
    fun openSeason(entry: com.example.piliai.data.model.SeasonSeriesEntry) {
        val meta = entry.meta ?: return
        if (meta.seasonId == 0L && meta.seriesId == 0L) return
        viewModelScope.launch {
            _state.value = _state.value.copy(seasonVideosLoading = true, openSeasonMeta = meta,
                seasonVideos = emptyList(), seasonVideoPage = 1)
            if (meta.seasonId > 0L) {
                api.getSeasonArchives(mid, meta.seasonId)
                    .onSuccess { resp ->
                        if (resp.code == 0) _state.value = _state.value.copy(
                            seasonVideos = resp.data?.archives.orEmpty(),
                            seasonVideosTotal = resp.data?.total ?: 0,
                            seasonVideosLoading = false
                        ) else _state.value = _state.value.copy(seasonVideosLoading = false)
                    }
                    .onFailure { _state.value = _state.value.copy(seasonVideosLoading = false) }
            } else {
                // series 走 seasons_archives_list 的 series_id 参数（同接口 series_id 别名）
                api.getSeriesArchives(mid, meta.seriesId)
                    .onSuccess { resp ->
                        if (resp.code == 0) _state.value = _state.value.copy(
                            seasonVideos = resp.data?.archives.orEmpty(),
                            seasonVideosTotal = resp.data?.total ?: 0,
                            seasonVideosLoading = false
                        ) else _state.value = _state.value.copy(seasonVideosLoading = false)
                    }
                    .onFailure { _state.value = _state.value.copy(seasonVideosLoading = false) }
            }
        }
    }

    fun closeSeason() {
        _state.value = _state.value.copy(openSeasonMeta = null, seasonVideos = emptyList())
    }

    fun setOrder(order: String) {
        if (_state.value.order == order) return
        _state.value = _state.value.copy(order = order, videos = emptyList())
        loadVideos(1)
    }

    /** 批次L44：空间内搜索投稿（复用 wbi arc/search keyword） */
    fun searchArchives(keyword: String) {
        // 审核42：空关键词与重入保护
        val kw = keyword.trim()
        if (kw.isEmpty() || isLoadingArchives) return
        isLoadingArchives = true
        _state.value = _state.value.copy(videos = emptyList(), isLoading = true, error = null)
        viewModelScope.launch {
            api.getSpaceArchives(mid, 1, _state.value.order, keyword = kw).onSuccess { resp ->
                isLoadingArchives = false
                if (resp.code == 0) {
                    val list = resp.data?.list?.vlist.orEmpty()
                    _state.value = _state.value.copy(
                        isLoading = false, videos = list, page = 1, hasMore = false
                    )
                } else {
                    _state.value = _state.value.copy(isLoading = false, error = resp.message.ifEmpty { "搜索失败" })
                }
            }.onFailure { e ->
                isLoadingArchives = false
                _state.value = _state.value.copy(
                    isLoading = false,
                    // 审核292：过错误码翻译
                    error = com.example.piliai.data.remote.BiliErrorTranslator
                        .translate(e.message) ?: e.message ?: "加载失败"
                )
            }
        }
    }

    @Volatile private var isLoadingArchives = false

    fun toggleFollow() {
        if (!AccountSession.isLogin) {
            _state.value = _state.value.copy(error = "请先登录")
            return
        }
        val target = !_state.value.isFollowing
        viewModelScope.launch {
            api.modifyFollow(mid, target).onSuccess { ok ->
                if (ok) {
                    _state.value = _state.value.copy(isFollowing = target)
                    loadInfo()  // 刷新粉丝数
                }
            }.onFailure { e ->
                _state.value = _state.value.copy(
                    // 审核292：过错误码翻译
                    error = com.example.piliai.data.remote.BiliErrorTranslator
                        .translate(e.message) ?: e.message ?: "操作失败"
                )
            }
        }
    }

    fun consumeError() {
        _state.value = _state.value.copy(error = null)
    }
}
