package com.example.piliai.ui.navigation

import androidx.compose.ui.graphics.Color
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.net.toUri
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.NavType
import androidx.navigation.navArgument
import com.example.piliai.ui.comments.CommentScreen
import com.example.piliai.ui.library.FavMediaScreen
import com.example.piliai.ui.library.FavoritesScreen
import com.example.piliai.ui.library.HistoryScreen
import com.example.piliai.ui.live.LiveRoomScreen
import com.example.piliai.ui.login.LoginScreen
import com.example.piliai.ui.main.MainTab
import com.example.piliai.ui.main.SearchScreen
import com.example.piliai.ui.pages.dynamics.DynamicsScreen
import com.example.piliai.ui.pages.home.HomeScreen
import com.example.piliai.ui.pages.mine.MineScreen
import com.example.piliai.playback.VideoPlayerScreen

/**
 * App Navigation - Jetpack Compose
 * Replaces Flutter GetX router
 */
sealed class Screen(val route: String) {
    object Home : Screen("home")
    object Search : Screen("search?query={query}") {
        fun createRoute(query: String = "") = if (query.isBlank()) "search" else "search?query=${android.net.Uri.encode(query)}"
    }
    object Dynamics : Screen("dynamics")
    object Message : Screen("message")
    object Profile : Screen("profile")
    object Login : Screen("login")
    object Favorites : Screen("favorites/{mid}") {
        fun createRoute(mid: Long) = "favorites/$mid"
    }
    object FavMedia : Screen("favmedia/{mediaId}") {
        fun createRoute(mediaId: Long) = "favmedia/$mediaId"
    }
    object History : Screen("history")
    object Story : Screen("story")  // 审核轮207：竖屏沉浸式推荐流（BiliPai story 移植）
    // 审核319：听视频模式（BiliPai feature/audio 移植）。收藏夹即播放列表，
    // 歌词用 B 站 CC 字幕轨。mid 由调用方传入（收藏夹接口需要 up_mid）。
    object ListenVideo : Screen("listen_video?mid={mid}") {
        fun createRoute(mid: Long) = "listen_video?mid=$mid"
    }
    object TodayWatch : Screen("today_watch")  // 今日推荐独立页（today-watch 移植）
    object Settings : Screen("settings")
    object VideoPlayer : Screen("video/{bvid}?cid={cid}&local={local}") {
        const val CID_ARG = "cid"
        fun createRoute(bvid: String, cid: Long = 0L) = "video/$bvid?cid=$cid"
    }
    object LiveRoom : Screen("live/{roomId}") {
        fun createRoute(roomId: String) = "live/$roomId"
    }
    object Comment : Screen("comment/{bvid}") {
        fun createRoute(bvid: String) = "comment/$bvid"
    }
    object Member : Screen("member/{mid}") {
        fun createRoute(mid: Long) = "member/$mid"
    }
    object ToView : Screen("toview")
    object FollowList : Screen("followlist/{mid}?type={type}") {
        fun createRoute(mid: Long, followers: Boolean) =
            "followlist/$mid?type=${if (followers) "1" else "0"}"
    }
    object Bangumi : Screen("bangumi?seasonId={seasonId}&epId={epId}") {
        fun createRoute(seasonId: Long = 0L, epId: Long = 0L) =
            "bangumi?seasonId=$seasonId&epId=$epId"
    }
    object LiveList : Screen("livelist")
    object LiveArea : Screen("livearea")
    object HotMore : Screen("hotmore")
    object PgcIndex : Screen("pgcindex")
    object Timeline : Screen("timeline")
    object Zone : Screen("zone")
    object Rank : Screen("rank")
    object Article : Screen("article/{articleId}") {
        fun createRoute(id: Long) = "article/$id"
    }
    object DanmakuBlock : Screen("danmakublock")
    object Sessions : Screen("sessions")

    object Downloads : Screen("downloads")
    object Subscribe : Screen("subscribe") {
        fun createRoute() = "subscribe"

