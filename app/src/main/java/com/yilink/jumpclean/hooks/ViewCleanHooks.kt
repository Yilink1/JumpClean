package com.yilink.jumpclean.hooks

import com.yilink.jumpclean.XposedCompat
import com.yilink.jumpclean.XposedCompat.MethodHookParam

import android.app.Activity
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import com.yilink.jumpclean.HookUtils
import com.yilink.jumpclean.config.ConfigManager
import com.yilink.jumpclean.config.JumpConstants
import java.util.Collections
import java.util.WeakHashMap

object ViewCleanHooks {

    private var lastMainLayoutTime = 0L
    private var lastDetailLayoutTime = 0L
    private var lastGameDetailLayoutTime = 0L

    @Volatile private var cachedRedDotId = 0
    @Volatile private var cachedLotteryTabId = 0
    @Volatile private var cachedWebTabId = 0
    @Volatile private var lotteryTabWasHidden = false

    fun hook(classLoader: ClassLoader) {
        hookMainActivityUI(classLoader)
        hookTabVisibilityIntercept(classLoader)
        hookAdViews(classLoader)
        hookContentDetailMemberMask(classLoader)
        hookGameDetailClean(classLoader)
        hookOverviewSmoothing(classLoader)
    }

    private fun hookTabVisibilityIntercept(classLoader: ClassLoader) {
        try {
            XposedCompat.findAndHookMethod(
                View::class.java,
                "setVisibility",
                Int::class.javaPrimitiveType,
                object : XposedCompat.MethodHookCallback() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val view = param.thisObject as? View ?: return
                        val id = view.id
                        if (id <= 0) return

                        // 开发者「底栏变动追踪」：谁在显隐 Jump 赏 / 抽奖 / 红点，打印宿主侧调用栈
                        if ((id == cachedRedDotId || id == cachedLotteryTabId || id == cachedWebTabId) &&
                            GlassStyle.devTrace()
                        ) {
                            LiquidGlassHooks.traceTabChange(view, "setVisibility=" + param.args[0])
                        }

                        if (cachedRedDotId != 0 && id == cachedRedDotId) {
                            if (ConfigManager.isFeatureEnabledSafe(classLoader, JumpConstants.KEY_HIDE_LOTTERY_TAB)) {
                                param.args[0] = View.GONE
                            }
                        } else if (cachedLotteryTabId != 0 && id == cachedLotteryTabId) {
                            if (ConfigManager.isFeatureEnabledSafe(classLoader, JumpConstants.KEY_HIDE_LOTTERY_TAB)) {
                                param.args[0] = View.GONE
                            }
                        } else if (cachedWebTabId != 0 && id == cachedWebTabId) {
                            if (ConfigManager.isFeatureEnabledSafe(classLoader, JumpConstants.KEY_HIDE_WEB_TAB)) {
                                param.args[0] = View.GONE
                            }
                        }
                    }
                }
            )
            ConfigManager.log("[Hook] 底栏与红点净化已就绪")
        } catch (e: Exception) {
            ConfigManager.logError("✘ 底栏与红点 setVisibility 阻断 Hook 失败", e)
        }
    }

    private fun hookMainActivityUI(classLoader: ClassLoader) {
        val mainActivityClass = XposedCompat.findClassIfExists("com.vgjump.jump.ui.main.MainActivity", classLoader) ?: return
        try {
            XposedCompat.findAndHookMethod(mainActivityClass, "onCreate",
                android.os.Bundle::class.java, object : XposedCompat.MethodHookCallback() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val activity = param.thisObject as Activity
                            ConfigManager.initAppContext(activity)
                            cachedRedDotId = HookUtils.getCachedResId(activity, "vRedDot")
                            cachedLotteryTabId = HookUtils.getCachedResId(activity, "lotteryTab")
                            cachedWebTabId = HookUtils.getCachedResId(activity, "webTab")
                            applyAllUIVisibility(activity)

                            activity.window?.decorView?.rootView?.viewTreeObserver
                                ?.addOnGlobalLayoutListener {
                                    val now = SystemClock.uptimeMillis()
                                    if (now - lastMainLayoutTime >= JumpConstants.THROTTLE_INTERVAL_MS) {
                                        lastMainLayoutTime = now
                                        applyAllUIVisibility(activity)
                                    }
                                }
                        } catch (e: Exception) {
                            ConfigManager.logError("MainActivity onCreate UI 净化异常", e)
                        }
                    }
                }
            )
            ConfigManager.log("[Hook] 首页 UI 净化已就绪")
        } catch (e: Exception) {
            ConfigManager.logError("✘ 首页 UI 净化 Hook 失败", e)
        }
    }

    fun applyAllUIVisibility(activity: Activity) {
        try {
            if (activity.javaClass.name.contains("GameDetailActivity")) {
                applyGameDetailClean(activity)
                return
            }
            if (cachedRedDotId == 0) cachedRedDotId = HookUtils.getCachedResId(activity, "vRedDot")
            if (cachedLotteryTabId == 0) cachedLotteryTabId = HookUtils.getCachedResId(activity, "lotteryTab")
            if (cachedWebTabId == 0) cachedWebTabId = HookUtils.getCachedResId(activity, "webTab")

            mapOf(
                "webTab" to JumpConstants.KEY_HIDE_WEB_TAB,
                "lotteryTab" to JumpConstants.KEY_HIDE_LOTTERY_TAB
            ).forEach { (idName, prefKey) ->
                val isEnabled = ConfigManager.isFeatureEnabled(activity, prefKey)
                HookUtils.getCachedResId(activity, idName).takeIf { it != 0 }?.let { id ->
                    val tabView = activity.findViewById<View>(id) ?: return@let
                    if (isEnabled) {
                        if (tabView.visibility != View.GONE) {
                            tabView.visibility = View.GONE
                        }
                    } else {
                        if (tabView.visibility != View.VISIBLE) {
                            tabView.visibility = View.VISIBLE
                        }
                    }
                }
            }

            val lotteryHidden = ConfigManager.isFeatureEnabled(activity, JumpConstants.KEY_HIDE_LOTTERY_TAB)
            if (lotteryHidden) {
                lotteryTabWasHidden = true
                HookUtils.getCachedResId(activity, "vRedDot").takeIf { it != 0 }?.let { id ->
                    activity.findViewById<View>(id)?.let { redDot ->
                        if (redDot.visibility != View.GONE) redDot.visibility = View.GONE
                    }
                }
            } else {
                if (lotteryTabWasHidden) {
                    lotteryTabWasHidden = false
                    // 仅在用户从设置里取消隐藏的这一瞬间，如果原来有红点则单次恢复，后续绝不轮询干预
                    HookUtils.getCachedResId(activity, "vRedDot").takeIf { it != 0 }?.let { id ->
                        activity.findViewById<View>(id)?.let { redDot ->
                            if (redDot.visibility != View.VISIBLE) redDot.visibility = View.VISIBLE
                        }
                    }
                }
            }

            val targets = mapOf(
                "rvOpt" to JumpConstants.KEY_HIDE_TOPIC_LIST,
                "ivPublishTopic" to JumpConstants.KEY_HIDE_PUBLISH_TOPIC,
                "clPhotoWall" to JumpConstants.KEY_HIDE_PHOTO_WALL,
                "vColorRVTop" to JumpConstants.KEY_HIDE_MEMBER_CARD,
                "vBlackRVTop" to JumpConstants.KEY_HIDE_MEMBER_CARD,
                "tvMyOrder" to JumpConstants.KEY_HIDE_MY_ORDER,
                "tvMyOrderToolbar" to JumpConstants.KEY_HIDE_MY_ORDER,
                "rvOPT" to JumpConstants.KEY_HIDE_DISCOVER_TOP_AD,
                "adBanner" to JumpConstants.KEY_HIDE_DISCOVER_BANNER,
                "llDiscoverModules" to JumpConstants.KEY_HIDE_DISCOVER_MODULES,
                "moduleEveryoneBuying" to JumpConstants.KEY_HIDE_DISCOVER_MODULES,
                "ivTag" to JumpConstants.KEY_HIDE_WIDGET_VIP_TAG
            )

            val activeTargetIds = HashSet<Int>()
            val restoreTargetIds = HashSet<Int>()
            val hasAnyCollapsed = HookUtils.hasCollapsedViews()
            targets.forEach { (idName, prefKey) ->
                HookUtils.getCachedResId(activity, idName).takeIf { it != 0 }?.let { id ->
                    if (ConfigManager.isFeatureEnabled(activity, prefKey)) {
                        activeTargetIds.add(id)
                    } else if (hasAnyCollapsed) {
                        restoreTargetIds.add(id)
                    }
                }
            }

            if (activeTargetIds.isNotEmpty() || restoreTargetIds.isNotEmpty()) {
                activity.window?.decorView?.let { decorView ->
                    applyTargetViewsVisibility(decorView, activeTargetIds, restoreTargetIds)
                }
            }

            // 精准消除发现页探索栏顶部的 46px (12.27dp) 顽固白缝
            if (ConfigManager.isFeatureEnabled(activity, JumpConstants.KEY_HIDE_DISCOVER_BANNER) ||
                ConfigManager.isFeatureEnabled(activity, JumpConstants.KEY_HIDE_DISCOVER_TOP_AD) ||
                ConfigManager.isFeatureEnabled(activity, JumpConstants.KEY_HIDE_DISCOVER_MODULES)
            ) {
                val collapsingId = HookUtils.getCachedResId(activity, "collapsing_toolbar")
                if (collapsingId != 0) {
                    activity.findViewById<ViewGroup>(collapsingId)?.let { ctl ->
                        for (i in 0 until ctl.childCount) {
                            val child = ctl.getChildAt(i)
                            if (child != null && child.javaClass.name.contains("ConstraintLayout")) {
                                val lp = child.layoutParams
                                if (lp is ViewGroup.MarginLayoutParams && lp.topMargin > 0) {
                                    lp.topMargin = 0
                                    child.layoutParams = lp
                                    child.requestLayout()
                                }
                            }
                        }
                    }
                }
            }

            val memberCardEnabled = ConfigManager.isFeatureEnabled(activity, JumpConstants.KEY_HIDE_MEMBER_CARD)
            val buyBtnId = HookUtils.getCachedResId(activity, "tvBuy")
            if (buyBtnId != 0) {
                activity.findViewById<View>(buyBtnId)?.let { buyBtn ->
                    (buyBtn.parent as? View)?.let { memberContainer ->
                        if (memberCardEnabled) {
                            HookUtils.collapseView(memberContainer)
                        } else if (HookUtils.isCollapsed(memberContainer)) {
                            HookUtils.restoreView(memberContainer)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            ConfigManager.logError("应用界面净化规则异常", e)
        }
    }

    private fun applyTargetViewsVisibility(view: View, targetIds: Set<Int>, restoreIds: Set<Int>) {
        for (id in targetIds) {
            val targetView = view.findViewById<View>(id) ?: continue
            if (!HookUtils.isCollapsed(targetView)) {
                HookUtils.collapseView(targetView)
            }
        }
        for (id in restoreIds) {
            val targetView = view.findViewById<View>(id) ?: continue
            if (HookUtils.isCollapsed(targetView)) {
                HookUtils.restoreView(targetView)
            }
        }
    }

    private fun hookAdViews(classLoader: ClassLoader) {
        try {
            val nativeAdClass = XposedCompat.findClassIfExists("com.qq.e.ads.nativ.widget.NativeAdContainer", classLoader) ?: return
            val expressAdClass = XposedCompat.findClassIfExists("com.qq.e.ads.nativ.NativeExpressADView", classLoader)

            var lastNativeAdLogTime = 0L
            val collapseAdAction: (View) -> Unit = { view ->
                if (ConfigManager.isFeatureEnabled(view.context, JumpConstants.KEY_HIDE_POST_AD)) {
                    HookUtils.collapseView(view)
                    val now = SystemClock.uptimeMillis()
                    if (now - lastNativeAdLogTime > 1500L) {
                        lastNativeAdLogTime = now
                        ConfigManager.log("✔ [容器折叠] 腾讯 NativeAdContainer (推荐流/帖子内嵌)")
                    }
                } else if (HookUtils.isCollapsed(view)) {
                    HookUtils.restoreView(view)
                }
            }

            val hookAdClass = { targetCls: Class<*> ->
                XposedCompat.hookAllConstructors(targetCls, object : XposedCompat.MethodHookCallback() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val view = param.thisObject as? View ?: return
                            collapseAdAction(view)
                        } catch (e: Exception) {
                            ConfigManager.logError("${targetCls.simpleName} 构造拦截异常", e)
                        }
                    }
                })

                XposedCompat.hookAllMethods(targetCls, "setVisibility", object : XposedCompat.MethodHookCallback() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            val view = param.thisObject as? View ?: return
                            if (ConfigManager.isFeatureEnabled(view.context, JumpConstants.KEY_HIDE_POST_AD)) {
                                param.args[0] = View.GONE
                                collapseAdAction(view)
                            }
                        } catch (e: Exception) {
                            ConfigManager.logError("${targetCls.simpleName} setVisibility 拦截异常", e)
                        }
                    }
                })
            }

            hookAdClass(nativeAdClass)
            if (expressAdClass != null) {
                hookAdClass(expressAdClass)
            }

            XposedCompat.findAndHookMethod(View::class.java, "onAttachedToWindow", object : XposedCompat.MethodHookCallback() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val view = param.thisObject as? View ?: return
                        if (nativeAdClass.isInstance(view) || (expressAdClass != null && expressAdClass.isInstance(view))) {
                            collapseAdAction(view)
                        }
                    } catch (_: Exception) {}
                }
            })

            ConfigManager.log("[Hook] 商业广告通用容器 Hook 已就绪")
        } catch (e: Exception) {
            ConfigManager.logError("✘ 通用广告容器 Hook 失败", e)
        }
    }

    private fun hookContentDetailMemberMask(classLoader: ClassLoader) {
        val targetActivities = listOf(
            "com.vgjump.jump.ui.content.detail.ContentDetailActivity",
            "com.vgjump.jump.ui.content.detail.CommentDetailActivity"
        )

        val maskHook = object : XposedCompat.MethodHookCallback() {
            override fun afterHookedMethod(param: MethodHookParam) {
                try {
                    val activity = param.thisObject as? Activity ?: return
                    ConfigManager.initAppContext(activity)
                    applyContentDetailMemberMaskHide(activity)

                    activity.window?.decorView?.rootView?.viewTreeObserver
                        ?.addOnGlobalLayoutListener {
                            val now = SystemClock.uptimeMillis()
                            if (now - lastDetailLayoutTime >= JumpConstants.THROTTLE_INTERVAL_MS) {
                                lastDetailLayoutTime = now
                                if (ConfigManager.isFeatureEnabled(activity, JumpConstants.KEY_HIDE_CONTENT_MEMBER_MASK)) {
                                    applyContentDetailMemberMaskHide(activity)
                                }
                            }
                        }
                } catch (e: Exception) {
                    ConfigManager.logError("详情页遮罩初始化异常", e)
                }
            }
        }

        targetActivities.forEach { className ->
            val clazz = XposedCompat.findClassIfExists(className, classLoader) ?: return@forEach
            XposedCompat.hookAllMethods(clazz, "onCreate", maskHook)
            XposedCompat.hookAllMethods(clazz, "onResume", object : XposedCompat.MethodHookCallback() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val activity = param.thisObject as? Activity ?: return
                    applyContentDetailMemberMaskHide(activity)
                }
            })
        }
        ConfigManager.log("[Hook] 游戏评价遮罩纯净单点已就绪")
    }

    fun applyContentDetailMemberMaskHide(activity: Activity) {
        val enabled = ConfigManager.isFeatureEnabled(activity, JumpConstants.KEY_HIDE_CONTENT_MEMBER_MASK)
        if (!enabled && !HookUtils.hasCollapsedViews()) return
        try {
            val targetMaskNames = listOf(
                "clMemberMask",
                "llMemberTry",
                "vMemberMask",
                "vMemberChildMask"
            )

            for (idName in targetMaskNames) {
                val resId = HookUtils.getCachedResId(activity, idName)
                if (resId != 0) {
                    activity.findViewById<View>(resId)?.let { view ->
                        if (enabled) {
                            HookUtils.collapseView(view)
                        } else if (HookUtils.isCollapsed(view)) {
                            HookUtils.restoreView(view)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            ConfigManager.logError("会员遮罩处理失败", e)
        }
    }

    private val layoutHookedActivities = Collections.newSetFromMap(WeakHashMap<Activity, Boolean>())

    private fun hookGameDetailClean(classLoader: ClassLoader) {
        hookGameDetailData(classLoader)
        hookGameDetailUI(classLoader)
        hookGameDetailNetwork(classLoader)
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
                    applyTargetViewsVisibility(decor, activeIds, restoreIds)
                }
            }
        } catch (e: Exception) {
            ConfigManager.logError("游戏折扣页净化执行异常", e)
        }
    }



    private fun hookGameDetailNetwork(classLoader: ClassLoader) {
        try {
            val builderClass = XposedCompat.findClassIfExists("okhttp3.OkHttpClient\$Builder", classLoader) ?: return
            val interceptorClass = XposedCompat.findClassIfExists("okhttp3.Interceptor", classLoader) ?: return

            val interceptorProxy = java.lang.reflect.Proxy.newProxyInstance(
                classLoader,
                arrayOf(interceptorClass)
            ) { _, method, args ->
                if (method.name == "intercept" && args != null && args.isNotEmpty()) {
                    val chain = args[0]
                    val request = XposedCompat.callMethod(chain, "request") ?: return@newProxyInstance null
                    val urlStr = XposedCompat.callMethod(request, "url")?.toString() ?: ""

                    val proceedMethod = chain.javaClass.methods.firstOrNull { it.name == "proceed" && it.parameterTypes.size == 1 }

                    // 1. 动态购买弹幕纯网络断电：/jmall/order/recently 直接返回空数据
                    if (urlStr.contains("/jmall/order/recently")) {
                        if (ConfigManager.isFeatureEnabledSafe(classLoader, JumpConstants.KEY_HIDE_GAME_DYNAMIC_BUBBLE)) {
                            try {
                                val resp = createMockJsonResponse(
                                    classLoader,
                                    request,
                                    """{"success":true,"code":200,"msg":"成功","data":[]}"""
                                )
                                ConfigManager.log("✔ [网络阻断] 拦截动态购买弹幕 (/jmall/order/recently)")
                                return@newProxyInstance resp
                            } catch (e: Throwable) {
                                ConfigManager.logError("✘ 伪造 /jmall/order/recently 响应失败，回退原生请求", e)
                            }
                        }
                    }

                    // 1.5 二手比价纯网络断电：/jmall/second_hand/sell_supplier_quotation 直接返回空数据
                    if (urlStr.contains("/jmall/second_hand/sell_supplier_quotation")) {
                        if (ConfigManager.isFeatureEnabledSafe(classLoader, JumpConstants.KEY_HIDE_GAME_SECOND_HAND)) {
                            try {
                                val resp = createMockJsonResponse(
                                    classLoader,
                                    request,
                                    """{"success":true,"code":200,"msg":"成功","data":[]}"""
                                )
                                ConfigManager.log("✔ [网络阻断] 拦截二手比价数据 (/jmall/second_hand/sell_supplier_quotation)")
                                return@newProxyInstance resp
                            } catch (e: Throwable) {
                                ConfigManager.logError("✘ 伪造 /jmall/second_hand/sell_supplier_quotation 响应失败，回退原生请求", e)
                            }
                        }
                    }

                    // 2. 低价榜推广项与导购链接清洗：/jump/price/getAllPriceByGame (方案 B：过滤前三假国家广告 + 移除各国 shopLink 去购买)
                    if (urlStr.contains("/jump/price/getAllPriceByGame")) {
                        if (ConfigManager.isFeatureEnabledSafe(classLoader, JumpConstants.KEY_HIDE_GAME_PRICE_ADS)) {
                            val originalResponse = proceedSafe(proceedMethod, chain, request)
                            try {
                                val cleaned = cleanPriceResponse(classLoader, originalResponse)
                                ConfigManager.log("✔ [网络清洗] 过滤低价榜推广项与导购链接 (/jump/price/getAllPriceByGame)")
                                return@newProxyInstance cleaned
                            } catch (e: Throwable) {
                                ConfigManager.logError("✘ 清洗 /jump/price/getAllPriceByGame 异常", e)
                                return@newProxyInstance originalResponse
                            }
                        }
                    }

                    // 3. 游戏扩展信息清洗：/jump/game/ext (移除底栏 preSale 租卡体验 + 充值 priceBanner + 详情页广告)
                    if (urlStr.contains("/jump/game/ext")) {
                        if (ConfigManager.isFeatureEnabledSafe(classLoader, JumpConstants.KEY_HIDE_GAME_BOTTOM_TRIAL_AD)) {
                            val originalResponse = proceedSafe(proceedMethod, chain, request)
                            try {
                                val cleaned = cleanGameExtResponse(classLoader, originalResponse)
                                ConfigManager.log("✔ [网络清洗] 剔除底栏购前体验与充值横幅 (/jump/game/ext)")
                                return@newProxyInstance cleaned
                            } catch (e: Throwable) {
                                ConfigManager.logError("✘ 清洗 /jump/game/ext 异常", e)
                                return@newProxyInstance originalResponse
                            }
                        }
                    }

                    // 4. 促销卡券横幅网络断电：/jmall/product/detail (拦截进入游戏详情页时背景拉取的 Switch 香港点卡等营销商品)
                    if (urlStr.contains("/jmall/product/detail")) {
                        if (ConfigManager.isFeatureEnabledSafe(classLoader, JumpConstants.KEY_HIDE_GAME_FIND_AD)) {
                            // 仅在详情页背景静默请求 (from=1 或当前在 GameDetailActivity) 时阻断，不影响商城正向浏览
                            if (urlStr.contains("from=1") || SplashHooks.currentActivityName.contains("GameDetail")) {
                                try {
                                    val resp = createMockJsonResponse(
                                        classLoader,
                                        request,
                                        """{"success":false,"code":404,"msg":"Ad blocked","data":null}"""
                                    )
                                    ConfigManager.log("✔ [网络阻断] 拦截促销点卡卡券横幅 (/jmall/product/detail)")
                                    return@newProxyInstance resp
                                } catch (e: Throwable) {
                                    ConfigManager.logError("✘ 伪造 /jmall/product/detail 响应失败，回退原生请求", e)
                                }
                            }
                        }
                    }

                    // 5. 推荐流与帖子第三方商业广告网络断电：腾讯优量汇 / 广点通 SDK (gdt.qq.com)
                    if (urlStr.contains("gdt.qq.com") || urlStr.contains("gdt_mview")) {
                        if (ConfigManager.isFeatureEnabledSafe(classLoader, JumpConstants.KEY_HIDE_POST_AD)) {
                            try {
                                val resp = createMockJsonResponse(
                                    classLoader,
                                    request,
                                    """{"ret":-1,"msg":"No ad"}"""
                                )
                                ConfigManager.log("✔ [网络阻断] 拦截腾讯优量汇商业广告 SDK (gdt.qq.com)")
                                return@newProxyInstance resp
                            } catch (e: Throwable) {
                                ConfigManager.logError("✘ 伪造 gdt.qq.com 广告响应失败", e)
                            }
                        }
                    }

                    // 5.5 推荐流广告总控网络清洗：/jump/recommend/ad_conf (包含小酱推广贴 adType=8、Jumper热议 adType=106、推荐流商业卡片 adType=101)
                    if (urlStr.contains("/jump/recommend/ad_conf")) {
                        val blockPromo = ConfigManager.isFeatureEnabledSafe(classLoader, JumpConstants.KEY_EXP_BLOCK_OFFICIAL_PROMO_POST)
                        val blockHot = ConfigManager.isFeatureEnabledSafe(classLoader, JumpConstants.KEY_HIDE_HOT_DISCUSS)
                        val blockAd = ConfigManager.isFeatureEnabledSafe(classLoader, JumpConstants.KEY_HIDE_POST_AD)

                        if (blockPromo || blockHot || blockAd) {
                            val originalResponse = proceedSafe(proceedMethod, chain, request)
                            try {
                                val cleaned = cleanRecommendAdConfResponse(classLoader, originalResponse, blockPromo, blockHot, blockAd)
                                return@newProxyInstance cleaned
                            } catch (e: Throwable) {
                                ConfigManager.logError("✘ 清洗 /jump/recommend/ad_conf 异常", e)
                                return@newProxyInstance originalResponse
                            }
                        }
                    }

                    // 6. 首页顶部枢纽数据清洗：/jump/interest_v2/home (按需清空轮播广告 bannerList 和顶部话题栏 promotionList)
                    if (urlStr.contains("/jump/interest_v2/home")) {
                        val hideBanner = ConfigManager.isFeatureEnabledSafe(classLoader, JumpConstants.KEY_HIDE_BANNER)
                        val hideTopicList = ConfigManager.isFeatureEnabledSafe(classLoader, JumpConstants.KEY_HIDE_TOPIC_LIST)
                        if (hideBanner || hideTopicList) {
                            val originalResponse = proceedSafe(proceedMethod, chain, request)
                            try {
                                val cleaned = cleanHomeInterestResponse(classLoader, originalResponse, hideBanner, hideTopicList)
                                val desc = when {
                                    hideBanner && hideTopicList -> "清空轮播与话题栏"
                                    hideBanner -> "清空轮播广告"
                                    else -> "清空顶部话题栏"
                                }
                                ConfigManager.log("✔ [网络清洗] 首页顶部数据 ($desc)")
                                return@newProxyInstance cleaned
                            } catch (e: Throwable) {
                                ConfigManager.logError("✘ 清洗 /jump/interest_v2/home 异常", e)
                                return@newProxyInstance originalResponse
                            }
                        }
                    }

                    // 7. Jumper 热议广场网络断电：/jump/subject/squareList 直接返回空数据
                    if (urlStr.contains("/jump/subject/squareList")) {
                        if (ConfigManager.isFeatureEnabledSafe(classLoader, JumpConstants.KEY_HIDE_HOT_DISCUSS)) {
                            try {
                                val resp = createMockJsonResponse(
                                    classLoader,
                                    request,
                                    """{"success":true,"code":0,"msg":"success","data":[]}"""
                                )
                                ConfigManager.log("✔ [网络阻断] 拦截 Jumper 热议广场 (/jump/subject/squareList)")
                                return@newProxyInstance resp
                            } catch (e: Throwable) {
                                ConfigManager.logError("✘ 伪造 /jump/subject/squareList 响应失败，回退原生请求", e)
                            }
                        }
                    }

                    // 8. 发现页顶部轮播广告网络断电：/jump/findGame/advert (返回空 data 数组，彻底避免适配器产生条目)
                    if (urlStr.contains("/jump/findGame/advert")) {
                        if (ConfigManager.isFeatureEnabledSafe(classLoader, JumpConstants.KEY_HIDE_DISCOVER_BANNER)) {
                            try {
                                val resp = createMockJsonResponse(
                                    classLoader,
                                    request,
                                    """{"success":true,"code":0,"msg":"success","data":[]}"""
                                )
                                ConfigManager.log("✔ [网络阻断] 拦截发现页顶部轮播广告 (/jump/findGame/advert)")
                                return@newProxyInstance resp
                            } catch (e: Throwable) {
                                ConfigManager.logError("✘ 伪造 /jump/findGame/advert 响应失败，回退原生请求", e)
                            }
                        }
                    }

                    // 10. 营销活动与引导弹窗网络断电：/jump/nice/free_nice_dialog 与 /jump/ad/game/dialog (发现页新人礼/活动营销弹窗)
                    if (urlStr.contains("/jump/nice/free_nice_dialog") || urlStr.contains("/jump/ad/game/dialog")) {
                        if (ConfigManager.isFeatureEnabledSafe(classLoader, JumpConstants.KEY_HIDE_VOUCHER_POPUP)) {
                            try {
                                val resp = createMockJsonResponse(
                                    classLoader,
                                    request,
                                    """{"success":true,"code":200,"msg":"success","data":null}"""
                                )
                                val endpoint = if (urlStr.contains("free_nice")) "free_nice_dialog" else "ad/game/dialog"
                                ConfigManager.log("✔ [网络阻断] 拦截营销弹窗配置接口: $endpoint")
                                return@newProxyInstance resp
                            } catch (e: Throwable) {
                                ConfigManager.logError("✘ 伪造营销弹窗响应失败", e)
                            }
                        }
                    }

                    // 9. 发现页推荐模块数据网络断电：findGame/list_new, findPageV2/* (大家都在买/近期热门/收藏精选/40元以下/即将发售)
                    if (urlStr.contains("/jump/findGame/list_new") ||
                        urlStr.contains("/jump/findPageV2/favorite") ||
                        urlStr.contains("/jump/findPageV2/newHotGame") ||
                        urlStr.contains("/jump/findPageV2/lt40") ||
                        urlStr.contains("/jump/findPageV2/comingSoon")
                    ) {
                        if (ConfigManager.isFeatureEnabledSafe(classLoader, JumpConstants.KEY_HIDE_DISCOVER_MODULES)) {
                            try {
                                val resp = createMockJsonResponse(
                                    classLoader,
                                    request,
                                    """{"success":true,"code":0,"msg":"success","data":[]}"""
                                )
                                val endpoint = urlStr.substringBefore("?").substringAfterLast("/")
                                ConfigManager.log("✔ [网络阻断] 拦截发现页模块数据 ($endpoint)")
                                return@newProxyInstance resp
                            } catch (e: Throwable) {
                                ConfigManager.logError("✘ 伪造发现页模块数据响应失败，回退原生请求", e)
                            }
                        }
                    }

                    return@newProxyInstance proceedSafe(proceedMethod, chain, request)
                }
                if (method.name == "toString") return@newProxyInstance "JumpCleanNetworkInterceptor"
                if (method.name == "hashCode") return@newProxyInstance 123456
                if (method.name == "equals") return@newProxyInstance args?.getOrNull(0) === this
                method.invoke(this, *(args ?: emptyArray()))
            }

            XposedCompat.findAndHookMethod(builderClass, "build", object : XposedCompat.MethodHookCallback() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        val builder = param.thisObject
                        val interceptors = XposedCompat.callMethod(builder, "interceptors") as? MutableList<Any>
                        if (interceptors != null && !interceptors.contains(interceptorProxy)) {
                            interceptors.add(0, interceptorProxy)
                        } else {
                            XposedCompat.callMethod(builder, "addInterceptor", interceptorProxy)
                        }
                    } catch (e: Throwable) {
                        ConfigManager.logError("✘ 注入 OkHttp 拦截器异常", e)
                    }
                }
            })
            ConfigManager.log("[Hook] OkHttp 网络层动态拦截器已挂载")
        } catch (e: Throwable) {
            ConfigManager.logError("✘ OkHttp 拦截器挂载异常", e)
        }
    }

    private fun proceedSafe(proceedMethod: java.lang.reflect.Method?, chain: Any, request: Any): Any {
        if (proceedMethod != null) {
            try {
                return proceedMethod.invoke(chain, request) ?: throw java.io.IOException("Null response from proceed")
            } catch (e: java.lang.reflect.InvocationTargetException) {
                val cause = e.targetException
                if (cause is java.io.IOException) {
                    throw cause
                }
                throw java.io.IOException(cause)
            }
        }
        try {
            return XposedCompat.callMethod(chain, "proceed", request) ?: throw java.io.IOException("Proceed returned null")
        } catch (e: Throwable) {
            val cause = e.cause ?: e
            if (cause is java.io.IOException) {
                throw cause
            }
            throw java.io.IOException(cause)
        }
    }



    private fun cleanJsonResponse(classLoader: ClassLoader, response: Any, transform: (String) -> String): Any {
        val body = XposedCompat.callMethod(response, "body") ?: return response
        // 配置响应应很小；超限时保留原响应，避免无限缓存响应体。
        val maxBytes = 4L * 1024 * 1024
        val snapshot = XposedCompat.callMethod(response, "peekBody", maxBytes + 1)
        val bodyString = try {
            val size = XposedCompat.callMethod(snapshot, "contentLength") as? Long ?: return response
            if (size > maxBytes) return response
            XposedCompat.callMethod(snapshot, "string") as? String ?: return response
        } finally {
            XposedCompat.callMethod(snapshot, "close")
        }
        val cleanJson = transform(bodyString)
        if (cleanJson == bodyString) return response
        val mediaType = XposedCompat.callMethod(body, "contentType")
        val newBody = createResponseBody(classLoader, mediaType, cleanJson)
        val newBuilder = XposedCompat.callMethod(response, "newBuilder")
        XposedCompat.callMethod(newBuilder, "body", newBody)
        XposedCompat.callMethod(newBuilder, "removeHeader", "Content-Length")
        val cleaned = XposedCompat.callMethod(newBuilder, "build")
        // 只在替换响应构造成功后释放原始 body，失败路径保留原 body 可读。
        try { XposedCompat.callMethod(body, "close") } catch (_: Throwable) {}
        return cleaned ?: response
    }

    private fun cleanPriceResponse(classLoader: ClassLoader, response: Any): Any {
        return cleanJsonResponse(classLoader, response, ::cleanPriceListJson)
    }

    private fun cleanPriceListJson(jsonStr: String): String {
        try {
            val root = org.json.JSONObject(jsonStr)
            val data = root.optJSONObject("data") ?: return jsonStr
            val prices = data.optJSONArray("prices") ?: return jsonStr
            val newPrices = org.json.JSONArray()
            val adTypes = setOf("10", "11", "12")
            val adCountryNames = setOf("二手卡带", "购前体验", "特价兑换码")

            for (i in 0 until prices.length()) {
                val item = prices.optJSONObject(i) ?: continue
                val type = item.optString("type")
                val country = item.optString("country")
                // 过滤伪装成国家的推广项
                if (adTypes.contains(type) || adCountryNames.contains(country)) {
                    continue
                }
                // 移除普通国家的 shopLink 导购链接，消除红色的「去购买」按钮
                item.remove("shopLink")
                newPrices.put(item)
            }
            data.put("prices", newPrices)
            return root.toString()
        } catch (_: Throwable) {
            return jsonStr
        }
    }

    private fun cleanGameExtResponse(classLoader: ClassLoader, response: Any): Any {
        return cleanJsonResponse(classLoader, response, ::cleanGameExtJson)
    }

    private fun cleanGameExtJson(jsonStr: String): String {
        try {
            val root = org.json.JSONObject(jsonStr)
            val data = root.optJSONObject("data") ?: return jsonStr
            data.remove("preSale")
            data.remove("priceBanner")
            data.put("gameDetailADs", org.json.JSONArray())
            return root.toString()
        } catch (_: Throwable) {
            return jsonStr
        }
    }

    private fun cleanHomeInterestResponse(classLoader: ClassLoader, response: Any, hideBanner: Boolean, hideTopicList: Boolean): Any {
        return cleanJsonResponse(classLoader, response) {
            cleanHomeInterestJson(it, hideBanner, hideTopicList)
        }
    }

    private fun cleanHomeInterestJson(jsonStr: String, hideBanner: Boolean, hideTopicList: Boolean): String {
        try {
            val root = org.json.JSONObject(jsonStr)
            val data = root.optJSONObject("data") ?: return jsonStr
            if (hideBanner) {
                data.put("bannerList", org.json.JSONArray())
            }
            if (hideTopicList) {
                data.put("promotionList", org.json.JSONArray())
            }
            return root.toString()
        } catch (_: Throwable) {
            return jsonStr
        }
    }

    private fun cleanRecommendAdConfResponse(
        classLoader: ClassLoader,
        response: Any,
        blockPromo: Boolean,
        blockHot: Boolean,
        blockAd: Boolean
    ): Any {
        var blockedPromoCount = 0
        val cleaned = cleanJsonResponse(classLoader, response) {
            cleanRecommendAdConfJson(it, blockPromo, blockHot, blockAd) { count ->
                blockedPromoCount = count
            }
        }
        if (cleaned !== response) {
            repeat(blockedPromoCount) { ConfigManager.recordOfficialPromoBlocked() }
        }
        return cleaned ?: response
    }

    private fun cleanRecommendAdConfJson(
        jsonStr: String,
        blockPromo: Boolean,
        blockHot: Boolean,
        blockAd: Boolean,
        onPromoBlocked: (Int) -> Unit
    ): String {
        try {
            val root = org.json.JSONObject(jsonStr)
            val data = root.optJSONArray("data") ?: return jsonStr
            val newData = org.json.JSONArray()
            var modified = false
            var blockedPromoCount = 0

            for (i in 0 until data.length()) {
                val rawPage = data.get(i)
                val pageItem = rawPage as? org.json.JSONObject
                val ads = pageItem?.optJSONArray("ads")
                if (pageItem == null || ads == null) {
                    newData.put(rawPage)
                    continue
                }
                val newAds = org.json.JSONArray()

                for (j in 0 until ads.length()) {
                    val rawAd = ads.get(j)
                    val ad = rawAd as? org.json.JSONObject
                    val adType = ad?.opt("adType")

                    // 1. 小酱推广贴 (adType == 8)
                    if (blockPromo && PromoRules.isOfficialPromoType(adType)) {
                        blockedPromoCount++
                        modified = true
                        continue
                    }

                    // 2. Jumper热议 (adType == 106)
                    if (blockHot && (adType == 106 || adType == "106")) {
                        modified = true
                        continue
                    }

                    // 3. 只删除已识别的商业卡片；未知类型和结构原样保留。
                    if (blockAd && (adType == 101 || adType == "101")) {
                        modified = true
                        continue
                    }

                    newAds.put(rawAd)
                }

                pageItem.put("ads", newAds)
                newData.put(pageItem)
            }

            if (!modified) return jsonStr
            root.put("data", newData)
            val result = root.toString()
            onPromoBlocked(blockedPromoCount)
            return result
        } catch (_: Throwable) {
            return jsonStr
        }
    }

    private fun createResponseBody(classLoader: ClassLoader, mediaType: Any?, content: String): Any {
        val responseBodyClass = XposedCompat.findClass("okhttp3.ResponseBody", classLoader)
        val mediaTypeClass = XposedCompat.findClass("okhttp3.MediaType", classLoader)
        val actualMediaType = mediaType ?: try {
            XposedCompat.callStaticMethod(mediaTypeClass, "parse", "application/json; charset=utf-8")
        } catch (_: Throwable) {
            val companion = XposedCompat.getStaticObjectField(mediaTypeClass, "Companion")
            XposedCompat.callMethod(companion, "parse", "application/json; charset=utf-8")
        }

        val created = try {
            XposedCompat.callStaticMethod(responseBodyClass, "create", actualMediaType, content)
        } catch (_: Throwable) {
            try {
                val companion = XposedCompat.getStaticObjectField(responseBodyClass, "Companion")
                XposedCompat.callMethod(companion, "create", actualMediaType, content)
            } catch (_: Throwable) {
                XposedCompat.callStaticMethod(responseBodyClass, "create", content, actualMediaType)
            }
        }
        return created ?: throw IllegalStateException("Failed to create response body")
    }

    private fun createMockJsonResponse(classLoader: ClassLoader, request: Any, jsonStr: String): Any {
        val responseBuilderClass = XposedCompat.findClass("okhttp3.Response\$Builder", classLoader)
        val protocolClass = XposedCompat.findClass("okhttp3.Protocol", classLoader)
        val responseBody = createResponseBody(classLoader, null, jsonStr)

        val http11 = XposedCompat.getStaticObjectField(protocolClass, "HTTP_1_1")
        val builder = responseBuilderClass.getDeclaredConstructor().newInstance()
        XposedCompat.callMethod(builder, "request", request)
        XposedCompat.callMethod(builder, "protocol", http11)
        XposedCompat.callMethod(builder, "code", 200)
        XposedCompat.callMethod(builder, "message", "OK")
        XposedCompat.callMethod(builder, "body", responseBody)
        return XposedCompat.callMethod(builder, "build") ?: throw IllegalStateException("Build response failed")
    }

    // =========================================================================
    // 个人主页各模块平滑刷新 (心愿单、游戏墙、动态、创作、收藏、合集防闪烁)
    // =========================================================================
    private val cachedOverviewFieldMaps = java.util.concurrent.ConcurrentHashMap<String, Map<String, Any?>>()
    @Volatile private var cachedWishlistTotal: Int? = null
    @Volatile private var cachedWishlistGames: List<*>? = null
    @Volatile private var cachedWishlistDiscount: Int? = null

    // ── 反射元数据缓存池 (Method / Field 终生只查一次，消除每帧 getMethod/declaredFields 性能损耗) ──
    private val methodCache = java.util.concurrent.ConcurrentHashMap<Class<*>, java.util.concurrent.ConcurrentHashMap<String, java.lang.reflect.Method?>>()
    private val fieldsCache = java.util.concurrent.ConcurrentHashMap<Class<*>, Array<java.lang.reflect.Field>>()

    private fun getCachedMethod(clazz: Class<*>, methodName: String): java.lang.reflect.Method? {
        val classMethods = methodCache.getOrPut(clazz) { java.util.concurrent.ConcurrentHashMap() }
        return classMethods.getOrPut(methodName) {
            try {
                clazz.getMethod(methodName).apply { isAccessible = true }
            } catch (_: Throwable) {
                null
            }
        }
    }

    private fun getCachedFields(clazz: Class<*>): Array<java.lang.reflect.Field> {
        return fieldsCache.getOrPut(clazz) {
            val list = mutableListOf<java.lang.reflect.Field>()
            for (f in clazz.declaredFields) {
                if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                f.isAccessible = true
                list.add(f)
            }
            list.toTypedArray()
        }
    }

    private fun isDummyOverviewModel(model: Any): Boolean {
        val clazz = model.javaClass
        val name = clazz.name

        // 1. 主页主游戏墙 (GameWallOverView: 39款 / 849.6h / 4023元)
        if (name.endsWith("GameWallOverView")) {
            val mGameCount = getCachedMethod(clazz, "getGameCount")
            val mPlayTime = getCachedMethod(clazz, "getTotalPlayTime")
            val mOwnList = getCachedMethod(clazz, "getOwnGameList")

            val gameCount = try { (mGameCount?.invoke(model) as? Number)?.toInt() } catch (_: Throwable) { null }
            val playTime = try { mPlayTime?.invoke(model) as? String } catch (_: Throwable) { null }
            val ownList = try { mOwnList?.invoke(model) as? List<*> } catch (_: Throwable) { null }
            return (ownList == null || ownList.isEmpty()) && (playTime.isNullOrEmpty() || gameCount == null || gameCount == 0)
        }

        // 2. 平台分流游戏墙 (MemberGameWallOverview)
        if (name.endsWith("MemberGameWallOverview")) {
            val s1 = try { getCachedMethod(clazz, "getSwitchRecentSummary")?.invoke(model) } catch (_: Throwable) { null }
            val s2 = try { getCachedMethod(clazz, "getSteamRecentSummary")?.invoke(model) } catch (_: Throwable) { null }
            val s3 = try { getCachedMethod(clazz, "getPsRecentSummary")?.invoke(model) } catch (_: Throwable) { null }
            val s4 = try { getCachedMethod(clazz, "getXboxRecentSummary")?.invoke(model) } catch (_: Throwable) { null }
            return s1 == null && s2 == null && s3 == null && s4 == null
        }

        // 3. 心愿单: 游戏列表 gameList 与 count 均为空，或 gameList 为 null 且 count 为 null/空串
        if (name.endsWith("FavoriteOverView")) {
            val gameList = try { getCachedMethod(clazz, "getGameList")?.invoke(model) } catch (_: Throwable) { null }
            val count = try { getCachedMethod(clazz, "getCount")?.invoke(model) } catch (_: Throwable) { null }
            return gameList == null && (count == null || count == "")
        }

        // 4. 动态、创作、收藏、合集: 空壳时 list 列表对象为 null
        val list = try { getCachedMethod(clazz, "getList")?.invoke(model) } catch (_: Throwable) { null }
        if (list == null) {
            val subCount = try {
                getCachedMethod(clazz, "getDynamicCount")?.invoke(model)
            } catch (_: Throwable) {
                try {
                    getCachedMethod(clazz, "getArticleCount")?.invoke(model)
                } catch (_: Throwable) {
                    try {
                        getCachedMethod(clazz, "getCollectionCount")?.invoke(model)
                    } catch (_: Throwable) {
                        try {
                            getCachedMethod(clazz, "getGameListCount")?.invoke(model)
                        } catch (_: Throwable) {
                            null
                        }
                    }
                }
            }
            if (subCount == null) return true
        }

        return false
    }

    private fun getModelCacheKey(model: Any): String {
        val clazz = model.javaClass
        val className = clazz.name
        val mOrderType = getCachedMethod(clazz, "getOrderType")
        val orderType = try {
            mOrderType?.invoke(model)?.toString()
        } catch (_: Throwable) {
            null
        }
        return if (!orderType.isNullOrEmpty()) "$className#$orderType" else className
    }

    private fun snapshotOverviewModel(model: Any) {
        if (isDummyOverviewModel(model)) return // 严防空壳覆盖有效快照
        val clazz = model.javaClass
        val cacheKey = getModelCacheKey(model)
        val fields = getCachedFields(clazz)
        val map = HashMap<String, Any?>(fields.size)
        for (field in fields) {
            val v = field.get(model)
            if (v != null) {
                // 仅缓存展示相关基本类型与集合，避免持有复杂引用
                if (v is String || v is Number || v is Boolean || v is java.util.List<*>) {
                    map[field.name] = v
                }
            }
        }
        if (map.isNotEmpty()) {
            cachedOverviewFieldMaps[cacheKey] = map
            cachedOverviewFieldMaps[clazz.name] = map
            ConfigManager.log("[OverviewSmoothing] 成功快照真实模块数据: $cacheKey")
        }
    }

    private fun backfillOverviewModel(model: Any): Boolean {
        val clazz = model.javaClass
        val cacheKey = getModelCacheKey(model)
        val cachedMap = cachedOverviewFieldMaps[cacheKey] ?: cachedOverviewFieldMaps[clazz.name] ?: return false
        return try {
            val fields = getCachedFields(clazz)
            for (field in fields) {
                val cachedVal = cachedMap[field.name]
                if (cachedVal != null) {
                    field.set(model, cachedVal)
                }
            }
            ConfigManager.log("[OverviewSmoothing] 抑制主页模块瞬时闪烁，成功回填: $cacheKey")
            true
        } catch (e: Throwable) {
            ConfigManager.logError("回填主页模块缓存失败: $cacheKey", e)
            false
        }
    }

    private fun hookOverviewSmoothing(classLoader: ClassLoader) {
        try {
            // 1. 监听 MyFragment.A0: 网络数据源头，抓取全量真实模块数据快照
            val myFragClass = XposedCompat.findClassIfExists("com.vgjump.jump.ui.my.MyFragment", classLoader)
            if (myFragClass != null) {
                val a0Method = myFragClass.declaredMethods.firstOrNull { method ->
                    method.name == "A0" &&
                        method.parameterTypes.size == 2 &&
                        method.parameterTypes[0] == myFragClass
                }
                if (a0Method != null) {
                    XposedCompat.hookMethod(a0Method, object : XposedCompat.MethodHookCallback() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val container = param.args.getOrNull(1) ?: return
                            try {
                                for (field in container.javaClass.declaredFields) {
                                    if (java.lang.reflect.Modifier.isStatic(field.modifiers)) continue
                                    field.isAccessible = true
                                    val item = field.get(container) ?: continue
                                    if (item.javaClass.name.contains("overview", ignoreCase = true)) {
                                        snapshotOverviewModel(item)
                                    }
                                }
                            } catch (e: Throwable) {
                                ConfigManager.logError("解析 overview 网络包异常", e)
                            }
                        }
                    })
                    ConfigManager.log("[Hook] MyFragment.A0 网络数据监听挂载完成")
                }
            }

            // 2. 挂载 RecyclerUtilsKt.b: f1 生成空骨架塞入列表的入口，在塞入时就地回填
            val recyclerUtilsClass = XposedCompat.findClassIfExists("com.drake.brv.utils.RecyclerUtilsKt", classLoader)
            if (recyclerUtilsClass != null) {
                val bMethod = recyclerUtilsClass.declaredMethods.firstOrNull { method ->
                    method.name == "b" &&
                        method.parameterTypes.size >= 2 &&
                        method.parameterTypes[1] == java.util.List::class.java
                }
                if (bMethod != null) {
                    XposedCompat.hookMethod(bMethod, object : XposedCompat.MethodHookCallback() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (!ConfigManager.isFeatureEnabledSafe(classLoader, JumpConstants.KEY_PREVENT_WISHLIST_FLASH)) {
                                return
                            }
                            val list = param.args.getOrNull(1) as? List<*> ?: return
                            for (item in list) {
                                if (item == null) continue
                                if (!item.javaClass.name.contains("overview", ignoreCase = true)) continue
                                if (isDummyOverviewModel(item)) {
                                    backfillOverviewModel(item)
                                } else {
                                    snapshotOverviewModel(item)
                                }
                            }
                        }
                    })
                    ConfigManager.log("[Hook] RecyclerUtilsKt.b 列表注入防抖挂载完成")
                }
            }

            // 3. 挂载 UserBaseViewModel 各模块 UI 数据绑定入口 (双重兜底)
            val userBaseVmClass = XposedCompat.findClassIfExists("com.vgjump.jump.ui.my.UserBaseViewModel", classLoader)
            if (userBaseVmClass == null) {
                ConfigManager.log("UserBaseViewModel 未找到，跳过主页模块平滑 Hook")
                return
            }

            val matchingMethods = userBaseVmClass.declaredMethods.filter { method ->
                method.parameterTypes.size >= 2 &&
                    method.parameterTypes[0] == android.content.Context::class.java &&
                    method.parameterTypes.any { it.name.contains("overview", ignoreCase = true) }
            }

            for (method in matchingMethods) {
                XposedCompat.hookMethod(method, object : XposedCompat.MethodHookCallback() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!ConfigManager.isFeatureEnabledSafe(classLoader, JumpConstants.KEY_PREVENT_WISHLIST_FLASH)) {
                            return
                        }
                        val hasOtherUserId = param.args.any { it is String && it.isNotEmpty() }
                        if (hasOtherUserId) return

                        val model = param.args.firstOrNull { it != null && it.javaClass.name.contains("overview", ignoreCase = true) } ?: return
                        if (isDummyOverviewModel(model)) {
                            backfillOverviewModel(model)
                        } else {
                            snapshotOverviewModel(model)
                        }
                    }
                })
            }
            ConfigManager.log("[Hook] 个人主页各大模块平滑刷新净化已就绪 (${matchingMethods.size} 个模块入口)")

            // 4. 精准挂载心愿单专有刷新方法 (特征扫描，不依赖混淆方法名 v1)
            val favMethods = userBaseVmClass.declaredMethods.filter { method ->
                method.parameterTypes.size in 3..6 &&
                    method.parameterTypes[0] == android.content.Context::class.java &&
                    method.parameterTypes.any { it.name.endsWith("FavoriteOverView") }
            }
            for (method in favMethods) {
                XposedCompat.hookMethod(method, object : XposedCompat.MethodHookCallback() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!ConfigManager.isFeatureEnabledSafe(classLoader, JumpConstants.KEY_PREVENT_WISHLIST_FLASH)) {
                            return
                        }
                        val hasOtherUserId = param.args.any { it is String && it.isNotEmpty() }
                        if (hasOtherUserId) return

                        val fav = param.args.firstOrNull { it != null && it.javaClass.name.endsWith("FavoriteOverView") } ?: return
                        val total = try {
                            XposedCompat.callMethod(fav, "getTotalGameNum") as? Int
                        } catch (_: Throwable) {
                            try {
                                (XposedCompat.callMethod(fav, "getCount") as? String)?.toIntOrNull()
                            } catch (_: Throwable) { null }
                        }

                        if (total == null) {
                            // 这是空骨架，回填心愿单缓存
                            val cachedTotal = cachedWishlistTotal
                            if (cachedTotal != null) {
                                try {
                                    XposedCompat.callMethod(fav, "setTotalGameNum", cachedTotal)
                                    cachedWishlistGames?.let { games ->
                                        try { XposedCompat.callMethod(fav, "setFavoriteGames", games) } catch (_: Throwable) {}
                                    }
                                    cachedWishlistDiscount?.let { discount ->
                                        try { XposedCompat.callMethod(fav, "setDiscountNum", discount) } catch (_: Throwable) {}
                                    }
                                    ConfigManager.log("[WishlistSmoothing] 抑制心愿单瞬时闪烁，回填缓存: total=$cachedTotal")
                                } catch (e: Throwable) {
                                    ConfigManager.logError("回填心愿单缓存失败", e)
                                }
                            }
                        } else {
                            // 真实数据到达，更新最新有效快照
                            cachedWishlistTotal = total
                            try {
                                cachedWishlistGames = XposedCompat.callMethod(fav, "getFavoriteGames") as? List<*>
                                cachedWishlistDiscount = XposedCompat.callMethod(fav, "getDiscountNum") as? Int
                            } catch (_: Throwable) {}
                        }
                    }
                })
            }
            if (favMethods.isNotEmpty()) {
                ConfigManager.log("[Hook] 心愿单平滑刷新已就绪 (${favMethods.size} 个方法)")
            }
        } catch (e: Throwable) {
            ConfigManager.logError("✘ 个人主页平滑刷新 Hook 失败", e)
        }
    }
}
