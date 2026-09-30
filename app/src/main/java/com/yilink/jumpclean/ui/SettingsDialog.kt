package com.yilink.jumpclean.ui

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
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import com.yilink.jumpclean.config.ConfigManager
import com.yilink.jumpclean.config.JumpConstants
import com.yilink.jumpclean.hooks.ViewCleanHooks

sealed interface SettingEntry
data class SectionHeader(val title: String) : SettingEntry
data class SettingItem(val key: String, val title: CharSequence, val desc: String = "") : SettingEntry
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

        val isDark = (activity.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES

        val bgColor = if (isDark) Color.parseColor("#121212") else Color.parseColor("#F6F6F6")
        val cardBgColor = if (isDark) Color.parseColor("#1E1E1E") else Color.parseColor("#FFFFFF")
        val primaryTextColor = if (isDark) Color.parseColor("#F5F5F7") else Color.parseColor("#1D1D1F")
        val secondaryTextColor = if (isDark) Color.parseColor("#8E8E93") else Color.parseColor("#86868B")
        val sectionTextColor = if (isDark) Color.parseColor("#AAAAAA") else Color.parseColor("#444444")
        val dividerColor = if (isDark) Color.parseColor("#2C2C2E") else Color.parseColor("#EFEFEF")

        var keywordDescView: TextView? = null
        var updateTerminalVisibility: ((Boolean) -> Unit)? = null

        val items = listOf(
            SectionHeader("启动与弹窗"),
            SettingItem(JumpConstants.KEY_SKIP_SPLASH, "跳过开屏广告"),
            SettingItem(JumpConstants.KEY_HIDE_VOUCHER_POPUP, "屏蔽营销弹窗"),
            SettingItem(JumpConstants.KEY_HIDE_MSG_PUSH_GUIDE, "屏蔽通知开启引导"),

            SectionHeader("首页"),
            SettingItem(JumpConstants.KEY_HIDE_TOPIC_LIST, "隐藏顶部话题栏"),
            SettingItem(JumpConstants.KEY_HIDE_BANNER, "屏蔽首页轮播广告"),
            SettingItem(JumpConstants.KEY_HIDE_HOT_DISCUSS, "隐藏「Jumper热议」卡片"),
            SettingItem(JumpConstants.KEY_EXP_BLOCK_OFFICIAL_PROMO_POST, "屏蔽推荐流小酱推广贴"),
            SettingItem(JumpConstants.KEY_HIDE_POST_AD, "屏蔽推荐流与帖子内嵌广告"),
            SettingItem(JumpConstants.KEY_HIDE_PUBLISH_TOPIC, "隐藏发布按钮"),

            SectionHeader("发现页"),
            SettingItem(JumpConstants.KEY_HIDE_DISCOVER_TOP_AD, "屏蔽顶部广告"),
            SettingItem(JumpConstants.KEY_HIDE_DISCOVER_BANNER, "屏蔽轮播广告"),

            SectionHeader("游戏折扣页"),
            SettingItem(JumpConstants.KEY_HIDE_GAME_MEMBER_GUIDE, "屏蔽会员广告"),
            SettingItem(JumpConstants.KEY_HIDE_GAME_FIND_AD, "屏蔽促销横幅广告"),
            SettingItem(JumpConstants.KEY_HIDE_GAME_PRICE_ADS, "屏蔽低价排名广告"),
            SettingItem(JumpConstants.KEY_HIDE_GAME_DYNAMIC_BUBBLE, "屏蔽滚动营销弹幕"),
            SettingItem(JumpConstants.KEY_HIDE_GAME_BOTTOM_TRIAL_AD, "隐藏「购前体验」按钮"),
            SettingItem(JumpConstants.KEY_HIDE_GAME_SECOND_HAND, "隐藏「二手比价」栏"),

            SectionHeader("内容与详情"),
            SettingItem(JumpConstants.KEY_ENABLE_COPY, "允许长按复制文本"),
            SettingItem(JumpConstants.KEY_RESTORE_POST_YEAR, "恢复帖子完整年份"),
            SettingItem(JumpConstants.KEY_HIDE_CONTENT_MEMBER_MASK, "查看游戏评价总结"),
            ConfigurableSettingItem(
                JumpConstants.KEY_ENABLE_KEYWORD_BLOCK,
                "帖子关键词屏蔽",
                desc = getKeywordItemDesc(activity),
                bindDescView = { keywordDescView = it }
            ) {
                KeywordDialog.show(activity) {
                    keywordDescView?.text = getKeywordItemDesc(activity)
                }
            },

            SectionHeader("个人中心"),
            SettingItem(JumpConstants.KEY_HIDE_MEMBER_CARD, "隐藏 Jump+ 会员卡片"),
            SettingItem(JumpConstants.KEY_HIDE_MY_ORDER, "隐藏「我的订单」"),
            SettingItem(JumpConstants.KEY_HIDE_PHOTO_WALL, "隐藏截图展示墙"),
            SettingItem(JumpConstants.KEY_HIDE_WIDGET_VIP_TAG, "隐藏小组件会员标识"),

            SectionHeader("底栏"),
            SettingItem(JumpConstants.KEY_HIDE_WEB_TAB, "隐藏「Jump 赏」"),
            SettingItem(JumpConstants.KEY_HIDE_LOTTERY_TAB, "隐藏「抽奖 / 全新 App」"),

            SectionHeader("个性化与扩展"),
            ActionItem("更换 App 图标") {
                IconPickerDialog.show(activity)
            },
            SettingItem(JumpConstants.KEY_ENABLE_DEBUG_LOG, "开启调试日志"),
            ActionItem("查看诊断日志", desc = "查看近期 200 行运行日志") {
                LogDialog.show(activity)
            },

            SectionHeader("实验性功能"),
            SettingItem(
                JumpConstants.KEY_BLOCK_NOTIFICATION_AD,
                "屏蔽通知广告",
                desc = "去除应用内突袭震动横幅广告"
            ),
            ActionItem("大  饼", desc = "只能看不能用") {
                PieDialog.show(activity) { enabled ->
                    updateTerminalVisibility?.invoke(enabled)
                }
            }
        )

        val dp = { value: Int -> (value * activity.resources.displayMetrics.density).toInt() }

        val statusBarHeight = run {
            val resourceId = activity.resources.getIdentifier("status_bar_height", "dimen", "android")
            if (resourceId > 0) activity.resources.getDimensionPixelSize(resourceId) else dp(28)
        }

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
            setPadding(dp(16), dp(8), dp(16), dp(32))
        }

        // 熊先生商会 · 业务结算（顶部战报卡片，由大饼彩蛋中的 ??? 激活解锁）
        val blockedPromoCount = prefs.getInt(JumpConstants.KEY_BLOCKED_OFFICIAL_PROMO_COUNT, 0)
        val isTerminalEnabled = prefs.getBoolean(JumpConstants.KEY_SHOW_BEAR_TERMINAL, false)

        val statusCard = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (isTerminalEnabled) View.VISIBLE else View.GONE
            background = GradientDrawable().apply {
                setColor(cardBgColor)
                cornerRadius = dp(14).toFloat()
                if (!isDark) {
                    setStroke(dp(1), Color.parseColor("#E5E5EA"))
                }
            }
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(4)
                bottomMargin = dp(8)
            }
        }

        val terminalTitle = TextView(activity).apply {
            text = "🐟 熊先生商会 · 业务结算"
            textSize = 13.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(sectionTextColor)
        }
        statusCard.addView(terminalTitle)

        val statRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            setPadding(0, dp(6), 0, 0)
        }

        val countText = TextView(activity).apply {
            text = "$blockedPromoCount"
            textSize = 26f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#FF5252"))
        }

        val unitText = TextView(activity).apply {
            text = " 份「小酱」配额已达成"
            textSize = 13.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(primaryTextColor)
            setPadding(0, 0, 0, dp(3))
        }

        statRow.addView(countText)
        statRow.addView(unitText)
        statusCard.addView(statRow)

        val getEvaluationDesc = { count: Int ->
            when {
                count < 10 -> "评价等级：学徒。刚领到打工装备，正在熟悉小酱的出现规律"
                count < 50 -> "评价等级：半熟。渐入佳境，配额收集稳步推进中，干得不错"
                count < 200 -> "评价等级：独当一面。业务娴熟，多余的小酱推广均被精准回收"
                count < 500 -> "评价等级：熟练。高效运作，配额交付十分稳定，值得信赖"
                count < 1000 -> "评价等级：达人。金牌员工，任何伪装推广都逃不过你的准星"
                else -> "评价等级：传说。终极打工鱿，这片信息流已被彻底净化！"
            }
        }

        val descText = TextView(activity).apply {
            text = getEvaluationDesc(blockedPromoCount)
            textSize = 11.5f
            setTextColor(secondaryTextColor)
            setPadding(0, dp(4), 0, 0)
        }
        statusCard.addView(descText)

        contentLayout.addView(statusCard)

        updateTerminalVisibility = { enabled ->
            val count = prefs.getInt(JumpConstants.KEY_BLOCKED_OFFICIAL_PROMO_COUNT, 0)
            countText.text = "$count"
            descText.text = getEvaluationDesc(count)
            statusCard.visibility = if (enabled) View.VISIBLE else View.GONE
        }

        var currentCardLayout: LinearLayout? = null

        // 屏蔽通知广告在启动期按需挂载，修改后提示重启生效
        val restartRequiredKeys = setOf(JumpConstants.KEY_BLOCK_NOTIFICATION_AD)

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
                            prefs.edit().putBoolean(entry.key, checked).apply()
                            ViewCleanHooks.applyAllUIVisibility(activity)
                            if (entry.key in restartRequiredKeys) {
                                showRestartPrompt(activity, isDark, dp)
                            }
                        }
                    }

                    rowLayout.addView(textContainer)
                    rowLayout.addView(switchView)
                    rowLayout.setOnClickListener { switchView.toggle() }

                    currentCardLayout?.addView(rowLayout)
                }
                is ConfigurableSettingItem -> {
                    val isChecked = prefs.getBoolean(entry.key, ConfigManager.getDefaultFeatureValue(entry.key))

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
                            prefs.edit().putBoolean(entry.key, checked).apply()
                            ViewCleanHooks.applyAllUIVisibility(activity)
                            if (entry.key in restartRequiredKeys) {
                                showRestartPrompt(activity, isDark, dp)
                            }
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

    private fun showRestartPrompt(activity: Activity, isDark: Boolean, dp: (Int) -> Int) {
        val promptDialog = Dialog(activity).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }

        val cardBg = if (isDark) Color.parseColor("#242426") else Color.WHITE
        val primaryText = if (isDark) Color.parseColor("#F5F5F7") else Color.parseColor("#1C1C1E")
        val secondaryText = if (isDark) Color.parseColor("#8E8E93") else Color.parseColor("#8A8A8E")
        val cancelBtnBg = if (isDark) Color.parseColor("#323236") else Color.parseColor("#F0F0F2")
        val cancelBtnText = if (isDark) Color.parseColor("#D1D1D6") else Color.parseColor("#636366")
        val accentColor = Color.parseColor("#E54D42") // 柔和温润的 Jump 珊瑚红

        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            minimumWidth = dp(300)
            background = GradientDrawable().apply {
                setColor(cardBg)
                cornerRadius = dp(20).toFloat()
            }
            setPadding(dp(22), dp(22), dp(22), dp(18))
            layoutParams = ViewGroup.LayoutParams(dp(300), ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val titleView = TextView(activity).apply {
            text = "重启 App 生效"
            textSize = 17f
            setTypeface(null, Typeface.BOLD)
            setTextColor(primaryText)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val descView = TextView(activity).apply {
            text = "此项改动需重启后生效"
            textSize = 13.5f
            setTextColor(secondaryText)
            gravity = Gravity.CENTER
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            setLineSpacing(dp(3).toFloat(), 1f)
            setPadding(dp(6), dp(10), dp(6), dp(20))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val btnContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val cancelBtn = TextView(activity).apply {
            text = "稍后"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(cancelBtnText)
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, dp(10))
            background = GradientDrawable().apply {
                setColor(cancelBtnBg)
                cornerRadius = dp(12).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(6)
            }
            setOnClickListener { promptDialog.dismiss() }
        }

        val restartBtn = TextView(activity).apply {
            text = "立即重启"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, dp(10))
            background = GradientDrawable().apply {
                setColor(accentColor)
                cornerRadius = dp(12).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(6)
            }
            setOnClickListener {
                promptDialog.dismiss()
                restartApp(activity)
            }
        }

        btnContainer.addView(cancelBtn)
        btnContainer.addView(restartBtn)

        card.addView(titleView)
        card.addView(descView)
        card.addView(btnContainer)

        promptDialog.setContentView(card)
        promptDialog.show()
        promptDialog.window?.let { win ->
            win.setLayout(dp(300), ViewGroup.LayoutParams.WRAP_CONTENT)
            win.setGravity(Gravity.CENTER)
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
