package com.example.piliai.ui.theme

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween

/**
 * 审核318：全站动效 token（移植 Kototoro KototoroMotion.kt 的设计）。
 *
 * Kototoro 的做法是把动效收敛到一个对象里，语义化命名（fadeFast /
 * spatialDefault / PullRefresh ...），业务页不允许出现裸 tween 字面量。
 * piliai 此前全仓库零动效：grep AnimatedContent / Crossfade /
 * animateFloatAsState / updateTransition 全部零命中，骨架屏那一个
 * infiniteTransition 是唯一的动画。
 *
 * 与 Kototoro 的差异：Kototoro 以 MaterialTheme.motionScheme 为单一来源
 * （M3 1.3+ 的 MotionScheme API）。本项目 material3 1.3.1 未提供该 API
 * （已用 javap/class 扫描核实），因此这里用命名常量自建同一层语义，
 * 约束「动效只从 PiliMotion 取」不变。
 *
 * 使用方式：
 * ```
 * val alpha by animateFloatAsState(
 *     targetValue = if (visible) 1f else 0f,
 *     animationSpec = PiliMotion.fadeDefault(),
 *     label = "alpha",
 * )
 * ```
 */
object PiliMotion {

    // ---- 语义淡入淡出（透明度、颜色） ----

    /** 快速反馈：点击涟漪、开关、hover。 */
    fun <T> fadeFast(): FiniteAnimationSpec<T> = tween(100, easing = FastOutSlowInEasing)

    /** 默认淡入淡出：内容显隐、空态切换。 */
    fun <T> fadeDefault(): FiniteAnimationSpec<T> = tween(200, easing = FastOutSlowInEasing)

    /** 慢速淡入淡出：大面积遮罩、页面级过渡。 */
    fun <T> fadeSlow(): FiniteAnimationSpec<T> = tween(350, easing = FastOutSlowInEasing)

    // ---- 空间动画（位移、尺寸） ----

    /** 快速位移：tab 指示器、小浮层。 */
    fun <T> spatialFast(): FiniteAnimationSpec<T> = tween(200, easing = FastOutSlowInEasing)

    /** 默认位移：卡片展开、面板滑入。 */
    fun <T> spatialDefault(): FiniteAnimationSpec<T> = tween(300, easing = FastOutSlowInEasing)

    /** 慢速位移：页面转场、大块内容重排。 */
    fun <T> spatialSlow(): FiniteAnimationSpec<T> = tween(450, easing = FastOutSlowInEasing)

    // ---- 物理弹簧（带回弹的交互） ----

    /** 按压回弹：底部 tab、点赞按钮（Kototoro ThumbSpring 参数）。 */
    fun <T> pressSpring(): FiniteAnimationSpec<T> =
        spring(dampingRatio = 0.72f, stiffness = 520f)

    /** 下拉刷新落定（Kototoro PullSettleSpring 参数）。 */
    fun <T> pullSettleSpring(): FiniteAnimationSpec<T> =
        spring(dampingRatio = 0.78f, stiffness = 420f)

    /** 横向列表入场（Kototoro RailEntrySpring 参数）。 */
    fun <T> railEntrySpring(): FiniteAnimationSpec<T> =
        spring(dampingRatio = 0.82f, stiffness = 420f)

    // ---- 任务专用 ----

    /** 下拉刷新指示（Kototoro PullRefresh 参数）。 */
    fun <T> pullRefresh(): FiniteAnimationSpec<T> = tween(180)

    /** 页面缩放（Kototoro PageZoom 参数）。 */
    fun <T> pageZoom(): FiniteAnimationSpec<T> = tween(220)

    /** 匀速：进度条、shimmer、loading。 */
    fun <T> linear(durationMillis: Int): FiniteAnimationSpec<T> =
        tween(durationMillis, easing = LinearEasing)

    /** 弹簧参数常量，供需要自行配置 spring() 的地方复用（不重复写字面量）。 */
    object SpringParams {
        const val THUMB_DAMPING = 0.72f
        const val THUMB_STIFFNESS = 520f
        const val PULL_DAMPING = 0.78f
        const val PULL_STIFFNESS = 420f
        const val RAIL_DAMPING = 0.82f
        const val RAIL_STIFFNESS = 420f
        val DampingRatio: (Float) -> Float = { it }
        val StiffnessLow: Float = Spring.StiffnessLow
        val StiffnessMedium: Float = Spring.StiffnessMedium
    }
}
