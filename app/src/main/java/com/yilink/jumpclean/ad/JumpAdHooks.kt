package com.yilink.jumpclean.ad

import com.yilink.jumpclean.config.ConfigManager
import com.yilink.jumpclean.config.JumpConstants
import com.yilink.jumpclean.hooks.FeatureHooks
import com.yilink.jumpclean.hooks.FeedHooks
import com.yilink.jumpclean.hooks.SettingsEntryHooks
import com.yilink.jumpclean.hooks.SplashHooks
import com.yilink.jumpclean.hooks.ViewCleanHooks
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * JumpClean — Jump App 界面净化与体验增强总调度器
 */
object JumpAdHooks {

    fun hook(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != JumpConstants.TARGET_PACKAGE) return

        ConfigManager.init(lpparam)
        ConfigManager.log("JumpClean 开始加载")

        // 1. 框架与开屏加速层（秒跳 Splash、Byazt 快速失败、延迟压缩、营销弹窗阻断）
        SplashHooks.hook(lpparam)

        // 2. 数据与渲染层（BRV Adapter 数据源拦截、小酱推广贴物理移除、关键词过滤）
        FeedHooks.hook(lpparam)

        // 3. 视图净化层（首页/发现页控件隐藏、消除 46px 白缝、通用广告容器、评价遮罩）
        ViewCleanHooks.hook(lpparam)

        // 4. 特性增强层（网页/原生文本长按复制、帖子发布年份完整还原）
        FeatureHooks.hook(lpparam)

        // 5. 设置入口挂载（原生设置页第一项插入、长按「我的」Tab 快捷入口）
        SettingsEntryHooks.hook(lpparam)

        ConfigManager.log("JumpClean 加载完成")
    }
}