package com.example.piliai.data.model

import kotlinx.serialization.Serializable

/**
 * 动态模型 —— 对应 GET /x/polymer/web-dynamic/v1/feed/all（需登录 SESSDATA）
 */
@Serializable
data class DynamicFeedResponse(
    val code: Int = 0,
    val message: String? = null,
    val data: DynamicFeedData? = null
)

@Serializable
data class DynamicFeedData(
    val has_more: Boolean = false,
    val offset: String? = null,          // 翻页游标，传回下一页请求
    val items: List<DynamicFeedItem> = emptyList()
)

@Serializable
data class DynamicFeedItem(
    val id_str: String = "",
    val type: String = "",               // DYNAMIC_TYPE_AV=视频 等
    val modules: DynamicModules? = null
) {
    /** 便捷取值：视频动态的核心信息（非视频动态返回 null） */
    val videoInfo: DynamicVideoInfo? get() = modules?.major?.archive

    /** 审核266：便捷取值：被转发的原动态（非转发动态返回 null） */
    val forwardInfo: DynamicForward? get() = modules?.major?.additional?.forward
}

@Serializable
data class DynamicModules(
    val module_author: DynamicModuleAuthor? = null,
    val module_dynamic: DynamicModuleDynamic? = null,
    val module_stat: DynamicModuleStat? = null,
    val major: DynamicMajor? = null
)

@Serializable
data class DynamicModuleAuthor(
    val mid: Long = 0L,
    val name: String = "",
    val face: String = "",
    val pub_time: String = ""
)

@Serializable
data class DynamicModuleDynamic(
    val desc: DynamicDesc? = null
)

@Serializable
data class DynamicDesc(val text: String = "")

@Serializable
data class DynamicModuleStat(
    val forward: Long = 0L,
    val comment: Long = 0L,
    val like: Long = 0L
)

@Serializable
data class DynamicMajor(
    val archive: DynamicVideoInfo? = null,   // type=DYNAMIC_TYPE_AV 时存在
    val draw: DynamicDrawInfo? = null,       // 图片动态
    // 审核266：转发动动态引用的原动态（modules.major.additional.type = ADDITIONAL_TYPE_FORWARD）
    val additional: DynamicAdditional? = null
)

/**
 * 审核266：动态附加内容（转发 / 预约 / 直播等）。
 * 只实现最常见的 FORWARD（转发），其它 type 原样保留 type 名不解析。
 */
@Serializable
data class DynamicAdditional(
    val type: String = "",
    val forward: DynamicForward? = null
)

/** 审核266：被转发的原动态。 */
@Serializable
data class DynamicForward(
    val desc: DynamicDesc? = null,
    /** 原动态若是视频，有 archive；若是图动态，有 draw */
    val archive: DynamicVideoInfo? = null,
    val draw: DynamicDrawInfo? = null
)

@Serializable
data class DynamicVideoInfo(
    val aid: Long = 0L,
    val bvid: String = "",
    val cid: Long = 0L,
    val title: String = "",
    val pic: String = "",
    val desc: String = "",
    val duration_text: String = ""
)

@Serializable
data class DynamicDrawInfo(val items: List<DynamicDrawItem> = emptyList())

@Serializable
data class DynamicDrawItem(val src: String = "")
