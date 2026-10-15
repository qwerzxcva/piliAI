package com.example.piliai.ui.note

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * 审核319：视频笔记内容编解码（Quill delta 格式）。
 *
 * 为什么用 Quill delta 而不是自造格式：BiliPai 的笔记同样是 Quill delta，
 * 采用同一格式意味着（a）格式已被真实客户端验证，边界情况（列表/引用/
 * 高亮/删除线）都有对应属性；（b）将来若要做公开笔记，内容可直接发给
 * B 站笔记接口，无需转换层。
 *
 * 存储形态：JSON 数组，每个元素是一次「插入操作」：
 * ```
 * { "insert": "文本", "attributes": { "bold": true } }
 * { "insert": { "tag": { "seconds": 12, "cid": 1, "title": "00:12" } } }
 * ```
 */

/** 编辑器文档：标题 + 块列表 */
data class VideoNoteDocument(
    val title: String = "",
    val blocks: List<VideoNoteBlock> = emptyList(),
)

/** 笔记块。文本块带富文本属性；时间点块可跳转到视频对应秒数。 */
sealed interface VideoNoteBlock {
    data class Text(
        val text: String,
        val bold: Boolean = false,
        val highlight: Boolean = false,
        val unorderedList: Boolean = false,
        val italic: Boolean = false,
        val underline: Boolean = false,
        val strikethrough: Boolean = false,
    ) : VideoNoteBlock

    data class Quote(val text: String) : VideoNoteBlock

    data class Timestamp(
        val seconds: Long,
        val cid: Long,
        val label: String = "",
    ) : VideoNoteBlock {
        /** label 为空时按秒数生成 mm:ss（默认值不能调 object 成员，故放 init） */
        val displayLabel: String get() = label.ifBlank { VideoNoteCodec.formatTimestamp(seconds) }
    }
}

/** 编码结果：内容 JSON + 派生字段（摘要/标签/长度） */
data class EncodedNote(
    val content: String,
    val summary: String,
    val tags: String,
    val contentLength: Int,
)

object VideoNoteCodec {

    private const val HIGHLIGHT_COLOR = "#fff359"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    fun formatTimestamp(seconds: Long): String {
        val safe = seconds.coerceAtLeast(0L)
        return "%02d:%02d".format(safe / 60, safe % 60)
    }

    fun decode(title: String, content: String): VideoNoteDocument {
        val blocks = runCatching {
            json.parseToJsonElement(content).jsonArray.mapNotNull(::decodeBlock)
        }.getOrDefault(emptyList())
        return VideoNoteDocument(title = title, blocks = blocks)
    }

    fun encode(document: VideoNoteDocument): EncodedNote {
        val ops = document.blocks.map(::encodeBlock)
        val content = json.encodeToString(JsonArray.serializer(), JsonArray(ops))
        val plain = toPlainText(document)
        return EncodedNote(
            content = content,
            summary = plain.replace('\n', ' ').trim().take(80),
            tags = extractTags(document),
            contentLength = plain.length,
        )
    }

    /** 纯文本（列表预览 + 摘要 + 长度统计） */
    fun toPlainText(document: VideoNoteDocument): String =
        document.blocks.joinToString(separator = "") { block ->
            when (block) {
                is VideoNoteBlock.Text -> block.text
                is VideoNoteBlock.Quote -> block.text
                is VideoNoteBlock.Timestamp -> "[${block.displayLabel}]"
            }
        }.trim()

    /** 标签：高亮文本里以 # 开头的词（Quill 社区惯例） */
    private fun extractTags(document: VideoNoteDocument): String =
        document.blocks
            .filterIsInstance<VideoNoteBlock.Text>()
            .flatMap { block ->
                TAG_REGEX.findAll(block.text).map { it.groupValues[1] }
            }
            .distinct()
            .joinToString(" ")

    private val TAG_REGEX = Regex("#([\\p{L}\\p{N}_]{1,20})")

    // ---------- 解码 ----------

    private fun decodeBlock(element: JsonElement): VideoNoteBlock? {
        val obj = element.jsonObject
        val insert = obj["insert"] ?: return null
        val attributes = obj["attributes"]?.jsonObject

        // 引用块
        if (attributes?.get("blockquote")?.jsonPrimitive?.booleanOrNull == true &&
            insert is JsonPrimitive
        ) {
            val text = insert.content.removeSuffix("\n")
            if (text.isBlank()) return null
            return VideoNoteBlock.Quote(text)
        }

        // 文本块
        if (insert is JsonPrimitive) {
            val text = insert.content
            if (text.isEmpty()) return null
            return VideoNoteBlock.Text(
                text = text,
                bold = attributes?.get("bold")?.jsonPrimitive?.booleanOrNull == true,
                highlight = attributes?.get("background")?.jsonPrimitive?.content == HIGHLIGHT_COLOR,
                unorderedList = attributes?.get("list")?.jsonPrimitive?.content == "bullet",
                italic = attributes?.get("italic")?.jsonPrimitive?.booleanOrNull == true,
                underline = attributes?.get("underline")?.jsonPrimitive?.booleanOrNull == true,
                strikethrough = attributes?.get("strike")?.jsonPrimitive?.booleanOrNull == true,
            )
        }

        // 时间点块
        val tag = insert.jsonObject["tag"]?.jsonObject ?: return null
        val seconds = tag["seconds"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
        return VideoNoteBlock.Timestamp(
            seconds = seconds,
            cid = tag["cid"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
            label = tag["title"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                ?: formatTimestamp(seconds),
        )
    }

    // ---------- 编码 ----------

    private fun encodeBlock(block: VideoNoteBlock): JsonObject = when (block) {
        is VideoNoteBlock.Text -> {
            val attributes = buildMap<String, JsonElement> {
                if (block.bold) put("bold", JsonPrimitive(true))
                if (block.highlight) put("background", JsonPrimitive(HIGHLIGHT_COLOR))
                if (block.unorderedList) put("list", JsonPrimitive("bullet"))
                if (block.italic) put("italic", JsonPrimitive(true))
                if (block.underline) put("underline", JsonPrimitive(true))
                if (block.strikethrough) put("strike", JsonPrimitive(true))
            }
            // Quill 的列表项必须以 \n 结尾，否则渲染不成列表
            val insert = if (block.unorderedList && !block.text.endsWith("\n")) {
                block.text + "\n"
            } else {
                block.text
            }
            buildJsonObject {
                put("insert", JsonPrimitive(insert))
                if (attributes.isNotEmpty()) put("attributes", JsonObject(attributes))
            }
        }

        is VideoNoteBlock.Quote -> buildJsonObject {
            val text = if (block.text.endsWith("\n")) block.text else block.text + "\n"
            put("insert", JsonPrimitive(text))
            put("attributes", buildJsonObject { put("blockquote", JsonPrimitive(true)) })
        }

        is VideoNoteBlock.Timestamp -> buildJsonObject {
            put(
                "insert",
                buildJsonObject {
                    put(
                        "tag",
                        buildJsonObject {
                            put("cid", JsonPrimitive(block.cid))
                            put("status", JsonPrimitive(0))
                            put("seconds", JsonPrimitive(block.seconds))
                            put("cidCount", JsonPrimitive(1))
                            put("title", JsonPrimitive(block.label))
                        }
                    )
                }
            )
        }
    }
}
