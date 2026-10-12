package com.example.piliai.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

/**
 * 首页推荐流（/x/web-interface/wbi/index/top/feed/rcmd）。
 *
 * 移植自 bv `BiliHttpApi.getFeedRcmd`（bili-api/.../http/BiliHttpApi.kt:3147-3164）：
 * fresh_type=4 + fresh_idx 翻页，返回结构与 index/top/rcmd 的 item[] 同构，
 * 因此复用 [TopRcmdItem] 解析（bv 的 RcmdTopData.RcmdItem 同样有 bvid/cid/pic/owner/stat）。
 *
 * 与 /x/web-interface/popular 的差异：popular 是全站热门（所有用户相同），
 * feed/rcmd 是个性化推荐且必须靠 fresh_idx 翻页；首页主列表用它才符合
 * 「刷新能出新内容」的预期（popular 的 pn 翻页内容更新慢且重复度高）。
 */
@Serializable
data class FeedRcmdResponse(
    val code: Int = 0,
    val message: String? = null,
    val data: FeedRcmdData? = null
)

@Serializable
data class FeedRcmdData(
    val item: List<FeedRcmdItem> = emptyList(),
    /** 审核237：服务端告知是否还有更多（bv 未使用，但可用于停止无效翻页） */
    @SerialName("business_card") val businessCard: kotlinx.serialization.json.JsonElement? = null
)

@Serializable
data class FeedRcmdItem(
    val id: Long = 0L,
    val bvid: String = "",
    val cid: Long = 0L,
    val goto: String = "",
    val uri: String = "",
    val title: String = "",
    val pic: String = "",
    val duration: Int = 0,
    val pubdate: Long = 0L,
    @SerialName("owner") val owner: TopRcmdOwner? = null,
    val stat: TopRcmdStat? = null
) {
    /** 只有 av 类型可播放；直播/广告/横栏等 goto 直接过滤掉 */
    val playable: Boolean get() = bvid.isNotEmpty() && (goto == "av" || goto.isEmpty())
}
