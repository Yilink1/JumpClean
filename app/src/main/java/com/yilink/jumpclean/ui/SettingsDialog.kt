package com.yilink.jumpclean.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.yilink.jumpclean.config.ConfigManager
import com.yilink.jumpclean.config.JumpConstants

sealed interface SettingEntry
data class SectionHeader(val title: String) : SettingEntry
data class SettingItem(val key: String, val title: String, val desc: String = "") : SettingEntry
data class ConfigurableSettingItem(
    val key: String,
    val title: String,
    val desc: String = "",
    val bindDescView: ((TextView) -> Unit)? = null,
    val onConfigClick: () -> Unit
) : SettingEntry
data class ActionItem(val title: String, val desc: String = "", val onClick: () -> Unit) : SettingEntry

object SettingsDialog {

    private fun getKeywordItemDesc(context: Context): String {
        val sp = context.getSharedPreferences(JumpConstants.PREFS_NAME, Context.MODE_PRIVATE)
        val rawKeywords = sp.getString(JumpConstants.KEY_BLOCKED_KEYWORDS, "") ?: ""
        val count = if (rawKeywords.isBlank()) 0 else rawKeywords.split(",", "，", "\n").count { it.trim().isNotEmpty() }
        return if (count > 0) "已配置 $count 个规则 · 点击编辑" else "点击设置屏蔽词与生效范围"
    }

