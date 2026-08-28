package com.yilink.jumpclean.ad

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.SparseArray
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.yilink.jumpclean.HookUtils
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.io.File
import java.util.ArrayList
import java.util.function.Function

/**
 * JumpClean — Jump App 界面净化与体验增强模块（极限性能单点直击版）
 */
object JumpAdHooks {

    // ==================== 常量 ====================

    private const val PREFS_NAME = "jumpclean_settings"
    private const val TAG = "JumpClean"

    private const val SETTING_ITEM_TITLE = "JumpClean 设置"

    // 自重启标志 Extra Key
    private const val EXTRA_ICON_RESTART = "jumpclean_icon_restart"

    // 功能开关 Key
    private const val KEY_SKIP_SPLASH = "skip_splash"
    private const val KEY_HIDE_BANNER = "hide_banner"
    private const val KEY_HIDE_TOPIC_LIST = "hide_topic_list"
    private const val KEY_HIDE_HOT_DISCUSS = "hide_hot_discuss"
    private const val KEY_HIDE_PUBLISH_TOPIC = "hide_publish_topic"
    private const val KEY_HIDE_POST_AD = "hide_post_ad"
    private const val KEY_HIDE_DISCOVER_TOP_AD = "hide_discover_top_ad"
    private const val KEY_HIDE_DISCOVER_BANNER = "hide_discover_banner"
    private const val KEY_HIDE_PHOTO_WALL = "hide_photo_wall"
    private const val KEY_HIDE_MEMBER_CARD = "hide_member_card"
    private const val KEY_HIDE_MY_ORDER = "hide_my_order"
    private const val KEY_HIDE_WEB_TAB = "hide_web_tab"
    private const val KEY_HIDE_LOTTERY_TAB = "hide_lottery_tab"
    private const val KEY_HIDE_MSG_PUSH_GUIDE = "hide_msg_push_guide"
    private const val KEY_HIDE_WIDGET_VIP_TAG = "hide_widget_vip_tag"
    private const val KEY_ENABLE_COPY = "enable_article_copy"
    private const val KEY_HIDE_CONTENT_MEMBER_MASK = "hide_content_member_mask"
    private const val KEY_BLOCK_CLIPBOARD = "block_clipboard"
    private const val KEY_ENABLE_DEBUG_LOG = "enable_debug_log"

    // Byazt SDK 内部常量
    private const val KEY_EVENT_CODE = -0x5f5e0f3
    private const val KEY_CLASS_TYPE = -0x5f5e0f1
    private const val EVENT_LOAD_FAIL = 0x1bdb7
    private const val KEY_ERROR_CODE = 0x40359
    private const val KEY_ERROR_MSG = 0x4035a

    // 延迟压缩参数
    private const val STARTUP_WINDOW_MS = 6000L
    private const val MIN_DELAY_TO_COMPRESS = 400L
    private const val COMPRESSED_DELAY = 0L

    // View 隐藏重试
    private val RETRY_DELAYS_MS = longArrayOf(500L, 1500L, 3000L)
    private const val THROTTLE_INTERVAL_MS = 50L

    // ==================== 广告 Layout 资源黑名单 ====================

    private val POST_AD_LAYOUT_NAMES = setOf(
        "content_list_ad_sdk_item",
        "content_list_ad_steam_price_item",
        "content_list_ad_lottery_item",
        "content_list_waterfall_ad_sdk_item",
        "content_list_waterfall_ad_lottery_item",
        "content_list_waterfall_ad_steam_price_item"
    )

    // ==================== 21 组物理级精准图标映射 ====================

    private data class JumpIconModel(
        val name: String,
        val drawableName: String,
        val targetClass: String,
        val shortKey: String
    )

    private val OFFICIAL_ICONS = listOf(
        JumpIconModel("默认", "member_change_icon_default", "com.vgjump.jump.ui.main.launch.SplashActivity", "launch_alias_default"),
        JumpIconModel("会员", "member_change_icon_plus", "com.vgjump.jump.icon_plus", "launch_alias_plus"),
        JumpIconModel("深色", "member_change_icon_dark", "com.vgjump.jump.icon_dark", "launch_alias_dark"),
        JumpIconModel("红白", "member_change_icon_white", "com.vgjump.jump.icon_white", "launch_alias_white"),
        JumpIconModel("黑白", "member_change_icon_black", "com.vgjump.jump.icon_black", "launch_alias_black"),
        JumpIconModel("J+", "member_change_icon_default_j", "com.vgjump.jump.default_j", "launch_alias_default_j"),
        JumpIconModel("深色 J+", "member_change_icon_dark_j", "com.vgjump.jump.icon_dark_j", "launch_alias_dark_j"),
        JumpIconModel("红白 J+", "member_change_icon_white_j", "com.vgjump.jump.white_j", "launch_alias_white_j"),
        JumpIconModel("黑白 J+", "member_change_icon_black_j", "com.vgjump.jump.icon_black_j", "launch_alias_black_j"),
        JumpIconModel("Golden Hour", "member_change_icon_golden_hour", "com.vgjump.jump.icon_golden_hour", "launch_alias_golden_hour"),
        JumpIconModel("黎明", "member_change_icon_dawn", "com.vgjump.jump.icon_dawn", "launch_alias_dawn"),
        JumpIconModel("晌午", "member_change_icon_noon", "com.vgjump.jump.icon_noon", "launch_alias_noon"),
        JumpIconModel("午夜", "member_change_icon_night", "com.vgjump.jump.icon_night", "launch_alias_night"),
        JumpIconModel("夜视", "member_change_icon_night_vision", "com.vgjump.jump.icon_night_vision", "launch_alias_night_vision"),
        JumpIconModel("金属", "member_change_icon_metal", "com.vgjump.jump.icon_metal", "launch_alias_metal"),
        JumpIconModel("多彩", "member_change_icon_colorful", "com.vgjump.jump.icon_colorful", "launch_alias_colorful"),
        JumpIconModel("Switch", "member_change_icon_switch", "com.vgjump.jump.icon_switch", "launch_alias_switch"),
        JumpIconModel("NES", "member_change_icon_nes", "com.vgjump.jump.icon_nes", "launch_alias_nes"),
        JumpIconModel("PS2", "member_change_icon_ps2", "com.vgjump.jump.icon_ps2", "launch_alias_ps2"),
        JumpIconModel("XBOX", "member_change_icon_xbox", "com.vgjump.jump.icon_xbox", "launch_alias_xbox"),
        JumpIconModel("DC", "member_change_icon_dc", "com.vgjump.jump.icon_dc", "launch_alias_dc")
    )

    // ==================== 运行时状态 ====================

    private val resIdCache = mutableMapOf<String, Int>()
    private var processStartTime = 0L
    private var mainActivitySeen = false

    private var lastMainLayoutTime = 0L
    private var lastDetailLayoutTime = 0L

    @Volatile
    private var currentActivityName = ""
    private var targetClassLoader: ClassLoader? = null

    // ==================== 入口 ====================

    fun hook(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != "com.vgjump.jump") return

        targetClassLoader = lpparam.classLoader
        processStartTime = SystemClock.uptimeMillis()
        logInit("JumpClean 开始加载")

        hookFrameworkLayer(lpparam)
        hookDataLayer(lpparam)
        hookAppLayer(lpparam)
        hookViewLayer(lpparam)
        hookSettingsEntry(lpparam)
        hookSettingActivityEntry(lpparam)

