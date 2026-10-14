package com.example.piliai.ui.pages.bangumi

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.piliai.data.model.PgcIndexItem
import com.example.piliai.data.remote.BiliApiClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 批次L2：pgc 首页分类——season_type 切换 + 分页加载 + 排序
 */
class PgcIndexViewModel : ViewModel() {

    private val api = BiliApiClient()

    data class PgcIndexState(
        val seasonType: Int = 1,               // 1番剧 2电影 3纪录片 4国创 5电视剧 7综艺
        val order: String = "update",          // update/score/double/play
        val items: List<PgcIndexItem> = emptyList(),
        val page: Int = 1,
        val hasNext: Boolean = true,
        val isLoading: Boolean = true,
        val isLoadingMore: Boolean = false,
        val error: String? = null
    )

    private val _state = MutableStateFlow(PgcIndexState())
    val state: StateFlow<PgcIndexState> = _state.asStateFlow()

    init { refresh() }

    fun setSeasonType(t: Int) {
        if (t == _state.value.seasonType) return
        _state.value = _state.value.copy(seasonType = t)
        refresh()
    }

    fun setOrder(o: String) {
        if (o == _state.value.order) return
        _state.value = _state.value.copy(order = o)
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, error = null)
            api.getPgcIndex(_state.value.seasonType, 1, _state.value.order).onSuccess { resp ->
                val list = resp.data?.list.orEmpty()
                _state.value = _state.value.copy(
                    isLoading = false,
                    items = list,
                    page = 1,
                    hasNext = (resp.data?.hasNext ?: 0) == 1
                )
            }.onFailure { e ->
                _state.value = _state.value.copy(
                    isLoading = false,
                    // 审核292：过错误码翻译
                    error = com.example.piliai.data.remote.BiliErrorTranslator
                        .translate(e.message) ?: e.message ?: "索引加载失败"
                )
            }
        }
    }

    fun loadMore() {
        val s = _state.value
        if (!s.hasNext || s.isLoading || s.isLoadingMore) return
        viewModelScope.launch {
            _state.value = s.copy(isLoadingMore = true)
            api.getPgcIndex(s.seasonType, s.page + 1, s.order).onSuccess { resp ->
                val more = resp.data?.list.orEmpty()
                _state.value = _state.value.copy(
                    isLoadingMore = false,
                    items = _state.value.items + more.filter { it.seasonId > 0 },
                    page = s.page + 1,
                    hasNext = (resp.data?.hasNext ?: 0) == 1
                )
            }.onFailure {
                _state.value = _state.value.copy(isLoadingMore = false, hasNext = false)
            }
        }
    }

    fun consumeError() { _state.value = _state.value.copy(error = null) }
}
