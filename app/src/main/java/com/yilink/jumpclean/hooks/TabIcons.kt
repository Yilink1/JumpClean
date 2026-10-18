package com.yilink.jumpclean.hooks

import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable

/**
 * Tab 图标：模块 APK 的资源不会自动出现在宿主进程里，
 * 所以这里用 Path 直接画 24x24 的矢量图标，再由 ImageView 的 tint 上色。
 */
object TabIcons {

    enum class Kind { HOME, COMPASS, PERSON, GIFT, SPARKLE }

    fun forTitle(title: CharSequence): Drawable {
        val text = title.toString()
        val kind = when {
            text.contains("首页") || text.contains("主页") || text.contains("home", true) -> Kind.HOME
            text.contains("发现") || text.contains("探索") || text.contains("频道") -> Kind.COMPASS
            text.contains("我的") || text.contains("个人") || text.contains("mine", true) -> Kind.PERSON
            text.contains("赏") || text.contains("抽") || text.contains("礼") || text.contains("游戏") -> Kind.GIFT
            else -> Kind.SPARKLE
        }
        return GlyphDrawable(kind)
    }

    private class GlyphDrawable(private val kind: Kind) : Drawable() {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.BLACK
        }
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.BLACK
            strokeWidth = 1.9f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val path = Path()
        private var tint: ColorStateList? = null
        private var alphaValue = 255

        override fun draw(canvas: Canvas) {
            val b = bounds
            if (b.isEmpty) return
            val scale = minOf(b.width(), b.height()) / 24f
            val save = canvas.save()
            canvas.translate(b.exactCenterX() - 12f * scale, b.exactCenterY() - 12f * scale)
            canvas.scale(scale, scale)
            when (kind) {
                Kind.HOME -> drawHome(canvas)
                Kind.COMPASS -> drawCompass(canvas)
                Kind.PERSON -> drawPerson(canvas)
                Kind.GIFT -> drawGift(canvas)
                Kind.SPARKLE -> drawSparkle(canvas)
            }
            canvas.restoreToCount(save)
        }

        private fun drawHome(canvas: Canvas) {
            path.reset()
            path.moveTo(12f, 3.1f)
            path.lineTo(21f, 11f)
            path.lineTo(18.6f, 11f)
            path.lineTo(18.6f, 20f)
            path.lineTo(13.9f, 20f)
            path.lineTo(13.9f, 14.5f)
            path.lineTo(10.1f, 14.5f)
            path.lineTo(10.1f, 20f)
            path.lineTo(5.4f, 20f)
            path.lineTo(5.4f, 11f)
            path.lineTo(3f, 11f)
            path.close()
            canvas.drawPath(path, fill)
        }

        private fun drawCompass(canvas: Canvas) {
            canvas.drawCircle(12f, 12f, 8.4f, line)
            path.reset()
            path.moveTo(16f, 8f)
            path.lineTo(13.6f, 13.6f)
            path.lineTo(8f, 16f)
            path.lineTo(10.4f, 10.4f)
            path.close()
            canvas.drawPath(path, fill)
        }

        private fun drawPerson(canvas: Canvas) {
            canvas.drawCircle(12f, 7.9f, 3.7f, fill)
            path.reset()
            path.moveTo(4.7f, 20.3f)
            path.cubicTo(5.3f, 16.2f, 8.3f, 13.7f, 12f, 13.7f)
            path.cubicTo(15.7f, 13.7f, 18.7f, 16.2f, 19.3f, 20.3f)
            path.close()
            canvas.drawPath(path, fill)
        }

        private fun drawGift(canvas: Canvas) {
            canvas.drawRoundRect(4.6f, 10.2f, 19.4f, 20f, 1.8f, 1.8f, fill)
            canvas.drawRoundRect(3.6f, 6.4f, 20.4f, 9.9f, 1.5f, 1.5f, fill)
            canvas.drawCircle(9.2f, 4.6f, 1.6f, line)
            canvas.drawCircle(14.8f, 4.6f, 1.6f, line)
        }

        private fun drawSparkle(canvas: Canvas) {
            path.reset()
            path.moveTo(12f, 3.4f)
            path.lineTo(13.8f, 10.2f)
            path.lineTo(20.6f, 12f)
            path.lineTo(13.8f, 13.8f)
            path.lineTo(12f, 20.6f)
            path.lineTo(10.2f, 13.8f)
            path.lineTo(3.4f, 12f)
            path.lineTo(10.2f, 10.2f)
            path.close()
            canvas.drawPath(path, fill)
        }

        override fun setTintList(tint: ColorStateList?) {
            this.tint = tint
            applyTint()
            invalidateSelf()
        }

        override fun onStateChange(state: IntArray): Boolean {
            if (tint != null) {
                applyTint()
                return true
            }
            return super.onStateChange(state)
        }

        override fun isStateful(): Boolean = tint?.isStateful == true

        private fun applyTint() {
            val color = tint?.getColorForState(state, Color.BLACK) ?: Color.BLACK
            fill.color = color
            line.color = color
            fill.alpha = alphaValue
            line.alpha = alphaValue
        }

        override fun setAlpha(alpha: Int) {
            alphaValue = alpha
            fill.alpha = alpha
            line.alpha = alpha
            invalidateSelf()
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            fill.colorFilter = colorFilter
            line.colorFilter = colorFilter
            invalidateSelf()
        }

        // Android 15 起 getOpacity 已废弃，但 Drawable 仍要求实现。
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }
}
