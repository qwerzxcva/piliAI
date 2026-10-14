package com.example.piliai.todaywatch

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.piliai.utils.toHttpsUrl

/**
 * 「今日推荐」首页 Section：UP 主榜（横向） + 推荐视频队列（横向卡片）。
 *
 * 风格对齐 Kototoro：12dp 圆角、去阴影、surface 色阶分层、推荐理由 chip。
 *
 * 交互：
 * - 点击卡片 → onVideoClick
 * - 长按卡片 → 弹出「不感兴趣」菜单 → onDislike（写 Room 负反馈 + 重新生成）
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TodayWatchSection(
    modifier: Modifier = Modifier,
    plan: TodayWatchPlan,
    onVideoClick: (bvid: String) -> Unit,
    onSeeAll: () -> Unit = {},
    onDislike: (video: RcmdCandidate) -> Unit = {},
) {
    Column(modifier = modifier.fillMaxWidth()) {
        // 标题行（审核280：改用通用 SectionHeader，规格对齐 Kototoro HomeSectionHeader）
        com.example.piliai.ui.components.SectionHeader(
            title = "今日推荐",
            actionText = "查看全部",
            onClick = onSeeAll,
            modifier = Modifier.fillMaxWidth(),
        )

        // UP 主榜（横向）
        if (plan.upRanks.isNotEmpty()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(plan.upRanks, key = { it.mid }) { rank ->
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                    ) {
                        Text(
                            text = rank.name,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        // 推荐视频队列（横向卡片）
        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(plan.videoQueue, key = { it.bvid }) { video ->
                TodayWatchCard(
                    video = video,
                    reason = plan.explanationByBvid[video.bvid].orEmpty(),
                    onClick = { onVideoClick(video.bvid) },
                    onDislike = { onDislike(video) },
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TodayWatchCard(
    video: RcmdCandidate,
    reason: String,
    onClick: () -> Unit,
    onDislike: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }
    Box {
        Card(
            onClick = onClick,
            modifier = Modifier
                .width(220.dp)
                .combinedClickable(onClick = onClick, onLongClick = { showMenu = true }),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        ) {
            Column {
                AsyncImage(
                    model = video.cover.toHttpsUrl(),
                    contentDescription = video.title,
                    modifier = Modifier.fillMaxWidth().aspectRatio(1.6f).clip(
                        RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)
                    ),
                    contentScale = ContentScale.Crop,
                )
                Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                    Text(
                        text = video.title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (reason.isNotBlank()) {
                        Spacer(Modifier.height(3.dp))
                        Text(
                            text = reason,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = video.ownerName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        // 长按弹出「不感兴趣」
        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false },
        ) {
            DropdownMenuItem(
                text = { Text("不感兴趣") },
                onClick = { showMenu = false; onDislike() },
            )
        }
    }
}
