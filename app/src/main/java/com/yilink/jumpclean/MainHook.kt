package com.yilink.jumpclean

import com.yilink.jumpclean.hooks.JumpHooks
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

/**
 * JumpClean 现代 Xposed 模块入口
 * 基于 libxposed API 102
 */
class MainHook : XposedModule() {

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        val packageName = param.packageName
        val classLoader = param.classLoader

        // 绑定当前模块实例到 XposedCompat 工具桥
        XposedCompat.module = this

        // 1. 宿主拦截
        if (packageName == "com.vgjump.jump") {
            HookUtils.log("Target loaded: $packageName")
            JumpHooks.hook(classLoader)
            return
        }

        // 2. 模块自身激活自检：支持任何后缀的 jumpclean 包名（Debug/Release 通用）
        if (packageName.startsWith("com.yilink.jumpclean")) {
            try {
                val settingsClass = XposedCompat.findClassIfExists(
                    "com.yilink.jumpclean.SettingsActivity",
                    classLoader
                )
                if (settingsClass != null) {
                    val checkHookMarkerMethod = XposedCompat.findMethodExactIfExists(settingsClass, "checkHookMarker")
                    if (checkHookMarkerMethod != null) {
                        hook(checkHookMarkerMethod).intercept {
                            true
                        }
                    }
                } else {
                    HookUtils.log("激活自检失败: SettingsActivity 未找到 ($packageName)")
                }
            } catch (t: Throwable) {
                HookUtils.err("激活自检 Hook 异常: " + t.javaClass.simpleName + ": " + t.message, t)
            }
        }
    }
}
