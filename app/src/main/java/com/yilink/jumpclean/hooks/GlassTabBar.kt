package com.yilink.jumpclean.hooks

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.SystemClock
import android.view.ViewTreeObserver
import android.widget.ImageView
import android.widget.TextView
import android.view.ViewGroup
import com.example.liquidglass.LiquidGlassTabBar
import com.example.liquidglass.LiquidGlassView

/**
 * 只做一件事：给库的 tab 文字/图标上色。
 *
 * 玻璃绘制、透镜、滑动动画、拖拽和背景采样全部留在 [LiquidGlassTabBar] 内部。
 * 这里不改透镜、不反射私有字段，只遍历库公开创建出来的 tab 子 View 设置颜色，
 * 因为库把普通态文字色写死成“亮背景黑 / 暗背景白”，无法覆盖。
 */
class GlassTabBar(context: Context) : LiquidGlassTabBar(context) {
    private var labelSelected = 0xFF202633.toInt()
    private var labelNormal = 0x8C3A4252.toInt()
    private var scrollThrottleUntil = 0L
    private var lastBodyInvalidate = 0L

    private val scrollIdle = Runnable {
        scrollThrottleUntil = 0L
        lastBodyInvalidate = 0L
        super.invalidate()
    }
    private val scrollListener = ViewTreeObserver.OnScrollChangedListener {
        // 信息流滚动时玻璃本体最多 ~11fps 重绘，停手后补一帧。
        scrollThrottleUntil = SystemClock.uptimeMillis() + 180L
        removeCallbacks(scrollIdle)
        postDelayed(scrollIdle, 200L)
    }

    init {
        tuneDroplet()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnScrollChangedListener(scrollListener)
    }

    override fun onDetachedFromWindow() {
        try {
            viewTreeObserver.removeOnScrollChangedListener(scrollListener)
        } catch (_: Throwable) {
        }
        removeCallbacks(scrollIdle)
        super.onDetachedFromWindow()
    }

    override fun invalidate() {
        if (SystemClock.uptimeMillis() < scrollThrottleUntil) {
            val now = SystemClock.uptimeMillis()
            if (now - lastBodyInvalidate < 90L) return
            lastBodyInvalidate = now
        }
        super.invalidate()
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
            val density = resources.displayMetrics.density
            droplet.enableBackdropBlur = false
            droplet.bevelWidth = 16f * density
            droplet.refractionHeight = 12f * density
            droplet.refractionFalloff = 0.62f
            droplet.refractionNoFold = false
            droplet.refractionOutward = false
            droplet.dispersionStrength = 0.30f
            droplet.edgeHighlightOpacity = 100f
            droplet.edgeSoftness = 1f * density
            droplet.adaptiveLensScale = true
            droplet.enableChromaticDispersion = false
            droplet.enableOptimizedCapture = true
            droplet.globalDownsampleFactor = 0.5f
            droplet.downsampleScale = 3
            droplet.saturation = 135f
            droplet.setGlassTint(Color.WHITE, 0.03f)
            droplet.overLight = false
            droplet.invalidate()
        } catch (_: Throwable) {
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
