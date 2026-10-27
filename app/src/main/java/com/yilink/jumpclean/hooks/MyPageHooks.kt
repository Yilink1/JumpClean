package com.yilink.jumpclean.hooks

import com.yilink.jumpclean.XposedCompat
import com.yilink.jumpclean.XposedCompat.MethodHookParam

import com.yilink.jumpclean.config.ConfigManager
import com.yilink.jumpclean.config.JumpConstants

/**
 * 个人中心模块管理与防瞬时闪烁引擎
 * 1. 列表数据源级模块过滤（照片墙按需剔除，杜绝异步加载漏网与 0x0 布局抖动）
 * 2. 真实网络快照 → 空壳判定 → 就地回填（抑制心愿单、游戏墙、动态等模块闪烁）
 * 3. 反射元数据静态缓存池（Method / Field 终生只查一次，零性能损耗）
 */
object MyPageHooks {

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

    fun hook(classLoader: ClassLoader) {
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

            // 2. 挂载 RecyclerUtilsKt.b: 列表数据源注入入口 (防抖回填 + 照片墙数据级精准剔除)
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
                            val list = param.args.getOrNull(1) as? List<*> ?: return

                            // 数据源级精准拦截：隐藏截图展示墙 (彻底消除 View 层 0x0 折叠抖动与图片资源浪费)
                            val hidePhotoWall = ConfigManager.isFeatureEnabledSafe(classLoader, JumpConstants.KEY_HIDE_PHOTO_WALL)
                            if (hidePhotoWall) {
                                val hasPhotoWall = list.any { it != null && it.javaClass.name.endsWith("UserCardMemberPhotoWall") }
                                if (hasPhotoWall) {
                                    val mutableList = (list as? MutableList<*>) ?: ArrayList(list)
                                    val iterator = mutableList.iterator()
                                    while (iterator.hasNext()) {
                                        val item = iterator.next()
                                        if (item != null && item.javaClass.name.endsWith("UserCardMemberPhotoWall")) {
                                            iterator.remove()
                                            ConfigManager.log("✔ [数据剔除] 个人主页移除截图展示墙 (UserCardMemberPhotoWall)")
                                        }
                                    }
                                    param.args[1] = mutableList
                                }
                            }

                            if (!ConfigManager.isFeatureEnabledSafe(classLoader, JumpConstants.KEY_PREVENT_WISHLIST_FLASH)) {
                                return
                            }
                            val curList = param.args.getOrNull(1) as? List<*> ?: return
                            for (item in curList) {
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
