package com.yilink.jumpclean.hooks

import android.app.Activity
import android.content.Context
import android.os.SystemClock
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
import java.util.concurrent.ConcurrentHashMap

object FeedHooks {

    private const val PREF_CACHED_BRV_CLASS = "cached_brv_adapter_class"
    private const val PREF_CACHED_BRV_VERSION = "cached_brv_host_version"

    @Volatile
    private var brvBaseClass: Class<*>? = null

    // 反射 Field 静态缓存
    @Volatile
    private var fieldAdType: Field? = null
    @Volatile
    private var fieldAdId: Field? = null
    @Volatile
    private var fieldCustomNickname: Field? = null
    @Volatile
    private var fieldUserNameStr: Field? = null
    @Volatile
    private var cachedModelsField: Field? = null
    @Volatile
    private var fieldsResolved = false

    // 内存极速布局资源名称缓存（O(1) 替代跨 JNI 的 getResourceEntryName）
    private val layoutNameCache = ConcurrentHashMap<Int, String>()

    // onBindViewHolder 防重（防子类/父类双重 Hook 导致的每项多次执行）
    @Volatile
    private var lastBindHolderHash = 0
    @Volatile
    private var lastBindTime = 0L

    // 列表清洗防重（同一次刷新触发的多次内部 setModels/addModels 仅清洗一次）
    @Volatile
    private var lastFilteredListHash = 0
    @Volatile
    private var lastFilteredListTime = 0L

    // 记录已经挂载 Hook 的 Adapter 类名，避免重复 Hook
    private val hookedAdapterClasses = Collections.synchronizedSet(HashSet<String>())

    @Volatile
    private var lastAdCollapseLogTime = 0L

    private fun getCachedLayoutName(context: Context, resId: Int): String {
        return layoutNameCache.getOrPut(resId) {
            try {
                context.resources.getResourceEntryName(resId)
            } catch (_: Throwable) {
                ""
            }
        }
    }

