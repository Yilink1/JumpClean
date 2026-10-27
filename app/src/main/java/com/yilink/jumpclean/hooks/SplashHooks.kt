package com.yilink.jumpclean.hooks

import com.yilink.jumpclean.XposedCompat
import com.yilink.jumpclean.XposedCompat.MethodHookParam

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.SparseArray
import com.yilink.jumpclean.config.ConfigManager
import com.yilink.jumpclean.config.JumpConstants
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Function

object SplashHooks {

    private var processStartTime = 0L
    private var mainActivitySeen = false
    private val voucherHooked = AtomicBoolean(false)
    private val dialogFragmentHooked = AtomicBoolean(false)

    @Volatile
    var currentActivityName = ""
        private set

    fun hook(classLoader: ClassLoader) {
        processStartTime = SystemClock.uptimeMillis()
        hookSplashInstantJump(classLoader)
        hookByaztFastFail(classLoader)
        hookSplashAdBase(classLoader)
        hookStartupDelayCompress(classLoader)
        hookActivityFlowProbe()
        hookFakeNotificationPermission(classLoader)
        ensureVoucherDialogHooked(classLoader)
        hookDialogFragmentShow(classLoader)
        hookNotificationAd(classLoader)
    }

    private fun ensureVoucherDialogHooked(classLoader: ClassLoader) {
        if (voucherHooked.get()) return

        val targetClassNames = listOf(
            "com.vgjump.jump.ui.main.MainViewModel",
            "com.vgjump.jump.ui.main.g"
        )

        var success = false
        for (className in targetClassNames) {
            val vmClass = XposedCompat.findClassIfExists(className, classLoader) ?: continue
            var hookedCount = 0

            vmClass.declaredMethods.forEach { method ->
                if (java.lang.reflect.Modifier.isAbstract(method.modifiers)) return@forEach
                if (method.name.contains("Voucher", ignoreCase = true) ||
                    method.name.contains("DialogData", ignoreCase = true)) {
                    try {
                        XposedCompat.hookMethod(method, object : XposedCompat.MethodHookCallback() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                if (ConfigManager.isFeatureEnabledSafe(classLoader, JumpConstants.KEY_HIDE_VOUCHER_POPUP)) {
                                    param.result = null
                                    ConfigManager.log("✔ [弹窗阻断] 拦截营销弹窗请求: ${vmClass.simpleName}.${method.name}")
                                }
                            }
                        })
                        hookedCount++
                    } catch (e: Throwable) {
                        ConfigManager.log("Hook ${method.name} 异常: ${e.message}")
                    }
                }
            }

