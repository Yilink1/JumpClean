package com.yilink.jumpclean.hooks

import android.app.Activity
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.SparseArray
import com.yilink.jumpclean.config.ConfigManager
import com.yilink.jumpclean.config.JumpConstants
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.util.function.Function

object SplashHooks {

    private var processStartTime = 0L
    private var mainActivitySeen = false

    @Volatile
    var currentActivityName = ""
        private set

    fun hook(lpparam: XC_LoadPackage.LoadPackageParam) {
        processStartTime = SystemClock.uptimeMillis()
        hookSplashInstantJump(lpparam)
        hookByaztFastFail(lpparam)
        hookSplashAdBase(lpparam)
        hookStartupDelayCompress(lpparam)
        hookActivityFlowProbe()
        hookFakeNotificationPermission(lpparam)
        hookVoucherDialog(lpparam)
    }

    private fun hookVoucherDialog(lpparam: XC_LoadPackage.LoadPackageParam) {
        val targetClassNames = listOf(
            "com.vgjump.jump.ui.main.MainViewModel",
            "com.vgjump.jump.ui.main.g"
        )

        for (className in targetClassNames) {
            val vmClass = XposedHelpers.findClassIfExists(className, lpparam.classLoader) ?: continue
            var hooked = false
            vmClass.declaredMethods.forEach { method ->
                if (method.name.contains("VoucherDialog", ignoreCase = true)) {
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (ConfigManager.isFeatureEnabledSafe(lpparam.classLoader, JumpConstants.KEY_HIDE_VOUCHER_POPUP)) {
                                param.result = null
                                ConfigManager.log("✔ [源头阻断] 拦截营销弹窗请求: ${vmClass.simpleName}.${method.name}")
                            }
                        }
                    })
                    hooked = true
                }
            }
            if (hooked) {
                ConfigManager.log("✔ 屏蔽营销弹窗 Hook 已就绪 ($className)")
                return
            }
        }
    }

    private fun hookSplashInstantJump(lpparam: XC_LoadPackage.LoadPackageParam) {
        val splashClass = XposedHelpers.findClassIfExists(
            "com.vgjump.jump.ui.main.launch.SplashActivity", lpparam.classLoader
        ) ?: return

        XposedBridge.hookAllMethods(splashClass, "onCreate", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val activity = param.thisObject as? Activity ?: return
                ConfigManager.initAppContext(activity)
                val isRestartFromIcon = activity.intent?.getBooleanExtra(JumpConstants.EXTRA_ICON_RESTART, false) == true
                val isSkipEnabled = ConfigManager.isFeatureEnabledSafe(lpparam.classLoader, JumpConstants.KEY_SKIP_SPLASH)

                if (isRestartFromIcon || isSkipEnabled) {
                    try {
                        val mainIntent = Intent().apply {
                            setClassName(activity.packageName, "com.vgjump.jump.ui.main.MainActivity")
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        }
                        activity.startActivity(mainIntent)
                        activity.finish()
                        ConfigManager.log("✔ 瞬时穿透 SplashActivity 成功")
                    } catch (e: Exception) {
                        ConfigManager.logError("瞬跳 MainActivity 失败", e)
                    }
                }
            }
        })
        ConfigManager.log("✔ SplashActivity 穿透 Hook 已就绪")
    }

    private fun hookByaztFastFail(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val mgClass = XposedHelpers.findClassIfExists("com.byazt.oy.mg", lpparam.classLoader) ?: return
            XposedBridge.hookAllMethods(mgClass, "loadAdByType", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!ConfigManager.isFeatureEnabledSafe(lpparam.classLoader, JumpConstants.KEY_SKIP_SPLASH)) return
                    try {
                        val type = param.args.getOrNull(0) as? Int ?: return
                        if (type != 3) return

                        val callback = param.args.getOrNull(2)
                        var dispatched = false
                        if (callback != null) {
                            dispatched = dispatchByaztSplashLoadFail(callback)
                        }
                        if (dispatched || callback == null) {
                            param.result = null
                        }
                    } catch (e: Exception) {
                        ConfigManager.logError("Byazt 请求级拦截异常", e)
                    }
                }
            })
            ConfigManager.log("✔ Byazt 开屏请求快速阻断 Hook 已安装")
        } catch (e: Exception) {
            ConfigManager.logError("✘ Byazt 请求级 Hook 失败", e)
        }
    }

    private fun dispatchByaztSplashLoadFail(callback: Any): Boolean {
        return try {
            val errorFunction = Function<Any?, Any?> {
                SparseArray<Any>().apply {
                    put(JumpConstants.KEY_ERROR_CODE, -1)
                    put(JumpConstants.KEY_ERROR_MSG, "Splash ad blocked")
                }
            }
            val event = SparseArray<Any>(3).apply {
                put(0, errorFunction)
                put(JumpConstants.KEY_EVENT_CODE, JumpConstants.EVENT_LOAD_FAIL)
                put(JumpConstants.KEY_CLASS_TYPE, Void::class.java)
            }
            when (callback) {
                is Function<*, *> -> @Suppress("UNCHECKED_CAST") (callback as Function<Any, Any?>).apply(event)
                else -> XposedHelpers.callMethod(callback, "apply", event)
            }
            true
        } catch (e: Exception) {
            ConfigManager.logError("Byazt 失败回调派发异常", e)
            false
        }
    }

    private fun hookSplashAdBase(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val splashBase = XposedHelpers.findClassIfExists("com.byazt.se.a", lpparam.classLoader) ?: return

            XposedBridge.hookAllMethods(splashBase, "apply", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!ConfigManager.isFeatureEnabledSafe(lpparam.classLoader, JumpConstants.KEY_SKIP_SPLASH)) return
                    try {
                        val sparseArray = param.args[0] as? SparseArray<*> ?: return
                        val pluginValueSet = XposedHelpers.callStaticMethod(
                            XposedHelpers.findClass("com.byazt.evl.vf", lpparam.classLoader),
                            "vf", sparseArray
                        )
                        val valueSet = XposedHelpers.callMethod(pluginValueSet, "a")
                        val cmdCode = XposedHelpers.callMethod(valueSet, "intValue", -99999987) as Int

                        if (cmdCode == 110108 || cmdCode == 110109) {
                            param.result = null
                            try {
                                XposedHelpers.callMethod(param.thisObject, "hideSkipButton")
                            } catch (_: Exception) {}
                        }
                    } catch (_: Exception) {}
                }
            })
            ConfigManager.log("✔ Byazt 开屏渲染基类 Hook 已安装")
        } catch (e: Exception) {
            ConfigManager.logError("✘ Byazt 渲染基类 Hook 失败", e)
        }
    }

    private fun hookStartupDelayCompress(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            XposedHelpers.findAndHookMethod(
                Handler::class.java,
                "postDelayed",
                Runnable::class.java,
                Long::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!ConfigManager.isFeatureEnabledSafe(lpparam.classLoader, JumpConstants.KEY_SKIP_SPLASH)) return
                        try {
                            val delay = param.args[1] as? Long ?: return
                            val sinceStart = if (processStartTime > 0L)
                                SystemClock.uptimeMillis() - processStartTime else -1L

                            if (sinceStart in 0L..JumpConstants.STARTUP_WINDOW_MS
                                && !mainActivitySeen
                                && delay >= JumpConstants.MIN_DELAY_TO_COMPRESS
                                && Looper.myLooper() == Looper.getMainLooper()
                            ) {
                                param.args[1] = JumpConstants.COMPRESSED_DELAY
                            }
                        } catch (e: Exception) {
                            ConfigManager.logError("延迟压缩异常", e)
                        }
                    }
                }
            )
            ConfigManager.log("✔ 启动延迟瞬时压缩 Hook 已安装")
        } catch (e: Exception) {
            ConfigManager.logError("✘ 启动延迟压缩 Hook 失败", e)
        }
    }

    private fun hookActivityFlowProbe() {
        try {
            XposedBridge.hookAllMethods(Activity::class.java, "onCreate", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val act = param.thisObject as? Activity ?: return
                        ConfigManager.initAppContext(act)
                        val name = act.javaClass.name
                        if (name.contains("MainActivity")) {
                            mainActivitySeen = true
                        }
                    } catch (_: Exception) {}
                }
            })
            XposedBridge.hookAllMethods(Activity::class.java, "onResume", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val act = param.thisObject as? Activity ?: return
                        ConfigManager.initAppContext(act)
                        currentActivityName = act.javaClass.name
                    } catch (_: Exception) {}
                }
            })
        } catch (e: Exception) {
            ConfigManager.logError("✘ Activity 生命周期探针失败", e)
        }
    }

    private fun hookFakeNotificationPermission(lpparam: XC_LoadPackage.LoadPackageParam) {
        val returnTrueHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (ConfigManager.isFeatureEnabledSafe(lpparam.classLoader, JumpConstants.KEY_HIDE_MSG_PUSH_GUIDE)) {
                    param.result = true
                }
            }
        }

        try {
            val compatClass = XposedHelpers.findClassIfExists("androidx.core.app.NotificationManagerCompat", lpparam.classLoader)
            if (compatClass != null) {
                XposedHelpers.findAndHookMethod(compatClass, "areNotificationsEnabled", returnTrueHook)
                ConfigManager.log("✔ NotificationManagerCompat 权限伪造 Hook 已安装")
            }
        } catch (e: Exception) {
            ConfigManager.logError("NotificationManagerCompat Hook 失败", e)
        }

        try {
            val nmClass = XposedHelpers.findClassIfExists("android.app.NotificationManager", lpparam.classLoader)
            if (nmClass != null) {
                XposedHelpers.findAndHookMethod(nmClass, "areNotificationsEnabled", returnTrueHook)
                ConfigManager.log("✔ 原生 NotificationManager 权限伪造 Hook 已安装")
            }
        } catch (e: Exception) {
            ConfigManager.logError("原生 NotificationManager Hook 失败", e)
        }
    }
}