        /**
         * 审核261：带初始搜索词的订阅页。
         * Bangumi 详情页用它在订阅源里搜同名番剧（用户核心场景：
         * 在 Bangumi 看介绍 -> 去订阅源找片源）。
         */
        fun createSearchRoute(query: String) =
            "subscribe?q=${android.net.Uri.encode(query)}"
    }
    object BangumiSubject : Screen("bgmsubject/{id}") {
        fun createRoute(id: Long) = "bgmsubject/$id"
    }
    object SourcePlay : Screen("sourceplay/{requestId}?title={title}") {
        fun createRoute(requestId: String, title: String) =
            "sourceplay/${android.net.Uri.encode(requestId)}?title=${android.net.Uri.encode(title)}"
    }
    /**
     * 直链播放（订阅源 / 外部链接）
     *
     * 审核轮13：URL **不能放在路径分段**里。
     * 即使 Uri.encode 后 "/" 变成 "%2F"，Navigation 在匹配 / 解码过程中仍可能
     * 把它还原成分段分隔符，导致路由匹配失败（表现为「点了没跳转」）。
     * 因此改为纯 query 参数：directplay?url=...&title=...&cover=...
     */
    object DirectPlay : Screen("directplay?url={url}&title={title}&cover={cover}") {
        fun createRoute(url: String, title: String = "", cover: String = "") =
            "directplay?url=${android.net.Uri.encode(url)}" +
                "&title=${android.net.Uri.encode(title)}" +
                "&cover=${android.net.Uri.encode(cover)}"
    }
}

