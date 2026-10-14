package com.example.piliai.ui.messages

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.piliai.data.model.*
import com.example.piliai.data.remote.AccountSession
import com.example.piliai.data.remote.BiliApiClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 私聊会话列表 VM */
class SessionListViewModel : ViewModel() {
    private val api = BiliApiClient()

    data class UiState(
        val loading: Boolean = false,
        val sessions: List<SessionItem> = emptyList(),
        val error: String? = null
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init { load() }

    fun load() {
        if (!AccountSession.isLogin) {
            _state.value = UiState(error = "请先登录")
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true)
            api.getMySessions().onSuccess { resp ->
                _state.value = UiState(sessions = resp.data?.sessionList.orEmpty())
            }.onFailure { e ->
                _state.value = UiState(
                    // 审核292：过错误码翻译
                    error = com.example.piliai.data.remote.BiliErrorTranslator
                        .translate(e.message) ?: e.message ?: "加载失败"
                )
            }
        }
    }
}

/** 单个私聊对话 VM（消息记录 + 发送） */
class ChatViewModel(private val talkerId: Long) : ViewModel() {
    private val api = BiliApiClient()

    data class UiState(
        val loading: Boolean = false,
        val sending: Boolean = false,
        val messages: List<SessionMsg> = emptyList(),
        val myMid: Long = 0L,
        val error: String? = null
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        _state.value = _state.value.copy(myMid = AccountSession.mid)
        load()
    }

    fun load() {
        if (!AccountSession.isLogin) {
            _state.value = _state.value.copy(error = "请先登录")
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true)
            api.fetchSessionMsgs(talkerId).onSuccess { resp ->
                _state.value = _state.value.copy(
                    loading = false,
                    messages = resp.data?.messages.orEmpty().sortedBy { it.timestamp }
                )
            }.onFailure { e ->
                _state.value = _state.value.copy(
                    loading = false,
                    // 审核292：过错误码翻译
                    error = com.example.piliai.data.remote.BiliErrorTranslator
                        .translate(e.message) ?: e.message ?: "加载失败"
                )
            }
        }
    }

    fun send(text: String) {
        if (text.isBlank()) return
        viewModelScope.launch {
            _state.value = _state.value.copy(sending = true)
            api.sendPrivateMsg(_state.value.myMid, talkerId, text.trim()).onSuccess { ok ->
                _state.value = _state.value.copy(sending = false)
                if (ok) load() else _state.value = _state.value.copy(error = "发送失败")
            }.onFailure { e ->
                _state.value = _state.value.copy(
                    sending = false,
                    // 审核292：过错误码翻译
                    error = com.example.piliai.data.remote.BiliErrorTranslator
                        .translate(e.message) ?: e.message ?: "发送失败"
                )
            }
        }
    }

    /** 发送图片消息（msg_type=2，批次L4）——以 URL 方式发图 */
    fun sendImage(url: String, width: Int, height: Int) {
        if (url.isBlank()) return
        viewModelScope.launch {
            _state.value = _state.value.copy(sending = true)
            api.sendPrivateImage(_state.value.myMid, talkerId, url, width, height).onSuccess { ok ->
                _state.value = _state.value.copy(sending = false)
                if (ok) load() else _state.value = _state.value.copy(error = "图片发送失败")
            }.onFailure { e ->
                _state.value = _state.value.copy(
                    sending = false,
                    // 审核292：过错误码翻译
                    error = com.example.piliai.data.remote.BiliErrorTranslator
                        .translate(e.message) ?: e.message ?: "图片发送失败"
                )
            }
        }
    }
}