    fun hook(lpparam: XC_LoadPackage.LoadPackageParam) {
        // 1. 启动期尝试定位并 Hook BRV Adapter 基类（优先使用持久化缓存，零性能损耗）
        hookBrvAdaptersAtStartup(lpparam)

        // 2. 启动期定位并 Hook BRVAH 列表适配器（BaseQuickAdapter，支持游戏详情页评测等列表）
        hookBrvahAdaptersAtStartup(lpparam)

        // 3. 常驻开启 RecyclerView.setAdapter 动态探针，捕获游戏详情页评测等动态加载的 Adapter
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
                    return cls
                }
            }
        }

        // 2. 尝试已知类名
        val knownNames = listOf("com.drake.brv.BindingAdapter", "com.drake.brv.b", "zn0", "yn0", "xn0", "wn0")
        for (name in knownNames) {
            try {
                val cls = XposedHelpers.findClassIfExists(name, classLoader) ?: continue
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
                        ConfigManager.log("[Hook] DEX 动态扫描定位到 BRV Adapter: $className")
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
                brvBaseClass = adapterClass
                ensureAdapterClassHooked(adapterClass, lpparam)
            } else {
                ConfigManager.log("[Hook] 未直接命中 BRV 基类，已开启 setAdapter 动态探针兜底")
            }
        } catch (e: Exception) {
            ConfigManager.logError("启动期 BRV Hook 异常", e)
        }
    }

    private fun hookBrvahAdaptersAtStartup(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val brvahClass = XposedHelpers.findClassIfExists(
                "com.chad.library.adapter.base.BaseQuickAdapter", lpparam.classLoader
            )
            if (brvahClass != null) {
                ensureAdapterClassHooked(brvahClass, lpparam)
            }
        } catch (e: Exception) {
            ConfigManager.logError("启动期 BRVAH Hook 异常", e)
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
                    val cls = adapter.javaClass
                    val clsName = cls.name.lowercase()
                    if (clsName.contains("ninegrid") || clsName.contains("photoview") || clsName.contains("indicator")) return

                    if (!hookedAdapterClasses.contains(cls.name)) {
                        ConfigManager.log("[Probe] 动态捕获列表适配器: ${cls.simpleName} (${cls.name})")
                        ensureAdapterClassHooked(cls, lpparam)
                    }

                    // 检查已挂载数据（防止在 setAdapter 之前就已注入旧数据）
                    val isPromoEnabled = ConfigManager.isFeatureEnabledSafe(lpparam.classLoader, JumpConstants.KEY_EXP_BLOCK_OFFICIAL_PROMO_POST)
                    val isKeywordEnabled = ConfigManager.isFeatureEnabledSafe(lpparam.classLoader, JumpConstants.KEY_ENABLE_KEYWORD_BLOCK)
                    if (isPromoEnabled || isKeywordEnabled) {
                        scanAndFilterAdapterModels(adapter, isPromoEnabled, isKeywordEnabled)
                    }
                }
            })
            ConfigManager.log("[Hook] 列表动态探针已就绪")
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
                brvBaseClass = cls
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
                    } catch (_: Throwable) {}
                }
            }
        }

        val onBindHook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                try {
                    val holder = param.args.getOrNull(0) ?: return

                    // 1. 同一条目在 5ms 内防重执行（解决继承链及不同重载导致单条目被 Hook 两次问题）
                    val holderHash = System.identityHashCode(holder)
                    val now = SystemClock.uptimeMillis()
                    if (holderHash == lastBindHolderHash && (now - lastBindTime) < 5) {
                        return
                    }
                    lastBindHolderHash = holderHash
                    lastBindTime = now

                    val itemView = XposedHelpers.getObjectField(holder, "itemView") as? View ?: return
                    val context = itemView.context ?: return
                    ConfigManager.initAppContext(context)

                    // 核心边界约束：以下所有 Feed 流清理规则（轮播、热议、会员卡片、广告条目）仅在主页/推荐流中生效
                    // 严禁在帖子详情页（ContentDetailActivity 等）中执行，避免误伤帖子内部图片、话题组件和正文布局
                    val actName = (context as? Activity)?.javaClass?.name ?: SplashHooks.currentActivityName
                    if (!actName.contains("MainActivity") && !actName.contains("HomeActivity")) {
                        return
                    }

                    // 2. 发帖年份补全（基于 context 直接读取，零反射）
                    if (ConfigManager.isFeatureEnabled(context, JumpConstants.KEY_RESTORE_POST_YEAR)) {
                        FeatureHooks.restoreItemYearWithValidation(holder, itemView, context)
                    }

                    // 快速通道：使用 context 直读配置（零反射），若所有 Feed 流净化开关均关闭且无折叠视图，直接快速返回
                    val isMemberCardEnabled = ConfigManager.isFeatureEnabled(context, JumpConstants.KEY_HIDE_MEMBER_CARD)
                    val isBannerEnabled = ConfigManager.isFeatureEnabled(context, JumpConstants.KEY_HIDE_BANNER)
                    val isHotDiscussEnabled = ConfigManager.isFeatureEnabled(context, JumpConstants.KEY_HIDE_HOT_DISCUSS)
                    val isPostAdEnabled = ConfigManager.isFeatureEnabled(context, JumpConstants.KEY_HIDE_POST_AD)

                    if (!isMemberCardEnabled && !isBannerEnabled && !isHotDiscussEnabled && !isPostAdEnabled && !HookUtils.hasCollapsedViews()) {
                        return
                    }

                    // 3. 会员卡片买会员按钮条目拦截 (tvBuy)
                    val buyBtnId = HookUtils.getCachedResId(context, "tvBuy")
                    if (buyBtnId != 0 && itemView.findViewById<View>(buyBtnId) != null) {
                        if (isMemberCardEnabled) {
                            HookUtils.collapseView(itemView)
                            return
                        } else if (HookUtils.isCollapsed(itemView)) {
                            HookUtils.restoreView(itemView)
                        }
                    }

                    // 4. 获取条目布局名称（走 O(1) 内存缓存，彻底杜绝高频 JNI getResourceEntryName 调用）
                    val itemViewType = try {
                        XposedHelpers.callMethod(holder, "getItemViewType") as? Int
                    } catch (_: Exception) {
                        null
                    }
                    val resName = if (itemViewType != null && itemViewType != 0) {
                        getCachedLayoutName(context, itemViewType)
                    } else ""

                    // 5. 首页轮播图 (banner)
                    // 核心限制：仅在顶部 Header 布局（包含 header 或 general_interest）中折叠轮播组件，
                    // 严禁在信息流条目中盲目折叠，避免误杀好友动态、好友玩过等卡片中的游戏图片
                    val isHeaderLayout = resName.contains("header") || resName.contains("general_interest")
                    if (isHeaderLayout) {
                        val bannerId = HookUtils.getCachedResId(context, "banner")
                        if (bannerId != 0) {
                            val bannerView = itemView.findViewById<View>(bannerId)
                            if (bannerView != null) {
                                if (isBannerEnabled) {
                                    if (bannerView.visibility != View.GONE) {
                                        HookUtils.collapseView(bannerView)
                                    }
                                } else if (HookUtils.isCollapsed(bannerView)) {
                                    HookUtils.restoreView(bannerView)
                                }
                            }
                        }
                    }

                    // 6. Jumper 热议（精准匹配首页热议列表布局及指示器）
                    val allTopicId = HookUtils.getCachedResId(context, "flAllTopic")
                    val indicatorId = HookUtils.getCachedResId(context, "clIndicator")
                    val isHotDiscussItem = (resName.isNotEmpty() && (resName in JumpConstants.HOT_DISCUSS_LAYOUT_NAMES ||
                            resName.contains("hot_discuss"))) ||
                            (allTopicId != 0 && itemView.findViewById<View>(allTopicId) != null) ||
                            (indicatorId != 0 && itemView.findViewById<View>(indicatorId) != null)

                    if (isHotDiscussItem) {
                        if (isHotDiscussEnabled) {
                            HookUtils.collapseView(itemView)
                            return
                        } else if (HookUtils.isCollapsed(itemView)) {
                            HookUtils.restoreView(itemView)
                        }
                    }

                    // 7. 帖子广告
                    if (resName.isNotEmpty() && (resName in JumpConstants.POST_AD_LAYOUT_NAMES || resName.contains("ad_sdk") || resName.contains("ad_lottery"))) {
                        if (isPostAdEnabled) {
                            HookUtils.collapseView(itemView)
                            val nowTime = SystemClock.uptimeMillis()
                            if (nowTime - lastAdCollapseLogTime > 1000L) {
                                lastAdCollapseLogTime = nowTime
                                ConfigManager.log("✔ [视图折叠] 推荐流商业广告 ($resName)")
                            }
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
        val desc = if (hookCount > 0) {
            "列表适配器已挂载: ${adapterClass.simpleName} ($hookCount 个数据入口)"
        } else {
            "列表适配器已挂载: ${adapterClass.simpleName}"
        }
        ConfigManager.log("[Hook] $desc")
    }

    /**
     * 当 Adapter 挂载时，扫描其实例字段中的 models 列表并执行一次清洗
     */
    private fun scanAndFilterAdapterModels(adapter: Any, isPromoEnabled: Boolean, isKeywordEnabled: Boolean) {
        if (!isPromoEnabled && !isKeywordEnabled) return
        try {
            val cachedField = cachedModelsField
            if (cachedField != null && cachedField.declaringClass.isInstance(adapter)) {
                val value = cachedField.get(adapter)
                if (value is MutableList<*>) {
                    if (filterPromoList(value, isPromoEnabled, isKeywordEnabled)) {
                        try { XposedHelpers.callMethod(adapter, "notifyDataSetChanged") } catch (_: Throwable) {}
                    }
                    return
                }
            }

            var cur: Class<*>? = adapter.javaClass
            while (cur != null && cur != Any::class.java && cur.name != "androidx.recyclerview.widget.RecyclerView\$Adapter") {
                for (field in cur.declaredFields) {
                    if (field.name == "models" || field.name == "_models" || field.name == "mData" || field.name == "data" || field.name == "f" ||
                        java.util.List::class.java.isAssignableFrom(field.type)) {
                        field.isAccessible = true
                        val value = field.get(adapter)
                        if (value is MutableList<*>) {
                            cachedModelsField = field
                            if (filterPromoList(value, isPromoEnabled, isKeywordEnabled)) {
                                try { XposedHelpers.callMethod(adapter, "notifyDataSetChanged") } catch (_: Throwable) {}
                            }
                            return
                        }
                    }
                }
                cur = cur.superclass
            }
        } catch (_: Throwable) {}
    }

    private fun filterPromoList(list: MutableList<*>, isPromoEnabled: Boolean, isKeywordEnabled: Boolean): Boolean {
        if (list.isEmpty()) return false

        // 1. 列表实例清洗防重：同一批数据在 300ms 内避免被多个内部调用重复扫描
        val listHash = System.identityHashCode(list)
        val now = SystemClock.uptimeMillis()
        if (listHash == lastFilteredListHash && (now - lastFilteredListTime) < 300) {
            return false
        }
        lastFilteredListHash = listHash
        lastFilteredListTime = now

        val app = ConfigManager.getValidAppContext()
        val keywordScope = app?.getSharedPreferences(JumpConstants.PREFS_NAME, Context.MODE_PRIVATE)
            ?.getString(JumpConstants.KEY_KEYWORD_BLOCK_SCOPE, JumpConstants.SCOPE_RECOMMEND) ?: JumpConstants.SCOPE_RECOMMEND

        // 2. 零开销极速判定是否在首页流（替代原耗时极高的 Thread.currentThread().stackTrace 抓栈）
        val actName = SplashHooks.currentActivityName
        val isFromRecommendStream = actName.isEmpty() || actName.contains("MainActivity") || actName.contains("HomeActivity")

        val matchers = if (isKeywordEnabled && app != null) ConfigManager.getBlockedMatchers(app) else emptyList()

        var modified = false
        val iterator = list.iterator()
        while (iterator.hasNext()) {
            val item = iterator.next() ?: continue

            // 1. 小酱推广贴：锁定仅在推荐流剔除
            if (isPromoEnabled && isFromRecommendStream && isOfficialPromoModel(item)) {
                iterator.remove()
                modified = true
                ConfigManager.recordOfficialPromoBlocked()
                ConfigManager.log("✔ [数据剔除] 推荐流命中官方推广规则")
                continue
            }

            // 2. 关键词拦截：按用户独立配置的作用域生效
            if (isKeywordEnabled && matchers.isNotEmpty()) {
                val shouldCheckKeyword = (keywordScope == JumpConstants.SCOPE_GLOBAL) || isFromRecommendStream
                if (shouldCheckKeyword && isPostHitBlockedKeyword(item, matchers)) {
                    iterator.remove()
                    modified = true
                    ConfigManager.log("✔ [数据剔除] 命中屏蔽词规则 ($keywordScope)")
                }
            }
        }
        return modified
    }

    private fun isPostHitBlockedKeyword(model: Any, matchers: List<KeywordMatcher>): Boolean {
        if (matchers.isEmpty()) return false
        return try {
            // 1. 优先调用富文本行/全文 Getter (如 TopicDiscuss 的 getContentLineStr)
            val fullLine = HookUtils.safeCallStringGetter(model, "getContentLineStr")
            if (!fullLine.isNullOrEmpty() && matchers.any { it.matches(fullLine) }) {
                return true
            }

            // 2. 常规 Getter 读取 (UserContentItem / TopicDiscuss / 评测模型通用)
            val title = HookUtils.safeCallStringGetter(model, "getTitle") ?: ""
            val content = HookUtils.safeCallStringGetter(model, "getContent") ?: ""
            val comment = HookUtils.safeCallStringGetter(model, "getComment") ?: ""
            val text = HookUtils.safeCallStringGetter(model, "getText") ?: ""

            if (title.isNotEmpty() || content.isNotEmpty() || comment.isNotEmpty() || text.isNotEmpty()) {
                return matchers.any { matcher ->
                    (title.isNotEmpty() && matcher.matches(title)) ||
                            (content.isNotEmpty() && matcher.matches(content)) ||
                            (comment.isNotEmpty() && matcher.matches(comment)) ||
                            (text.isNotEmpty() && matcher.matches(text))
                }
            }

            // 3. 兜底字段读取（仅在 Getter 全部未取到时安全反射）
            val fallback = HookUtils.safeGetObjectField(model, "comment")?.toString()
                ?: HookUtils.safeGetObjectField(model, "content")?.toString()
                ?: HookUtils.safeGetObjectField(model, "title")?.toString()
                ?: HookUtils.safeGetObjectField(model, "text")?.toString()
                ?: ""
            if (fallback.isNotEmpty()) {
                matchers.any { it.matches(fallback) }
            } else false
        } catch (_: Exception) {
            false
        }
    }

    private fun isOfficialPromoModel(model: Any): Boolean {
        return try {
            val clazz = model.javaClass

            if (!fieldsResolved) {
                fieldAdType = HookUtils.findFieldRecursively(clazz, "adType")
                fieldAdId = HookUtils.findFieldRecursively(clazz, "adId")
                fieldCustomNickname = HookUtils.findFieldRecursively(clazz, "customNickname")
                fieldUserNameStr = HookUtils.findFieldRecursively(clazz, "userNameStr")
                fieldsResolved = true
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
        } catch (_: Throwable) {
            false
        }
    }
}
