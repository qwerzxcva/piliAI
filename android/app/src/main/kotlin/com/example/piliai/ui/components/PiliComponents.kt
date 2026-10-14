package com.example.piliai.ui.components

/**
 * 审核234：BiliPai/Kototoro 视觉语言组件库。
 * - SearchPill：B站 App 首页顶部搜索胶囊
 * - SegmentedPillRow：浮动胶囊分段控件（顶部分类/设置分类共用）
 * - SkeletonVideoCardRow：首页加载骨架屏（替代转圈，感知更快）
 */
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import com.example.piliai.ui.theme.PiliCardShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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

/** 浮动胶囊分段控件（BiliPai HomeTopTabFloatingDock 风格简化版） */
@Composable
fun SegmentedPillRow(
    labels: List<String>,
    selectedIndex: Int,
    modifier: Modifier = Modifier,
    onSelect: (Int) -> Unit = {},
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(4.dp),
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

/** 骨架网格（首页 Loading 态） */
@Composable
fun SkeletonVideoGrid(modifier: Modifier = Modifier, rows: Int = 3) {
    // 审核271/280：间距与首页成功态一致（Kototoro GridSpacing 8dp、页边 12dp）——
    // 不一致会让加载完成后间距跳变，观感断层。
    Column(modifier = modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(rows) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SkeletonVideoCard(Modifier.weight(1f))
                SkeletonVideoCard(Modifier.weight(1f))
            }
        }
    }
}
