package com.example.piliai.ui.comments

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString

/**
 * 审核295：空降助手——评论正文里的时间戳可点击，点击直接跳到视频对应进度。
 *
 * B 站评论区高频用法是「3:25 处高能」「01:23:45 名场面」，用户看到后要自己
 * 拖进度条去找。这里把时间戳识别成可点击片段，点一下跳转。
 *
 * 时间戳格式（B 站评论常见写法）：
 *   - `3:25`      分:秒
 *   - `01:23:45`  时:分:秒
 *   - `@3:25`     带 @ 前缀（部分用户习惯）
 * 不匹配的情况：单个数字（`3`）、超过合理范围的（`99:99`）不做处理，
 * 避免把普通文本误判成跳转点。
 */

/** 时间戳匹配：可选的 @ 前缀 + 1~2位(:1~2位){1,2}，且每位数字 <= 59 */
private val TIMESTAMP_REGEX = Regex(
    """(?:@)?(?:\d{1,2}:)?\d{1,2}:\d{1,2}(?=\s|$|[，。！？、,.)）]|$)"""
)

/** 把 `h:mm:ss` / `m:ss` 解析成毫秒；非法格式返回 null */
fun parseTimestampToMs(text: String): Long? {
    val clean = text.removePrefix("@").trim()
    val parts = clean.split(":")
    if (parts.size !in 2..3) return null
    val nums = parts.map { it.toIntOrNull() ?: return null }
    // 合理性：分/秒必须 < 60，小时不限但需为正
    for (n in nums.dropLast(1)) {
        if (n < 0) return null
    }
    if (nums.last() !in 0..59) return null
    if (nums.size == 3 && nums[1] !in 0..59) return null
    val seconds = when (nums.size) {
        2 -> nums[0] * 60L + nums[1]
        3 -> nums[0] * 3600L + nums[1] * 60L + nums[2]
        else -> return null
    }
    // 超过 24 小时的一律视为误判
    if (seconds > 24 * 3600) return null
    return seconds * 1000L
}

/**
 * 把评论正文转成带可点击时间戳的 AnnotatedString。
 *
 * @param onSeek 点击时间戳时回调（毫秒）
 */
fun buildSeekableCommentText(
    text: String,
    onSeek: (Long) -> Unit
): androidx.compose.ui.text.AnnotatedString {
    if (text.isBlank()) return androidx.compose.ui.text.AnnotatedString(text)
    val builder = androidx.compose.ui.text.AnnotatedString.Builder(text)
    var found = false
    for (match in TIMESTAMP_REGEX.findAll(text)) {
        val ms = parseTimestampToMs(match.value) ?: continue
        // 单段「3」不会被正则匹配（要求至少两段），这里再挡一下 `3:3` 这种
        // 秒数不两位的写法其实也合法，无需额外过滤。
        builder.addStringAnnotation(
            tag = SEEK_TAG,
            annotation = ms.toString(),
            start = match.range.first,
            end = match.range.last + 1,
        )
        found = true
    }
    if (!found) return androidx.compose.ui.text.AnnotatedString(text)
    return builder.toAnnotatedString()
}

const val SEEK_TAG = "seek_timestamp"

/**
 * 审核295：可点击时间戳的评论正文。
 *
 * 没有时间戳时退化成普通 Text（零额外开销）；命中时间戳时给片段上
 * primary 色 + 下划线，点击触发 onSeekTo。
 */
@Composable
fun ClickableCommentText(
    text: String,
    style: androidx.compose.ui.text.TextStyle,
    modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier,
    onSeekTo: (Long) -> Unit = {}
) {
    val primary = MaterialTheme.colorScheme.primary
    val annotated = remember(text, primary) {
        val base = buildSeekableCommentText(text) { }
        val builder = androidx.compose.ui.text.AnnotatedString.Builder(base)
        base.getStringAnnotations(SEEK_TAG, 0, base.length).forEach { ann ->
            builder.addStyle(
                style = androidx.compose.ui.text.SpanStyle(
                    color = primary,
                    textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline
                ),
                start = ann.start,
                end = ann.end
            )
        }
        builder.toAnnotatedString()
    }
    val layoutResult = remember { mutableStateOf<androidx.compose.ui.text.TextLayoutResult?>(null) }
    Text(
        text = annotated,
        style = style,
        modifier = modifier.then(
            if (annotated.getStringAnnotations(SEEK_TAG, 0, annotated.length).isNotEmpty()) {
                Modifier.pointerInput(annotated) {
                    detectTapGestures { pos ->
                        val layout = layoutResult.value ?: return@detectTapGestures
                        val offset = layout.getOffsetForPosition(pos)
                        annotated.getStringAnnotations(SEEK_TAG, offset, offset)
                            .firstOrNull()?.let { ann ->
                                ann.item.toLongOrNull()?.let { onSeekTo(it) }
                            }
                    }
                }
            } else Modifier
        ),
        onTextLayout = { layoutResult.value = it }
    )
}
