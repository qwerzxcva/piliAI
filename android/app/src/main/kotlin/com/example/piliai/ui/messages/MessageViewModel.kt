package com.example.piliai.ui.messages

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.piliai.data.model.MsgFeedItem
import com.example.piliai.data.remote.AccountSession
import com.example.piliai.data.remote.BiliApiClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 消息中心 ViewModel（批次H）——未读数 + 回复/@/赞 消息流
 */
class MessageViewModel : ViewModel() {

    private val api = BiliApiClient()

    private val _state = MutableStateFlow(MessageState())
    val state: StateFlow<MessageState> = _state.asStateFlow()

    data class MessageState(
        val unreadTotal: Long = 0L,
        val unreadReply: Long = 0L,
        val unreadAt: Long = 0L,
        val unreadLike: Long = 0L,
        val tab: Int = 0,                   // 0=回复 1=@ 2=赞
        val items: List<MsgFeedItem> = emptyList(),
        val page: Int = 1,
        val hasMore: Boolean = false,
        val isLoading: Boolean = false,
        val isLogin: Boolean = AccountSession.isLogin,
        val error: String? = null
    )

    init { loadUnread(); load(1) }

    fun loadUnread() {
        viewModelScope.launch {
            api.getMsgUnread().onSuccess { resp ->
                resp.data?.let { d ->
                    _state.value = _state.value.copy(
                        unreadTotal = d.total, unreadReply = d.reply,
                        unreadAt = d.at, unreadLike = d.like
                    )
                }
            }
        }
    }

    fun setTab(tab: Int) {
        if (_state.value.tab == tab) return
        _state.value = _state.value.copy(tab = tab, items = emptyList())
        load(1)
    }

    fun load(page: Int) {
        if (!AccountSession.isLogin) {
            _state.value = _state.value.copy(isLogin = false, isLoading = false)
            return
        }
        if (page == 1) _state.value = _state.value.copy(isLoading = true, error = null)
        viewModelScope.launch {
            val call = when (_state.value.tab) {
                1 -> api.getMsgAt(page)
                2 -> api.getMsgLike(page)
                else -> api.getMsgReply(page)
            }
            call.onSuccess { resp ->
                if (resp.code == 0) {
                    val list = resp.data?.items.orEmpty()
                    _state.value = _state.value.copy(
                        isLoading = false,
                        items = if (page == 1) list else _state.value.items + list,
                        page = page,
                        hasMore = list.isNotEmpty(),
                        isLogin = true
                    )
                } else {
                    _state.value = _state.value.copy(isLoading = false, error = resp.message.ifEmpty { "加载失败" })
                }
            }.onFailure { e ->
                _state.value = _state.value.copy(
                    isLoading = false,
                    // 审核292：过错误码翻译
                    error = com.example.piliai.data.remote.BiliErrorTranslator
                        .translate(e.message) ?: e.message ?: "消息加载失败"
                )
            }
        }
    }

    fun loadMore() {
        val s = _state.value
        if (s.isLoading || !s.hasMore) return
        load(s.page + 1)
    }

    fun consumeError() {
        _state.value = _state.value.copy(error = null)
    }
}
