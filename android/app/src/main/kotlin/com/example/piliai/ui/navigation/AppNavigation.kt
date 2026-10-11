package com.example.piliai.ui.navigation

import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.net.toUri
import androidx.compose.ui.res.stringResource
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

    fun chat(talkerId: Long) = "chat/$talkerId"

    object Downloads : Screen("downloads")
    object Subscribe : Screen("subscribe")
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
            MainTab.MINE -> Screen.Profile.route
        }
    }
    val showBottomBar = MainTab.entries.any { tabRouteOf(it) == currentRoute }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                // 审核234：BiliPai 悬浮胶囊 dock——水平 16dp/底部 12dp 悬空、全圆角容器、
                // 选中项 primaryContainer 胶囊指示（替代 MD3 NavigationBar 通栏）
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
                            .padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        MainTab.entries.forEach { tab ->
                            val route = tabRouteOf(tab)
                            val selected = currentRoute == route
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(
                                        if (selected) MaterialTheme.colorScheme.primaryContainer
                                        else Color.Transparent
                                    )
                                    .clickable {
                                        if (!selected) {
                                            navController.navigate(route) {
                                                popUpTo(Screen.Home.route) { saveState = true }
                                                launchSingleTop = true
                                                restoreState = true
                                            }
                                        }
                                    }
                                    .padding(horizontal = 18.dp, vertical = 6.dp),
                            ) {
                                Icon(
                                    if (selected) tab.iconFilled else tab.iconOutlined,
                                    contentDescription = stringResource(tab.labelRes),
                                    tint = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    stringResource(tab.labelRes),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
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
                onSearchClick = {
                    navController.navigate(Screen.Search.route)
                },
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
                onDownloadsClick = { navController.navigate(Screen.Downloads.route) },
                onSubscribeClick = { navController.navigate(Screen.Subscribe.route) }
            )
        }
        composable(Screen.Settings.route) {
            com.example.piliai.ui.settings.SettingsScreen(
                onBack = { navController.popBackStack() },
                onDanmakuBlockClick = { navController.navigate(Screen.DanmakuBlock.route) }
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
                }
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
                onOpenChat = { talkerId -> navController.navigate("chat/$talkerId") }
            )
        }
        composable(
            "chat/{talkerId}",
            arguments = listOf(navArgument("talkerId") { type = NavType.LongType })
        ) { backStackEntry ->
            com.example.piliai.ui.messages.ChatScreen(
                talkerId = backStackEntry.arguments?.getLong("talkerId") ?: 0L,
                onBack = { navController.popBackStack() },
                onGoLogin = { navController.navigate(Screen.Login.route) }
            )
        }
        composable(Screen.Downloads.route) {
            com.example.piliai.ui.download.DownloadScreen(
                onBack = { navController.popBackStack() },
                onPlayLocal = { bvid -> navController.navigate("video/$bvid?local=1") }
            )
        }
        // 订阅页：Animeko「订阅源」移植
        composable(Screen.Subscribe.route) {
            com.example.piliai.ui.subscribe.SubscribeScreen(
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
            com.example.piliai.ui.subscribe.BangumiSubjectScreen(
                subjectId = entry.arguments?.getLong("id") ?: 0L,
                onBack = { navController.popBackStack() }
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
