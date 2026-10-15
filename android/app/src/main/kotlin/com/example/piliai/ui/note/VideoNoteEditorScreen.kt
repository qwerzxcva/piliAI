package com.example.piliai.ui.note

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatListBulleted
import androidx.compose.material.icons.filled.FormatStrikethrough
import androidx.compose.material.icons.filled.FormatUnderlined
import androidx.compose.material.icons.filled.Highlight
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.launch

/**
 * 审核319：视频笔记编辑器（私有）。
 *
 * 交互模型（对齐 BiliPai VideoNoteEditor）：
 * - 正文是一个「块列表」：文本块（可加粗/斜体/下划线/删除线/高亮/列表）、
 *   引用块、时间点块。
 * - 「插入时间点」按钮把当前播放秒数变成一个块，点击该块跳回视频对应位置。
 * - 保存时经 [VideoNoteCodec] 编码为 Quill delta 存 Room。
 *
 * 简化说明：BiliPai 用的是真正的富文本编辑器（光标内联样式、选区操作）。
 * 本实现按块组织，样式作用于整块而非选区——对「记要点 + 打时间戳」这个
 * 核心场景够用，且实现复杂度低一个量级。
 */
class VideoNoteEditorViewModel(
    private val repo: VideoNoteRepository,
) : ViewModel() {

    var title by mutableStateOf("")
        private set

    /** 块列表（可变，编辑器直接增删改） */
    val blocks = mutableStateListOf<VideoNoteBlock>()

    var saving by mutableStateOf(false)
        private set
    var saved by mutableStateOf(false)
        private set

    /** 载入已有笔记 */
    fun load(bvid: String, cid: Long, videoTitle: String, cover: String) {
        // 先把标识字段落到实例上，再异步读库——否则 save() 时这些字段还是空
        this.bvid = bvid
        this.cid = cid
        this.videoTitle = videoTitle
        this.cover = cover
        viewModelScope.launch {
            val existing = repo.load(bvid, cid)
            if (existing != null) {
                title = existing.title
                blocks.clear()
                blocks.addAll(VideoNoteCodec.decode(existing.title, existing.content).blocks)
            } else {
                title = ""
                blocks.clear()
                blocks.add(VideoNoteBlock.Text(""))
            }
        }
    }

    private var bvid: String = ""
    private var cid: Long = 0L
    private var videoTitle: String = ""
    private var cover: String = ""

    fun updateTitle(value: String) { title = value }

    /** 在末尾追加一个文本块 */
    fun addTextBlock() {
        blocks.add(VideoNoteBlock.Text(""))
    }

    fun updateTextBlock(index: Int, text: String) {
        val current = blocks.getOrNull(index) as? VideoNoteBlock.Text ?: return
        blocks[index] = current.copy(text = text)
    }

    /** 切换文本块的某个富文本属性 */
    fun toggleStyle(index: Int, style: NoteStyle) {
        val current = blocks.getOrNull(index) as? VideoNoteBlock.Text ?: return
        blocks[index] = when (style) {
            NoteStyle.BOLD -> current.copy(bold = !current.bold)
            NoteStyle.ITALIC -> current.copy(italic = !current.italic)
            NoteStyle.UNDERLINE -> current.copy(underline = !current.underline)
            NoteStyle.STRIKETHROUGH -> current.copy(strikethrough = !current.strikethrough)
            NoteStyle.HIGHLIGHT -> current.copy(highlight = !current.highlight)
            NoteStyle.LIST -> current.copy(unorderedList = !current.unorderedList)
        }
    }

    fun removeBlock(index: Int) {
        if (index in blocks.indices) blocks.removeAt(index)
    }

    /** 插入时间点块（seconds 由 UI 传当前播放进度） */
    fun insertTimestamp(seconds: Long) {
        val insertAt = blocks.indexOfLast { it is VideoNoteBlock.Text && it.text.isNotBlank() }
            .let { if (it < 0) blocks.size else it + 1 }
        blocks.add(insertAt, VideoNoteBlock.Timestamp(seconds = seconds, cid = cid))
    }

    fun insertQuote() {
        blocks.add(VideoNoteBlock.Quote(""))
    }

    fun updateQuote(index: Int, text: String) {
        val current = blocks.getOrNull(index) as? VideoNoteBlock.Quote ?: return
        blocks[index] = current.copy(text = text)
    }

    fun save() {
        viewModelScope.launch {
            saving = true
            val encoded = VideoNoteCodec.encode(VideoNoteDocument(title = title, blocks = blocks.toList()))
            repo.save(
                bvid = bvid,
                cid = cid,
                title = title,
                content = encoded.content,
                summary = encoded.summary,
                tags = encoded.tags,
                contentLength = encoded.contentLength,
                videoTitle = videoTitle,
                cover = cover,
            )
            saving = false
            saved = true
        }
    }
}

enum class NoteStyle { BOLD, ITALIC, UNDERLINE, STRIKETHROUGH, HIGHLIGHT, LIST }

