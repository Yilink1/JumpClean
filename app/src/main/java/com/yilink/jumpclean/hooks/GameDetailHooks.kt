package com.yilink.jumpclean.hooks

import com.yilink.jumpclean.XposedCompat
import com.yilink.jumpclean.XposedCompat.MethodHookParam

import android.app.Activity
import android.os.SystemClock
import android.view.View
import com.yilink.jumpclean.HookUtils
import com.yilink.jumpclean.config.ConfigManager
import com.yilink.jumpclean.config.JumpConstants

/** 游戏详情页净化：UI 层隐藏 + 数据层过滤断电 */
object GameDetailHooks {

    private var lastGameDetailLayoutTime = 0L
    private val layoutHookedActivities = java.util.Collections.newSetFromMap(java.util.WeakHashMap<Activity, Boolean>())

    fun hook(classLoader: ClassLoader) {
        hookGameDetailData(classLoader)
        hookGameDetailUI(classLoader)
    }

    private fun hookGameDetailData(classLoader: ClassLoader) {
        try {
            val candidateVmFindAdClasses = listOf(
                "com.vgjump.jump.ui.game.detail.home.GameDetailHomeViewModel",
                "com.vgjump.jump.ui.game.detail.GameDetailHomeViewModel"
            )
            for (className in candidateVmFindAdClasses) {
                val homeVmClass = XposedCompat.findClassIfExists(className, classLoader) ?: continue
                homeVmClass.declaredMethods.forEach { method ->
                    if (method.name.contains("findad", ignoreCase = true)) {
                        XposedCompat.hookMethod(method, object : XposedCompat.MethodHookCallback() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                if (ConfigManager.isFeatureEnabledSafe(classLoader, JumpConstants.KEY_HIDE_GAME_FIND_AD)) {
                                    param.result = null
                                }
                            }
                        })
                    }
                }
                ConfigManager.log("[Hook] 详情页协程断电已挂载: $className")
            }

