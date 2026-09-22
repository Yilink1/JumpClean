package com.yilink.jumpclean.hooks

import android.app.Activity
import android.content.Context
import android.view.View
import android.widget.TextView
import com.yilink.jumpclean.HookUtils
import com.yilink.jumpclean.config.ConfigManager
import com.yilink.jumpclean.config.JumpConstants
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.util.Collections
import java.util.LinkedHashMap

object FeatureHooks {

    // 帖子与评测完整发布日期缓存
    private val postDateCache = Collections.synchronizedMap(LinkedHashMap<String, String>())
    private const val POST_DATE_CACHE_MAX_SIZE = 500

    fun hook(lpparam: XC_LoadPackage.LoadPackageParam) {
        hookUserContentItemModel(lpparam)
        hookTopicDiscussModel(lpparam)
        hookArticleCopy(lpparam)
        hookNativeTextCopy(lpparam)
        hookPostDateCacheRead(lpparam)
    }

    // ==================== 年份恢复逻辑 ====================

    private fun hookUserContentItemModel(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val userContentItemClass = XposedHelpers.findClassIfExists(
                "com.vgjump.jump.bean.content.UserContentItem", lpparam.classLoader
            ) ?: return

            XposedBridge.hookAllMethods(userContentItemClass, "getPostTimeStr", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!ConfigManager.isFeatureEnabledSafe(lpparam.classLoader, JumpConstants.KEY_RESTORE_POST_YEAR)) return
                    try {
                        val obj = param.thisObject ?: return
                        val postTimeStr = param.result as? String ?: return
                        if (postTimeStr.isBlank() || !JumpConstants.REGEX_YEAR_PREFIX.containsMatchIn(postTimeStr)) return

                        val contentId = HookUtils.safeCallStringGetter(obj, "getContentId")
                        if (!contentId.isNullOrBlank()) {
                            synchronized(postDateCache) {
                                if (postDateCache.size >= POST_DATE_CACHE_MAX_SIZE) {
                                    postDateCache.clear()
                                }
                                postDateCache[contentId] = postTimeStr
                            }
                        }
                    } catch (_: Exception) {}
                }
            })
            ConfigManager.log("✔ UserContentItem 社区数据模型 Hook 已安装")
        } catch (e: Exception) {
            ConfigManager.logError("✘ UserContentItem 数据模型 Hook 失败", e)
        }
    }

    private fun hookTopicDiscussModel(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val topicDiscussClass = XposedHelpers.findClassIfExists(
                "com.vgjump.jump.bean.content.topic.TopicDiscuss", lpparam.classLoader
            ) ?: return

            XposedBridge.hookAllMethods(topicDiscussClass, "getPostTimeStr", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!ConfigManager.isFeatureEnabledSafe(lpparam.classLoader, JumpConstants.KEY_RESTORE_POST_YEAR)) return
                    try {
                        val obj = param.thisObject ?: return
                        val postTimeStr = param.result as? String ?: return
                        if (postTimeStr.isBlank() || !JumpConstants.REGEX_YEAR_PREFIX.containsMatchIn(postTimeStr)) return

                        val postId = HookUtils.safeCallStringGetter(obj, "getPostId")
                        val topicId = HookUtils.safeCallStringGetter(obj, "getTopicId")
                        val commentId = HookUtils.safeCallStringGetter(obj, "getCommentId")

                        synchronized(postDateCache) {
                            if (postDateCache.size >= POST_DATE_CACHE_MAX_SIZE) {
                                postDateCache.clear()
                            }
                            if (!postId.isNullOrBlank()) postDateCache[postId] = postTimeStr
                            if (!topicId.isNullOrBlank()) postDateCache[topicId] = postTimeStr
                            if (!commentId.isNullOrBlank()) postDateCache[commentId] = postTimeStr
                        }
                    } catch (_: Exception) {}
                }
            })
            ConfigManager.log("✔ TopicDiscuss 评测数据 getter 拦截 Hook 已就绪")
        } catch (e: Exception) {
            ConfigManager.logError("✘ TopicDiscuss 数据模型 Hook 失败", e)
        }
    }

    fun cacheDatesFromRawCollection(collection: Collection<*>) {
        try {
            for (item in collection) {
                if (item == null) continue
                val className = item.javaClass.name
                if (className.contains("UserContentItem") || className.contains("TopicDiscuss")) {
                    val postTimeStr = HookUtils.safeCallStringGetter(item, "getPostTimeStr") ?: continue

                    if (postTimeStr.isNotBlank() && JumpConstants.REGEX_YEAR_PREFIX.containsMatchIn(postTimeStr)) {
                        val contentId = HookUtils.safeCallStringGetter(item, "getContentId")
                            ?: HookUtils.safeCallStringGetter(item, "getPostId")
                            ?: HookUtils.safeCallStringGetter(item, "getTopicId")
                            ?: HookUtils.safeCallStringGetter(item, "getCommentId")

                        if (!contentId.isNullOrBlank()) {
                            synchronized(postDateCache) {
                                if (postDateCache.size >= POST_DATE_CACHE_MAX_SIZE) {
                                    postDateCache.clear()
                                }
                                postDateCache[contentId] = postTimeStr
                            }
                        }
                    }
                }
            }
        } catch (_: Throwable) {}
    }

    fun restoreItemYearWithValidation(holder: Any, itemView: View, context: Context) {
        try {
            val tvDateId = HookUtils.getCachedResId(context, "tvDate").takeIf { it != 0 }
                ?: HookUtils.getCachedResId(context, "tvTime")

            if (tvDateId == 0) return
            val tvDate = itemView.findViewById<TextView>(tvDateId) ?: return

            val currentText = tvDate.text?.toString() ?: return
            if (currentText.isBlank() || JumpConstants.REGEX_HAS_YEAR.containsMatchIn(currentText) || currentText.contains("未知年份")) return
            if (!JumpConstants.REGEX_MM_DD.containsMatchIn(currentText)) return

            var targetModel: Any? = null
            var curClass: Class<*>? = holder.javaClass
            while (curClass != null && curClass != Any::class.java) {
                for (f in curClass.declaredFields) {
                    f.isAccessible = true
                    val value = f.get(holder) ?: continue
                    val valName = value.javaClass.name
                    if (valName.contains("UserContentItem") || valName.contains("TopicDiscuss")) {
                        targetModel = value
                        break
                    }
                }
                if (targetModel != null) break
                curClass = curClass.superclass
            }

            if (targetModel == null) {
                try {
                    val adapter = XposedHelpers.callMethod(holder, "getBindingAdapter")
                    val pos = XposedHelpers.callMethod(holder, "getLayoutPosition") as? Int ?: -1
                    val models = (XposedHelpers.getObjectField(adapter, "models") as? List<*>)
                        ?: (XposedHelpers.getObjectField(adapter, "f") as? List<*>)

                    if (models != null && pos in models.indices) {
                        targetModel = models[pos]
                    }
                } catch (_: Throwable) {}
            }

            if (targetModel == null) return

            val fullDate = HookUtils.safeCallStringGetter(targetModel, "getPostTimeStr")
            if (fullDate.isNullOrBlank() || !JumpConstants.REGEX_YEAR_PREFIX.containsMatchIn(fullDate)) return

            val newText = applyValidatedYear(currentText, fullDate) ?: return
            tvDate.text = newText
            ConfigManager.log("✔ [列表条目年份还原] $currentText -> $newText")

        } catch (t: Throwable) {
            ConfigManager.logError("✘ [列表条目年份异常]: ${t.javaClass.simpleName} - ${t.message}")
        }
    }

    private fun applyValidatedYear(currentText: String, fullDate: String): String? {
        val uiDateMatch = JumpConstants.REGEX_MM_DD.find(currentText) ?: return null
        val uiMonthDay = uiDateMatch.value

        val modelDateMatch = JumpConstants.REGEX_FULL_DATE.find(fullDate) ?: return null
        val (modelYear, modelMonthDay) = modelDateMatch.destructured

        return if (uiMonthDay == modelMonthDay) {
            currentText.replaceFirst(uiMonthDay, "$modelYear-$uiMonthDay")
        } else {
            currentText.replaceFirst(uiMonthDay, "未知年份-$uiMonthDay")
        }
    }

    private fun hookPostDateCacheRead(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            XposedHelpers.findAndHookMethod(
                TextView::class.java, "setText",
                CharSequence::class.java, TextView.BufferType::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!ConfigManager.isFeatureEnabledSafe(lpparam.classLoader, JumpConstants.KEY_RESTORE_POST_YEAR)) return
                        try {
                            val tv = param.thisObject as? TextView ?: return
                            val context = tv.context ?: return
                            if (context !is Activity) return

                            val actName = SplashHooks.currentActivityName
                            if (!actName.contains("ContentDetailActivity") &&
                                !actName.contains("CommentDetailActivity") &&
                                !actName.contains("GameDetailActivity")
                            ) return

                            val tvDateId = HookUtils.getCachedResId(context, "tvDate")
                            if (tvDateId == 0 || tv.id != tvDateId) return

                            val incoming = param.args.getOrNull(0) as? CharSequence ?: return
                            val incomingText = incoming.toString()
                            if (incomingText.isBlank() || incomingText.contains("未知年份")) return

                            var newText = incomingText

                            var targetId: String? = null
                            val extras = context.intent?.extras
                            if (extras != null) {
                                for (key in JumpConstants.INTENT_ID_KEYS) {
                                    @Suppress("DEPRECATION")
                                    val v = extras.get(key) ?: continue
                                    val s = v.toString().trim()
                                    if (s.isNotEmpty() && s != "0" && s != "null") {
                                        targetId = s
                                        break
                                    }
                                }
                            }

                            if (targetId != null) {
                                val cachedDate = synchronized(postDateCache) { postDateCache[targetId] }
                                if (cachedDate != null && !JumpConstants.REGEX_HAS_YEAR.containsMatchIn(newText)) {
                                    val validated = applyValidatedYear(newText, cachedDate)
                                    if (validated != null) {
                                        newText = validated
                                    }
                                }
                            }

                            if (newText.contains("image", ignoreCase = true)) {
                                newText = newText.replace(JumpConstants.REGEX_IMAGE_CLEAN, "").trim()
                            }

                            if (newText != incomingText) {
                                param.args[0] = newText
                                ConfigManager.log("✔ [详情页年份还原] 拦截渲染: $incomingText -> $newText")
                            }
                        } catch (e: Exception) {
                            ConfigManager.logError("年份缓存读取异常", e)
                        }
                    }
                }
            )
            ConfigManager.log("✔ 帖子年份缓存读取 Hook 已安装")
        } catch (e: Exception) {
            ConfigManager.logError("✘ 帖子年份缓存读取 Hook 失败", e)
        }
    }

    // ==================== 文本复制解锁 ====================

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
                            ConfigManager.logError("WebView JS 注入失败", e)
                        }
                    }, delay)
                } catch (e: Exception) {
                    ConfigManager.logError("WebView postDelayed 调度失败", e)
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
                            if (firstArg.contains("registerArticleDataRes") &&
                                ConfigManager.isFeatureEnabledSafe(lpparam.classLoader, JumpConstants.KEY_ENABLE_COPY)
                            ) {
                                inject(param.thisObject)
                            }
                        } catch (e: Exception) {
                            ConfigManager.logError("DWebView.d 回调异常", e)
                        }
                    }
                })
                XposedBridge.hookAllMethods(dWebViewClass, "loadUrl", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val url = param.args.getOrNull(0) as? String ?: return
                            if (ConfigManager.isFeatureEnabledSafe(lpparam.classLoader, JumpConstants.KEY_ENABLE_COPY) && shouldInjectCopyJs(url)) {
                                inject(param.thisObject)
                            }
                        } catch (e: Exception) {
                            ConfigManager.logError("DWebView.loadUrl 回调异常", e)
                        }
                    }
                })
            }

            val webViewClass = XposedHelpers.findClassIfExists("android.webkit.WebView", lpparam.classLoader)
            if (webViewClass != null) {
                XposedBridge.hookAllMethods(webViewClass, "loadUrl", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val url = param.args.getOrNull(0) as? String ?: return
                        if (ConfigManager.isFeatureEnabledSafe(lpparam.classLoader, JumpConstants.KEY_ENABLE_COPY) && shouldInjectCopyJs(url)) {
                            inject(param.thisObject)
                        }
                    }
                })
                XposedBridge.hookAllMethods(webViewClass, "setLongClickable", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (ConfigManager.isFeatureEnabledSafe(lpparam.classLoader, JumpConstants.KEY_ENABLE_COPY)) {
                            param.args[0] = true
                        }
                    }
                })
            }
            ConfigManager.log("✔ WebView 文章复制解锁 Hook 已安装")
        } catch (e: Exception) {
            ConfigManager.logError("✘ WebView 文章复制解锁 Hook 失败", e)
        }
    }

    private fun isTargetTextView(tv: TextView?): Boolean {
        if (tv == null) return false
        val text = tv.text?.toString()?.trim() ?: return false
        if (text.length < 10) return false
        val act = SplashHooks.currentActivityName
        val cls = tv.javaClass.name
        if (act.contains("GameDetailActivity")) {
            return cls.contains("MyExpandableTextView") && text.length >= 10
        }
        if (act.contains("ContentDetailActivity") || act.contains("CommentDetailActivity")) {
            return cls.contains("LineHeightTextView") || cls.contains("MyExpandableTextView")
        }
        return false
    }

    private fun enableTextCopy(tv: TextView?, classLoader: ClassLoader) {
        if (!ConfigManager.isFeatureEnabledSafe(classLoader, JumpConstants.KEY_ENABLE_COPY) || tv == null) return
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
            ConfigManager.logError("TextView 文本选择设置失败", e)
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
            ConfigManager.log("✔ 原生 TextView 复制解锁 Hook 已安装")
        } catch (e: Exception) {
            ConfigManager.logError("✘ 原生 TextView 复制解锁 Hook 失败", e)
        }
    }
}
