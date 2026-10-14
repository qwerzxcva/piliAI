package com.example.piliai.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 审核296：应用更新检查（GitHub Releases）。
 *
 * 走公开 Releases API（无需鉴权），比对 tag_name 与本地 versionName。
 * 仓库还没发过 Release 时返回 null（调用方提示「暂无发布版本」），
 * 不是错误。
 */
object UpdateChecker {

    private const val OWNER = "qwerzxcva"
    private const val REPO = "piliai"
    private const val API = "https://api.github.com/repos/$OWNER/$REPO/releases/latest"

    @Serializable
    private data class Release(
        val tag_name: String? = null,
        val name: String? = null,
        val body: String? = null,
        val html_url: String? = null,
        val draft: Boolean = false,
        val prerelease: Boolean = false,
    )

    data class UpdateInfo(
        val version: String,
        val notes: String,
        val url: String,
    )

    /**
     * 检查更新。
     * @param currentVersion 本地 versionName（如 "1.0.0"）
     * @return 有新版返回 [UpdateInfo]；已是最新或仓库无 Release 返回 null
     */
    suspend fun check(currentVersion: String): UpdateInfo? = withContext(Dispatchers.IO) {
        runCatching {
            val req = java.net.URL(API).openConnection() as java.net.HttpURLConnection
            req.connectTimeout = 10_000
            req.readTimeout = 10_000
            req.setRequestProperty("Accept", "application/vnd.github+json")
            req.setRequestProperty("User-Agent", "piliai-update-checker")
            try {
                if (req.responseCode != 200) return@runCatching null
                val text = req.inputStream.bufferedReader().use { it.readText() }
                val release = Json { ignoreUnknownKeys = true }
                    .decodeFromString<Release>(text)
                if (release.draft) return@runCatching null
                val remote = release.tag_name?.removePrefix("v")?.trim().orEmpty()
                if (remote.isEmpty()) return@runCatching null
                if (!isNewer(remote, currentVersion)) return@runCatching null
                UpdateInfo(
                    version = remote,
                    notes = release.body.orEmpty().trim().ifEmpty { "（无更新说明）" },
                    url = release.html_url ?: "https://github.com/$OWNER/$REPO/releases",
                )
            } finally {
                req.disconnect()
            }
        }.getOrNull()
    }

    /** 语义化版本比较：remote 比 current 新才返回 true */
    internal fun isNewer(remote: String, current: String): Boolean {
        val r = parseVersion(remote) ?: return false
        val c = parseVersion(current) ?: return false
        for (i in 0 until maxOf(r.size, c.size)) {
            val rv = r.getOrElse(i) { 0 }
            val cv = c.getOrElse(i) { 0 }
            if (rv != cv) return rv > cv
        }
        return false
    }

    /** "1.2.3-beta" -> [1,2,3]；解析不出数字段返回 null */
    private fun parseVersion(v: String): List<Int>? {
        val numeric = v.trim().substringBefore('-').substringBefore('+')
        if (numeric.isEmpty()) return null
        val parts = numeric.split('.')
        val nums = parts.map { it.toIntOrNull() ?: return null }
        return if (nums.isEmpty()) null else nums
    }
}
