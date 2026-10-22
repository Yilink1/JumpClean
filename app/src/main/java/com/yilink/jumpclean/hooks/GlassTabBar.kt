package com.yilink.jumpclean.hooks

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import com.example.liquidglass.GlassAccessibilityMode
import com.example.liquidglass.LiquidGlassTabBar
import com.example.liquidglass.LiquidGlassView
import com.yilink.jumpclean.config.ConfigManager

/**
 * 玻璃绘制、透镜、滑动动画、拖拽和背景采样仍留在 [LiquidGlassTabBar] 内部。
 *
 * 这里只补库没有公开的三件事：
 * 1. 给库创建的 tab 文字/图标重新上色（库把普通态写死成亮/暗两套）；
 * 2. 微调水滴的公开光学参数，并在 JVM 堆或系统低内存时主动熔断降级；
 * 3. 把标签行抬到水滴上方，避免水滴折射出第二层白字。
 */
class GlassTabBar(context: Context) : LiquidGlassTabBar(context), ComponentCallbacks2 {
    private var labelSelected = 0xFF202633.toInt()
    private var labelNormal = 0x8C3A4252.toInt()
    private var dropletView: LiquidGlassView? = null
    private var qualityReduced = false
    private var memoryCallbacksRegistered = false
    private var nightMode = false
    private var lastHeapCheckTime = 0L

    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }

    init {
        tuneDroplet()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!memoryCallbacksRegistered) {
            context.applicationContext.registerComponentCallbacks(this)
            memoryCallbacksRegistered = true
        }
        // 水滴在标签行之后 add，默认盖住选中项；把标签行抬到水滴上方，
        // 避免水滴把已经着色的文字/图标再折射出一层白影（夜间尤其明显）。
        raiseTabsAboveDroplet()
        checkHeapAndAdjustQuality()
    }

    override fun onDetachedFromWindow() {
        if (memoryCallbacksRegistered) {
            try {
                context.applicationContext.unregisterComponentCallbacks(this)
            } catch (_: Throwable) {
            }
            memoryCallbacksRegistered = false
        }
        super.onDetachedFromWindow()
    }

    private fun raiseTabsAboveDroplet() {
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            // 标签行是唯一带子 View 的普通 ViewGroup；水滴本身也是 ViewGroup（LiquidGlassView），
            // 但不能把它当成标签行。
            if (child is ViewGroup && child !is LiquidGlassView && child.childCount > 0) {
                if (i != childCount - 1) {
                    bringChildToFront(child)
                    if (GlassStyle.devTrace()) {
                        ConfigManager.log("[LiquidGlass] 标签行已抬到水滴上方")
                    }
                }
                return
            }
        }
    }

    /**
     * 主动监控 JVM 堆内存（防 OOM 真正核心）：
     *
     * 宿主（Jump）发现页包含大量 Banner 与游戏流图片，Glide 缓存会把堆内存推向 400MB+。
     * 当 JVM 堆占用达到 78%（约 400MB / 512MB 上限）时，立即主动熔断降级为不透明轻质胶囊，
     * 彻底断开背景捕获并释放位图分配，抢在 ART 垃圾回收抛 OOM 之前保全进程。
     * 当堆内存回落到 65% 以下且稳定后，再平滑恢复液态透镜。
     */
    private fun checkHeapAndAdjustQuality() {
        val now = SystemClock.uptimeMillis()
        if (now - lastHeapCheckTime < 350L) return
        lastHeapCheckTime = now

        val rt = Runtime.getRuntime()
        val max = rt.maxMemory()
        if (max <= 0) return
        val used = rt.totalMemory() - rt.freeMemory()
        val usedRatio = used.toFloat() / max.toFloat()

        if (usedRatio >= 0.78f) {
            if (!qualityReduced) {
                qualityReduced = true
                accessibilityMode = GlassAccessibilityMode.FORCE_OPAQUE
                dropletView?.accessibilityMode = GlassAccessibilityMode.FORCE_OPAQUE
                enableBackdropBlur = false
                enableEdgeHighlight = false
                dropletView?.enableEdgeHighlight = false
                invalidate()
                ConfigManager.log(
                    "[LiquidGlass] JVM 堆熔断触发: 占用 ${(usedRatio * 100).toInt()}% (${used / 1024 / 1024}MB / ${max / 1024 / 1024}MB)，已降级为不透明轻质胶囊"
                )
            }
        } else if (usedRatio < 0.65f) {
            if (qualityReduced) {
                qualityReduced = false
                accessibilityMode = GlassAccessibilityMode.AUTO
                dropletView?.accessibilityMode = GlassAccessibilityMode.AUTO
                enableBackdropBlur = true
                enableEdgeHighlight = true
                dropletView?.enableEdgeHighlight = true
                invalidate()
                ConfigManager.log(
                    "[LiquidGlass] JVM 堆恢复正常: 占用 ${(usedRatio * 100).toInt()}% (${used / 1024 / 1024}MB / ${max / 1024 / 1024}MB)，已恢复液态透镜"
                )
            }
        }
    }

    /**
     * 库的透镜高光只集中在边缘法线方向，深色信息流上整条胶囊的轮廓容易“隐身”。
     * 补一圈 1dp 的极淡内描边，给底栏一个稳定的边界；它画在玻璃上、标签下。
     */
    override fun onDraw(canvas: Canvas) {
        checkHeapAndAdjustQuality()
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        val d = resources.displayMetrics.density
        val inset = 0.75f * d
        rimPaint.strokeWidth = 1f * d
        rimPaint.color = if (nightMode) 0x30FFFFFF else 0x22000000
        val radius = (height - inset * 2f) / 2f
        canvas.drawRoundRect(
            inset, inset, width - inset, height - inset,
            radius, radius, rimPaint
        )
    }

    // ── 系统低内存兜底（次要保底） ──
    override fun onTrimMemory(level: Int) {
        val reallyLow = level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
            level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
            level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE
        if (reallyLow && !qualityReduced) {
            qualityReduced = true
            accessibilityMode = GlassAccessibilityMode.FORCE_OPAQUE
            dropletView?.accessibilityMode = GlassAccessibilityMode.FORCE_OPAQUE
            enableBackdropBlur = false
            enableEdgeHighlight = false
            dropletView?.enableEdgeHighlight = false
            invalidate()
            ConfigManager.log(
                "[LiquidGlass] 系统低内存降级 level=$level heap=${heapSummary()}"
            )
        }
    }

    override fun onLowMemory() {
        onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {}

    /** onResume 重新应用外观时尝试恢复完整画质；若堆内存依然偏高则继续维持降级 */
    fun restoreQuality() {
        val rt = Runtime.getRuntime()
        val max = rt.maxMemory()
        val used = rt.totalMemory() - rt.freeMemory()
        val usedRatio = if (max > 0) used.toFloat() / max.toFloat() else 0f
        if (usedRatio >= 0.78f) {
            qualityReduced = true
            accessibilityMode = GlassAccessibilityMode.FORCE_OPAQUE
            dropletView?.accessibilityMode = GlassAccessibilityMode.FORCE_OPAQUE
            enableBackdropBlur = false
            enableEdgeHighlight = false
            dropletView?.enableEdgeHighlight = false
            invalidate()
            return
        }

        if (!qualityReduced) return
        qualityReduced = false
        accessibilityMode = GlassAccessibilityMode.AUTO
        dropletView?.accessibilityMode = GlassAccessibilityMode.AUTO
        enableBackdropBlur = true
        enableEdgeHighlight = true
        dropletView?.enableEdgeHighlight = true
        invalidate()
    }

    private fun heapSummary(): String {
        val rt = Runtime.getRuntime()
        val used = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)
        val max = rt.maxMemory() / (1024 * 1024)
        return "${used}MB/${max}MB"
    }

    /**
     * 只调库自己那颗透镜水滴的光学参数（绘制/动画仍由库负责）。
     * 水滴是库的私有字段，失败时静默退回库默认值。
     */
    private fun tuneDroplet() {
        try {
            val field = LiquidGlassTabBar::class.java.getDeclaredField("droplet")
            field.isAccessible = true
            val droplet = field.get(this) as? LiquidGlassView ?: return
            dropletView = droplet
            val density = resources.displayMetrics.density
            droplet.enableBackdropBlur = false
            // 水滴只负责“凸起”和边缘折光；标签已抬到水滴上方，但折射太深仍会在
            // 图标下方拖出白色残影，所以保留较窄的折射带。
            droplet.bevelWidth = 12f * density
            droplet.refractionHeight = 7f * density
            droplet.refractionFalloff = 0.75f
            droplet.refractionNoFold = false
            droplet.refractionOutward = false
            droplet.dispersionStrength = 0.20f
            droplet.edgeHighlightOpacity = 85f
            droplet.edgeSoftness = 1f * density
            droplet.adaptiveLensScale = true
            droplet.enableChromaticDispersion = false
            droplet.enableOptimizedCapture = true
            droplet.globalDownsampleFactor = 0.5f
            droplet.downsampleScale = 3
            droplet.saturation = 135f
            droplet.setGlassTint(Color.BLACK, 0.05f)
            droplet.overLight = false
            droplet.invalidate()
            if (GlassStyle.devTrace()) {
                ConfigManager.log("[LiquidGlass] droplet 光学参数已应用")
            }
        } catch (t: Throwable) {
            ConfigManager.logError("[LiquidGlass] droplet 调参失败", t)
        }
    }

    /**
     * 白底上给透镜一点极轻的深色，让它有轮廓；深色主题用黑色底 + 轻微黑染，
     * 不再用白色底 tint（那会在深色背景上变成一块灰玻璃）。
     *
     * @param tintPercent 当前夜间玻璃浓度；水滴比底栏略浅一点，保证选中项仍然可辨。
     */
    fun setDropletNight(night: Boolean, tintPercent: Int = 0) {
        nightMode = night
        val droplet = dropletView ?: run {
            if (GlassStyle.devTrace()) {
                ConfigManager.log("[LiquidGlass] droplet 未取到，夜间透镜配色跳过")
            }
            invalidate()
            return
        }
        droplet.overLight = night
        val strength = if (night) {
            ((tintPercent - 15).coerceIn(2, 26) / 100f)
        } else {
            0.05f
        }
        droplet.setGlassTint(Color.BLACK, strength)
        droplet.edgeHighlightOpacity = if (night) 65f else 85f
        droplet.invalidate()
        invalidate()
        if (GlassStyle.devTrace()) {
            ConfigManager.log(
                "[LiquidGlass] droplet night=$night overLight=${droplet.overLight} " +
                    "tint=${Integer.toHexString(droplet.glassTint)} edge=${droplet.edgeHighlightOpacity}"
            )
        }
    }

    fun setLabelColors(selected: Int, normal: Int) {
        labelSelected = selected
        labelNormal = normal
        reapplyLabelColors()
    }

    /** 库在 setTabs / 切页时会重写文字色，之后需要再套一次。 */
    fun reapplyLabelColors() {
        for (i in 0 until childCount) {
            val row = getChildAt(i) as? ViewGroup ?: continue
            if (row.childCount == 0) continue
            for (j in 0 until row.childCount) {
                val tab = row.getChildAt(j) as? ViewGroup ?: continue
                val color = if (j == selectedIndex) labelSelected else labelNormal
                for (k in 0 until tab.childCount) {
                    when (val child = tab.getChildAt(k)) {
                        is TextView -> child.setTextColor(color)
                        is ImageView -> child.imageTintList = ColorStateList.valueOf(color)
                    }
                }
            }
        }
    }
}
