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
import androidx.compose.foundation.lazy.LazyColumn
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
@OptIn(ExperimentalMaterial3Api::class)
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
    var showRelatedSheet by remember { mutableStateOf(false) }
    var showIntroSheet by remember { mutableStateOf(false) }
    var showCoinSheet by remember { mutableStateOf(false) }
    var showFavSheet by remember { mutableStateOf(false) }
    
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
    
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
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
            AndroidView(
                factory = { ctx ->
                    createPlayerView(ctx, viewModel.player).also { playerViewRef = it }
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
                shape = RoundedCornerShape(12.dp),
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
                    androidx.compose.material3.TextButton(onClick = { viewModel.setError(null) }) {
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
                    Text(title, color = Color.White, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                    Row {
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
                        IconButton(onClick = { showDanmakuSheet = true }) {
                            Icon(
                                if (state.danmakuOn) Icons.Default.Subtitles else Icons.Default.SubtitlesOff,
                                "Danmaku", tint = Color.White
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
                    Slider(
                        value = if (state.duration > 0) state.currentTime.toFloat() / state.duration else 0f,
                        onValueChange = { fraction ->
                            isSeeking = true
                            seekPreviewSec = ((state.duration * fraction) / 1000).toLong()
                        },
                        onValueChangeFinished = {
                            isSeeking = false
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
                
                // Engagement buttons
                Row(modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly) {
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
                        tintColor = Color.White
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
                        label = "评论", onClick = { onOpenComments(bvid) },
                        tintColor = Color.White
                    )
                    // 审核319：笔记入口。传当前播放进度，编辑器打开时
                    // 「插入时间点」按这个秒数打戳。
                    EngagementButton(
                        icon = Icons.Default.EditNote,
                        label = "笔记",
                        onClick = { onOpenNote(bvid, cid, viewModel.getCurrentTime()) },
                        tintColor = Color.White
                    )
                    EngagementButton(
                        icon = Icons.Default.AutoAwesome,
                        label = "相关",
                        count = if (state.related.isNotEmpty()) state.related.size.toString() else "",
                        onClick = { showRelatedSheet = true },
                        tintColor = Color.White
                    )
                    EngagementButton(
                        icon = Icons.Default.Schedule,
                        label = "稍后看", onClick = { viewModel.addToWatchLater() },
                        tintColor = Color.White
                    )
                    // 离线下载（批次I 接线：发起队列）
                    EngagementButton(
                        icon = Icons.Default.Download,
                        label = "下载",
                        onClick = { viewModel.downloadCurrent(context.applicationContext) },
                        tintColor = Color.White
                    )
                    EngagementButton(
                        icon = Icons.Default.Info,
                        label = "简介",
                        onClick = {
                            viewModel.loadAiConclusion()
                            showIntroSheet = true
                        },
                        tintColor = Color.White
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
                        tintColor = Color.White
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
                        tintColor = Color.White
                    )
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

        // 弹幕设置面板
        if (showDanmakuSheet) {
            ModalBottomSheet(onDismissRequest = { showDanmakuSheet = false }) {
                Column(Modifier.padding(16.dp)) {
                    Text("弹幕设置", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(12.dp))
                    // 发送弹幕（批次L5）
                    var dmInput by remember { mutableStateOf("") }
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
                                    viewModel.sendDanmaku(dmInput)
                                    dmInput = ""
                                }
                            },
                            enabled = dmInput.isNotBlank()
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Send, "发送弹幕")
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
                    .background(Color.Black.copy(0.6f), androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
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
                    .background(Color.Black.copy(0.7f), androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }

        // Gesture handler
        if (!isInPiP) Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { showControls = !showControls },
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
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun EngagementButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    count: String = "",
    onClick: () -> Unit,
    tintColor: Color,
    // 审核轮146：长按 = 一键三连（B站官方交互）
    onLongClick: (() -> Unit)? = null
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // 审核241（P2-13）：原实现同时给 IconButton 的 onClick 和外层
        // combinedClickable 的 onClick 挂了同一个回调 → 单击触发两次
        // （点赞再取消，点赞数闪一下又回去）。改为 Box + combinedClickable
        // 单一入口，长按/单击都只走一处。
        Box(
            modifier = Modifier
                .size(48.dp)
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
            Icon(icon, label, tint = tintColor, modifier = Modifier.size(24.dp))
        }
        if (count.isNotEmpty()) {
            Text(count, color = tintColor, style = MaterialTheme.typography.labelSmall)
        }
        Text(label, color = Color.White.copy(alpha = 0.8f), 
            style = MaterialTheme.typography.labelSmall)
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
        setShowNextButton(false)
        setShowPreviousButton(false)
        controllerAutoShow = true
        controllerShowTimeoutMs = 3000
    }
    return view
}