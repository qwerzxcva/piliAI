package com.example.piliai.ui.pages.article

import android.text.Html
import android.widget.TextView
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.piliai.data.model.ArticleData
import com.example.piliai.data.remote.BiliApiClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.example.piliai.utils.toHttpsUrl

// 审核97：复用 format 实例，避免 composable 内每次重组重建
private val articleDateFormat by lazy { java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CHINA) }

/**
 * 批次L23：专栏文章阅读页 ViewModel
 */
class ArticleViewModel : ViewModel() {
    private val _article = MutableStateFlow<ArticleData?>(null)
    val article: StateFlow<ArticleData?> = _article.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun load(id: Long) {
        if (_article.value != null) return
        viewModelScope.launch {
            BiliApiClient().getArticleView(id)
                .onSuccess { resp ->
                    if (resp.code == 0 && resp.data != null) _article.value = resp.data
                    else _error.value = resp.message ?: "文章加载失败"
                }
                .onFailure { _error.value = it.message ?: "文章加载失败" }
        }
    }
}

/**
 * 专栏文章阅读页（批次L23）：标题/作者/统计/正文 HTML 渲染
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArticleScreen(
    articleId: Long,
    onBack: () -> Unit = {}
) {
    val vm: ArticleViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    LaunchedEffect(articleId) { vm.load(articleId) }
    val article by vm.article.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("专栏", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        when {
            article == null && error == null -> Box(Modifier.padding(padding).fillMaxSize(), Alignment.Center) {
                CircularProgressIndicator()
            }
            error != null -> Box(Modifier.padding(padding).fillMaxSize(), Alignment.Center) {
                Text(error ?: "")
            }
            else -> article?.let { a ->
                Column(
                    Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())
                        .padding(16.dp)
                ) {
                    Text(a.title, style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            buildString {
                                append(a.author?.name.orEmpty())
                                if (a.publishTime > 0) {
                                    append(" · ")
                                    append(articleDateFormat.format(java.util.Date(a.publishTime * 1000)))
                                }
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    a.stats?.let { s ->
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "阅读 ${s.view} · 点赞 ${s.like} · 评论 ${s.reply} · 收藏 ${s.favorite}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (a.bannerUrl.isNotBlank()) {
                        Spacer(Modifier.height(12.dp))
                        AsyncImage(
                            model = a.bannerUrl.toHttpsUrl(),
                            contentDescription = null,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp)
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    // 正文 HTML（/x/article/view content 为 HTML 片段）
                    AndroidView(
                        factory = { ctx ->
                            TextView(ctx).apply {
                                textSize = 15f
                                setTextColor(android.graphics.Color.DKGRAY)
                                setLineSpacing(4f, 1.1f)
                                // 审核243（animeko P0-4）：Html.fromHtml 会丢掉 <img>
                                // 的 URL 与 <a> 的 href，图片只剩 alt 占位、链接变纯文本
                                // 且不可点。这里显式接 ImageGetter + MovementMethod 接回来。
                                movementMethod = android.text.method.LinkMovementMethod.getInstance()
                            }
                        },
                        update = { tv ->
                            // 审核：minSdk 24 >= N，直接用 FROM_HTML_MODE_COMPACT（lint ObsoleteSdkInt）
                            tv.text = Html.fromHtml(
                                a.content,
                                Html.FROM_HTML_MODE_COMPACT,
                                articleImageGetter(tv),
                                null
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(32.dp))
                }
            }
        }
    }
}

/**
 * 审核243（animeko P0-4）：专栏正文图片的 ImageGetter。
 *
 * `Html.fromHtml` 不带 ImageGetter 时会丢掉 `<img>` 的 URL，图片位置只剩
 * alt 文字。这里用 Coil 同步取图（submit().get() 走磁盘/内存缓存，
 * 专栏图小且首屏外懒加载，可接受）；失败返回 null 让 Html 退化成 alt
 * 文本，不会崩。
 */
private fun articleImageGetter(textView: android.widget.TextView): Html.ImageGetter =
    Html.ImageGetter { source ->
        if (source.isNullOrBlank()) return@ImageGetter null
        runCatching {
            val loader = coil.ImageLoader.Builder(textView.context).crossfade(false).build()
            val request = coil.request.ImageRequest.Builder(textView.context)
                .data(source)
                .build()
            // Coil 2.x 的 execute() 是 suspend，ImageGetter 在主线程同步调用，
            // 用 enqueue() 拿同步结果（内部仍走缓存优先）。
            val drawable = (loader.enqueue(request) as? coil.request.SuccessResult)
                ?.drawable ?: return@runCatching null
            val width = textView.width.takeIf { it > 0 }
                ?: textView.resources.displayMetrics.widthPixels
            val ratio = drawable.intrinsicHeight.toFloat() /
                drawable.intrinsicWidth.coerceAtLeast(1).toFloat()
            drawable.setBounds(0, 0, width, (width * ratio).toInt().coerceAtLeast(1))
            drawable
        }.getOrNull()
    }
