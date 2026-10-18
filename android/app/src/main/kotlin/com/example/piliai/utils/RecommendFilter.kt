package com.example.piliai.utils

import com.example.piliai.data.model.VideoItem

/**
 * 审核233：推荐流过滤（移植 PiliPlus lib/utils/recommend_filter.dart）。
 * 全部参数来自 StorageManager 设置（DataStore 持久化），默认全关闭=行为与原版一致。
 */
object RecommendFilter {

    data class Config(
        val minDurationSec: Int = 0,
        val minPlay: Long = 0L,
        val minLikeRatioPercent: Int = 0,
        val banWords: String = "",
        val exemptFilterForFollowed: Boolean = true,
        /** 审核370：屏蔽 UP 主（mid，逗号分隔） */
        val blockedUps: String = "",
        /** 审核370：白名单 UP 主（永不屏蔽） */
        val whitelistUps: String = "",
    ) {
        val enabled: Boolean
            get() = minDurationSec > 0 || minPlay > 0 || minLikeRatioPercent > 0 ||
                banWords.isNotBlank() || blockedUps.isNotBlank()

        private val blockedUpSet: Set<Long> = blockedUps
            .split(',', ' ', '\n').mapNotNull { it.trim().toLongOrNull() }.toSet()

        private val whitelistUpSet: Set<Long> = whitelistUps
            .split(',', ' ', '\n').mapNotNull { it.trim().toLongOrNull() }.toSet()

        /** 该 UP 是否应被屏蔽（白名单优先） */
        fun shouldBlockUp(mid: Long): Boolean {
            if (mid <= 0L) return false
            if (mid in whitelistUpSet) return false
            return mid in blockedUpSet
        }

        /** 大小写不敏感正则（PiliPlus RegExp(caseSensitive:false) 对齐） */
        private val banRegex: Regex? = try {
            if (banWords.isBlank()) null else Regex(banWords, RegexOption.IGNORE_CASE)
        } catch (_: Exception) {
            null // 用户输入非法正则时不过滤（PiliPlus 同样容错）
        }

        /** 标题屏蔽词过滤 */
        fun filterTitle(title: String): Boolean = banRegex?.containsMatchIn(title) ?: false

        /** 播放量/点赞比过滤（PiliPlus filterLikeRatio 对齐） */
        fun filterLikeRatio(like: Long, view: Long): Boolean {
            if (view <= 0) return false
            if (view < minPlay) return true
            return like >= 0 && like * 100 < minLikeRatioPercent * view
        }

        /** 时长过滤 */
        fun filterDuration(durationSec: Int): Boolean =
            durationSec > 0 && durationSec < minDurationSec
    }

    /**
     * 过滤推荐/热门列表。followed 标记列表（关注流）可豁免。
     */
    fun filter(items: List<VideoItem>, config: Config, isFollowedFeed: Boolean = false): List<VideoItem> {
        if (!config.enabled) return items
        if (isFollowedFeed && config.exemptFilterForFollowed) return items
        return items.filter { item ->
            !config.shouldBlockUp(item.owner?.mid ?: 0L) &&
                !config.filterDuration(item.duration) &&
                !config.filterLikeRatio(item.stat.like, item.stat.displayView) &&
                !config.filterTitle(item.title)
        }
    }
}
