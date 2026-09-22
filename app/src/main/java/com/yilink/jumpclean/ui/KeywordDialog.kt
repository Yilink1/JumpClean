package com.yilink.jumpclean.ui

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.yilink.jumpclean.config.ConfigManager
import com.yilink.jumpclean.config.JumpConstants

object KeywordDialog {

    /**
     * 关键词配置弹窗（统一采用 308dp 物理限制与属性赋值，增加软键盘防挤压）
     */
    fun show(activity: Activity, onSaved: () -> Unit) {
        val prefs = activity.getSharedPreferences(JumpConstants.PREFS_NAME, Context.MODE_PRIVATE)
        val currentKeywords = prefs.getString(JumpConstants.KEY_BLOCKED_KEYWORDS, "") ?: ""
        var currentScope = prefs.getString(JumpConstants.KEY_KEYWORD_BLOCK_SCOPE, JumpConstants.SCOPE_RECOMMEND) ?: JumpConstants.SCOPE_RECOMMEND

        val dp = { value: Int -> (value * activity.resources.displayMetrics.density).toInt() }
        val isDark = (activity.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES

        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val dialogBg = if (isDark) Color.parseColor("#202022") else Color.parseColor("#FFFFFF")
        val inputBg = if (isDark) Color.parseColor("#2C2C2E") else Color.parseColor("#F5F5F7")
        val primaryText = if (isDark) Color.parseColor("#F5F5F7") else Color.parseColor("#1D1D1F")
        val secondaryText = if (isDark) Color.parseColor("#8E8E93") else Color.parseColor("#86868B")
        val accentRed = Color.parseColor("#FF5252")

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(16))
            background = GradientDrawable().apply {
                setColor(dialogBg)
                cornerRadius = dp(20).toFloat()
            }
        }

        val titleView = TextView(activity).apply {
            text = "屏蔽指定关键词"
            textSize = 17f
            setTypeface(null, Typeface.BOLD)
            setTextColor(primaryText)
        }
        root.addView(titleView)

        val scopeTitle = TextView(activity).apply {
            text = "生效范围"
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(secondaryText)
            setPadding(0, dp(12), 0, dp(6))
        }
        root.addView(scopeTitle)

        val scopeContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(36))
            background = GradientDrawable().apply {
                setColor(inputBg)
                cornerRadius = dp(10).toFloat()
            }
            setPadding(dp(3), dp(3), dp(3), dp(3))
        }

        val btnRecommend = TextView(activity).apply {
            text = "仅推荐流"
            textSize = 12.5f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        }

        val btnGlobal = TextView(activity).apply {
            text = "全局生效"
            textSize = 12.5f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        }

        fun updateScopeViews() {
            if (currentScope == JumpConstants.SCOPE_RECOMMEND) {
                btnRecommend.setTextColor(Color.WHITE)
                btnRecommend.setTypeface(null, Typeface.BOLD)
                btnRecommend.background = GradientDrawable().apply {
                    setColor(accentRed)
                    cornerRadius = dp(8).toFloat()
                }

                btnGlobal.setTextColor(secondaryText)
                btnGlobal.setTypeface(null, Typeface.NORMAL)
                btnGlobal.background = null
            } else {
                btnGlobal.setTextColor(Color.WHITE)
                btnGlobal.setTypeface(null, Typeface.BOLD)
                btnGlobal.background = GradientDrawable().apply {
                    setColor(accentRed)
                    cornerRadius = dp(8).toFloat()
                }

                btnRecommend.setTextColor(secondaryText)
                btnRecommend.setTypeface(null, Typeface.NORMAL)
                btnRecommend.background = null
            }
        }

        btnRecommend.setOnClickListener {
            currentScope = JumpConstants.SCOPE_RECOMMEND
            updateScopeViews()
        }

        btnGlobal.setOnClickListener {
            currentScope = JumpConstants.SCOPE_GLOBAL
            updateScopeViews()
        }

        updateScopeViews()
        scopeContainer.addView(btnRecommend)
        scopeContainer.addView(btnGlobal)
        root.addView(scopeContainer)

        val kwTitle = TextView(activity).apply {
            text = "屏蔽词列表"
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(secondaryText)
            setPadding(0, dp(12), 0, dp(6))
        }
        root.addView(kwTitle)

        val editTextView = EditText(activity).apply {
            hint = "多个词用逗号或换行分隔\n支持前缀 regex: 如 regex:^抽奖.*"
            setText(currentKeywords)
            textSize = 13f
            setTextColor(primaryText)
            setHintTextColor(Color.parseColor("#777777"))
            minLines = 4
            gravity = Gravity.TOP
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                setColor(inputBg)
                cornerRadius = dp(10).toFloat()
            }
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(96))
            root.addView(this, lp)
        }

        val btnLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.topMargin = dp(16)
            layoutParams = lp
        }

        val cancelBtn = TextView(activity).apply {
            text = "取消"
            textSize = 14f
            setPadding(dp(14), dp(8), dp(14), dp(8))
            setTextColor(secondaryText)
            setOnClickListener { dialog.dismiss() }
        }

        val saveBtn = TextView(activity).apply {
            text = "保存"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(16), dp(8), dp(16), dp(8))
            setTextColor(accentRed)
            setOnClickListener {
                val text = editTextView.text.toString().trim()
                prefs.edit()
                    .putString(JumpConstants.KEY_BLOCKED_KEYWORDS, text)
                    .putString(JumpConstants.KEY_KEYWORD_BLOCK_SCOPE, currentScope)
                    .apply()
                ConfigManager.invalidateKeywordMatchers()
                Toast.makeText(activity, "屏蔽配置已更新", Toast.LENGTH_SHORT).show()
                onSaved()
                dialog.dismiss()
            }
        }

        btnLayout.addView(cancelBtn)
        btnLayout.addView(saveBtn)
        root.addView(btnLayout)

        dialog.setContentView(root)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.show()

        // 物理锁定窗口属性尺寸，防止软键盘挤压或特定 ROM 测量冲刷
        dialog.window?.let { win ->
            @Suppress("DEPRECATION")
            win.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            val lp = win.attributes
            lp.width = dp(308)
            lp.height = WindowManager.LayoutParams.WRAP_CONTENT
            win.attributes = lp
        }
    }
}
