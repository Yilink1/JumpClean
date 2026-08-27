package com.yilink.jumpclean

import com.yilink.jumpclean.ad.JumpAdHooks
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

class MainHook : IXposedHookLoadPackage {
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {

        // 1. 宿主拦截
        if (lpparam.packageName == "com.vgjump.jump") {
            HookUtils.log("Target loaded: ${lpparam.packageName}")
            JumpAdHooks.hook(lpparam)
            return
        }

        // 2. 模块自身激活自检：支持任何后缀的 jumpclean 包名（Debug/Release 通用）
        if (lpparam.packageName.contains("jumpclean")) {
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
                }
            } catch (_: Throwable) {}
        }
    }
}