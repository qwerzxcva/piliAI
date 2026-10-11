package com.example.piliai.utils

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
