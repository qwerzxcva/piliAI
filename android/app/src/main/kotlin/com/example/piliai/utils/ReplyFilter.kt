package com.example.piliai.utils

import com.example.piliai.data.model.CommentNode

/**
 * 审核368：评论区过滤（对齐 PiliNara reply_settings）。
 *
 * 原来评论区没有任何过滤能力，屏蔽词只作用于首页推荐。
 * 这里补上 PiliNara 的四条规则：
 * - 关键词过滤（正则，非法表达式容错）
 * - 屏蔽用户（按 mid）
 * - 屏蔽带货评论
 * - 保留 UP 主 / 置顶 / UP 主点赞 / UP 主参与的评论（豁免前述规则）
 */
object ReplyFilter {

    data class Config(
        /** 关键词正则（多行或 | 分隔），空表示不过滤 */
        val banWords: String = "",
        /** 屏蔽的用户 mid，逗号分隔 */
        val blockedMids: String = "",
        /** 屏蔽带货评论 */
        val hideGoodsComments: Boolean = false,
        /** 保留 UP 主自己的评论 */
        val keepUpper: Boolean = true,
        /** 保留 UP 主置顶 */
        val keepTop: Boolean = true,
        /** 保留 UP 主参与回复的 */
        val keepUpperReplied: Boolean = true,
    ) {
        val enabled: Boolean
            get() = banWords.isNotBlank() || blockedMids.isNotBlank() || hideGoodsComments

        private val banRegex: Regex? = try {
            if (banWords.isBlank()) null else Regex(banWords, RegexOption.IGNORE_CASE)
        } catch (_: Exception) {
            null
        }

        private val blockedMidSet: Set<Long> = blockedMids
            .split(',', ' ', '\n')
            .mapNotNull { it.trim().toLongOrNull() }
            .toSet()

        /** 该评论是否应被隐藏。upperMid 用于豁免判断。 */
        fun shouldHide(comment: CommentNode, upperMid: Long, isTop: Boolean = false): Boolean {
            if (!enabled) return false
            val mid = comment.member?.mid ?: 0L

            // 豁免：这些评论永不过滤（PiliNara「保留…」系列语义）
            if (keepTop && isTop) return false
            if (keepUpper && mid > 0L && mid == upperMid) return false
            // 服务端不返回「UP 主点赞过」这个字段，无法实现该豁免，不臆造。
            if (keepUpperReplied && mid != upperMid &&
                comment.replies.any { it.member?.mid == upperMid }
            ) return false

            if (mid > 0L && mid in blockedMidSet) return true

            val text = comment.content?.message.orEmpty()
            if (hideGoodsComments && looksLikeGoods(text)) return true
            if (banRegex?.containsMatchIn(text) == true) return true
            return false
        }

        /**
         * 带货评论的粗略判据：PiliNara 用服务端商品卡片字段，
         * 这里拿不到该字段，退化为「出现购买引导词 + 链接/价格特征」。
         */
        private fun looksLikeGoods(text: String): Boolean {
            if (text.isBlank()) return false
            val keywords = listOf("下单", "优惠券", "领券", "秒杀", "包邮", "点击购买", "限时价")
            val hasKeyword = keywords.any { text.contains(it, ignoreCase = true) }
            val hasPrice = Regex("""[¥￥]\s?\d+|\d+\s?元""").containsMatchIn(text)
            return hasKeyword && (hasPrice || text.contains("http", ignoreCase = true))
        }
    }
}
