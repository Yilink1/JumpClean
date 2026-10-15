package com.yilink.jumpclean.hooks

internal object PromoRules {
    // 已抓包确认：推荐广告配置中 adType=8 为小酱推广。
    fun isOfficialPromoType(value: Any?): Boolean = when (value) {
        is Number -> value.toDouble() == 8.0
        is String -> value == "8"
        else -> false
    }
}
