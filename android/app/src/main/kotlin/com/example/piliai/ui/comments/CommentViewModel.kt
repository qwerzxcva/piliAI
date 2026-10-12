package com.example.piliai.ui.comments

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.piliai.data.model.CommentNode
import com.example.piliai.data.remote.AccountSession
import com.example.piliai.data.remote.BiliApiClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 评论区 ViewModel（批次E）——真实 API
 * 列表（wbi main）+ 排序（3=热门 2=最新）+ 楼中楼 + 评论点赞 + 发评论
 */
class CommentViewModel(private val bvid: String) : ViewModel() {

    private val api = BiliApiClient()

    private val _state = MutableStateFlow(CommentState())
    val state: StateFlow<CommentState> = _state.asStateFlow()

    data class CommentState(
        val oid: Long = 0L,                 // aid
        val comments: List<CommentNode> = emptyList(),
        // 审核237：置顶评论（页1固定显示在列表头）
        val topComment: CommentNode? = null,
        val totalCount: Int = 0,
        val page: Int = 1,
        val hasMore: Boolean = false,
        val mode: Int = 3,                  // 3=热门 2=最新
        val isLoading: Boolean = false,
        val isLoadingMore: Boolean = false,
        val error: String? = null,
        // 楼中楼展开状态：rootRpid -> 回复列表
        val expandedReplies: Map<Long, List<CommentNode>> = emptyMap(),
        val likedRpid: Set<Long> = emptySet(),
        val sending: Boolean = false,
        // @用户搜索（批次L7）
        val atResults: List<com.example.piliai.data.model.AtUser> = emptyList(),
        val atQuery: String = "",
        val pendingAt: com.example.piliai.data.model.AtUser? = null
    )

    init { load(1) }

    private fun aid(): Long = _state.value.oid

    fun load(page: Int, mode: Int? = null) {
        val m = mode ?: _state.value.mode
        if (page == 1) _state.value = _state.value.copy(isLoading = true, error = null, mode = m)
        else _state.value = _state.value.copy(isLoadingMore = true)

        viewModelScope.launch {
            // 先由 view 解析 aid（详情接口），oid==0 时兜底取
            val oid = if (aid() > 0) aid() else resolveAid()
            if (oid <= 0) {
                _state.value = _state.value.copy(isLoading = false, error = "无法获取视频 aid")
                return@launch
            }
            api.getComments(bvid, oid = oid, page = page, mode = m).onSuccess { resp ->
                if (resp.code == 0) {
                    val list = resp.data?.replay.orEmpty()
                    // 审核237：置顶评论取 upper 优先（UP主置顶），无则 admin
                    val top = resp.data?.top?.let { it.upper ?: it.admin ?: it.vote }
                    _state.value = _state.value.copy(
                        isLoading = false, isLoadingMore = false,
                        topComment = if (page == 1) top else _state.value.topComment,
                        comments = if (page == 1) list else _state.value.comments + list,
                        totalCount = resp.data?.count ?: 0,
                        page = page,
                        hasMore = list.isNotEmpty()
                    )
                } else {
                    _state.value = _state.value.copy(
                        isLoading = false, isLoadingMore = false,
                        error = resp.data?.let { "加载失败" } ?: "评论加载失败"
                    )
                }
            }.onFailure { e ->
                _state.value = _state.value.copy(
                    isLoading = false, isLoadingMore = false, error = e.message
                )
            }
        }
    }

    fun setMode(mode: Int) {
        if (_state.value.mode == mode) return
        load(1, mode)
    }

    fun loadMore() {
        val s = _state.value
        if (s.isLoading || s.isLoadingMore || !s.hasMore) return
        load(s.page + 1)
    }

    /** 展开楼中楼 */
    fun expandReplies(rpid: Long) {
        if (_state.value.expandedReplies.containsKey(rpid)) return
        viewModelScope.launch {
            api.getReplyList(aid(), rpid).onSuccess { resp ->
                if (resp.code == 0) {
                    _state.value = _state.value.copy(
                        expandedReplies = _state.value.expandedReplies +
                            (rpid to resp.data?.replies.orEmpty())
                    )
                }
            }
        }
    }

    fun collapseReplies(rpid: Long) {
        _state.value = _state.value.copy(
            expandedReplies = _state.value.expandedReplies - rpid
        )
    }

    /** 评论点赞 */
    fun toggleCommentLike(rpid: Long) {
        if (!AccountSession.isLogin) {
            _state.value = _state.value.copy(error = "请先登录")
            return
        }
        val liked = rpid in _state.value.likedRpid
        viewModelScope.launch {
            api.likeComment(aid(), rpid, !liked).onSuccess { ok ->
                if (ok) {
                    val newSet = _state.value.likedRpid.toMutableSet()
                    if (liked) newSet.remove(rpid) else newSet.add(rpid)
                    // 本地计数 ±1
                    _state.value = _state.value.copy(
                        likedRpid = newSet,
                        comments = _state.value.comments.map { c ->
                            if (c.rpid == rpid) c.copy(like = c.like + if (liked) -1 else 1)
                            else c
                        }
                    )
                }
            }
        }
    }

    /** 表情包（小黄脸包 id=1），供评论输入面板 */
    suspend fun loadEmotes(): List<com.example.piliai.data.model.EmoteItem> {
        return api.getEmotePackage(1).getOrNull()?.data?.emotes.orEmpty()
    }

    /** 发评论（rootRpid>0 = 回复楼中楼） */
    fun sendComment(message: String, rootRpid: Long = 0L) {
        if (message.isBlank()) return
        if (!AccountSession.isLogin) {
            _state.value = _state.value.copy(error = "请先登录")
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(sending = true)
            val at = _state.value.pendingAt
            api.addComment(
                aid(), message.trim(), rootRpid = rootRpid,
                atUid = at?.mid ?: 0L, atName = at?.uname.orEmpty()
            ).onSuccess { ok ->
                _state.value = _state.value.copy(sending = false, pendingAt = null)
                if (ok) {
                    if (rootRpid > 0) expandReplies(rootRpid) else load(1)
                } else {
                    _state.value = _state.value.copy(error = "发送失败")
                }
            }.onFailure { e ->
                _state.value = _state.value.copy(sending = false, error = e.message)
            }
        }
    }

    /** @用户搜索（批次L7，需登录） */
    fun searchAt(keyword: String, rootRpid: Long = 0L) {
        _state.value = _state.value.copy(atQuery = keyword)
        if (keyword.isBlank()) {
            _state.value = _state.value.copy(atResults = emptyList())
            return
        }
        viewModelScope.launch {
            api.searchAtUser(keyword, aid(), rootRpid, rootRpid).onSuccess { resp ->
                _state.value = _state.value.copy(atResults = resp.data)
            }.onFailure {
                _state.value = _state.value.copy(atResults = emptyList())
            }
        }
    }

    /** 选中某 @用户 → 记录待发送 + 作为 pendingAt */
    fun pickAtUser(user: com.example.piliai.data.model.AtUser) {
        _state.value = _state.value.copy(pendingAt = user, atResults = emptyList(), atQuery = "")
    }

    fun clearAt() { _state.value = _state.value.copy(pendingAt = null) }

    fun consumeError() {
        _state.value = _state.value.copy(error = null)
    }

    /** bv → aid（view 接口） */
    private suspend fun resolveAid(): Long {
        return api.getVideoInfo(bvid).getOrNull()?.data?.aid ?: 0L
    }
}