            // 2. 动态购买弹幕总电闸：OrderRecentlyKt.shouldShowOrderRecentlyBanner 强制返回 false
            val candidateOrderRecentlyClasses = listOf(
                "com.vgjump.jump.ui.game.detail.OrderRecentlyKt",
                "com.vgjump.jump.ui.game.detail.home.OrderRecentlyKt",
                "com.vgjump.jump.ui.game.OrderRecentlyKt"
            )
            for (className in candidateOrderRecentlyClasses) {
                val orderClazz = XposedCompat.findClassIfExists(className, classLoader) ?: continue
                XposedCompat.hookAllMethods(orderClazz, "shouldShowOrderRecentlyBanner", object : XposedCompat.MethodHookCallback() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (ConfigManager.isFeatureEnabledSafe(classLoader, JumpConstants.KEY_HIDE_GAME_DYNAMIC_BUBBLE)) {
                            param.result = false
                        }
                    }
                })
                XposedCompat.hookAllMethods(orderClazz, "toOrderRecentlyBannerItems", object : XposedCompat.MethodHookCallback() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (ConfigManager.isFeatureEnabledSafe(classLoader, JumpConstants.KEY_HIDE_GAME_DYNAMIC_BUBBLE)) {
                            param.result = emptyList<Any>()
                        }
                    }
                })
                ConfigManager.log("[Hook] 订单弹幕断电已挂载: $className")
            }

            // 3. GameDetailViewModel 订单轮播协程入口断电 (仅匹配包含 getOrderSlideShow 的安全全名方法)
            val candidateVmClasses = listOf(
                "com.vgjump.jump.ui.game.detail.GameDetailViewModel",
                "com.vgjump.jump.ui.game.detail.home.GameDetailViewModel"
            )
            for (className in candidateVmClasses) {
                val vmClazz = XposedCompat.findClassIfExists(className, classLoader) ?: continue
                vmClazz.declaredMethods.forEach { method ->
                    if (method.name.contains("getOrderSlideShow", ignoreCase = true)) {
                        XposedCompat.hookMethod(method, object : XposedCompat.MethodHookCallback() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                if (ConfigManager.isFeatureEnabledSafe(classLoader, JumpConstants.KEY_HIDE_GAME_DYNAMIC_BUBBLE)) {
                                    param.result = null
                                }
                            }
                        })
                    }
                }
            }
        } catch (e: Throwable) {
            ConfigManager.logError("✘ 游戏折扣页数据层断电 Hook 异常", e)
        }
    }

    private fun hookGameDetailUI(classLoader: ClassLoader) {
        val targetClass = "com.vgjump.jump.ui.game.detail.GameDetailActivity"
        val clazz = XposedCompat.findClassIfExists(targetClass, classLoader) ?: return

        try {
            val lifecycleHook = object : XposedCompat.MethodHookCallback() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val activity = param.thisObject as? Activity ?: return
                    if (activity.javaClass.name.contains("GameDetailActivity")) {
                        ConfigManager.initAppContext(activity)
                        applyGameDetailClean(activity)
                        attachGameDetailLayoutListener(activity)
                    }
                }
            }

            var c: Class<*>? = clazz
            while (c != null && c != Activity::class.java) {
                XposedCompat.hookAllMethods(c, "onResume", lifecycleHook)
                XposedCompat.hookAllMethods(c, "onCreate", lifecycleHook)
                c = c.superclass
            }
            ConfigManager.log("[Hook] 游戏折扣页 UI 净化已就绪")
        } catch (e: Exception) {
            ConfigManager.logError("✘ 游戏折扣页 UI Hook 失败", e)
        }
    }

    private fun attachGameDetailLayoutListener(activity: Activity) {
        if (!layoutHookedActivities.add(activity)) return
        val decor = activity.window?.decorView ?: return
        decor.viewTreeObserver?.let { vto ->
            if (vto.isAlive) {
                vto.addOnGlobalLayoutListener {
                    val now = SystemClock.uptimeMillis()
                    if (now - lastGameDetailLayoutTime >= JumpConstants.THROTTLE_INTERVAL_MS) {
                        lastGameDetailLayoutTime = now
                        applyGameDetailClean(activity)
                    }
                }
            }
        }
        listOf(200L, 500L, 1000L).forEach { delay ->
            decor.postDelayed({ applyGameDetailClean(activity) }, delay)
        }
    }

    fun applyGameDetailClean(activity: Activity) {
        try {
            val hideMemberAd = ConfigManager.isFeatureEnabled(activity, JumpConstants.KEY_HIDE_GAME_MEMBER_GUIDE)
            val hideFindAd = ConfigManager.isFeatureEnabled(activity, JumpConstants.KEY_HIDE_GAME_FIND_AD)

            // 1. 会员广告：开通引导条 (clMemberGuide) - 静态控件，仅折叠自身
            val memberGuideId = HookUtils.getCachedResId(activity, "clMemberGuide")
            if (memberGuideId != 0) {
                activity.findViewById<View>(memberGuideId)?.let { v ->
                    if (hideMemberAd) {
                        HookUtils.collapseView(v)
                    } else if (HookUtils.isCollapsed(v)) {
                        HookUtils.restoreView(v)
                    }
                }
            }

            // 2. 会员广告：ONLINE 会员/专属标识 (ivExtra) 与促销横幅 (ivFindAD) - 递归扫描整个视图树精准折叠
            val extraId = HookUtils.getCachedResId(activity, "ivExtra")
            val findAdId = HookUtils.getCachedResId(activity, "ivFindAD")
            val decor = activity.window?.decorView
            if (decor != null) {
                val activeIds = HashSet<Int>()
                val restoreIds = HashSet<Int>()
                if (extraId != 0) {
                    if (hideMemberAd) activeIds.add(extraId)
                    else if (HookUtils.hasCollapsedViews()) restoreIds.add(extraId)
                }
                if (findAdId != 0) {
                    if (hideFindAd) activeIds.add(findAdId)
                    else if (HookUtils.hasCollapsedViews()) restoreIds.add(findAdId)
                }
                if (activeIds.isNotEmpty() || restoreIds.isNotEmpty()) {
                    HookUtils.applyTargetViewsVisibility(decor, activeIds, restoreIds)
                }
            }
        } catch (e: Exception) {
            ConfigManager.logError("游戏折扣页净化执行异常", e)
        }
    }
}
