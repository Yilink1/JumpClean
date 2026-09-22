package com.yilink.jumpclean.hooks

import android.content.Context
import android.view.View
import com.yilink.jumpclean.HookUtils
import com.yilink.jumpclean.config.ConfigManager
import com.yilink.jumpclean.config.JumpConstants
import com.yilink.jumpclean.config.KeywordMatcher
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.reflect.Field
import java.util.ArrayList

object FeedHooks {

    // 反射 Field 静态缓存
    @Volatile
    private var fieldAdType: Field? = null
    @Volatile
    private var fieldAdId: Field? = null
    @Volatile
    private var fieldCustomNickname: Field? = null
    @Volatile
    private var fieldUserNameStr: Field? = null

    fun hook(lpparam: XC_LoadPackage.LoadPackageParam) {
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

    private fun hookBrvAdapters(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val adapterClass = findBrvAdapterClass(lpparam.classLoader)
            if (adapterClass == null) {
                ConfigManager.logError("BRV Adapter Hook 失败：未找到 Adapter 基类")
                return
            }

            val filterDataHook = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val args = param.args
                    val isPromoEnabled = ConfigManager.isFeatureEnabledSafe(lpparam.classLoader, JumpConstants.KEY_EXP_BLOCK_OFFICIAL_PROMO_POST)
                    val isKeywordEnabled = ConfigManager.isFeatureEnabledSafe(lpparam.classLoader, JumpConstants.KEY_ENABLE_KEYWORD_BLOCK)
                    val isRestoreEnabled = ConfigManager.isFeatureEnabledSafe(lpparam.classLoader, JumpConstants.KEY_RESTORE_POST_YEAR)

                    for (i in args.indices) {
                        val arg = args[i] ?: continue
                        if (arg is Collection<*>) {
                            ConfigManager.log("[BRV数据流入] 方法=${param.method.name}, 参数位置=$i, 类型=${arg.javaClass.name}, 数量=${arg.size}")

                            if (isRestoreEnabled) {
                                FeatureHooks.cacheDatesFromRawCollection(arg)
                            }

                            if (isPromoEnabled || isKeywordEnabled) {
                                try {
                                    if (arg is MutableList<*>) {
                                        filterPromoList(arg, isPromoEnabled, isKeywordEnabled)
                                    } else if (arg is List<*>) {
                                        val mutableCopy = ArrayList(arg)
                                        if (filterPromoList(mutableCopy, isPromoEnabled, isKeywordEnabled)) {
                                            param.args[i] = mutableCopy
                                        }
                                    }
                                } catch (e: Exception) {
                                    ConfigManager.logError("BRV 数据源拦截过滤异常", e)
                                }
                            }
                        }
                    }
                }
            }

            var hookCount = 0
            adapterClass.declaredMethods.forEach { method ->
                if (method.name != "onBindViewHolder") {
                    val hasCollectionParam = method.parameterTypes.any { Collection::class.java.isAssignableFrom(it) }
                    if (hasCollectionParam) {
                        try {
                            XposedBridge.hookMethod(method, filterDataHook)
                            hookCount++
                            ConfigManager.log("[Adapter方法挂载成功] 拦截数据方法: ${method.name}(${method.parameterTypes.joinToString { it.simpleName }})")
                        } catch (_: Throwable) {}
                    }
                }
            }

            val onBindHook = object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val holder = param.args.getOrNull(0) ?: return
                        val itemView = XposedHelpers.getObjectField(holder, "itemView") as? View ?: return
                        val context = itemView.context ?: return
                        ConfigManager.initAppContext(context)

                        if (ConfigManager.isFeatureEnabledSafe(lpparam.classLoader, JumpConstants.KEY_RESTORE_POST_YEAR)) {
                            FeatureHooks.restoreItemYearWithValidation(holder, itemView, context)
                        }

                        if (ConfigManager.isFeatureEnabledSafe(lpparam.classLoader, JumpConstants.KEY_HIDE_MEMBER_CARD)) {
                            val buyBtnId = HookUtils.getCachedResId(context, "tvBuy")
                            if (buyBtnId != 0 && itemView.findViewById<View>(buyBtnId) != null) {
                                HookUtils.collapseView(itemView)
                                return
                            }
                        }

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

                        if (resName.contains("general_interest_home_header")) {
                            if (ConfigManager.isFeatureEnabledSafe(lpparam.classLoader, JumpConstants.KEY_HIDE_BANNER)) {
                                val bannerId = HookUtils.getCachedResId(context, "banner")
                                if (bannerId != 0) {
                                    itemView.findViewById<View>(bannerId)?.let { bannerView ->
                                        if (bannerView.visibility != View.GONE) {
                                            HookUtils.collapseView(bannerView)
                                        }
                                    }
                                }
                            }
                        }

                        if (ConfigManager.isFeatureEnabledSafe(lpparam.classLoader, JumpConstants.KEY_HIDE_HOT_DISCUSS)) {
                            val allTopicId = HookUtils.getCachedResId(context, "flAllTopic")
                            val indicatorId = HookUtils.getCachedResId(context, "clIndicator")
                            val isHotDiscussItem = resName in JumpConstants.HOT_DISCUSS_LAYOUT_NAMES ||
                                    (allTopicId != 0 && itemView.findViewById<View>(allTopicId) != null) ||
                                    (indicatorId != 0 && itemView.findViewById<View>(indicatorId) != null)

                            if (isHotDiscussItem) {
                                HookUtils.collapseView(itemView)
                                return
                            }
                        }

