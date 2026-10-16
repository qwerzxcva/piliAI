package com.example.piliai.utils

/**
 * 审核320：发布时间相对格式化。
 *
 * B 站接口给的是 Unix 秒（pubdate），用户要看的是「3天前」而不是时间戳。
 * 与 BiliPai/BiliRoamingX 的展示口径一致。
 */
fun formatRelativeTime(unixSeconds: Long, nowMs: Long = System.currentTimeMillis()): String {
    if (unixSeconds <= 0L) return ""
    val deltaSec = (nowMs / 1000 - unixSeconds).coerceAtLeast(0L)
    return when {
        deltaSec < 60L -> "刚刚"
        deltaSec < 3600L -> "${deltaSec / 60}分钟前"
        deltaSec < 86400L -> "${deltaSec / 3600}小时前"
        deltaSec < 86400L * 30L -> "${deltaSec / 86400}天前"
        deltaSec < 86400L * 365L -> "${deltaSec / (86400L * 30L)}个月前"
        else -> "${deltaSec / (86400L * 365L)}年前"
    }
}