            // 拦截协程类 MainViewModel$getVoucherDialogData$1
            val coroutineClassName = "$className\$getVoucherDialogData\$1"
            val coroutineClass = XposedCompat.findClassIfExists(coroutineClassName, classLoader)
            if (coroutineClass != null) {
                try {
                    XposedCompat.hookAllMethods(coroutineClass, "invokeSuspend", object : XposedCompat.MethodHookCallback() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (ConfigManager.isFeatureEnabledSafe(classLoader, JumpConstants.KEY_HIDE_VOUCHER_POPUP)) {
                                param.result = Unit
                                ConfigManager.log("✔ [弹窗阻断] 拦截营销弹窗协程: $coroutineClassName")
                            }
                        }
                    })
                    hookedCount++
                } catch (_: Throwable) {}
            }

            if (hookedCount > 0) {
                success = true
                ConfigManager.log("[Hook] 营销弹窗屏蔽挂载就绪 ($className, $hookedCount 个挂载点)")
            }
        }

        if (success) {
            voucherHooked.set(true)
        }
    }

    private fun hookDialogFragmentShow(classLoader: ClassLoader) {
        if (dialogFragmentHooked.getAndSet(true)) return
        try {
            val dfClass = XposedCompat.findClassIfExists("androidx.fragment.app.DialogFragment", classLoader) ?: return
            val dismissHook = object : XposedCompat.MethodHookCallback() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val df = param.thisObject ?: return
                    val className = df.javaClass.name
                    val tag = param.args.lastOrNull() as? String ?: ""

                    // 仅精准拦截发现页与主界面的活动营销弹窗，坚决不误伤商城选券或普通功能弹窗
                    val isMarketingDialog = className.contains("JumpNice", ignoreCase = true) ||
                            className.contains("MemberCouponDialog", ignoreCase = true) ||
                            tag == "dialog_activity" ||
                            tag == "jump_nice_guide_dialog"

                    if (isMarketingDialog && ConfigManager.isFeatureEnabledSafe(classLoader, JumpConstants.KEY_HIDE_VOUCHER_POPUP)) {
                        param.result = null
                        ConfigManager.log("✔ [弹窗阻断] 拦截营销 DialogFragment: $className (tag=$tag)")
                    }
                }
            }
            XposedCompat.hookAllMethods(dfClass, "show", dismissHook)
            XposedCompat.hookAllMethods(dfClass, "showNow", dismissHook)
            ConfigManager.log("[Hook] DialogFragment 营销弹窗展示层拦截就绪")
        } catch (e: Throwable) {
            ConfigManager.log("Hook DialogFragment.show 异常: ${e.message}")
        }
    }

    private fun hookSplashInstantJump(classLoader: ClassLoader) {
        val splashClass = XposedCompat.findClassIfExists(
            "com.vgjump.jump.ui.main.launch.SplashActivity", classLoader
        ) ?: return

        val jumpAction: (Activity) -> Unit = { activity ->
            ConfigManager.initAppContext(activity)
            processStartTime = SystemClock.uptimeMillis()
            mainActivitySeen = false
            val isRestartFromIcon = activity.intent?.getBooleanExtra(JumpConstants.EXTRA_ICON_RESTART, false) == true
            val isSkipEnabled = ConfigManager.isFeatureEnabledSafe(classLoader, JumpConstants.KEY_SKIP_SPLASH)

            if ((isRestartFromIcon || isSkipEnabled) && !activity.isFinishing) {
                try {
                    val mainIntent = Intent().apply {
                        setClassName(activity.packageName, "com.vgjump.jump.ui.main.MainActivity")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    }
                    activity.startActivity(mainIntent)
                    activity.finish()
                    ConfigManager.log("✔ [开屏穿透] 瞬时穿透 SplashActivity 成功 (热/冷启动加速)")
                } catch (e: Exception) {
                    ConfigManager.logError("瞬跳 MainActivity 失败", e)
                }
            }
        }

        XposedCompat.hookAllMethods(splashClass, "onCreate", object : XposedCompat.MethodHookCallback() {
            override fun afterHookedMethod(param: MethodHookParam) {
                (param.thisObject as? Activity)?.let { jumpAction(it) }
            }
        })
        XposedCompat.hookAllMethods(splashClass, "onResume", object : XposedCompat.MethodHookCallback() {
            override fun afterHookedMethod(param: MethodHookParam) {
                (param.thisObject as? Activity)?.let { jumpAction(it) }
            }
        })
        ConfigManager.log("[Hook] SplashActivity 穿透挂载完成")
    }

    private fun hookByaztFastFail(classLoader: ClassLoader) {
        try {
            val mgClass = XposedCompat.findClassIfExists("com.byazt.oy.mg", classLoader) ?: return
            XposedCompat.hookAllMethods(mgClass, "loadAdByType", object : XposedCompat.MethodHookCallback() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!ConfigManager.isFeatureEnabledSafe(classLoader, JumpConstants.KEY_SKIP_SPLASH)) return
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
            ConfigManager.log("[Hook] Byazt 开屏阻断挂载完成")
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
                else -> XposedCompat.callMethod(callback, "apply", event)
            }
            true
        } catch (e: Exception) {
            ConfigManager.logError("Byazt 失败回调派发异常", e)
            false
        }
    }

    private fun hookSplashAdBase(classLoader: ClassLoader) {
        try {
            val splashBase = XposedCompat.findClassIfExists("com.byazt.se.a", classLoader) ?: return

            XposedCompat.hookAllMethods(splashBase, "apply", object : XposedCompat.MethodHookCallback() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!ConfigManager.isFeatureEnabledSafe(classLoader, JumpConstants.KEY_SKIP_SPLASH)) return
                    try {
                        val sparseArray = param.args[0] as? SparseArray<*> ?: return
                        val pluginValueSet = XposedCompat.callStaticMethod(
                            XposedCompat.findClass("com.byazt.evl.vf", classLoader),
                            "vf", sparseArray
                        )
                        val valueSet = XposedCompat.callMethod(pluginValueSet, "a")
                        val cmdCode = XposedCompat.callMethod(valueSet, "intValue", -99999987) as Int

                        if (cmdCode == 110108 || cmdCode == 110109) {
                            param.result = null
                            try {
                                XposedCompat.callMethod(param.thisObject, "hideSkipButton")
                            } catch (_: Exception) {}
                        }
                    } catch (_: Exception) {}
                }
            })
            ConfigManager.log("[Hook] Byazt 渲染基类挂载完成")
        } catch (e: Exception) {
            ConfigManager.logError("✘ Byazt 渲染基类 Hook 失败", e)
        }
    }

    private fun hookStartupDelayCompress(classLoader: ClassLoader) {
        try {
            XposedCompat.findAndHookMethod(
                Handler::class.java,
                "postDelayed",
                Runnable::class.java,
                Long::class.javaPrimitiveType,
                object : XposedCompat.MethodHookCallback() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!ConfigManager.isFeatureEnabledSafe(classLoader, JumpConstants.KEY_SKIP_SPLASH)) return
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
            ConfigManager.log("[Hook] 启动延迟瞬时压缩已就绪")
        } catch (e: Exception) {
            ConfigManager.logError("✘ 启动延迟压缩 Hook 失败", e)
        }
    }

    private fun hookActivityFlowProbe() {
        try {
            XposedCompat.hookAllMethods(Application::class.java, "onCreate", object : XposedCompat.MethodHookCallback() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val app = param.thisObject as? Application ?: return
                        ConfigManager.initAppContext(app)
                        ensureVoucherDialogHooked(app.classLoader)
                        hookDialogFragmentShow(app.classLoader)
                    } catch (_: Throwable) {}
                }
            })
            XposedCompat.hookAllMethods(Activity::class.java, "onCreate", object : XposedCompat.MethodHookCallback() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val act = param.thisObject as? Activity ?: return
                        ConfigManager.initAppContext(act)
                        val name = act.javaClass.name
                        if (name.contains("MainActivity")) {
                            mainActivitySeen = true
                            ensureVoucherDialogHooked(act.classLoader)
                            hookDialogFragmentShow(act.classLoader)
                        } else if (name.contains("SplashActivity")) {
                            mainActivitySeen = false
                            processStartTime = SystemClock.uptimeMillis()
                        }
                    } catch (_: Exception) {}
                }
            })
            XposedCompat.hookAllMethods(Activity::class.java, "onResume", object : XposedCompat.MethodHookCallback() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val act = param.thisObject as? Activity ?: return
                        ConfigManager.initAppContext(act)
                        currentActivityName = act.javaClass.name
                        if (act.javaClass.name.contains("MainActivity")) {
                            ensureVoucherDialogHooked(act.classLoader)
                            hookDialogFragmentShow(act.classLoader)
                        }
                    } catch (_: Exception) {}
                }
            })
            XposedCompat.hookAllMethods(Activity::class.java, "onDestroy", object : XposedCompat.MethodHookCallback() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val act = param.thisObject as? Activity ?: return
                        val name = act.javaClass.name
                        if (name.contains("MainActivity")) {
                            mainActivitySeen = false
                            processStartTime = SystemClock.uptimeMillis()
                            ConfigManager.log("[Probe] MainActivity 退出销毁，重置启动加速探针")
                        }
                    } catch (_: Exception) {}
                }
            })
        } catch (e: Exception) {
            ConfigManager.logError("✘ Activity 生命周期探针失败", e)
        }
    }

    private fun hookFakeNotificationPermission(classLoader: ClassLoader) {
        val returnTrueHook = object : XposedCompat.MethodHookCallback() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (ConfigManager.isFeatureEnabledSafe(classLoader, JumpConstants.KEY_HIDE_MSG_PUSH_GUIDE)) {
                    param.result = true
                }
            }
        }

        try {
            val compatClass = XposedCompat.findClassIfExists("androidx.core.app.NotificationManagerCompat", classLoader)
            if (compatClass != null) {
                XposedCompat.findAndHookMethod(compatClass, "areNotificationsEnabled", returnTrueHook)
                ConfigManager.log("[Hook] NotificationManagerCompat 权限伪造已就绪")
            }
        } catch (e: Exception) {
            ConfigManager.logError("NotificationManagerCompat Hook 失败", e)
        }

        try {
            val nmClass = XposedCompat.findClassIfExists("android.app.NotificationManager", classLoader)
            if (nmClass != null) {
                XposedCompat.findAndHookMethod(nmClass, "areNotificationsEnabled", returnTrueHook)
                ConfigManager.log("[Hook] 原生 NotificationManager 权限伪造已就绪")
            }
        } catch (e: Exception) {
            ConfigManager.logError("原生 NotificationManager Hook 失败", e)
        }
    }

    private fun hookNotificationAd(classLoader: ClassLoader) {

        // 核心架构层拦截：GlobalViewModel.onReceiveMessage (阻止前台消息处理与震动横幅派发)
        try {
            val globalVmClass = XposedCompat.findClassIfExists(
                "com.vgjump.jump.ui.main.func.GlobalViewModel", classLoader
            ) ?: return

            globalVmClass.declaredMethods.forEach { method ->
                if (method.name == "onReceiveMessage") {
                    XposedCompat.hookMethod(method, object : XposedCompat.MethodHookCallback() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (ConfigManager.isFeatureEnabledSafe(classLoader, JumpConstants.KEY_BLOCK_NOTIFICATION_AD)) {
                                param.result = null
                                ConfigManager.log("✔ [通知阻断] 拦截应用内突袭推送与震动横幅 (GlobalViewModel.onReceiveMessage)")
                            }
                        }
                    })
                }
            }
            ConfigManager.log("[Hook] GlobalViewModel 应用内通知挂载就绪")
        } catch (e: Throwable) {
            ConfigManager.logError("Hook GlobalViewModel.onReceiveMessage 异常", e)
        }
    }
}
