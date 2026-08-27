package com.yilink.jumpclean

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowInsetsController
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class SettingsActivity : Activity() {

    /**
     * 激活状态锚点方法：
     * 编译进 APK 时恒定返回 false。
     * 当 LSPosed 模块被激活（自身在作用域内）时，MainHook 会精确 hook 这个方法并强制返回 true。
     */
    private fun isActivated(): Boolean {
        return false
    }

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        super.onCreate(savedInstanceState)

        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }

        val statusBarHeight = run {
            val resourceId = resources.getIdentifier("status_bar_height", "dimen", "android")
            if (resourceId > 0) resources.getDimensionPixelSize(resourceId) else dp(32)
        }

        val isDark = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES

        val bgColor = if (isDark) Color.parseColor("#121212") else Color.parseColor("#F5F6F9")
        val cardBgColor = if (isDark) Color.parseColor("#1E1E1E") else Color.parseColor("#FFFFFF")
        val primaryText = if (isDark) Color.parseColor("#F5F5F7") else Color.parseColor("#1C1C1E")
        val secondaryText = if (isDark) Color.parseColor("#8E8E93") else Color.parseColor("#8A8A8E")

        window.statusBarColor = bgColor

        val root = ScrollView(this).apply {
            setBackgroundColor(bgColor)
            isVerticalScrollBarEnabled = false
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), statusBarHeight + dp(18), dp(20), dp(40))
        }

        // 1. 标题
        val title = TextView(this).apply {
            text = "JumpClean"
            textSize = 28f
            setTypeface(null, Typeface.BOLD)
            setTextColor(primaryText)
            setPadding(dp(4), 0, 0, dp(18))
        }
        container.addView(title)

        // 2. 激活状态大卡片
        val isActive = isActivated()
        val activeThemeColor = if (isActive) Color.parseColor("#00B06F") else Color.parseColor("#FF6B6B")

        val activeCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(18))
            background = GradientDrawable().apply {
                setColor(activeThemeColor)
                cornerRadius = dp(16).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(14)
            }
        }

        val checkIcon = TextView(this).apply {
            text = if (isActive) "✓" else "!"
            textSize = 17f
            setTypeface(null, Typeface.BOLD)
            setTextColor(activeThemeColor)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                shape = GradientDrawable.OVAL
                setSize(dp(28), dp(28))
            }
            layoutParams = LinearLayout.LayoutParams(dp(28), dp(28)).apply {
                marginEnd = dp(14)
            }
        }

        val activeTextGroup = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply {
                text = if (isActive) "已激活" else "未激活"
                textSize = 17f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.WHITE)
            })
            addView(TextView(context).apply {
                text = if (isActive) "LSPosed / Hook 服务正常运行" else "请在 LSPosed 中勾选本模块与宿主"
                textSize = 12f
                setTextColor(if (isActive) Color.parseColor("#E0F8EE") else Color.parseColor("#FFEAEA"))
                setPadding(0, dp(2), 0, 0)
            })
        }
        activeCard.addView(checkIcon)
        activeCard.addView(activeTextGroup)
        container.addView(activeCard)

        // 通用卡片构建
        fun createCard(titleStr: String, descStr: String, actionText: String? = null, onClick: (() -> Unit)? = null): LinearLayout {
            return LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(18), dp(16), dp(18), dp(16))
                background = GradientDrawable().apply {
                    setColor(cardBgColor)
                    cornerRadius = dp(16).toFloat()
                }
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(12)
                }
                if (onClick != null) {
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { onClick() }
                }

                addView(TextView(context).apply {
                    text = titleStr
                    textSize = 15.5f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(primaryText)
                })

                addView(TextView(context).apply {
                    text = descStr
                    textSize = 12.5f
                    setTextColor(secondaryText)
                    setPadding(0, dp(4), 0, 0)
                    setLineSpacing(dp(2).toFloat(), 1f)
                })

                if (actionText != null) {
                    addView(TextView(context).apply {
                        text = actionText
                        textSize = 13f
                        setTypeface(null, Typeface.BOLD)
                        setTextColor(Color.parseColor("#007AFF"))
                        setPadding(0, dp(10), 0, 0)
                    })
                }
            }
        }

        // 3. 动态获取自身 APK 的真实版本号
        val appVersionName = try {
            val pkgInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0)
            }
            pkgInfo.versionName ?: "1.0.0"
        } catch (_: Exception) {
            "1.0.0"
        }

        container.addView(createCard(
            "模块版本",
            "v$appVersionName (Release)\n还原纯粹的社区体验"
        ))

        // 4. 模块设置指引卡片
        container.addView(createCard(
            "模块设置",
            "设置面板已深度集成在宿主中\n请在客户端首页长按底栏「我的」Tab 开启",
            "打开客户端 ›"
        ) {
            var launched = false
            try {
                val launchIntent = packageManager.getLaunchIntentForPackage("com.vgjump.jump")
                if (launchIntent != null) {
                    startActivity(launchIntent)
                    launched = true
                }
            } catch (_: Exception) {}

            if (!launched) {
                try {
                    val explicitIntent = Intent().apply {
                        component = ComponentName("com.vgjump.jump", "com.vgjump.jump.ui.main.MainActivity")
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    startActivity(explicitIntent)
                    launched = true
                } catch (_: Exception) {}
            }

            if (!launched) {
                Toast.makeText(this, "未检测到已安装的 Jump 客户端", Toast.LENGTH_SHORT).show()
            }
        })

        // 5. GitHub 仓库跳转卡片
        container.addView(createCard(
            "GitHub",
            "开源主页与更新日志",
            "查看源码仓库 ↗"
        ) {
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Yilink1/JumpClean"))
                startActivity(intent)
            } catch (_: Exception) {}
        })

        root.addView(container)
        setContentView(root)

        // 状态栏沉浸适配
        window?.let { win ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                win.insetsController?.setSystemBarsAppearance(
                    if (!isDark) WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS else 0,
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                )
            } else {
                @Suppress("DEPRECATION")
                var flags = win.decorView.systemUiVisibility
                flags = if (!isDark) flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR else flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
                @Suppress("DEPRECATION")
                win.decorView.systemUiVisibility = flags
            }
        }
    }
}