        logInit("JumpClean 加载完成")
    }

    // ============================================================
    // 第 1 层：框架与开屏加速层
    // ============================================================

    private fun hookFrameworkLayer(lpparam: XC_LoadPackage.LoadPackageParam) {
        hookSplashInstantJump(lpparam)
        hookByaztFastFail(lpparam)
        hookSplashAdBase(lpparam)
        hookStartupDelayCompress(lpparam)
        hookActivityFlowProbe()
        hookBlockClipboard(lpparam)
        hookFakeNotificationPermission(lpparam)
    }

    private fun hookSplashInstantJump(lpparam: XC_LoadPackage.LoadPackageParam) {
        val splashClass = XposedHelpers.findClassIfExists(
            "com.vgjump.jump.ui.main.launch.SplashActivity", lpparam.classLoader
        ) ?: return

        XposedBridge.hookAllMethods(splashClass, "onCreate", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val activity = param.thisObject as? Activity ?: return
                val isRestartFromIcon = activity.intent?.getBooleanExtra(EXTRA_ICON_RESTART, false) == true
                val isSkipEnabled = isFeatureEnabledSafe(lpparam.classLoader, KEY_SKIP_SPLASH)

                if (isRestartFromIcon || isSkipEnabled) {
                    try {
                        val mainIntent = Intent().apply {
                            setClassName(activity.packageName, "com.vgjump.jump.ui.main.MainActivity")
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        }
                        activity.startActivity(mainIntent)
                        activity.finish()
                        log("✔ 瞬时穿透 SplashActivity 成功")
                    } catch (e: Exception) {
                        logError("瞬跳 MainActivity 失败", e)
                    }
                }
            }
        })
        logInit("✔ SplashActivity 穿透 Hook 已就绪")
    }

    private fun hookByaztFastFail(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val mgClass = XposedHelpers.findClassIfExists("com.byazt.oy.mg", lpparam.classLoader) ?: return
            XposedBridge.hookAllMethods(mgClass, "loadAdByType", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!isFeatureEnabledSafe(lpparam.classLoader, KEY_SKIP_SPLASH)) return
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
                        logError("Byazt 请求级拦截异常", e)
                    }
                }
            })
            logInit("✔ Byazt 开屏请求快速阻断 Hook 已安装")
        } catch (e: Exception) {
            logError("✘ Byazt 请求级 Hook 失败", e)
        }
    }

    private fun dispatchByaztSplashLoadFail(callback: Any): Boolean {
        return try {
            val errorFunction = Function<Any?, Any?> {
                SparseArray<Any>().apply {
                    put(KEY_ERROR_CODE, -1)
                    put(KEY_ERROR_MSG, "Splash ad blocked")
                }
            }
            val event = SparseArray<Any>(3).apply {
                put(0, errorFunction)
                put(KEY_EVENT_CODE, EVENT_LOAD_FAIL)
                put(KEY_CLASS_TYPE, Void::class.java)
            }
            when (callback) {
                is Function<*, *> -> @Suppress("UNCHECKED_CAST") (callback as Function<Any, Any?>).apply(event)
                else -> XposedHelpers.callMethod(callback, "apply", event)
            }
            true
        } catch (e: Exception) {
            logError("Byazt 失败回调派发异常", e)
            false
        }
    }

    private fun hookSplashAdBase(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val splashBase = XposedHelpers.findClassIfExists("com.byazt.se.a", lpparam.classLoader) ?: return

            XposedBridge.hookAllMethods(splashBase, "apply", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!isFeatureEnabledSafe(lpparam.classLoader, KEY_SKIP_SPLASH)) return
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
            logInit("✔ Byazt 开屏渲染基类 Hook 已安装")
        } catch (e: Exception) {
            logError("✘ Byazt 渲染基类 Hook 失败", e)
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
                        if (!isFeatureEnabledSafe(lpparam.classLoader, KEY_SKIP_SPLASH)) return
                        try {
                            val delay = param.args[1] as? Long ?: return
                            val sinceStart = if (processStartTime > 0L)
                                SystemClock.uptimeMillis() - processStartTime else -1L

                            if (sinceStart in 0L..STARTUP_WINDOW_MS
                                && !mainActivitySeen
                                && delay >= MIN_DELAY_TO_COMPRESS
                                && Looper.myLooper() == Looper.getMainLooper()
                            ) {
                                param.args[1] = COMPRESSED_DELAY
                            }
                        } catch (e: Exception) {
                            logError("延迟压缩异常", e)
                        }
                    }
                }
            )
            logInit("✔ 启动延迟瞬时压缩 Hook 已安装")
        } catch (e: Exception) {
            logError("✘ 启动延迟压缩 Hook 失败", e)
        }
    }

    private fun hookActivityFlowProbe() {
        try {
            XposedBridge.hookAllMethods(Activity::class.java, "onCreate", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val act = param.thisObject as? Activity ?: return
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
                        currentActivityName = act.javaClass.name
                    } catch (_: Exception) {}
                }
            })
        } catch (e: Exception) {
            logError("✘ Activity 生命周期探针失败", e)
        }
    }

    private fun hookBlockClipboard(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val clipboardManagerClass = XposedHelpers.findClass("android.content.ClipboardManager", lpparam.classLoader)
            val blockHook = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (isFeatureEnabledSafe(lpparam.classLoader, KEY_BLOCK_CLIPBOARD)) {
                        param.result = null
                    }
                }
            }
            XposedHelpers.findAndHookMethod(clipboardManagerClass, "getPrimaryClip", blockHook)
            XposedHelpers.findAndHookMethod(clipboardManagerClass, "getText", blockHook)
            XposedHelpers.findAndHookMethod(clipboardManagerClass, "getPrimaryClipDescription", blockHook)
            XposedHelpers.findAndHookMethod(clipboardManagerClass, "hasPrimaryClip", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (isFeatureEnabledSafe(lpparam.classLoader, KEY_BLOCK_CLIPBOARD)) {
                        param.result = false
                    }
                }
            })
            logInit("✔ 剪贴板读保护 Hook 已安装")
        } catch (e: Exception) {
            logError("✘ 剪贴板保护 Hook 失败", e)
        }
    }

    private fun hookFakeNotificationPermission(lpparam: XC_LoadPackage.LoadPackageParam) {
        val returnTrueHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (isFeatureEnabledSafe(lpparam.classLoader, KEY_HIDE_MSG_PUSH_GUIDE)) {
                    param.result = true
                }
            }
        }

        try {
            val compatClass = XposedHelpers.findClassIfExists("androidx.core.app.NotificationManagerCompat", lpparam.classLoader)
            if (compatClass != null) {
                XposedHelpers.findAndHookMethod(compatClass, "areNotificationsEnabled", returnTrueHook)
                logInit("✔ NotificationManagerCompat 权限伪造 Hook 已安装")
            }
        } catch (e: Exception) {
            logError("NotificationManagerCompat Hook 失败", e)
        }

        try {
            val nmClass = XposedHelpers.findClassIfExists("android.app.NotificationManager", lpparam.classLoader)
            if (nmClass != null) {
                XposedHelpers.findAndHookMethod(nmClass, "areNotificationsEnabled", returnTrueHook)
                logInit("✔ 原生 NotificationManager 权限伪造 Hook 已安装")
            }
        } catch (e: Exception) {
            logError("原生 NotificationManager Hook 失败", e)
        }
    }

    // ============================================================
    // 第 2 层：数据/渲染层 Hook（单点直击 Header 与信息流广告）
    // ============================================================

    private fun hookDataLayer(lpparam: XC_LoadPackage.LoadPackageParam) {
        hookBrvAdapters(lpparam)
    }

    private fun findBrvAdapterClass(classLoader: ClassLoader): Class<*>? {
        val knownNames = listOf("com.drake.brv.b", "zn0", "yn0", "xn0", "wn0")
        for (name in knownNames) {
            try {
                val cls = XposedHelpers.findClassIfExists(name, classLoader) ?: continue
                return cls
            } catch (_: Exception) {}
        }
        return null
    }

    /**
     * 单点直击：在 BRV 绑定 View 的一瞬间完成广告折叠，零全局遍历，零多余开销
     */
    private fun hookBrvAdapters(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val adapterClass = findBrvAdapterClass(lpparam.classLoader)
            if (adapterClass == null) {
                logError("BRV Adapter Hook 失败：未找到 Adapter 基类")
                return
            }

            val onBindHook = object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val holder = param.args.getOrNull(0) ?: return
                        val itemView = XposedHelpers.getObjectField(holder, "itemView") as? View ?: return
                        val context = itemView.context ?: return

                        val itemViewType = try {
                            XposedHelpers.callMethod(holder, "getItemViewType") as? Int
                        } catch (_: Exception) {
                            null
                        } ?: return

                        val resName = try {
                            context.resources.getResourceEntryName(itemViewType)
                        } catch (_: Exception) {
                            null
                        } ?: return

                        // 1. 首页顶部 Header 绑定瞬间：精准折叠内部的广告 RelativeLayout(R.id.banner)，保留话题栏
                        if (resName.contains("general_interest_home_header")) {
                            if (isFeatureEnabledSafe(lpparam.classLoader, KEY_HIDE_BANNER)) {
                                val bannerId = getCachedResId(context, "banner")
                                if (bannerId != 0) {
                                    itemView.findViewById<View>(bannerId)?.let { bannerView ->
                                        if (bannerView.visibility != View.GONE) {
                                            collapseView(bannerView)
                                            log("✔ [首页顶部 Header] 单点折叠广告 banner 完成（保留话题栏且无白框）")
                                        }
                                    }
                                }
                            }
                        }

                        // 2. 信息流推荐内嵌广告：精准折叠对应广告 Item
                        if (isFeatureEnabledSafe(lpparam.classLoader, KEY_HIDE_POST_AD) && resName in POST_AD_LAYOUT_NAMES) {
                            log("✔ [信息流广告] 成功命中并折叠 Layout: $resName")
                            collapseView(itemView)
                            (itemView.parent as? View)?.requestLayout()
                        }
                    } catch (e: Exception) {
                        logError("BRV onBindViewHolder 过滤异常", e)
                    }
                }
            }

            XposedBridge.hookAllMethods(adapterClass, "onBindViewHolder", onBindHook)
            logInit("✔ BRV 极速单点广告过滤 Hook 已就绪")
        } catch (e: Exception) {
            logError("✘ BRV Adapter Hook 失败", e)
        }
    }

    // ============================================================
    // 第 3 层：应用层 Hook
    // ============================================================

    private fun hookAppLayer(lpparam: XC_LoadPackage.LoadPackageParam) {
        hookArticleCopy(lpparam)
        hookNativeTextCopy(lpparam)
    }

    private fun shouldInjectCopyJs(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val u = url.lowercase()
        if (u.startsWith("javascript:") || u.startsWith("about:") || u.startsWith("data:")) return false
        return u.contains("jumprichtext") || u.contains("jump-game.com") ||
                u.contains("wk.jump-game.com") || u.contains("richtext") ||
                u.startsWith("file:///storage/emulated/0/android/data/com.vgjump.jump/")
    }

    private fun hookArticleCopy(lpparam: XC_LoadPackage.LoadPackageParam) {
        val unlockJs = """
            (function() {
                try {
                    if (window.__jump_unlock_installed__) return;
                    window.__jump_unlock_installed__ = true;
                    function applyStyle() {
                        try {
                            var id = '__jump_unlock_style__';
                            var css = '* { -webkit-user-select: text !important; user-select: text !important; } html, body { -webkit-user-select: text !important; user-select: text !important; }';
                            var style = document.getElementById(id);
                            if (!style) { style = document.createElement('style'); style.id = id; style.type = 'text/css'; (document.head || document.documentElement).appendChild(style); }
                            if (style.textContent !== css) style.textContent = css;
                            document.oncopy = null; document.oncut = null; document.onselectstart = null; document.oncontextmenu = null;
                            if (document.body) { document.body.oncopy = null; document.body.oncut = null; document.body.onselectstart = null; document.body.oncontextmenu = null; }
                        } catch(e) {}
                    }
                    try {
                        ['copy', 'cut', 'selectstart', 'contextmenu'].forEach(function(evt) {
                            document.addEventListener(evt, function(e) { try { e.stopPropagation(); } catch(_e) {} }, true);
                            window.addEventListener(evt, function(e) { try { e.stopPropagation(); } catch(_e) {} }, true);
                        });
                    } catch(e) {}
                    applyStyle();
                    try { setInterval(applyStyle, 1000); } catch(e) {}
                    try {
                        if (window.MutationObserver && document.documentElement) {
                            new MutationObserver(function() { applyStyle(); }).observe(document.documentElement, {
                                childList: true, subtree: true, attributes: true, attributeFilter: ['style', 'class']
                            });
                        }
                    } catch(e) {}
                } catch(e) {}
            })();
        """.trimIndent()

        fun inject(webView: Any) {
            listOf(300L, 1200L).forEach { delay ->
                try {
                    XposedHelpers.callMethod(webView, "postDelayed", Runnable {
                        try {
                            XposedHelpers.callMethod(webView, "evaluateJavascript", unlockJs, null)
                        } catch (e: Exception) {
                            logError("WebView JS 注入失败", e)
                        }
                    }, delay)
                } catch (e: Exception) {
                    logError("WebView postDelayed 调度失败", e)
                }
            }
        }

        try {
            val dWebViewClass = XposedHelpers.findClassIfExists("com.vgjump.jump.basic.jsbridge.DWebView", lpparam.classLoader)
            if (dWebViewClass != null) {
                XposedBridge.hookAllMethods(dWebViewClass, "d", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val firstArg = param.args.getOrNull(0)?.toString() ?: ""
                            if (firstArg.contains("registerArticleDataRes") && isFeatureEnabledSafe(lpparam.classLoader, KEY_ENABLE_COPY)) {
                                inject(param.thisObject)
                            }
                        } catch (e: Exception) {
                            logError("DWebView.d 回调异常", e)
                        }
                    }
                })
                XposedBridge.hookAllMethods(dWebViewClass, "loadUrl", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val url = param.args.getOrNull(0) as? String ?: return
                            if (isFeatureEnabledSafe(lpparam.classLoader, KEY_ENABLE_COPY) && shouldInjectCopyJs(url)) {
                                inject(param.thisObject)
                            }
                        } catch (e: Exception) {
                            logError("DWebView.loadUrl 回调异常", e)
                        }
                    }
                })
            }

            val webViewClass = XposedHelpers.findClassIfExists("android.webkit.WebView", lpparam.classLoader)
            if (webViewClass != null) {
                XposedBridge.hookAllMethods(webViewClass, "loadUrl", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val url = param.args.getOrNull(0) as? String ?: return
                        if (isFeatureEnabledSafe(lpparam.classLoader, KEY_ENABLE_COPY) && shouldInjectCopyJs(url)) {
                            inject(param.thisObject)
                        }
                    }
                })
                XposedBridge.hookAllMethods(webViewClass, "setLongClickable", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (isFeatureEnabledSafe(lpparam.classLoader, KEY_ENABLE_COPY)) {
                            param.args[0] = true
                        }
                    }
                })
            }
            logInit("✔ WebView 文章复制解锁 Hook 已安装")
        } catch (e: Exception) {
            logError("✘ WebView 文章复制解锁 Hook 失败", e)
        }
    }

    private fun isTargetTextView(tv: TextView?): Boolean {
        if (tv == null) return false
        val text = tv.text?.toString()?.trim() ?: return false
        if (text.length < 10) return false
        val act = currentActivityName
        val cls = tv.javaClass.name
        if (act.contains("GameDetailActivity")) {
            return cls.contains("MyExpandableTextView") && text.length >= 10
        }
        if (act.contains("ContentDetailActivity")) {
            return cls.contains("LineHeightTextView") || cls.contains("MyExpandableTextView")
        }
        return false
    }

    private fun enableTextCopy(tv: TextView?, classLoader: ClassLoader) {
        if (!isFeatureEnabledSafe(classLoader, KEY_ENABLE_COPY) || tv == null) return
        try {
            if (isTargetTextView(tv)) {
                if (tv.isTextSelectable && tv.isLongClickable) return
                tv.setTextIsSelectable(true)
                tv.isLongClickable = true
                tv.isFocusable = true
                tv.isFocusableInTouchMode = true
                tv.customSelectionActionModeCallback = null
            } else {
                if (tv.isTextSelectable) {
                    tv.setTextIsSelectable(false)
                    tv.isLongClickable = false
                }
            }
        } catch (e: Exception) {
            logError("TextView 文本选择设置失败", e)
        }
    }

    private fun hookNativeTextCopy(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            XposedHelpers.findAndHookMethod(
                TextView::class.java, "setText",
                CharSequence::class.java, TextView.BufferType::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        enableTextCopy(param.thisObject as? TextView, lpparam.classLoader)
                    }
                }
            )
            XposedHelpers.findAndHookMethod(View::class.java, "onAttachedToWindow", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    enableTextCopy(param.thisObject as? TextView, lpparam.classLoader)
                }
            })
            logInit("✔ 原生 TextView 复制解锁 Hook 已安装")
        } catch (e: Exception) {
            logError("✘ 原生 TextView 复制解锁 Hook 失败", e)
        }
    }

    // ============================================================
    // 第 4 层：View 层 UI 净化（极简扁平匹配，绝不挂多余布局监听）
    // ============================================================

    private fun hookViewLayer(lpparam: XC_LoadPackage.LoadPackageParam) {
        hookMainActivityUI(lpparam)
        hookAdViews(lpparam)
        hookContentDetailMemberMask(lpparam)
        hookContentDetailScrollUnlock(lpparam)
    }

    private fun hookMainActivityUI(lpparam: XC_LoadPackage.LoadPackageParam) {
        val mainActivityClass = XposedHelpers.findClassIfExists("com.vgjump.jump.ui.main.MainActivity", lpparam.classLoader) ?: return
        try {
            XposedHelpers.findAndHookMethod(mainActivityClass, "onCreate",
                android.os.Bundle::class.java, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val activity = param.thisObject as Activity
                            applyAllUIVisibility(activity)

                            activity.window?.decorView?.rootView?.viewTreeObserver
                                ?.addOnGlobalLayoutListener {
                                    val now = SystemClock.uptimeMillis()
                                    if (now - lastMainLayoutTime >= THROTTLE_INTERVAL_MS) {
                                        lastMainLayoutTime = now
                                        applyAllUIVisibility(activity)
                                    }
                                }
                        } catch (e: Exception) {
                            logError("MainActivity onCreate UI 净化异常", e)
                        }
                    }
                }
            )
            logInit("✔ 首页 UI 净化 Hook 已安装")
        } catch (e: Exception) {
            logError("✘ 首页 UI 净化 Hook 失败", e)
        }
    }

    private fun applyAllUIVisibility(activity: Activity) {
        mapOf("webTab" to KEY_HIDE_WEB_TAB, "lotteryTab" to KEY_HIDE_LOTTERY_TAB).forEach { (idName, prefKey) ->
            if (isFeatureEnabled(activity, prefKey)) {
                getCachedResId(activity, idName).takeIf { it != 0 }?.let { id ->
                    activity.findViewById<View>(id)?.let { hidePersistently(it) }
                }
            }
        }
        if (isFeatureEnabled(activity, KEY_HIDE_LOTTERY_TAB)) {
            getCachedResId(activity, "vRedDot").takeIf { it != 0 }?.let { id ->
                activity.findViewById<View>(id)?.visibility = View.GONE
            }
        }

        val targets = mapOf(
            "rvOpt" to KEY_HIDE_TOPIC_LIST,
            "ivPublishTopic" to KEY_HIDE_PUBLISH_TOPIC,
            "clPhotoWall" to KEY_HIDE_PHOTO_WALL,
            "clContent" to KEY_HIDE_MEMBER_CARD,
            "vColorRVTop" to KEY_HIDE_MEMBER_CARD,
            "vBlackRVTop" to KEY_HIDE_MEMBER_CARD,
            "tvMyOrder" to KEY_HIDE_MY_ORDER,
            "tvMyOrderToolbar" to KEY_HIDE_MY_ORDER,
            "rvOPT" to KEY_HIDE_DISCOVER_TOP_AD,
            "adBanner" to KEY_HIDE_DISCOVER_BANNER,
            "ivTag" to KEY_HIDE_WIDGET_VIP_TAG
        )

        val activeTargetIds = ArrayList<Int>()
        targets.forEach { (idName, prefKey) ->
            if (isFeatureEnabled(activity, prefKey)) {
                getCachedResId(activity, idName).takeIf { it != 0 }?.let { activeTargetIds.add(it) }
            }
        }

        if (activeTargetIds.isNotEmpty()) {
            activity.window?.decorView?.let { decorView ->
                collapseTargetViews(decorView, activeTargetIds)
            }
        }

        if (isFeatureEnabled(activity, KEY_HIDE_HOT_DISCUSS)) {
            hideTextItem(activity, "查看所有话题")
        }
    }

    private fun collapseTargetViews(view: View, targetIds: ArrayList<Int>) {
        if (targetIds.contains(view.id)) {
            collapseView(view)
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
                if (isFeatureEnabled(view.context, KEY_HIDE_POST_AD)) {
                    collapseView(view)
                }
            }

            XposedBridge.hookAllConstructors(nativeAdClass, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val view = param.thisObject as? View ?: return
                        collapseAdAction(view)
                    } catch (e: Exception) {
                        logError("NativeAdContainer 构造拦截异常", e)
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
                        if (isFeatureEnabled(view.context, KEY_HIDE_POST_AD)) {
                            param.args[0] = View.GONE
                            collapseAdAction(view)
                        }
                    } catch (e: Exception) {
                        logError("NativeAdContainer setVisibility 拦截异常", e)
                    }
                }
            })
            logInit("✔ 通用广告容器（首页推荐流 + 帖子内嵌）Hook 已安装")
        } catch (e: Exception) {
            logError("✘ 通用广告容器 Hook 失败", e)
        }
    }

    private fun hookContentDetailMemberMask(lpparam: XC_LoadPackage.LoadPackageParam) {
        val clazz = XposedHelpers.findClassIfExists("com.vgjump.jump.ui.content.detail.ContentDetailActivity", lpparam.classLoader) ?: return
        try {
            XposedBridge.hookAllMethods(clazz, "onCreate", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val activity = param.thisObject as? Activity ?: return
                        applyContentDetailMemberMaskHide(activity)

                        activity.window?.decorView?.rootView?.viewTreeObserver
                            ?.addOnGlobalLayoutListener {
                                val now = SystemClock.uptimeMillis()
                                if (now - lastDetailLayoutTime >= THROTTLE_INTERVAL_MS) {
                                    lastDetailLayoutTime = now
                                    applyContentDetailMemberMaskHide(activity)
                                }
                            }
                    } catch (e: Exception) {
                        logError("ContentDetailActivity onCreate 遮罩隐藏异常", e)
                    }
                }
            })
            XposedBridge.hookAllMethods(clazz, "onResume", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val activity = param.thisObject as? Activity ?: return
                        applyContentDetailMemberMaskHide(activity)
                    } catch (e: Exception) {
                        logError("ContentDetailActivity onResume 遮罩隐藏异常", e)
                    }
                }
            })
            logInit("✔ 会员遮罩隐藏 Hook 已安装")
        } catch (e: Exception) {
            logError("✘ 会员遮罩隐藏 Hook 失败", e)
        }
    }

    private fun applyContentDetailMemberMaskHide(activity: Activity) {
        if (!isFeatureEnabled(activity, KEY_HIDE_CONTENT_MEMBER_MASK)) return
        try {
            val decorView = activity.window?.decorView ?: return
            val targetIds = ArrayList<Int>()
            listOf("llMemberTry", "vMemberMask", "vMemberChildMask", "ivMemberMask", "clMemberContainer").forEach { idName ->
                getCachedResId(activity, idName).takeIf { it != 0 }?.let { targetIds.add(it) }
            }
            if (targetIds.isNotEmpty()) collapseTargetViews(decorView, targetIds)
            releaseContentDetailTouchBlockers(decorView)
        } catch (e: Exception) {
            logError("会员遮罩隐藏失败", e)
        }
    }

    private fun releaseContentDetailTouchBlockers(root: View) {
        try {
            releaseContentDetailTouchBlockersRecursive(root)
        } catch (e: Exception) {
            logError("触摸拦截释放失败", e)
        }
    }

    private fun releaseContentDetailTouchBlockersRecursive(view: View) {
        try {
            val idName = try {
                if (view.id != View.NO_ID) view.resources.getResourceEntryName(view.id) else ""
            } catch (_: Exception) { "" }
            val cls = view.javaClass.name
            val visible = view.visibility == View.VISIBLE

            val nameLooksMember = idName.contains("member", true) ||
                    idName.contains("mask", true) || idName.contains("vip", true) ||
                    idName.contains("try", true) || cls.contains("BlurView", true)

            val looksLikeBottomBlocker = visible &&
                    view.width > 800 && view.height in 100..700 &&
                    view.id == View.NO_ID &&
                    (cls.contains("AppCompatImageView") || cls.contains("ImageView"))

            if (nameLooksMember || looksLikeBottomBlocker) {
                view.isEnabled = false
                view.isClickable = false
                view.isLongClickable = false
                view.isFocusable = false
                view.isFocusableInTouchMode = false
                collapseView(view)
            }

            if (view is ViewGroup) {
                view.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
                for (i in 0 until view.childCount) {
                    releaseContentDetailTouchBlockersRecursive(view.getChildAt(i))
                }
            }
        } catch (_: Exception) {}
    }

    private fun hookContentDetailScrollUnlock(lpparam: XC_LoadPackage.LoadPackageParam) {
        val scrollClasses = listOf(
            "androidx.recyclerview.widget.RecyclerView",
            "androidx.core.widget.NestedScrollView",
            "androidx.viewpager2.widget.ViewPager2"
        )
        scrollClasses.forEach { className ->
            try {
                val clazz = XposedHelpers.findClassIfExists(className, lpparam.classLoader) ?: return@forEach
                val methodName = if (className.contains("ViewPager2")) "setUserInputEnabled" else "setNestedScrollingEnabled"
                XposedBridge.hookAllMethods(clazz, methodName, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            if (!currentActivityName.contains("ContentDetailActivity")) return
                            val view = param.thisObject as? View ?: return
                            if (!isFeatureEnabled(view.context, KEY_HIDE_CONTENT_MEMBER_MASK)) return
                            param.args[0] = true
                        } catch (e: Exception) {
                            logError("滚动解锁异常 (${className})", e)
                        }
                    }
                })
            } catch (e: Exception) {
                logError("滚动解锁 Hook 失败 (${className})", e)
            }
        }
        logInit("✔ 详情页滚动解锁 Hook 已安装")
    }

    // ============================================================
    // 第 5 层：入口挂载（原生设置页置顶内嵌 + 长按快捷入口）
    // ============================================================

    private fun hookSettingActivityEntry(lpparam: XC_LoadPackage.LoadPackageParam) {
        val settingActivityClass = XposedHelpers.findClassIfExists(
            "com.vgjump.jump.ui.my.setting.SettingActivity", lpparam.classLoader
        ) ?: return

        val settingItemClass = XposedHelpers.findClassIfExists(
            "com.vgjump.jump.bean.my.SettingItem", lpparam.classLoader
        ) ?: return

        XposedBridge.hookAllMethods(settingActivityClass, "initData", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                try {
                    val activity = param.thisObject as? Activity ?: return
                    val adapter = XposedHelpers.callMethod(activity, "s") ?: return

                    val constructor = settingItemClass.getConstructor(
                        java.lang.Integer::class.java,
                        String::class.java,
                        String::class.java,
                        String::class.java,
                        String::class.java,
                        String::class.java
                    )
                    val customItem = constructor.newInstance(
                        null,
                        SETTING_ITEM_TITLE,
                        "",
                        "",
                        "1",
                        null
                    )

                    @Suppress("UNCHECKED_CAST")
                    val dataList = XposedHelpers.getObjectField(adapter, "f") as? MutableList<Any>
                    if (dataList != null && !dataList.contains(customItem)) {
                        dataList.add(0, customItem)
                        XposedHelpers.callMethod(adapter, "notifyItemInserted", 0)
                    }
                } catch (e: Exception) {
                    logError("向设置页插入条目失败", e)
                }
            }
        })

        val candidateClickListenerNames = listOf(
            "vq", "e92", "d92", "f92", "wq", "xq", "yq", "zq",
            "a83", "b83", "c83", "uq", "tq", "yv2"
        )
        val clickHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                try {
                    val adapter = param.args.getOrNull(0) ?: return
                    val position = param.args.getOrNull(2) as? Int ?: return

                    val dataList = XposedHelpers.getObjectField(adapter, "f") as? List<*> ?: return
                    val clickedItem = dataList.getOrNull(position) ?: return

                    val title = XposedHelpers.callMethod(clickedItem, "getTitle") as? String
                    if (title == SETTING_ITEM_TITLE) {
                        param.result = null
                        val view = param.args.getOrNull(1) as? View
                        val context = view?.context ?: return
                        if (context is Activity) {
                            showFullscreenSettings(context)
                        }
                    }
                } catch (e: Exception) {
                    logError("设置项点击拦截异常", e)
                }
            }
        }

        for (clsName in candidateClickListenerNames) {
            val targetClass = XposedHelpers.findClassIfExists(clsName, lpparam.classLoader)
            if (targetClass != null) {
                try {
                    XposedBridge.hookAllMethods(targetClass, "a", clickHook)
                } catch (_: Exception) {}
            }
        }
    }

    private fun hookSettingsEntry(lpparam: XC_LoadPackage.LoadPackageParam) {
        val mainActivityClass = XposedHelpers.findClassIfExists("com.vgjump.jump.ui.main.MainActivity", lpparam.classLoader) ?: return
        try {
            XposedHelpers.findAndHookMethod(mainActivityClass, "onCreate",
                android.os.Bundle::class.java, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val activity = param.thisObject as Activity
                            getCachedResId(activity, "myTab").takeIf { it != 0 }?.let { id ->
                                activity.findViewById<View>(id)?.setOnLongClickListener {
                                    showFullscreenSettings(activity)
                                    true
                                }
                            }
                        } catch (e: Exception) {
                            logError("设置入口注入异常", e)
                        }
                    }
                }
            )
            logInit("✔ 快捷入口（长按「我的」Tab）已安装")
        } catch (e: Exception) {
            logError("✘ 快捷入口 Hook 失败", e)
        }
    }

    /**
     * 沉浸式全屏设置面板
     */
    @Suppress("DEPRECATION")
    @SuppressLint("SetTextI18n")
    private fun showFullscreenSettings(activity: Activity) {
        val prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        val items = listOf(
            SectionHeader("启动与隐私"),
            SettingItem(KEY_SKIP_SPLASH, "跳过开屏广告"),
            SettingItem(KEY_HIDE_MSG_PUSH_GUIDE, "屏蔽通知开启引导"),
            SettingItem(KEY_BLOCK_CLIPBOARD, "禁止后台读取剪贴板"),

            SectionHeader("首页"),
            SettingItem(KEY_HIDE_TOPIC_LIST, "隐藏顶部话题"),
            SettingItem(KEY_HIDE_BANNER, "隐藏首页轮播广告"),
            SettingItem(KEY_HIDE_HOT_DISCUSS, "隐藏 Jumper 热议"),
            SettingItem(KEY_HIDE_POST_AD, "隐藏推荐流与帖子内嵌广告"),
            SettingItem(KEY_HIDE_PUBLISH_TOPIC, "隐藏发帖按钮"),

            SectionHeader("发现"),
            SettingItem(KEY_HIDE_DISCOVER_TOP_AD, "隐藏顶部广告"),
            SettingItem(KEY_HIDE_DISCOVER_BANNER, "隐藏轮播广告"),

            SectionHeader("内容与详情"),
            SettingItem(KEY_ENABLE_COPY, "解除文本复制限制"),
            SettingItem(KEY_HIDE_CONTENT_MEMBER_MASK, "解锁游戏评价总结"),

            SectionHeader("个人中心"),
            SettingItem(KEY_HIDE_MEMBER_CARD, "隐藏 Jump+ 会员卡片"),
            SettingItem(KEY_HIDE_MY_ORDER, "隐藏我的订单入口"),
            SettingItem(KEY_HIDE_PHOTO_WALL, "隐藏截图展示墙"),

            SectionHeader("底栏与小组件"),
            SettingItem(KEY_HIDE_WEB_TAB, "隐藏底栏「Jump 赏」"),
            SettingItem(KEY_HIDE_LOTTERY_TAB, "隐藏底栏「抽奖 / 全新 App」"),
            SettingItem(KEY_HIDE_WIDGET_VIP_TAG, "隐藏小组件会员标识"),

            SectionHeader("个性化与拓展"),
            SettingItem(KEY_ENABLE_DEBUG_LOG, "开启调试日志", desc = "输出 Hook 安装与广告命中详情至 LSPosed 日志"),
            ActionItem("更换 App 图标", desc = "修复官方遗漏图标，含 21 款") {
                showVisualIconGridPicker(activity)
            }
        )

        val dp = { value: Int -> (value * activity.resources.displayMetrics.density).toInt() }

        val statusBarHeight = run {
            val resourceId = activity.resources.getIdentifier("status_bar_height", "dimen", "android")
            if (resourceId > 0) activity.resources.getDimensionPixelSize(resourceId) else dp(28)
        }

        val isDark = (activity.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES

        val bgColor = if (isDark) Color.parseColor("#121212") else Color.parseColor("#F6F6F6")
        val cardBgColor = if (isDark) Color.parseColor("#1E1E1E") else Color.parseColor("#FFFFFF")
        val primaryTextColor = if (isDark) Color.parseColor("#F5F5F7") else Color.parseColor("#1D1D1F")
        val secondaryTextColor = if (isDark) Color.parseColor("#8E8E93") else Color.parseColor("#86868B")
        val sectionTextColor = if (isDark) Color.parseColor("#AAAAAA") else Color.parseColor("#444444")
        val warnTextColor = Color.parseColor("#FF9800")
        val accentColor = Color.parseColor("#FF5252")
        val dividerColor = if (isDark) Color.parseColor("#2C2C2E") else Color.parseColor("#EFEFEF")

        val dialog = Dialog(activity, android.R.style.Theme_NoTitleBar)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val rootFrame = FrameLayout(activity).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundColor(bgColor)
        }

        val mainLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        val statusBarPlaceholder = View(activity).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, statusBarHeight)
            setBackgroundColor(cardBgColor)
        }
        mainLayout.addView(statusBarPlaceholder)

        val navBar = FrameLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48))
            setBackgroundColor(cardBgColor)
        }

        val backBtn = TextView(activity).apply {
            text = "‹"
            textSize = 34f
            setTextColor(primaryTextColor)
            setPadding(dp(16), 0, dp(16), dp(3))
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT).apply {
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
            }
            setOnClickListener { dialog.dismiss() }
        }

        val pageTitle = TextView(activity).apply {
            text = "JumpClean 设置"
            textSize = 17.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(primaryTextColor)
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER
            }
        }

        navBar.addView(pageTitle)
        navBar.addView(backBtn)
        mainLayout.addView(navBar)

        val navDivider = View(activity).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
            setBackgroundColor(dividerColor)
        }
        mainLayout.addView(navDivider)

        val scrollView = ScrollView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            isVerticalScrollBarEnabled = false
        }

        val contentLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(96))
        }

        val initialMap = mutableMapOf<String, Boolean>()
        val stateMap = mutableMapOf<String, Boolean>()
        var currentCardLayout: LinearLayout? = null

        val fabButton = TextView(activity).apply {
            text = "✓ 保存并重启"
            textSize = 14.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(13), dp(28), dp(13))
            background = GradientDrawable().apply {
                setColor(accentColor)
                cornerRadius = dp(24).toFloat()
            }
            elevation = dp(8).toFloat()
            visibility = View.GONE
            translationY = dp(70).toFloat()
            alpha = 0f

            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(24)
            }
        }

        fun updateFabState() {
            var hasChange = false
            stateMap.forEach { (k, v) ->
                if (initialMap[k] != v) hasChange = true
            }

            if (hasChange) {
                if (fabButton.visibility != View.VISIBLE) {
                    fabButton.visibility = View.VISIBLE
                    fabButton.animate()
                        .translationY(0f)
                        .alpha(1f)
                        .setDuration(220)
                        .setListener(null)
                        .start()
                }
            } else {
                if (fabButton.visibility == View.VISIBLE) {
                    fabButton.animate()
                        .translationY(dp(70).toFloat())
                        .alpha(0f)
                        .setDuration(180)
                        .setListener(object : AnimatorListenerAdapter() {
                            override fun onAnimationEnd(animation: Animator) {
                                fabButton.visibility = View.GONE
                            }
                        })
                        .start()
                }
            }
        }

        fabButton.setOnClickListener {
            val editor = prefs.edit()
            stateMap.forEach { (key, value) ->
                editor.putBoolean(key, value)
            }
            if (editor.commit()) {
                dialog.dismiss()
                restartApp(activity)
            } else {
                Toast.makeText(activity, "保存失败，请重试", Toast.LENGTH_SHORT).show()
            }
        }

        items.forEach { entry ->
            when (entry) {
                is SectionHeader -> {
                    val sectionTitle = TextView(activity).apply {
                        text = entry.title
                        textSize = 12.5f
                        setTypeface(null, Typeface.BOLD)
                        setTextColor(sectionTextColor)
                        setPadding(dp(8), dp(14), dp(8), dp(6))
                    }
                    contentLayout.addView(sectionTitle)

                    currentCardLayout = LinearLayout(activity).apply {
                        orientation = LinearLayout.VERTICAL
                        background = GradientDrawable().apply {
                            setColor(cardBgColor)
                            cornerRadius = dp(14).toFloat()
                        }
                        setPadding(dp(16), dp(4), dp(16), dp(4))
                    }
                    contentLayout.addView(currentCardLayout)
                }
                is SettingItem -> {
                    val isChecked = prefs.getBoolean(entry.key, getDefaultFeatureValue(entry.key))
                    initialMap[entry.key] = isChecked
                    stateMap[entry.key] = isChecked

                    val rowLayout = LinearLayout(activity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(0, dp(11), 0, dp(11))
                    }

                    val textContainer = LinearLayout(activity).apply {
                        orientation = LinearLayout.VERTICAL
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    }

                    val itemTitle = TextView(activity).apply {
                        text = entry.title
                        textSize = 14.5f
                        setTextColor(primaryTextColor)
                    }
                    textContainer.addView(itemTitle)

                    if (entry.desc.isNotEmpty()) {
                        val itemDesc = TextView(activity).apply {
                            text = entry.desc
                            textSize = 11f
                            setTextColor(warnTextColor)
                            setPadding(0, dp(2), 0, 0)
                        }
                        textContainer.addView(itemDesc)
                    }

                    val switchView = Switch(activity).apply {
                        this.isChecked = isChecked
                        setOnCheckedChangeListener { _, checked ->
                            stateMap[entry.key] = checked
                            updateFabState()
                        }
                    }

                    rowLayout.addView(textContainer)
                    rowLayout.addView(switchView)
                    rowLayout.setOnClickListener { switchView.toggle() }

                    currentCardLayout?.addView(rowLayout)
                }
                is ActionItem -> {
                    val rowLayout = LinearLayout(activity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(0, dp(12), 0, dp(12))
                        isClickable = true
                        isFocusable = true
                    }

                    val textContainer = LinearLayout(activity).apply {
                        orientation = LinearLayout.VERTICAL
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    }

                    val itemTitle = TextView(activity).apply {
                        text = entry.title
                        textSize = 14.5f
                        setTextColor(primaryTextColor)
                    }
                    textContainer.addView(itemTitle)

                    if (entry.desc.isNotEmpty()) {
                        val itemDesc = TextView(activity).apply {
                            text = entry.desc
                            textSize = 11f
                            setTextColor(secondaryTextColor)
                            setPadding(0, dp(2), 0, 0)
                        }
                        textContainer.addView(itemDesc)
                    }

                    val arrowView = TextView(activity).apply {
                        text = "›"
                        textSize = 22f
                        setTextColor(secondaryTextColor)
                        setPadding(dp(4), 0, 0, dp(2))
                    }

                    rowLayout.addView(textContainer)
                    rowLayout.addView(arrowView)
                    rowLayout.setOnClickListener { entry.onClick() }

                    currentCardLayout?.addView(rowLayout)
                }
            }
        }

        scrollView.addView(contentLayout)
        mainLayout.addView(scrollView)
        rootFrame.addView(mainLayout)
        rootFrame.addView(fabButton)

        dialog.setContentView(rootFrame)

        dialog.window?.let { win ->
            win.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            win.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            win.setFlags(
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            )

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val controller = win.insetsController
                if (controller != null) {
                    if (!isDark) {
                        controller.setSystemBarsAppearance(
                            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
                            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        )
                    } else {
                        controller.setSystemBarsAppearance(
                            0,
                            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        )
                    }
                }
            } else {
                var flags = win.decorView.systemUiVisibility
                flags = if (!isDark) {
                    flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                } else {
                    flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
                }
                win.decorView.systemUiVisibility = flags
            }
        }

        try {
            dialog.show()
        } catch (e: Exception) {
            logError("显示设置面板失败", e)
        }
    }

    /**
     * 21 宫格网格选择面板
     */
    @SuppressLint("SetTextI18n")
    private fun showVisualIconGridPicker(activity: Activity) {
        try {
            val pm = activity.packageManager
            val pkgName = activity.packageName

            var currentIdx = 0
            for (i in OFFICIAL_ICONS.indices) {
                val cls = OFFICIAL_ICONS[i].targetClass
                val state = pm.getComponentEnabledSetting(ComponentName(pkgName, cls))
                if (state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
                    currentIdx = i
                    break
                }
            }

            val dp = { value: Int -> (value * activity.resources.displayMetrics.density).toInt() }
            val isDark = (activity.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                    android.content.res.Configuration.UI_MODE_NIGHT_YES

            val dialogBgColor = if (isDark) Color.parseColor("#1E1E1E") else Color.parseColor("#FFFFFF")
            val primaryTextColor = if (isDark) Color.parseColor("#F5F5F7") else Color.parseColor("#1D1D1F")
            val secondaryTextColor = if (isDark) Color.parseColor("#8E8E93") else Color.parseColor("#86868B")
            val activeBorderColor = Color.parseColor("#00B06F")

            val iconDialog = Dialog(activity)
            iconDialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

            val rootLayout = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply {
                    setColor(dialogBgColor)
                    cornerRadius = dp(20).toFloat()
                }
                setPadding(dp(16), dp(18), dp(16), dp(16))
                layoutParams = ViewGroup.LayoutParams(dp(330), ViewGroup.LayoutParams.WRAP_CONTENT)
            }

            val titleView = TextView(activity).apply {
                text = "更换 App 图标"
                textSize = 17.5f
                setTypeface(null, Typeface.BOLD)
                setTextColor(primaryTextColor)
                gravity = Gravity.CENTER
            }
            rootLayout.addView(titleView)

            val tipView = TextView(activity).apply {
                text = "切换后首次进入可能有开屏广告"
                textSize = 11.5f
                setTextColor(secondaryTextColor)
                gravity = Gravity.CENTER
                setPadding(0, dp(4), 0, dp(12))
            }
            rootLayout.addView(tipView)

            val gridScroll = ScrollView(activity).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(350))
                isVerticalScrollBarEnabled = false
            }

            val grid = GridLayout(activity).apply {
                columnCount = 4
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }

            for (i in OFFICIAL_ICONS.indices) {
                val iconModel = OFFICIAL_ICONS[i]
                val itemFrame = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    val p = dp(4)
                    setPadding(p, p, p, p)
                    layoutParams = GridLayout.LayoutParams().apply {
                        width = dp(74)
                        height = dp(84)
                        setGravity(Gravity.CENTER)
                    }
                    background = GradientDrawable().apply {
                        cornerRadius = dp(12).toFloat()
                        if (i == currentIdx) {
                            setStroke(dp(2), activeBorderColor)
                            setColor(if (isDark) Color.parseColor("#2A3B32") else Color.parseColor("#E6F7F0"))
                        } else {
                            setColor(Color.TRANSPARENT)
                        }
                    }
                }

                val iconView = ImageView(activity).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
                    val resId = activity.resources.getIdentifier(iconModel.drawableName, "drawable", pkgName).let {
                        if (it != 0) it else activity.resources.getIdentifier(iconModel.drawableName, "mipmap", pkgName)
                    }
                    if (resId != 0) {
                        setImageResource(resId)
                    } else {
                        setImageResource(activity.applicationInfo.icon)
                    }
                }

                val nameView = TextView(activity).apply {
                    text = iconModel.name
                    textSize = 10f
                    setTextColor(if (i == currentIdx) activeBorderColor else secondaryTextColor)
                    gravity = Gravity.CENTER
                    maxLines = 1
                    setPadding(0, dp(2), 0, 0)
                }

                itemFrame.addView(iconView)
                itemFrame.addView(nameView)

                itemFrame.setOnClickListener {
                    iconDialog.dismiss()
                    if (i != currentIdx) {
                        val oldAlias = OFFICIAL_ICONS[currentIdx].targetClass
                        val newAlias = iconModel.targetClass
                        val shortKey = iconModel.shortKey
                        executeOfficialIconSwitch(activity, oldAlias, newAlias, shortKey)
                    }
                }
                grid.addView(itemFrame)
            }

            gridScroll.addView(grid)
            rootLayout.addView(gridScroll)

            val cancelBtn = TextView(activity).apply {
                text = "取消"
                textSize = 14f
                setTextColor(secondaryTextColor)
                gravity = Gravity.CENTER
                setPadding(0, dp(12), 0, 0)
                setOnClickListener { iconDialog.dismiss() }
            }
            rootLayout.addView(cancelBtn)

            iconDialog.setContentView(rootLayout)
            iconDialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            iconDialog.show()

        } catch (e: Exception) {
            logError("拉起 21 宫格图标面板失败", e)
            Toast.makeText(activity, "拉起面板失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 切换图标
     */
    private fun executeOfficialIconSwitch(context: Context, oldAliasClass: String, newAliasClass: String, shortKey: String) {
        try {
            val pm = context.packageManager
            val pkgName = context.packageName

            if (oldAliasClass.isNotBlank() && oldAliasClass != newAliasClass) {
                pm.setComponentEnabledSetting(
                    ComponentName(pkgName, oldAliasClass),
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP
                )
            }

            try {
                val mmkvClass = XposedHelpers.findClassIfExists("com.tencent.mmkv.MMKV", context.classLoader)
                if (mmkvClass != null) {
                    val defaultMMKV = XposedHelpers.callStaticMethod(mmkvClass, "defaultMMKV")
                    XposedHelpers.callMethod(defaultMMKV, "encode", "launch_icon", shortKey)
                }
            } catch (_: Exception) {}

            pm.setComponentEnabledSetting(
                ComponentName(pkgName, newAliasClass),
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )

            Toast.makeText(context, "图标已更换，正在重载...", Toast.LENGTH_SHORT).show()

            val restartIntent = Intent().apply {
                component = ComponentName(pkgName, newAliasClass)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                putExtra(EXTRA_ICON_RESTART, true)
            }
            context.startActivity(restartIntent)

            Handler(Looper.getMainLooper()).postDelayed({
                Runtime.getRuntime().exit(0)
            }, 200L)

        } catch (e: Exception) {
            logError("执行图标切换异常", e)
            Toast.makeText(context, "切换失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private sealed interface SettingEntry
    private data class SectionHeader(val title: String) : SettingEntry
    private data class SettingItem(val key: String, val title: String, val desc: String = "") : SettingEntry
    private data class ActionItem(val title: String, val desc: String = "", val onClick: () -> Unit) : SettingEntry

    private fun restartApp(activity: Activity) {
        try {
            val intent = activity.packageManager.getLaunchIntentForPackage(activity.packageName)
            activity.startActivity(Intent.makeRestartActivityTask(intent?.component))
            Runtime.getRuntime().exit(0)
        } catch (e: Exception) {
            Toast.makeText(activity, "已保存，请手动重启 App", Toast.LENGTH_SHORT).show()
        }
    }

    // ============================================================
    // 内部工具方法
    // ============================================================

    private fun hideTextItem(activity: Activity, text: String) {
        try {
            val decorView = activity.window?.decorView ?: return
            val textViews = ArrayList<View>()
            decorView.findViewsWithText(textViews, text, View.FIND_VIEWS_WITH_TEXT)
            textViews.forEach { tv ->
                var current: View? = tv
                while (current?.parent != null) {
                    val parent = current.parent
                    if (parent.javaClass.name.contains("RecyclerView")) {
                        collapseView(current, false)
                        (parent as? ViewGroup)?.let { vg ->
                            val idx = vg.indexOfChild(current)
                            if (idx != -1 && idx + 1 < vg.childCount) {
                                collapseView(vg.getChildAt(idx + 1), false)
                            }
                        }
                        break
                    }
                    current = parent as? View
                }
            }
        } catch (e: Exception) {
            logError("文本项隐藏失败", e)
        }
    }

    /**
     * 彻底折叠 View 并清零内外边距及尺寸
     */
    private fun collapseView(view: View, safeMode: Boolean = false) {
        if (view.visibility != View.GONE) view.visibility = View.GONE
        view.isEnabled = false
        view.isClickable = false
        view.isLongClickable = false
        view.isFocusable = false
        view.isFocusableInTouchMode = false
        if (!safeMode) {
            val params = view.layoutParams
            if (params != null) {
                params.height = 0
                params.width = 0
                if (params is ViewGroup.MarginLayoutParams) {
                    params.topMargin = 0
                    params.bottomMargin = 0
                    params.leftMargin = 0
                    params.rightMargin = 0
                }
                view.layoutParams = params
            }
            view.setPadding(0, 0, 0, 0)
        }
        (view.parent as? View)?.requestLayout()
    }

    private fun hidePersistently(view: View) {
        view.visibility = View.GONE
        RETRY_DELAYS_MS.forEach { delay ->
            view.postDelayed({ view.visibility = View.GONE }, delay)
        }
    }

    private fun getCachedResId(context: Context, idName: String): Int {
        return resIdCache.getOrPut(idName) {
            val id = context.resources.getIdentifier(idName, "id", context.packageName)
            if (id == 0) {
                log("⚠ 资源 ID '$idName' 未找到，布局可能已变化")
            }
            id
        }
    }

    /**
     * 统一默认值策略：按指定配置开启核心去广告、主页净化及底栏隐藏项，其余项保持关闭
     */
    private fun getDefaultFeatureValue(key: String): Boolean {
        return when (key) {
            KEY_SKIP_SPLASH,
            KEY_HIDE_BANNER,
            KEY_HIDE_TOPIC_LIST,
            KEY_HIDE_HOT_DISCUSS,
            KEY_HIDE_DISCOVER_TOP_AD,
            KEY_HIDE_DISCOVER_BANNER,
            KEY_HIDE_POST_AD,
            KEY_HIDE_WEB_TAB,
            KEY_HIDE_LOTTERY_TAB -> true

            KEY_HIDE_PUBLISH_TOPIC,
            KEY_HIDE_PHOTO_WALL,
            KEY_HIDE_MEMBER_CARD,
            KEY_HIDE_MY_ORDER,
            KEY_HIDE_MSG_PUSH_GUIDE,
            KEY_HIDE_WIDGET_VIP_TAG,
            KEY_ENABLE_COPY,
            KEY_HIDE_CONTENT_MEMBER_MASK,
            KEY_BLOCK_CLIPBOARD,
            KEY_ENABLE_DEBUG_LOG -> false

            else -> false
        }
    }

    private fun isFeatureEnabled(context: Context, key: String): Boolean {
        return try {
            val defaultVal = getDefaultFeatureValue(key)
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(key, defaultVal)
        } catch (e: Exception) {
            logError("读取设置失败: $key", e)
            getDefaultFeatureValue(key)
        }
    }

    private fun isFeatureEnabledSafe(classLoader: ClassLoader?, key: String): Boolean {
        val cl = classLoader ?: targetClassLoader ?: JumpAdHooks::class.java.classLoader

        // 1. 常规尝试通过 Application 读取 SP
        try {
            val activityThread = XposedHelpers.findClass("android.app.ActivityThread", cl)
            val currentApp = XposedHelpers.callStaticMethod(activityThread, "currentApplication") as? Context
            if (currentApp != null) {
                return isFeatureEnabled(currentApp, key)
            }
        } catch (_: Exception) {}

        // 2. 启动极早期 (currentApp 为 null) 自适应多路径读取 SP XML 文件兜底
        return try {
            val candidatePaths = listOf(
                "/data/user/0/com.vgjump.jump/shared_prefs/${PREFS_NAME}.xml",
                "/data/data/com.vgjump.jump/shared_prefs/${PREFS_NAME}.xml"
            )
            val spFile = candidatePaths.map { File(it) }.firstOrNull { it.exists() }
            if (spFile != null) {
                val content = spFile.readText()
                if (content.contains("""name="$key" value="true"""") || content.contains("""name="$key">true<""")) {
                    true
                } else if (content.contains("""name="$key" value="false"""") || content.contains("""name="$key">false<""")) {
                    false
                } else {
                    getDefaultFeatureValue(key)
                }
            } else {
                getDefaultFeatureValue(key)
            }
        } catch (_: Exception) {
            getDefaultFeatureValue(key)
        }
    }

    /**
     * 启动/初始化阶段日志：仅在进程加载时输出一次，不受 Application Context 尚未就绪的限制
     */
    private fun logInit(msg: String) {
        HookUtils.log(msg)
    }

    /**
     * 运行阶段日志（拦截/滑动触发）：受调试日志开关控制
     */
    private fun log(msg: String) {
        if (isFeatureEnabledSafe(targetClassLoader, KEY_ENABLE_DEBUG_LOG)) {
            HookUtils.log(msg)
        }
    }

    private fun logError(msg: String, e: Exception? = null) {
        if (e != null) {
            HookUtils.err(msg, e)
        } else {
            HookUtils.log("✘ $msg")
        }
    }
}