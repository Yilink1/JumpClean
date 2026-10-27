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

/** 主页净化：MainActivity UI 显隐 + 底栏/红点拦截 + 广告视图折叠 */
object HomeHooks {

    private var lastMainLayoutTime = 0L

    @Volatile private var cachedRedDotId = 0
    @Volatile private var cachedLotteryTabId = 0
    @Volatile private var cachedWebTabId = 0
    @Volatile private var lotteryTabWasHidden = false

    fun hook(classLoader: ClassLoader) {
        hookMainActivityUI(classLoader)
        hookTabVisibilityIntercept(classLoader)
        hookAdViews(classLoader)
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
                GameDetailHooks.applyGameDetailClean(activity)
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
                    HookUtils.applyTargetViewsVisibility(decorView, activeTargetIds, restoreTargetIds)
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
}
