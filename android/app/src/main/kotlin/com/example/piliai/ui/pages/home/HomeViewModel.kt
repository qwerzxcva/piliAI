package com.example.piliai.ui.pages.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.piliai.data.model.VideoItem
import com.example.piliai.data.repository.HomeRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    /** 审核237：刷新/加载中（下拉刷新指示器用） */
    val isRefreshing: StateFlow<Boolean> = repo.isLoading

    private var loadingMore = false

    fun refresh() {
        viewModelScope.launch {
            _state.value = HomeUiState.Loading
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
                    }
                }
                .onFailure { /* 加载更多失败静默保留当前列表 */ }
            loadingMore = false
        }
    }

    private fun _hasMoreGuard(): Boolean = repo.hasMore.value

    init {
        refresh()
    }
}