                        if (ConfigManager.isFeatureEnabledSafe(lpparam.classLoader, JumpConstants.KEY_HIDE_POST_AD) &&
                            resName in JumpConstants.POST_AD_LAYOUT_NAMES
                        ) {
                            HookUtils.collapseView(itemView)
                        }
                    } catch (e: Exception) {
                        ConfigManager.logError("BRV onBindViewHolder 过滤异常", e)
                    }
                }
            }

            XposedBridge.hookAllMethods(adapterClass, "onBindViewHolder", onBindHook)
            ConfigManager.log("✔ BRV 极速数据源物理剔除与过滤 Hook 已就绪 (共挂载 $hookCount 个数据方法)")
        } catch (e: Exception) {
            ConfigManager.logError("✘ BRV Adapter Hook 失败", e)
        }
    }

    private fun filterPromoList(list: MutableList<*>, isPromoEnabled: Boolean, isKeywordEnabled: Boolean): Boolean {
        val app = ConfigManager.getValidAppContext()
        val keywordScope = app?.getSharedPreferences(JumpConstants.PREFS_NAME, Context.MODE_PRIVATE)
            ?.getString(JumpConstants.KEY_KEYWORD_BLOCK_SCOPE, JumpConstants.SCOPE_RECOMMEND) ?: JumpConstants.SCOPE_RECOMMEND

        // 堆栈前 25 层轻量深度扫描，快速判定是否在首页推荐流
        val isFromRecommendStream = run {
            val stack = Thread.currentThread().stackTrace
            val limit = minOf(stack.size, 25)
            for (i in 0 until limit) {
                if (stack[i].className.contains("CommunityRecommendViewModel")) return@run true
            }
            false
        }

        val matchers = if (isKeywordEnabled && app != null) ConfigManager.getBlockedMatchers(app) else emptyList()

        var modified = false
        val iterator = list.iterator()
        while (iterator.hasNext()) {
            val item = iterator.next() ?: continue

            // 1. 小酱推广贴：锁定仅在推荐流剔除
            if (isPromoEnabled && isFromRecommendStream && isOfficialPromoModel(item)) {
                iterator.remove()
                modified = true
                val content = HookUtils.safeCallStringGetter(item, "getContent") ?: ""
                val adId = HookUtils.safeGetObjectField(item, "adId")?.toString()
                    ?: fieldAdId?.get(item)?.toString()
                    ?: ""
                ConfigManager.recordOfficialPromoBlocked(adId, content)
                ConfigManager.log("✔ [数据层剔除] 推荐流命中官方推广规则，已移除条目")
                continue
            }

            // 2. 关键词拦截：按用户独立配置的作用域生效
            if (isKeywordEnabled && matchers.isNotEmpty()) {
                val shouldCheckKeyword = (keywordScope == JumpConstants.SCOPE_GLOBAL) || isFromRecommendStream
                if (shouldCheckKeyword && isPostHitBlockedKeyword(item, matchers)) {
                    iterator.remove()
                    modified = true
                    ConfigManager.log("✔ [数据层剔除] 命中屏蔽词规则 ($keywordScope)，已移除帖子")
                }
            }
        }
        return modified
    }

    private fun isPostHitBlockedKeyword(model: Any, matchers: List<KeywordMatcher>): Boolean {
        if (matchers.isEmpty() || !model.javaClass.name.contains("UserContentItem")) return false
        return try {
            val content = HookUtils.safeCallStringGetter(model, "getContent") ?: ""
            val title = HookUtils.safeCallStringGetter(model, "getTitle") ?: ""
            matchers.any { matcher ->
                (title.isNotEmpty() && matcher.matches(title)) ||
                        (content.isNotEmpty() && matcher.matches(content))
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun isOfficialPromoModel(model: Any): Boolean {
        if (!model.javaClass.name.contains("UserContentItem")) return false
        return try {
            val clazz = model.javaClass

            if (fieldAdType == null) {
                fieldAdType = HookUtils.findFieldRecursively(clazz, "adType")
                fieldAdId = HookUtils.findFieldRecursively(clazz, "adId")
                fieldCustomNickname = HookUtils.findFieldRecursively(clazz, "customNickname")
                fieldUserNameStr = HookUtils.findFieldRecursively(clazz, "userNameStr")
            }

            val adType = fieldAdType?.get(model)
            val adId = fieldAdId?.get(model)
            if (adType != null || adId != null) {
                return true
            }

            val nickname = HookUtils.safeCallStringGetter(model, "getCustomNickname")
                ?: HookUtils.safeCallStringGetter(model, "getUserNameStr")
                ?: fieldCustomNickname?.get(model)?.toString()
                ?: fieldUserNameStr?.get(model)?.toString()
                ?: ""

            if (nickname.contains("小酱") || nickname.contains("Jump官方")) {
                return true
            }

            false
        } catch (_: Exception) {
            false
        }
    }
}