@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    val sourcePlaybackSession: com.example.piliai.playback.SourcePlaybackSession =
        androidx.lifecycle.viewmodel.compose.viewModel()
    val ctx = androidx.compose.ui.platform.LocalContext.current

    // 审核（关键修复）：MainActivity 原先使用 MainApp，其 NavHost 只有 6 条路由且
    // HomeScreen 的 onSearchClick 等回调全为空实现——导致搜索/排行/分区/番剧/
    // 推荐流/今日推荐等按钮点击无反应，且 25+ 个页面不可达。
    // 现改为以本文件（完整路由图）为唯一入口，并在此挂载三 tab 底部导航。
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val tabRouteOf: (MainTab) -> String = { tab ->
        when (tab) {
            MainTab.HOME -> Screen.Home.route
            MainTab.DYNAMICS -> Screen.Dynamics.route
            // 审核318：直播列表页此前无任何入口（路由注册了但没人 navigate），
            // 因此升为一级 tab。参考 BiliPai BottomNavItem.LIVE。
            MainTab.LIVE -> Screen.LiveList.route
            MainTab.MINE -> Screen.Profile.route
        }
    }
    // 审核298（真 bug）：原来只判断「当前路由是三个 tab 之一」，导致进了二级页
    // （个人中心/收藏/历史/…）后 dock 消失，用户再也回不到主 tab——
    // 「点首页没反应、回不去了」。改为：只有明确的「全屏页」才隐藏 dock。
    val fullScreenRoutes = setOf(
        Screen.VideoPlayer.route,
        Screen.Login.route,
        Screen.Story.route,
        Screen.LiveRoom.route,
        Screen.Search.route,
    )
    val isFullScreen = currentRoute != null && fullScreenRoutes.any {
        currentRoute == it || currentRoute.startsWith(it.substringBefore('{'))
    }
    val showDock = !isFullScreen

    Scaffold(
        bottomBar = {
            if (showDock) {
                // 审核234：BiliPai 悬浮胶囊 dock——水平 16dp/底部 12dp 悬空、全圆角容器、
                // 选中项 primaryContainer 胶囊指示（替代 MD3 NavigationBar 通栏）
                //
                // 审核318：直播 tab 加入后变成 4 个。原来每个 tab 是「图标+文字」
                // 且带 18dp 水平内边距，4 个挤在一行必然超宽被裁。改为 Kototoro
                // KototoroBottomNav 的规格：纯图标（22dp）+ 胶囊指示 + 点击脉冲，
                // 选中态图标做颜色 lerp 动画（0.25s）。文字只保留在 a11y 的
                // contentDescription 里（读屏可用，视觉不挤）。
                Box(Modifier.fillMaxWidth().padding(horizontal = 48.dp, vertical = 12.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(50))
                            .background(MaterialTheme.colorScheme.surfaceContainerLow)
                            .border(
                                1.dp,
                                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                                RoundedCornerShape(50)
                            )
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        MainTab.entries.forEach { tab ->
                            val route = tabRouteOf(tab)
                            val selected = currentRoute == route
                            // 审核318：图标颜色 lerp——选中态从 onSurfaceVariant 渐变到
                            // onPrimaryContainer，比硬切换柔和。
                            val iconTint by androidx.compose.animation.animateColorAsState(
                                targetValue = if (selected) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                animationSpec = androidx.compose.animation.core.tween(250),
                                label = "tabIconTint",
                            )
                            // 审核318：点击脉冲——非选中 tab 被点时轻微回弹（0.85f→1f），
                            // 给用户「按到了」的确认。
                            val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                            val pressed by interaction.collectIsPressedAsState()
                            val scale by androidx.compose.animation.core.animateFloatAsState(
                                targetValue = if (pressed) 0.85f else 1f,
                                animationSpec = androidx.compose.animation.core.tween(180),
                                label = "tabScale",
                            )
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(RoundedCornerShape(50))
                                    .background(
                                        if (selected) MaterialTheme.colorScheme.primaryContainer
                                        else Color.Transparent
                                    )
                                    .clickable(
                                        interactionSource = interaction,
                                        indication = null,
                                    ) {
                                        if (!selected) {
                                            navController.navigate(route) {
                                                popUpTo(Screen.Home.route) { saveState = true }
                                                launchSingleTop = true
                                                restoreState = true
                                            }
                                        }
                                    },
                            ) {
                                Icon(
                                    if (selected) tab.iconFilled else tab.iconOutlined,
                                    contentDescription = stringResource(tab.labelRes),
                                    tint = iconTint,
                                    modifier = Modifier
                                        .size(22.dp)
                                        .scale(scale),
                                )
                            }
                        }
                    }
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Box(Modifier.padding(padding)) {
    NavHost(
        navController = navController,
        startDestination = Screen.Home.route
    ) {
        composable(Screen.Home.route) {
            HomeScreen(
                onVideoClick = { bvid, cid ->
                    navController.navigate(Screen.VideoPlayer.createRoute(bvid, cid))
                },
                // 审核318：首页卡片补 UP 主跳转（此前未接线，只有分区页有）
                onAuthorClick = { mid ->
                    navController.navigate(Screen.Member.createRoute(mid))
                },
                onSearchClick = {
                    navController.navigate(Screen.Search.route)
                },
                // 审核241（P1-1）：消息入口此前从未接线，Screen.Message 路由不可达
                onMessageClick = { navController.navigate(Screen.Message.route) },
                // 审核241（P1-2）：「我的」入口此前是死参数，二级页底部 dock 会隐藏
                onMineClick = { navController.navigate(Screen.Profile.route) },
                onRankClick = {
                    navController.navigate(Screen.Rank.route)
                },
                onHotMoreClick = {
                    navController.navigate(Screen.HotMore.route)
                },
                onZoneClick = { navController.navigate(Screen.Zone.route) },
                onBangumiClick = { navController.navigate(Screen.Bangumi.createRoute()) },
                onStoryClick = { navController.navigate(Screen.Story.route) },
                onTodayWatchClick = { navController.navigate(Screen.TodayWatch.route) }
            )
        }
        composable(Screen.Search.route) { backStackEntry ->
            val initialQuery = backStackEntry.arguments?.getString("query").orEmpty()
            SearchScreen(
                initialQuery = initialQuery,
                onVideoClick = { bvid ->
                    navController.navigate(Screen.VideoPlayer.createRoute(bvid))
                },
                onUserClick = { mid ->
                    navController.navigate(Screen.Member.createRoute(mid))
                },
                onLiveClick = { roomId ->
                    navController.navigate(Screen.LiveRoom.createRoute(roomId.toString()))
                },
                onBack = { navController.popBackStack() }
            )
        }
        composable(Screen.Dynamics.route) {
            DynamicsScreen(
                onOpenVideo = { bvid, cid ->
                    navController.navigate(Screen.VideoPlayer.createRoute(bvid, cid))
                },
                // 审核318：点头像/UP 主名跳空间（此前无此参数）
                onOpenUser = { mid ->
                    navController.navigate(Screen.Member.createRoute(mid))
                },
                onGoLogin = { navController.navigate(Screen.Login.route) }
            )
        }
        composable(Screen.Message.route) {
            com.example.piliai.ui.messages.MessageScreen(
                onGoLogin = { navController.navigate(Screen.Login.route) },
                onOpenVideo = { bvid, cid ->
                    navController.navigate(Screen.VideoPlayer.createRoute(bvid, cid))
                },
                onOpenUser = { mid ->
                    navController.navigate(Screen.Member.createRoute(mid))
                },
                onOpenSessions = { navController.navigate(Screen.Sessions.route) }
            )
        }
        composable(Screen.Profile.route) {
            MineScreen(
                onSettingsClick = { navController.navigate(Screen.Settings.route) },
                onLoginClick = { navController.navigate(Screen.Login.route) },
                onHistoryClick = { navController.navigate(Screen.History.route) },
                onToViewClick = { navController.navigate(Screen.ToView.route) },
                onBangumiClick = { navController.navigate(Screen.Bangumi.createRoute()) },
                onFavoritesClick = { mid ->
                    navController.navigate(Screen.Favorites.createRoute(mid))
                },
                // 审核297：我的关注入口（此前只能从 UP 主空间绕进去）
                onFollowsClick = { mid ->
                    navController.navigate(Screen.FollowList.createRoute(mid, false))
                },
                // 审核319：听视频模式（收藏夹作播放列表 + CC 字幕歌词）
                onListenVideoClick = { mid ->
                    navController.navigate(Screen.ListenVideo.createRoute(mid))
                },
                onDownloadsClick = { navController.navigate(Screen.Downloads.route) },
                onSubscribeClick = { navController.navigate(Screen.Subscribe.route) }
            )
        }
        composable(Screen.Settings.route) {
            com.example.piliai.ui.settings.SettingsScreen(
                onBack = { navController.popBackStack() },
                // 审核241（P2-1）：弹幕屏蔽入口此前从未接线——设置页点了没反应
                onDanmakuBlockClick = { navController.navigate(Screen.DanmakuBlock.route) },
                // 审核241：账号/下载/订阅等设置项入口补齐（此前是死参数）
                onDownloadsClick = { navController.navigate(Screen.Downloads.route) },
                onSubscribeClick = { navController.navigate(Screen.Subscribe.route) }
            )
        }
        composable(Screen.Login.route) {
            LoginScreen(
                onBack = { navController.popBackStack() },
                onLoggedIn = { navController.popBackStack() }
            )
        }
        composable(Screen.Favorites.route) { backStackEntry ->
            val mid = backStackEntry.arguments?.getString("mid")?.toLongOrNull() ?: 0L
            FavoritesScreen(
                mid = mid,
                onBack = { navController.popBackStack() },
                onOpenFolder = { mediaId -> navController.navigate(Screen.FavMedia.createRoute(mediaId)) }
            )
        }
        composable(Screen.FavMedia.route) { backStackEntry ->
            val mediaId = backStackEntry.arguments?.getString("mediaId")?.toLongOrNull() ?: 0L
            FavMediaScreen(
                mediaId = mediaId,
                onBack = { navController.popBackStack() },
                onOpenVideo = { bvid, cid ->
                    navController.navigate(Screen.VideoPlayer.createRoute(bvid, cid))
                }
            )
        }
        composable(Screen.Story.route) {
            com.example.piliai.ui.story.StoryScreen(
                onBack = { navController.popBackStack() },
                onVideoClick = { bvid, cid ->
                    navController.navigate(Screen.VideoPlayer.createRoute(bvid, cid))
                },
                // 审核318：竖屏流点头像跳 UP 主空间（重写后新增）
                onUserClick = { mid ->
                    navController.navigate(Screen.Member.createRoute(mid))
                },
            )
        }
        // 审核319：听视频模式。mid 从 MineScreen 的登录资料传入。
        composable(
            route = Screen.ListenVideo.route,
            arguments = listOf(navArgument("mid") { type = NavType.LongType; defaultValue = 0L }),
        ) { backStackEntry ->
            val mid = backStackEntry.arguments?.getLong("mid") ?: 0L
            com.example.piliai.ui.audio.ListenVideoScreen(
                mid = mid,
                onBack = { navController.popBackStack() },
            )
        }
        composable(Screen.TodayWatch.route) {
            com.example.piliai.todaywatch.TodayWatchScreen(
                onBack = { navController.popBackStack() },
                onVideoClick = { bvid ->
                    navController.navigate(Screen.VideoPlayer.createRoute(bvid, 0L))
                }
            )
        }
        composable(Screen.History.route) {
            HistoryScreen(
                onBack = { navController.popBackStack() },
                onOpenVideo = { bvid, cid ->
                    navController.navigate(Screen.VideoPlayer.createRoute(bvid, cid))
                },
                onLogin = { navController.navigate(Screen.Login.route) }
            )
        }
        composable(Screen.VideoPlayer.route,
            arguments = listOf(
                navArgument("bvid") { type = NavType.StringType },
                navArgument("cid") { type = NavType.LongType; defaultValue = 0L },
                navArgument("local") { type = NavType.StringType; defaultValue = "0" }
            )
        ) { backStackEntry ->
            val bvid = backStackEntry.arguments?.getString("bvid") ?: ""
            val cid = backStackEntry.arguments?.getLong("cid") ?: 0L
            val local = backStackEntry.arguments?.getString("local") == "1"
            // ep 请求参数（番剧模式）：video/ep123 形式
            val epId = if (bvid.startsWith("ep")) bvid.removePrefix("ep").toLongOrNull() ?: 0L else 0L
            VideoPlayerScreen(
                videoUrl = "",
                bvid = bvid,
                cid = cid,
                epId = epId,
                local = local,
                onBack = { navController.popBackStack() },
                onOpenComments = { bv -> navController.navigate(Screen.Comment.createRoute(bv)) },
                onSearchTag = { tag -> navController.navigate(Screen.Search.createRoute(tag)) },
                isCurrentDestination = {
                    navController.currentBackStackEntry === backStackEntry
                }
            )
        }
        composable(Screen.LiveRoom.route) { backStackEntry ->
            val roomId = backStackEntry.arguments?.getString("roomId") ?: ""
            LiveRoomScreen(
                roomId = roomId,
                onBack = { navController.popBackStack() }  // 审核轮202：原空lambda——直播间无法返回
            )
        }
        composable(Screen.Comment.route) { backStackEntry ->
            val bvid = backStackEntry.arguments?.getString("bvid") ?: ""
            CommentScreen(
                bvid = bvid,
                onBack = { navController.popBackStack() },      // 审核轮202：原空lambda——评论页无法返回
                onGoLogin = { navController.navigate(Screen.Login.route) }  // 未登录跳登录
            )
        }
        composable(Screen.Member.route) { backStackEntry ->
            val mid = backStackEntry.arguments?.getString("mid")?.toLongOrNull() ?: 0L
            com.example.piliai.ui.pages.member.MemberScreen(
                mid = mid,
                onBack = { navController.popBackStack() },
                onOpenVideo = { bvid, cid ->
                    navController.navigate(Screen.VideoPlayer.createRoute(bvid, cid))
                },
                onOpenFollowList = { m, followers ->
                    navController.navigate(Screen.FollowList.createRoute(m, followers))
                },
                onOpenArticle = { articleId ->
                    navController.navigate(Screen.Article.createRoute(articleId))
                },
                onOpenWeb = { url ->
                    // 批次L45：课程等无原生链路页面用外部浏览器打开
                    ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, url.toUri()))
                }
            )
        }
        composable(Screen.ToView.route) {
            com.example.piliai.ui.library.ToViewScreen(
                onBack = { navController.popBackStack() },
                onOpenVideo = { bvid, cid ->
                    navController.navigate(Screen.VideoPlayer.createRoute(bvid, cid))
                }
            )
        }
        composable(
            Screen.FollowList.route,
            arguments = listOf(
                navArgument("mid") { type = NavType.LongType },
                navArgument("type") { type = NavType.IntType; defaultValue = 0 }
            )
        ) { backStackEntry ->
            val mid = backStackEntry.arguments?.getLong("mid") ?: 0L
            val followers = backStackEntry.arguments?.getInt("type") == 1
            com.example.piliai.ui.pages.member.FollowListScreen(
                mid = mid,
                followers = followers,
                onBack = { navController.popBackStack() },
                onOpenUser = { m -> navController.navigate(Screen.Member.createRoute(m)) }
            )
        }
        composable(
            Screen.Bangumi.route,
            arguments = listOf(
                navArgument("seasonId") { type = NavType.LongType; defaultValue = 0L },
                navArgument("epId") { type = NavType.LongType; defaultValue = 0L }
            )
        ) { backStackEntry ->
            val sid = backStackEntry.arguments?.getLong("seasonId") ?: 0L
            val epid = backStackEntry.arguments?.getLong("epId") ?: 0L
            com.example.piliai.ui.pages.bangumi.BangumiScreen(
                seasonId = sid,
                epId = epid,
                onBack = { navController.popBackStack() },
                onOpenVideo = { bvid, cid ->
                    navController.navigate(Screen.VideoPlayer.createRoute(bvid, cid))
                },
                onOpenIndex = { navController.navigate(Screen.PgcIndex.route) }
            )
        }
        composable(Screen.LiveList.route) {
            com.example.piliai.ui.pages.livelist.LiveListScreen(
                onBack = { navController.popBackStack() },
                onOpenRoom = { roomId -> navController.navigate(Screen.LiveRoom.createRoute(roomId)) },
                onOpenArea = { navController.navigate(Screen.LiveArea.route) }
            )
        }
        composable(Screen.LiveArea.route) {
            com.example.piliai.ui.pages.livelist.LiveAreaScreen(
                onBack = { navController.popBackStack() },
                onOpenRoom = { roomId -> navController.navigate(Screen.LiveRoom.createRoute(roomId.toString())) }
            )
        }
        composable(Screen.HotMore.route) {
            com.example.piliai.ui.pages.home.HotMoreScreen(
                onBack = { navController.popBackStack() },
                onOpenVideo = { bvid, cid -> navController.navigate(Screen.VideoPlayer.createRoute(bvid, cid)) },
                onOpenZone = { navController.navigate(Screen.Zone.route) }
            )
        }
        composable(Screen.PgcIndex.route) {
            com.example.piliai.ui.pages.bangumi.PgcIndexScreen(
                onBack = { navController.popBackStack() },
                onOpenSeason = { sid ->
                    navController.navigate(Screen.Bangumi.createRoute(seasonId = sid))
                },
                onOpenTimeline = { navController.navigate(Screen.Timeline.route) }
            )
        }
        composable(Screen.Timeline.route) {
            com.example.piliai.ui.pages.bangumi.TimelineScreen(
                onBack = { navController.popBackStack() },
                onOpenSeason = { sid ->
                    navController.navigate(Screen.Bangumi.createRoute(seasonId = sid.toLong()))
                }
            )
        }
        composable(Screen.Zone.route) {
            com.example.piliai.ui.pages.zone.ZoneScreen(
                onBack = { navController.popBackStack() },
                onOpenVideo = { bvid, cid -> navController.navigate(Screen.VideoPlayer.createRoute(bvid, cid)) },
                onOpenUser = { mid -> navController.navigate(Screen.Member.createRoute(mid)) }
            )
        }
        composable(Screen.Rank.route) {
            com.example.piliai.ui.pages.rank.RankScreen(
                onBack = { navController.popBackStack() },
                onOpenVideo = { bvid -> navController.navigate(Screen.VideoPlayer.createRoute(bvid)) }
            )
        }
        composable(Screen.Article.route) {
            val id = it.arguments?.getString("articleId")?.toLongOrNull() ?: 0L
            com.example.piliai.ui.pages.article.ArticleScreen(
                articleId = id,
                onBack = { navController.popBackStack() }
            )
        }
        composable(Screen.DanmakuBlock.route) {
            com.example.piliai.ui.settings.DanmakuBlockScreen(
                onBack = { navController.popBackStack() }
            )
        }
        composable(Screen.Sessions.route) {
            com.example.piliai.ui.messages.SessionListScreen(
                onBack = { navController.popBackStack() },
                onGoLogin = { navController.navigate(Screen.Login.route) },
                onOpenChat = { talkerId, name ->
                    navController.navigate("chat/$talkerId?name=${android.net.Uri.encode(name)}")
                }
            )
        }
        composable(
            "chat/{talkerId}?name={name}",
            arguments = listOf(
                navArgument("talkerId") { type = NavType.LongType },
                navArgument("name") { type = NavType.StringType; defaultValue = "" }
            ),
        ) { backStackEntry ->
            com.example.piliai.ui.messages.ChatScreen(
                talkerId = backStackEntry.arguments?.getLong("talkerId") ?: 0L,
                // 审核270：带上对方昵称，否则顶栏标题永远是「对话」
                talkerName = backStackEntry.arguments?.getString("name")?.let {
                    android.net.Uri.decode(it)
                }.orEmpty(),
                onBack = { navController.popBackStack() },
                onGoLogin = { navController.navigate(Screen.Login.route) }
            )
        }
        composable(Screen.Downloads.route) {
            com.example.piliai.ui.download.DownloadScreen(
                onBack = { navController.popBackStack() },
                onPlayLocal = { bvid, cid ->
                    navController.navigate("video/$bvid?cid=$cid&local=1")
                }
            )
        }
        // 订阅页：Animeko「订阅源」移植
        composable(
            route = Screen.Subscribe.route,
            arguments = listOf(
                navArgument("q") { type = NavType.StringType; defaultValue = "" }
            )
        ) { entry ->
            // 审核261：从 Bangumi 详情页带过来的搜索词
            val initialQuery = entry.arguments?.getString("q")?.let {
                android.net.Uri.decode(it)
            }.orEmpty()
            com.example.piliai.ui.subscribe.SubscribeScreen(
                initialQuery = initialQuery,
                onBack = { navController.popBackStack() },
                onPlay = { url, title, cover ->
                    // 外部源直链：走专用路由，播放器直接吃 URL（Kazumi 式直链播放）
                    navController.navigate(
                        Screen.DirectPlay.createRoute(url, title, cover)
                    )
                },
                onSettingsClick = { navController.navigate(Screen.Settings.route) },
                onOpenSubject = { id ->
                    navController.navigate(Screen.BangumiSubject.createRoute(id))
                },
                onSourcePlay = { request, title, _ ->
                    val requestId = sourcePlaybackSession.register(request)
                    try {
                        navController.navigate(Screen.SourcePlay.createRoute(requestId, title))
                    } catch (error: Exception) {
                        sourcePlaybackSession.remove(requestId)
                        throw error
                    }
                }
            )
        }
        composable(
            Screen.BangumiSubject.route,
            arguments = listOf(navArgument("id") { type = NavType.LongType })
        ) { entry ->
            // 审核265：已看剧集 id 集合。进程内即可 —— Bangumi 侧是权威，
            // 本地只为即时反馈；重启后重新拉。
            var watchedEpisodes by remember { mutableStateOf<Set<Long>>(emptySet()) }
            var subjectEpisodes by remember { mutableStateOf<List<com.example.piliai.data.remote.BangumiApi.Episode>>(emptyList()) }
            val episodeScope = rememberCoroutineScope()
            com.example.piliai.ui.subscribe.BangumiSubjectScreen(
                subjectId = entry.arguments?.getLong("id") ?: 0L,
                onBack = { navController.popBackStack() },
                // 审核243：详情页直接追番（Bangumi 直连，sourceId=0）
                // 审核261：Bangumi 资料 -> 订阅源搜片源
                onSearchInSubscriptions = { title ->
                    navController.navigate(Screen.Subscribe.createSearchRoute(title))
                },
                // 审核265：标记某集看过。Bangumi 按 ep_id 单集设置
                // （PATCH /v0/users/-/collections/{subject_id}/episodes），
                // 与条目级收藏类型独立。
                // 审核265：剧集列表上报，用于算本地追番进度
                onEpisodesLoaded = { list -> subjectEpisodes = list },
                isEpisodeWatched = { epId -> watchedEpisodes.contains(epId) },
                onToggleEpisodeWatched = { epId, watched ->
                    val subjectId = entry.arguments?.getLong("id") ?: 0L
                    episodeScope.launch {
                        // 未登录时 Bangumi 会 401：只记本地，不弹错打扰用户
                        val remoteOk = com.example.piliai.data.remote.BangumiSession.isLogin &&
                            runCatching {
                                com.example.piliai.data.remote.BangumiApi.setEpisodeCollections(
                                    subjectId = subjectId,
                                    episodeIds = listOf(epId),
                                    // 剧集级编码：2=看过，0=未收藏
                                    type = if (watched) 2 else 0
                                )
                            }.getOrDefault(false)
                        watchedEpisodes = if (watched) {
                            watchedEpisodes + epId
                        } else {
                            watchedEpisodes - epId
                        }
                        if (com.example.piliai.data.remote.BangumiSession.isLogin && !remoteOk) {
                            android.widget.Toast.makeText(
                                navController.context,
                                "同步到 Bangumi 失败（已本地记录）",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                        // 本地追番进度取「已看集数与当前集序的较大值」，
                        // 避免用户跳着标记时进度倒退。
                        syncLocalWatchedProgress(subjectId, subjectEpisodes, watchedEpisodes)
                    }
                },
                onFollow = { id, title, cover, episodes ->
                    // 详情页只拿得到 Bangumi 侧信息：以 bgm:{id} 作 sourceKey，
                    // 走 Bangumi 同步路径（sourceId=0）而不是某个订阅源。
                    bangumiFollow(
                        navController,
                        id,
                        title,
                        cover,
                        episodes
                    )
                }
            )
        }
        composable(
            Screen.SourcePlay.route,
            arguments = listOf(
                navArgument("requestId") { type = NavType.StringType },
                navArgument("title") { type = NavType.StringType; defaultValue = "" }
            )
        ) { entry ->
            val requestId = entry.arguments?.getString("requestId").orEmpty()
            androidx.lifecycle.ViewModelProvider(
                entry,
                object : androidx.lifecycle.ViewModelProvider.Factory {
                    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                        require(modelClass == com.example.piliai.playback.SourcePlaybackEntryOwner::class.java)
                        return modelClass.cast(
                            com.example.piliai.playback.SourcePlaybackEntryOwner(
                                sourcePlaybackSession, requestId
                            )
                        )
                    }
                }
            )[com.example.piliai.playback.SourcePlaybackEntryOwner::class.java]
            val request = sourcePlaybackSession.request(requestId)
            if (request == null) {
                // Process recreation intentionally does not restore source credentials.
                androidx.compose.foundation.layout.Column {
                    Text(stringResource(com.example.piliai.R.string.subscribe_source_unavailable))
                    androidx.compose.material3.TextButton(onClick = { navController.popBackStack() }) {
                        Text(stringResource(com.example.piliai.R.string.bangumi_back))
                    }
                }
            } else {
                VideoPlayerScreen(
                    videoUrl = request.videoUrl,
                    title = entry.arguments?.getString("title").orEmpty(),
                    sourceRequest = request,
                    onBack = { navController.popBackStack() },
                    isCurrentDestination = {
                        navController.currentBackStackEntry === entry
                    }
                )
            }
        }
        // 直链播放（订阅源 / 外部链接）：URL 经 Uri.encode，避免特殊字符破坏路由
        composable(
            Screen.DirectPlay.route,
            arguments = listOf(
                navArgument("url") { type = NavType.StringType; defaultValue = "" },
                navArgument("title") { type = NavType.StringType; defaultValue = "" },
                navArgument("cover") { type = NavType.StringType; defaultValue = "" }
            )
        ) { backStackEntry ->
            val url = backStackEntry.arguments?.getString("url")?.let {
                android.net.Uri.decode(it)
            } ?: ""
            val title = backStackEntry.arguments?.getString("title")?.let {
                android.net.Uri.decode(it)
            } ?: ""
            // 审核轮13：URL 为空时不进入播放器（否则会打开一个永远加载失败的空播放器）
            if (url.isBlank()) {
                LaunchedEffect(Unit) { navController.popBackStack() }
                return@composable
            }
            VideoPlayerScreen(
                videoUrl = url,
                bvid = "",
                cid = 0L,
                title = title,
                onBack = { navController.popBackStack() },
                isCurrentDestination = {
                    navController.currentBackStackEntry === backStackEntry
                }
            )
        }
    }
}
    }
}

