package com.example.piliai.data.repository

import com.example.piliai.utils.toHttpsUrl

/**
 * 审核290：DASH 播放地址（含 CDN 候选列表）。
 *
 * B 站 playurl 给每条流返回 `baseUrl` + `backupUrl`（3 个左右 CDN 节点）。
 * 实测中遇到过 **baseUrl 首个节点 TLS 握手超时、而 backupUrl 节点完全正常**
 * 的情况（容器网络到个别 mcdn 节点抖动）。原来的实现只取 baseUrl，
 * 一遇到这种节点整个播放就失败——这是「普通视频也播放失败」的根因。
 *
 * 现在把候选列表一并交给播放器：主 URL 失败时按顺序尝试备用节点。
 */
data class PlayUrlCandidate(
    /** 主地址（baseUrl） */
    val primary: String,
    /** 备用 CDN 节点（backupUrl，可能为空） */
    val backups: List<String> = emptyList(),
) {
    /** 全部候选，主地址在前 */
    val all: List<String>
        get() = if (backups.isEmpty()) listOf(primary) else listOf(primary) + backups

    companion object {
        /** 从 playurl 响应的 Stream JSON 提取（Rust 选流输出或原始响应同构） */
        fun fromJson(stream: org.json.JSONObject?): PlayUrlCandidate? {
            if (stream == null) return null
            val primary = stream.optString("baseUrl").takeIf { it.isNotBlank() }?.toHttpsUrl()
                ?: return null
            val backups = mutableListOf<String>()
            val arr = stream.optJSONArray("backupUrl")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    arr.optString(i).takeIf { it.isNotBlank() }?.toHttpsUrl()?.let { backups.add(it) }
                }
            }
            return PlayUrlCandidate(primary, backups)
        }
    }
}
