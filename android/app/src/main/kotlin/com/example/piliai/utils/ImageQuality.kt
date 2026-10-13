package com.example.piliai.utils

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 审核235：图片质量压缩（移植 PiliPlus image_utils.thumbnailUrl）。
 * B站图床支持 @Nq.webp 后缀按质量压缩（N=1~100）。
 * picQuality 设置（0=原图 2/4/10=压缩档）作用于全 App 封面/头像，节省流量+加快加载。
 */
object ImageQuality {

    private val THUMB_REGEX = Regex("""(@(\d+[a-z]_?)*)(\..*)?$""")
    private val SUFFIX_REGEX = Regex("""\.(jpg|jpeg|png|webp|gif|avif)$""", RegexOption.IGNORE_CASE)

    @Volatile
    var qualityPercent: Int = 100

    /**
     * 审核260：进程启动时预热。
     *
     * 此前 qualityPercent 只由 SettingsViewModel 写（它的 collect + setter 都会写）。
     * 问题：用户没打开过设置页时 SettingsViewModel 不存在，qualityPercent 停在
     * 默认 100（不压缩），而首页/播放器/搜索的封面早于它加载 ——
     * 于是「图片质量」对那些页面无效（要先进设置页再回来才生效）。
     *
     * 与 RendererPrefs.init 同一模式：Application 启动时异步读一次并持续同步。
     */
    fun warmUp(context: android.content.Context) {
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            runCatching {
                StorageManager.getInstance(context.applicationContext)
                    .picQualityFlow
                    .collect { q -> qualityPercent = if (q == 0) 100 else q }
            }
        }
    }

    /** 追加/替换压缩参数（PiliPlus thumbnailUrl 对齐） */
    fun apply(src: String?): String {
        if (src.isNullOrBlank() || qualityPercent >= 100) return src.toHttpsUrl()
        var url = src
        var hasMatch = false
        url = THUMB_REGEX.replace(url) { m ->
            hasMatch = true
            val suffix = m.groupValues[3].ifBlank { ".webp" }
            "${m.groupValues[1]}_${qualityPercent}q$suffix"
        }
        if (!hasMatch && SUFFIX_REGEX.containsMatchIn(url)) {
            url += "@${qualityPercent}q.webp"
        }
        return url.toHttpsUrl()
    }
}

/** 带压缩的 URL 入口（UI 层封面/头像统一走这里） */
fun String?.toCoverUrl(): String = ImageQuality.apply(this)
