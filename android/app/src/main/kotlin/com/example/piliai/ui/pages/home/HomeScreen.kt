package com.example.piliai.ui.pages.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.piliai.data.model.VideoItem
import com.example.piliai.utils.toHttpsUrl

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

    // 今日推荐：进入首页后异步加载，不阻塞首屏
    val todayWatchViewModel: com.example.piliai.todaywatch.TodayWatchViewModel = viewModel()
    val todayWatchState by todayWatchViewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { todayWatchViewModel.loadIfNeeded() }

    // 触底自动加载下一页
    val shouldLoadMore by remember {
        derivedStateOf {
            val info = gridState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            info.totalItemsCount > 0 && last >= info.totalItemsCount - 6
        }
    }
    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore) viewModel.loadMore()
    }

    Scaffold(
        topBar = {
            // Kototoro 打磨：品牌渐变顶栏
            // Kototoro 风格顶栏：去掉粉蓝渐变与彩虹图标色，改用主题 surface +
            // 单一 onSurface 图标色；次要入口收进溢出菜单，主操作只留搜索。
            Surface(
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth().statusBarsPadding(),
            ) {
                TopAppBar(
                    title = {
                        Text(
                            "PiliAI",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    },
                    actions = {
                        // 审核215+用户版融合：全部入口一级图标平铺顶栏（含消息/我的）
                        IconButton(onClick = onMessageClick) {
                            Icon(Icons.Filled.Notifications, contentDescription = "消息",
                                tint = MaterialTheme.colorScheme.onSurface)
                        }
                        IconButton(onClick = onMineClick) {
                            Icon(Icons.Filled.Person, contentDescription = "我的",
                                tint = MaterialTheme.colorScheme.onSurface)
                        }
                        IconButton(onClick = onTodayWatchClick) {
                            Icon(Icons.Filled.AutoAwesome, contentDescription = "今日推荐",
                                tint = MaterialTheme.colorScheme.onSurface)
                        }
                        IconButton(onClick = onRankClick) {
                            Icon(Icons.Filled.EmojiEvents, contentDescription = "排行榜",
                                tint = MaterialTheme.colorScheme.onSurface)
                        }
                        IconButton(onClick = onHotMoreClick) {
                            Icon(Icons.Filled.Whatshot, contentDescription = "热门精选",
                                tint = MaterialTheme.colorScheme.onSurface)
                        }
                        IconButton(onClick = onZoneClick) {
                            Icon(Icons.Filled.Apps, contentDescription = "分区浏览",
                                tint = MaterialTheme.colorScheme.onSurface)
                        }
                        IconButton(onClick = onBangumiClick) {
                            Icon(Icons.Filled.Movie, contentDescription = "番剧",
                                tint = MaterialTheme.colorScheme.onSurface)
                        }
                        IconButton(onClick = onStoryClick) {
                            Icon(Icons.Filled.PlayCircleOutline, contentDescription = "竖屏推荐流",
                                tint = MaterialTheme.colorScheme.onSurface)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent
                    ),
                )
            }
        },
    ) { padding ->
        // 审核215：顶部可滑动分类条（B站 App 风格），点击直达对应页面
        var selectedTab by remember { androidx.compose.runtime.mutableIntStateOf(0) }
        val tabs = listOf(
            "推荐" to ({} as () -> Unit), // 当前页即推荐
            "热门" to onHotMoreClick,
            "排行" to onRankClick,
            "番剧" to onBangumiClick,
            "分区" to onZoneClick,
            "竖屏流" to onStoryClick,
        )
        ScrollableTabRow(
            selectedTabIndex = selectedTab,
            edgePadding = 12.dp,
            containerColor = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth(),
        ) {
            tabs.forEachIndexed { i, (label, onClick) ->
                Tab(
                    selected = selectedTab == i,
                    onClick = { selectedTab = i; onClick() },
                    text = { Text(label) },
                )
            }
        }
        PullToRefreshBox(
            isRefreshing = state is HomeUiState.Loading,
            onRefresh = { viewModel.refresh() },
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            when (val s = state) {
                is HomeUiState.Loading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                is HomeUiState.Success -> {
                    val topRcmd by viewModel.topRcmd.collectAsStateWithLifecycle()
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        state = gridState,
                        contentPadding = PaddingValues(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        // 顶部 Hero 轮播（Kototoro 风格：240dp 高、底部 20dp 圆角、
                        // 100dp 渐变遮罩、胶囊指示器）
                        if (topRcmd.isNotEmpty()) {
                            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                                HeroCarousel(
                                    items = topRcmd,
                                    onItemClick = { onVideoClick(it.bvid, it.cid) },
                                    modifier = Modifier.padding(vertical = 4.dp),
                                )
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
                    model = item.pic.ifBlank { item.cover }.toHttpsUrl(),
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
                            Brush.verticalGradient(
                                listOf(Color.Transparent, Color.Black.copy(alpha = 0.78f))
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

@Composable
private fun VideoCardItem(card: VideoItem, onClick: () -> Unit) {
    // 审核（丑根因）：原标题 Text 用 Box 默认 TopStart 直接压在封面上，
    // 无背景区分，既丑又难读。改为「封面 + 下方文字区」的标准卡片结构：
    // 播放量/时长作为角标叠在图上，标题置于下方分层 surface（AMOLED 灰阶）。
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp), // Kototoro 大圆角（原 12dp 太小）
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        elevation = androidx.compose.material3.CardDefaults.cardElevation(
            defaultElevation = 0.dp,
        ),
    ) {
        Column {
            // 封面 + 角标（播放量、时长）
            Box {
                AsyncImage(
                    model = coil.request.ImageRequest.Builder(androidx.compose.ui.platform.LocalContext.current)
                        .data(card.pic.toHttpsUrl())
                        .crossfade(180)
                        .build(),
                    contentDescription = card.title,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                    contentScale = ContentScale.Crop,
                )
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(6.dp)
                        .background(
                            Color.Black.copy(alpha = 0.55f),
                            RoundedCornerShape(4.dp)
                        )
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.PlayCircleOutline,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(3.dp))
                    Text(
                        text = card.viewCountText,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                    )
                }
                if (card.durationText.isNotBlank()) {
                    Text(
                        text = card.durationText,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(6.dp)
                            .background(
                                Color.Black.copy(alpha = 0.55f),
                                RoundedCornerShape(4.dp)
                            )
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                    )
                }
            }
            // 下方文字区：标题 + UP 主/描述
            Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                Text(
                    text = card.title,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(4.dp))
                // 审核223：UP 主行（头像 + 名称）——B站 App 同款布局
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (card.authorFace.isNotBlank()) {
                        AsyncImage(
                            model = card.authorFace.toHttpsUrl(),
                            contentDescription = null,
                            modifier = Modifier
                                .size(18.dp)
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
    }
}
