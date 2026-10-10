package com.example.piliai.ui.subscribe

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
fun BangumiSubjectScreen(subjectId: Long, onBack: () -> Unit) {
    var subject by remember(subjectId) { mutableStateOf<BangumiApi.Subject?>(null) }
    var loadFailed by remember(subjectId) { mutableStateOf(false) }
    var retryAttempt by remember(subjectId) { mutableStateOf(0) }

    LaunchedEffect(subjectId, retryAttempt) {
        subject = null
        loadFailed = false
        try {
            subject = BangumiApi.subject(subjectId)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            loadFailed = true
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
                    }
                }
            }
        }
    }
}
