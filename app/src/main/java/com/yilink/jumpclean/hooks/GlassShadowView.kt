package com.yilink.jumpclean.hooks

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View

/**
 * 底栏投影：垫在玻璃条下面的一圈柔和阴影。
 *
 * 为什么不用 elevation：玻璃是半透明的，系统阴影会透过玻璃把内部压脏。
 * 这里把玻璃形状本身从阴影里挖掉（clipOutPath），只留外圈。
 * 本 View 比底栏每边大 [padPx]，阴影画在多出来的那一圈里，不会被自己的边界裁掉。
 */
class GlassShadowView(context: Context) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val glassRect = RectF()
    private val glassPath = Path()
    private var strength = 0.2f
    private var blur = 16f
    private var offsetY = 5f
    private var corner = 9999f
    private var pad = 0f

    init {
        // BlurMaskFilter 在软件层最稳（低版本硬件加速不支持）；只有一圈小位图，开销很小
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        isClickable = false
        isFocusable = false
    }

    /** @return 本 View 相对底栏每边需要多出的像素，用来算 LayoutParams */
    fun configure(strength: Float, blurPx: Float, offsetYPx: Float, cornerPx: Float): Int {
        this.strength = strength.coerceIn(0f, 1f)
        this.blur = blurPx.coerceAtLeast(1f)
        this.offsetY = offsetYPx
        this.corner = cornerPx
        pad = blur * 1.6f + kotlin.math.abs(offsetYPx)
        paint.maskFilter = BlurMaskFilter(this.blur, BlurMaskFilter.Blur.NORMAL)
        invalidate()
        return kotlin.math.ceil(pad.toDouble()).toInt()
    }

    override fun onDraw(canvas: Canvas) {
        if (strength <= 0.001f) return
        val p = kotlin.math.ceil(pad.toDouble()).toFloat()
        glassRect.set(p, p, width - p, height - p)
        if (glassRect.width() <= 0f || glassRect.height() <= 0f) return
        val r = kotlin.math.min(corner, glassRect.height() / 2f)

        glassPath.reset()
        glassPath.addRoundRect(glassRect, r, r, Path.Direction.CW)

        canvas.save()
        canvas.clipOutPath(glassPath)
        paint.color = Color.argb((strength * 255f).toInt().coerceIn(0, 255), 0, 0, 0)
        canvas.drawRoundRect(
            glassRect.left, glassRect.top + offsetY, glassRect.right, glassRect.bottom + offsetY,
            r, r, paint
        )
        canvas.restore()
    }
}
