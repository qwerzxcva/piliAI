package com.example.piliai.ui.subscribe

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import com.example.piliai.R
import kotlinx.coroutines.CancellationException
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.piliai.data.remote.BangumiApi

/**
 * 订阅条目的 Bangumi 介绍页：封面、评分、开播日期与简介。
 * 视频本身仍来自订阅源，本页只展示 Bangumi 的公开资料。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BangumiSubjectScreen(
    subjectId: Long,
    onBack: () -> Unit,
    onFollow: (Long, String, String, Int) -> Unit = { _, _, _, _ -> }
) {
    var subject by remember(subjectId) { mutableStateOf<BangumiApi.Subject?>(null) }
    var loadFailed by remember(subjectId) { mutableStateOf(false) }
    var retryAttempt by remember(subjectId) { mutableStateOf(0) }
    // 审核261：剧集列表（Bangumi /v0/episodes）。此前本页只有资料展示，
    // 用户看不到「新出了哪几集 / 什么时候放的」。
    var episodes by remember(subjectId) { mutableStateOf<List<BangumiApi.Episode>>(emptyList()) }
    var episodesFailed by remember(subjectId) { mutableStateOf(false) }

    LaunchedEffect(subjectId, retryAttempt) {
        subject = null
        loadFailed = false
        episodes = emptyList()
        episodesFailed = false
        try {
            subject = BangumiApi.subject(subjectId)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            loadFailed = true
        }
    }

    // 剧集单独加载：失败不影响资料展示（有些条目（如音乐/小说）没有章节，
    // /v0/episodes 返回 total=0 是正常的，不应当成错误）。
    LaunchedEffect(subjectId, retryAttempt, subject?.id) {
        val id = subject?.id ?: return@LaunchedEffect
        if (id <= 0L) return@LaunchedEffect
        try {
            episodes = BangumiApi.episodes(id).data.sortedBy { it.order }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            episodesFailed = true
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(subject?.title ?: stringResource(R.string.bangumi_subject_details)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.bangumi_back)
                        )
                    }
                },
                // 审核243：追番入口。此前详情页只能看资料，看完还得回订阅页
                // 找条目点爱心，闭环断在这里。
                actions = {
                    val s = subject
                    if (s != null && s.id > 0L) {
                        IconButton(
                            onClick = {
                                onFollow(s.id, s.title, s.cover, s.bestEpisodeCount)
                            }
                        ) {
                            Icon(
                                Icons.Default.Favorite,
                                contentDescription = "追番",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        val loadedSubject = subject
        when {
            loadFailed -> Column(Modifier.padding(padding).padding(24.dp)) {
                Text(stringResource(R.string.bangumi_load_failed))
                Spacer(Modifier.height(16.dp))
                Button(onClick = { retryAttempt += 1 }) {
                    Text(stringResource(R.string.bangumi_retry))
                }
            }
            loadedSubject == null -> CircularProgressIndicator(
                Modifier.padding(padding).padding(24.dp)
            )
            else -> {
                val s = loadedSubject
                Column(
                    Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                ) {
                    if (s.cover.isNotBlank()) {
                        AsyncImage(
                            model = s.cover,
                            contentDescription = s.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxWidth().height(240.dp)
                        )
                    }
                    Column(Modifier.padding(16.dp)) {
                        Text(s.title, style = MaterialTheme.typography.headlineMedium)
                        Spacer(Modifier.height(8.dp))
                        val metadata = mutableListOf<String>()
                        if (s.rating.score > 0) {
                            metadata.add(stringResource(R.string.bangumi_rating, s.rating.score))
                        }
                        if (s.rating.rank > 0) {
                            metadata.add(stringResource(R.string.bangumi_rank, s.rating.rank))
                        }
                        if (s.date.isNotBlank()) metadata.add(s.date)
                        if (s.bestEpisodeCount > 0) {
                            metadata.add(stringResource(R.string.bangumi_episodes, s.bestEpisodeCount))
                        }
                        Text(
                            metadata.joinToString(" · "),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            s.summary.ifBlank { stringResource(R.string.bangumi_summary_unavailable) },
                            style = MaterialTheme.typography.bodyLarge
                        )

                        // 审核261：剧集列表（Bangumi /v0/episodes）。
                        // 单独失败/无章节都不影响上面的资料展示。
                        if (episodesFailed) {
                            Spacer(Modifier.height(16.dp))
                            Text(
                                "剧集列表加载失败",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.error
                            )
                        } else if (episodes.isNotEmpty()) {
                            Spacer(Modifier.height(20.dp))
                            Text(
                                "剧集（共 ${episodes.size} 集）",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Spacer(Modifier.height(8.dp))
                            episodes.forEach { ep ->
                                Row(
                                    Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // 集数徽标：SP/OP/ED 用类型标签，正片用集数
                                    Surface(
                                        color = MaterialTheme.colorScheme.secondaryContainer,
                                        shape = RoundedCornerShape(6.dp)
                                    ) {
                                        Text(
                                            text = ep.typeLabel.ifBlank { "${ep.order}" },
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                                            modifier = Modifier.padding(
                                                horizontal = 8.dp, vertical = 3.dp
                                            )
                                        )
                                    }
                                    Spacer(Modifier.width(10.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            ep.displayTitle,
                                            style = MaterialTheme.typography.bodyMedium,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        val meta = buildList {
                                            ep.airdate?.takeIf { it.isNotBlank() }?.let { add(it) }
                                            // duration 是 "24m" 文本；为空时用 duration_seconds 兜底
                                            val dur = ep.duration?.takeIf { it.isNotBlank() }
                                                ?: ep.durationSeconds.takeIf { it > 0 }
                                                    ?.let { "${it / 60} 分钟" }
                                            dur?.let { add(it) }
                                        }
                                        if (meta.isNotEmpty()) {
                                            Text(
                                                meta.joinToString(" · "),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        // 单集简介（Bangumi 有 desc 的条目才显示）
                                        ep.desc?.takeIf { it.isNotBlank() }?.let { d ->
                                            Text(
                                                d.trim().replace("\r\n", " "),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 3,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.padding(top = 2.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
