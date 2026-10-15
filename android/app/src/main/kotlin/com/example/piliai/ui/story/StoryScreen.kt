package com.example.piliai.ui.story

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil.compose.AsyncImage
import com.example.piliai.data.model.VideoItem
import com.example.piliai.utils.toCoverUrl

/**
 * 审核318：竖屏沉浸式推荐流（重写）。
 *
 * 旧实现（178 行）只有封面图 + 标题 + 「全屏播放」按钮，点一下跳详情页播放器，
 * 竖屏流本身不播放——「抖音式上下滑」名不副实。BiliPai PortraitVideoPager
 * 是 4524 行 + 12 个策略文件，完整移植不现实；这里按核心体验对齐：
 *
 * - 每页自带 SurfaceView + Media3Backend，滑到即播（取流/CDN/缓冲全部复用
 *   播放器的既有能力，见 [StoryPlaybackViewModel]）
 * - 上滑预取下一页，滑到当前页才起播（避免一次起播 N 个 ExoPlayer）
 * - 播完自动进下一条
 * - 单击暂停/继续，双击点赞由调用方接管
 *
 * 视觉按 Kototoro 规格：纯黑底、封面全屏 Crop、底部信息区渐变遮罩
 * （PiliCoverScrimStops 6 段色标，与全站封面同一份）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StoryScreen(
    onBack: () -> Unit,
    onVideoClick: (bvid: String, cid: Long) -> Unit = { _, _ -> },
    onUserClick: (Long) -> Unit = {},
    vm: StoryPlaybackViewModel = viewModel(
        factory = viewModelFactory { initializer { StoryPlaybackViewModel() } }
    ),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val pagerState = rememberPagerState(pageCount = { state.items.size })

    // 滑到当前页才起播（切页即取消上一个请求，见 VM.play 内的 loadJob）
    LaunchedEffect(pagerState, state.items) {
        snapshotFlow { pagerState.currentPage }.collect { page ->
            val item = state.items.getOrNull(page) ?: return@collect
            vm.play(item)
        }
    }

    // 距底部 3 条预取下一页
    val nearEnd by remember {
        derivedStateOf { pagerState.currentPage >= state.items.size - 3 }
    }
    LaunchedEffect(nearEnd) { if (nearEnd) vm.loadMore() }

    // 播完自动下一条
    LaunchedEffect(state.playingBvid, state.isPlaying) {
        if (state.items.isNotEmpty() && !state.isPlaying &&
            state.playError == null && state.playingBvid.isNotBlank() &&
            state.durationMs > 0L && state.positionMs >= state.durationMs - 500L
        ) {
            val next = pagerState.currentPage + 1
            if (next < state.items.size) {
                pagerState.animateScrollToPage(next)
            } else {
                vm.loadMore()
            }
        }
    }

    // 退到后台暂停（与详情页 pauseOnMinimize 语义一致）
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) vm.onAppBackground()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (state.items.isEmpty() && state.loading) {
            CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
        }
        state.error?.let { err ->
            if (state.items.isEmpty()) {
                Column(
                    Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(err, color = Color.White)
                    Text("重试", color = Color.White, modifier = Modifier
                        .background(Color.White.copy(alpha = 0.2f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .clickable { vm.loadMore() })
                }
            }
        }
        if (state.items.isNotEmpty()) {
            VerticalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                val v = state.items[page]
                val active = pagerState.currentPage == page
                StoryPage(
                    item = v,
                    active = active,
                    playingBvid = state.playingBvid,
                    isPlaying = state.isPlaying,
                    isBuffering = state.isBuffering,
                    durationMs = state.durationMs,
                    positionMs = state.positionMs,
                    playError = state.playError,
                    onSurfaceReady = { surface -> vm.attachSurface(surface) },
                    onTogglePlay = { vm.togglePlayPause() },
                    onOpenDetail = { onVideoClick(v.bvid, v.cid) },
                    onOpenUser = { if (v.authorMid > 0L) onUserClick(v.authorMid) },
                )
            }
        }

        // 顶部返回（透明叠在画面上，不用 Scaffold topBar——竖屏流要全屏沉浸）
        IconButton(
            onClick = onBack,
            modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = Color.White)
        }
    }
}

/**
 * 竖屏流单页：SurfaceView 画面 + 底部信息 + 右侧互动。
 *
 * 关键点：SurfaceView 只给当前页 attach（见 [attachSurface]），翻页后旧页的
 * Surface 被 detach，否则 ExoPlayer 的 surface 指向已不可见的 View。
 */
