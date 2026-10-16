package com.example.piliai.todaywatch

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.piliai.database.PiliNaraDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 「今日推荐」ViewModel：异步生成 Plan，不阻塞首屏。
 *
 * 状态机：Idle → Loading → Success(plan) / Hidden
 * - 未登录或候选为空 → Hidden（不显示 Section，避免空占位）
 */
class TodayWatchViewModel(app: Application) : AndroidViewModel(app) {

    sealed interface UiState {
        data object Idle : UiState
        data object Loading : UiState
        data class Success(val plan: TodayWatchPlan) : UiState
        data object Hidden : UiState
        data class Error(val message: String) : UiState
    }

    private val _state = MutableStateFlow<UiState>(UiState.Idle)
    val state: StateFlow<UiState> = _state

    /** 当前模式/策略（持久化，供独立页展示与生成用） */
    private val _mode = MutableStateFlow(TodayWatchMode.RELAX)
    val mode: StateFlow<TodayWatchMode> = _mode
    private val _strategy = MutableStateFlow(TodayWatchStrategy.BALANCED)
    val strategy: StateFlow<TodayWatchStrategy> = _strategy

    private val storage = com.example.piliai.utils.StorageManager(app)
    private val repository: TodayWatchRepository by lazy {
        TodayWatchRepository(PiliNaraDatabase.getDatabase(app))
    }
    private var loaded = false
    private var prefsLoaded = false

    /** 进入首页时调用一次；loaded 防重复触发 */
    fun loadIfNeeded() {
        if (loaded) return
        loaded = true
        refresh()
    }

    /** 独立页每次进入都刷（设置变更后立即生效） */
    fun refreshOnEnter() = refresh()

    /** 切换模式（持久化 + 重新生成） */
    fun setMode(mode: TodayWatchMode) {
        _mode.value = mode
        viewModelScope.launch(Dispatchers.IO) {
            storage.setTodayWatchMode(if (mode == TodayWatchMode.RELAX) "relaxed" else "learn")
        }
        refresh()
    }

    /** 切换策略（持久化 + 重新生成） */
    fun setStrategy(strategy: TodayWatchStrategy) {
        _strategy.value = strategy
        viewModelScope.launch(Dispatchers.IO) {
            storage.setTodayWatchStrategy(strategy.name.lowercase())
        }
        refresh()
    }

    fun refresh() {
        _state.value = UiState.Loading
        viewModelScope.launch {
            // 首次加载时读持久化偏好
            if (!prefsLoaded) {
                prefsLoaded = true
                val (m, s) = kotlinx.coroutines.withContext(Dispatchers.IO) {
                    val mRaw = runCatching { storage.todayWatchModeFlow.first() }.getOrDefault("relaxed")
                    val sRaw = runCatching { storage.todayWatchStrategyFlow.first() }.getOrDefault("balanced")
                    mRaw to sRaw
                }
                _mode.value = if (m == "learn") TodayWatchMode.LEARN else TodayWatchMode.RELAX
                _strategy.value = when (s) {
                    "affinity" -> TodayWatchStrategy.AFFINITY
                    "explore" -> TodayWatchStrategy.EXPLORE
                    else -> TodayWatchStrategy.BALANCED
                }
            }
            repository.buildPlan(mode = _mode.value, strategy = _strategy.value)
                .onSuccess { plan ->
                    _state.value = if (plan.videoQueue.isEmpty()) UiState.Hidden else UiState.Success(plan)
                }
                .onFailure {
                    _state.value = UiState.Error(
                        it.message ?: "今日推荐加载失败，请重试"
                    )
                }
        }
    }

    /** 点踩后重新生成 */
    fun dislike(bvid: String, ownerMid: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.dislike(bvid, ownerMid)
            refresh()
        }
    }
}
