package com.yilink.jumpclean.hooks

import android.app.Activity
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
import java.lang.reflect.Modifier
import java.util.ArrayList
import java.util.Collections
import java.util.HashSet

object FeedHooks {

    private const val PREF_CACHED_BRV_CLASS = "cached_brv_adapter_class"
    private const val PREF_CACHED_BRV_VERSION = "cached_brv_host_version"

    // 反射 Field 静态缓存
    @Volatile
    private var fieldAdType: Field? = null
    @Volatile
    private var fieldAdId: Field? = null
    @Volatile
    private var fieldCustomNickname: Field? = null
    @Volatile
    private var fieldUserNameStr: Field? = null

    // 记录已经挂载 Hook 的 Adapter 类名，避免重复 Hook
    private val hookedAdapterClasses = Collections.synchronizedSet(HashSet<String>())

    fun hook(lpparam: XC_LoadPackage.LoadPackageParam) {
        // 1. 启动期尝试定位并 Hook BRV Adapter 基类（优先使用持久化缓存，零性能损耗）
        hookBrvAdaptersAtStartup(lpparam)

        // 2. 运行时动态 Hook RecyclerView.setAdapter，作为永不失效的动态兜底探针
        hookRecyclerViewSetAdapter(lpparam)
    }

    private fun getHostVersionCode(context: Context?): Int {
        return try {
            val ctx = context ?: ConfigManager.getValidAppContext() ?: return 0
            val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            pi.versionCode
        } catch (_: Throwable) {
            0
        }
    }

    /**
     * 查找 BRV Adapter 基类：
     * 1. 优先读取持久化缓存（版本未升级时 0ms 瞬间命中）
     * 2. 尝试已知历史混淆字典
     * 3. 首次更新时执行一次轻量 DEX 特征扫描，找到后即刻缓存
     */
    private fun findBrvAdapterClass(classLoader: ClassLoader): Class<*>? {
        val app = ConfigManager.getValidAppContext()
        val prefs = app?.getSharedPreferences(JumpConstants.PREFS_NAME, Context.MODE_PRIVATE)
        val currentVersion = getHostVersionCode(app)

        // 1. 检查持久化缓存
        if (prefs != null && currentVersion > 0) {
            val cachedName = prefs.getString(PREF_CACHED_BRV_CLASS, null)
            val cachedVersion = prefs.getInt(PREF_CACHED_BRV_VERSION, -1)
            if (!cachedName.isNullOrBlank() && cachedVersion == currentVersion) {
                val cls = XposedHelpers.findClassIfExists(cachedName, classLoader)
                if (cls != null) {
                    ConfigManager.log("✔ [持久化缓存命中] 直接加载已缓存 BRV Adapter: $cachedName")
                    return cls
                }
            }
        }

        // 2. 尝试已知类名
        val knownNames = listOf("com.drake.brv.BindingAdapter", "com.drake.brv.b", "zn0", "yn0", "xn0", "wn0")
        for (name in knownNames) {
            try {
                val cls = XposedHelpers.findClassIfExists(name, classLoader) ?: continue
                ConfigManager.log("✔ 命中已知 BRV Adapter 类名: $name")
                saveBrvClassToCache(name, currentVersion)
                return cls
            } catch (_: Exception) {}
        }

        // 3. 执行轻量 DEX 特征扫描定位
        val scanned = scanBrvAdapterClass(classLoader)
        if (scanned != null) {
            saveBrvClassToCache(scanned.name, currentVersion)
        }
        return scanned
    }

    private fun saveBrvClassToCache(className: String, versionCode: Int) {
        try {
            val app = ConfigManager.getValidAppContext() ?: return
            val prefs = app.getSharedPreferences(JumpConstants.PREFS_NAME, Context.MODE_PRIVATE)
            val v = if (versionCode > 0) versionCode else getHostVersionCode(app)
            prefs.edit()
                .putString(PREF_CACHED_BRV_CLASS, className)
                .putInt(PREF_CACHED_BRV_VERSION, v)
                .apply()
            ConfigManager.log("✔ 已将 BRV Adapter 类名持久化至本地缓存: $className (版本 $v)")
        } catch (_: Throwable) {}
    }

