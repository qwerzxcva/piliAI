package com.example.piliai.playback
import java.util.Locale

import android.app.PictureInPictureParams
import android.os.Build
import android.content.Context
import android.util.Rational
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.border
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Comment
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.piliai.danmaku.DanmakuView
import com.example.piliai.data.model.formatCount
import com.example.piliai.utils.toHttpsUrl

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@android.annotation.SuppressLint("SourceLockedOrientationActivity") // 审核轮175：播放器全屏切换需运行时切横竖屏，非清单锁定
@Composable
fun VideoPlayerScreen(
    videoUrl: String,
    bvid: String = "",
    cid: Long = 0L,
    epId: Long = 0L,
    local: Boolean = false,
    title: String = "",
    onBack: () -> Unit = {},
    onOpenComments: (bvid: String) -> Unit = {},
    // 审核319：打开视频笔记编辑器（bvid + cid + 当前播放毫秒）
    onOpenNote: (bvid: String, cid: Long, positionMs: Long) -> Unit = { _, _, _ -> },
    onSearchTag: (String) -> Unit = {},
    onOpenUser: (Long) -> Unit = {},
    // 审核347：听音频（用当前 UP 主 mid 打开音频模式）
    onOpenListenAudio: (Long) -> Unit = {},
    sourceRequest: SourcePlaybackRequest? = null,
    // 自动 PiP 资格必须区分“仍停留在本播放页”与“已导航到其他页面”。
    // 页面 lifecycle 在导航转场期间可能仍为 STARTED，因此需要一个独立的
    // 当前目的地信号；进入 PiP 时本页仍是当前目的地，不受影响。
    isCurrentDestination: () -> Boolean = { true },
    viewModel: VideoPlayerViewModel = viewModel(
        factory = VideoPlayerViewModelFactory(LocalContext.current)
    )
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // 审核312：互动状态改从 EngagementController 的 StateFlow 读。
    // 原来读 PlayerState 里的同名字段——互动逻辑搬到 Controller 后那些字段
    // 已无人赋值，UI 读到的永远是初始值（点赞后计数不动）。
    val isLiked by viewModel.isLiked.collectAsStateWithLifecycle()
    val isFavorited by viewModel.isFavorited.collectAsStateWithLifecycle()
    val likeCount by viewModel.likeCount.collectAsStateWithLifecycle()
    val coinCountTotal by viewModel.coinCountTotal.collectAsStateWithLifecycle()
    val favCount by viewModel.favCount.collectAsStateWithLifecycle()
    // 审核313：倍速档位（设置页可编辑）
    val speedList by viewModel.speedList.collectAsStateWithLifecycle()
    // 审核267：取帧按钮的协程作用域
    val captureScope = rememberCoroutineScope()
    LaunchedEffect(viewModel) {
        viewModel.restorePlaybackSpeed()
    }
    var showControls by remember { mutableStateOf(true) }
    // 审核364：控制条此前永远不会自动隐藏（全代码只有构造处和 onTap 切换），
    // 一直压在画面上——这是「播放器 UI 丑/显示不全」的直接原因之一。
    // 自动隐藏逻辑放在所有面板状态声明之后（见下方 autoHideControls）。
    var showSpeedMenu by remember { mutableStateOf(false) }
    // 审核轮152：长按 3x 状态
    var showSpeedBurst by remember { mutableStateOf(false) }
    var savedSpeed by remember { mutableFloatStateOf(1.0f) }
    fun finishSpeedBurst() {
        if (!showSpeedBurst) return
        showSpeedBurst = false
        viewModel.setPlaybackSpeed(savedSpeed, raw = true)
    }

    // 审核轮153（真 bug）：亮度手势更新了 state.brightness 但 UI 从未消费——死功能。
    // 仅在用户实际操作过（brightnessTouched）后才应用，避免一进播放页就把系统
    // 亮度强设为 0.5；离开播放页恢复跟随系统（BRIGHTNESS_OVERRIDE_NONE）。
    val activity = androidx.compose.ui.platform.LocalContext.current as? android.app.Activity
    val brightnessTouched = viewModel.brightnessTouched
    LaunchedEffect(brightnessTouched, state.brightness) {
        if (!brightnessTouched) return@LaunchedEffect
        activity?.window?.attributes?.let { attrs ->
            attrs.screenBrightness = state.brightness.coerceIn(0.05f, 1f)
            activity.window.attributes = attrs
        }
    }
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            activity?.window?.attributes?.let { attrs ->
                attrs.screenBrightness = android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                activity.window.attributes = attrs
            }
            // 审核轮175：离开播放页恢复竖屏，避免整个 App 卡在横屏
            activity?.requestedOrientation =
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
    // 审核轮175：横屏时按返回键先回竖屏（B站行为），再按才退出
    // 审核217：enableAutoEnter——播放就绪即自动进入全屏（横屏），设置开关控制
    LaunchedEffect(state.isPlaying, state.isBuffering) {
        if (state.isPlaying && viewModel.shouldAutoEnterFullscreen()) {
            activity?.let { act ->
                if (act.resources.configuration.orientation !=
                    android.content.res.Configuration.ORIENTATION_LANDSCAPE
                ) act.requestedOrientation =
                    android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
        }
    }
    androidx.activity.compose.BackHandler(
        enabled = activity?.resources?.configuration?.orientation ==
            android.content.res.Configuration.ORIENTATION_LANDSCAPE
    ) {
        // 审核241（P1-9）：设置里开了「返回键直接退出」就直接退出播放页，
        // 否则保持原有行为（先回竖屏）。此前该开关没有任何消费者。
        if (com.example.piliai.utils.RendererPrefs.directExitOnBack) {
            onBack()
        } else {
            activity?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }
    var showVolumeSlider by remember { mutableStateOf(false) }
    var isInPiP by remember(activity) {
        mutableStateOf(activity?.isInPictureInPictureMode == true)
    }
    var showQualityMenu by remember { mutableStateOf(false) }
    var showPartSheet by remember { mutableStateOf(false) }
    var showDanmakuSheet by remember { mutableStateOf(false) }
    var showShareSheet by remember { mutableStateOf(false) }
    var showRelatedSheet by remember { mutableStateOf(false) }
    var showIntroSheet by remember { mutableStateOf(false) }
    var showCoinSheet by remember { mutableStateOf(false) }
    var showFavSheet by remember { mutableStateOf(false) }

    // 审核366：暂停时自动重新显示控制条（B 站行为），否则暂停后
    // 控制条正在隐藏，用户看不到播放键，得先点一下画面才能继续。
    LaunchedEffect(state.isPlaying) {
        if (!state.isPlaying) showControls = true
    }

    // 审核364：播放中 4 秒自动收起控制条；任一面板/菜单开着时不收，
    // 避免用户操作到一半被抽走。暂停时保持显示。
    LaunchedEffect(
        showControls, state.isPlaying, showSpeedMenu, showQualityMenu, showPartSheet,
        showDanmakuSheet, showVolumeSlider, showFavSheet, showCoinSheet, showShareSheet,
    ) {
        if (!showControls || !state.isPlaying) return@LaunchedEffect
        if (showSpeedMenu || showQualityMenu || showPartSheet || showDanmakuSheet ||
            showVolumeSlider || showFavSheet || showCoinSheet || showShareSheet
        ) return@LaunchedEffect
        kotlinx.coroutines.delay(4_000L)
        showControls = false
    }
    
    val context = LocalContext.current
    val componentActivity = activity as? androidx.activity.ComponentActivity
    var playerViewRef by remember { mutableStateOf<PlayerView?>(null) }
    val pipLifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val pipPlayer = playerViewRef?.player
    DisposableEffect(playerViewRef, isInPiP) {
        val enteredInPiP = isInPiP
        val playerView = playerViewRef
        val originalUseController = playerView?.useController
        val originalSubtitleVisibility = playerView?.subtitleView?.visibility
        if (enteredInPiP) {
            playerView?.useController = false
            playerView?.subtitleView?.visibility = android.view.View.INVISIBLE
        }
        onDispose {
            if (enteredInPiP) {
                originalUseController?.let { playerView?.useController = it }
                originalSubtitleVisibility?.let { playerView?.subtitleView?.visibility = it }
            }
        }
    }
    DisposableEffect(activity, playerViewRef, pipPlayer, pipLifecycleOwner, isCurrentDestination) {
        val playerView = playerViewRef
        val lifecycle = pipLifecycleOwner.lifecycle
        val observedViews = listOfNotNull(playerView, playerView?.videoSurfaceView).distinct()
        fun updatePipParams() {
            val pageActive = lifecycle.currentState.isAtLeast(
                androidx.lifecycle.Lifecycle.State.STARTED
            )
            activity?.setPictureInPictureParams(
                playerView.buildPipParams(
                    autoEnter = pageActive && isCurrentDestination() &&
                        pipPlayer?.isPlaying == true,
                    includeSourceRectHint = activity.isInPictureInPictureMode != true
                )
            )
        }
        val layoutListener = android.view.View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updatePipParams()
        }
        val attachListener = object : android.view.View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: android.view.View) {
                updatePipParams()
            }

            override fun onViewDetachedFromWindow(view: android.view.View) {
                updatePipParams()
            }
        }
        val playerListener = object : androidx.media3.common.Player.Listener {
            override fun onEvents(
                player: androidx.media3.common.Player,
                events: androidx.media3.common.Player.Events
            ) {
                if (events.containsAny(
                        androidx.media3.common.Player.EVENT_IS_PLAYING_CHANGED,
                        androidx.media3.common.Player.EVENT_VIDEO_SIZE_CHANGED,
                        androidx.media3.common.Player.EVENT_MEDIA_ITEM_TRANSITION
                    )) {
                    updatePipParams()
                }
            }
        }
        val lifecycleObserver = androidx.lifecycle.LifecycleEventObserver { _, _ ->
            updatePipParams()
        }
        val pipListener = androidx.core.util.Consumer<androidx.core.app.PictureInPictureModeChangedInfo> {
            updatePipParams()
        }
        observedViews.forEach { view ->
            view.addOnLayoutChangeListener(layoutListener)
            view.addOnAttachStateChangeListener(attachListener)
        }
        pipPlayer?.addListener(playerListener)
        lifecycle.addObserver(lifecycleObserver)
        componentActivity?.addOnPictureInPictureModeChangedListener(pipListener)
        updatePipParams()
        onDispose {
            observedViews.forEach { view ->
                view.removeOnLayoutChangeListener(layoutListener)
                view.removeOnAttachStateChangeListener(attachListener)
            }
            pipPlayer?.removeListener(playerListener)
            lifecycle.removeObserver(lifecycleObserver)
            componentActivity?.removeOnPictureInPictureModeChangedListener(pipListener)
            activity?.setPictureInPictureParams(
                PictureInPictureParams.Builder().setAutoEnterEnabled(false).build()
            )
        }
    }
    DisposableEffect(componentActivity, viewModel) {
        val listener = androidx.core.util.Consumer<androidx.core.app.PictureInPictureModeChangedInfo> { info ->
            isInPiP = info.isInPictureInPictureMode
            if (isInPiP) {
                finishSpeedBurst()
                viewModel.cancelGestureSeek()
                showSpeedMenu = false
                showQualityMenu = false
                showPartSheet = false
                showDanmakuSheet = false
                showRelatedSheet = false
                showIntroSheet = false
                showCoinSheet = false
                showFavSheet = false
                showVolumeSlider = false
            }
        }
        componentActivity?.addOnPictureInPictureModeChangedListener(listener)
        onDispose {
            componentActivity?.removeOnPictureInPictureModeChangedListener(listener)
            componentActivity?.setPictureInPictureParams(
                PictureInPictureParams.Builder().setAutoEnterEnabled(false).build()
            )
        }
    }
    
    LaunchedEffect(viewModel, videoUrl, bvid, cid, epId, local, sourceRequest) {
        viewModel.loadVideo(videoUrl, bvid, cid, epId, local, sourceRequest)
    }

    // 播放中每 15 秒上报一次历史进度（需登录）
    LaunchedEffect(state.isPlaying) {
        while (state.isPlaying) {
            kotlinx.coroutines.delay(15_000L)
            viewModel.reportProgress()
        }
    }
    
    val portrait = androidx.compose.ui.platform.LocalConfiguration.current.orientation !=
        android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val detailBvid = bvid.takeIf { it.isNotBlank() && !it.startsWith("ep") }.orEmpty()
    val commentViewModel = if (detailBvid.isNotEmpty()) {
        viewModel(
            key = "player-comments-$detailBvid",
            factory = object : androidx.lifecycle.ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                    com.example.piliai.ui.comments.CommentViewModel(detailBvid) as T
            }
        ) as com.example.piliai.ui.comments.CommentViewModel
    } else {
        null
    }
    val commentState = commentViewModel?.state?.collectAsStateWithLifecycle()?.value
    val detailEpoch by viewModel.detailEpoch.collectAsStateWithLifecycle()
    val isFollowingOwner by viewModel.isFollowingOwner.collectAsStateWithLifecycle()
    val inWatchLater by viewModel.isInWatchLater.collectAsStateWithLifecycle()
    val aiEpoch by viewModel.aiEpoch.collectAsStateWithLifecycle()
    val aiLoading by viewModel.aiLoading.collectAsStateWithLifecycle()
    val aiError by viewModel.aiError.collectAsStateWithLifecycle()
    // 审核362：viewModel.aid 是普通 var（非 Compose State），拿它当 LaunchedEffect
    // 的 key 不会在 aid 变化时重跑，评论可能一直拿不到 aid 而加载失败。
    // 改用 detailEpoch（详情就绪时自增的 StateFlow）作为触发信号。
    LaunchedEffect(detailEpoch, commentViewModel) {
        val aid = viewModel.aid
        if (aid > 0L) commentViewModel?.bindAid(aid)
    }
    // 审核371：分页数随设置变化（简介必留；评论/推荐可关）
    val showPlayerRelated by com.example.piliai.utils.StorageManager
        .getInstance(context.applicationContext).showPlayerRelatedFlow
        .collectAsStateWithLifecycle(initialValue = true)
    val showPlayerComment by com.example.piliai.utils.StorageManager
        .getInstance(context.applicationContext).showPlayerCommentFlow
        .collectAsStateWithLifecycle(initialValue = true)
    val detailTabCount = 1 +
        (if (showPlayerComment) 1 else 0) +
        (if (showPlayerRelated) 1 else 0)
    val detailPager = if (portrait && detailBvid.isNotEmpty())
        rememberPagerState(pageCount = { detailTabCount }) else null
    // 审核371：各分页的实际索引（关闭评论时推荐前移到 1）
    val commentPageIndex = if (showPlayerComment) 1 else -1
    val relatedPageIndex = when {
        showPlayerComment && showPlayerRelated -> 2
        showPlayerRelated -> 1
        else -> -1
    }

    Column(modifier = Modifier.fillMaxSize().background(Color.Black)) {
    Box(modifier = Modifier.then(
        if (portrait && detailBvid.isNotEmpty()) Modifier.fillMaxWidth().aspectRatio(16f / 9f)
        else Modifier.fillMaxSize()
    )) {
        // PiP keeps playback active; ordinary background handling follows the pause preference.
        val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
        androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
            val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                when (event) {
                    androidx.lifecycle.Lifecycle.Event.ON_STOP -> {
                        // 审核239 H2：退出/切后台前补一次最终进度上报——原实现只有
                        // 15s 周期上报，最多丢 15 秒观看进度（历史记录回跳不准）。
                        if (!isInPiP) viewModel.reportProgress()
                        // 审核216：pauseOnMinimize 设置控制（默认关——Flutter 版行为对齐）
                        if (activity?.isInPictureInPictureMode != true) {
                            viewModel.onAppBackground()
                        }
                    }
                    else -> {}
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }
        // 画面：Media3 走 PlayerView；GPU-next 走独立 Surface，交给 libmpv 的 vo=gpu-next
        val backendId = viewModel.activeBackend
        if (backendId == "gpu-next") {
            AndroidView(
                factory = { ctx ->
                    android.view.SurfaceView(ctx).apply {
                        holder.addCallback(object : android.view.SurfaceHolder.Callback {
                            override fun surfaceCreated(holder: android.view.SurfaceHolder) {
                                viewModel.attachVideoSurface(holder.surface)
                            }
                            override fun surfaceChanged(
                                holder: android.view.SurfaceHolder,
                                format: Int,
                                width: Int,
                                height: Int
                            ) {
                                viewModel.attachVideoSurface(holder.surface)
                            }
                            override fun surfaceDestroyed(holder: android.view.SurfaceHolder) {
                                viewModel.detachVideoSurface()
                            }
                        })
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            val playerEpoch by viewModel.playerEpoch.collectAsStateWithLifecycle()
            AndroidView(
                factory = { ctx ->
                    createPlayerView(ctx, viewModel.player).also { playerViewRef = it }
                },
                update = { view ->
                    // ExoPlayer is created later in ensureBackend(). Binding only in
                    // factory leaves view.player null, so progress moves with no picture.
                    val player = if (playerEpoch >= 0) viewModel.player else null
                    if (view.player !== player) view.player = player
                },
                modifier = Modifier.fillMaxSize()
            )
        }
        
        // Danmaku Overlay（批次L14 真实渲染接线）
        var dmViewRef by remember { mutableStateOf<com.example.piliai.danmaku.DanmakuView?>(null) }
        AndroidView(
            factory = { ctx ->
                com.example.piliai.danmaku.DanmakuView(ctx).also { dmViewRef = it }
            },
            update = { v ->
                v.alphaFactor = state.danmakuAlpha
                v.scaleFactor = state.danmakuScale
                v.speedFactor = state.danmakuSpeed
                // 审核233：显示区域 + 描边（PiliPlus 弹幕细节）
                v.showAreaFactor = state.danmakuShowArea
                v.strokeWidthFactor = state.danmakuStrokeWidth
                // 审核244（P0-1/P0-2）：字号与行高接入。此前这两个设置项
                // 只写 DataStore，播放器从不读 → 滑块完全无效。
                v.lineHeightFactor = state.danmakuLineHeight
                // 审核249：字重接入（此前同为假开关）
                v.fontWeight = state.danmakuFontWeight
                // 审核259：每秒弹幕上限（0=不限）
                v.maxPerSecond = state.danmakuMaxPerSecond
                // 审核249：点击弹幕交互。命中弹幕时提示内容（B站是发弹幕/复制，
                // 这里先用轻量 toast，避免引入不必要的新 UI）。
                v.tapInteractEnabled = state.danmakuTapInteract
                v.onDanmakuTapped = { text ->
                    android.widget.Toast
                        .makeText(context, text, android.widget.Toast.LENGTH_SHORT)
                        .show()
                }
                v.mergeDanmaku = state.danmakuMerge
                v.massiveMode = state.danmakuMassiveMode
                v.visibility = if (isInPiP) android.view.View.INVISIBLE else android.view.View.VISIBLE
                v.setRunning(state.isPlaying && !isInPiP)
            },
            modifier = Modifier.fillMaxSize()
        )
        // 已发送到渲染层的弹幕 id（避免重复 add）
        // 审核：Kotlin 2.2 + compose lint 对 `remember { mutableSetOf() }` 的返回值
        // 仍判为 RememberReturnType(Error)。改用显式实现类 HashSet<String>，
        // 让表达式类型明确，行为不变（仍是去重集合）。
        val dispatchedIds: MutableSet<String> = remember { HashSet() }
        // 从去重键解析时间戳（键格式 "ts_content" 或纯 id）；解析失败返回 Long.MAX_VALUE（不淘汰）
        fun tsOf(key: String): Long =
            key.substringBefore('_').toLongOrNull() ?: Long.MAX_VALUE
        // 审核274（真 bug）：dmViewRef 必须进 LaunchedEffect 的 key。
        // 原来是 (isPlaying, currentTime, danmakuOn) —— AndroidView 的 factory
        // 首次组合时 dmViewRef 还是 null，effect 首次执行就 return；
        // 之后虽然 currentTime 变化会重跑，但**视图就绪前那几个时间点的弹幕
        // 永久丢失**（表现为「刚进播放器前几秒没弹幕」）。
        LaunchedEffect(state.isPlaying, state.currentTime, state.danmakuOn, dmViewRef) {
            val v = dmViewRef ?: return@LaunchedEffect
            if (!state.danmakuOn) { v.clearAll(); return@LaunchedEffect }
            // 审核轮122：去重集合按窗口淘汰，避免长视频（数小时）无界增长泄漏内存。
            // 弹幕只在其时间戳后 3 秒窗口内分发，故 10 秒前的 id 可安全丢弃。
            val cutoff = state.currentTime - 10_000L
            dispatchedIds.removeAll { tsOf(it) < cutoff }
            // 窗口内未分发的弹幕 → add（审核轮167：按类型开关过滤顶部/底部弹幕）
            for (e in viewModel.getDanmakuAtTime(state.currentTime)) {
                if (e.mode == 4 && !viewModel.cachedDmShowBottom) continue
                if (e.mode == 5 && !viewModel.cachedDmShowTop) continue
                val key = e.id.ifEmpty { "${e.timestamp}_${e.content}" }
                if (dispatchedIds.add(key)) {
                    // 审核297：用用户设置的字号作基准。原来直接传弹幕自带的
                    // fontSize（B 站协议固定 25），设置页「弹幕字号」滑块
                    // 因此完全无效——这是审核244 漏修的一项。
                    v.add(
                        text = e.content,
                        color = e.color,
                        fontSizeSp = if (state.danmakuFontSize > 0) state.danmakuFontSize
                        else e.fontSize
                    )
                }
            }
        }
        // seek/切P 时重置去重集合并清屏
        LaunchedEffect(state.currentTime) {
            if (state.currentTime < 3000L) dispatchedIds.clear()
        }

        // 字幕层（批次K）
        val subtitle by viewModel.currentSubtitle.collectAsStateWithLifecycle()
        // 批次L41：视频章节
        val viewPoints by viewModel.viewPoints.collectAsStateWithLifecycle()
        LaunchedEffect(state.isPlaying, state.currentTime) {
            viewModel.updateSubtitleAt(state.currentTime)
        }
        if (subtitle.isNotEmpty() && !isInPiP) {
            // 审核235：字幕细节设置真实生效（字号/边距/背景透明度/阴影/加粗）
            val subScale = state.subtitleScale
            val subPadH = state.subtitlePaddingH
            val subPadB = state.subtitlePaddingB
            val subBg = state.subtitleBgOpacity
            val subStroke = state.subtitleStroke
            val subBold = state.subtitleBold
            Box(
                Modifier.fillMaxSize().padding(horizontal = subPadH.dp),
                contentAlignment = Alignment.BottomCenter
            ) {
                Surface(
                    color = Color.Black.copy(alpha = subBg),
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.padding(bottom = subPadB.dp)
                ) {
                    Text(
                        subtitle,
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = MaterialTheme.typography.bodyMedium.fontSize * subScale,
                            fontWeight = if (subBold) androidx.compose.ui.text.font.FontWeight.Bold
                                else MaterialTheme.typography.bodyMedium.fontWeight,
                            shadow = if (subStroke > 0f) androidx.compose.ui.graphics.Shadow(
                                androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.9f),
                                offset = androidx.compose.ui.geometry.Offset(1f, 1f),
                                blurRadius = subStroke * 4f
                            ) else null,
                        ),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }
        }
        
        // Error overlay
        state.error?.takeUnless { isInPiP }?.let { error ->
            // 审核302：原来这里是 Text("Error: $error", color = Color.Red)——
            // 中文 App 里冒英文前缀、纯红刺眼、无重试语义。改为可读的中文卡片：
            // 主题 error 色 + 圆角容器 + 「重试」文字按钮。
            androidx.compose.material3.Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(com.example.piliai.ui.theme.PiliTokens.controlCornerRadius),
                modifier = Modifier.align(Alignment.Center).padding(horizontal = 32.dp)
            ) {
                Column(
                    Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        error,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    Spacer(Modifier.height(10.dp))
                    androidx.compose.material3.TextButton(onClick = { viewModel.retryCurrent() }) {
                        Text("重试", color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
            }
        }
        
        // Buffering indicator
        if (state.isBuffering && state.error == null && !isInPiP) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp)
            }
        }
        
        // 审核364：控制条显示期间不加全屏透明点击层——它会吞掉下方所有
        // 按钮的点击（造成「点了没反应」）。收起改为靠 4 秒自动隐藏。

        // 审核236：顶部/底部渐变 scrim（BiliPai 风，控制条显示时淡入）
        if (showControls && state.error == null && !isInPiP) {
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.7f),
                        0.18f to Color.Transparent,
                        0.72f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.8f),
                    )
                )
            )
        }
        
        // Controls
        if (showControls && state.error == null && !isInPiP) {
            Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                // Top bar
                Row(modifier = Modifier.fillMaxWidth(), 
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
                    }
                    Text(
                        title,
                        color = Color.White,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                    Row(
                        Modifier
                            .widthIn(max = 220.dp)
                            .horizontalScroll(rememberScrollState()),
                    ) {
                        // 清晰度
                        if (state.qualities.isNotEmpty()) {
                            Text(
                                state.qualities.firstOrNull { it.qn == state.currentQn }?.label
                                    ?: "清晰度",
                                color = Color.White,
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier
                                    .clickable { showQualityMenu = true }
                                    .background(Color.White.copy(alpha = 0.14f), RoundedCornerShape(50))
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                        // 分P
                        if (state.partCount > 1) {
                            Text(
                                "P${state.currentPart}",
                                color = Color.White,
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier
                                    .clickable { showPartSheet = true }
                                    .background(Color.White.copy(alpha = 0.14f), RoundedCornerShape(50))
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                        // 审核轮193：下一P（多P时显示）
                        if (state.partCount > 1 && state.currentPart < state.partCount) {
                            IconButton(onClick = { viewModel.playNextPart() }) {
                                Icon(Icons.Default.SkipNext, "下一P", tint = Color.White)
                            }
                        }
                        IconButton(onClick = { showSpeedMenu = !showSpeedMenu }) {
                            Icon(Icons.Default.Speed, "Speed", tint = Color.White)
                        }
                        // 审核360：弹幕按钮原来是直接打开设置面板，想快速关弹幕
                        // 还得再点一次开关。改为短按直接开/关，长按进设置面板。
                        Box(
                            modifier = Modifier.combinedClickable(
                                onClick = { viewModel.danmakuEnabled = !state.danmakuOn },
                                onLongClick = { showDanmakuSheet = true },
                            ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                if (state.danmakuOn) Icons.Default.Subtitles else Icons.Default.SubtitlesOff,
                                if (state.danmakuOn) "关闭弹幕" else "打开弹幕",
                                tint = Color.White,
                                modifier = Modifier.padding(12.dp).size(24.dp),
                            )
                        }
                        IconButton(onClick = { showVolumeSlider = !showVolumeSlider }) {
                            Icon(
                                if (state.isMuted) Icons.AutoMirrored.Filled.VolumeOff 
                                else Icons.AutoMirrored.Filled.VolumeUp,
                                "Volume", tint = Color.White
                            )
                        }
                        // 审核轮173：横屏/全屏切换（B站播放器核心交互）
                        IconButton(onClick = {
                            val act = context.findActivity() ?: return@IconButton
                            val target = if (act.resources.configuration.orientation ==
                                android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
                                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                            } else {
                                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                            }
                            act.requestedOrientation = target
                        }) {
                            Icon(Icons.Default.ScreenRotation, "全屏", tint = Color.White)
                        }
                        IconButton(onClick = {
                            if (!isInPiP) context.findActivity()?.enterPiP(playerViewRef)
                        }) {
                            Icon(Icons.Default.PictureInPicture, "PiP", tint = Color.White)
                        }
                    }
                }
                
                Spacer(Modifier.weight(1f))

                // Seek bar（拖动时显示 storyboard 缩略图预览，批次L3）
                var isSeeking by remember { mutableStateOf(false) }
                var seekPreviewSec by remember { mutableLongStateOf(0L) }
                Box {
                    if (isSeeking) {
                        val frame = viewModel.shotFrameAt(seekPreviewSec)
                        val shot = viewModel.videoShot.collectAsStateWithLifecycle().value
                        if (frame != null && shot != null && shot.imgXLen > 0 && shot.imgYLen > 0) {
                            // 雪碧图整图按格位偏移裁剪显示
                            val cellW = shot.imgXSize.toFloat()
                            val cellH = shot.imgYSize.toFloat()
                            val scale = 160f / cellW
                            // 审核：无需 constraints scope，用 Box（消除 lint UnusedBoxWithConstraintsScope）
                            androidx.compose.foundation.layout.Box(
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(bottom = 44.dp)
                                    .size(width = 160.dp, height = 90.dp)
                            ) {
                                coil.compose.AsyncImage(
                                    model = frame.first.toHttpsUrl(),
                                    contentDescription = "预览",
                                    modifier = Modifier
                                        .size(
                                            width = (shot.imgXLen * cellW * scale).dp,
                                            height = (shot.imgYLen * cellH * scale).dp
                                        )
                                        .graphicsLayer {
                                            translationX = -frame.second * cellW * scale * density
                                            translationY = -frame.third * cellH * scale * density
                                        },
                                    contentScale = androidx.compose.ui.layout.ContentScale.FillBounds
                                )
                            }
                        }
                    }
                    // 批次L42：弹幕密度热力曲线（高能进度条），画在进度条上方
                    val heatCurve by viewModel.heatCurve.collectAsStateWithLifecycle()
                    if (heatCurve.isNotEmpty()) {
                        androidx.compose.foundation.Canvas(
                            Modifier.fillMaxWidth().height(28.dp)
                        ) {
                            val n = heatCurve.size
                            val bw = size.width / n
                            heatCurve.forEachIndexed { i, v ->
                                val h = (v.coerceIn(0f, 1f)) * size.height
                                drawRect(
                                    color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.35f + 0.4f * v),
                                    topLeft = androidx.compose.ui.geometry.Offset(i * bw, size.height - h),
                                    size = androidx.compose.ui.geometry.Size(bw * 0.9f, h)
                                )
                            }
                        }
                    }
                    // 审核236：BiliPai 风细圆角进度条（4dp 轨道+BiliPink 填充+白点 thumb）
                    // 审核361：原来 value 直接绑 state.currentTime，拖动时每帧都被
                    // 播放进度覆盖，滑块会跳回去——拖不动。拖动中用本地值。
                    var dragFraction by remember { androidx.compose.runtime.mutableFloatStateOf(-1f) }
                    Slider(
                        value = when {
                            dragFraction >= 0f -> dragFraction
                            state.duration > 0 -> state.currentTime.toFloat() / state.duration
                            else -> 0f
                        },
                        onValueChange = { fraction ->
                            isSeeking = true
                            dragFraction = fraction
                            seekPreviewSec = ((state.duration * fraction) / 1000).toLong()
                        },
                        onValueChangeFinished = {
                            isSeeking = false
                            dragFraction = -1f
                            viewModel.seekTo(seekPreviewSec * 1000)
                        },
                        modifier = Modifier.fillMaxSize().height(24.dp),
                        colors = SliderDefaults.colors(
                            thumbColor = Color.White,
                            activeTrackColor = Color(0xFFFB7299),
                            inactiveTrackColor = Color.White.copy(alpha = 0.25f),
                        )
                    )
                }
                
                // 审核236：时间行收进玻璃胶囊（左时间/右倍速）
                Row(
                    modifier = Modifier
                        .background(Color.White.copy(alpha = 0.12f), RoundedCornerShape(50))
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("${formatTime(state.currentTime)} / ${formatTime(state.duration)}", 
                        color = Color.White, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.width(12.dp))
                    Text("${state.playbackSpeed}x", color = Color.White, 
                        style = MaterialTheme.typography.bodySmall)
                }
                
                Spacer(Modifier.weight(1f))
                
                // Play/Pause center button
                Row(modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center) {
                    IconButton(
                        onClick = { viewModel.togglePlayPause() },
                        modifier = Modifier
                            .background(Color.Black.copy(0.5f), CircleShape)
                            .padding(12.dp)
                    ) {
                        Icon(
                            imageVector = if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            "Play/Pause", tint = Color.White, modifier = Modifier.height(40.dp)
                        )
                    }
                }
                
                Spacer(Modifier.height(16.dp))
                
                // 竖屏时互动按钮移到画面下方独立一行：16:9 画面高度装不下
                // 顶栏 + 播放键 + 11 个按钮，叠在画面里会被截断。
                if (!portrait || detailBvid.isEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    EngagementButton(
                        icon = Icons.Filled.ThumbUp,
                        label = "点赞",
                        count = formatCount(likeCount),
                        onClick = { viewModel.toggleLike() },
                        tintColor = if (isLiked) Color(0xFF00A1D6) else Color.White,
                        onLongClick = { viewModel.likeCoinFav() }
                    )
                    EngagementButton(
                        icon = Icons.Default.MonetizationOn,
                        label = "投币",
                        count = formatCount(coinCountTotal),
                        onClick = { showCoinSheet = true },
                        tintColor = Color.White,
                        showLabel = false
                    )
                    EngagementButton(
                        icon = if (isFavorited) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                        label = "收藏",
                        count = formatCount(favCount),
                        onClick = {
                            viewModel.loadFavFolders()
                            showFavSheet = true
                        },
                        tintColor = if (isFavorited) Color(0xFFFF6B9D) else Color.White
                    )
                    EngagementButton(
                        icon = Icons.AutoMirrored.Filled.Comment,
                        label = "评论",
                        onClick = {
                            if (bvid.isBlank() || bvid.startsWith("ep")) {
                                viewModel.showToast("这个视频没有评论区")
                            } else {
                                onOpenComments(bvid)
                            }
                        },
                        tintColor = Color.White,
                        showLabel = false
                    )
                    // 审核319：笔记入口。传当前播放进度，编辑器打开时
                    // 「插入时间点」按这个秒数打戳。
                    EngagementButton(
                        icon = Icons.Default.EditNote,
                        label = "笔记",
                        onClick = { onOpenNote(bvid, viewModel.currentCid(), viewModel.getCurrentTime()) },
                        tintColor = Color.White,
                        showLabel = false
                    )
                    EngagementButton(
                        icon = Icons.Default.AutoAwesome,
                        label = "相关",
                        count = if (state.related.isNotEmpty()) state.related.size.toString() else "",
                        onClick = { showRelatedSheet = true },
                        tintColor = Color.White,
                        showLabel = false
                    )
                    EngagementButton(
                        icon = Icons.Default.Schedule,
                        label = "稍后看", onClick = { viewModel.addToWatchLater() },
                        tintColor = Color.White,
                        showLabel = false
                    )
                    // 离线下载（批次I 接线：发起队列）
                    EngagementButton(
                        icon = Icons.Default.Download,
                        label = "下载",
                        onClick = { viewModel.downloadCurrent(context.applicationContext) },
                        tintColor = Color.White,
                        showLabel = false
                    )
                    EngagementButton(
                        icon = Icons.Default.Info,
                        label = "简介",
                        onClick = {
                            viewModel.loadAiConclusion()
                            showIntroSheet = true
                        },
                        tintColor = Color.White,
                        showLabel = false
                    )
                    // 审核267：取帧（截图当前画面）。
                    // 审核315：在线视频也可用了——请求头经
                    // ExoPlayerAssetLoader.Factory 注入（见 VideoFrameCapture）。
                    EngagementButton(
                        icon = Icons.Default.PhotoCamera,
                        label = "取帧",
                        onClick = {
                            val uri = viewModel.currentPlaybackUri()
                            if (uri == null) {
                                viewModel.showToast("当前视频不支持取帧")
                            } else {
                                captureScope.launch {
                                    val file = com.example.piliai.playback.VideoFrameCapture
                                        .captureFromPlayback(
                                            context = context.applicationContext,
                                            uri = uri,
                                            positionMs = state.currentTime,
                                            headers = viewModel.currentPlaybackHeaders(),
                                            userAgent = viewModel.currentPlaybackUserAgent(),
                                        )
                                    viewModel.showToast(
                                        if (file != null) "已保存到 ${file.absolutePath}"
                                        else "取帧失败，请稍后重试"
                                    )
                                }
                            }
                        },
                        tintColor = Color.White,
                        showLabel = false
                    )
                    EngagementButton(
                        icon = Icons.Default.Share,
                        label = "分享",
                        onClick = {
                            // 审核295：原来只复制链接到剪贴板——用户想分享到微信/QQ
                            // 还得自己切 App 粘贴。改成弹系统分享面板（ACTION_SEND），
                            // 剪贴板降级为分享失败时的兜底。播放器 state 不持有
                            // 视频标题（只存播放相关状态），故只分享链接。
                            val link = if (bvid.startsWith("ep"))
                                "https://www.bilibili.com/bangumi/play/$bvid"
                            else "https://www.bilibili.com/video/$bvid"
                            val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(android.content.Intent.EXTRA_TEXT, link)
                            }
                            val shared = runCatching {
                                context.startActivity(
                                    android.content.Intent.createChooser(send, "分享到")
                                )
                            }.isSuccess
                            if (!shared) {
                                // 没有可处理分享的组件（极端情况）：退回复制链接
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE)
                                    as android.content.ClipboardManager
                                cm.setPrimaryClip(
                                    android.content.ClipData.newPlainText("link", link)
                                )
                                viewModel.notifyShared(link)
                            }
                        },
                        tintColor = Color.White,
                        showLabel = false
                    )
                }
                }
            }
        }
        
        // Speed menu
        DropdownMenu(expanded = showSpeedMenu, onDismissRequest = { showSpeedMenu = false }) {
            // 审核313：档位改从设置读取（原来是硬编码 listOf）；
            // 同时把英文 "Normal" 改成中文「正常」
            speedList.forEach { speed ->
                DropdownMenuItem(
                    text = { 
                        Text(
                            if (speed == 1.0f) "正常" else "${formatSpeed(speed)}",
                            color = if (state.playbackSpeed == speed) MaterialTheme.colorScheme.primary else Color.Black
                        ) 
                    },
                    onClick = { viewModel.setPlaybackSpeed(speed); showSpeedMenu = false }
                )
            }
        }
        
        // Volume slider overlay
        if (showVolumeSlider) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(0.7f)),
                contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(
                        if (state.isMuted) Icons.AutoMirrored.Filled.VolumeOff 
                        else Icons.AutoMirrored.Filled.VolumeUp,
                        "Volume", tint = Color.White, modifier = Modifier.height(48.dp)
                    )
                    Slider(
                        value = state.volume,
                        onValueChange = { viewModel.setVolume(it) },
                        modifier = Modifier.width(200.dp)
                    )
                    Text("${(state.volume * 100).toInt()}%", color = Color.White)
                }
            }
        }
        
        // Quality menu
        DropdownMenu(expanded = showQualityMenu, onDismissRequest = { showQualityMenu = false }) {
            state.qualities.forEach { q ->
                DropdownMenuItem(
                    text = {
                        Text(
                            q.label,
                            color = if (state.currentQn == q.qn) MaterialTheme.colorScheme.primary else Color.Black
                        )
                    },
                    onClick = { viewModel.switchQuality(q.qn); showQualityMenu = false }
                )
            }
        }

        // 分P 选择面板
        if (showPartSheet) {
            ModalBottomSheet(onDismissRequest = { showPartSheet = false }) {
                Text(
                    "选集（共 ${state.partCount} P）",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(16.dp)
                )
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 400.dp)) {
                    items((1..state.partCount).toList()) { p ->
                        val partTitle = viewModel.pagesSnapshot().getOrNull(p - 1)?.part ?: "第 $p 集"
                        ListItem(
                            headlineContent = { Text("P$p · $partTitle", maxLines = 1) },
                            trailingContent = {
                                IconButton(onClick = {
                                    viewModel.downloadPart(context.applicationContext, p - 1)
                                }) {
                                    Icon(Icons.Default.Download, "下载本P",
                                        tint = MaterialTheme.colorScheme.primary)
                                }
                            },
                            modifier = Modifier.clickable {
                                viewModel.playPart(p - 1); showPartSheet = false
                            },
                            colors = if (p == state.currentPart)
                                ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                            else ListItemDefaults.colors()
                        )
                    }
                }
            }
        }

        // 审核342：分享面板（复制链接 / 系统分享 / 浏览器打开）
        if (showShareSheet) {
            ModalBottomSheet(onDismissRequest = { showShareSheet = false }) {
                Column(Modifier.padding(16.dp)) {
                    Text("分享", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        videoLink(bvid),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                    )
                    Spacer(Modifier.height(12.dp))
                    listOf(
                        "复制链接" to { copyVideoLink(context, bvid) },
                        "系统分享" to { shareViaSystem(context, bvid) },
                        "浏览器打开" to { openInBrowser(context, bvid) },
                    ).forEach { (label, action) ->
                        Text(
                            label,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { action(); showShareSheet = false }
                                .padding(vertical = 12.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }

        // 弹幕设置面板
        if (showDanmakuSheet) {
            ModalBottomSheet(onDismissRequest = { showDanmakuSheet = false }) {
                Column(Modifier.padding(16.dp)) {
                    Text("弹幕设置", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(12.dp))
                    // 发送弹幕（批次L5）
                    // 审核341：对齐 PiliNara send_danmaku/view.dart——发送前可选
                    // 模式（滚动/顶部/底部）与颜色，原来只能发默认的白色滚动弹幕。
                    var dmInput by remember { mutableStateOf("") }
                    var dmMode by remember { androidx.compose.runtime.mutableIntStateOf(1) }
                    var dmColorIdx by remember { androidx.compose.runtime.mutableIntStateOf(0) }
                    // 审核341：value = B 站弹幕接口的 color 整数，swatch = 预览色
                    val dmColors: List<Pair<Int, Long>> = listOf(
                        16777215 to 0xFFFFFFFF, // 白
                        16711680 to 0xFFFF5252, // 红
                        16744192 to 0xFFFFA726, // 橙
                        16776960 to 0xFFFFEE00, // 黄
                        6749952 to 0xFF66CCFF,  // 蓝
                        10229552 to 0xFF9C27B0, // 紫
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = dmInput,
                            onValueChange = { dmInput = it },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("发个弹幕见证当下…") },
                            maxLines = 2,
                            shape = MaterialTheme.shapes.large
                        )
                        Spacer(Modifier.width(8.dp))
                        IconButton(
                            onClick = {
                                if (dmInput.isNotBlank()) {
                                    viewModel.sendDanmaku(
                                        text = dmInput,
                                        mode = dmMode,
                                        color = dmColors[dmColorIdx].first,
                                    )
                                    dmInput = ""
                                }
                            },
                            enabled = dmInput.isNotBlank()
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Send, "发送弹幕")
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        listOf(1 to "滚动", 5 to "顶部", 4 to "底部").forEach { (m, label) ->
                            Text(
                                label,
                                style = MaterialTheme.typography.labelMedium,
                                color = if (dmMode == m) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(
                                        if (dmMode == m) MaterialTheme.colorScheme.primaryContainer
                                        else Color.Transparent
                                    )
                                    .clickable { dmMode = m }
                                    .padding(horizontal = 10.dp, vertical = 4.dp),
                            )
                        }
                        Spacer(Modifier.width(4.dp))
                        dmColors.forEachIndexed { index, (_, swatch) ->
                            val selected = dmColorIdx == index
                            Box(
                                modifier = Modifier
                                    .size(22.dp)
                                    .clip(CircleShape)
                                    .background(Color(swatch))
                                    .then(
                                        if (selected) Modifier.border(
                                            2.dp,
                                            MaterialTheme.colorScheme.primary,
                                            CircleShape,
                                        ) else Modifier
                                    )
                                    .clickable { dmColorIdx = index },
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("显示弹幕", modifier = Modifier.weight(1f))
                        Switch(
                            checked = state.danmakuOn,
                            onCheckedChange = { viewModel.danmakuEnabled = it }
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    // 审核轮167：顶部/底部弹幕开关（B站播放器标配）
                    // 审核247：改用 VM 的 Flow 驱动状态，不再 remember 一份副本 +
                    // 手动回写。原来 remember 初始值 + persist 只写 DataStore 不回读，
                    // 用户改完重启 App 又回到默认 true（持久化失效）。
                    val dmTop by viewModel.dmShowTop.collectAsStateWithLifecycle()
                    val dmBottom by viewModel.dmShowBottom.collectAsStateWithLifecycle()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("显示顶部弹幕", modifier = Modifier.weight(1f))
                        Switch(checked = dmTop, onCheckedChange = { on ->
                            viewModel.persistDmShowTop(on)
                        })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("显示底部弹幕", modifier = Modifier.weight(1f))
                        Switch(checked = dmBottom, onCheckedChange = { on ->
                            viewModel.persistDmShowBottom(on)
                        })
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("透明度 ${(state.danmakuAlpha * 100).toInt()}%")
                    Slider(
                        value = state.danmakuAlpha,
                        onValueChange = { viewModel.setDanmakuAlpha(it) },
                        valueRange = 0.1f..1f
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("大小 ${"%.1f".format(state.danmakuScale)}x")
                    Slider(
                        value = state.danmakuScale,
                        onValueChange = { viewModel.setDanmakuScale(it) },
                        valueRange = 0.5f..2f
                    )
                    // 字幕选择（批次L9）
                    val tracks by viewModel.subtitleTracks.collectAsStateWithLifecycle()
                    val selectedSubId by viewModel.selectedSubtitleId.collectAsStateWithLifecycle()
                    if (tracks.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text("字幕", style = MaterialTheme.typography.labelLarge)
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilterChip(
                                selected = selectedSubId == -1L,
                                onClick = { viewModel.selectSubtitle(-1L) },
                                label = { Text("关闭") }
                            )
                            tracks.forEach { (id, name, _) ->
                                FilterChip(
                                    selected = selectedSubId == id,
                                    onClick = { viewModel.selectSubtitle(id) },
                                    label = { Text(name) }
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                }
            }
        }

        // 收藏夹选择面板（批次L20）
        if (showFavSheet) {
            // 审核312：favFolders 改为 EngagementController 的 StateFlow
            val favFolders by viewModel.favFolders.collectAsStateWithLifecycle()
            ModalBottomSheet(onDismissRequest = { showFavSheet = false }) {
                Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
                    Text("选择收藏夹", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    favFolders.forEach { f ->
                        ListItem(
                            headlineContent = { Text(f.second) },
                            supportingContent = { Text("${f.third} 个内容") },
                            leadingContent = {
                                Icon(Icons.Filled.Folder, contentDescription = null)
                            },
                            modifier = Modifier.fillMaxWidth().clickable {
                                viewModel.favoriteTo(f.first)
                                showFavSheet = false
                            }
                        )
                    }
                    if (favFolders.isEmpty()) {
                        Text("未获取到收藏夹（需登录）", style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }

        // 投币面板（批次L19：1/2 枚选择）
        if (showCoinSheet) {
            ModalBottomSheet(onDismissRequest = { showCoinSheet = false }) {
                Column(Modifier.padding(16.dp).fillMaxWidth()) {
                    Text("为 UP 主投币", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        listOf(1, 2).forEach { n ->
                            Button(
                                onClick = {
                                    viewModel.coinOnce(n)
                                    showCoinSheet = false
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("投 $n 枚")
                            }
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }

        // 简介面板（批次L18）
        if (showIntroSheet) {
            ModalBottomSheet(onDismissRequest = { showIntroSheet = false }) {
                Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
                    Text(viewModel.videoTitle, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        buildString {
                            append(viewModel.videoOwner)
                            viewModel.videoPubdate.takeIf { it > 0 }?.let {
                                append(" · ")
                                append(java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.CHINA)
                                    .format(java.util.Date(it * 1000)))
                            }
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    // 批次L40：实时在线人数（审核216：受设置 enableOnlineTotal 控制）
                    val showOnline by androidx.compose.runtime.produceState(initialValue = true) {
                        com.example.piliai.utils.StorageManager.getInstance(com.example.piliai.AppContext.get())
                            .showOnlineTotalFlow.collect { value = it }
                    }
                    if (state.onlineCount > 0L && showOnline) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "👁 ${state.onlineCount} 人正在看",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    // 审核222：弹幕总数（enableDmCount 设置控制）
                    val showDmCount by androidx.compose.runtime.produceState(initialValue = false) {
                        com.example.piliai.utils.StorageManager.getInstance(com.example.piliai.AppContext.get())
                            .dmCountEnabledFlow.collect { value = it }
                    }
                    if (state.dmTotalCount > 0 && showDmCount) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "💬 ${state.dmTotalCount} 条弹幕",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    // 批次L41：视频章节（view_points，点击跳转时间点）
                    if (viewPoints.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        Text("章节", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        viewPoints.forEach { vp ->
                            Text(
                                "[${java.text.SimpleDateFormat("mm:ss", java.util.Locale.CHINA).format(java.util.Date((vp.from * 1000).toLong()))}] ${vp.content}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { viewModel.seekTo((vp.from * 1000).toLong()) }
                                    .padding(vertical = 4.dp)
                            )
                        }
                    }
                    if (viewModel.videoTags.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            viewModel.videoTags.take(12).forEach { tag ->
                                SuggestionChip(
                                    onClick = { onSearchTag(tag) },
                                    label = { Text(tag, fontSize = 12.sp) }
                                )
                            }
                        }
                    }
                    if (viewModel.videoDesc.isNotBlank()) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            viewModel.videoDesc,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    // AI 总结（批次L22，需登录，失败不显示）
                    if (viewModel.aiSummary.isNotBlank()) {
                        Spacer(Modifier.height(12.dp))
                        Text("AI 视频总结", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(4.dp))
                        Text(viewModel.aiSummary, style = MaterialTheme.typography.bodySmall)
                        viewModel.aiOutline.forEach { (ts, title) ->
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "%02d:%02d  %s".format(ts / 60, ts % 60, title),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                // 审核315：大纲条目改为可点击跳转（空降助手）。
                                // 原先是 primary 色纯文本——看着可点但点了没反应，
                                // 用户会以为是 bug。时间戳单位是秒。
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { viewModel.seekTo(ts * 1000L) }
                                    .padding(vertical = 2.dp),
                            )
                        }
                    } else {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "AI 总结加载中或不可用（需登录）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }

        // 相关视频面板
        if (showRelatedSheet) {
            ModalBottomSheet(onDismissRequest = { showRelatedSheet = false }) {
                Text(
                    "相关推荐",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(16.dp)
                )
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                    items(state.related, key = { it.bvid }) { r ->
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable {
                                    viewModel.playRelated(r); showRelatedSheet = false
                                }
                                .padding(12.dp)
                        ) {
                            coil.compose.AsyncImage(
                                model = r.pic.toHttpsUrl(),
                                contentDescription = r.title,
                                modifier = Modifier.width(120.dp).height(68.dp),
                                contentScale = androidx.compose.ui.layout.ContentScale.Crop
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    r.title, maxLines = 2,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "${r.author} · ${r.viewText}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }

        // 审核237：拖动停止 1s 后浮标消失（tick 归零防重显示）
        LaunchedEffect(state.volumeTouchedTick, state.brightnessTouchedTick) {
            if (state.volumeTouchedTick > 0L || state.brightnessTouchedTick > 0L) {
                kotlinx.coroutines.delay(1000)
                viewModel.clearGestureTicks()
            }
        }
        // 审核轮156+审核237：手势浮标（音量/亮度竖拖实时反馈）
        val gestureTip = when {
            // 审核241（P1-10）：提示跟随实际倍速，不再硬编码 3x
            showSpeedBurst -> "${state.playbackSpeed}x 快进中 »"
            state.brightnessTouchedTick > 0 -> "☀ 亮度 ${(state.brightness * 100).toInt()}%"
            state.volumeTouchedTick > 0 -> if (state.volume > 0f) "🔊 音量 ${(state.volume * 100).toInt()}%" else "🔇 静音"
            showVolumeSlider -> if (state.volume > 0f) "音量 ${(state.volume * 100).toInt()}%" else "🔇 静音"
            else -> null
        }
        if (gestureTip != null && !isInPiP) {
            Text(
                gestureTip,
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color.Black.copy(0.6f), androidx.compose.foundation.shape.RoundedCornerShape(com.example.piliai.ui.theme.PiliTokens.chipCornerRadius))
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }

        // 审核轮179：toast 提示（弹幕发送成功等），替代原来的全屏红字
        val toastMsg by viewModel.toast.collectAsStateWithLifecycle()
        toastMsg?.takeUnless { isInPiP }?.let { msg ->
            Text(
                msg,
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 96.dp)
                    .background(Color.Black.copy(0.7f), androidx.compose.foundation.shape.RoundedCornerShape(com.example.piliai.ui.theme.PiliTokens.chipCornerRadius))
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }

        // Gesture handler
        if (!isInPiP && !showControls) Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { showControls = true },
                        onDoubleTap = { tapOffset ->
                            val tapX = tapOffset.x
                            if (tapX < size.width / 2) {
                                viewModel.seekRelative(-10_000L)
                            } else {
                                viewModel.seekRelative(10_000L)
                            }
                        },
                        // 审核轮152：长按 = 临时倍速（松开恢复，BV/Flutter 版标志性交互）
                        // 审核241（P1-8）：倍速值与开关改读设置，不再硬编码 3x。
                        onLongPress = {
                            if (!com.example.piliai.utils.RendererPrefs.longPressSpeedEnabled) {
                                return@detectTapGestures
                            }
                            savedSpeed = state.playbackSpeed
                            viewModel.setPlaybackSpeed(
                                com.example.piliai.utils.RendererPrefs.longPressSpeedValue, raw = true
                            )
                            showSpeedBurst = true
                        },
                        onPress = { release ->
                            try { awaitRelease() } finally {
                                finishSpeedBurst()
                            }
                        }
                    )
                }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragEnd = { viewModel.commitGestureSeek() },
                        onDragCancel = { viewModel.cancelGestureSeek() },
                        onDrag = { change, drag ->
                            change.consume()
                            // 审核242（手势安全区）：边缘手势不触发。
                            // bv BvPlayerController.kt:901-915/938-944 用水平 10% /
                            // 垂直 20% 边缘豁免 + 20px 方向阈值，否则用户在屏幕边缘
                            // 只想轻推一下却莫名跳进度/改音量。
                            val startX = change.position.x
                            val startY = change.position.y
                            val edgeX = size.width * GESTURE_EDGE_HORIZONTAL
                            val edgeY = size.height * GESTURE_EDGE_VERTICAL
                            if (startX < edgeX || startX > size.width - edgeX ||
                                startY < edgeY || startY > size.height - edgeY
                            ) {
                                return@detectDragGestures
                            }
                            val horizontal = kotlin.math.abs(drag.x) > kotlin.math.abs(drag.y)
                            if (horizontal) {
                                viewModel.onGestureSeek(drag.x, size.width.toFloat())
                            } else {
                                viewModel.onVerticalDrag(
                                    startX < size.width / 2,
                                    drag.y, size.height.toFloat()
                                )
                            }
                        }
                    )
                }
        )

        // 审核359：竖屏播放器右下角常驻全屏按钮。
        // 原来全屏只在顶栏那一排里，竖屏宽度受限时会被挤进横滑区，用户找不到。
        if (portrait && !isInPiP) {
            androidx.compose.material3.IconButton(
                onClick = {
                    context.findActivity()?.requestedOrientation =
                        android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp),
            ) {
                Icon(
                    Icons.Default.Fullscreen,
                    "全屏",
                    tint = Color.White,
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.35f), CircleShape)
                        .padding(6.dp)
                        .size(22.dp),
                )
            }
        }

        // 手势提示浮层（快进/音量/亮度）
        if (state.gestureSeekDeltaMs != 0L && !isInPiP) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Surface(color = Color.Black.copy(0.6f), shape = MaterialTheme.shapes.medium) {
                    Text(
                        (if (state.gestureSeekDeltaMs > 0) "快进 " else "快退 ") +
                            "${kotlin.math.abs(state.gestureSeekDeltaMs) / 1000}s",
                        color = Color.White, modifier = Modifier.padding(16.dp)
                    )
                }
            }
        }
    }
    if (portrait && detailBvid.isNotEmpty() && detailPager != null && commentViewModel != null) {
        val scope = rememberCoroutineScope()
        val page = detailPager.currentPage
        // 画面下方一行互动：点赞/投币/收藏/分享等，横滑不占画面高度
        Row(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PlayerActionChip(
                icon = Icons.Filled.ThumbUp,
                label = formatCount(likeCount),
                tint = if (isLiked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                selected = isLiked,
                onClick = { viewModel.toggleLike() },
                onLongClick = { viewModel.likeCoinFav() },
            )
            PlayerActionChip(
                icon = Icons.Default.MonetizationOn,
                label = formatCount(coinCountTotal),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                onClick = { showCoinSheet = true },
            )
            PlayerActionChip(
                icon = if (isFavorited) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                label = formatCount(favCount),
                tint = if (isFavorited) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                onClick = { viewModel.loadFavFolders(); showFavSheet = true },
            )
            PlayerActionChip(
                icon = Icons.Default.Share,
                label = "分享",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                onClick = { showShareSheet = true },
            )
            PlayerActionChip(
                icon = Icons.Default.Download,
                label = "下载",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                onClick = { viewModel.downloadCurrent(context.applicationContext) },
            )
            PlayerActionChip(
                icon = if (inWatchLater) Icons.Default.Check else Icons.Default.Schedule,
                label = if (inWatchLater) "已添加" else "稍后看",
                tint = if (inWatchLater) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                selected = inWatchLater,
                onClick = { viewModel.addToWatchLater() },
            )
            PlayerActionChip(
                icon = Icons.Default.ThumbDown,
                label = "不喜欢",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                onClick = { viewModel.dislikeCurrent() },
            )
            PlayerActionChip(
                icon = Icons.Default.Collections,
                label = "分集",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                onClick = { showPartSheet = true },
            )
            PlayerActionChip(
                icon = Icons.Default.EditNote,
                label = "笔记",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                onClick = { onOpenNote(bvid, viewModel.currentCid(), viewModel.getCurrentTime()) },
            )
            PlayerActionChip(
                icon = Icons.Default.Headphones,
                label = "听音频",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                onClick = {
                    val mid = viewModel.videoOwnerMid
                    if (mid > 0L) onOpenListenAudio(mid)
                    else viewModel.showToast("无法获取 UP 主信息")
                },
            )
            PlayerActionChip(
                icon = Icons.Default.PhotoCamera,
                label = "取帧",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                onClick = {
                    val uri = viewModel.currentPlaybackUri()
                    if (uri == null) {
                        viewModel.showToast("当前视频不支持取帧")
                    } else {
                        captureScope.launch {
                            val file = com.example.piliai.playback.VideoFrameCapture.captureFromPlayback(
                                context = context.applicationContext,
                                uri = uri,
                                positionMs = viewModel.getCurrentTime(),
                                headers = viewModel.currentPlaybackHeaders(),
                                userAgent = viewModel.currentPlaybackUserAgent(),
                            )
                            viewModel.showToast(
                                if (file != null) "已保存到 ${file.absolutePath}"
                                else "取帧失败，请稍后重试"
                            )
                        }
                    }
                },
            )
        }
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
        ) {
            TabRow(selectedTabIndex = page) {
                Tab(
                    selected = page == 0,
                    onClick = { scope.launch { detailPager.animateScrollToPage(0) } },
                    text = { Text("简介") },
                )
                // 审核371：评论/推荐分页可按设置隐藏，索引随之前移
                if (showPlayerComment) {
                    Tab(
                        selected = page == commentPageIndex,
                        onClick = { scope.launch { detailPager.animateScrollToPage(commentPageIndex) } },
                        text = {
                            val n = commentState?.totalCount ?: 0
                            Text(if (n > 0) "评论 $n" else "评论")
                        },
                    )
                }
                if (showPlayerRelated) {
                    Tab(
                        selected = page == relatedPageIndex,
                        onClick = { scope.launch { detailPager.animateScrollToPage(relatedPageIndex) } },
                        text = { Text("推荐") },
                    )
                }
            }
            HorizontalPager(
                state = detailPager,
                modifier = Modifier.fillMaxSize(),
            ) { index ->
                if (index == 0) {
                    PlayerIntroPage(
                        title = if (detailEpoch >= 0) viewModel.videoTitle.ifBlank { title } else title,
                        owner = viewModel.videoOwner,
                        ownerMid = viewModel.videoOwnerMid,
                        ownerFace = viewModel.videoOwnerFace,
                        cover = viewModel.videoCover,
                        desc = viewModel.videoDesc,
                        tags = viewModel.videoTags,
                        parts = viewModel.partList(),
                        currentPart = state.currentPart,
                        videoView = viewModel.videoView,
                        videoDanmaku = viewModel.videoDanmaku,
                        videoReply = viewModel.videoReply,
                        videoPubdate = viewModel.videoPubdate,
                        related = state.related,
                        onPart = { viewModel.playPart(it) },
                        onTag = onSearchTag,
                        onRelated = { viewModel.playRelated(it) },
                        onOpenComments = {
                            if (commentPageIndex >= 0) {
                                scope.launch { detailPager.animateScrollToPage(commentPageIndex) }
                            }
                        },
                        onOpenUser = onOpenUser,
                        onShowCollection = { if (viewModel.partList().size > 1) showPartSheet = true },
                        viewModel = viewModel,
                        isFollowingOwner = isFollowingOwner,
                        followerCount = viewModel.ownerFollowerCount,
                        aiSummary = if (aiEpoch >= 0) viewModel.aiSummary else "",
                        aiLoading = aiLoading,
                        aiError = aiError,
                    )
                } else if (showPlayerComment && index == commentPageIndex) {
                    com.example.piliai.ui.comments.CommentScreen(
                        bvid = detailBvid,
                        onBack = { scope.launch { detailPager.animateScrollToPage(0) } },
                        onGoLogin = {},
                        embedded = true,
                        viewModel = commentViewModel,
                    )
                } else if (showPlayerRelated && index == relatedPageIndex) {
                    PlayerRelatedPage(
                        related = state.related,
                        onRelated = { viewModel.playRelated(it) },
                    )
                }
            }
        }
    }
    }
}

@Composable
private fun PlayerRelatedPage(
    related: List<VideoPlayerViewModel.RelatedItem>,
    onRelated: (VideoPlayerViewModel.RelatedItem) -> Unit,
) {
    if (related.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("暂无相关推荐", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(related, key = { it.bvid }) { item ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onRelated(item) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                coil.compose.AsyncImage(
                    model = item.pic.toHttpsUrl(),
                    contentDescription = item.title,
                    modifier = Modifier
                        .width(128.dp)
                        .height(72.dp)
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(com.example.piliai.ui.theme.PiliTokens.cardCornerRadius)),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.title, maxLines = 2, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        listOf(item.author, item.viewText, item.durationText)
                            .filter { it.isNotBlank() }
                            .joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun PlayerActionChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: Color,
    onClick: () -> Unit,
    selected: Boolean = false,
    onLongClick: (() -> Unit)? = null,
) {
    // 审核332：对齐 PiliNara ActionRowLineItem——胶囊描边，选中填色。
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(
                if (selected) MaterialTheme.colorScheme.secondaryContainer
                else Color.Transparent
            )
            .then(
                if (onLongClick != null) {
                    Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
                } else {
                    Modifier.clickable(onClick = onClick)
                }
            )
            .then(
                if (!selected) {
                    Modifier.border(
                        1.dp,
                        MaterialTheme.colorScheme.secondaryContainer,
                        RoundedCornerShape(50),
                    )
                } else Modifier
            )
            .padding(horizontal = 12.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(15.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = tint)
    }
}

/** 视频链接（番剧走 bangumi/play） */
private fun videoLink(bvid: String): String = if (bvid.startsWith("ep"))
    "https://www.bilibili.com/bangumi/play/$bvid"
else "https://www.bilibili.com/video/$bvid"

/** 系统分享面板，失败退回剪贴板 */
private fun shareViaSystem(context: android.content.Context, bvid: String) {
    val link = videoLink(bvid)
    val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(android.content.Intent.EXTRA_TEXT, link)
    }
    val shared = runCatching {
        context.startActivity(android.content.Intent.createChooser(send, "分享到"))
    }.isSuccess
    if (!shared) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("link", link))
    }
}

/** 复制链接到剪贴板 */
private fun copyVideoLink(context: android.content.Context, bvid: String) {
    val link = videoLink(bvid)
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    cm.setPrimaryClip(android.content.ClipData.newPlainText("link", link))
    android.widget.Toast.makeText(context, "链接已复制", android.widget.Toast.LENGTH_SHORT).show()
}

/** 用浏览器打开原页面 */
private fun openInBrowser(context: android.content.Context, bvid: String) {
    val link = videoLink(bvid)
    val ok = runCatching {
        context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, link.toUri()))
    }.isSuccess
    if (!ok) {
        android.widget.Toast.makeText(context, "没有可用的浏览器", android.widget.Toast.LENGTH_SHORT).show()
    }
}

@Composable
private fun PlayerIntroPage(
    title: String,
    owner: String,
    ownerMid: Long,
    ownerFace: String,
    cover: String,
    desc: String,
    tags: List<String>,
    parts: List<VideoPlayerViewModel.PartInfo>,
    currentPart: Int,
    videoView: Long,
    videoDanmaku: Long,
    videoReply: Long,
    videoPubdate: Long,
    related: List<VideoPlayerViewModel.RelatedItem>,
    onPart: (Int) -> Unit,
    onTag: (String) -> Unit,
    onRelated: (VideoPlayerViewModel.RelatedItem) -> Unit,
    onOpenComments: () -> Unit,
    onOpenUser: (Long) -> Unit,
    onShowCollection: (() -> Unit)? = null,
    viewModel: VideoPlayerViewModel,
    isFollowingOwner: Boolean,
    aiSummary: String,
    aiLoading: Boolean = false,
    aiError: String? = null,
    followerCount: Long = 0L,
    useRelatedGrid: Boolean = true,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(title.ifBlank { "正在播放" }, style = MaterialTheme.typography.titleMedium)
                if (owner.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Row(
                        Modifier.fillMaxWidth().clickable(enabled = ownerMid > 0L) { onOpenUser(ownerMid) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        coil.compose.AsyncImage(
                            model = ownerFace.toHttpsUrl(),
                            contentDescription = owner,
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape),
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(owner, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                buildList {
                                    if (followerCount > 0L) add("${formatCount(followerCount)}粉丝")
                                    if (ownerMid > 0L) add("进入主页")
                                }.joinToString(" · "),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        androidx.compose.material3.TextButton(
                            onClick = { viewModel.followOwner(ownerMid) },
                            enabled = ownerMid > 0L,
                        ) {
                            Text(if (isFollowingOwner) "已关注" else "关注")
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    buildList {
                        if (videoView > 0L) add("${formatCount(videoView)}播放")
                        if (videoDanmaku > 0L) add("${formatCount(videoDanmaku)}弹幕")
                        if (videoReply > 0L) add("${formatCount(videoReply)}评论")
                        if (videoPubdate > 0L) add(
                            java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CHINA)
                                .format(java.util.Date(videoPubdate * 1000))
                        )
                    }.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (desc.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    var expanded by remember { androidx.compose.runtime.mutableStateOf(false) }
                    Text(
                        desc,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = if (expanded) Int.MAX_VALUE else 3,
                        modifier = Modifier.clickable { expanded = !expanded },
                    )
                    Text(
                        if (expanded) "收起" else "展开",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .padding(top = 2.dp)
                            .clickable { expanded = !expanded },
                    )
                }
                if (tags.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        tags.take(12).forEach { tag ->
                            Text(
                                "#$tag",
                                modifier = Modifier
                                    .padding(end = 8.dp)
                                    .clickable { onTag(tag) }
                                    .background(
                                        MaterialTheme.colorScheme.surfaceContainerHigh,
                                        RoundedCornerShape(50),
                                    )
                                    .padding(horizontal = 10.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onOpenComments) { Text("查看评论") }
                    if (onShowCollection != null) {
                        TextButton(onClick = onShowCollection) { Text("分集") }
                    }
                }
            }
        }
        // 审核334：AI 总结（PiliNara 的 AI 按钮接在简介区右侧，
        // 这里做成一块可展开卡片）。数据已在 loadAiConclusion。
        item {
            var aiExpanded by remember { androidx.compose.runtime.mutableStateOf(false) }
            Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            if (aiSummary.isBlank()) viewModel.loadAiConclusion()
                            aiExpanded = !aiExpanded
                        }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.AutoAwesome,
                        null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("AI 总结", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.weight(1f))
                    Text(
                        if (aiExpanded) "收起" else "查看",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                if (aiExpanded) {
                    if (aiSummary.isBlank()) {
                        LaunchedEffect(aiExpanded) { viewModel.loadAiConclusion() }
                        // 审核348：区分加载中 / 失败 / 本身没有三种情况，
                        // 原来一律显示「暂无 AI 总结」，用户以为功能坏了。
                        when {
                            aiLoading -> Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                )
                                Spacer(Modifier.width(8.dp))
                                Text("正在获取 AI 总结…", style = MaterialTheme.typography.bodySmall)
                            }
                            aiError != null -> Text(
                                aiError,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                            else -> Text(
                                "该视频没有 AI 总结",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        Text(aiSummary, style = MaterialTheme.typography.bodySmall)
                        viewModel.aiOutline.forEach { (ts, title) ->
                            Text(
                                "%d:%02d %s".format(ts / 60, ts % 60, title),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .padding(top = 4.dp)
                                    .clickable { viewModel.seekTo(ts * 1000) },
                            )
                        }
                    }
                }
            }
        }
        if (parts.size > 1) {
            item {
                Text(
                    "分集（${parts.size}）",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            item {
                // 审核330：多 P 用横滑卡片。原来是整列文字行，
                // 50P 的视频要滚很久才能找到想看的那一 P。
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(parts.size) { index ->
                        val part = parts[index]
                        val selected = part.page == currentPart
                        Column(
                            modifier = Modifier
                                .width(132.dp)
                                .clip(androidx.compose.foundation.shape.RoundedCornerShape(com.example.piliai.ui.theme.PiliTokens.cardCornerRadius))
                                .background(
                                    if (selected) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surfaceContainerHigh
                                )
                                .clickable { onPart(index) }
                                .padding(10.dp),
                        ) {
                            Text(
                                "P${part.page}",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                part.part.ifBlank { "第${part.page}P" },
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 2,
                                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                                else MaterialTheme.colorScheme.onSurface,
                            )
                            if (part.durationSec > 0) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "%d:%02d".format(part.durationSec / 60, part.durationSec % 60),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
        if (related.isEmpty()) {
            item {
                Text(
                    "暂无相关推荐",
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else if (useRelatedGrid) {
            items(related, key = { it.bvid }) { item ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onRelated(item) }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    coil.compose.AsyncImage(
                        model = item.pic.toHttpsUrl(),
                        contentDescription = item.title,
                        modifier = Modifier
                            .width(128.dp)
                            .height(72.dp)
                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(com.example.piliai.ui.theme.PiliTokens.cardCornerRadius)),
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(item.title, maxLines = 2, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            listOf(item.author, item.viewText, item.durationText)
                                .filter { it.isNotBlank() }
                                .joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        } else {
            // 审核331：横屏/竖屏都用两列网格，和首页卡片密度一致。
            // 原来只有单行大图列表，一屏放不下几个。
            val rows = (related.size + 1) / 2
            items(rows) { rowIndex ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    for (col in 0..1) {
                        val index = rowIndex * 2 + col
                        if (index < related.size) {
                            val video = related[index]
                            Column(
                                Modifier
                                    .weight(1f)
                                    .clickable { onRelated(video) },
                            ) {
                                coil.compose.AsyncImage(
                                    model = video.pic.toHttpsUrl(),
                                    contentDescription = video.title,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .aspectRatio(16f / 9f)
                                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(com.example.piliai.ui.theme.PiliTokens.cardCornerRadius)),
                                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    video.title,
                                    maxLines = 2,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    listOf(video.author, video.viewText)
                                        .filter { it.isNotBlank() }
                                        .joinToString(" · "),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                            }
                        } else {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun EngagementButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    count: String = "",
    onClick: () -> Unit,
    tintColor: Color,
    // 审核346：横屏高度紧张，不显示文字标签，只留图标与计数
    showLabel: Boolean = true,
    // 审核轮146：长按 = 一键三连（B站官方交互）
    onLongClick: (() -> Unit)? = null
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(if (showLabel) 4.dp else 2.dp)) {
        // 审核241（P2-13）：原实现同时给 IconButton 的 onClick 和外层
        // combinedClickable 的 onClick 挂了同一个回调 → 单击触发两次
        // （点赞再取消，点赞数闪一下又回去）。改为 Box + combinedClickable
        // 单一入口，长按/单击都只走一处。
        Box(
            modifier = Modifier
                .size(if (showLabel) 48.dp else 38.dp)
                .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                .then(
                    if (onLongClick != null) {
                        Modifier.combinedClickable(
                            onClick = onClick,
                            onLongClick = onLongClick
                        )
                    } else {
                        Modifier.clickable(onClick = onClick)
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, label, tint = tintColor, modifier = Modifier.size(if (showLabel) 24.dp else 20.dp))
        }
        if (count.isNotEmpty()) {
            Text(count, color = tintColor, style = MaterialTheme.typography.labelSmall)
        }
        if (showLabel) {
            Text(label, color = Color.White.copy(alpha = 0.8f),
                style = MaterialTheme.typography.labelSmall)
        }
    }
}

/** 审核313：倍速档位显示（1 →「正常」由调用方处理；0.75 →「0.75x」） */
private fun formatSpeed(speed: Float): String {
    val r = Math.round(speed * 100f) / 100f
    val s = if (r % 1.0f == 0.0f) r.toInt().toString() else r.toString()
    return "${s}x"
}

private fun formatTime(ms: Long): String {
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
    else String.format(Locale.ROOT, "%d:%02d", minutes, seconds)
}

class VideoPlayerViewModelFactory(
    private val context: android.content.Context
) : androidx.lifecycle.ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(VideoPlayerViewModel::class.java)) {
            return VideoPlayerViewModel(context) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

private fun Context.findActivity(): android.app.Activity? {
    var ctx = this
    while (ctx is android.content.ContextWrapper) {
        if (ctx is android.app.Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private fun PlayerView?.pipAspectRatio(): Rational {
    val size = this?.player?.videoSize ?: return Rational(16, 9)
    if (size.width <= 0 || size.height <= 0 ||
        !size.pixelWidthHeightRatio.isFinite() || size.pixelWidthHeightRatio <= 0f
    ) return Rational(16, 9)
    var ratio = size.width.toDouble() * size.pixelWidthHeightRatio / size.height
    if (size.unappliedRotationDegrees == 90 || size.unappliedRotationDegrees == 270) {
        ratio = 1.0 / ratio
    }
    // Android restricts ordinary PiP windows to this aspect-ratio range.
    val supportedRatio = ratio.coerceIn(1.0 / 2.39, 2.39)
    val numerator = kotlin.math.round(supportedRatio * 10_000).toInt()
    return when {
        numerator.toLong() * 239 < 100L * 10_000 -> Rational(100, 239)
        numerator.toLong() * 100 > 239L * 10_000 -> Rational(239, 100)
        else -> Rational(numerator, 10_000)
    }
}

/**
 * 审核242：播放器手势安全区（bv BvPlayerController.kt:901-915/938-944）。
 * 水平 10% / 垂直 20% 边缘内不响应拖拽手势，避免用户只想轻推
 * 却莫名跳进度/改音量。
 */
private const val GESTURE_EDGE_HORIZONTAL = 0.10f
private const val GESTURE_EDGE_VERTICAL = 0.20f

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private fun PlayerView?.buildPipParams(
    autoEnter: Boolean,
    includeSourceRectHint: Boolean
): PictureInPictureParams {
    val surface = this?.videoSurfaceView ?: this
    val validBounds = surface != null && surface.isAttachedToWindow &&
        surface.width > 0 && surface.height > 0
    val builder = PictureInPictureParams.Builder()
        .setAspectRatio(pipAspectRatio())
        .setAutoEnterEnabled(autoEnter && validBounds)
    if (includeSourceRectHint && validBounds) {
        val location = IntArray(2)
        surface.getLocationInWindow(location)
        builder.setSourceRectHint(android.graphics.Rect(
            location[0], location[1],
            location[0] + surface.width, location[1] + surface.height
        ))
    }
    return builder.build()
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
fun android.app.Activity.enterPiP(playerView: PlayerView? = null) {
    enterPictureInPictureMode(
        playerView.buildPipParams(
            autoEnter = playerView?.player?.isPlaying == true,
            includeSourceRectHint = !isInPictureInPictureMode
        )
    )
}

/** 审核：PlayerView 配置集中于此并显式 OptIn Media3 UnstableApi（消除 lint UnsafeOptInUsageError） */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private fun createPlayerView(
    ctx: android.content.Context,
    player: androidx.media3.exoplayer.ExoPlayer?
): androidx.media3.ui.PlayerView {
    // Kazumi 特性：渲染器切换（SurfaceView 默认 / TextureView）
    //
    // 注意：Media3（本项 1.3.1）的 PlayerView 没有公开 setSurfaceType（javap 已核实），
    // surface_type 只能在 inflate 时通过 XML 属性生效。
    // 因此这里按偏好选择对应布局 inflate。
    // DataStore 是异步的，故读 RendererPrefs 的进程内同步缓存，
    // 避免在 view 工厂里 runBlocking（冷启动卡帧）。
    val layout = if (com.example.piliai.utils.RendererPrefs.useTextureView) {
        com.example.piliai.R.layout.player_view_texture
    } else {
        com.example.piliai.R.layout.player_view_surface
    }
    val view = android.view.LayoutInflater.from(ctx).inflate(layout, null, false)
        as androidx.media3.ui.PlayerView
    view.apply {
        this.player = player
        resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
        useController = false
        setShowNextButton(false)
        setShowPreviousButton(false)
        controllerAutoShow = false
        controllerShowTimeoutMs = 0
    }
    return view
}