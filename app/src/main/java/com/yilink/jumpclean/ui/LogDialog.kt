package com.yilink.jumpclean.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.yilink.jumpclean.HookUtils

object LogDialog {

    /**
     * 极简矢量图标绘制：双层矩形剪贴板复制图标
     */
    private class CopyIconDrawable(
        private val color: Int,
        private val strokeWidthPx: Float
    ) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            this.strokeWidth = strokeWidthPx
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            this.color = this@CopyIconDrawable.color
        }

        override fun draw(canvas: Canvas) {
            val b = bounds
            val w = b.width().toFloat()
            val h = b.height().toFloat()
            if (w <= 0 || h <= 0) return

            canvas.save()
            canvas.translate(b.left.toFloat(), b.top.toFloat())
            val scale = minOf(w / 24f, h / 24f)
            val dx = (w - 24f * scale) / 2f
            val dy = (h - 24f * scale) / 2f
            canvas.translate(dx, dy)
            canvas.scale(scale, scale)

            // 1. 前置矩形 (8.5..19.5, 8.5..19.5)
            val frontRect = RectF(8.5f, 8.5f, 19.5f, 19.5f)
            canvas.drawRoundRect(frontRect, 2.5f, 2.5f, paint)

            // 2. 后置矩形轮廓 (上边和左边)
            val backPath = Path().apply {
                moveTo(15.5f, 4.5f)
                lineTo(6.5f, 4.5f)
                quadTo(4.5f, 4.5f, 4.5f, 6.5f)
                lineTo(4.5f, 15.5f)
                quadTo(4.5f, 17.5f, 6.5f, 17.5f)
            }
            canvas.drawPath(backPath, paint)

            canvas.restore()
        }

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            paint.colorFilter = colorFilter
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    /**
     * 极简矢量图标绘制：小垃圾桶清空图标
     */
    private class TrashIconDrawable(
        private val color: Int,
        private val strokeWidthPx: Float
    ) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            this.strokeWidth = strokeWidthPx
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            this.color = this@TrashIconDrawable.color
        }

        override fun draw(canvas: Canvas) {
            val b = bounds
            val w = b.width().toFloat()
            val h = b.height().toFloat()
            if (w <= 0 || h <= 0) return

            canvas.save()
            canvas.translate(b.left.toFloat(), b.top.toFloat())
            val scale = minOf(w / 24f, h / 24f)
            val dx = (w - 24f * scale) / 2f
            val dy = (h - 24f * scale) / 2f
            canvas.translate(dx, dy)
            canvas.scale(scale, scale)

            // 1. 盖子把手: (9.5, 6) -> (9.5, 4.5) -> (14.5, 4.5) -> (14.5, 6)
            val handlePath = Path().apply {
                moveTo(9.5f, 6f)
                lineTo(9.5f, 4.5f)
                lineTo(14.5f, 4.5f)
                lineTo(14.5f, 6f)
            }
            canvas.drawPath(handlePath, paint)

            // 2. 桶盖水平横线: (4, 6) -> (20, 6)
            canvas.drawLine(4f, 6f, 20f, 6f, paint)

            // 3. 桶身轮廓
            val bodyPath = Path().apply {
                moveTo(6.5f, 6f)
                lineTo(7.5f, 18.5f)
                quadTo(7.7f, 20f, 9.5f, 20f)
                lineTo(14.5f, 20f)
                quadTo(16.3f, 20f, 16.5f, 18.5f)
                lineTo(17.5f, 6f)
            }
            canvas.drawPath(bodyPath, paint)

            // 4. 内部双垂直槽线
            canvas.drawLine(10f, 9.5f, 10f, 16.5f, paint)
            canvas.drawLine(14f, 9.5f, 14f, 16.5f, paint)

            canvas.restore()
        }

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            paint.colorFilter = colorFilter
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    @SuppressLint("SetTextI18n")
    fun show(activity: Activity) {
        val dp = { value: Int -> (value * activity.resources.displayMetrics.density).toInt() }
        val dpF = { value: Float -> value * activity.resources.displayMetrics.density }
        val isDark = (activity.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES

        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val dialogBg = if (isDark) Color.parseColor("#202022") else Color.parseColor("#FFFFFF")
        val contentBg = if (isDark) Color.parseColor("#2C2C2E") else Color.parseColor("#F5F5F7")
        val primaryText = if (isDark) Color.parseColor("#F5F5F7") else Color.parseColor("#1D1D1F")
        val secondaryText = if (isDark) Color.parseColor("#8E8E93") else Color.parseColor("#86868B")
        val accentRed = Color.parseColor("#FF5252")
        val successGreen = Color.parseColor("#00B06F")

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(14))
            background = GradientDrawable().apply {
                setColor(dialogBg)
                cornerRadius = dp(20).toFloat()
            }
        }

        // 1. 顶部标题栏：左侧标题与条数标签，右上角纯图标化 (清空垃圾桶 + 复制剪贴板)
        val headerLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isBaselineAligned = false
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(12)
            }
        }

        val titleContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isBaselineAligned = false
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val titleView = TextView(activity).apply {
            text = "诊断日志"
            textSize = 17f
            setTypeface(null, Typeface.BOLD)
            setTextColor(primaryText)
            includeFontPadding = false
            gravity = Gravity.CENTER_VERTICAL
        }

        val countBadge = TextView(activity).apply {
            textSize = 11.5f
            setTextColor(secondaryText)
            includeFontPadding = false
            gravity = Gravity.CENTER
            setPadding(dp(7), dp(3), dp(7), dp(3))
            background = GradientDrawable().apply {
                setColor(contentBg)
                cornerRadius = dp(6).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(8)
            }
        }

        titleContainer.addView(titleView)
        titleContainer.addView(countBadge)

        // 右上角图标容器 (垂直居中对齐)
        val iconActionsLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isBaselineAligned = false
        }

        val btnSize = dp(36)
        val iconSize = dp(18)

        // 清空图标按钮 (垃圾桶，8~12dp 间距防误触)
        val trashBtn = FrameLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(btnSize, btnSize).apply {
                marginEnd = dp(10)
            }
            background = GradientDrawable().apply {
                setColor(contentBg)
                cornerRadius = btnSize / 2f
            }
            isClickable = true
            isFocusable = true
            val iconView = ImageView(context).apply {
                setImageDrawable(TrashIconDrawable(secondaryText, dpF(1.6f).coerceAtLeast(1.5f)))
                layoutParams = FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER)
            }
            addView(iconView)
        }

        // 复制图标按钮 (剪贴板，Jump 主题红)
        val copyBtn = FrameLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(btnSize, btnSize)
            background = GradientDrawable().apply {
                setColor(contentBg)
                cornerRadius = btnSize / 2f
            }
            isClickable = true
            isFocusable = true
            val iconView = ImageView(context).apply {
                setImageDrawable(CopyIconDrawable(accentRed, dpF(1.6f).coerceAtLeast(1.5f)))
                layoutParams = FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER)
            }
            addView(iconView)
        }

        iconActionsLayout.addView(trashBtn)
        iconActionsLayout.addView(copyBtn)

        headerLayout.addView(titleContainer)
        headerLayout.addView(iconActionsLayout)
        root.addView(headerLayout)

        // 2. 日志内容滚动视窗
        val logScrollView = ScrollView(activity).apply {
            isVerticalScrollBarEnabled = true
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(280)).apply {
                bottomMargin = dp(12)
            }
            background = GradientDrawable().apply {
                setColor(contentBg)
                cornerRadius = dp(12).toFloat()
            }
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }

        val logTextView = TextView(activity).apply {
            textSize = 11.5f
            setTextColor(primaryText)
            setLineSpacing(dp(3).toFloat(), 1f)
            setTextIsSelectable(true)
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        logScrollView.addView(logTextView)
        root.addView(logScrollView)

        // 刷新日志内容与徽标
        fun renderLogs() {
            val logs = HookUtils.getRecentLogs(activity)
            countBadge.text = "共 ${logs.size} 条"

            if (logs.isEmpty()) {
                logTextView.text = "暂无日志记录\n\n提示：可在设置中开启「调试日志」以记录详细运行流程，发生错误时无需开启也会自动记录。"
                logTextView.setTextColor(secondaryText)
                logTextView.gravity = Gravity.CENTER
                (logTextView.layoutParams as? FrameLayout.LayoutParams)?.gravity = Gravity.CENTER
                return
            }

            logTextView.gravity = Gravity.START
            (logTextView.layoutParams as? FrameLayout.LayoutParams)?.gravity = Gravity.NO_GRAVITY
            logTextView.setTextColor(primaryText)

            val ssb = SpannableStringBuilder()
            logs.forEachIndexed { index, line ->
                val start = ssb.length
                ssb.append(line)
                val end = ssb.length

                // 时间戳变淡灰
                if (line.startsWith("[") && line.length >= 10 && line[9] == ']') {
                    ssb.setSpan(ForegroundColorSpan(secondaryText), start, start + 10, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }

                // 错误标红加粗
                if (line.contains("[ERR]")) {
                    ssb.setSpan(ForegroundColorSpan(accentRed), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    ssb.setSpan(StyleSpan(Typeface.BOLD), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                } else if (line.contains("✔")) {
                    ssb.setSpan(ForegroundColorSpan(successGreen), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }

                if (index < logs.size - 1) {
                    ssb.append("\n")
                }
            }

            logTextView.text = ssb

            logScrollView.post {
                logScrollView.fullScroll(ScrollView.FOCUS_DOWN)
            }
        }

        renderLogs()

        // 绑定图标点击事件
        trashBtn.setOnClickListener {
            HookUtils.clearRecentLogs(activity)
            renderLogs()
            Toast.makeText(activity, "日志已清空", Toast.LENGTH_SHORT).show()
        }

        copyBtn.setOnClickListener {
            val logs = HookUtils.getRecentLogs(activity)
            if (logs.isEmpty()) {
                Toast.makeText(activity, "暂无日志可复制", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val textContent = logs.joinToString("\n")
            val clip = ClipData.newPlainText("JumpClean Diagnostic Logs", textContent)
            clipboard?.setPrimaryClip(clip)
            Toast.makeText(activity, "已复制 ${logs.size} 条诊断日志", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        // 3. 底部极简关闭栏
        val closeBtn = TextView(activity).apply {
            text = "关闭"
            textSize = 13.5f
            setTextColor(secondaryText)
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(2))
            isClickable = true
            isFocusable = true
            setOnClickListener { dialog.dismiss() }
        }
        root.addView(closeBtn)

        dialog.setContentView(root)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.show()

        // 窗口宽度响应式适配
        dialog.window?.let { win ->
            val screenWidth = activity.resources.displayMetrics.widthPixels
            val targetWidth = (screenWidth * 0.90f).toInt().coerceIn(dp(310), dp(360))
            val lp = win.attributes
            lp.width = targetWidth
            lp.height = WindowManager.LayoutParams.WRAP_CONTENT
            win.attributes = lp
        }
    }
}
