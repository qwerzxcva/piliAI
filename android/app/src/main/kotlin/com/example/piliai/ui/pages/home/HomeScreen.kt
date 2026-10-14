package com.example.piliai.ui.pages.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayCircleOutline
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Surface
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.piliai.data.model.VideoItem
import com.example.piliai.utils.toHttpsUrl
import com.example.piliai.utils.toCoverUrl
import com.example.piliai.ui.theme.PiliCoverScrimStops
import com.example.piliai.ui.theme.rememberCoverRimBrush

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onVideoClick: (bvid: String, cid: Long) -> Unit = { _, _ -> },
    onSearchClick: () -> Unit = {},
    onMessageClick: () -> Unit = {},
    onMineClick: () -> Unit = {},
    onRankClick: () -> Unit = {},
    onHotMoreClick: () -> Unit = {},
    onZoneClick: () -> Unit = {},
    onBangumiClick: () -> Unit = {},
    onStoryClick: () -> Unit = {},
    onTodayWatchClick: () -> Unit = {},
    viewModel: HomeViewModel = viewModel(
        factory = run {
            val app = androidx.compose.ui.platform.LocalContext.current.applicationContext
            object : androidx.lifecycle.ViewModelProvider.Factory {
                override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                    @Suppress("UNCHECKED_CAST")
                    return HomeViewModel(storage = com.example.piliai.utils.StorageManager.getInstance(app)) as T
                }
            }
        },
    ),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val gridState = rememberLazyGridState()
    // 审核241：下拉刷新指示跟随仓库真实 loading，避免「刷新完圈还在转」
    val repoRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val feedSource by viewModel.feedSource.collectAsStateWithLifecycle()
    // 审核241（P0-2）：触底加载必须有 hasMore 兜底。否则失败后 loadingMore 释放、
    // shouldLoadMore 仍为 true，反复触底会连续打请求且用户看不到任何提示。
    val hasMore by viewModel.hasMore.collectAsStateWithLifecycle()
    val loadMoreError by viewModel.loadMoreError.collectAsStateWithLifecycle()

    // 今日推荐：进入首页后异步加载，不阻塞首屏
    val todayWatchViewModel: com.example.piliai.todaywatch.TodayWatchViewModel = viewModel()
    val todayWatchState by todayWatchViewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { todayWatchViewModel.loadIfNeeded() }

    // 触底自动加载下一页
    // 审核241（P0-2）：追加 hasMore 守卫，失败后不再反复请求
    val shouldLoadMore by remember {
        derivedStateOf {
            val info = gridState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            info.totalItemsCount > 0 && hasMore && last >= info.totalItemsCount - 4
        }
    }
    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore) viewModel.loadMore()
    }

    // 审核241（P0-2）：加载更多失败给一次性提示（此前静默，用户以为到底了）
    val homeContext = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(loadMoreError) {
        val msg = loadMoreError ?: return@LaunchedEffect
        android.widget.Toast.makeText(homeContext, msg, android.widget.Toast.LENGTH_SHORT).show()
        viewModel.consumeLoadMoreError()
    }

    Scaffold(
        topBar = {
            // 审核234：BiliPai 式顶栏——搜索胶囊为主视觉 + 右侧仅消息/我的；
            // 排行/分区/番剧/竖屏流等入口全部下沉到分类条（不再 9 图标铺满）
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    // 审核241（P2-1）：删掉这里的 statusBarsPadding()。
                    // MainActivity 已 enableEdgeToEdge()，Scaffold 的 contentWindowInsets
                    // 默认含 systemBars，body 又消费一次 padding → 顶部双倍状态栏留白。
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                com.example.piliai.ui.components.SearchPill(
                    hint = "搜索视频、UP主",
                    onClick = onSearchClick,
                    trailing = {
                        // 审核241（P1-5）：触达区域从 32dp 提到 40dp（48dp 建议值内的
                        // 折中：SearchPill 高 40dp，再大就要加高整条顶栏）
                        IconButton(onClick = onMessageClick, modifier = Modifier.size(40.dp)) {
                            Icon(Icons.Filled.Notifications, contentDescription = "消息",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp))
                        }
                        IconButton(onClick = onMineClick, modifier = Modifier.size(40.dp)) {
                            Icon(Icons.Filled.Person, contentDescription = "我的",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp))
                        }
                    },
                )
            }
        },
    ) { padding ->
        // 审核234：浮动胶囊分段分类条（BiliPai HomeTopTabFloatingDock 风格），
        // 顶栏下沉的入口（今日推荐/排行/热门/分区/番剧/竖屏流）全部收进这里
        var selectedTab by remember { androidx.compose.runtime.mutableIntStateOf(0) }
        val tabs = remember {
            listOf(
                // 审核241（P1-4）：「推荐」原来是空实现，点一下只高亮不做事，
                // 用户以为页面坏了。改为回到顶部并刷新当前流，与其他胶囊语义一致。
                "推荐" to ({ viewModel.refresh() } as () -> Unit),
                "热门" to onHotMoreClick,
                "排行" to onRankClick,
                "番剧" to onBangumiClick,
                "分区" to onZoneClick,
                "竖屏流" to onStoryClick,
                "今日" to onTodayWatchClick,
            )
        }
        androidx.compose.foundation.lazy.LazyRow(
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            item {
                com.example.piliai.ui.components.SegmentedPillRow(
                    labels = tabs.take(4).map { it.first },
                    selectedIndex = if (selectedTab < 4) selectedTab else -1,
                    onSelect = { i -> selectedTab = i; tabs[i].second() },
                )
            }
            item {
                com.example.piliai.ui.components.SegmentedPillRow(
                    labels = tabs.drop(4).map { it.first },
                    selectedIndex = if (selectedTab >= 4) selectedTab - 4 else -1,
                    onSelect = { i -> selectedTab = i + 4; tabs[i + 4].second() },
                )
            }
        }
        PullToRefreshBox(
            isRefreshing = repoRefreshing,
            onRefresh = { viewModel.refresh() },
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            // 审核279：显示当前生效的数据源。原来只在降级为热门时提示，
            // 用户切到「入站必刷/每周必看」后看不出当前是哪档。
            if (feedSource != com.example.piliai.data.repository.HomeRepository.FeedSource.RECOMMEND &&
                state is HomeUiState.Success
            ) {
                val label = when (feedSource) {
                    com.example.piliai.data.repository.HomeRepository.FeedSource.POPULAR ->
                        "全站热门"
                    com.example.piliai.data.repository.HomeRepository.FeedSource.PRECIOUS ->
                        "入站必刷"
                    com.example.piliai.data.repository.HomeRepository.FeedSource.WEEKLY ->
                        "每周必看"
                    else -> ""
                }
                androidx.compose.material3.Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text(
                        "当前：$label（设置 → 首页数据源 可切换）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }

            when (val s = state) {
                is HomeUiState.Loading -> {
                    // 审核234：骨架屏替代转圈（BiliPai HomeFeedSkeletonCard 观感，感知加载更快）
                    com.example.piliai.ui.components.SkeletonVideoGrid(Modifier.fillMaxSize())
                }
                is HomeUiState.Success -> {
                    val topRcmd by viewModel.topRcmd.collectAsStateWithLifecycle()
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        state = gridState,
                        // 审核271/280：卡距 8dp 对齐 Kototoro GridSpacing
                        // （KototoroContentListScreen.kt:117），页边 12dp 对齐
                        // AppLayoutTokens.compactItemHorizontalPadding。
                        // 封面 16:9 不变（视频内容决定，不照搬漫画的竖封面）。
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        // 顶部 Hero 轮播（Kototoro 风格：240dp 高、底部 20dp 圆角、
                        // 100dp 渐变遮罩、胶囊指示器）
                        if (topRcmd.isNotEmpty()) {
                            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                                Column {
                                    com.example.piliai.ui.components.SectionHeader(
                                        title = "为你推荐",
                                        count = topRcmd.size,
                                        modifier = Modifier.padding(top = 4.dp),
                                    )
                                    HeroCarousel(
                                        items = topRcmd,
                                        onItemClick = { onVideoClick(it.bvid, it.cid) },
                                        modifier = Modifier.padding(vertical = 2.dp),
                                    )
                                }
                            }
                        }
                        // 今日推荐 Section（全宽，成功才显示）
                        val tw = todayWatchState
                        if (tw is com.example.piliai.todaywatch.TodayWatchViewModel.UiState.Success) {
                            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                                com.example.piliai.todaywatch.TodayWatchSection(
                                    plan = tw.plan,
                                    onVideoClick = { bvid -> onVideoClick(bvid, 0L) },
                                    onSeeAll = onTodayWatchClick,
                                    onDislike = { v -> todayWatchViewModel.dislike(v.bvid, v.ownerMid) },
                                    modifier = Modifier.padding(vertical = 4.dp),
                                )
                            }
                        }
                        // 审核280：视频流加 Section 标题——此前 Hero 和网格直接裸奔，
                        // 没有区块层级，是「廉价感」的来源之一。
                        item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                            com.example.piliai.ui.components.SectionHeader(
                                title = com.example.piliai.data.repository.HomeRepository
                                    .prefLabel(viewModel.preferredSource),
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                        items(s.items, key = { it.bvid }) { card ->
                            VideoCardItem(card, onClick = { onVideoClick(card.bvid, card.cid) })
                        }
                    }
                }
                is HomeUiState.Error -> {
                    Box(
                        Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                "加载失败：${s.message}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                            Spacer(Modifier.height(12.dp))
                            androidx.compose.material3.Button(onClick = { viewModel.refresh() }) {
                                androidx.compose.material3.Text("重试")
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Kototoro 风格 Hero 轮播：
 * - 高度 240dp、底部圆角 20dp
 * - 100dp 渐变遮罩保证标题可读
 * - 胶囊指示器（选中 16×6，未选中 6×6）
 */
@Composable
private fun HeroCarousel(
    modifier: Modifier = Modifier,
    items: List<com.example.piliai.data.model.TopRcmdItem>,
    onItemClick: (com.example.piliai.data.model.TopRcmdItem) -> Unit,
) {
    val pagerState = rememberPagerState(pageCount = { items.size })
    Column(modifier = modifier.fillMaxWidth()) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxWidth().height(240.dp),
            pageSpacing = 0.dp,
        ) { page ->
            val item = items[page]
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .clickable { onItemClick(item) }
            ) {
                AsyncImage(
                    model = item.pic.ifBlank { item.cover }.toCoverUrl(),
                    contentDescription = item.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
                // 100dp 渐变遮罩（底部）
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(100.dp)
                        .background(
                            // 审核280：改用 Kototoro HomeHeroArtworkScrim 规格
                            // （HomeUi.kt:199）——20%→56% 双段，比原来单段到 78%
                            // 更自然：顶部不完全透明，压住亮封面的杂色。
                            Brush.verticalGradient(
                                0f to Color.Black.copy(alpha = 0.20f),
                                1f to Color.Black.copy(alpha = 0.56f)
                            )
                        )
                )
                Column(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = Color.White,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val upName = item.owner?.name ?: item.name
                    if (upName.isNotBlank()) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = upName,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.82f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        // 胶囊指示器
        if (items.size > 1) {
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                repeat(items.size) { i ->
                    val selected = pagerState.currentPage == i
                    Box(
                        Modifier
                            .padding(horizontal = 3.dp)
                            .width(if (selected) 16.dp else 6.dp)
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(
                                if (selected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f)
                            )
                    )
                }
            }
        }
    }
}

/**
 * 审核280：首页视频卡。视觉语言对齐 Kototoro 源码级规格：
 * - 封面：0.5dp rim 渐变描边 + 2dp 阴影 + surfaceVariant 底色
 *   （rememberCoverRimBorderBrush / TabletPosterCover，SharedContentCardBadges.kt:246）
 * - 标题压在封面底部 overlay（CompactGridTitleOverlay，SharedPosterCover.kt:63），
 *   不再放封面外下方：省垂直空间、层级更清晰，是 Kototoro 的标志性观感
 * - 遮罩用 6 段色标（末端黑 72%），标题带文字阴影保证亮封面可读
 * - UP 主行留在封面外下方（视频 App 需要作者归属，Kototoro 的副标题同理）
 */
@Composable
private fun VideoCardItem(card: VideoItem, onClick: () -> Unit) {
    val rimBrush = rememberCoverRimBrush()
    val coverShape = RoundedCornerShape(12.dp)
    val titleSize = 13.sp
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                // 2dp 阴影先于 clip：阴影按封面形状外扩，clip=false 才能透出来
                .shadow(2.dp, coverShape, clip = false)
                .clip(coverShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .border(0.5.dp, rimBrush, coverShape),
        ) {
            AsyncImage(
                model = coil.request.ImageRequest.Builder(androidx.compose.ui.platform.LocalContext.current)
                    .data(card.pic.toCoverUrl())
                    .crossfade(180)
                    .build(),
                contentDescription = card.title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            // 播放量 + 弹幕：左上角一排（Kototoro 徽标同样叠在封面上层）
            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .background(
                        Color.Black.copy(alpha = 0.45f),
                        RoundedCornerShape(4.dp)
                    )
                    .padding(horizontal = 5.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.PlayCircleOutline,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(13.dp),
                )
                Spacer(Modifier.width(3.dp))
                Text(
                    text = card.viewCountText,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                )
                Spacer(Modifier.width(6.dp))
                Icon(
                    Icons.Filled.ChatBubbleOutline,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(13.dp),
                )
                Spacer(Modifier.width(3.dp))
                Text(
                    text = card.danmakuCountText,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                )
            }
            // 时长：右上角
            if (card.durationText.isNotBlank()) {
                Text(
                    text = card.durationText,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .background(
                            Color.Black.copy(alpha = 0.45f),
                            RoundedCornerShape(4.dp)
                        )
                        .padding(horizontal = 5.dp, vertical = 3.dp),
                )
            }
            // 底部遮罩 + overlay 标题（CompactGridTitleOverlay 移植）
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(46.dp)
                    .background(Brush.verticalGradient(colorStops = PiliCoverScrimStops)),
            )
            Text(
                text = card.title,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelMedium.copy(
                    fontSize = titleSize,
                    lineHeight = (titleSize.value + 3.5f).sp,
                    fontWeight = FontWeight.SemiBold,
                    shadow = Shadow(
                        color = Color.Black.copy(alpha = 0.75f),
                        offset = Offset(0f, 1.5f),
                        blurRadius = 5f,
                    ),
                ),
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // 封面外仅保留 UP 主行：头像 + 名称（视频内容需要作者归属）
        Row(
            modifier = Modifier.padding(start = 2.dp, top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (card.authorFace.isNotBlank()) {
                AsyncImage(
                    model = card.authorFace.toCoverUrl(),
                    contentDescription = null,
                    modifier = Modifier
                        .size(16.dp)
                        .clip(CircleShape),
                    contentScale = ContentScale.Crop,
                )
                Spacer(Modifier.width(5.dp))
            }
            Text(
                text = card.author,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
