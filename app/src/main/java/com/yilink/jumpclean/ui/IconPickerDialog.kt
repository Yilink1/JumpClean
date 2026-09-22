package com.yilink.jumpclean.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.yilink.jumpclean.config.ConfigManager
import com.yilink.jumpclean.config.JumpConstants
import de.robv.android.xposed.XposedHelpers

object IconPickerDialog {

    /**
     * 21 宫格网格选择面板
     */
    @SuppressLint("SetTextI18n")
    fun show(activity: Activity) {
        try {
            val pm = activity.packageManager
            val pkgName = activity.packageName

            var currentIdx = 0
            for (i in JumpConstants.OFFICIAL_ICONS.indices) {
                val cls = JumpConstants.OFFICIAL_ICONS[i].targetClass
                val state = pm.getComponentEnabledSetting(ComponentName(pkgName, cls))
                if (state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
                    currentIdx = i
                    break
                }
            }

            val dp = { value: Int -> (value * activity.resources.displayMetrics.density).toInt() }
            val isDark = (activity.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                    android.content.res.Configuration.UI_MODE_NIGHT_YES

            val dialogBgColor = if (isDark) Color.parseColor("#1E1E1E") else Color.parseColor("#FFFFFF")
            val primaryTextColor = if (isDark) Color.parseColor("#F5F5F7") else Color.parseColor("#1D1D1F")
            val secondaryTextColor = if (isDark) Color.parseColor("#8E8E93") else Color.parseColor("#86868B")
            val activeBorderColor = Color.parseColor("#00B06F")

            val iconDialog = Dialog(activity)
            iconDialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

            val rootLayout = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply {
                    setColor(dialogBgColor)
                    cornerRadius = dp(20).toFloat()
                }
                setPadding(dp(16), dp(18), dp(16), dp(16))
                layoutParams = ViewGroup.LayoutParams(dp(330), ViewGroup.LayoutParams.WRAP_CONTENT)
            }

            val titleView = TextView(activity).apply {
                text = "更换 App 图标"
                textSize = 17.5f
                setTypeface(null, Typeface.BOLD)
                setTextColor(primaryTextColor)
                gravity = Gravity.CENTER
            }
            rootLayout.addView(titleView)

            val tipView = TextView(activity).apply {
                text = "切换后首次进入可能有开屏广告"
                textSize = 11.5f
                setTextColor(secondaryTextColor)
                gravity = Gravity.CENTER
                setPadding(0, dp(4), 0, dp(12))
            }
            rootLayout.addView(tipView)

            val gridScroll = ScrollView(activity).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(350))
                isVerticalScrollBarEnabled = false
            }

            val grid = GridLayout(activity).apply {
                columnCount = 4
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }

            for (i in JumpConstants.OFFICIAL_ICONS.indices) {
                val iconModel = JumpConstants.OFFICIAL_ICONS[i]
                val itemFrame = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    val p = dp(4)
                    setPadding(p, p, p, p)
                    layoutParams = GridLayout.LayoutParams().apply {
                        width = dp(74)
                        height = dp(84)
                        setGravity(Gravity.CENTER)
                    }
                    background = GradientDrawable().apply {
                        cornerRadius = dp(12).toFloat()
                        if (i == currentIdx) {
                            setStroke(dp(2), activeBorderColor)
                            setColor(if (isDark) Color.parseColor("#2A3B32") else Color.parseColor("#E6F7F0"))
                        } else {
                            setColor(Color.TRANSPARENT)
                        }
                    }
                }

                val iconView = ImageView(activity).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
                    val resId = activity.resources.getIdentifier(iconModel.drawableName, "drawable", pkgName).let {
                        if (it != 0) it else activity.resources.getIdentifier(iconModel.drawableName, "mipmap", pkgName)
                    }
                    if (resId != 0) {
                        setImageResource(resId)
                    } else {
                        setImageResource(activity.applicationInfo.icon)
                    }
                }

                val nameView = TextView(activity).apply {
                    text = iconModel.name
                    textSize = 10f
                    setTextColor(if (i == currentIdx) activeBorderColor else secondaryTextColor)
                    gravity = Gravity.CENTER
                    maxLines = 1
                    setPadding(0, dp(2), 0, 0)
                }

                itemFrame.addView(iconView)
                itemFrame.addView(nameView)

                itemFrame.setOnClickListener {
                    iconDialog.dismiss()
                    if (i != currentIdx) {
                        val oldAlias = JumpConstants.OFFICIAL_ICONS[currentIdx].targetClass
                        val newAlias = iconModel.targetClass
                        val shortKey = iconModel.shortKey
                        executeOfficialIconSwitch(activity, oldAlias, newAlias, shortKey)
                    }
                }
                grid.addView(itemFrame)
            }

            gridScroll.addView(grid)
            rootLayout.addView(gridScroll)

            val cancelBtn = TextView(activity).apply {
                text = "取消"
                textSize = 14f
                setTextColor(secondaryTextColor)
                gravity = Gravity.CENTER
                setPadding(0, dp(12), 0, 0)
                setOnClickListener { iconDialog.dismiss() }
            }
            rootLayout.addView(cancelBtn)

            iconDialog.setContentView(rootLayout)
            iconDialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            iconDialog.show()

        } catch (e: Exception) {
            ConfigManager.logError("拉起 21 宫格图标面板失败", e)
            Toast.makeText(activity, "拉起面板失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun executeOfficialIconSwitch(context: Context, oldAliasClass: String, newAliasClass: String, shortKey: String) {
        try {
            val pm = context.packageManager
            val pkgName = context.packageName

            if (oldAliasClass.isNotBlank() && oldAliasClass != newAliasClass) {
                pm.setComponentEnabledSetting(
                    ComponentName(pkgName, oldAliasClass),
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP
                )
            }

            try {
                val mmkvClass = XposedHelpers.findClassIfExists("com.tencent.mmkv.MMKV", context.classLoader)
                if (mmkvClass != null) {
                    val defaultMMKV = XposedHelpers.callStaticMethod(mmkvClass, "defaultMMKV")
                    XposedHelpers.callMethod(defaultMMKV, "encode", "launch_icon", shortKey)
                }
            } catch (_: Exception) {}

            pm.setComponentEnabledSetting(
                ComponentName(pkgName, newAliasClass),
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )

            Toast.makeText(context, "图标已更换，正在重载...", Toast.LENGTH_SHORT).show()

            val restartIntent = Intent().apply {
                component = ComponentName(pkgName, newAliasClass)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                putExtra(JumpConstants.EXTRA_ICON_RESTART, true)
            }
            context.startActivity(restartIntent)

            Handler(Looper.getMainLooper()).postDelayed({
                Runtime.getRuntime().exit(0)
            }, 200L)

        } catch (e: Exception) {
            ConfigManager.logError("执行图标切换异常", e)
            Toast.makeText(context, "切换失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
