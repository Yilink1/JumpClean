package com.yilink.jumpclean

import com.yilink.jumpclean.ad.JumpAdHooks
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

class MainHook : IXposedHookLoadPackage {
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {

        // 1. 自身激活自检：精确匹配无参私有方法 isActivated()
        if (lpparam.packageName == "com.yilink.jumpclean") {
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
                    HookUtils.err("激活自检失败：未找到 SettingsActivity 类")
                }
            } catch (e: Throwable) {
                HookUtils.err("激活自检 Hook 失败", e)
            }
            return
        }

        // 2. 宿主拦截
        if (lpparam.packageName == "com.vgjump.jump") {
            HookUtils.log("Target loaded: ${lpparam.packageName}")
            JumpAdHooks.hook(lpparam)
        }
    }
}