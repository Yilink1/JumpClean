package com.yilink.jumpclean.hooks

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.TextView
import com.example.liquidglass.GlassMaterial
import com.example.liquidglass.LiquidGlassTabBar
import com.example.liquidglass.LiquidGlassView
import com.yilink.jumpclean.config.ConfigManager

/**
 * JumpClean 专用底栏：在库的 LiquidGlassTabBar 上补三件事
 *
 * 1. 按住时水滴「浮起」：折射/斜面增强 + 放大 + 微微提亮，松手回弹（库原生只有拖动才有反馈）
 * 2. 不依赖库的自适应染色也能切换文字深浅色。
 *    库的 enableAdaptiveTint 在亮背景上会往玻璃里混 ~22% 黑，这就是「灰蒙蒙」的来源，
 *    所以默认关掉它，改由这里自己采样背景亮度来翻转文字颜色
 * 3. 文字大小 / 居中（反射 tabsRow，失败则静默跳过）
 */
class JumpGlassTabBar(context: Context) : LiquidGlassTabBar(context) {

    companion object {
        const val TEXT_AUTO = 0
        const val TEXT_DARK = 1
        const val TEXT_LIGHT = 2
        private const val PROBE_SIZE = 16
        private const val PROBE_INTERVAL_MS = 1000L
    }

    // ───────────── 文字配色 ─────────────

    var textMode: Int = TEXT_AUTO
        set(value) {
            if (field != value) {
                field = value
                applyTextMode()
            }
        }

    /** 自动配色时采样的内容视图（底栏背后的页面容器） */
    var sampleSource: View? = null

    /**
     * 采样是把内容区用软件 Canvas 画进 16×16 位图；内容里有可见 WebView 时这会触发 WebView 的软件绘制
     * （又慢又可能干扰它的硬件绘制），所以此时暂停采样、保持上一次的文字明暗。
     * 返回 true = 当前有可见 WebView，由宿主 Hook 侧提供（不用再遍历 View 树）。
     */
    var webGuard: (() -> Boolean)? = null

    /** 设置面板读数时置位：保证帧统计在采集（否则只在「调试日志」开着时采集） */
    @Volatile var statsForced = false
        set(value) {
            field = value
            collectFrameStats = value || GlassStyle.devTrace()
        }

    /** 每 3 秒：「调试日志」开着就把玻璃实测帧率写进日志；没开就不采集统计 */
    private val perfTick = object : Runnable {
        override fun run() {
            if (!isAttachedToWindow) return
            val dbg = GlassStyle.devTrace()
            collectFrameStats = dbg || statsForced
            if (dbg) {
                val st = lastFrameStats
                if (st != null) {
                    ConfigManager.log(
                        "[GlassPerf] drawFps=${st.drawFps} cpuMs=${"%.2f".format(st.totalMs)} " +
                            "${st.effectName} ${st.processedWidth}x${st.processedHeight} dynamicBg=$enableDynamicBackground"
                    )
                } else {
                    ConfigManager.log("[GlassPerf] 暂无帧统计（底栏还没重绘过，或当前渲染管线不产生统计） dynamicBg=$enableDynamicBackground")
                }
            }
            postDelayed(this, 3000)
        }
    }

    private var appliedLight: Boolean? = null
    private var smoothLum = 0.5f
    private var probeLight = false
    private var probeSeeded = false
    private var probeBmp: Bitmap? = null
    private val probePixels = IntArray(PROBE_SIZE * PROBE_SIZE)
    private val probeLoc = IntArray(2)
    private val srcLoc = IntArray(2)

    private val probeTick = object : Runnable {
        override fun run() {
            if (!isAttachedToWindow) return
            try { probe() } catch (_: Throwable) {}
            postDelayed(this, PROBE_INTERVAL_MS)
        }
    }

    // ───────────── 水滴 ─────────────

    private val droplet: LiquidGlassView? = reflectField("droplet") as? LiquidGlassView
    private val tabsRowView: ViewGroup? = reflectField("tabsRow") as? ViewGroup

    private var baseRefraction = -1f
    private var baseBevel = 0f
    private var baseDispersion = 0.04f
    private var pressInflate = 1.08f
    private var pressBoost = 2.2f
    private var pressLift = 0.10f

    private var pressing = false
    private var pressProgress = 0f
    private var pressAnim: ValueAnimator? = null

    private var labelSizeSp = 14f

    private fun reflectField(name: String): Any? = try {
        val f = LiquidGlassTabBar::class.java.getDeclaredField(name)
        f.isAccessible = true
        f.get(this)
    } catch (_: Throwable) {
        null
    }

