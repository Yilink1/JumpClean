package com.yilink.jumpclean.config

data class JumpIconModel(
    val name: String,
    val drawableName: String,
    val targetClass: String,
    val shortKey: String
)

object JumpConstants {

    const val TAG = "JumpClean"
    const val PREFS_NAME = "jumpclean_settings"
    const val TARGET_PACKAGE = "com.vgjump.jump"
    const val SETTING_ITEM_TITLE = "JumpClean 设置"

    // 自重启标志 Extra Key
    const val EXTRA_ICON_RESTART = "jumpclean_icon_restart"

    // 功能开关 Key
    const val KEY_SKIP_SPLASH = "skip_splash"
    const val KEY_HIDE_BANNER = "hide_banner"
    const val KEY_HIDE_TOPIC_LIST = "hide_topic_list"
    const val KEY_HIDE_HOT_DISCUSS = "hide_hot_discuss"
    const val KEY_HIDE_PUBLISH_TOPIC = "hide_publish_topic"
    const val KEY_HIDE_POST_AD = "hide_post_ad"
    const val KEY_HIDE_DISCOVER_TOP_AD = "hide_discover_top_ad"
    const val KEY_HIDE_DISCOVER_BANNER = "hide_discover_banner"
    const val KEY_HIDE_PHOTO_WALL = "hide_photo_wall"
    const val KEY_HIDE_MEMBER_CARD = "hide_member_card"
    const val KEY_HIDE_MY_ORDER = "hide_my_order"
    const val KEY_HIDE_WEB_TAB = "hide_web_tab"
    const val KEY_HIDE_LOTTERY_TAB = "hide_lottery_tab"
    const val KEY_HIDE_MSG_PUSH_GUIDE = "hide_msg_push_guide"
    const val KEY_HIDE_WIDGET_VIP_TAG = "hide_widget_vip_tag"
    const val KEY_ENABLE_COPY = "enable_article_copy"
    const val KEY_HIDE_CONTENT_MEMBER_MASK = "hide_content_member_mask"
    const val KEY_ENABLE_DEBUG_LOG = "enable_debug_log"
    const val KEY_RESTORE_POST_YEAR = "restore_post_year"

    // 弹窗与小酱
    const val KEY_HIDE_VOUCHER_POPUP = "hide_voucher_popup"
    const val KEY_EXP_BLOCK_OFFICIAL_PROMO_POST = "exp_block_official_promo_post"
    const val KEY_BLOCKED_OFFICIAL_PROMO_COUNT = "blocked_official_promo_count"

    // 关键词屏蔽
    const val KEY_ENABLE_KEYWORD_BLOCK = "enable_keyword_block"
    const val KEY_BLOCKED_KEYWORDS = "blocked_keywords"
    const val KEY_KEYWORD_BLOCK_SCOPE = "keyword_block_scope"
    const val SCOPE_RECOMMEND = "recommend"
    const val SCOPE_GLOBAL = "global"

    // Byazt SDK 内部常量
    const val KEY_EVENT_CODE = -0x5f5e0f3
    const val KEY_CLASS_TYPE = -0x5f5e0f1
    const val EVENT_LOAD_FAIL = 0x1bdb7
    const val KEY_ERROR_CODE = 0x40359
    const val KEY_ERROR_MSG = 0x4035a

    // 延迟压缩参数
    const val STARTUP_WINDOW_MS = 6000L
    const val MIN_DELAY_TO_COMPRESS = 400L
    const val COMPRESSED_DELAY = 0L

    // 布局监听节流阈值
    const val THROTTLE_INTERVAL_MS = 50L

    // 静态预编译正则
    val REGEX_YEAR_PREFIX = Regex("""^\d{4}-""")
    val REGEX_HAS_YEAR = Regex("""\b\d{4}\b""")
    val REGEX_MM_DD = Regex("""\b(\d{2}-\d{2})\b""")
    val REGEX_FULL_DATE = Regex("""^(\d{4})-(\d{2}-\d{2})""")
    val REGEX_IMAGE_CLEAN = Regex("""(?i)\s*image""")

    // 广告 Layout 资源黑名单
    val POST_AD_LAYOUT_NAMES = setOf(
        "content_list_ad_sdk_item",
        "content_list_ad_steam_price_item",
        "content_list_ad_lottery_item",
        "content_list_waterfall_ad_sdk_item",
        "content_list_waterfall_ad_lottery_item",
        "content_list_waterfall_ad_steam_price_item"
    )

    val HOT_DISCUSS_LAYOUT_NAMES = setOf(
        "content_list_topic_discuss_item",
        "content_list_hot_discuss_item",
        "content_home_hot_discuss_item",
        "content_home_topic_item"
    )

    // Intent ID 查询 Key 列表
    val INTENT_ID_KEYS = listOf(
        "content_id", "evaluate_id", "evaluateId", "postId", "id", "topic_id", "topicId", "commentId"
    )

    // 21 组物理级精准图标映射
    val OFFICIAL_ICONS = listOf(
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
        JumpIconModel("多彩", "member_change_icon_colorful", "com.vgjump.jump.icon_colorful", "launch_colorful"),
        JumpIconModel("Switch", "member_change_icon_switch", "com.vgjump.jump.icon_switch", "launch_alias_switch"),
        JumpIconModel("NES", "member_change_icon_nes", "com.vgjump.jump.icon_nes", "launch_alias_nes"),
        JumpIconModel("PS2", "member_change_icon_ps2", "com.vgjump.jump.icon_ps2", "launch_alias_ps2"),
        JumpIconModel("XBOX", "member_change_icon_xbox", "com.vgjump.jump.icon_xbox", "launch_alias_xbox"),
        JumpIconModel("DC", "member_change_icon_dc", "com.vgjump.jump.icon_dc", "launch_alias_dc")
    )
}
