package com.example.piliai.ui.audio

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil.compose.AsyncImage
import com.example.piliai.data.model.FavFolder
import com.example.piliai.data.model.SubtitleCue
import com.example.piliai.ui.theme.rememberCoverRimBrush
import com.example.piliai.utils.toCoverUrl
import kotlinx.coroutines.isActive

/**
 * 审核319：听视频模式（移植 BiliPai feature/audio）。
 *
 * 三段式 UI：
 * 1. 收藏夹列表（= 播放列表/歌单入口）
 * 2. 播放列表（收藏夹内的视频）
 * 3. 播放器（黑胶唱片 + 歌词）
 *
 * 视觉：黑胶唱片旋转沿用 BiliPai MusicArtworkRotationPolicy 的规格
 * （24 秒一圈、LinearEasing、播放时才转、reduceMotion 停）。歌词用 B 站
 * CC 字幕轨（piliai 已有 SubtitleController），不引入第三方歌词源。
 */
private const val ARTWORK_ROTATION_MS = 24_000

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListenVideoScreen(
    mid: Long,
    onBack: () -> Unit,
    vm: ListenVideoViewModel = viewModel(
        factory = viewModelFactory { initializer { ListenVideoViewModel() } }
    ),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(mid) { vm.loadFolders(mid) }

    val showPlayer = state.tracks.isNotEmpty() && state.playingIndex >= 0

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (showPlayer) state.tracks.getOrNull(state.playingIndex)?.title.orEmpty()
                        else "听视频",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            if (state.loading) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            }
            state.error?.let { err ->
                Column(
                    Modifier.align(Alignment.Center).padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(err, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                    Text(
                        "重试",
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer)
                            .clickable { vm.loadFolders(mid) }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
            if (state.notLoggedIn && !state.loading) {
                Text(
                    "登录后可使用听视频模式\n（用你的收藏夹作为播放列表）",
                    Modifier.align(Alignment.Center).padding(32.dp),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            when {
                showPlayer -> ListenPlayerPane(
                    state = state,
                    cues = vm.subtitle.cueList.collectAsStateWithLifecycle().value,
                    currentIndex = vm.subtitle.currentIndex.collectAsStateWithLifecycle().value,
                    onTogglePlay = { vm.togglePlayPause() },
                    onNext = { vm.next() },
                    onPrevious = { vm.previous() },
                    onSeek = { vm.seekTo(it) },
                )

                state.tracks.isNotEmpty() -> TrackListPane(
                    title = state.selectedFolderTitle,
                    tracks = state.tracks,
                    onPick = { vm.playAt(it) },
                )

                else -> FolderListPane(
                    folders = state.folders,
                    onPick = { vm.openFolder(it) },
                )
            }
        }
    }
}

@Composable
private fun FolderListPane(
    folders: List<FavFolder>,
    onPick: (FavFolder) -> Unit,
) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text(
                "选择收藏夹作为播放列表",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
            )
        }
        items(folders, key = { it.id }) { folder ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .clickable { onPick(folder) }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        folder.title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${folder.media_count} 个内容",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun TrackListPane(
    title: String,
    tracks: List<ListenVideoViewModel.ListenTrack>,
    onPick: (Int) -> Unit,
) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
            )
        }
        items(tracks.size) { index ->
            val track = tracks[index]
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .clickable { onPick(index) }
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AsyncImage(
                    model = track.cover.toCoverUrl(),
                    contentDescription = null,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentScale = ContentScale.Crop,
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        track.title,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (track.ownerName.isNotBlank()) {
                        Text(
                            track.ownerName,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 播放器面板：黑胶唱片 + 歌词 + 控制条。
 *
 * 黑胶旋转规格（BiliPai MusicArtworkRotationPolicy）：
 * - 24 秒一圈，LinearEasing（匀速，像真唱片）
 * - 只在播放时转，暂停时停在当前角度
 * - 系统动画关闭时不转
 */
@Composable
private fun ListenPlayerPane(
    state: ListenVideoViewModel.UiState,
    cues: List<SubtitleCue>,
    currentIndex: Int,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Long) -> Unit,
) {
    val track = state.tracks.getOrNull(state.playingIndex)
    val rimBrush = rememberCoverRimBrush()

    // 系统动画被关掉时（设置 → 无障碍 → 移除动画）不转唱片
    val animatorScale = android.provider.Settings.Global.getFloat(
        LocalContext.current.contentResolver,
        android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
        1f,
    )
    val reduceMotion = animatorScale == 0f

    val rotation = remember(track?.bvid) { Animatable(0f) }
    LaunchedEffect(state.isPlaying, track?.bvid, reduceMotion) {
        if (!state.isPlaying || reduceMotion) return@LaunchedEffect
        while (isActive) {
            rotation.animateTo(
                targetValue = rotation.value + 360f,
                animationSpec = tween(ARTWORK_ROTATION_MS, easing = LinearEasing),
            )
        }
    }

    Column(
        Modifier.fillMaxSize().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(16.dp))

        // 黑胶唱片：外圈唱片底盘 + 内圈封面（封面旋转）
        Box(
            Modifier
                .fillMaxWidth(0.78f)
                .aspectRatio(1f),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .background(Color(0xFF0A0A0A))
                    .border(1.dp, Color.White.copy(alpha = 0.08f), CircleShape),
            )
            Box(
                Modifier
                    .fillMaxWidth(0.68f)
                    .aspectRatio(1f)
                    .graphicsLayer { rotationZ = rotation.value }
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .border(1.dp, rimBrush, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                AsyncImage(
                    model = track?.cover.orEmpty().toCoverUrl(),
                    contentDescription = track?.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
                // 中心唱针孔
                Box(
                    Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF0A0A0A))
                        .border(1.dp, Color.White.copy(alpha = 0.25f), CircleShape),
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        Text(
            track?.title.orEmpty(),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        if (track?.ownerName?.isNotBlank() == true) {
            Spacer(Modifier.height(4.dp))
            Text(
                track.ownerName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }

        Spacer(Modifier.height(16.dp))

        // 进度条
        val duration = state.durationMs
        if (duration > 0L) {
            Slider(
                value = state.positionMs.toFloat().coerceIn(0f, duration.toFloat()),
                onValueChange = { onSeek(it.toLong()) },
                valueRange = 0f..duration.toFloat(),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    formatMs(state.positionMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    formatMs(duration),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        // 控制条
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            IconButton(onClick = onPrevious) {
                Icon(Icons.Filled.SkipPrevious, "上一首", Modifier.size(36.dp))
            }
            Spacer(Modifier.width(16.dp))
            Box(
                Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
                    .clickable { onTogglePlay() },
                contentAlignment = Alignment.Center,
            ) {
                if (state.isBuffering) {
                    CircularProgressIndicator(
                        Modifier.size(28.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Icon(
                        if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (state.isPlaying) "暂停" else "播放",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(32.dp),
                    )
                }
            }
            Spacer(Modifier.width(16.dp))
            IconButton(onClick = onNext) {
                Icon(Icons.Filled.SkipNext, "下一首", Modifier.size(36.dp))
            }
        }

        state.playError?.let { err ->
            Spacer(Modifier.height(8.dp))
            Text(
                err,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
        }

        Spacer(Modifier.height(12.dp))

        // 歌词（B 站 CC 字幕轨）
        LyricsPane(
            cues = cues,
            currentIndex = currentIndex,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * 歌词视图：整条时间轴滚动 + 当前行高亮。
 *
 * 数据来自 B 站 CC 字幕轨（com.example.piliai.playback.SubtitleController），
 * 不是第三方歌词源——B 站音乐类视频普遍带字幕，且零新增网络依赖。
 */
@Composable
private fun LyricsPane(
    cues: List<SubtitleCue>,
    currentIndex: Int,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()

    LaunchedEffect(currentIndex) {
        if (currentIndex >= 0 && cues.isNotEmpty()) {
            listState.animateScrollToItem((currentIndex - 2).coerceAtLeast(0))
        }
    }

    Box(modifier.fillMaxWidth()) {
        if (cues.isEmpty()) {
            Text(
                "暂无歌词（该视频没有 CC 字幕轨）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center).padding(16.dp),
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = listState,
                contentPadding = PaddingValues(vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                itemsIndexed(cues) { i, cue ->
                    val active = i == currentIndex
                    Text(
                        text = cue.content.replace("<br/>", "\n"),
                        style = if (active) {
                            MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.SemiBold,
                            )
                        } else {
                            MaterialTheme.typography.bodyMedium
                        },
                        color = if (active) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        },
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}

private fun formatMs(ms: Long): String {
    val total = ms / 1000
    val m = total / 60
    val s = total % 60
    return "%d:%02d".format(m, s)
}
