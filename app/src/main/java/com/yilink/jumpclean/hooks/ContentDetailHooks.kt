package com.yilink.jumpclean.hooks

import com.yilink.jumpclean.XposedCompat
import com.yilink.jumpclean.XposedCompat.MethodHookParam

import android.app.Activity
import android.os.SystemClock
import android.view.View
import com.yilink.jumpclean.HookUtils
import com.yilink.jumpclean.config.ConfigManager
import com.yilink.jumpclean.config.JumpConstants

/** 内容详情页：会员内容遮罩净化 */
object ContentDetailHooks {

    private var lastDetailLayoutTime = 0L

    fun hook(classLoader: ClassLoader) {
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
}