package com.example.piliai.ui.components

/**
 * 审核234：BiliPai/Kototoro 视觉语言组件库。
 * - SearchPill：B站 App 首页顶部搜索胶囊
 * - SegmentedPillRow：浮动胶囊分段控件（顶部分类/设置分类共用）
 * - SkeletonVideoCardRow：首页加载骨架屏（替代转圈，感知更快）
 */
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.piliai.ui.theme.PiliCardShape
import com.example.piliai.ui.theme.PiliCoverScrimStops
import com.example.piliai.ui.theme.rememberCoverRimBrush
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.PlayCircleOutline
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** B站 App 风格搜索胶囊：整条可点，进入搜索页 */
@Composable
fun SearchPill(
    hint: String = "搜索视频、UP主",
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            // 审核241（P2-4）：padding 必须放在 clickable 之前。
            // 原顺序先 clickable 后 padding，触摸区不含那 14dp 内边距，
            // 点胶囊最左/最右边缘无反应。
            .padding(horizontal = 14.dp)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.Search,
            contentDescription = null,
            // 审核270：搜索图标用强调色 —— Kototoro 的识别特征是强调色
            // 贯穿关键交互点，之前整条胶囊都是同一种中性色，没有重点。
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(18.dp).height(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            hint,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke()
    }
}

/**
 * 审核282：单条可横滑的分类胶囊（BiliPai 首页顶部分类）。
 *
 * 取代原先「两个 SegmentedPillRow 塞进一个 LazyRow」的写法：那会把
 * 7 个 tab 拆成 4+3 两组并列胶囊，语义混乱、整行只能横滑。
 *
 * 规格：一组胶囊共用一个浅底色容器，选中项 primaryContainer 填充 +
 * primary 文字；间距 2dp（组内）/ 6dp（左右内容边）。
 */
