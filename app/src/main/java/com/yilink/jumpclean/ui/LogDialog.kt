package com.yilink.jumpclean.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
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
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.yilink.jumpclean.HookUtils

object LogDialog {

    @SuppressLint("SetTextI18n")
    fun show(activity: Activity) {
        val dp = { value: Int -> (value * activity.resources.displayMetrics.density).toInt() }
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
            setPadding(dp(20), dp(20), dp(20), dp(16))
            background = GradientDrawable().apply {
                setColor(dialogBg)
                cornerRadius = dp(20).toFloat()
            }
        }

        // 1. 顶部标题栏
        val headerLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(14)
            }
        }

        val titleView = TextView(activity).apply {
            text = "诊断日志"
            textSize = 17f
            setTypeface(null, Typeface.BOLD)
            setTextColor(primaryText)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val countBadge = TextView(activity).apply {
            textSize = 11.5f
            setTextColor(secondaryText)
            setPadding(dp(8), dp(3), dp(8), dp(3))
            background = GradientDrawable().apply {
                setColor(contentBg)
                cornerRadius = dp(6).toFloat()
            }
        }

        headerLayout.addView(titleView)
        headerLayout.addView(countBadge)
        root.addView(headerLayout)

        // 2. 日志内容滚动视窗
        val logScrollView = ScrollView(activity).apply {
            isVerticalScrollBarEnabled = true
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(280)).apply {
                bottomMargin = dp(14)
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
            val logs = HookUtils.getRecentLogs()
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

        // 3. 底部操作按钮栏
        val btnLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val clearBtn = TextView(activity).apply {
            text = "清空"
            textSize = 13.5f
            setTextColor(secondaryText)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setOnClickListener {
                HookUtils.clearRecentLogs()
                renderLogs()
                Toast.makeText(activity, "日志已清空", Toast.LENGTH_SHORT).show()
            }
        }

        val spacer = TextView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
        }

        val closeBtn = TextView(activity).apply {
            text = "关闭"
            textSize = 13.5f
            setTextColor(secondaryText)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setOnClickListener { dialog.dismiss() }
        }

        val copyBtn = TextView(activity).apply {
            text = "复制全部"
            textSize = 13.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(dp(16), dp(8), dp(16), dp(8))
            background = GradientDrawable().apply {
                setColor(accentRed)
                cornerRadius = dp(8).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(8)
            }
            setOnClickListener {
                val logs = HookUtils.getRecentLogs()
                if (logs.isEmpty()) {
                    Toast.makeText(activity, "暂无日志可复制", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                val textContent = logs.joinToString("\n")
                val clip = ClipData.newPlainText("JumpClean Diagnostic Logs", textContent)
                clipboard?.setPrimaryClip(clip)
                Toast.makeText(activity, "已复制 ${logs.size} 条诊断日志到剪贴板", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
        }

        btnLayout.addView(clearBtn)
        btnLayout.addView(spacer)
        btnLayout.addView(closeBtn)
        btnLayout.addView(copyBtn)
        root.addView(btnLayout)

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
