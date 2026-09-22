package com.yilink.jumpclean.hooks

import android.app.Activity
import android.view.View
import com.yilink.jumpclean.HookUtils
import com.yilink.jumpclean.config.ConfigManager
import com.yilink.jumpclean.config.JumpConstants
import com.yilink.jumpclean.ui.SettingsDialog
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.util.Collections
import java.util.WeakHashMap

object SettingsEntryHooks {

    private val hookedSettingAdapters = Collections.newSetFromMap(WeakHashMap<Class<*>, Boolean>())

    fun hook(lpparam: XC_LoadPackage.LoadPackageParam) {
        hookSettingsEntry(lpparam)
        hookSettingActivityEntry(lpparam)
    }

    private fun hookSettingActivityEntry(lpparam: XC_LoadPackage.LoadPackageParam) {
        val settingActivityClass = XposedHelpers.findClassIfExists(
            "com.vgjump.jump.ui.my.setting.SettingActivity", lpparam.classLoader
        ) ?: return

        val settingItemClass = XposedHelpers.findClassIfExists(
            "com.vgjump.jump.bean.my.SettingItem", lpparam.classLoader
        ) ?: return

        XposedBridge.hookAllMethods(settingActivityClass, "initData", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                try {
                    val activity = param.thisObject as? Activity ?: return
                    ConfigManager.initAppContext(activity)
                    val adapter = XposedHelpers.callMethod(activity, "s") ?: return

                    val constructor = settingItemClass.getConstructor(
                        java.lang.Integer::class.java,
                        String::class.java,
                        String::class.java,
                        String::class.java,
                        String::class.java,
                        String::class.java
                    )
                    val customItem = constructor.newInstance(
                        null,
                        JumpConstants.SETTING_ITEM_TITLE,
                        "",
                        "",
                        "1",
                        null
                    )

                    @Suppress("UNCHECKED_CAST")
                    val dataList = XposedHelpers.getObjectField(adapter, "f") as? MutableList<Any> ?: return
                    val alreadyExists = dataList.any { item ->
                        try {
                            XposedHelpers.callMethod(item, "getTitle") as? String == JumpConstants.SETTING_ITEM_TITLE
                        } catch (_: Exception) {
                            false
                        }
                    }

                    if (!alreadyExists) {
                        dataList.add(0, customItem)
                        try {
                            XposedHelpers.callMethod(adapter, "notifyItemInserted", 0)
                        } catch (_: Exception) {
                            XposedHelpers.callMethod(adapter, "notifyDataSetChanged")
                        }
                    }

                    hookAdapterBindForSettingsEntry(adapter.javaClass)
                } catch (e: Exception) {
                    ConfigManager.logError("向设置页插入条目失败", e)
                }
            }
        })
    }

    private fun hookAdapterBindForSettingsEntry(adapterClass: Class<*>) {
        synchronized(hookedSettingAdapters) {
            if (hookedSettingAdapters.contains(adapterClass)) return
            hookedSettingAdapters.add(adapterClass)
        }

        try {
            XposedBridge.hookAllMethods(adapterClass, "onBindViewHolder", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val args = param.args
                        if (args.size < 2) return
                        val holder = args[0] ?: return
                        val position = args[1] as? Int ?: return

                        val itemView = XposedHelpers.getObjectField(holder, "itemView") as? View ?: return

                        @Suppress("UNCHECKED_CAST")
                        val dataList = XposedHelpers.getObjectField(param.thisObject, "f") as? List<Any> ?: return
                        val item = dataList.getOrNull(position) ?: return

                        val title = try {
                            XposedHelpers.callMethod(item, "getTitle") as? String
                        } catch (_: Exception) { null }

                        if (title == JumpConstants.SETTING_ITEM_TITLE) {
                            itemView.setOnClickListener {
                                val context = itemView.context
                                if (context is Activity) {
                                    SettingsDialog.show(context)
                                }
                            }
                        } else {
                            itemView.setOnClickListener(null)
                            itemView.isClickable = false
                        }
                    } catch (e: Exception) {
                        ConfigManager.logError("设置项绑定拦截异常", e)
                    }
                }
            })
            ConfigManager.log("✔ 设置页点击拦截（Title 锚点 + 复用重置）已就绪: ${adapterClass.name}")
        } catch (e: Exception) {
            ConfigManager.logError("✘ 设置页点击拦截安装失败", e)
        }
    }

    private fun hookSettingsEntry(lpparam: XC_LoadPackage.LoadPackageParam) {
        val mainActivityClass = XposedHelpers.findClassIfExists("com.vgjump.jump.ui.main.MainActivity", lpparam.classLoader) ?: return
        try {
            XposedHelpers.findAndHookMethod(mainActivityClass, "onCreate",
                android.os.Bundle::class.java, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val activity = param.thisObject as Activity
                            ConfigManager.initAppContext(activity)
                            HookUtils.getCachedResId(activity, "myTab").takeIf { it != 0 }?.let { id ->
                                activity.findViewById<View>(id)?.setOnLongClickListener {
                                    SettingsDialog.show(activity)
                                    true
                                }
                            }
                        } catch (e: Exception) {
                            ConfigManager.logError("设置入口注入异常", e)
                        }
                    }
                }
            )
            ConfigManager.log("✔ 快捷入口（长按「我的」Tab）已安装")
        } catch (e: Exception) {
            ConfigManager.logError("✘ 快捷入口 Hook 失败", e)
        }
    }
}