    /**
     * 沉浸式全屏设置面板
     */
    @Suppress("DEPRECATION")
    @SuppressLint("SetTextI18n")
    fun show(activity: Activity) {
        val prefs = activity.getSharedPreferences(JumpConstants.PREFS_NAME, Context.MODE_PRIVATE)
        val blockedPromoCount = prefs.getInt(JumpConstants.KEY_BLOCKED_OFFICIAL_PROMO_COUNT, 0)

        var keywordDescView: TextView? = null

        val items = listOf(
            SectionHeader("启动与弹窗"),
            SettingItem(JumpConstants.KEY_SKIP_SPLASH, "跳过开屏广告"),
            SettingItem(JumpConstants.KEY_HIDE_VOUCHER_POPUP, "屏蔽营销弹窗"),
            SettingItem(JumpConstants.KEY_HIDE_MSG_PUSH_GUIDE, "屏蔽通知开启引导"),

            SectionHeader("首页"),
            SettingItem(JumpConstants.KEY_HIDE_TOPIC_LIST, "隐藏顶部话题栏"),
            SettingItem(JumpConstants.KEY_HIDE_BANNER, "屏蔽首页轮播广告"),
            SettingItem(JumpConstants.KEY_HIDE_HOT_DISCUSS, "隐藏「Jumper热议」卡片"),
            SettingItem(JumpConstants.KEY_HIDE_POST_AD, "屏蔽推荐流与帖子内嵌广告"),
            SettingItem(JumpConstants.KEY_HIDE_PUBLISH_TOPIC, "隐藏发布按钮"),

            SectionHeader("发现"),
            SettingItem(JumpConstants.KEY_HIDE_DISCOVER_TOP_AD, "屏蔽顶部广告"),
            SettingItem(JumpConstants.KEY_HIDE_DISCOVER_BANNER, "屏蔽轮播广告"),

            SectionHeader("内容与详情"),
            SettingItem(JumpConstants.KEY_ENABLE_COPY, "允许长按复制文本"),
            SettingItem(JumpConstants.KEY_RESTORE_POST_YEAR, "恢复帖子完整年份"),
            SettingItem(JumpConstants.KEY_HIDE_CONTENT_MEMBER_MASK, "查看游戏评价总结"),

            SectionHeader("个人中心"),
            SettingItem(JumpConstants.KEY_HIDE_MEMBER_CARD, "隐藏 Jump+ 会员卡片"),
            SettingItem(JumpConstants.KEY_HIDE_MY_ORDER, "隐藏「我的订单」"),
            SettingItem(JumpConstants.KEY_HIDE_PHOTO_WALL, "隐藏截图展示墙"),

            SectionHeader("底栏与小组件"),
            SettingItem(JumpConstants.KEY_HIDE_WEB_TAB, "隐藏底栏「Jump 赏」"),
            SettingItem(JumpConstants.KEY_HIDE_LOTTERY_TAB, "隐藏底栏「抽奖 / 全新 App」"),
            SettingItem(JumpConstants.KEY_HIDE_WIDGET_VIP_TAG, "隐藏小组件会员标识"),

            SectionHeader("个性化与拓展"),
            SettingItem(JumpConstants.KEY_ENABLE_DEBUG_LOG, "开启调试日志"),
            ActionItem("更换 App 图标", desc = "修复官方遗漏图标，含 21 款") {
                IconPickerDialog.show(activity)
            },

            SectionHeader("实验性功能"),
            ConfigurableSettingItem(
                JumpConstants.KEY_ENABLE_KEYWORD_BLOCK,
                "屏蔽帖子指定关键词",
                desc = getKeywordItemDesc(activity),
                bindDescView = { keywordDescView = it }
            ) {
                KeywordDialog.show(activity) {
                    keywordDescView?.text = getKeywordItemDesc(activity)
                }
            },
            SettingItem(
                JumpConstants.KEY_EXP_BLOCK_OFFICIAL_PROMO_POST,
                "屏蔽推荐流小酱推广贴",
                desc = "累计屏蔽: ${blockedPromoCount} 次"
            )
        )

        val dp = { value: Int -> (value * activity.resources.displayMetrics.density).toInt() }

        val statusBarHeight = run {
            val resourceId = activity.resources.getIdentifier("status_bar_height", "dimen", "android")
            if (resourceId > 0) activity.resources.getDimensionPixelSize(resourceId) else dp(28)
        }

        val isDark = (activity.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES

        val bgColor = if (isDark) Color.parseColor("#121212") else Color.parseColor("#F6F6F6")
        val cardBgColor = if (isDark) Color.parseColor("#1E1E1E") else Color.parseColor("#FFFFFF")
        val primaryTextColor = if (isDark) Color.parseColor("#F5F5F7") else Color.parseColor("#1D1D1F")
        val secondaryTextColor = if (isDark) Color.parseColor("#8E8E93") else Color.parseColor("#86868B")
        val sectionTextColor = if (isDark) Color.parseColor("#AAAAAA") else Color.parseColor("#444444")
        val accentColor = Color.parseColor("#FF5252")
        val dividerColor = if (isDark) Color.parseColor("#2C2C2E") else Color.parseColor("#EFEFEF")

        val dialog = Dialog(activity, android.R.style.Theme_NoTitleBar)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val rootFrame = FrameLayout(activity).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundColor(bgColor)
        }

        val mainLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        val statusBarPlaceholder = View(activity).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, statusBarHeight)
            setBackgroundColor(cardBgColor)
        }
        mainLayout.addView(statusBarPlaceholder)

        val navBar = FrameLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48))
            setBackgroundColor(cardBgColor)
        }

        val backBtn = TextView(activity).apply {
            text = "‹"
            textSize = 34f
            setTextColor(primaryTextColor)
            setPadding(dp(16), 0, dp(16), dp(3))
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT).apply {
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
            }
            setOnClickListener { dialog.dismiss() }
        }

        val pageTitle = TextView(activity).apply {
            text = "JumpClean 设置"
            textSize = 17.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(primaryTextColor)
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER
            }
        }

        navBar.addView(pageTitle)
        navBar.addView(backBtn)
        mainLayout.addView(navBar)

        val navDivider = View(activity).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
            setBackgroundColor(dividerColor)
        }
        mainLayout.addView(navDivider)

        val scrollView = ScrollView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            isVerticalScrollBarEnabled = false
        }

        val contentLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(96))
        }

        val initialMap = mutableMapOf<String, Boolean>()
        val stateMap = mutableMapOf<String, Boolean>()
        var currentCardLayout: LinearLayout? = null

        val fabButton = TextView(activity).apply {
            text = "✓ 保存并重启"
            textSize = 14.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(13), dp(28), dp(13))
            background = GradientDrawable().apply {
                setColor(accentColor)
                cornerRadius = dp(24).toFloat()
            }
            elevation = dp(8).toFloat()
            visibility = View.GONE
            translationY = dp(70).toFloat()
            alpha = 0f

            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(24)
            }
        }

        fun updateFabState() {
            var hasChange = false
            stateMap.forEach { (k, v) ->
                if (initialMap[k] != v) hasChange = true
            }

            if (hasChange) {
                if (fabButton.visibility != View.VISIBLE) {
                    fabButton.visibility = View.VISIBLE
                    fabButton.animate()
                        .translationY(0f)
                        .alpha(1f)
                        .setDuration(220)
                        .setListener(null)
                        .start()
                }
            } else {
                if (fabButton.visibility == View.VISIBLE) {
                    fabButton.animate()
                        .translationY(dp(70).toFloat())
                        .alpha(0f)
                        .setDuration(180)
                        .setListener(object : AnimatorListenerAdapter() {
                            override fun onAnimationEnd(animation: Animator) {
                                fabButton.visibility = View.GONE
                            }
                        })
                        .start()
                }
            }
        }

        fabButton.setOnClickListener {
            val editor = prefs.edit()
            stateMap.forEach { (key, value) ->
                editor.putBoolean(key, value)
            }
            if (editor.commit()) {
                dialog.dismiss()
                restartApp(activity)
            } else {
                Toast.makeText(activity, "保存失败，请重试", Toast.LENGTH_SHORT).show()
            }
        }

        items.forEach { entry ->
            when (entry) {
                is SectionHeader -> {
                    val sectionTitle = TextView(activity).apply {
                        text = entry.title
                        textSize = 12.5f
                        setTypeface(null, Typeface.BOLD)
                        setTextColor(sectionTextColor)
                        setPadding(dp(8), dp(14), dp(8), dp(6))
                    }
                    contentLayout.addView(sectionTitle)

                    currentCardLayout = LinearLayout(activity).apply {
                        orientation = LinearLayout.VERTICAL
                        background = GradientDrawable().apply {
                            setColor(cardBgColor)
                            cornerRadius = dp(14).toFloat()
                        }
                        setPadding(dp(16), dp(4), dp(16), dp(4))
                    }
                    contentLayout.addView(currentCardLayout)
                }
                is SettingItem -> {
                    val isChecked = prefs.getBoolean(entry.key, ConfigManager.getDefaultFeatureValue(entry.key))
                    initialMap[entry.key] = isChecked
                    stateMap[entry.key] = isChecked

                    val rowLayout = LinearLayout(activity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(0, dp(11), 0, dp(11))
                    }

                    val textContainer = LinearLayout(activity).apply {
                        orientation = LinearLayout.VERTICAL
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    }

                    val itemTitle = TextView(activity).apply {
                        text = entry.title
                        textSize = 14.5f
                        setTextColor(primaryTextColor)
                    }
                    textContainer.addView(itemTitle)

                    if (entry.desc.isNotEmpty()) {
                        val itemDesc = TextView(activity).apply {
                            text = entry.desc
                            textSize = 11f
                            setTextColor(secondaryTextColor)
                            setPadding(0, dp(2), 0, 0)
                        }
                        textContainer.addView(itemDesc)
                    }

                    val switchView = Switch(activity).apply {
                        this.isChecked = isChecked
                        setOnCheckedChangeListener { _, checked ->
                            stateMap[entry.key] = checked
                            updateFabState()
                        }
                    }

                    rowLayout.addView(textContainer)
                    rowLayout.addView(switchView)
                    rowLayout.setOnClickListener { switchView.toggle() }

                    currentCardLayout?.addView(rowLayout)
                }
                is ConfigurableSettingItem -> {
                    val isChecked = prefs.getBoolean(entry.key, ConfigManager.getDefaultFeatureValue(entry.key))
                    initialMap[entry.key] = isChecked
                    stateMap[entry.key] = isChecked

                    val rowLayout = LinearLayout(activity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(0, dp(11), 0, dp(11))
                        isClickable = true
                        isFocusable = true
                    }

                    val textContainer = LinearLayout(activity).apply {
                        orientation = LinearLayout.VERTICAL
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    }

                    val itemTitle = TextView(activity).apply {
                        text = entry.title
                        textSize = 14.5f
                        setTextColor(primaryTextColor)
                    }
                    textContainer.addView(itemTitle)

                    val itemDesc = TextView(activity).apply {
                        text = entry.desc
                        textSize = 11f
                        setTextColor(secondaryTextColor)
                        setPadding(0, dp(2), 0, 0)
                    }
                    textContainer.addView(itemDesc)
                    entry.bindDescView?.invoke(itemDesc)

                    val switchView = Switch(activity).apply {
                        this.isChecked = isChecked
                        setOnCheckedChangeListener { _, checked ->
                            stateMap[entry.key] = checked
                            updateFabState()
                        }
                    }

                    rowLayout.addView(textContainer)
                    rowLayout.addView(switchView)
                    rowLayout.setOnClickListener { entry.onConfigClick() }

                    currentCardLayout?.addView(rowLayout)
                }
                is ActionItem -> {
                    val rowLayout = LinearLayout(activity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(0, dp(12), 0, dp(12))
                        isClickable = true
                        isFocusable = true
                    }

                    val textContainer = LinearLayout(activity).apply {
                        orientation = LinearLayout.VERTICAL
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    }

                    val itemTitle = TextView(activity).apply {
                        text = entry.title
                        textSize = 14.5f
                        setTextColor(primaryTextColor)
                    }
                    textContainer.addView(itemTitle)

                    if (entry.desc.isNotEmpty()) {
                        val itemDesc = TextView(activity).apply {
                            text = entry.desc
                            textSize = 11f
                            setTextColor(secondaryTextColor)
                            setPadding(0, dp(2), 0, 0)
                        }
                        textContainer.addView(itemDesc)
                    }

                    val arrowView = TextView(activity).apply {
                        text = "›"
                        textSize = 22f
                        setTextColor(secondaryTextColor)
                        setPadding(dp(4), 0, 0, dp(2))
                    }

                    rowLayout.addView(textContainer)
                    rowLayout.addView(arrowView)
                    rowLayout.setOnClickListener { entry.onClick() }

                    currentCardLayout?.addView(rowLayout)
                }
            }
        }

        scrollView.addView(contentLayout)
        mainLayout.addView(scrollView)
        rootFrame.addView(mainLayout)
        rootFrame.addView(fabButton)

        dialog.setContentView(rootFrame)

        dialog.window?.let { win ->
            win.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            win.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            win.setFlags(
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            )

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val controller = win.insetsController
                if (controller != null) {
                    if (!isDark) {
                        controller.setSystemBarsAppearance(
                            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
                            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        )
                    } else {
                        controller.setSystemBarsAppearance(
                            0,
                            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        )
                    }
                }
            } else {
                var flags = win.decorView.systemUiVisibility
                flags = if (!isDark) {
                    flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                } else {
                    flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
                }
                win.decorView.systemUiVisibility = flags
            }
        }

        try {
            dialog.show()
        } catch (e: Exception) {
            ConfigManager.logError("显示设置面板失败", e)
        }
    }

    private fun restartApp(activity: Activity) {
        try {
            val intent = activity.packageManager.getLaunchIntentForPackage(activity.packageName)
            activity.startActivity(Intent.makeRestartActivityTask(intent?.component))
            Runtime.getRuntime().exit(0)
        } catch (e: Exception) {
            Toast.makeText(activity, "已保存，请手动重启 App", Toast.LENGTH_SHORT).show()
        }
    }
}
