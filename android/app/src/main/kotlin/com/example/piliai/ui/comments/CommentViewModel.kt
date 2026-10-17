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
        val pendingAt: com.example.piliai.data.model.AtUser? = null,
        // 审核239 B3：楼中楼分页状态 rootRpid -> 已加载页码
        val repliesPage: Map<Long, Int> = emptyMap(),
        val repliesHasMore: Map<Long, Boolean> = emptyMap(),
    )

    // 审核362：独立评论页（无 bindAid）靠这里的兜底加载；
    // 播放页内嵌时会很快调 bindAid，那时会按拿到的 aid 重新拉。
    init { load(1) }

    /** 播放页已经拿到 aid 时直接用，避免评论接口再打一次详情。 */
    fun bindAid(aid: Long) {
        if (aid <= 0L) return
        if (_state.value.oid == aid && _state.value.comments.isNotEmpty()) return
        // 审核362：换视频时清空旧列表并递增代际，防止上一个视频的
        // 在飞请求返回后覆盖新视频的评论。
        generation++
        _state.value = _state.value.copy(
            oid = aid, comments = emptyList(), topComment = null,
            totalCount = 0, page = 1, hasMore = false,
        )
        load(1)
    }

    /** 审核362：换视频时代际递增，过期的响应直接丢弃 */
    private var generation = 0

    private fun aid(): Long = _state.value.oid

    fun load(page: Int, mode: Int? = null) {
        val m = mode ?: _state.value.mode
        val gen = generation
        if (page == 1) _state.value = _state.value.copy(isLoading = true, error = null, mode = m)
        else _state.value = _state.value.copy(isLoadingMore = true)

        viewModelScope.launch {
            // 先由 view 解析 aid（详情接口），oid==0 时兜底取
            val oid = if (aid() > 0) aid() else resolveAid()
            if (gen != generation) return@launch
            if (oid <= 0) {
                _state.value = _state.value.copy(isLoading = false, error = "无法获取视频 aid")
                return@launch
            }
            api.getComments(bvid, oid = oid, page = page, mode = m).onSuccess { resp ->
                if (gen != generation) return@onSuccess
                if (resp.code == 0) {
                    val list = resp.data?.replay.orEmpty()
                    // 审核237：置顶评论取 upper 优先（UP主置顶），无则 admin
                    val top = resp.data?.top?.let { it.upper ?: it.admin ?: it.vote }
                    // 审核239 C2：用服务端 action 位初始化点赞集——原实现 likedRpid
                    // 只记本会话点击，重启后"已赞"的评论显示为未赞，再点会变成取消赞。
                    if (page == 1) {
                        val serverLiked = list.filter { it.action == 1 }.map { it.rpid }.toSet() +
                            (top?.takeIf { it.action == 1 }?.rpid?.let { setOf(it) } ?: emptySet())
                        _state.value = _state.value.copy(likedRpid = serverLiked)
                    }
                    _state.value = _state.value.copy(
                        isLoading = false, isLoadingMore = false,
                        topComment = if (page == 1) top else _state.value.topComment,
                        comments = if (page == 1) list else _state.value.comments + list,
                        totalCount = resp.data?.count ?: 0,
                        page = page,
                        hasMore = list.isNotEmpty()
                    )
                } else {
                    // 审核276：把服务端的 code/message 带出来，别再显示
                    // 无信息的「加载失败」——用户分不清是 -403（需登录）
                    // 还是风控，只能干瞪眼。
                    val raw = resp.message?.takeIf { it.isNotBlank() }
                        ?: "评论加载失败（code=${resp.code}）"
                    _state.value = _state.value.copy(
                        isLoading = false, isLoadingMore = false,
                        error = com.example.piliai.data.remote.BiliErrorTranslator
                            .translate(raw) ?: raw
                    )
                }
            }.onFailure { e ->
                _state.value = _state.value.copy(
                    isLoading = false, isLoadingMore = false,
                        // 审核292：过错误码翻译，否则用户看到裸的 "-352"/"-403"
                        error = com.example.piliai.data.remote.BiliErrorTranslator
                            .translate(e.message) ?: e.message ?: "评论加载失败"
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
            api.getReplyList(aid(), rpid, page = 1).onSuccess { resp ->
                if (resp.code == 0) {
                    _state.value = _state.value.copy(
                        expandedReplies = _state.value.expandedReplies +
                            (rpid to resp.data?.replies.orEmpty()),
                        repliesPage = _state.value.repliesPage + (rpid to 1),
                        // 审核239 B3：第一页满 20 条才认为可能有更多
                        repliesHasMore = _state.value.repliesHasMore +
                            (rpid to (resp.data?.replies.orEmpty().size >= 20)),
                    )
                }
            }
        }
    }

    /** 审核239 B3：楼中楼加载更多（原实现只取第一页 20 条，长楼层看不全） */
    fun loadMoreReplies(rpid: Long) {
        val st = _state.value
        if (st.repliesHasMore[rpid] != true) return
        val next = (st.repliesPage[rpid] ?: 1) + 1
        viewModelScope.launch {
            api.getReplyList(aid(), rpid, page = next).onSuccess { resp ->
                if (resp.code == 0) {
                    val fresh = resp.data?.replies.orEmpty()
                    _state.value = _state.value.copy(
                        expandedReplies = _state.value.expandedReplies +
                            (rpid to (st.expandedReplies[rpid].orEmpty() + fresh)),
                        repliesPage = _state.value.repliesPage + (rpid to next),
                        repliesHasMore = _state.value.repliesHasMore +
                            (rpid to (fresh.size >= 20)),
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
                    val delta = if (liked) -1L else 1L
                    val newSet = _state.value.likedRpid.toMutableSet()
                    if (liked) newSet.remove(rpid) else newSet.add(rpid)
                    // 审核239 C1：同步更新主列表 + 置顶评论 + 已展开楼中楼（原实现
                    // 只改主列表——楼中楼/置顶评论里点赞后计数纹丝不动）
                    fun bump(c: CommentNode) =
                        if (c.rpid == rpid) c.copy(like = c.like + delta, action = if (liked) 0 else 1) else c
                    _state.value = _state.value.copy(
                        likedRpid = newSet,
                        comments = _state.value.comments.map(::bump),
                        topComment = _state.value.topComment?.let(::bump),
                        expandedReplies = _state.value.expandedReplies.mapValues { (_, list) ->
                            list.map(::bump)
                        },
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
                // 审核289：带上真实原因（评论太频繁/评论区已关闭/未登录），
                // 经 BiliErrorTranslator 翻译成可行动描述
                _state.value = _state.value.copy(
                    sending = false,
                    error = com.example.piliai.data.remote.BiliErrorTranslator
                        .translate(e.message) ?: e.message ?: "发送失败"
                )
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
