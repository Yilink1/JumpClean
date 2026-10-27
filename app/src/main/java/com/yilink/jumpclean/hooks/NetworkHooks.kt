package com.yilink.jumpclean.hooks

import com.yilink.jumpclean.XposedCompat
import com.yilink.jumpclean.XposedCompat.MethodHookParam
import com.yilink.jumpclean.config.ConfigManager
import com.yilink.jumpclean.config.JumpConstants

/**
 * 全局 OkHttp 网络层动态拦截器
 * 负责集中管理所有接口的阻断、响应清洗与 Mock 数据生成
 */
object NetworkHooks {

    fun hook(classLoader: ClassLoader) {
        try {
            val builderClass = XposedCompat.findClassIfExists("okhttp3.OkHttpClient\$Builder", classLoader) ?: return
            val interceptorClass = XposedCompat.findClassIfExists("okhttp3.Interceptor", classLoader) ?: return

            val interceptorProxy = java.lang.reflect.Proxy.newProxyInstance(
                classLoader,
                arrayOf(interceptorClass)
            ) { _, method, args ->
                if (method.name == "intercept" && args != null && args.isNotEmpty()) {
                    val chain = args[0]
                    val request = XposedCompat.callMethod(chain, "request") ?: return@newProxyInstance null
                    val urlStr = XposedCompat.callMethod(request, "url")?.toString() ?: ""

                    val proceedMethod = chain.javaClass.methods.firstOrNull { it.name == "proceed" && it.parameterTypes.size == 1 }

                    // 1. 动态购买弹幕纯网络断电：/jmall/order/recently 直接返回空数据
                    if (urlStr.contains("/jmall/order/recently")) {
                        if (ConfigManager.isFeatureEnabledSafe(classLoader, JumpConstants.KEY_HIDE_GAME_DYNAMIC_BUBBLE)) {
                            try {
                                val resp = createMockJsonResponse(
                                    classLoader,
                                    request,
                                    """{"success":true,"code":200,"msg":"成功","data":[]}"""
                                )
                                ConfigManager.log("✔ [网络阻断] 拦截动态购买弹幕 (/jmall/order/recently)")
                                return@newProxyInstance resp
                            } catch (e: Throwable) {
                                ConfigManager.logError("✘ 伪造 /jmall/order/recently 响应失败，回退原生请求", e)
                            }
                        }
                    }

                    // 2. 二手比价纯网络断电：/jmall/second_hand/sell_supplier_quotation 直接返回空数据
                    if (urlStr.contains("/jmall/second_hand/sell_supplier_quotation")) {
                        if (ConfigManager.isFeatureEnabledSafe(classLoader, JumpConstants.KEY_HIDE_GAME_SECOND_HAND)) {
                            try {
                                val resp = createMockJsonResponse(
                                    classLoader,
                                    request,
                                    """{"success":true,"code":200,"msg":"成功","data":[]}"""
                                )
                                ConfigManager.log("✔ [网络阻断] 拦截二手比价数据 (/jmall/second_hand/sell_supplier_quotation)")
                                return@newProxyInstance resp
                            } catch (e: Throwable) {
                                ConfigManager.logError("✘ 伪造 /jmall/second_hand/sell_supplier_quotation 响应失败，回退原生请求", e)
                            }
                        }
                    }

                    // 3. 低价榜推广项与导购链接清洗：/jump/price/getAllPriceByGame (过滤假国家广告 + 移除各国 shopLink 去购买)
                    if (urlStr.contains("/jump/price/getAllPriceByGame")) {
                        if (ConfigManager.isFeatureEnabledSafe(classLoader, JumpConstants.KEY_HIDE_GAME_PRICE_ADS)) {
                            val originalResponse = proceedSafe(proceedMethod, chain, request)
                            try {
                                val cleaned = cleanPriceResponse(classLoader, originalResponse)
                                ConfigManager.log("✔ [网络清洗] 过滤低价榜推广项与导购链接 (/jump/price/getAllPriceByGame)")
                                return@newProxyInstance cleaned
                            } catch (e: Throwable) {
                                ConfigManager.logError("✘ 清洗 /jump/price/getAllPriceByGame 异常", e)
                                return@newProxyInstance originalResponse
                            }
                        }
                    }

                    // 4. 游戏扩展信息清洗：/jump/game/ext (移除底栏 preSale 租卡体验 + 充值 priceBanner + 详情页广告)
                    if (urlStr.contains("/jump/game/ext")) {
                        if (ConfigManager.isFeatureEnabledSafe(classLoader, JumpConstants.KEY_HIDE_GAME_BOTTOM_TRIAL_AD)) {
                            val originalResponse = proceedSafe(proceedMethod, chain, request)
                            try {
                                val cleaned = cleanGameExtResponse(classLoader, originalResponse)
                                ConfigManager.log("✔ [网络清洗] 剔除底栏购前体验与充值横幅 (/jump/game/ext)")
                                return@newProxyInstance cleaned
                            } catch (e: Throwable) {
                                ConfigManager.logError("✘ 清洗 /jump/game/ext 异常", e)
                                return@newProxyInstance originalResponse
                            }
                        }
                    }

                    // 5. 促销卡券横幅网络断电：/jmall/product/detail (拦截进入游戏详情页时背景拉取的营销商品)
                    if (urlStr.contains("/jmall/product/detail")) {
                        if (ConfigManager.isFeatureEnabledSafe(classLoader, JumpConstants.KEY_HIDE_GAME_FIND_AD)) {
                            if (urlStr.contains("from=1") || SplashHooks.currentActivityName.contains("GameDetail")) {
                                try {
                                    val resp = createMockJsonResponse(
                                        classLoader,
                                        request,
                                        """{"success":false,"code":404,"msg":"Ad blocked","data":null}"""
                                    )
                                    ConfigManager.log("✔ [网络阻断] 拦截促销点卡卡券横幅 (/jmall/product/detail)")
                                    return@newProxyInstance resp
                                } catch (e: Throwable) {
                                    ConfigManager.logError("✘ 伪造 /jmall/product/detail 响应失败，回退原生请求", e)
                                }
                            }
                        }
                    }

                    // 6. 推荐流与帖子第三方商业广告网络断电：腾讯优量汇 / 广点通 SDK (gdt.qq.com)
                    if (urlStr.contains("gdt.qq.com") || urlStr.contains("gdt_mview")) {
                        if (ConfigManager.isFeatureEnabledSafe(classLoader, JumpConstants.KEY_HIDE_POST_AD)) {
                            try {
                                val resp = createMockJsonResponse(
                                    classLoader,
                                    request,
                                    """{"ret":-1,"msg":"No ad"}"""
                                )
                                ConfigManager.log("✔ [网络阻断] 拦截腾讯优量汇商业广告 SDK (gdt.qq.com)")
                                return@newProxyInstance resp
                            } catch (e: Throwable) {
                                ConfigManager.logError("✘ 伪造 gdt.qq.com 广告响应失败", e)
                            }
                        }
                    }

                    // 7. 推荐流广告总控网络清洗：/jump/recommend/ad_conf (小酱推广贴 adType=8、Jumper热议 adType=106、推荐流商业卡片 adType=101)
                    if (urlStr.contains("/jump/recommend/ad_conf")) {
                        val blockPromo = ConfigManager.isFeatureEnabledSafe(classLoader, JumpConstants.KEY_EXP_BLOCK_OFFICIAL_PROMO_POST)
                        val blockHot = ConfigManager.isFeatureEnabledSafe(classLoader, JumpConstants.KEY_HIDE_HOT_DISCUSS)
                        val blockAd = ConfigManager.isFeatureEnabledSafe(classLoader, JumpConstants.KEY_HIDE_POST_AD)

                        if (blockPromo || blockHot || blockAd) {
                            val originalResponse = proceedSafe(proceedMethod, chain, request)
                            try {
                                val cleaned = cleanRecommendAdConfResponse(classLoader, originalResponse, blockPromo, blockHot, blockAd)
                                return@newProxyInstance cleaned
                            } catch (e: Throwable) {
                                ConfigManager.logError("✘ 清洗 /jump/recommend/ad_conf 异常", e)
                                return@newProxyInstance originalResponse
                            }
                        }
                    }

                    // 8. 首页顶部枢纽数据清洗：/jump/interest_v2/home (按需清空轮播广告 bannerList 和顶部话题栏 promotionList)
                    if (urlStr.contains("/jump/interest_v2/home")) {
                        val hideBanner = ConfigManager.isFeatureEnabledSafe(classLoader, JumpConstants.KEY_HIDE_BANNER)
                        val hideTopicList = ConfigManager.isFeatureEnabledSafe(classLoader, JumpConstants.KEY_HIDE_TOPIC_LIST)
                        if (hideBanner || hideTopicList) {
                            val originalResponse = proceedSafe(proceedMethod, chain, request)
                            try {
                                val cleaned = cleanHomeInterestResponse(classLoader, originalResponse, hideBanner, hideTopicList)
                                val desc = when {
                                    hideBanner && hideTopicList -> "清空轮播与话题栏"
                                    hideBanner -> "清空轮播广告"
                                    else -> "清空顶部话题栏"
                                }
                                ConfigManager.log("✔ [网络清洗] 首页顶部数据 ($desc)")
                                return@newProxyInstance cleaned
                            } catch (e: Throwable) {
                                ConfigManager.logError("✘ 清洗 /jump/interest_v2/home 异常", e)
                                return@newProxyInstance originalResponse
                            }
                        }
                    }

                    // 9. Jumper 热议广场网络断电：/jump/subject/squareList 直接返回空数据
                    if (urlStr.contains("/jump/subject/squareList")) {
                        if (ConfigManager.isFeatureEnabledSafe(classLoader, JumpConstants.KEY_HIDE_HOT_DISCUSS)) {
                            try {
                                val resp = createMockJsonResponse(
                                    classLoader,
                                    request,
                                    """{"success":true,"code":0,"msg":"success","data":[]}"""
                                )
                                ConfigManager.log("✔ [网络阻断] 拦截 Jumper 热议广场 (/jump/subject/squareList)")
                                return@newProxyInstance resp
                            } catch (e: Throwable) {
                                ConfigManager.logError("✘ 伪造 /jump/subject/squareList 响应失败，回退原生请求", e)
                            }
                        }
                    }

                    // 10. 发现页顶部轮播广告网络断电：/jump/findGame/advert (返回空 data 数组)
                    if (urlStr.contains("/jump/findGame/advert")) {
                        if (ConfigManager.isFeatureEnabledSafe(classLoader, JumpConstants.KEY_HIDE_DISCOVER_BANNER)) {
                            try {
                                val resp = createMockJsonResponse(
                                    classLoader,
                                    request,
                                    """{"success":true,"code":0,"msg":"success","data":[]}"""
                                )
                                ConfigManager.log("✔ [网络阻断] 拦截发现页顶部轮播广告 (/jump/findGame/advert)")
                                return@newProxyInstance resp
                            } catch (e: Throwable) {
                                ConfigManager.logError("✘ 伪造 /jump/findGame/advert 响应失败，回退原生请求", e)
                            }
                        }
                    }

                    // 11. 营销活动与引导弹窗网络断电：/jump/nice/free_nice_dialog 与 /jump/ad/game/dialog
                    if (urlStr.contains("/jump/nice/free_nice_dialog") || urlStr.contains("/jump/ad/game/dialog")) {
                        if (ConfigManager.isFeatureEnabledSafe(classLoader, JumpConstants.KEY_HIDE_VOUCHER_POPUP)) {
                            try {
                                val resp = createMockJsonResponse(
                                    classLoader,
                                    request,
                                    """{"success":true,"code":200,"msg":"success","data":null}"""
                                )
                                val endpoint = if (urlStr.contains("free_nice")) "free_nice_dialog" else "ad/game/dialog"
                                ConfigManager.log("✔ [网络阻断] 拦截营销弹窗配置接口: $endpoint")
                                return@newProxyInstance resp
                            } catch (e: Throwable) {
                                ConfigManager.logError("✘ 伪造营销弹窗响应失败", e)
                            }
                        }
                    }

                    // 12. 发现页推荐模块数据网络断电：findGame/list_new, findPageV2/*
                    if (urlStr.contains("/jump/findGame/list_new") ||
                        urlStr.contains("/jump/findPageV2/favorite") ||
                        urlStr.contains("/jump/findPageV2/newHotGame") ||
                        urlStr.contains("/jump/findPageV2/lt40") ||
                        urlStr.contains("/jump/findPageV2/comingSoon")
                    ) {
                        if (ConfigManager.isFeatureEnabledSafe(classLoader, JumpConstants.KEY_HIDE_DISCOVER_MODULES)) {
                            try {
                                val resp = createMockJsonResponse(
                                    classLoader,
                                    request,
                                    """{"success":true,"code":0,"msg":"success","data":[]}"""
                                )
                                val endpoint = urlStr.substringBefore("?").substringAfterLast("/")
                                ConfigManager.log("✔ [网络阻断] 拦截发现页模块数据 ($endpoint)")
                                return@newProxyInstance resp
                            } catch (e: Throwable) {
                                ConfigManager.logError("✘ 伪造发现页模块数据响应失败，回退原生请求", e)
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

            XposedCompat.findAndHookMethod(builderClass, "build", object : XposedCompat.MethodHookCallback() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        val builder = param.thisObject
                        val interceptors = XposedCompat.callMethod(builder, "interceptors") as? MutableList<Any>
                        if (interceptors != null && !interceptors.contains(interceptorProxy)) {
                            interceptors.add(0, interceptorProxy)
                        } else {
                            XposedCompat.callMethod(builder, "addInterceptor", interceptorProxy)
                        }
                    } catch (e: Throwable) {
                        ConfigManager.logError("✘ 注入 OkHttp 拦截器异常", e)
                    }
                }
            })
            ConfigManager.log("[Hook] OkHttp 网络层动态拦截器已挂载")
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
            return XposedCompat.callMethod(chain, "proceed", request) ?: throw java.io.IOException("Proceed returned null")
        } catch (e: Throwable) {
            val cause = e.cause ?: e
            if (cause is java.io.IOException) {
                throw cause
            }
            throw java.io.IOException(cause)
        }
    }

    private fun cleanJsonResponse(classLoader: ClassLoader, response: Any, transform: (String) -> String): Any {
        val body = XposedCompat.callMethod(response, "body") ?: return response
        val maxBytes = 4L * 1024 * 1024
        val snapshot = XposedCompat.callMethod(response, "peekBody", maxBytes + 1)
        val bodyString = try {
            val size = XposedCompat.callMethod(snapshot, "contentLength") as? Long ?: return response
            if (size > maxBytes) return response
            XposedCompat.callMethod(snapshot, "string") as? String ?: return response
        } finally {
            XposedCompat.callMethod(snapshot, "close")
        }
        val cleanJson = transform(bodyString)
        if (cleanJson == bodyString) return response
        val mediaType = XposedCompat.callMethod(body, "contentType")
        val newBody = createResponseBody(classLoader, mediaType, cleanJson)
        val newBuilder = XposedCompat.callMethod(response, "newBuilder")
        XposedCompat.callMethod(newBuilder, "body", newBody)
        XposedCompat.callMethod(newBuilder, "removeHeader", "Content-Length")
        val cleaned = XposedCompat.callMethod(newBuilder, "build")
        try { XposedCompat.callMethod(body, "close") } catch (_: Throwable) {}
        return cleaned ?: response
    }

    private fun cleanPriceResponse(classLoader: ClassLoader, response: Any): Any {
        return cleanJsonResponse(classLoader, response, ::cleanPriceListJson)
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
                if (adTypes.contains(type) || adCountryNames.contains(country)) {
                    continue
                }
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
        return cleanJsonResponse(classLoader, response, ::cleanGameExtJson)
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
        return cleanJsonResponse(classLoader, response) {
            cleanHomeInterestJson(it, hideBanner, hideTopicList)
        }
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

    private fun cleanRecommendAdConfResponse(
        classLoader: ClassLoader,
        response: Any,
        blockPromo: Boolean,
        blockHot: Boolean,
        blockAd: Boolean
    ): Any {
        var blockedPromoCount = 0
        val cleaned = cleanJsonResponse(classLoader, response) {
            cleanRecommendAdConfJson(it, blockPromo, blockHot, blockAd) { count ->
                blockedPromoCount = count
            }
        }
        if (cleaned !== response) {
            repeat(blockedPromoCount) { ConfigManager.recordOfficialPromoBlocked() }
        }
        return cleaned ?: response
    }

    private fun cleanRecommendAdConfJson(
        jsonStr: String,
        blockPromo: Boolean,
        blockHot: Boolean,
        blockAd: Boolean,
        onPromoBlocked: (Int) -> Unit
    ): String {
        try {
            val root = org.json.JSONObject(jsonStr)
            val data = root.optJSONArray("data") ?: return jsonStr
            val newData = org.json.JSONArray()
            var modified = false
            var blockedPromoCount = 0

            for (i in 0 until data.length()) {
                val rawPage = data.get(i)
                val pageItem = rawPage as? org.json.JSONObject
                val ads = pageItem?.optJSONArray("ads")
                if (pageItem == null || ads == null) {
                    newData.put(rawPage)
                    continue
                }
                val newAds = org.json.JSONArray()

                for (j in 0 until ads.length()) {
                    val rawAd = ads.get(j)
                    val ad = rawAd as? org.json.JSONObject
                    val adType = ad?.opt("adType")

                    // 1. 小酱推广贴 (adType == 8)
                    if (blockPromo && PromoRules.isOfficialPromoType(adType)) {
                        blockedPromoCount++
                        modified = true
                        continue
                    }

                    // 2. Jumper热议 (adType == 106)
                    if (blockHot && (adType == 106 || adType == "106")) {
                        modified = true
                        continue
                    }

                    // 3. 商业广告卡片 (adType == 101)
                    if (blockAd && (adType == 101 || adType == "101")) {
                        modified = true
                        continue
                    }

                    newAds.put(rawAd)
                }

                pageItem.put("ads", newAds)
                newData.put(pageItem)
            }

            if (!modified) return jsonStr
            root.put("data", newData)
            val result = root.toString()
            onPromoBlocked(blockedPromoCount)
            return result
        } catch (_: Throwable) {
            return jsonStr
        }
    }

    private fun createResponseBody(classLoader: ClassLoader, mediaType: Any?, content: String): Any {
        val responseBodyClass = XposedCompat.findClass("okhttp3.ResponseBody", classLoader)
        val mediaTypeClass = XposedCompat.findClass("okhttp3.MediaType", classLoader)
        val actualMediaType = mediaType ?: try {
            XposedCompat.callStaticMethod(mediaTypeClass, "parse", "application/json; charset=utf-8")
        } catch (_: Throwable) {
            val companion = XposedCompat.getStaticObjectField(mediaTypeClass, "Companion")
            XposedCompat.callMethod(companion, "parse", "application/json; charset=utf-8")
        }

        val created = try {
            XposedCompat.callStaticMethod(responseBodyClass, "create", actualMediaType, content)
        } catch (_: Throwable) {
            try {
                val companion = XposedCompat.getStaticObjectField(responseBodyClass, "Companion")
                XposedCompat.callMethod(companion, "create", actualMediaType, content)
            } catch (_: Throwable) {
                XposedCompat.callStaticMethod(responseBodyClass, "create", content, actualMediaType)
            }
        }
        return created ?: throw IllegalStateException("Failed to create response body")
    }

    private fun createMockJsonResponse(classLoader: ClassLoader, request: Any, jsonStr: String): Any {
        val responseBuilderClass = XposedCompat.findClass("okhttp3.Response\$Builder", classLoader)
        val protocolClass = XposedCompat.findClass("okhttp3.Protocol", classLoader)
        val responseBody = createResponseBody(classLoader, null, jsonStr)

        val http11 = XposedCompat.getStaticObjectField(protocolClass, "HTTP_1_1")
        val builder = responseBuilderClass.getDeclaredConstructor().newInstance()
        XposedCompat.callMethod(builder, "request", request)
        XposedCompat.callMethod(builder, "protocol", http11)
        XposedCompat.callMethod(builder, "code", 200)
        XposedCompat.callMethod(builder, "message", "OK")
        XposedCompat.callMethod(builder, "body", responseBody)
        return XposedCompat.callMethod(builder, "build") ?: throw IllegalStateException("Build response failed")
    }
}