    /** 水滴基础参数（px）。按住时在此基础上叠加 boost / inflate / lift */
    fun configureDroplet(
        refractionPx: Float, bevelPx: Float, dispersion: Float,
        inflate: Float, boost: Float, lift: Float
    ) {
        baseRefraction = refractionPx
        baseBevel = bevelPx
        baseDispersion = dispersion
        pressInflate = inflate
        pressBoost = boost
        pressLift = lift
        renderDroplet(pressProgress)
    }

    private fun renderDroplet(p: Float) {
        val d = droplet ?: return
        if (baseRefraction < 0f) return
        val k = p.coerceAtLeast(0f)
        d.refractionHeight = baseRefraction * (1f + (pressBoost - 1f) * k)
        d.bevelWidth = (baseBevel * (1f + (pressBoost - 1f) * 0.5f * k)).coerceAtLeast(2f)
        d.dispersionStrength = (baseDispersion * (1f + k)).coerceAtMost(0.5f)
        d.setGlassTint(Color.WHITE, (pressLift * k).coerceIn(0f, 1f))
    }

    /**
     * 统一设置水滴缩放，并把支点夹在「不会让水滴出标签行」的范围内
     * （库的玻璃滴出了标签行就采不到背景，会渲染成黑，看起来像一块被吃掉）。
     * [preferFrac] 是想要的支点位置（0 = 左缘，0.5 = 中心，1 = 右缘）。
     */
    private fun applyDropletScale(sx: Float, sy: Float, preferFrac: Float) {
        val d = droplet ?: return
        val row = tabsRowView
        if (row != null && d.width > 0 && kotlin.math.abs(sx - 1f) > 0.001f) {
            val w = d.width.toFloat()
            val l0 = d.left + d.translationX
            val want = w * preferFrac
            val pivot: Float
            if (sx > 1f) {
                val k = sx - 1f
                val maxPivot = (l0 - row.left) / k
                val minPivot = (l0 + w * sx - row.right) / k
                pivot = if (minPivot <= maxPivot) want.coerceIn(minPivot, maxPivot) else (minPivot + maxPivot) / 2f
            } else {
                val m = 1f - sx
                val minPivot = (row.left - l0) / m
                val maxPivot = (row.right - l0 - w * sx) / m
                pivot = if (minPivot <= maxPivot) want.coerceIn(minPivot, maxPivot) else (minPivot + maxPivot) / 2f
            }
            d.pivotX = pivot.coerceIn(0f, w)
        }
        d.pivotY = d.height / 2f
        d.scaleX = sx
        d.scaleY = sy
    }

    private fun inflateDroplet(p: Float) {
        val k = p.coerceAtLeast(0f)
        applyDropletScale(
            1f + (pressInflate - 1f) * k,
            1f + (pressInflate - 1f) * 0.5f * k,
            0.5f
        )
    }

    private fun resetDropletPivot() {
        try { droplet?.resetPivot() } catch (_: Throwable) {}
    }

    private fun setPressing(on: Boolean) {
        if (pressing == on) return
        pressing = on
        pressAnim?.cancel()
        pressAnim = ValueAnimator.ofFloat(pressProgress, if (on) 1f else 0f).apply {
            duration = if (on) 220L else 320L
            interpolator = if (on) OvershootInterpolator(1.6f) else DecelerateInterpolator()
            addUpdateListener {
                pressProgress = it.animatedValue as Float
                renderDroplet(pressProgress)
                // 松手后的缩放交还给库（切页动画/落位会自己写 scale），这里只在按住且未拖动时放大
                if (pressing && !dragMode) inflateDroplet(pressProgress)
            }
            start()
        }
    }

    private var dragMode = false
    private var pressDownX = 0f

    // ───────────── 拖动果冻 / 撞墙挤压 ─────────────
    /** 0..1：拖动时按速度拉伸/压扁的强度 */
    var dragJelly = 0.6f
    /** 0..1：手指拖过两端时水滴被墙压扁的强度 */
    var wallSquash = 0.6f
    private var lastMoveX = 0f
    private var lastMoveT = 0L
    private var vSmooth = 0f

    private val deformTick = object : Runnable {
        override fun run() {
            if (!dragMode) return
            vSmooth *= 0.84f          // 手指停住后，拉伸按指数衰减回正圆
            deformAt(lastMoveX)
            if (kotlin.math.abs(vSmooth) > 5f) postOnAnimation(this)
        }
    }

