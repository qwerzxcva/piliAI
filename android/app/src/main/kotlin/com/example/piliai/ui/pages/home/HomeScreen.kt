package com.example.piliai.ui.pages.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.background
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
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
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
        // 审核298：contentWindowInsets 只保 bottom。top 由顶栏自己的
        // statusBarsPadding() 消费——否则 body 也会拿到 top inset，
        // 顶栏下方多出一截空白（双倍留白）。
        contentWindowInsets = WindowInsets(0.dp, 0.dp, 0.dp, 0.dp),
        topBar = {
            // 审核234：BiliPai 式顶栏——搜索胶囊为主视觉 + 右侧仅消息/我的；
            // 排行/分区/番剧/竖屏流等入口全部下沉到分类条（不再 9 图标铺满）
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    // 审核298（真 bug）：这里必须有 statusBarsPadding()。
                    // 审核241 删掉它的理由（「Scaffold contentWindowInsets 默认含
                    // systemBars，body 会消费」）是错的——**自定义 Column 作为
                    // topBar 时 Scaffold 不会自动加 insets**，只有 MD3 TopAppBar
                    // 内部才处理。删掉后顶栏直接画到状态栏底下，用户看到的就是
                    // 「大黑边挡住状态栏」。
                    .statusBarsPadding()
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
        // 审核282：分类条。原来是「两个 SegmentedPillRow 塞进一个横向 LazyRow」——
        // 7 个 tab 拆成 4+3 两组并列，语义混乱且整行只能横滑，是首页「廉价感」
        // 的直接来源之一。改为单条可横滑的分段控件：一组胶囊、一个容器、
        // 选中项 primaryContainer 填充（Kototoro/BiliPai 的做法）。
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
        com.example.piliai.ui.components.CategoryPillStrip(
            labels = tabs.map { it.first },
            selectedIndex = selectedTab,
            onSelect = { i -> selectedTab = i; tabs[i].second() },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        )
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

            // 审核283：网格列数按可用宽度算（Loading 骨架屏与 Success 网格共用），
            // 移植 Kototoro resolveGridColumnCount（KototoroContentListScreen.kt:742）。
            // 手机竖屏仍是 2 列；平板/折叠屏展开时自动到 3~4 列，不会出现
            // 「大屏上卡片被拉成巨卡」的廉价观感。目标卡宽取
            // moviePosterCardStyle 下限 160dp，卡距 8dp、页边 12dp。
            var homeGridColumns by remember { androidx.compose.runtime.mutableIntStateOf(2) }
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                homeGridColumns = resolveGridColumnCount(
                    availableWidth = maxWidth - 24.dp,
                    targetItemWidth = 160.dp,
                    spacing = 8.dp,
                )
            }

            when (val s = state) {
                is HomeUiState.Loading -> {
                    // 审核234：骨架屏替代转圈（BiliPai HomeFeedSkeletonCard 观感，感知加载更快）
                    // 审核283：列数与成功态一致，避免加载完成时网格跳变
                    com.example.piliai.ui.components.SkeletonVideoGrid(
                        modifier = Modifier.fillMaxSize(),
                        columns = homeGridColumns,
                    )
                }
                is HomeUiState.Success -> {
                    val topRcmd by viewModel.topRcmd.collectAsStateWithLifecycle()
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(homeGridColumns),
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
                        // 审核294：空态——推荐过滤把所有视频筛掉时，原来用户看到
                        // 只有 SectionHeader + 一片空白，分不清「没加载出来」还是
                        // 「被过滤了」。给明确说明 + 去调整过滤的入口。
                        if (s.items.isEmpty()) {
                            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                                Column(
                                    Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    Text(
                                        "没有符合条件的视频",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        "推荐过滤条件可能过于严格\n可在 设置 → 推荐过滤 中放宽",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                    )
                                }
                            }
                        }
                        items(s.items, key = { it.bvid }) { card ->
                            com.example.piliai.ui.components.VideoGridCard(
                                title = card.title,
                                coverUrl = card.pic.toCoverUrl(),
                                durationText = card.durationText,
                                viewCountText = card.viewCountText,
                                danmakuCountText = card.danmakuCountText,
                                authorName = card.author,
                                authorFaceUrl = card.authorFace.toCoverUrl(),
                                onClick = { onVideoClick(card.bvid, card.cid) },
                            )
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
 * 审核283：按可用宽度算网格列数（移植 Kototoro resolveGridColumnCount，
 * KototoroContentListScreen.kt:742）。
 *
 * `floor((availableWidth + spacing) / (minCellWidth + spacing))`，其中
 * minCellWidth = targetItemWidth * 0.94（Kototoro GridColumnMinFitRatio，:113）——
 * 允许卡片比目标宽度略窄，避免「差一点就能多放一列」的浪费。
 */
private fun resolveGridColumnCount(
    availableWidth: androidx.compose.ui.unit.Dp,
    targetItemWidth: androidx.compose.ui.unit.Dp,
    spacing: androidx.compose.ui.unit.Dp,
): Int {
    val minFitRatio = 0.94f
    val minCellWidth = targetItemWidth.value * minFitRatio
    val columns = kotlin.math.floor(
        (availableWidth.value + spacing.value) / (minCellWidth + spacing.value)
    ).toInt()
    return columns.coerceAtLeast(2)
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
    // 审核297：Hero 封面补 rim 描边 + 阴影——全站封面规格统一后，Hero 作为
    // 最大的封面元素反而漏了，在暗色主题下边缘发糊、观感与下面的网格卡割裂。
    val rimBrush = com.example.piliai.ui.theme.rememberCoverRimBrush()
    val coverShape = RoundedCornerShape(20.dp)
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
                    .shadow(2.dp, coverShape, clip = false)
                    .clip(coverShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .border(0.5.dp, rimBrush, coverShape)
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
                        color = Color.White,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        // 审核298：补文字阴影。Hero 标题压在渐变遮罩上，
                        // 亮封面时白字会糊成一片（与 VideoGridCard 的 overlay
                        // 标题保持同一规格）。
                        style = MaterialTheme.typography.titleSmall.copy(
                            shadow = Shadow(
                                color = Color.Black.copy(alpha = 0.75f),
                                offset = Offset(0f, 1.5f),
                                blurRadius = 5f,
                            )
                        ),
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
