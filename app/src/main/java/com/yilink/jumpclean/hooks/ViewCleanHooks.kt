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

object ViewCleanHooks {

    private var lastMainLayoutTime = 0L
    private var lastDetailLayoutTime = 0L

    fun hook(lpparam: XC_LoadPackage.LoadPackageParam) {
        hookMainActivityUI(lpparam)
        hookAdViews(lpparam)
        hookContentDetailMemberMask(lpparam)
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

    private fun applyAllUIVisibility(activity: Activity) {
        mapOf(
            "webTab" to JumpConstants.KEY_HIDE_WEB_TAB,
            "lotteryTab" to JumpConstants.KEY_HIDE_LOTTERY_TAB
        ).forEach { (idName, prefKey) ->
            if (ConfigManager.isFeatureEnabled(activity, prefKey)) {
                HookUtils.getCachedResId(activity, idName).takeIf { it != 0 }?.let { id ->
                    activity.findViewById<View>(id)?.let { HookUtils.hidePersistently(it) }
                }
            }
        }
        if (ConfigManager.isFeatureEnabled(activity, JumpConstants.KEY_HIDE_LOTTERY_TAB)) {
            HookUtils.getCachedResId(activity, "vRedDot").takeIf { it != 0 }?.let { id ->
                activity.findViewById<View>(id)?.visibility = View.GONE
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
        targets.forEach { (idName, prefKey) ->
            if (ConfigManager.isFeatureEnabled(activity, prefKey)) {
                HookUtils.getCachedResId(activity, idName).takeIf { it != 0 }?.let { activeTargetIds.add(it) }
            }
        }

        if (activeTargetIds.isNotEmpty()) {
            activity.window?.decorView?.let { decorView ->
                collapseTargetViews(decorView, activeTargetIds)
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

        if (ConfigManager.isFeatureEnabled(activity, JumpConstants.KEY_HIDE_MEMBER_CARD)) {
            val buyBtnId = HookUtils.getCachedResId(activity, "tvBuy")
            if (buyBtnId != 0) {
                activity.findViewById<View>(buyBtnId)?.let { buyBtn ->
                    (buyBtn.parent as? View)?.let { memberContainer ->
                        HookUtils.collapseView(memberContainer)
                    }
                }
            }
        }
    }

    private fun collapseTargetViews(view: View, targetIds: Set<Int>) {
        if (targetIds.contains(view.id)) {
            HookUtils.collapseView(view)
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                collapseTargetViews(view.getChildAt(i), targetIds)
            }
        }
    }

    private fun hookAdViews(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val nativeAdClass = XposedHelpers.findClassIfExists("com.qq.e.ads.nativ.widget.NativeAdContainer", lpparam.classLoader) ?: return

            val collapseAdAction: (View) -> Unit = { view ->
                if (ConfigManager.isFeatureEnabled(view.context, JumpConstants.KEY_HIDE_POST_AD)) {
                    HookUtils.collapseView(view)
                }
            }

            XposedBridge.hookAllConstructors(nativeAdClass, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val view = param.thisObject as? View ?: return
                        collapseAdAction(view)
                    } catch (e: Exception) {
                        ConfigManager.logError("NativeAdContainer 构造拦截异常", e)
                    }
                }
            })

            XposedHelpers.findAndHookMethod(View::class.java, "onAttachedToWindow", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val view = param.thisObject as? View ?: return
                        if (nativeAdClass.isInstance(view)) {
                            collapseAdAction(view)
                        }
                    } catch (_: Exception) {}
                }
            })

            XposedBridge.hookAllMethods(nativeAdClass, "setVisibility", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        val view = param.thisObject as? View ?: return
                        if (ConfigManager.isFeatureEnabled(view.context, JumpConstants.KEY_HIDE_POST_AD)) {
                            param.args[0] = View.GONE
                            collapseAdAction(view)
                        }
                    } catch (e: Exception) {
                        ConfigManager.logError("NativeAdContainer setVisibility 拦截异常", e)
                    }
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
                                applyContentDetailMemberMaskHide(activity)
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

    private fun applyContentDetailMemberMaskHide(activity: Activity) {
        if (!ConfigManager.isFeatureEnabled(activity, JumpConstants.KEY_HIDE_CONTENT_MEMBER_MASK)) return
        try {
            val targetMaskNames = listOf(
                "clMemberMask",
                "llMemberTry",
                "vMemberMask",
                "vMemberChildMask",
                "ivMemberMask",
                "clMemberContainer"
            )

            for (idName in targetMaskNames) {
                val resId = HookUtils.getCachedResId(activity, idName)
                if (resId != 0) {
                    activity.findViewById<View>(resId)?.let { HookUtils.collapseView(it) }
                }
            }
        } catch (e: Exception) {
            ConfigManager.logError("会员遮罩隐藏失败", e)
        }
    }
}