    /**
     * 遍历 DEX 元素，动态搜寻具备 BRV 核心特征的 Adapter 类
     */
    private fun scanBrvAdapterClass(classLoader: ClassLoader): Class<*>? {
        return try {
            val pathListField = HookUtils.findFieldRecursively(classLoader.javaClass, "pathList") ?: return null
            val pathList = pathListField.get(classLoader) ?: return null
            val dexElementsField = HookUtils.findFieldRecursively(pathList.javaClass, "dexElements") ?: return null
            val dexElements = dexElementsField.get(pathList) as? Array<*> ?: return null

            val rvAdapterClass = XposedHelpers.findClassIfExists("androidx.recyclerview.widget.RecyclerView\$Adapter", classLoader)

            for (element in dexElements) {
                val elementObj = element ?: continue
                val dexFileField = HookUtils.findFieldRecursively(elementObj.javaClass, "dexFile") ?: continue
                val dexFile = dexFileField.get(elementObj) as? dalvik.system.DexFile ?: continue
                val entries = dexFile.entries()
                while (entries.hasMoreElements()) {
                    val className = entries.nextElement()
                    val isCandidate = className.startsWith("com.drake.brv.") ||
                            (!className.contains(".") && className.length in 2..5 && className.matches(Regex("^[a-z]{1,3}\\d{0,3}$")))
                    if (!isCandidate) continue

                    val cls = try {
                        classLoader.loadClass(className)
                    } catch (_: Throwable) {
                        continue
                    }

                    if (rvAdapterClass != null && !rvAdapterClass.isAssignableFrom(cls)) continue

                    val fields = cls.declaredFields
                    val hasItemTouchHelper = fields.any { it.type.name.contains("ItemTouchHelper") }
                    val hasRv = fields.any { it.type.name.contains("RecyclerView") }
                    val hasCollections = fields.any {
                        java.util.Collection::class.java.isAssignableFrom(it.type) ||
                                java.util.Map::class.java.isAssignableFrom(it.type)
                    }

                    if (hasItemTouchHelper || (hasRv && hasCollections)) {
                        ConfigManager.log("✔ 动态 DEX 特征扫描定位到 BRV Adapter: $className")
                        return cls
                    }
                }
            }
            null
        } catch (e: Throwable) {
            ConfigManager.logError("DEX 特征扫描异常", e)
            null
        }
    }

