package com.yilink.jumpclean.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import com.yilink.jumpclean.config.JumpConstants

object PieDialog {

    private val OVEN_TOASTS = listOf(
        "🍓 赞助了作者 202 颗草莓",
        "🖤 赞助了作者一个虚空之心",
        "💡 赞助了作者一个太阳灯泡",
        "⭐ 赞助了作者一颗星之果实"
    )

    @SuppressLint("SetTextI18n")
    fun show(activity: Activity, onTerminalToggled: ((Boolean) -> Unit)? = null) {
        val dp = { value: Int -> (value * activity.resources.displayMetrics.density).toInt() }
        val isDark = (activity.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES

        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val dialogBg = if (isDark) Color.parseColor("#1C1C1E") else Color.parseColor("#FFFFFF")
        val cardBg = if (isDark) Color.parseColor("#28282A") else Color.parseColor("#F5F5F7")
        val primaryText = if (isDark) Color.parseColor("#F5F5F7") else Color.parseColor("#1D1D1F")
        val secondaryText = if (isDark) Color.parseColor("#8E8E93") else Color.parseColor("#86868B")
        val accentRed = Color.parseColor("#FF5252")
        val badgeBg = if (isDark) Color.parseColor("#33FF5252") else Color.parseColor("#14FF5252")

        val screenWidth = activity.resources.displayMetrics.widthPixels
        val screenHeight = activity.resources.displayMetrics.heightPixels

        // 全屏透明容器（使提示能真正定位于手机屏幕最底端，且不带任何第三方应用图标）
        val fullScreenContainer = FrameLayout(activity).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setOnClickListener { dialog.dismiss() }
        }

        val rootFrame = FrameLayout(activity).apply {
            background = GradientDrawable().apply {
                setColor(dialogBg)
                cornerRadius = dp(22).toFloat()
            }
            layoutParams = FrameLayout.LayoutParams(
                (screenWidth * 0.88f).toInt().coerceAtMost(dp(350)),
                (screenHeight * 0.70f).toInt().coerceAtMost(dp(440))
            ).apply {
                gravity = Gravity.CENTER
            }
            // 消费点击事件，防止点击卡片内部触发 dismiss
            setOnClickListener { }
        }

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(18))
        }

        // 1. 顶部标题
        val headerLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val titleView = TextView(activity).apply {
            text = "🫓 大饼工坊"
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setTextColor(primaryText)
        }
        headerLayout.addView(titleView)
        root.addView(headerLayout)

        // 2. 灵魂副标名梗标语
        val sloganView = TextView(activity).apply {
            text = "🗡️ 预计在《武士零》DLC 发售时开发完毕"
            textSize = 11.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(accentRed)
            background = GradientDrawable().apply {
                setColor(badgeBg)
                cornerRadius = dp(6).toFloat()
            }
            setPadding(dp(8), dp(4), dp(8), dp(4))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, dp(8), 0, dp(14))
            }
        }
        root.addView(sloganView)

        val scrollContent = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }

        // 3. 四大画饼特性列表（置灰不可用开关，点击各自对应固定神作彩蛋）
        val pieFeatures = listOf(
            Triple("动态与视频下载", "解析帖子视频直链，一键保存至本地相册", false),
            Triple("夜间模式重启提示", "切换深浅主题时提示重启，确保全局变色成功", false),
            Triple("「立即购买」替换跳转", "替换购买按钮为跳转小黑盒，并自动复制游戏名称", false),
            Triple("屏蔽应用更新", "阻止客户端检查新版本，屏蔽强制更新弹窗", false)
        )

        // 纯净浮层提示（白底微投影，无任何 Jump 图标，定位于手机物理屏幕最底端）
        val pillBgColor = if (isDark) Color.parseColor("#2C2C2E") else Color.parseColor("#FFFFFF")
        val pillTextColor = if (isDark) Color.parseColor("#FFFFFF") else Color.parseColor("#1D1D1F")
        val pillBorderColor = if (isDark) Color.parseColor("#48484A") else Color.parseColor("#E5E5EA")

        val overlayPill = TextView(activity).apply {
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(pillTextColor)
            background = GradientDrawable().apply {
                setColor(pillBgColor)
                cornerRadius = dp(20).toFloat()
                setStroke(dp(1), pillBorderColor)
            }
            setPadding(dp(18), dp(10), dp(18), dp(10))
            gravity = Gravity.CENTER
            visibility = View.GONE
            elevation = dp(8).toFloat()
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(96) // 稍微往上移，与原生 Toast 的视觉高度完全一致
            }
        }

        var pillDismissRunnable: Runnable? = null

        val showPillMessage: (String) -> Unit = { msg ->
            pillDismissRunnable?.let { overlayPill.removeCallbacks(it) }
            overlayPill.text = msg
            overlayPill.alpha = 0f
            overlayPill.scaleX = 0.9f
            overlayPill.scaleY = 0.9f
            overlayPill.visibility = View.VISIBLE
            overlayPill.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(160)
                .start()

            val runnable = Runnable {
                overlayPill.animate()
                    .alpha(0f)
                    .scaleX(0.9f)
                    .scaleY(0.9f)
                    .setDuration(200)
                    .withEndAction { overlayPill.visibility = View.GONE }
                    .start()
            }
            pillDismissRunnable = runnable
            overlayPill.postDelayed(runnable, 1300)
        }

        val featuresContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(cardBg)
                cornerRadius = dp(14).toFloat()
            }
            setPadding(dp(14), dp(4), dp(14), dp(4))
        }

        pieFeatures.forEachIndexed { index, (title, desc, _) ->
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(10), 0, dp(10))
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    val msg = OVEN_TOASTS.getOrElse(index) { "" }
                    showPillMessage(msg)
                }
            }

            val textCol = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }

            val itemTitle = TextView(activity).apply {
                text = title
                textSize = 14f
                setTextColor(primaryText)
            }
            textCol.addView(itemTitle)

            val itemDesc = TextView(activity).apply {
                text = desc
                textSize = 11f
                setTextColor(secondaryText)
                setPadding(0, dp(2), 0, 0)
            }
            textCol.addView(itemDesc)

            val switchView = Switch(activity).apply {
                isChecked = false
                isEnabled = false // 置灰视觉
                alpha = 0.45f
                isClickable = false
            }

            row.addView(textCol)
            row.addView(switchView)
            featuresContainer.addView(row)

            val divider = View(activity).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
                setBackgroundColor(if (isDark) Color.parseColor("#353538") else Color.parseColor("#EAEAEA"))
            }
            featuresContainer.addView(divider)
        }

        // 4. 彩蛋隐藏开关：??? 接入熊先生商会打工终端（唯一可点亮的真实开关）
        val prefs = activity.getSharedPreferences(JumpConstants.PREFS_NAME, Context.MODE_PRIVATE)
        var isTerminalEnabled = prefs.getBoolean(JumpConstants.KEY_SHOW_BEAR_TERMINAL, false)

        val secretRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(10))
            isClickable = true
            isFocusable = true
        }

        val secretTextCol = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val secretTitle = TextView(activity).apply {
            text = "???"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(primaryText)
        }
        secretTextCol.addView(secretTitle)

        val secretDesc = TextView(activity).apply {
            text = "打工鱿专属频段（开启后在设置页接入终端）"
            textSize = 11f
            setTextColor(secondaryText)
            setPadding(0, dp(2), 0, 0)
        }
        secretTextCol.addView(secretDesc)

        val secretSwitch = Switch(activity).apply {
            isChecked = isTerminalEnabled
            isClickable = false // 由外层 row 统一接管点击
        }

        val toggleTerminal: () -> Unit = {
            isTerminalEnabled = !isTerminalEnabled
            secretSwitch.isChecked = isTerminalEnabled
            prefs.edit().putBoolean(JumpConstants.KEY_SHOW_BEAR_TERMINAL, isTerminalEnabled).apply()
            onTerminalToggled?.invoke(isTerminalEnabled)
            if (isTerminalEnabled) {
                showPillMessage("🐟 成功接入「熊先生商会」业务终端！")
            } else {
                showPillMessage("打工终端已断开连接")
            }
        }

        secretRow.setOnClickListener { toggleTerminal() }
        secretRow.addView(secretTextCol)
        secretRow.addView(secretSwitch)
        featuresContainer.addView(secretRow)

        scrollContent.addView(featuresContainer)

        val scrollView = ScrollView(activity).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            addView(scrollContent)
        }
        root.addView(scrollView)

        // 5. 底部收起按钮
        val closeBtn = TextView(activity).apply {
            text = "吃饱了，收起大饼"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(accentRed)
                cornerRadius = dp(20).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(40)
            ).apply {
                topMargin = dp(14)
            }
            setOnClickListener { dialog.dismiss() }
        }
        root.addView(closeBtn)

        rootFrame.addView(root)

        fullScreenContainer.addView(rootFrame)
        fullScreenContainer.addView(overlayPill)

        dialog.setContentView(fullScreenContainer)
        dialog.window?.let { win ->
            win.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            win.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        dialog.show()
    }
}
