package com.yilink.jumpclean

import com.yilink.jumpclean.hooks.JumpHooks
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

class MainHook : IXposedHookLoadPackage {
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {

        // 1. 宿主拦截
        if (lpparam.packageName == "com.vgjump.jump") {
            // 仅在主进程加载界面净化与功能逻辑，跳过 :pushcore 与 :marsservice 等无 UI 子进程
            if (lpparam.processName != "com.vgjump.jump") {
                return
            }
            HookUtils.log("Target loaded: ${lpparam.packageName}")
            JumpHooks.hook(lpparam)
            return
        }

                // 2. 模块自身激活自检：支持任何后缀的 jumpclean 包名（Debug/Release 通用）
        if (lpparam.packageName.startsWith("com.yilink.jumpclean")) {
            try {
                val settingsClass = XposedHelpers.findClassIfExists(
                    "com.yilink.jumpclean.SettingsActivity",
                    lpparam.classLoader
                )
                if (settingsClass != null) {
                    XposedHelpers.findAndHookMethod(
                        settingsClass,
                        "isActivated",
                        XC_MethodReplacement.returnConstant(true)
                    )
                } else {
                    // 类找不到时静默跳过会让模块「实际已激活但设置页显示未激活」，必须留痕
                    XposedBridge.log("[JumpClean] 激活自检失败: SettingsActivity 未找到 (" + lpparam.packageName + ")")
                }
            } catch (t: Throwable) {
                XposedBridge.log("[JumpClean] 激活自检 Hook 异常: " + t.javaClass.simpleName + ": " + t.message)
            }
        }
    }
}