@Composable
private fun StoryPage(
    item: VideoItem,
    active: Boolean,
    playingBvid: String,
    isPlaying: Boolean,
    isBuffering: Boolean,
    durationMs: Long,
    positionMs: Long,
    playError: String?,
    onSurfaceReady: (android.view.Surface?) -> Unit,
    onTogglePlay: () -> Unit,
    onOpenDetail: () -> Unit,
    onOpenUser: () -> Unit,
) {
    Box(
        Modifier.fillMaxSize().background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        // 画面：Media3 的 SurfaceView。只有当前页 attach surface，其它页只显示封面，
        // 避免一次持多个 Surface 导致解码器被抢占、画面黑屏。
        if (active) {
            AndroidView(
                factory = { ctx ->
                    android.view.SurfaceView(ctx).apply {
                        holder.addCallback(object : android.view.SurfaceHolder.Callback {
                            override fun surfaceCreated(holder: android.view.SurfaceHolder) {
                                onSurfaceReady(holder.surface)
                            }
                            override fun surfaceChanged(
                                holder: android.view.SurfaceHolder, format: Int, w: Int, h: Int,
                            ) = Unit
                            override fun surfaceDestroyed(holder: android.view.SurfaceHolder) {
                                onSurfaceReady(null)
                            }
                        })
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            // 非当前页：只出封面（滑过时有画面，滑回来再起播）
            AsyncImage(
                model = item.pic.toCoverUrl(),
                contentDescription = item.title,
                modifier = Modifier.fillMaxSize(),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            )
        }

        // 缓冲中 / 起播失败
        if (active && isBuffering && playingBvid == item.bvid) {
            CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
        }
        if (active && playError != null && playingBvid == item.bvid) {
            Column(
                Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(playError, color = Color.White)
                Text(
                    "查看详情",
                    color = Color.White,
                    modifier = Modifier
                        .background(Color.White.copy(alpha = 0.2f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .clickable { onOpenDetail() },
                )
            }
        }

        // 单击画面：暂停/继续
        Box(
            Modifier
                .fillMaxSize()
                .clickable { onTogglePlay() },
        )

        // 中央播放按钮（暂停时显示）
        if (active && !isPlaying && playError == null && playingBvid == item.bvid) {
            Icon(
                Icons.Filled.PlayArrow,
                contentDescription = "播放",
                tint = Color.White.copy(alpha = 0.85f),
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(64.dp)
                    .background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(50))
                    .padding(12.dp),
            )
        }

        // 底部信息区：渐变遮罩 + 标题/UP 主 + 进度条
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(180.dp)
                .background(
                    androidx.compose.ui.graphics.Brush.verticalGradient(
                        colorStops = com.example.piliai.ui.theme.PiliCoverScrimStops,
                    )
                )
        )
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text(
                text = "@${item.author}",
                style = androidx.compose.material3.MaterialTheme.typography.labelLarge,
                color = Color.White.copy(alpha = 0.9f),
                modifier = Modifier.clickable { onOpenUser() },
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = item.title,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
            // 进度条（当前页才显示，且时长已就绪）
            if (active && durationMs > 0L) {
                val fraction = (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth().height(2.dp),
                    color = Color.White,
                    trackColor = Color.White.copy(alpha = 0.25f),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "${formatMs(positionMs)} / ${formatMs(durationMs)}",
                    style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.75f),
                )
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
