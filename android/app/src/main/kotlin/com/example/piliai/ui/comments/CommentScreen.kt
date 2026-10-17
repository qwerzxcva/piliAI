package com.example.piliai.ui.comments

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.piliai.data.model.CommentNode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.example.piliai.utils.toHttpsUrl

private val timeFormat by lazy { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA) }
private fun formatTime(ts: Long): String =
    if (ts <= 0) "" else timeFormat.format(Date(ts * 1000))

private fun formatCount(n: Long): String = when {
    n >= 100_000_000 -> String.format(Locale.ROOT, "%.1f亿", n / 100_000_000.0)
    n >= 10_000 -> String.format(Locale.ROOT, "%.1f万", n / 10_000.0)
    else -> n.toString()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommentScreen(
    bvid: String,
    onBack: () -> Unit = {},
    onGoLogin: () -> Unit = {},
    embedded: Boolean = false,
    // 审核295：空降助手——点评论里的时间戳跳到视频对应进度（毫秒）。
    // null 表示用默认行为：走 VideoSeekBus（播放器还活着就能跳），
    // 独立打开的评论页（没有活跃播放器）给提示而不是静默无反应。
    onSeekTo: ((Long) -> Unit)? = null,
    viewModel: CommentViewModel = viewModel(
        key = bvid,
        factory = object : androidx.lifecycle.ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                CommentViewModel(bvid) as T
        }
    )
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // 审核295：空降助手的实际跳转回调。调用方可覆盖；默认走 VideoSeekBus，
    // 没有活跃播放器时给 toast（Composable 上下文里才能拿 LocalContext）。
    val commentSeekTo: (Long) -> Unit = onSeekTo ?: run {
        val ctx = androidx.compose.ui.platform.LocalContext.current
               { ms: Long ->
            if (!com.example.piliai.playback.VideoSeekBus.seekTo(ms)) {
                android.widget.Toast.makeText(
                    ctx, "当前没有正在播放的视频", android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }
    }
    val snackbar = remember { SnackbarHostState() }
    var inputText by remember { mutableStateOf("") }
    var showEmotePanel by remember { mutableStateOf(false) }
    var showAtSearch by remember { mutableStateOf(false) }
    var emotes by remember { mutableStateOf<List<com.example.piliai.data.model.EmoteItem>>(emptyList()) }
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val scope = rememberCoroutineScope()

    // 表情包面板数据（首次展开加载小黄脸包 id=1）
    LaunchedEffect(showEmotePanel) {
        if (showEmotePanel && emotes.isEmpty()) {
            emotes = viewModel.loadEmotes()
        }
    }

    LaunchedEffect(state.error) {
        state.error?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeError()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (!embedded) {
            TopAppBar(
                title = {
                    Text("评论 ${if (state.totalCount > 0) "(${formatCount(state.totalCount.toLong())})" else ""}")
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                }
            )
            }
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp, modifier = Modifier.imePadding()) {
                // 表情面板
                if (showEmotePanel) {
                    if (emotes.isEmpty()) {
                        Box(Modifier.fillMaxWidth().height(140.dp), Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(24.dp))
                        }
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(8),
                            modifier = Modifier.fillMaxWidth().height(150.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            gridItems(emotes) { emote ->
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier
                                        .clip(MaterialTheme.shapes.small)
                                        .clickable {
                                            inputText += emote.text
                                        }
                                        .padding(2.dp)
                                ) {
                                    AsyncImage(
                                        model = emote.url.toHttpsUrl(),
                                        contentDescription = emote.text,
                                        modifier = Modifier.size(32.dp)
                                    )
                                }
                            }
                        }
                    }
                }
                // @ 候选下拉（批次L7）
                if (showAtSearch && state.atResults.isNotEmpty()) {
                    Surface(
                        tonalElevation = 4.dp,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
                    ) {
                        Column {
                            state.atResults.take(5).forEach { u ->
                                Row(
                                    Modifier.fillMaxWidth()
                                        .clickable {
                                            // 替换最后一个 @ 后缀为用户名
                                            val atIdx = inputText.lastIndexOf('@')
                                            if (atIdx >= 0) {
                                                inputText = inputText.substring(0, atIdx + 1) + u.uname + " "
                                            } else {
                                                inputText += "@${u.uname} "
                                            }
                                            viewModel.pickAtUser(u)
                                            showAtSearch = false
                                        }
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    coil.compose.AsyncImage(
                                        model = u.face.toHttpsUrl(), contentDescription = u.uname,
                                        modifier = Modifier.size(28.dp)
                                            .clip(CircleShape)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(u.uname, style = MaterialTheme.typography.bodyMedium)
                                    if (u.isUp == 1) {
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            "UP", style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // @ 用户按钮（批次L7）
                    IconButton(onClick = {
                        inputText += "@"
                        viewModel.searchAt("", 0L)
                        showAtSearch = false
                    }) {
                        Icon(
                            Icons.Default.AlternateEmail, "@",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    OutlinedTextField(
                        value = inputText,
                        onValueChange = {
                            inputText = it
                            // 输入 @xxx 时实时搜索候选
                            val atIdx = it.lastIndexOf('@')
                            if (atIdx >= 0 && it.length > atIdx + 1) {
                                viewModel.searchAt(it.substring(atIdx + 1), 0L)
                                showAtSearch = true
                            } else if (atIdx < 0) {
                                showAtSearch = false
                            }
                        },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("发一条友善的评论") },
                        maxLines = 3,
                        shape = MaterialTheme.shapes.large
                    )
                    Spacer(Modifier.width(8.dp))
                    IconButton(
                        onClick = {
                            showEmotePanel = !showEmotePanel
                            if (showEmotePanel) focusManager.clearFocus()
                        }
                    ) {
                        Icon(
                            Icons.Default.EmojiEmotions, "表情",
                            tint = if (showEmotePanel) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(
                        onClick = {
                            if (inputText.isNotBlank() && !state.sending) {
                                viewModel.sendComment(inputText)
                                inputText = ""
                                focusManager.clearFocus()
                            }
                        },
                        enabled = inputText.isNotBlank() && !state.sending
                    ) {
                        if (state.sending) {
                            CircularProgressIndicator(Modifier.size(20.dp))
                        } else {
                            Icon(Icons.AutoMirrored.Filled.Send, "发送")
                        }
                    }
                }
            }
        }
    ) { padding ->
        // 审核356：内嵌到播放页分页时，外层已处理系统栏，这里再吃一次
        // padding 会把评论输入框挤出屏幕。内嵌模式只取 top（其实为 0）。
        val innerPadding = if (embedded) PaddingValues(0.dp) else padding
        Column(Modifier.padding(innerPadding).fillMaxSize()) {
            // 审核237：排序玻璃分段（BiliPai CommentSortFilterBar 视觉）
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f), RoundedCornerShape(50))
                        .padding(3.dp)
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        listOf(3 to "热门", 2 to "最新").forEach { (m, label) ->
                            val selected = state.mode == m
                            Text(
                                label,
                                style = MaterialTheme.typography.labelMedium,
                                color = if (selected) MaterialTheme.colorScheme.onPrimary
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .clickable { viewModel.setMode(m) }
                                    .background(
                                        if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                                        RoundedCornerShape(50)
                                    )
                                    .padding(horizontal = 16.dp, vertical = 6.dp)
                            )
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                Text(
                    "${formatCount(state.totalCount.toLong())} 条评论",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            when {
                state.isLoading -> Box(
                    Modifier.fillMaxSize(), Alignment.Center
                ) { CircularProgressIndicator() }

                state.error != null && state.comments.isEmpty() -> Box(
                    Modifier.fillMaxSize(), Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("评论加载失败", color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { viewModel.load(1) }) { Text("重试") }
                    }
                }

                state.comments.isEmpty() -> Box(
                    Modifier.fillMaxSize(), Alignment.Center
                ) {
                    Text("还没有评论，来抢沙发吧", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                else -> LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // 审核237：置顶评论固定头
                    state.topComment?.let { top ->
                        item(key = "top_${top.rpid}") {
                            Column {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        "置顶",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier
                                            .background(
                                                MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                                RoundedCornerShape(4.dp)
                                            )
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                                Spacer(Modifier.height(4.dp))
                                CommentRow(
                                    comment = top,
                                    liked = top.rpid in state.likedRpid,
                                    expanded = state.expandedReplies[top.rpid],
                                    hasMoreReplies = state.repliesHasMore[top.rpid] == true,
                                    onLoadMoreReplies = { viewModel.loadMoreReplies(top.rpid) },
                                    onLike = { viewModel.toggleCommentLike(top.rpid) },
                                    onExpand = { viewModel.expandReplies(top.rpid) },
                                    onCollapse = { viewModel.collapseReplies(top.rpid) },
                                    onSeekTo = commentSeekTo
                                )
                            }
                        }
                    }
                    items(state.comments, key = { it.rpid }) { comment ->
                        CommentRow(
                            comment = comment,
                            liked = comment.rpid in state.likedRpid,
                            expanded = state.expandedReplies[comment.rpid],
                            hasMoreReplies = state.repliesHasMore[comment.rpid] == true,
                            onLoadMoreReplies = { viewModel.loadMoreReplies(comment.rpid) },
                            onLike = { viewModel.toggleCommentLike(comment.rpid) },
                            onExpand = { viewModel.expandReplies(comment.rpid) },
                            onCollapse = { viewModel.collapseReplies(comment.rpid) },
                            onSeekTo = commentSeekTo
                        )
                    }
                    if (state.hasMore) {
                        item {
                            Box(Modifier.fillMaxWidth().padding(12.dp), Alignment.Center) {
                                LaunchedEffect(state.comments.size) { viewModel.loadMore() }
                                CircularProgressIndicator(Modifier.size(22.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CommentRow(
    comment: CommentNode,
    liked: Boolean,
    expanded: List<CommentNode>?,
    hasMoreReplies: Boolean = false,
    onLoadMoreReplies: () -> Unit = {},
    onLike: () -> Unit,
    onExpand: () -> Unit,
    onCollapse: () -> Unit,
    // 审核295：空降助手跳转（毫秒）。评论页可能没有关联播放器，默认空实现。
    onSeekTo: (Long) -> Unit = {}
) {
    val member = comment.member
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.Top) {
            AsyncImage(
                model = member?.face.toHttpsUrl(),
                contentDescription = member?.uname,
                modifier = Modifier.size(36.dp).clip(CircleShape),
                contentScale = ContentScale.Crop
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    member?.uname ?: "匿名",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    formatTime(comment.ctime),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // 点赞
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable { onLike() }.padding(4.dp)
            ) {
                Icon(
                    if (liked || comment.action == 1) Icons.Default.Favorite
                    else Icons.Default.FavoriteBorder,
                    contentDescription = "点赞",
                    modifier = Modifier.size(16.dp),
                    tint = if (liked || comment.action == 1) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (comment.like > 0) {
                    Spacer(Modifier.width(3.dp))
                    Text(
                        formatCount(comment.like),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        val commentText = comment.content?.message.orEmpty()
        // 审核295：空降助手——正文里的时间戳（3:25 / 01:23:45）可点击跳转
        ClickableCommentText(
            text = commentText,
            onSeekTo = onSeekTo,
            style = MaterialTheme.typography.bodyMedium
        )
        // 楼中楼
        val inlineReplies = comment.replies
        when {
            expanded != null -> {
                Spacer(Modifier.height(4.dp))
                Text(
                    "收起回复",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { onCollapse() }.padding(vertical = 2.dp)
                )
                // 审核237：展开楼中楼灰底卡
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    expanded.forEach { reply -> SubReplyRow(reply) }
                    // 审核239 B3：长楼层加载更多
                    if (hasMoreReplies) {
                        Text(
                            "查看更多回复",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clickable { onLoadMoreReplies() }
                                .padding(vertical = 6.dp)
                        )
                    }
                }
            }
            comment.replyCount > 0 -> {
                Spacer(Modifier.height(4.dp))
                Text(
                    "▶ 共 ${comment.replyCount} 条回复",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { onExpand() }.padding(vertical = 2.dp)
                )
            }
            inlineReplies.isNotEmpty() -> {
                Spacer(Modifier.height(6.dp))
                // 审核237：楼中楼灰底圆角卡（BiliPai 楼层视觉）
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) { inlineReplies.forEach { reply -> SubReplyRow(reply) } }
            }
        }
    }
}

@Composable
private fun SubReplyRow(reply: CommentNode) {
    Row(
        Modifier.padding(start = 46.dp, top = 4.dp).fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        AsyncImage(
            model = reply.member?.face.toHttpsUrl(),
            contentDescription = reply.member?.uname,
            modifier = Modifier.size(22.dp).clip(CircleShape),
            contentScale = ContentScale.Crop
        )
        Spacer(Modifier.width(6.dp))
        Column {
            Row {
                Text(
                    reply.member?.uname ?: "",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    formatTime(reply.ctime),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(reply.content?.message ?: "", style = MaterialTheme.typography.bodySmall)
        }
    }
}