@Composable
fun CategoryPillStrip(
    labels: List<String>,
    selectedIndex: Int,
    modifier: Modifier = Modifier,
    onSelect: (Int) -> Unit = {},
) {
    androidx.compose.foundation.lazy.LazyRow(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        itemsIndexed(labels) { i, label ->
            val selected = i == selectedIndex
            // 审核318：选中态底色与文字色走动画（PiliMotion.fadeFast）——此前是
            // 硬切换，切分类时「啪」地变色。Kototoro 分段控件同样做颜色补间。
            val pillBg by androidx.compose.animation.animateColorAsState(
                targetValue = if (selected) MaterialTheme.colorScheme.primaryContainer
                else Color.Transparent,
                animationSpec = com.example.piliai.ui.theme.PiliMotion.fadeFast(),
                label = "pillBg",
            )
            val pillText by androidx.compose.animation.animateColorAsState(
                targetValue = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                animationSpec = com.example.piliai.ui.theme.PiliMotion.fadeFast(),
                label = "pillText",
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(pillBg)
                    // padding 提到 clickable 之前，保证触达区含内边距
                    .padding(horizontal = 14.dp, vertical = 8.dp)
                    .clickable { onSelect(i) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    color = pillText,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * 审核280：Section 标题行。移植 Kototoro HomeSectionHeader
 * （core-ui/.../core/ui/home/HomeUi.kt:204）的规格：
 * - 标题 titleSmall + SemiBold + onSurface，maxLines=1
 * - 计数 labelMedium + onSurfaceVariant
 * - 右侧箭头 16.dp，tint onSurfaceVariant
 * - 整条可点，SpaceBetween 布局
 */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    count: Int? = null,
    actionText: String? = null,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (count != null) {
                Text(
                    text = if (count > 999) "999+" else count.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (actionText != null) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = actionText,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Icon(
                    Icons.Filled.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

/**
 * 审核280：横向视频列表行。移植 Kototoro HomeListRailRow
 * （core-ui/.../core/ui/home/HomeUi.kt:367）的封面规格与文字层级：
 * - 封面：shadow(2.dp) + clip + surfaceVariant + border(0.5.dp, rim)
 * - 标题：bodyMedium + SemiBold + 14.5sp/18sp 行高，onSurface，maxLines=2
 * - 副信息：labelMedium + Medium，primary 色（Kototoro 用强调色做支撑行）
 * - 来源行：labelSmall，onSurfaceVariant
 *
 * 用于排行榜/热门/搜索结果等横排列表页——这些页面此前是裸 Row +
 * HorizontalDivider + 硬编码色值，是全 App「廉价感」的主要来源。
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun VideoListRow(
    title: String,
    coverUrl: String,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    sourceText: String? = null,
    coverWidth: androidx.compose.ui.unit.Dp = 120.dp,
    trailing: (@Composable () -> Unit)? = null,
    onClick: () -> Unit = {},
    onLongClick: (() -> Unit)? = null,
) {
    val rimBrush = rememberCoverRimBrush()
    val coverShape = RoundedCornerShape(10.dp)
    val rowModifier = if (onLongClick != null) {
        // combinedClickable 是 ExperimentalFoundationApi，与调用方（如收藏页多选）
        // 的 OptIn 保持一致；只在真传了 onLongClick 时才走这条路径。
        Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
    } else {
        Modifier.clickable(onClick = onClick)
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(rowModifier)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .width(coverWidth)
                .aspectRatio(16f / 9f)
                .shadow(2.dp, coverShape, clip = false)
                .clip(coverShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(0.5.dp, rimBrush, coverShape),
        ) {
            AsyncImage(
                model = coverUrl,
                contentDescription = title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.5.sp,
                    lineHeight = 18.sp,
                ),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (supportingText != null) {
                Text(
                    text = supportingText,
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.Medium,
                    ),
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (sourceText != null) {
                Text(
                    text = sourceText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        trailing?.invoke()
    }
}

/** 浮动胶囊分段控件（BiliPai HomeTopTabFloatingDock 风格简化版） */
@Composable
fun SegmentedPillRow(
    labels: List<String>,
    selectedIndex: Int,
    modifier: Modifier = Modifier,
    onSelect: (Int) -> Unit = {},
) {
    // 审核298（真 bug）：这里必须 horizontalScroll。原来是无滚动的 Row，
    // 标签多了（设置页 5 个分类）超出屏幕的部分既不可见也不可点——
    // 用户看到「播放」被裁掉、点了没反应，就是「设置页分类不跳转」。
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(4.dp)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        labels.forEachIndexed { i, label ->
            val selected = i == selectedIndex
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(
                        // 审核270：选中态用 primary 容器色（而非中性凸起）——
                        // Kototoro 的分段控件选中项是强调色填充，一眼看出在哪页。
                        if (selected) MaterialTheme.colorScheme.primaryContainer
                        else Color.Transparent
                    )
                    // 审核241（P2-3）：padding 提到 clickable 之前，
                    // 否则点击区 = 文字区（约 32dp 高），低于 48dp 建议值。
                    .padding(horizontal = 14.dp, vertical = 10.dp)
                    .clickable { onSelect(i) },
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 首页骨架卡（shimmer） */
@Composable
fun SkeletonVideoCard(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val alpha by transition.animateFloat(
        initialValue = 0.35f, targetValue = 0.75f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse),
        label = "alpha",
    )
    val shimmer = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = alpha)
    // 审核280：与成功态卡片同构——封面 + 封面内两行占位条（标题已改为 overlay，
    // 不再有封面外的标题条）+ 封面外一行 UP 主占位。
    Column(modifier = modifier) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(12.dp))
                .background(shimmer)
        )
        Spacer(Modifier.height(6.dp))
        Row(
            Modifier.padding(horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(16.dp).clip(CircleShape).background(shimmer))
            Spacer(Modifier.width(5.dp))
            Box(Modifier.width(56.dp).height(10.dp).clip(RoundedCornerShape(5.dp)).background(shimmer))
        }
    }
}

/**
 * 审核285：网格视频卡（首页 / 分区页共用）。
 *
 * 从 HomeScreen.VideoCardItem 提出为公共组件——分区页 ZoneCard 此前是同一套
 * 结构的旧版副本（封面在 Card 内、无描边、间距 4dp），提出后两页共用一份实现。
 *
 * 视觉规格对齐 Kototoro 源码级：
 * - 封面：0.5dp rim 渐变描边 + 2dp 阴影 + surfaceVariant 底色
 *   （rememberCoverRimBorderBrush / TabletPosterCover，SharedContentCardBadges.kt:246）
 * - 标题压在封面底部 overlay（CompactGridTitleOverlay，SharedPosterCover.kt:63），
 *   不再放封面外下方：省垂直空间、层级更清晰，是 Kototoro 的标志性观感
 * - 遮罩用 6 段色标（末端黑 72%），标题带文字阴影保证亮封面可读
 * - UP 主行留在封面外下方（视频 App 需要作者归属，Kototoro 的副标题同理）
 *
 * 审核320：补长按（combinedClickable）与发布时间。combinedClickable 属
 * ExperimentalFoundationApi，故在此显式 OptIn（与 VideoListRow 一致）。
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun VideoGridCard(
    title: String,
    coverUrl: String,
    modifier: Modifier = Modifier,
    durationText: String = "",
    viewCountText: String = "",
    danmakuCountText: String = "",
    authorName: String = "",
    authorFaceUrl: String = "",
    onAuthorClick: (() -> Unit)? = null,
    onClick: () -> Unit = {},
    // 审核320：长按（调出「不感兴趣/稍后再看/屏蔽」等操作）。
    // BiliPai/BiliRoamingX 的网格卡都支持长按，piliai 此前完全没有——
    // 用户无法在不喜欢的视频上做任何操作。
    onLongClick: (() -> Unit)? = null,
    // 审核320：发布时间（如「3天前」「1周前」）。Kototoro 卡片同样在
    // 作者行右侧展示附加信息，视频内容需要时间归属。
    publishTimeText: String = "",
    // 审核318：入场动画开关。LazyVerticalGrid 的 items 里传 index 控制逐项
    // 淡入上移（Kototoro HomeWaterfallMotionSpec 的做法）；不传则关闭，
    // 避免非列表场景（如骨架屏对照）出现意外动画。
    enterAnimation: Boolean = false,
    enterIndex: Int = 0,
) {
    val rimBrush = rememberCoverRimBrush()
    val coverShape = RoundedCornerShape(12.dp)
    val titleSize = 13.sp
    // 审核320：关闭入场动画时不创建 LaunchedEffect/动画状态。首页滚动性能
    // 由此避免每个网格 item 各自维护动画协程。
    var appeared by remember { androidx.compose.runtime.mutableStateOf(!enterAnimation) }
    if (enterAnimation) {
        LaunchedEffect(Unit) { appeared = true }
    }
    val enterProgress by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (appeared) 1f else 0f,
        animationSpec = if (enterAnimation) {
            androidx.compose.animation.core.tween(
                durationMillis = 220,
                delayMillis = (enterIndex.coerceIn(0, 12) * 30),
                easing = androidx.compose.animation.core.FastOutSlowInEasing,
            )
        } else {
            androidx.compose.animation.core.tween(durationMillis = 0)
        },
        label = "gridCardEnter",
    )
    val enterOffset = androidx.compose.ui.unit.lerp(
        androidx.compose.ui.unit.Dp(12f), androidx.compose.ui.unit.Dp(0f), enterProgress,
    )
    Column(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                alpha = enterProgress
                translationY = enterOffset.toPx()
            }
            // 审核320：长按支持。用 combinedClickable 而不是 clickable，
            // 否则长按手势被点击消费掉。indication 由默认 ripple 提供。
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            ),
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
                model = coverUrl,
                contentDescription = title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            // 播放量 + 弹幕：左上角一排（Kototoro 徽标同样叠在封面上层）
            if (viewCountText.isNotBlank() || danmakuCountText.isNotBlank()) {
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
                    if (viewCountText.isNotBlank()) {
                        Icon(
                            Icons.Filled.PlayCircleOutline,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(13.dp),
                        )
                        Spacer(Modifier.width(3.dp))
                        Text(
                            text = viewCountText,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                        )
                    }
                    if (danmakuCountText.isNotBlank()) {
                        if (viewCountText.isNotBlank()) Spacer(Modifier.width(6.dp))
                        Icon(
                            Icons.Filled.ChatBubbleOutline,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(13.dp),
                        )
                        Spacer(Modifier.width(3.dp))
                        Text(
                            text = danmakuCountText,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                        )
                    }
                }
            }
            // 时长：右上角
            if (durationText.isNotBlank()) {
                Text(
                    text = durationText,
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
                text = title,
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
        if (authorName.isNotBlank() || publishTimeText.isNotBlank()) {
            Row(
                modifier = Modifier
                    .padding(start = 2.dp, top = 6.dp)
                    .then(
                        if (onAuthorClick != null) Modifier.clickable { onAuthorClick() }
                        else Modifier
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (authorFaceUrl.isNotBlank()) {
                    AsyncImage(
                        model = authorFaceUrl,
                        contentDescription = null,
                        modifier = Modifier
                            .size(16.dp)
                            .clip(CircleShape),
                        contentScale = ContentScale.Crop,
                    )
                    Spacer(Modifier.width(5.dp))
                }
                Text(
                    text = authorName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                // 审核320：发布时间跟在 UP 主名后面（Kototoro 卡片同样在
                // 作者行展示附加信息）。用 spacer + 圆点分隔，避免挤在一起。
                if (publishTimeText.isNotBlank()) {
                    if (authorName.isNotBlank()) {
                        Text(
                            text = "·",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        )
                        Spacer(Modifier.width(3.dp))
                    }
                    Text(
                        text = publishTimeText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** 骨架网格（首页 Loading 态） */
@Composable
fun SkeletonVideoGrid(modifier: Modifier = Modifier, columns: Int = 2, rows: Int = 3) {
    // 审核271/283：间距与首页成功态一致（Kototoro GridSpacing 8dp、页边 12dp）——
    // 不一致会让加载完成后间距跳变，观感断层。列数由调用方按可用宽度算好传入，
    // 与成功态 LazyVerticalGrid 保持一致。
    Column(modifier = modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(rows) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(columns) {
                    SkeletonVideoCard(Modifier.weight(1f))
                }
            }
        }
    }
}