/**
 * 审核243：Bangumi 详情页「追番」按钮的实现。
 *
 * 直接走 SubscribeRepository.followTitle，sourceId=0（Bangumi 直连、
 * 无订阅源），sourceKey="bgm:{id}" 与 syncBangumiCollections 的键一致，
 * 这样下次同步会命中同一行而不是重复插入。
 */
private fun bangumiFollow(
    navController: androidx.navigation.NavController,
    bangumiId: Long,
    title: String,
    cover: String,
    episodes: Int
) {
    val context = navController.context
    val scope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.Dispatchers.Main.immediate
    )
    scope.launch {
        val db = com.example.piliai.database.PiliNaraDatabase.getDatabase(context)
        val repo = com.example.piliai.data.repository.SubscribeRepository(
            db.subscribeSourceDao(),
            db.subscribeItemDao(),
            db.subscribeTitleDao()
        )
        repo.followTitle(
            sourceId = 0L,
            sourceKey = "bgm:$bangumiId",
            name = title,
            cover = cover,
            latestEpisode = episodes,
            bangumiId = bangumiId
        ).onSuccess {
            android.widget.Toast.makeText(context, "已追番：$title", android.widget.Toast.LENGTH_SHORT).show()
        }.onFailure { e ->
            android.widget.Toast.makeText(
                context,
                "追番失败：${e.message ?: "未知错误"}",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }
}


/**
 * 审核265：把「已看集数」同步到本地追番进度。
 *
 * 取已看集序的最大值：用户可能跳着标记（先看第 5 集），
 * 此时进度应是 5 而不是 1。
 */
private fun syncLocalWatchedProgress(
    subjectId: Long,
    episodes: List<com.example.piliai.data.remote.BangumiApi.Episode>,
    watchedEpisodes: Set<Long>
) {
    if (subjectId <= 0L || episodes.isEmpty()) return
    val maxOrder = episodes
        .filter { it.id in watchedEpisodes }
        .maxOfOrNull { it.order }
        ?: return
    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
        val db = com.example.piliai.database.PiliNaraDatabase
            .getDatabase(com.example.piliai.AppContext.get())
        val title = db.subscribeTitleDao().getByBangumiId(subjectId) ?: return@launch
        if (maxOrder > title.lastWatchedEpisode) {
            db.subscribeTitleDao().setWatchedEpisode(title.id, maxOrder)
        }
    }
}
