package com.example.piliai.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

/**
 * Comment models for Bilibili API
 */

@Serializable
data class CommentResponse(
    val code: Int = 0,
    // 审核276：补 message 字段——原来没有，导致 code != 0 时
    // 上层只能显示无信息的「加载失败」（分不清 -403 还是风控）
    val message: String? = null,
    val data: CommentData? = null
)

@Serializable
data class CommentData(
    val upper: UpperInfo? = null,
    val replay: List<CommentNode> = emptyList(),
    // 审核237：置顶评论（wbi/main 顶层 top 字段，admin/upper/vote 三种来源）
    val top: TopCommentInfo? = null,
    val cursor: CursorInfo? = null,
    val count: Int = 0,
    val num: Int = 0
)

/** 置顶评论容器：{admin:CommentNode?, upper:CommentNode?, vote:CommentNode?} */
@Serializable
data class TopCommentInfo(
    val admin: CommentNode? = null,
    val upper: CommentNode? = null,
    val vote: CommentNode? = null
)

@Serializable
data class UpperInfo(
    val mid: Long = 0L,
    val uname: String = "",
    val face: String = "",
    val official: OfficialInfo? = null,
    val vip: VipInfo? = null
)

@Serializable
data class VipInfo(
    val vipStatus: Int = 0,
    val vipType: Int = 0,
    val vipDueDate: Long = 0L
)

@Serializable
data class CommentNode(
    val rpid: Long = 0L,
    val root: Long = 0L,
    val parent: Long = 0L,
    val seq: Long = 0L,
    val member: MemberInfo? = null,
    val content: ContentInfo? = null,
    val replies: List<CommentNode> = emptyList(),
    val like: Long = 0L,
    val reply: Long = 0L,
    val ctime: Long = 0L,
    @kotlinx.serialization.SerialName("rcount") val replyCount: Int = 0,
    val action: Int = 0   // 1=已点赞
)

@Serializable
data class MemberInfo(
    val mid: Long = 0L,
    val uname: String = "",
    val face: String = "",
    val official: OfficialInfo? = null,
    val vip: VipInfo? = null,
    val pendant: PendantInfo? = null
)

@Serializable
data class ContentInfo(
    val message: String = "",
    val date: Long = 0L,
    val round: Int = 0
)

@Serializable
data class CursorInfo(
    val cursor: Long = 0L,
    val upperCursor: Long = 0L,
    val bottomCursor: Long = 0L,
    val nextCursor: Long = 0L,
    val sortType: String = ""
)

// ===== 批次E：评论区增强 =====

@Serializable
data class CommentOperationResponse(
    val code: Int = 0,
    val message: String = ""
)

/** 楼中楼回复 /x/v2/reply/reply */
@Serializable
data class ReplyListResponse(
    val code: Int = 0,
    val message: String = "",
    val data: ReplyListData? = null
)

@Serializable
data class ReplyListData(
    val root: CommentNode? = null,
    val replies: List<CommentNode> = emptyList(),
    val page: ReplyPage? = null
)

@Serializable
data class ReplyPage(
    val num: Int = 1,
    val size: Int = 10,
    val count: Int = 0
)

// ==================== 表情包（/x/emote） ====================

@Serializable
data class EmotePackageResponse(
    val code: Int = 0,
    val message: String = "",
    val data: EmotePackage? = null
)

@Serializable
data class EmotePackage(
    @SerialName("id") val id: Long = 0,
    @SerialName("text") val text: String = "",
    @SerialName("url") val url: String = "",
    @SerialName("emote") val emotes: List<EmoteItem> = emptyList()
)

@Serializable
data class EmoteItem(
    @SerialName("id") val id: Long = 0,
    @SerialName("text") val text: String = "",
    @SerialName("url") val url: String = "",
    @SerialName("meta") val meta: EmoteMeta = EmoteMeta()
)

@Serializable
data class EmoteMeta(
    @SerialName("size") val size: Int = 1  // 1=小 2=大
)

// ==================== @用户搜索（批次L7） ====================

@Serializable
data class AtSearchResponse(
    val code: Int = 0,
    val message: String = "",
    val data: List<AtUser> = emptyList()
)

@Serializable
data class AtUser(
    val mid: Long = 0L,
    val uname: String = "",
    val face: String = "",
    @SerialName("is_up") val isUp: Int = 0
)
