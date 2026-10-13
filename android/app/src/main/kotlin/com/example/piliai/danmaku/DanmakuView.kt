package com.example.piliai.danmaku

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/**
 * 弹幕渲染 View（批次L14 重写：修复此前"只 add 无渲染"的半成品）。
 * Canvas 直接绘制滚动弹幕，支持 透明度/大小倍率/暂停恢复/轨道避让。
 */
class DanmakuView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** 一条渲染中的弹幕 */
    data class LiveDanmaku(
        val id: Long,
        val text: String,
        val color: Int,
        val fontSizePx: Float,
        val row: Int,
        var x: Float,           // 当前左边缘 x
        val width: Float,       // 文本宽
        val speed: Float        // px/ms
    )

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val active = mutableListOf<LiveDanmaku>()
    private var lastFrame = 0L
    /** 审核259：流控用的「最近 1 秒内已入列条数」与窗口起点 */
    private var rateWindowStart = 0L
    private var rateWindowCount = 0
    private var nextId = 1L
    private var maxRows = 8
    private var running = true

    var alphaFactor: Float = 1f     // 0..1 全局透明度
    var scaleFactor: Float = 1f     // 0.5..2 全局大小倍率
    var speedFactor: Float = 1f     // 速度倍率
    // 审核233：PiliPlus 弹幕细节对齐
    var showAreaFactor: Float = 1f      // 0.1..1 显示区域（可用轨道占比，PiliPlus danmakuShowArea）
    var strokeWidthFactor: Float = 1.5f // 描边宽度（PiliPlus danmakuStrokeWidth，0=无描边）
    var lineHeightFactor: Float = 1.4f  // 行高倍率（PiliPlus danmakuLineHeight）
    // 审核249：字重（PiliPlus danmakuFontWeight，0=细 1=常规 2=中粗 3=粗）。
    // 此前 dmFontWeightFlow 只被 SettingsViewModel collect，渲染层零消费者。
    var fontWeight: Int = 1
    // 审核259：每秒弹幕上限（bv DanmakuRateLimiter 的 maxPerSecond）。
    // 高能时段（名场面/结尾）一秒能涌进几十条，不限制会糊成一片。0 = 不限。
    var maxPerSecond: Int = 0
    // 审核249：点击弹幕交互（PiliPlus enableTapDanmaku）。
    // 此前 enableTapDanmakuFlow 只被 SettingsViewModel collect，
    // DanmakuView 既没有开关也没有触摸处理 → 设置项纯摆设。
    var tapInteractEnabled: Boolean = false
    /** 点中弹幕时回调（参数：弹幕文本）。未命中任何弹幕不回调。 */
    var onDanmakuTapped: ((String) -> Unit)? = null
    var mergeDanmaku: Boolean = false   // 审核233：相同文本弹幕去重（PiliPlus mergeDanmaku）
    var massiveMode: Boolean = false    // 审核233：海量模式（不限制轨道行数）
    private val mergedTexts = HashSet<String>()
    private var lastFontSizePx = 0f

    /** 计算落点轨道：找一条已腾出的行，满了就重叠最少的一行 */
    private fun pickRow(width: Float): Int {
        // 审核233：showArea 限制可用轨道数（例如 0.5 = 只用上半屏 4/8 行）；massiveMode 按屏幕高度全量铺轨
        val effectiveMax = if (massiveMode && measuredHeight > 0 && lastFontSizePx > 0f) {
            (measuredHeight / (lastFontSizePx * lineHeightFactor)).toInt().coerceAtLeast(maxRows)
        } else maxRows
        val usableRows = (effectiveMax * showAreaFactor).toInt().coerceIn(1, effectiveMax)
        val rows = FloatArray(usableRows)
        for (d in active) {
            val r = d.row.coerceIn(0, usableRows - 1)
            rows[r] = maxOf(rows[r], d.x + d.width)
        }
        val edge = measuredWidth.toFloat()
        var best = 0
        var bestRight = Float.MAX_VALUE
        for (i in 0 until usableRows) {
            if (rows[i] < edge) return i
            if (rows[i] < bestRight) { bestRight = rows[i]; best = i }
        }
        return best
    }

    /** 外部追加弹幕（播放到对应时间点 / 本地发送回显）。批次audit24：接通屏蔽规则 */
    fun add(text: String, color: Int, fontSizeSp: Int, uid: Long = 0L) {
        // 审核轮125：暂停时不再丢弃弹幕（暂停时发弹幕原来会静默丢失）——
        // 仍入列，x 不滚动（onDraw 里 running=false 时不动），恢复后继续滚。
        if (text.isBlank() || measuredWidth <= 0) return
        if (com.example.piliai.ui.settings.DanmakuBlockViewModel.shouldBlock(text, uid)) return
        // 审核233：合并重复弹幕——同文本在屏期间只显示一条（滚出屏后允许再次出现）
        if (mergeDanmaku) {
            if (!mergedTexts.add(text)) return
        }
        // 审核259：每秒密度流控。超限的弹幕直接丢弃（不入列、不绘制）。
        // 与 mergeDanmaku 互补：merge 去重同文本，这个管总量。
        if (maxPerSecond > 0 && !allowByRate()) return
        val fs = fontSizeSp * resources.displayMetrics.density * scaleFactor
        lastFontSizePx = fs
        paint.textSize = fs
        val w = paint.measureText(text)
        val row = pickRow(w)
        // 基准速度：整屏 6 秒滚过
        val speed = (measuredWidth + w) / 6000f * speedFactor
        active.add(
            LiveDanmaku(
                id = nextId++, text = text, color = color,
                fontSizePx = fs, row = row, x = measuredWidth.toFloat(),
                width = w, speed = speed
            )
        )
        invalidate()
    }

    /**
     * 审核259：滑动窗口限流（bv DanmakuRateLimiter）。
     * 窗口 1 秒；跨窗口时重置计数。调用方需自行保证 maxPerSecond > 0。
     */
    private fun allowByRate(): Boolean {
        val now = System.currentTimeMillis()
        if (now - rateWindowStart >= 1000L) {
            rateWindowStart = now
            rateWindowCount = 0
        }
        if (rateWindowCount >= maxPerSecond) return false
        rateWindowCount++
        return true
    }

    /** 清空（seek/切P/弹幕开关） */
    fun clearAll() {
        active.clear()
        mergedTexts.clear()
        // 审核259：seek/切P 后重置流控窗口，否则新位置的高能段落会被旧窗口卡住
        rateWindowStart = 0L
        rateWindowCount = 0
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val now = System.currentTimeMillis()
        val dt = if (lastFrame == 0L) 16L else min(now - lastFrame, 100L)
        lastFrame = if (running) now else lastFrame
        val baseTextSize = active.firstOrNull()?.fontSizePx ?: (40f * scaleFactor)
        val rowH = baseTextSize * lineHeightFactor
        var needInvalidate = running && active.isNotEmpty()
        val it2 = active.iterator()
        while (it2.hasNext()) {
            val d = it2.next()
            if (running) d.x -= d.speed * dt
            if (d.x + d.width < 0) {
                it2.remove()
                if (mergeDanmaku) mergedTexts.remove(d.text)
                continue
            }
            // 审核轮194：textSize 变化才赋值（反复 set 触发内部 measure，弱机掉帧）
            if (paint.textSize != d.fontSizePx) paint.textSize = d.fontSizePx
            // 审核249：字重。只在变化时换 typeface（fake bold 比换 Typeface 便宜，
            // 但中粗/细需要真 typeface 才看得出来）。
            val wantBold = fontWeight >= 2
            if (paint.isFakeBoldText != wantBold) paint.isFakeBoldText = wantBold
            paint.alpha = (alphaFactor * 255).toInt().coerceIn(0, 255)
            // 审核233：描边（PiliPlus danmakuStrokeWidth——黑描边提升可读性，0 关闭）
            val sw = strokeWidthFactor * resources.displayMetrics.density
            if (sw > 0f) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = sw
                paint.color = android.graphics.Color.BLACK
                canvas.drawText(d.text, d.x, rowH * (d.row + 1), paint)
                paint.style = Paint.Style.FILL
            }
            paint.color = d.color
            canvas.drawText(d.text, d.x, rowH * (d.row + 1), paint)
            // 审核轮194：暂停时弹幕尚未滚出屏，仍需持续绘制（否则一帧后消失）
            if (!running) needInvalidate = true
        }
        if (needInvalidate) invalidate()
    }

    /**
     * 审核249：点击命中测试。
     *
     * 弹幕是 drawText 画出来的，没有 View 边界，只能按「文字矩形 + 行高」反推。
     * y 落在 rowH*(row+1) 基线上下半个行高内、x 落在 [x, x+width] 内算命中。
     */
    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {        if (!tapInteractEnabled || onDanmakuTapped == null) return false
        if (event.action != android.view.MotionEvent.ACTION_UP) return true
        // lint ClickableViewAccessibility：自定义 View 覆写 onTouchEvent 必须调
        // performClick()（读屏软件靠它触发点击）。放在命中测试之前无条件执行，
        // 避免 lint 认为「只在某一分支里点击」。
        performClick()
        val tx = event.x
        val ty = event.y
        val baseTextSize = active.firstOrNull()?.fontSizePx ?: (40f * scaleFactor)
        val rowH = baseTextSize * lineHeightFactor
        // 后画的在上层，倒序找最先命中的
        for (i in active.indices.reversed()) {
            val d = active[i]
            val baseline = rowH * (d.row + 1)
            if (ty < baseline - rowH * 0.75f || ty > baseline + rowH * 0.35f) continue
            if (tx < d.x || tx > d.x + d.width) continue
            onDanmakuTapped?.invoke(d.text)
            return true
        }
        return false
    }

    /**
     * 审核249：覆写 performClick 以满足 lint ClickableViewAccessibility。
     *
     * 无障碍服务（读屏/开关控制）是通过 performClick 触发点击的，而不是
     * onTouchEvent。本 View 的点击目标（哪条弹幕）依赖触点坐标，
     * performClick 没有坐标信息，因此这里只转调父类默认行为，
     * 实际的弹幕命中仍在 onTouchEvent 里做。
     */
    override fun performClick(): Boolean = super.performClick()

    /** 暂停/恢复（跟随播放器） */
    fun setRunning(running: Boolean) {
        this.running = running
        if (running) { lastFrame = 0L; invalidate() }
    }
}
