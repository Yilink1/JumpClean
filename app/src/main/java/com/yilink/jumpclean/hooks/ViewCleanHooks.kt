package com.yilink.jumpclean.hooks

import android.app.Activity
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import com.yilink.jumpclean.HookUtils
import com.yilink.jumpclean.config.ConfigManager
import com.yilink.jumpclean.config.JumpConstants
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
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

    fun hook(lpparam: XC_LoadPackage.LoadPackageParam) {
        hookMainActivityUI(lpparam)
        hookTabVisibilityIntercept(lpparam)
        hookAdViews(lpparam)
        hookContentDetailMemberMask(lpparam)
        hookGameDetailClean(lpparam)
    }

    private fun hookTabVisibilityIntercept(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            XposedHelpers.findAndHookMethod(
                View::class.java,
                "setVisibility",
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val view = param.thisObject as? View ?: return
                        val id = view.id
                        if (id <= 0) return

                        if (cachedRedDotId != 0 && id == cachedRedDotId) {
                            if (ConfigManager.isFeatureEnabledSafe(lpparam.classLoader, JumpConstants.KEY_HIDE_LOTTERY_TAB)) {
                                param.args[0] = View.GONE
                            }
                        } else if (cachedLotteryTabId != 0 && id == cachedLotteryTabId) {
                            if (ConfigManager.isFeatureEnabledSafe(lpparam.classLoader, JumpConstants.KEY_HIDE_LOTTERY_TAB)) {
                                param.args[0] = View.GONE
                            }
                        } else if (cachedWebTabId != 0 && id == cachedWebTabId) {
                            if (ConfigManager.isFeatureEnabledSafe(lpparam.classLoader, JumpConstants.KEY_HIDE_WEB_TAB)) {
                                param.args[0] = View.GONE
                            }
                        }
                    }
                }
            )
            ConfigManager.log("✔ 底栏与红点动态 setVisibility 阻断 Hook 已就绪")
        } catch (e: Exception) {
            ConfigManager.logError("✘ 底栏与红点 setVisibility 阻断 Hook 失败", e)
        }
    }

    private fun hookMainActivityUI(lpparam: XC_LoadPackage.LoadPackageParam) {
        val mainActivityClass = XposedHelpers.findClassIfExists("com.vgjump.jump.ui.main.MainActivity", lpparam.classLoader) ?: return
        try {
            XposedHelpers.findAndHookMethod(mainActivityClass, "onCreate",
                android.os.Bundle::class.java, object : XC_MethodHook() {
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
            ConfigManager.log("✔ 首页 UI 净化 Hook 已安装")
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
                ConfigManager.isFeatureEnabled(activity, JumpConstants.KEY_HIDE_DISCOVER_TOP_AD)
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
        if (targetIds.contains(view.id)) {
            HookUtils.collapseView(view)
        } else if (restoreIds.contains(view.id) && HookUtils.isCollapsed(view)) {
            HookUtils.restoreView(view)
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                applyTargetViewsVisibility(view.getChildAt(i), targetIds, restoreIds)
            }
        }
    }

    private fun hookAdViews(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val nativeAdClass = XposedHelpers.findClassIfExists("com.qq.e.ads.nativ.widget.NativeAdContainer", lpparam.classLoader) ?: return
            val expressAdClass = XposedHelpers.findClassIfExists("com.qq.e.ads.nativ.NativeExpressADView", lpparam.classLoader)

            var lastNativeAdLogTime = 0L
            val collapseAdAction: (View) -> Unit = { view ->
                if (ConfigManager.isFeatureEnabled(view.context, JumpConstants.KEY_HIDE_POST_AD)) {
                    HookUtils.collapseView(view)
                    val now = SystemClock.uptimeMillis()
                    if (now - lastNativeAdLogTime > 1500L) {
                        lastNativeAdLogTime = now
                        ConfigManager.log("🛡 [广告容器拦截] 折叠腾讯 NativeAdContainer 广告 (推荐流/帖子内嵌)")
                    }
                } else if (HookUtils.isCollapsed(view)) {
                    HookUtils.restoreView(view)
                }
            }

            val hookAdClass = { targetCls: Class<*> ->
                XposedBridge.hookAllConstructors(targetCls, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val view = param.thisObject as? View ?: return
                            collapseAdAction(view)
                        } catch (e: Exception) {
                            ConfigManager.logError("${targetCls.simpleName} 构造拦截异常", e)
                        }
                    }
                })

                XposedBridge.hookAllMethods(targetCls, "setVisibility", object : XC_MethodHook() {
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

            XposedHelpers.findAndHookMethod(View::class.java, "onAttachedToWindow", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val view = param.thisObject as? View ?: return
                        if (nativeAdClass.isInstance(view) || (expressAdClass != null && expressAdClass.isInstance(view))) {
                            collapseAdAction(view)
                        }
                    } catch (_: Exception) {}
                }
            })

            ConfigManager.log("✔ 通用广告容器（首页推荐流 + 帖子内嵌）Hook 已安装")
        } catch (e: Exception) {
            ConfigManager.logError("✘ 通用广告容器 Hook 失败", e)
        }
    }

    private fun hookContentDetailMemberMask(lpparam: XC_LoadPackage.LoadPackageParam) {
        val targetActivities = listOf(
            "com.vgjump.jump.ui.content.detail.ContentDetailActivity",
            "com.vgjump.jump.ui.content.detail.CommentDetailActivity"
        )

        val maskHook = object : XC_MethodHook() {
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
            val clazz = XposedHelpers.findClassIfExists(className, lpparam.classLoader) ?: return@forEach
            XposedBridge.hookAllMethods(clazz, "onCreate", maskHook)
            XposedBridge.hookAllMethods(clazz, "onResume", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val activity = param.thisObject as? Activity ?: return
                    applyContentDetailMemberMaskHide(activity)
                }
            })
        }
        ConfigManager.log("✔ 游戏评价遮罩纯净单点 Hook 已就绪")
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

    private fun hookGameDetailClean(lpparam: XC_LoadPackage.LoadPackageParam) {
        hookGameDetailData(lpparam)
        hookGameDetailUI(lpparam)
        hookGameDetailFindAdView(lpparam)
        hookGameDetailNetwork(lpparam)
    }

    private fun hookGameDetailData(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val candidateVmFindAdClasses = listOf(
                "com.vgjump.jump.ui.game.detail.home.GameDetailHomeViewModel",
                "com.vgjump.jump.ui.game.detail.GameDetailHomeViewModel"
            )
            for (className in candidateVmFindAdClasses) {
                val homeVmClass = XposedHelpers.findClassIfExists(className, lpparam.classLoader) ?: continue
                homeVmClass.declaredMethods.forEach { method ->
                    if (method.name.contains("findad", ignoreCase = true)) {
                        XposedBridge.hookMethod(method, object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                if (ConfigManager.isFeatureEnabledSafe(lpparam.classLoader, JumpConstants.KEY_HIDE_GAME_FIND_AD)) {
                                    param.result = null
                                }
                            }
                        })
                    }
                }
                ConfigManager.log("✔ GameDetailHomeViewModel findAD 协程断电 Hook 已挂载: $className")
            }

            // 2. 动态购买弹幕总电闸：OrderRecentlyKt.shouldShowOrderRecentlyBanner 强制返回 false
            val candidateOrderRecentlyClasses = listOf(
                "com.vgjump.jump.ui.game.detail.OrderRecentlyKt",
                "com.vgjump.jump.ui.game.detail.home.OrderRecentlyKt",
                "com.vgjump.jump.ui.game.OrderRecentlyKt"
            )
            for (className in candidateOrderRecentlyClasses) {
                val orderClazz = XposedHelpers.findClassIfExists(className, lpparam.classLoader) ?: continue
                XposedBridge.hookAllMethods(orderClazz, "shouldShowOrderRecentlyBanner", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (ConfigManager.isFeatureEnabledSafe(lpparam.classLoader, JumpConstants.KEY_HIDE_GAME_DYNAMIC_BUBBLE)) {
                            param.result = false
                        }
                    }
                })
                XposedBridge.hookAllMethods(orderClazz, "toOrderRecentlyBannerItems", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (ConfigManager.isFeatureEnabledSafe(lpparam.classLoader, JumpConstants.KEY_HIDE_GAME_DYNAMIC_BUBBLE)) {
                            param.result = emptyList<Any>()
                        }
                    }
                })
                ConfigManager.log("✔ OrderRecentlyKt 数据断电 Hook 已挂载: $className")
            }

            // 3. GameDetailViewModel 订单轮播协程入口断电 (仅匹配包含 getOrderSlideShow 的安全全名方法)
            val candidateVmClasses = listOf(
                "com.vgjump.jump.ui.game.detail.GameDetailViewModel",
                "com.vgjump.jump.ui.game.detail.home.GameDetailViewModel"
            )
            for (className in candidateVmClasses) {
                val vmClazz = XposedHelpers.findClassIfExists(className, lpparam.classLoader) ?: continue
                vmClazz.declaredMethods.forEach { method ->
                    if (method.name.contains("getOrderSlideShow", ignoreCase = true)) {
                        XposedBridge.hookMethod(method, object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                if (ConfigManager.isFeatureEnabledSafe(lpparam.classLoader, JumpConstants.KEY_HIDE_GAME_DYNAMIC_BUBBLE)) {
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

    private fun hookGameDetailUI(lpparam: XC_LoadPackage.LoadPackageParam) {
        val targetClass = "com.vgjump.jump.ui.game.detail.GameDetailActivity"
        val clazz = XposedHelpers.findClassIfExists(targetClass, lpparam.classLoader) ?: return

        try {
            val lifecycleHook = object : XC_MethodHook() {
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
                XposedBridge.hookAllMethods(c, "onResume", lifecycleHook)
                XposedBridge.hookAllMethods(c, "onCreate", lifecycleHook)
                c = c.superclass
            }
            ConfigManager.log("✔ 游戏折扣页单点 UI Hook 已就绪（仅限定 GameDetailActivity 继承链）")
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
            val hideMemberGuide = ConfigManager.isFeatureEnabled(activity, JumpConstants.KEY_HIDE_GAME_MEMBER_GUIDE)
            val hideExtraBadge = ConfigManager.isFeatureEnabled(activity, JumpConstants.KEY_HIDE_GAME_EXTRA_BADGE)
            val hideFindAd = ConfigManager.isFeatureEnabled(activity, JumpConstants.KEY_HIDE_GAME_FIND_AD)

            // 1. 会员开通引导条 (clMemberGuide) - 静态控件，仅折叠自身
            val memberGuideId = HookUtils.getCachedResId(activity, "clMemberGuide")
            if (memberGuideId != 0) {
                activity.findViewById<View>(memberGuideId)?.let { v ->
                    if (hideMemberGuide) {
                        HookUtils.collapseView(v)
                    } else if (HookUtils.isCollapsed(v)) {
                        HookUtils.restoreView(v)
                    }
                }
            }

            // 2. 会员专属优惠标识 (ivExtra) 与促销卡券横幅 (ivFindAD) - 递归扫描整个视图树精准折叠
            val extraId = HookUtils.getCachedResId(activity, "ivExtra")
            val findAdId = HookUtils.getCachedResId(activity, "ivFindAD")
            val decor = activity.window?.decorView
            if (decor != null) {
                val activeIds = HashSet<Int>()
                val restoreIds = HashSet<Int>()
                if (extraId != 0) {
                    if (hideExtraBadge) activeIds.add(extraId)
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

    private fun hookGameDetailFindAdView(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            // 1. Hook Group.setVisibility: 若受控 IDs 包含 ivFindAD，且开关开启，强制转为 GONE
            val groupClass = XposedHelpers.findClassIfExists("androidx.constraintlayout.widget.Group", lpparam.classLoader)
            if (groupClass != null) {
                XposedBridge.hookAllMethods(groupClass, "setVisibility", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            val group = param.thisObject as? View ?: return
                            val findAdId = HookUtils.getCachedResId(group.context, "ivFindAD")
                            if (findAdId == 0) return
                            val ids = XposedHelpers.callMethod(group, "getReferencedIds") as? IntArray ?: return
                            if (ids.contains(findAdId)) {
                                if (ConfigManager.isFeatureEnabledSafe(lpparam.classLoader, JumpConstants.KEY_HIDE_GAME_FIND_AD)) {
                                    param.args[0] = View.GONE
                                }
                            }
                        } catch (_: Throwable) {}
                    }
                })
            }

            // 2. Hook ImageFilterView (ivFindAD 控件类型)
            val imageFilterViewClass = XposedHelpers.findClassIfExists("androidx.constraintlayout.utils.widget.ImageFilterView", lpparam.classLoader)
            if (imageFilterViewClass != null) {
                // 强制 setVisibility 始终为 GONE
                XposedBridge.hookAllMethods(imageFilterViewClass, "setVisibility", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            val view = param.thisObject as? View ?: return
                            val findAdId = HookUtils.getCachedResId(view.context, "ivFindAD")
                            if (findAdId != 0 && view.id == findAdId) {
                                if (ConfigManager.isFeatureEnabledSafe(lpparam.classLoader, JumpConstants.KEY_HIDE_GAME_FIND_AD)) {
                                    param.args[0] = View.GONE
                                }
                            }
                        } catch (_: Throwable) {}
                    }
                })

                // 挂载到窗口时强行清零尺寸与边距，并同步折叠父级 Group
                XposedBridge.hookAllMethods(imageFilterViewClass, "onAttachedToWindow", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val view = param.thisObject as? View ?: return
                            val findAdId = HookUtils.getCachedResId(view.context, "ivFindAD")
                            if (findAdId != 0 && view.id == findAdId) {
                                if (ConfigManager.isFeatureEnabledSafe(lpparam.classLoader, JumpConstants.KEY_HIDE_GAME_FIND_AD)) {
                                    HookUtils.collapseView(view)
                                    (view.parent as? ViewGroup)?.let { parent ->
                                        for (i in 0 until parent.childCount) {
                                            val child = parent.getChildAt(i)
                                            if (child.javaClass.name.contains("Group")) {
                                                val ids = XposedHelpers.callMethod(child, "getReferencedIds") as? IntArray
                                                if (ids != null && ids.contains(findAdId)) {
                                                    HookUtils.collapseView(child)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        } catch (_: Throwable) {}
                    }
                })

                // 尺寸测量阻断：强行量出 0x0 像素
                XposedBridge.hookAllMethods(imageFilterViewClass, "onMeasure", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            val view = param.thisObject as? View ?: return
                            val findAdId = HookUtils.getCachedResId(view.context, "ivFindAD")
                            if (findAdId != 0 && view.id == findAdId) {
                                if (ConfigManager.isFeatureEnabledSafe(lpparam.classLoader, JumpConstants.KEY_HIDE_GAME_FIND_AD)) {
                                    val zeroSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.EXACTLY)
                                    param.args[0] = zeroSpec
                                    param.args[1] = zeroSpec
                                }
                            }
                        } catch (_: Throwable) {}
                    }
                })

                // 图片装载拦截：阻止 Bitmap 写入
                val setImageHook = object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            val view = param.thisObject as? View ?: return
                            val findAdId = HookUtils.getCachedResId(view.context, "ivFindAD")
                            if (findAdId != 0 && view.id == findAdId) {
                                if (ConfigManager.isFeatureEnabledSafe(lpparam.classLoader, JumpConstants.KEY_HIDE_GAME_FIND_AD)) {
                                    param.result = null
                                }
                            }
                        } catch (_: Throwable) {}
                    }
                }
                XposedBridge.hookAllMethods(imageFilterViewClass, "setImageDrawable", setImageHook)
                XposedBridge.hookAllMethods(imageFilterViewClass, "setImageBitmap", setImageHook)
            }
            ConfigManager.log("✔ ivFindAD 与关联 Group 专项防护 Hook 已就绪")
        } catch (e: Throwable) {
            ConfigManager.logError("✘ ivFindAD 专项防护 Hook 异常", e)
        }
    }

    private fun hookGameDetailNetwork(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val builderClass = XposedHelpers.findClassIfExists("okhttp3.OkHttpClient\$Builder", lpparam.classLoader) ?: return
            val interceptorClass = XposedHelpers.findClassIfExists("okhttp3.Interceptor", lpparam.classLoader) ?: return

            val interceptorProxy = java.lang.reflect.Proxy.newProxyInstance(
                lpparam.classLoader,
                arrayOf(interceptorClass)
            ) { _, method, args ->
                if (method.name == "intercept" && args != null && args.isNotEmpty()) {
                    val chain = args[0]
                    val request = XposedHelpers.callMethod(chain, "request")
                    val urlStr = XposedHelpers.callMethod(request, "url")?.toString() ?: ""
                    val proceedMethod = chain.javaClass.methods.firstOrNull { it.name == "proceed" && it.parameterTypes.size == 1 }

                    // 1. 动态购买弹幕纯网络断电：/jmall/order/recently 直接返回空数据
                    if (urlStr.contains("/jmall/order/recently")) {
                        if (ConfigManager.isFeatureEnabledSafe(lpparam.classLoader, JumpConstants.KEY_HIDE_GAME_DYNAMIC_BUBBLE)) {
                            try {
                                val resp = createMockJsonResponse(
                                    lpparam.classLoader,
                                    request,
                                    """{"success":true,"code":200,"msg":"成功","data":[]}"""
                                )
                                ConfigManager.log("⚡ [网络层阻断] 拦截动态购买弹幕 (/jmall/order/recently)")
                                return@newProxyInstance resp
                            } catch (e: Throwable) {
                                ConfigManager.logError("✘ 伪造 /jmall/order/recently 响应失败，回退原生请求", e)
                            }
                        }
                    }

                    // 2. 低价榜推广项与导购链接清洗：/jump/price/getAllPriceByGame (方案 B：过滤前三假国家广告 + 移除各国 shopLink 去购买)
                    if (urlStr.contains("/jump/price/getAllPriceByGame")) {
                        if (ConfigManager.isFeatureEnabledSafe(lpparam.classLoader, JumpConstants.KEY_HIDE_GAME_PRICE_ADS)) {
                            val originalResponse = proceedSafe(proceedMethod, chain, request)
                            try {
                                val cleaned = cleanPriceResponse(lpparam.classLoader, originalResponse)
                                ConfigManager.log("⚡ [网络层清洗] 过滤低价榜推广项与导购链接 (/jump/price/getAllPriceByGame)")
                                return@newProxyInstance cleaned
                            } catch (e: Throwable) {
                                ConfigManager.logError("✘ 清洗 /jump/price/getAllPriceByGame 异常", e)
                                return@newProxyInstance originalResponse
                            }
                        }
                    }

                    // 3. 游戏扩展信息清洗：/jump/game/ext (移除底栏 preSale 租卡体验 + 充值 priceBanner + 详情页广告)
                    if (urlStr.contains("/jump/game/ext")) {
                        if (ConfigManager.isFeatureEnabledSafe(lpparam.classLoader, JumpConstants.KEY_HIDE_GAME_BOTTOM_TRIAL_AD)) {
                            val originalResponse = proceedSafe(proceedMethod, chain, request)
                            try {
                                val cleaned = cleanGameExtResponse(lpparam.classLoader, originalResponse)
                                ConfigManager.log("⚡ [网络层清洗] 剔除底栏购前体验与充值横幅 (/jump/game/ext)")
                                return@newProxyInstance cleaned
                            } catch (e: Throwable) {
                                ConfigManager.logError("✘ 清洗 /jump/game/ext 异常", e)
                                return@newProxyInstance originalResponse
                            }
                        }
                    }

                    // 4. 促销卡券横幅网络断电：/jmall/product/detail (拦截进入游戏详情页时背景拉取的 Switch 香港点卡等营销商品)
                    if (urlStr.contains("/jmall/product/detail")) {
                        if (ConfigManager.isFeatureEnabledSafe(lpparam.classLoader, JumpConstants.KEY_HIDE_GAME_FIND_AD)) {
                            // 仅在详情页背景静默请求 (from=1 或当前在 GameDetailActivity) 时阻断，不影响商城正向浏览
                            if (urlStr.contains("from=1") || SplashHooks.currentActivityName.contains("GameDetail")) {
                                try {
                                    val resp = createMockJsonResponse(
                                        lpparam.classLoader,
                                        request,
                                        """{"success":false,"code":404,"msg":"Ad blocked","data":null}"""
                                    )
                                    ConfigManager.log("⚡ [网络层阻断] 拦截促销点卡卡券横幅 (/jmall/product/detail)")
                                    return@newProxyInstance resp
                                } catch (e: Throwable) {
                                    ConfigManager.logError("✘ 伪造 /jmall/product/detail 响应失败，回退原生请求", e)
                                }
                            }
                        }
                    }

                    // 5. 推荐流与帖子第三方商业广告网络断电：腾讯优量汇 / 广点通 SDK (gdt.qq.com)
                    if (urlStr.contains("gdt.qq.com") || urlStr.contains("gdt_mview")) {
                        if (ConfigManager.isFeatureEnabledSafe(lpparam.classLoader, JumpConstants.KEY_HIDE_POST_AD)) {
                            try {
                                val resp = createMockJsonResponse(
                                    lpparam.classLoader,
                                    request,
                                    """{"ret":-1,"msg":"No ad"}"""
                                )
                                ConfigManager.log("⚡ [网络层阻断] 拦截腾讯优量汇商业广告 SDK (gdt.qq.com)")
                                return@newProxyInstance resp
                            } catch (e: Throwable) {
                                ConfigManager.logError("✘ 伪造 gdt.qq.com 广告响应失败", e)
                            }
                        }
                    }

                    // 6. 首页顶部枢纽数据清洗：/jump/interest_v2/home (按需清空轮播广告 bannerList 和顶部话题栏 promotionList)
                    if (urlStr.contains("/jump/interest_v2/home")) {
                        val hideBanner = ConfigManager.isFeatureEnabledSafe(lpparam.classLoader, JumpConstants.KEY_HIDE_BANNER)
                        val hideTopicList = ConfigManager.isFeatureEnabledSafe(lpparam.classLoader, JumpConstants.KEY_HIDE_TOPIC_LIST)
                        if (hideBanner || hideTopicList) {
                            val originalResponse = proceedSafe(proceedMethod, chain, request)
                            try {
                                val cleaned = cleanHomeInterestResponse(lpparam.classLoader, originalResponse, hideBanner, hideTopicList)
                                ConfigManager.log("⚡ [网络层清洗] 首页顶部数据 (轮播=$hideBanner, 话题=$hideTopicList)")
                                return@newProxyInstance cleaned
                            } catch (e: Throwable) {
                                ConfigManager.logError("✘ 清洗 /jump/interest_v2/home 异常", e)
                                return@newProxyInstance originalResponse
                            }
                        }
                    }

                    // 7. Jumper 热议广场网络断电：/jump/subject/squareList 直接返回空数据
                    if (urlStr.contains("/jump/subject/squareList")) {
                        if (ConfigManager.isFeatureEnabledSafe(lpparam.classLoader, JumpConstants.KEY_HIDE_HOT_DISCUSS)) {
                            try {
                                val resp = createMockJsonResponse(
                                    lpparam.classLoader,
                                    request,
                                    """{"success":true,"code":0,"msg":"success","data":[]}"""
                                )
                                ConfigManager.log("⚡ [网络层阻断] 拦截 Jumper 热议广场 (/jump/subject/squareList)")
                                return@newProxyInstance resp
                            } catch (e: Throwable) {
                                ConfigManager.logError("✘ 伪造 /jump/subject/squareList 响应失败，回退原生请求", e)
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

            XposedHelpers.findAndHookMethod(builderClass, "build", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        val builder = param.thisObject
                        val interceptors = XposedHelpers.callMethod(builder, "interceptors") as? MutableList<Any>
                        if (interceptors != null && !interceptors.contains(interceptorProxy)) {
                            interceptors.add(0, interceptorProxy)
                        } else {
                            XposedHelpers.callMethod(builder, "addInterceptor", interceptorProxy)
                        }
                    } catch (e: Throwable) {
                        ConfigManager.logError("✘ 注入 OkHttp 拦截器异常", e)
                    }
                }
            })
            ConfigManager.log("✔ OkHttp 网络层动态拦截器已挂载")
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
            return XposedHelpers.callMethod(chain, "proceed", request)
        } catch (e: Throwable) {
            val cause = e.cause ?: e
            if (cause is java.io.IOException) {
                throw cause
            }
            throw java.io.IOException(cause)
        }
    }

    private fun cleanPriceResponse(classLoader: ClassLoader, response: Any): Any {
        val body = XposedHelpers.callMethod(response, "body") ?: return response
        val bodyString = XposedHelpers.callMethod(body, "string") as? String ?: return response

        val cleanJson = cleanPriceListJson(bodyString)
        val mediaType = XposedHelpers.callMethod(body, "contentType")
        val newBody = createResponseBody(classLoader, mediaType, cleanJson)
        val newBuilder = XposedHelpers.callMethod(response, "newBuilder")
        XposedHelpers.callMethod(newBuilder, "body", newBody)
        return XposedHelpers.callMethod(newBuilder, "build")
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
        val body = XposedHelpers.callMethod(response, "body") ?: return response
        val bodyString = XposedHelpers.callMethod(body, "string") as? String ?: return response

        val cleanJson = cleanGameExtJson(bodyString)
        val mediaType = XposedHelpers.callMethod(body, "contentType")
        val newBody = createResponseBody(classLoader, mediaType, cleanJson)
        val newBuilder = XposedHelpers.callMethod(response, "newBuilder")
        XposedHelpers.callMethod(newBuilder, "body", newBody)
        return XposedHelpers.callMethod(newBuilder, "build")
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
        val body = XposedHelpers.callMethod(response, "body") ?: return response
        val bodyString = XposedHelpers.callMethod(body, "string") as? String ?: return response

        val cleanJson = cleanHomeInterestJson(bodyString, hideBanner, hideTopicList)
        val mediaType = XposedHelpers.callMethod(body, "contentType")
        val newBody = createResponseBody(classLoader, mediaType, cleanJson)
        val newBuilder = XposedHelpers.callMethod(response, "newBuilder")
        XposedHelpers.callMethod(newBuilder, "body", newBody)
        return XposedHelpers.callMethod(newBuilder, "build")
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

    private fun createResponseBody(classLoader: ClassLoader, mediaType: Any?, content: String): Any {
        val responseBodyClass = XposedHelpers.findClass("okhttp3.ResponseBody", classLoader)
        val mediaTypeClass = XposedHelpers.findClass("okhttp3.MediaType", classLoader)
        val actualMediaType = mediaType ?: try {
            XposedHelpers.callStaticMethod(mediaTypeClass, "parse", "application/json; charset=utf-8")
        } catch (_: Throwable) {
            val companion = XposedHelpers.getStaticObjectField(mediaTypeClass, "Companion")
            XposedHelpers.callMethod(companion, "parse", "application/json; charset=utf-8")
        }

        return try {
            XposedHelpers.callStaticMethod(responseBodyClass, "create", actualMediaType, content)
        } catch (_: Throwable) {
            try {
                val companion = XposedHelpers.getStaticObjectField(responseBodyClass, "Companion")
                XposedHelpers.callMethod(companion, "create", actualMediaType, content)
            } catch (_: Throwable) {
                XposedHelpers.callStaticMethod(responseBodyClass, "create", content, actualMediaType)
            }
        }
    }

    private fun createMockJsonResponse(classLoader: ClassLoader, request: Any, jsonStr: String): Any {
        val responseBuilderClass = XposedHelpers.findClass("okhttp3.Response\$Builder", classLoader)
        val protocolClass = XposedHelpers.findClass("okhttp3.Protocol", classLoader)
        val responseBody = createResponseBody(classLoader, null, jsonStr)

        val http11 = XposedHelpers.getStaticObjectField(protocolClass, "HTTP_1_1")
        val builder = responseBuilderClass.getDeclaredConstructor().newInstance()
        XposedHelpers.callMethod(builder, "request", request)
        XposedHelpers.callMethod(builder, "protocol", http11)
        XposedHelpers.callMethod(builder, "code", 200)
        XposedHelpers.callMethod(builder, "message", "OK")
        XposedHelpers.callMethod(builder, "body", responseBody)
        return XposedHelpers.callMethod(builder, "build")
    }
}
