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

    enum class Kind { HOME, JOYCON, GAMEPAD, COMPASS, FLAME, PERSON, GIFT, SPARKLE }

    fun forTitle(title: CharSequence): Drawable {
        val text = title.toString()
        val kind = when {
            text.contains("首页") || text.contains("主页") || text.contains("home", true) -> Kind.JOYCON
            text.contains("发现") || text.contains("探索") || text.contains("频道") -> Kind.FLAME
            text.contains("游戏") -> Kind.GAMEPAD
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
                Kind.JOYCON -> drawJoyCon(canvas)
                Kind.GAMEPAD -> drawGamepad(canvas)
                Kind.COMPASS -> drawCompass(canvas)
                Kind.FLAME -> drawFlame(canvas)
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

        private fun drawGamepad(canvas: Canvas) {
            path.reset()
            path.fillType = Path.FillType.EVEN_ODD
            path.moveTo(7f, 7.6f)
            path.cubicTo(4.2f, 7.6f, 2.4f, 10.6f, 2.4f, 14f)
            path.cubicTo(2.4f, 17.4f, 4.4f, 19.2f, 6.4f, 19.2f)
            path.cubicTo(7.6f, 19.2f, 8.5f, 18.5f, 9.4f, 17.4f)
            path.lineTo(14.6f, 17.4f)
            path.cubicTo(15.5f, 18.5f, 16.4f, 19.2f, 17.6f, 19.2f)
            path.cubicTo(19.6f, 19.2f, 21.6f, 17.4f, 21.6f, 14f)
            path.cubicTo(21.6f, 10.6f, 19.8f, 7.6f, 17f, 7.6f)
            path.close()
            // 十字键
            path.moveTo(6.9f, 10.8f)
            path.lineTo(7.9f, 10.8f)
            path.lineTo(7.9f, 12.1f)
            path.lineTo(9.2f, 12.1f)
            path.lineTo(9.2f, 13.1f)
            path.lineTo(7.9f, 13.1f)
            path.lineTo(7.9f, 14.4f)
            path.lineTo(6.9f, 14.4f)
            path.lineTo(6.9f, 13.1f)
            path.lineTo(5.6f, 13.1f)
            path.lineTo(5.6f, 12.1f)
            path.lineTo(6.9f, 12.1f)
            path.close()
            path.addCircle(16.2f, 11.9f, 0.95f, Path.Direction.CW)
            path.addCircle(18.1f, 13.4f, 0.95f, Path.Direction.CW)
            canvas.drawPath(path, fill)
            path.fillType = Path.FillType.WINDING
        }

        private fun drawJoyCon(canvas: Canvas) {
            path.reset()
            path.fillType = Path.FillType.EVEN_ODD
            // 左右两只手柄
            path.addRoundRect(3.4f, 6.2f, 10.3f, 20.2f, 3.4f, 3.4f, Path.Direction.CW)
            path.addRoundRect(13.7f, 6.2f, 20.6f, 20.2f, 3.4f, 3.4f, Path.Direction.CW)
            // 左手十字键
            path.moveTo(6.35f, 10.2f)
            path.lineTo(7.35f, 10.2f)
            path.lineTo(7.35f, 11.15f)
            path.lineTo(8.3f, 11.15f)
            path.lineTo(8.3f, 12.15f)
            path.lineTo(7.35f, 12.15f)
            path.lineTo(7.35f, 13.1f)
            path.lineTo(6.35f, 13.1f)
            path.lineTo(6.35f, 12.15f)
            path.lineTo(5.4f, 12.15f)
            path.lineTo(5.4f, 11.15f)
            path.lineTo(6.35f, 11.15f)
            path.close()
            // 右手按键
            path.addCircle(17f, 10.9f, 0.85f, Path.Direction.CW)
            path.addCircle(18.5f, 12.5f, 0.85f, Path.Direction.CW)
            canvas.drawPath(path, fill)
            path.fillType = Path.FillType.WINDING
        }

        private fun drawFlame(canvas: Canvas) {
            path.reset()
            path.moveTo(12f, 2.4f)
            path.cubicTo(12.4f, 6.1f, 16.4f, 8.1f, 15.9f, 12.6f)
            path.cubicTo(15.6f, 16.3f, 14.1f, 19.1f, 12f, 21.2f)
            path.cubicTo(9.9f, 19.1f, 8.4f, 16.3f, 8.1f, 12.6f)
            path.cubicTo(7.8f, 9.6f, 9.4f, 7.1f, 10.4f, 5.1f)
            path.cubicTo(10.8f, 7.1f, 11.6f, 8.0f, 12.4f, 8.7f)
            path.cubicTo(12.9f, 6.7f, 12.5f, 4.5f, 12f, 2.4f)
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
