package com.example.piliai.ui.pages.bangumi

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.piliai.data.model.PgcEpisode
import com.example.piliai.data.model.PgcSeason
import com.example.piliai.data.remote.AccountSession
import com.example.piliai.data.remote.BiliApiClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 番剧详情 ViewModel（批次D）——season 详情 + 选集 + 追番
 */
class BangumiViewModel(private val seasonId: Long, private val epId: Long) : ViewModel() {

    private val api = BiliApiClient()

    private val _state = MutableStateFlow(BangumiState())
    val state: StateFlow<BangumiState> = _state.asStateFlow()

    data class BangumiState(
        val season: PgcSeason? = null,
        val currentEp: PgcEpisode? = null,
        val isFollowed: Boolean = false,
        val isLoading: Boolean = true,
        val error: String? = null
    )

    init { load() }

    fun load() {
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, error = null)
            api.getPgcSeason(seasonId = seasonId, epId = epId).onSuccess { resp ->
                if (resp.code == 0 && resp.result != null) {
                    val season = resp.result
                    val cur = season.episodes.firstOrNull { it.id == epId }
                        ?: season.episodes.firstOrNull()
                    _state.value = _state.value.copy(
                        isLoading = false, season = season, currentEp = cur
                    )
                } else {
                    _state.value = _state.value.copy(
                        isLoading = false, error = resp.message.ifEmpty { "番剧加载失败" }
                    )
                }
            }.onFailure { e ->
                _state.value = _state.value.copy(
                    isLoading = false,
                    // 审核292：过错误码翻译
                    error = com.example.piliai.data.remote.BiliErrorTranslator
                        .translate(e.message) ?: e.message ?: "番剧信息加载失败"
                )
            }
        }
    }

    fun selectEpisode(ep: PgcEpisode) {
        _state.value = _state.value.copy(currentEp = ep)
    }

    /** 播放当前集 → 跳播放器（bvid 可能空，用 ep_id 模式） */
    fun currentPlayTarget(): Triple<Long, Long, String>? {
        val ep = _state.value.currentEp ?: return null
        return Triple(ep.id, ep.cid, ep.bvid)
    }

    fun toggleFollow() {
        if (!AccountSession.isLogin) {
            _state.value = _state.value.copy(error = "请先登录")
            return
        }
        val target = !_state.value.isFollowed
        viewModelScope.launch {
            api.followBangumi(seasonId = seasonId, follow = target).onSuccess { ok ->
                if (ok) _state.value = _state.value.copy(isFollowed = target)
                else _state.value = _state.value.copy(error = "操作失败")
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
