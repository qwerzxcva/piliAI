package com.example.piliai.ui.pages.member

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.piliai.data.model.FollowUser
import com.example.piliai.data.remote.BiliApiClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.example.piliai.utils.toHttpsUrl

/** 关注/粉丝列表 ViewModel（批次H） */
class FollowListViewModel(private val mid: Long, private val followers: Boolean) : ViewModel() {
    private val api = BiliApiClient()
    private val _state = MutableStateFlow(FollowListState())
    val state: StateFlow<FollowListState> = _state.asStateFlow()

    data class FollowListState(
        val users: List<FollowUser> = emptyList(),
        val page: Int = 1,
        val hasMore: Boolean = false,
        val isLoading: Boolean = false,
        val error: String? = null
    )

    init { load(1) }

    fun load(page: Int) {
        if (page == 1) _state.value = _state.value.copy(isLoading = true, error = null)
        viewModelScope.launch {
            val call = if (followers) api.getFollowers(mid, page) else api.getFollowings(mid, page)
            call.onSuccess { resp ->
                if (resp.code == 0) {
                    val list = resp.data
                    _state.value = _state.value.copy(
                        isLoading = false,
                        users = if (page == 1) list else _state.value.users + list,
                        page = page, hasMore = list.isNotEmpty()
                    )
                } else {
                    _state.value = _state.value.copy(isLoading = false, error = resp.message.ifEmpty { "加载失败" })
                }
            }.onFailure { e ->
                _state.value = _state.value.copy(
                    isLoading = false,
                    // 审核292：过错误码翻译
                    error = com.example.piliai.data.remote.BiliErrorTranslator
                        .translate(e.message) ?: e.message ?: "关注列表加载失败"
                )
            }
        }
    }

    fun loadMore() {
        val s = _state.value
        if (s.isLoading || !s.hasMore) return
        load(s.page + 1)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FollowListScreen(
    mid: Long,
    followers: Boolean = false,
    onBack: () -> Unit = {},
    onOpenUser: (Long) -> Unit = {},
    viewModel: FollowListViewModel = viewModel(
        key = "$mid-$followers",
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                FollowListViewModel(mid, followers) as T
        }
    )
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (followers) "粉丝" else "关注") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                }
            )
        }
    ) { padding ->
        when {
            state.isLoading && state.users.isEmpty() -> Box(
                Modifier.padding(padding).fillMaxSize(), Alignment.Center
            ) { CircularProgressIndicator() }
            state.users.isEmpty() -> Box(
                Modifier.padding(padding).fillMaxSize(), Alignment.Center
            ) { Text("暂无数据", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            else -> LazyColumn(
                Modifier.padding(padding).fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(state.users, key = { it.mid }) { user ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onOpenUser(user.mid) }.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AsyncImage(
                            model = user.face.toHttpsUrl(),
                            contentDescription = user.uname,
                            modifier = Modifier.size(46.dp).clip(CircleShape),
                            contentScale = ContentScale.Crop
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(user.uname, fontWeight = FontWeight.SemiBold)
                            if (user.sign.isNotEmpty()) {
                                Text(
                                    user.sign,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
                if (state.hasMore) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(12.dp), Alignment.Center) {
                            LaunchedEffect(state.users.size) { viewModel.loadMore() }
                            CircularProgressIndicator(Modifier.size(22.dp))
                        }
                    }
                }
            }
        }
    }
}
