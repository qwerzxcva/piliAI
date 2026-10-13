package com.example.piliai.playback

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * 审核245：播放请求的 Referer 规则（移植 bv `PlaybackRequestHeaders.kt`）。
 *
 * bv 的原始注释：APP play URLs are signed for the Android client and Bilibili
 * rejects them when a web Referer is attached. Keep the Referer for live/WEB
 * URLs, including WEB URLs returned by API fallback.
 *
 * 背景：B 站有两类播放地址——
 * 1. WEB/DASH 地址：必须带 `Referer: https://www.bilibili.com`，
 *    否则 CDN 直接 403（本项目审核215 实测过）；
 * 2. APP 签名地址（query 带 `platform=android` / `android_*`）：
 *    签名只对 APP 客户端生效，带 web Referer 反而被拒。
 *
 * 早期实现「一律带 web Referer」在遇到 APP 兜底地址时会 403，
 * 而「一律不带」又会让 WEB 地址 403。所以必须按 URL 判定。
 */
object PlaybackRequestHeaders {

    /** 返回这些 URL 应该带的 Referer；命中 APP 签名地址时返回 null。 */
    fun refererFor(configuredReferer: String?, vararg urls: String?): String? {
        val referer = configuredReferer?.takeIf { it.isNotBlank() } ?: return null
        return referer.takeUnless { urls.filterNotNull().any { it.isAndroidAppMediaUrl() } }
    }

    private fun String.isAndroidAppMediaUrl(): Boolean = isAndroidAppMediaUrl(nestingDepth = 0)

    /**
     * 判定是否为 APP 签名地址。只解一层 `url=` 嵌套（bv 同样限制 nestingDepth <= 1）：
     * 再深就是别的站点的嵌套参数，与 B 站签名无关，且无限递归有风险。
     */
    private fun String.isAndroidAppMediaUrl(nestingDepth: Int): Boolean {
        val rawQuery = runCatching { URI(this).rawQuery }.getOrNull() ?: return false
        val parameters = rawQuery
            .split('&')
            .map { parameter ->
                decodeQueryComponent(parameter.substringBefore('=')) to
                    decodeQueryComponent(parameter.substringAfter('=', ""))
            }
        val platform = parameters
            .firstOrNull { (key) -> key.equals("platform", ignoreCase = true) }
            ?.second

        if (
            platform.equals("android", ignoreCase = true) ||
            platform?.startsWith("android_", ignoreCase = true) == true
        ) {
            return true
        }

        if (nestingDepth >= 1) return false
        return parameters.any { (key, value) ->
            key.equals("url", ignoreCase = true) &&
                value.isAndroidAppMediaUrl(nestingDepth = nestingDepth + 1)
        }
    }

    private fun decodeQueryComponent(value: String): String = runCatching {
        URLDecoder.decode(value, StandardCharsets.UTF_8.name())
    }.getOrDefault(value)
}