    private fun hookBrvAdaptersAtStartup(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val adapterClass = findBrvAdapterClass(lpparam.classLoader)
            if (adapterClass != null) {
                ensureAdapterClassHooked(adapterClass, lpparam)
                ConfigManager.log("✔ 启动期 BRV Adapter 基类 Hook 已安装: ${adapterClass.name}")
            } else {
                ConfigManager.log("⚠ 启动期未直接定位到 BRV 基类，已切换至 setAdapter 动态探针兜底")
            }
        } catch (e: Exception) {
            ConfigManager.logError("启动期 BRV Hook 异常", e)
        }
    }

    /**
     * Hook RecyclerView.setAdapter(adapter)
     * 无论宿主如何升级混淆，当列表绑定 Adapter 时，必定捕获真实的 Adapter 实例并安装 Hook
     */
    private fun hookRecyclerViewSetAdapter(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val rvClass = XposedHelpers.findClassIfExists("androidx.recyclerview.widget.RecyclerView", lpparam.classLoader) ?: return
            XposedBridge.hookAllMethods(rvClass, "setAdapter", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val adapter = param.args.getOrNull(0) ?: return
                    ensureAdapterClassHooked(adapter.javaClass, lpparam)

                    // 检查已挂载数据（防止在 setAdapter 之前就已注入旧数据）
                    val isPromoEnabled = ConfigManager.isFeatureEnabledSafe(lpparam.classLoader, JumpConstants.KEY_EXP_BLOCK_OFFICIAL_PROMO_POST)
                    val isKeywordEnabled = ConfigManager.isFeatureEnabledSafe(lpparam.classLoader, JumpConstants.KEY_ENABLE_KEYWORD_BLOCK)
                    if (isPromoEnabled || isKeywordEnabled) {
                        scanAndFilterAdapterModels(adapter, isPromoEnabled, isKeywordEnabled)
                    }
                }
            })
            ConfigManager.log("✔ RecyclerView.setAdapter 动态探针 Hook 已就绪")
        } catch (e: Exception) {
            ConfigManager.logError("✘ RecyclerView.setAdapter Hook 失败", e)
        }
    }

    /**
     * 向上遍历类继承链，确保 Adapter 及其 BRV 父类的方法全部完成拦截
     */
    private fun ensureAdapterClassHooked(adapterClass: Class<*>, lpparam: XC_LoadPackage.LoadPackageParam) {
        var cur: Class<*>? = adapterClass
        while (cur != null && cur != Any::class.java && cur.name != "androidx.recyclerview.widget.RecyclerView\$Adapter") {
            val className = cur.name
            if (hookedAdapterClasses.add(className)) {
                hookSingleAdapterClass(cur, lpparam)
                checkAndPersistBrvClass(cur)
            }
            cur = cur.superclass
        }
    }

    private fun checkAndPersistBrvClass(cls: Class<*>) {
        try {
            val fields = cls.declaredFields
            val hasItemTouchHelper = fields.any { it.type.name.contains("ItemTouchHelper") }
            val hasCollections = fields.any {
                java.util.Collection::class.java.isAssignableFrom(it.type) ||
                        java.util.Map::class.java.isAssignableFrom(it.type)
            }
            if (hasItemTouchHelper || (cls.name.startsWith("com.drake.brv.") && hasCollections)) {
                saveBrvClassToCache(cls.name, 0)
            }
        } catch (_: Throwable) {}
    }

    private fun hookSingleAdapterClass(adapterClass: Class<*>, lpparam: XC_LoadPackage.LoadPackageParam) {
        val filterDataHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val args = param.args
                val isPromoEnabled = ConfigManager.isFeatureEnabledSafe(lpparam.classLoader, JumpConstants.KEY_EXP_BLOCK_OFFICIAL_PROMO_POST)
                val isKeywordEnabled = ConfigManager.isFeatureEnabledSafe(lpparam.classLoader, JumpConstants.KEY_ENABLE_KEYWORD_BLOCK)
                val isRestoreEnabled = ConfigManager.isFeatureEnabledSafe(lpparam.classLoader, JumpConstants.KEY_RESTORE_POST_YEAR)

                for (i in args.indices) {
                    val arg = args[i] ?: continue
                    if (arg is Collection<*>) {
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
                                ConfigManager.logError("Adapter 数据源拦截过滤异常", e)
                            }
                        }
                    }
                }
            }
        }

        var hookCount = 0
        adapterClass.declaredMethods.forEach { method ->
            if (method.name != "onBindViewHolder" && !Modifier.isStatic(method.modifiers)) {
                val hasCollectionParam = method.parameterTypes.any { Collection::class.java.isAssignableFrom(it) }
                if (hasCollectionParam) {
                    try {
                        XposedBridge.hookMethod(method, filterDataHook)
                        hookCount++
                        ConfigManager.log("[Adapter方法挂载] 成功拦截数据输入方法: ${adapterClass.simpleName}.${method.name}")
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

                    // 1. 发帖年份补全（可以在任何包含 tvDate 的页面执行）
                    if (ConfigManager.isFeatureEnabledSafe(lpparam.classLoader, JumpConstants.KEY_RESTORE_POST_YEAR)) {
                        FeatureHooks.restoreItemYearWithValidation(holder, itemView, context)
                    }

                    // 核心边界约束：以下所有 Feed 流清理规则（轮播、热议、会员卡片、广告条目）仅在主页/推荐流中生效
                    // 严禁在帖子详情页（ContentDetailActivity 等）中执行，避免误伤帖子内部图片、话题组件和正文布局
                    val actName = (context as? Activity)?.javaClass?.name ?: SplashHooks.currentActivityName
                    if (!actName.contains("MainActivity") && !actName.contains("HomeActivity")) {
                        return
                    }

                    // 2. 会员卡片买会员按钮条目拦截 (tvBuy)
                    val buyBtnId = HookUtils.getCachedResId(context, "tvBuy")
                    if (buyBtnId != 0 && itemView.findViewById<View>(buyBtnId) != null) {
                        if (ConfigManager.isFeatureEnabledSafe(lpparam.classLoader, JumpConstants.KEY_HIDE_MEMBER_CARD)) {
                            HookUtils.collapseView(itemView)
                            return
                        } else if (HookUtils.isCollapsed(itemView)) {
                            HookUtils.restoreView(itemView)
                        }
                    }

                    // 获取条目布局名称（可能为 null）
                    val itemViewType = try {
                        XposedHelpers.callMethod(holder, "getItemViewType") as? Int
                    } catch (_: Exception) {
                        null
                    }
                    val resName = if (itemViewType != null) {
                        try {
                            context.resources.getResourceEntryName(itemViewType)
                        } catch (_: Exception) {
                            ""
                        }
                    } else ""

                    // 3. 首页轮播图 (banner)
                    // 核心限制：仅在顶部 Header 布局（包含 header 或 general_interest）中折叠轮播组件，
                    // 严禁在信息流条目中盲目折叠，避免误杀好友动态、好友玩过等卡片中的游戏图片
                    val isHeaderLayout = resName.contains("header") || resName.contains("general_interest")
                    if (isHeaderLayout) {
                        val bannerId = HookUtils.getCachedResId(context, "banner")
                        if (bannerId != 0) {
                            val bannerView = itemView.findViewById<View>(bannerId)
                            if (bannerView != null) {
                                if (ConfigManager.isFeatureEnabledSafe(lpparam.classLoader, JumpConstants.KEY_HIDE_BANNER)) {
                                    if (bannerView.visibility != View.GONE) {
                                        HookUtils.collapseView(bannerView)
                                    }
                                } else if (HookUtils.isCollapsed(bannerView)) {
                                    HookUtils.restoreView(bannerView)
                                }
                            }
                        }
                    }

                    // 4. Jumper 热议（仅匹配首页热议列表布局及指示器）
                    val allTopicId = HookUtils.getCachedResId(context, "flAllTopic")
                    val indicatorId = HookUtils.getCachedResId(context, "clIndicator")
                    val isHotDiscussItem = (resName.isNotEmpty() && (resName in JumpConstants.HOT_DISCUSS_LAYOUT_NAMES ||
                            resName.contains("hot_discuss"))) ||
                            (allTopicId != 0 && itemView.findViewById<View>(allTopicId) != null) ||
                            (indicatorId != 0 && itemView.findViewById<View>(indicatorId) != null)

                    if (isHotDiscussItem) {
                        if (ConfigManager.isFeatureEnabledSafe(lpparam.classLoader, JumpConstants.KEY_HIDE_HOT_DISCUSS)) {
                            HookUtils.collapseView(itemView)
                            return
                        } else if (HookUtils.isCollapsed(itemView)) {
                            HookUtils.restoreView(itemView)
                        }
                    }

                    // 5. 帖子广告
                    if (resName.isNotEmpty() && (resName in JumpConstants.POST_AD_LAYOUT_NAMES || resName.contains("ad_sdk") || resName.contains("ad_lottery"))) {
                        if (ConfigManager.isFeatureEnabledSafe(lpparam.classLoader, JumpConstants.KEY_HIDE_POST_AD)) {
                            HookUtils.collapseView(itemView)
                        } else if (HookUtils.isCollapsed(itemView)) {
                            HookUtils.restoreView(itemView)
                        }
                    }
                } catch (e: Exception) {
                    ConfigManager.logError("Adapter onBindViewHolder 过滤异常", e)
                }
            }
        }

        XposedBridge.hookAllMethods(adapterClass, "onBindViewHolder", onBindHook)
        ConfigManager.log("✔ 已为 ${adapterClass.name} 挂载 onBindViewHolder 与 $hookCount 个数据拦截方法")
    }

    /**
     * 当 Adapter 挂载时，扫描其实例字段中的 models 列表并执行一次清洗
     */
    private fun scanAndFilterAdapterModels(adapter: Any, isPromoEnabled: Boolean, isKeywordEnabled: Boolean) {
        try {
            var cur: Class<*>? = adapter.javaClass
            while (cur != null && cur != Any::class.java) {
                cur.declaredFields.forEach { field ->
                    field.isAccessible = true
                    val value = field.get(adapter)
                    if (value is MutableList<*>) {
                        filterPromoList(value, isPromoEnabled, isKeywordEnabled)
                    }
                }
                cur = cur.superclass
            }
        } catch (_: Throwable) {}
    }

    private fun filterPromoList(list: MutableList<*>, isPromoEnabled: Boolean, isKeywordEnabled: Boolean): Boolean {
        val app = ConfigManager.getValidAppContext()
        val keywordScope = app?.getSharedPreferences(JumpConstants.PREFS_NAME, Context.MODE_PRIVATE)
            ?.getString(JumpConstants.KEY_KEYWORD_BLOCK_SCOPE, JumpConstants.SCOPE_RECOMMEND) ?: JumpConstants.SCOPE_RECOMMEND

        // 堆栈前 40 层扫描判定是否在首页推荐/社区流
        val isFromRecommendStream = run {
            val stack = Thread.currentThread().stackTrace
            val limit = minOf(stack.size, 40)
            for (i in 0 until limit) {
                val cn = stack[i].className
                if (cn.contains("Recommend", ignoreCase = true) || cn.contains("Community", ignoreCase = true)) return@run true
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
        if (matchers.isEmpty()) return false
        return try {
            val content = HookUtils.safeCallStringGetter(model, "getContent") ?: ""
            val title = HookUtils.safeCallStringGetter(model, "getTitle") ?: ""
            if (content.isNotEmpty() || title.isNotEmpty()) {
                matchers.any { matcher ->
                    (title.isNotEmpty() && matcher.matches(title)) ||
                            (content.isNotEmpty() && matcher.matches(content))
                }
            } else {
                val titleField = HookUtils.findFieldRecursively(model.javaClass, "title")?.get(model)?.toString() ?: ""
                val contentField = HookUtils.findFieldRecursively(model.javaClass, "content")?.get(model)?.toString() ?: ""
                matchers.any { matcher ->
                    (titleField.isNotEmpty() && matcher.matches(titleField)) ||
                            (contentField.isNotEmpty() && matcher.matches(contentField))
                }
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun isOfficialPromoModel(model: Any): Boolean {
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
