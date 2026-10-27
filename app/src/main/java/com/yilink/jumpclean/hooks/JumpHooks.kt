package com.yilink.jumpclean.hooks

import com.yilink.jumpclean.config.ConfigManager
import com.yilink.jumpclean.config.JumpConstants

/**
 * JumpClean — Jump App 界面净化与体验增强总调度器
 */
object JumpHooks {

    fun hook(classLoader: ClassLoader) {
        ConfigManager.init(classLoader)
        ConfigManager.log("[Init] JumpClean 开始加载")

        // 1. 框架与开屏加速层（秒跳 Splash、Byazt 快速失败、延迟压缩、营销弹窗阻断）
        SplashHooks.hook(classLoader)

        // 2. 网络层全网拦截与响应清洗（OkHttp 动态拦截器：推广贴/轮播广告/热议/比价/营销弹窗断电）
        NetworkHooks.hook(classLoader)

        // 3. 数据与渲染层（BRV Adapter 数据源拦截、小酱推广贴物理移除、关键词过滤）
        FeedHooks.hook(classLoader)

        // 4. 视图净化层（首页/发现页控件隐藏、消除 46px 白缝、通用广告容器、评价遮罩）
        HomeHooks.hook(classLoader)
        ContentDetailHooks.hook(classLoader)
        GameDetailHooks.hook(classLoader)
        MyPageHooks.hook(classLoader)

        // 5. 特性增强层（网页/原生文本长按复制、帖子发布年份完整还原）
        FeatureHooks.hook(classLoader)

        // 6. 设置入口挂载（原生设置页第一项插入、长按「我的」Tab 快捷入口）
        SettingsEntryHooks.hook(classLoader)

        // 7. 实验性视觉层（底栏液态玻璃物理折射）
        LiquidGlassHooks.hook(classLoader)

        // 8. 夜间护眼（纯黑底色 → 深灰，可选柔化纯白文字）
        EyeCareHooks.hook(classLoader)

        ConfigManager.log("[Init] JumpClean 加载完成")
    }
}