    private fun deformAt(x: Float) {
        val d = droplet ?: return
        if (d.width <= 0) return
        val w = d.width.toFloat()
        val vNorm = (kotlin.math.abs(vSmooth) / (w * 5f)).coerceIn(0f, 1f)
        // 库按「手指位置」算目标，再夹到可达范围；差值就是手指拖过墙的距离（>0 右墙，<0 左墙）
        val over = (x - paddingLeft - w / 2f) - d.translationX
        val o = (kotlin.math.abs(over) / (w * 0.55f)).coerceIn(0f, 1f)
        val sx = 1.06f + dragJelly * 0.30f * vNorm - wallSquash * 0.20f * o
        val sy = 1.06f - dragJelly * 0.12f * vNorm + wallSquash * 0.14f * o
        val frac = when {
            o > 0.02f -> if (over > 0f) 1f else 0f   // 支点贴着墙，被压扁
            vSmooth > 0f -> 0.2f                      // 向右拖：右边「领先」伸出去
            else -> 0.8f
        }
        applyDropletScale(sx, sy, frac)
    }
    private val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val handled = super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragMode = false
                pressDownX = event.x
                lastMoveX = event.x
                lastMoveT = event.eventTime
                vSmooth = 0f
                setPressing(true)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragMode && kotlin.math.abs(event.x - pressDownX) > slop) {
                    dragMode = true
                    resetDropletPivot()
                }
                if (dragMode) {
                    val dt = (event.eventTime - lastMoveT).coerceAtLeast(1L)
                    val vx = (event.x - lastMoveX) / dt * 1000f
                    vSmooth += (vx - vSmooth) * 0.35f
                    lastMoveX = event.x
                    lastMoveT = event.eventTime
                    if (dragJelly > 0.01f || wallSquash > 0.01f) {
                        deformAt(event.x)
                        removeCallbacks(deformTick)
                        postOnAnimation(deformTick)
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(deformTick)
                dragMode = false
                vSmooth = 0f
                resetDropletPivot()
                setPressing(false)
            }
        }
        return handled
    }

    // ───────────── 文字外观 ─────────────

    /** 文字大小 + 居中。setTabs() 会重建标签，之后需要再调用一次 */
    fun setLabelTextSize(sp: Float) {
        labelSizeSp = sp
        applyLabelStyle()
    }

    fun applyLabelStyle() {
        val row = tabsRowView ?: return
        for (i in 0 until row.childCount) {
            val tab = row.getChildAt(i)
            if (tab is TextView) {
                tab.gravity = Gravity.CENTER
                tab.textSize = labelSizeSp
            } else if (tab is ViewGroup && tab.childCount == 1) {
                // 纯文字标签：root(LinearLayout) 里只有一个 TextView；带图标的标签不动
                val inner = tab.getChildAt(0)
                if (inner is TextView) {
                    inner.gravity = Gravity.CENTER
                    inner.textSize = labelSizeSp
                }
            }
        }
        requestLayout()
    }

    override fun onAppearanceChanged(isOverLight: Boolean) {
        super.onAppearanceChanged(isOverLight)
        // 库会把 droplet.overLight 同步成 true，亮背景下水滴会多叠一层 20% 黑罩，发灰。压回 false
        droplet?.overLight = false
    }

    private fun applyAppearance(light: Boolean) {
        if (appliedLight == light) return
        appliedLight = light
        onAppearanceChanged(light)
    }

    private fun applyTextMode() {
        when (textMode) {
            TEXT_DARK -> applyAppearance(true)
            TEXT_LIGHT -> applyAppearance(false)
            else -> {
                probeSeeded = false
                post { try { probe() } catch (_: Throwable) {} }
            }
        }
    }

    private fun libraryDrivesAppearance(): Boolean =
        enableAdaptiveTint && material == GlassMaterial.REGULAR

    private fun probe() {
        if (libraryDrivesAppearance()) return
        if (probeSeeded && webGuard?.invoke() == true) return
        if (textMode != TEXT_AUTO) {
            applyTextMode()
            return
        }
        val src = sampleSource ?: return
        val w = width
        val h = height
        if (w <= 0 || h <= 0 || !src.isShown) return

        val bmp = probeBmp?.takeIf { !it.isRecycled }
            ?: Bitmap.createBitmap(PROBE_SIZE, PROBE_SIZE, Bitmap.Config.ARGB_8888).also { probeBmp = it }
        bmp.eraseColor(Color.GRAY)

        getLocationOnScreen(probeLoc)
        src.getLocationOnScreen(srcLoc)
        val offX = (probeLoc[0] - srcLoc[0]).toFloat()
        val offY = (probeLoc[1] - srcLoc[1]).toFloat()

        val canvas = Canvas(bmp)
        canvas.scale(PROBE_SIZE.toFloat() / w, PROBE_SIZE.toFloat() / h)
        canvas.translate(-offX, -offY)
        // 只画底栏背后那一小块，避免整屏软件绘制造成掉帧
        canvas.clipRect(offX, offY, offX + w, offY + h)
        src.draw(canvas)

        bmp.getPixels(probePixels, 0, PROBE_SIZE, 0, 0, PROBE_SIZE, PROBE_SIZE)
        var sum = 0f
        for (c in probePixels) {
            sum += 0.2126f * Color.red(c) + 0.7152f * Color.green(c) + 0.0722f * Color.blue(c)
        }
        val lum = sum / probePixels.size / 255f

        if (!probeSeeded) {
            smoothLum = lum
            probeLight = lum > 0.55f
            probeSeeded = true
            applyAppearance(probeLight)
            return
        }
        smoothLum += (lum - smoothLum) * 0.4f
        if (!probeLight && smoothLum > 0.60f) probeLight = true
        else if (probeLight && smoothLum < 0.45f) probeLight = false
        applyAppearance(probeLight)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        removeCallbacks(probeTick)
        post(probeTick)
        if (textMode != TEXT_AUTO) applyTextMode()
        removeCallbacks(perfTick)
        post(perfTick)
    }

    // ───────────── 厚度光边（立体感） ─────────────
    /** 0..1：外缘渐变亮线 + 内缘暗线 + 顶部高光，给薄薄的玻璃条加「厚度」；0 = 关 */
    var rimStrength = 0.35f
        set(value) {
            field = value.coerceIn(0f, 1f)
            rimDirty = true
            invalidate()
        }
    private var rimDirty = true
    private var rimW = 0
    private var rimH = 0
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimRect = RectF()
    private var rimLightShader: Shader? = null
    private var rimDarkShader: Shader? = null
    private var sheenShader: Shader? = null

    private fun argbF(a: Float, rgb: Int): Int =
        Color.argb((a.coerceIn(0f, 1f) * 255f).toInt(), Color.red(rgb), Color.green(rgb), Color.blue(rgb))

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (rimStrength > 0.01f) drawRim(canvas)
    }

    private fun drawRim(canvas: Canvas) {
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return
        if (rimDirty || w != rimW || h != rimH) {
            rimW = w; rimH = h; rimDirty = false
            val s = rimStrength
            val hf = h.toFloat()
            sheenShader = LinearGradient(0f, 0f, 0f, hf * 0.55f,
                intArrayOf(argbF(0.16f * s, Color.WHITE), argbF(0f, Color.WHITE)), null, Shader.TileMode.CLAMP)
            rimLightShader = LinearGradient(0f, 0f, 0f, hf,
                intArrayOf(argbF(0.80f * s, Color.WHITE), argbF(0.10f * s, Color.WHITE), argbF(0.38f * s, Color.WHITE)),
                floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
            rimDarkShader = LinearGradient(0f, 0f, 0f, hf,
                intArrayOf(argbF(0f, Color.BLACK), argbF(0f, Color.BLACK), argbF(0.30f * s, Color.BLACK)),
                floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        }
        val dens = resources.displayMetrics.density
        val sw = 1.3f * dens
        val r = kotlin.math.min(cornerRadius, h / 2f)

        rimRect.set(0f, 0f, w.toFloat(), h.toFloat())
        sheenPaint.shader = sheenShader
        canvas.drawRoundRect(rimRect, r, r, sheenPaint)

        rimPaint.strokeWidth = sw
        rimPaint.shader = rimLightShader
        rimRect.set(sw / 2f, sw / 2f, w - sw / 2f, h - sw / 2f)
        canvas.drawRoundRect(rimRect, (r - sw / 2f).coerceAtLeast(0f), (r - sw / 2f).coerceAtLeast(0f), rimPaint)

        rimPaint.shader = rimDarkShader
        rimRect.set(sw * 1.5f, sw * 1.5f, w - sw * 1.5f, h - sw * 1.5f)
        canvas.drawRoundRect(rimRect, (r - sw * 1.5f).coerceAtLeast(0f), (r - sw * 1.5f).coerceAtLeast(0f), rimPaint)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(deformTick)
        removeCallbacks(probeTick)
        removeCallbacks(perfTick)
        pressAnim?.cancel()
        pressing = false
        pressProgress = 0f
        probeBmp?.recycle()
        probeBmp = null
        super.onDetachedFromWindow()
    }
}
