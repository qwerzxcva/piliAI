package com.example.piliai.ui.pages.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.piliai.data.model.VideoItem
import com.example.piliai.data.repository.HomeRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

sealed interface HomeUiState {
    data object Loading : HomeUiState
    data class Success(val items: List<VideoItem>) : HomeUiState
    data class Error(val message: String) : HomeUiState
}

class HomeViewModel(
    private val repo: HomeRepository = HomeRepository(),
    // 审核233：推荐过滤配置（PiliPlus recommend_filter 移植），storage 为空时不过滤
    storage: com.example.piliai.utils.StorageManager? = null,
) : ViewModel() {

    private var filterConfig: com.example.piliai.utils.RecommendFilter.Config =
        com.example.piliai.utils.RecommendFilter.Config()

    init {
        if (storage != null) {
            // 审核279：首页数据源偏好。先同步读一次缓存值（下次 refresh 生效），
            // 再 collect 持续同步——否则首次进首页用默认 auto，
            // 用户设了「每周必看」也要等设置页再进才生效。
            viewModelScope.launch {
                repo.setPreferredSource(storage.homeFeedSourceFlow.first())
                storage.homeFeedSourceFlow.collect { pref ->
                    if (repo.preferredSource != pref) {
                        repo.setPreferredSource(pref)
                        refresh()
                    }
                }
            }
            viewModelScope.launch {
                kotlinx.coroutines.flow.combine(
                    storage.minDurationForRcmdFlow,
                    storage.minPlayForRcmdFlow,
                    storage.minLikeRatioForRecommendFlow,
                    storage.banWordForRecommendFlow,
                    storage.exemptFilterForFollowedFlow,
                ) { minDur, minPlay, minLike, banWords, exempt ->
                    com.example.piliai.utils.RecommendFilter.Config(
                        minDurationSec = minDur, minPlay = minPlay.toLong(),
                        minLikeRatioPercent = minLike, banWords = banWords,
                        exemptFilterForFollowed = exempt,
                    )
                }.collect { cfg ->
                    filterConfig = cfg
                    // 审核237：过滤条件变化时立即对当前列表重筛（此前只有下次加载才生效）
                    (_state.value as? HomeUiState.Success)?.let { s ->
                        _state.value = HomeUiState.Success(applyFilter(s.items))
                    }
                }
            }
        }
    }

    /** 审核233：按当前过滤配置筛选 */
    private fun applyFilter(items: List<com.example.piliai.data.model.VideoItem>) =
        com.example.piliai.utils.RecommendFilter.filter(items, filterConfig)
    private val _state = MutableStateFlow<HomeUiState>(HomeUiState.Loading)
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    // 批次L29：首页顶部大卡轮播
    private val _topRcmd = MutableStateFlow<List<com.example.piliai.data.model.TopRcmdItem>>(emptyList())
    val topRcmd: StateFlow<List<com.example.piliai.data.model.TopRcmdItem>> = _topRcmd.asStateFlow()

    /** 审核237：当前数据源（推荐/热门），UI 可据此提示 */
    val feedSource: StateFlow<HomeRepository.FeedSource> = repo.feedSource

    /** 审核237：是否还有更多 */
    val hasMore: StateFlow<Boolean> = repo.hasMore

    /** 审核280：当前生效数据源的偏好值（供首页 Section 标题展示） */
    val preferredSource: String
        get() = repo.preferredSource

    /**
     * 审核241（P0-2）：加载更多失败的一次性提示。
     * 此前失败完全静默：用户反复触底打请求，却看不到「加载失败/到底」。
     */
    private val _loadMoreError = MutableStateFlow<String?>(null)
    val loadMoreError: StateFlow<String?> = _loadMoreError.asStateFlow()

    fun consumeLoadMoreError() {
        _loadMoreError.value = null
    }

    private var loadingMore = false

    /**
     * 下拉刷新。
     *
     * 审核300（真 bug）：原来刷新时 isLoading 时序不可靠——PullToRefreshBox 的
     * onRefresh 是 suspend 的，而 refresh() 返回 Unit 且内部自己 launch，
     * 调用方拿不到完成时机；repo.isLoading 又可能在回调到达前就变 false，
     * 于是下拉后**没有视觉反馈**，用户以为「刷新没反应」。
     *
     * 现在加一个独立的 refreshing 信号：进入即置 true，无论成功失败都置回
     * false（finally），保证指示器一定转完一圈。
     */
    private val _refreshing = kotlinx.coroutines.flow.MutableStateFlow(false)
    val refreshing: kotlinx.coroutines.flow.StateFlow<Boolean> = _refreshing.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            _refreshing.value = true
            try {
                // 审核241（P0-3）：只在列表本来就空时才切 Loading。
                // 否则每次下拉刷新都整屏骨架卡（已看内容全消失、滚动位置丢失）。
                if (repo.videos.value.isEmpty()) _state.value = HomeUiState.Loading
                // 轮播并行拉取（失败静默不阻塞首页）
                launch {
                    repo.topRcmd()
                        .onSuccess { resp ->
                            if (resp.code == 0) _topRcmd.value =
                                resp.data?.item.orEmpty().filter { it.goto == "av" && it.bvid.isNotEmpty() }
                        }
                }
                repo.refresh()
                    .onSuccess { _state.value = HomeUiState.Success(applyFilter(it)) }
                    .onFailure {
                        // 审核237：刷新失败时保留旧列表，不清成空屏（此前直接 Error 态白屏）
                        val current = repo.videos.value
                        if (current.isNotEmpty()) {
                            _state.value = HomeUiState.Success(applyFilter(current))
                        } else {
                            _state.value = HomeUiState.Error(it.message ?: "网络请求失败")
                        }
                    }
            } finally {
                _refreshing.value = false
            }
        }
    }

    /**
     * 审核237：加载更多不再按列表长度反推页码（过滤会让页码回退造成重复加载），
     * 由仓库维护 fresh_idx；并用仓库的 hasMore 兜底防止无限翻页。
     */
    fun loadMore() {
        if (loadingMore) return
        if (!_hasMoreGuard()) return
        loadingMore = true
        viewModelScope.launch {
            repo.loadMore()
                .onSuccess { added ->
                    if (added.isNotEmpty()) {
                        _state.value = HomeUiState.Success(applyFilter(repo.videos.value))
                    } else if (!repo.hasMore.value) {
                        _loadMoreError.value = "已经到底啦"
                    }
                }
                .onFailure { e ->
                    // 审核241：失败不再静默。列表保持不变，只提示一次。
                    _loadMoreError.value = "加载失败：${e.message ?: "网络错误"}"
                }
            loadingMore = false
        }
    }

    private fun _hasMoreGuard(): Boolean = repo.hasMore.value

    init {
        refresh()
    }
}