/**
 * 审核319：笔记编辑器界面。
 *
 * @param currentPositionMs 当前播放进度（毫秒），点「插入时间点」时用它
 * @param onSeekTo 点击时间点块时跳转到视频对应秒数
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoNoteEditorScreen(
    bvid: String,
    cid: Long,
    videoTitle: String,
    cover: String,
    currentPositionMs: Long,
    onSeekTo: (Long) -> Unit,
    onBack: () -> Unit,
    vm: VideoNoteEditorViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                VideoNoteEditorViewModel(
                    VideoNoteRepository(
                        com.example.piliai.AppContext.get()
                    )
                )
            }
        }
    ),
) {
    val scope = rememberCoroutineScope()

    androidx.compose.runtime.LaunchedEffect(bvid, cid) {
        vm.load(bvid, cid, videoTitle, cover)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("笔记") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                actions = {
                    if (vm.saving) {
                        CircularProgressIndicator(Modifier.size(24.dp))
                        Spacer(Modifier.width(12.dp))
                    } else {
                        Button(onClick = { vm.save() }) { Text("保存") }
                        Spacer(Modifier.width(12.dp))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            OutlinedTextField(
                value = vm.title,
                onValueChange = { vm.updateTitle(it) },
                label = { Text("标题") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            Spacer(Modifier.height(12.dp))

            // 工具栏
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "工具栏作用于整块：",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                IconButton(onClick = {
                    val idx = vm.blocks.indexOfLast { it is VideoNoteBlock.Text && it.text.isNotBlank() }
                    vm.toggleStyle(if (idx < 0) vm.blocks.lastIndex else idx, NoteStyle.BOLD)
                }) { Icon(Icons.Filled.FormatBold, "加粗") }
                IconButton(onClick = {
                    val idx = vm.blocks.indexOfLast { it is VideoNoteBlock.Text && it.text.isNotBlank() }
                    vm.toggleStyle(if (idx < 0) vm.blocks.lastIndex else idx, NoteStyle.ITALIC)
                }) { Icon(Icons.Filled.FormatItalic, "斜体") }
                IconButton(onClick = {
                    val idx = vm.blocks.indexOfLast { it is VideoNoteBlock.Text && it.text.isNotBlank() }
                    vm.toggleStyle(if (idx < 0) vm.blocks.lastIndex else idx, NoteStyle.HIGHLIGHT)
                }) { Icon(Icons.Filled.Highlight, "高亮") }
                IconButton(onClick = {
                    val idx = vm.blocks.indexOfLast { it is VideoNoteBlock.Text && it.text.isNotBlank() }
                    vm.toggleStyle(if (idx < 0) vm.blocks.lastIndex else idx, NoteStyle.LIST)
                }) { Icon(Icons.Filled.FormatListBulleted, "列表") }
            }

            Spacer(Modifier.height(4.dp))

            // 插入按钮
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = { vm.insertTimestamp(currentPositionMs / 1000) }) {
                    Icon(Icons.Filled.Schedule, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("插入时间点 ${VideoNoteCodec.formatTimestamp(currentPositionMs / 1000)}")
                }
                Button(onClick = { vm.insertQuote() }) { Text("引用") }
                Button(onClick = { vm.addTextBlock() }) { Text("段落") }
            }

            Spacer(Modifier.height(12.dp))

            // 块列表
            vm.blocks.forEachIndexed { index, block ->
                when (block) {
                    is VideoNoteBlock.Text -> TextBlockEditor(
                        block = block,
                        onChange = { vm.updateTextBlock(index, it) },
                        onRemove = { vm.removeBlock(index) },
                    )

                    is VideoNoteBlock.Quote -> QuoteBlockEditor(
                        block = block,
                        onChange = { vm.updateQuote(index, it) },
                        onRemove = { vm.removeBlock(index) },
                    )

                    is VideoNoteBlock.Timestamp -> TimestampBlock(
                        block = block,
                        onClick = { onSeekTo(block.seconds * 1000) },
                        onRemove = { vm.removeBlock(index) },
                    )
                }
                Spacer(Modifier.height(8.dp))
            }

            Spacer(Modifier.height(24.dp))

            if (vm.saved) {
                Text(
                    "已保存",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun TextBlockEditor(
    block: VideoNoteBlock.Text,
    onChange: (String) -> Unit,
    onRemove: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        OutlinedTextField(
            value = block.text,
            onValueChange = onChange,
            modifier = Modifier.weight(1f),
            textStyle = TextStyle(
                fontWeight = if (block.bold) FontWeight.Bold else FontWeight.Normal,
                fontStyle = if (block.italic) FontStyle.Italic else FontStyle.Normal,
                textDecoration = when {
                    block.underline && block.strikethrough ->
                        TextDecoration.combine(listOf(TextDecoration.Underline, TextDecoration.LineThrough))
                    block.underline -> TextDecoration.Underline
                    block.strikethrough -> TextDecoration.LineThrough
                    else -> TextDecoration.None
                },
                background = if (block.highlight) Color(0x59FFF359) else Color.Unspecified,
            ),
            placeholder = { Text(if (block.unorderedList) "列表项…" else "写点什么…") },
            prefix = if (block.unorderedList) {
                { Text("• ", style = MaterialTheme.typography.bodyLarge) }
            } else null,
        )
        IconButton(onClick = onRemove) {
            Icon(Icons.Filled.Delete, "删除块", tint = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun QuoteBlockEditor(
    block: VideoNoteBlock.Quote,
    onChange: (String) -> Unit,
    onRemove: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Surface(
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = RoundedCornerShape(8.dp),
        ) {
            Row(Modifier.padding(4.dp)) {
                Box(
                    Modifier
                        .width(3.dp)
                        .height(48.dp)
                        .background(MaterialTheme.colorScheme.primary)
                )
                OutlinedTextField(
                    value = block.text,
                    onValueChange = onChange,
                    modifier = Modifier.padding(start = 8.dp),
                    placeholder = { Text("引用内容…") },
                )
            }
        }
        IconButton(onClick = onRemove) {
            Icon(Icons.Filled.Delete, "删除块", tint = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun TimestampBlock(
    block: VideoNoteBlock.Timestamp,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Surface(
            onClick = onClick,
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = RoundedCornerShape(8.dp),
        ) {
            Row(
                Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.Schedule,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    block.displayLabel,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "点击跳转",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                )
            }
        }
        IconButton(onClick = onRemove) {
            Icon(Icons.Filled.Delete, "删除块", tint = MaterialTheme.colorScheme.error)
        }
    }
}
