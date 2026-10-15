package com.yilink.jumpclean.hooks

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.text.Editable
import android.text.TextWatcher
import com.example.liquidglass.LiquidGlassTabBar
import com.yilink.jumpclean.HookUtils
import com.yilink.jumpclean.config.ConfigManager
import com.yilink.jumpclean.config.JumpConstants
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.ref.WeakReference

/**
 * 底栏液态玻璃悬浮胶囊 Hook 调度器（v5）
 *
 * v4 修正点：
 * - 原底栏子 RadioButton 全部禁止触摸（不只是 alpha=0）
 * - Tab 文字反射居中（解决偏左问题）
 * - RecyclerView 留白只给当前可见 Fragment 主列表，深度限 2 层，不递归全量误伤
 */
object LiquidGlassHooks {

    private const val TAG_GLASS = "jumpclean_liquid_glass_nav"
    private const val TAG_TAB_MAP = -0x7f001005
    private const val TAG_SAVED_HEIGHT = -0x7f001004
    private const val TAG_SAVED_PADDING = -0x7f001003
    private const val TAG_TABS_WATCHED = -0x7f001002

    /** 由宿主驱动的选中同步期间置位，避免玻璃条的 onTabSelected 再反向 performClick 一次 */
    @Volatile private var suppressSelect = false


    /** 当前存活的 MainActivity，供设置页（不同 Activity）触发实时刷新 */
    private var mainActivityRef: WeakReference<Activity>? = null

    fun hook(lpparam: XC_LoadPackage.LoadPackageParam) {
        val mainActivityClass = XposedHelpers.findClassIfExists(
            "com.vgjump.jump.ui.main.MainActivity",
            lpparam.classLoader
        ) ?: return

        // ── WebView 一创建就开硬件层（库文档：玻璃背景里的 WebView 必须有硬件层，否则静止也每帧重绘→闪烁/白条）。
        //    只处理 MainActivity 里的 WebView，其它页面（文章页等）不动。
        //    同时登记弱引用：① 运行中才打开玻璃时补开硬件层 ② 供文字明暗采样判断「有没有可见 WebView」，
        //    不再靠周期性遍历整棵 View 树去找 ──
        try {
            XposedBridge.hookAllConstructors(android.webkit.WebView::class.java, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val v = param.thisObject as? View ?: return
                        val a = unwrapActivity(v.context) ?: return
                        if (!a.javaClass.name.endsWith(".MainActivity")) return
                        synchronized(webViews) { webViews.add(WeakReference(v)) }
                        if (ConfigManager.isFeatureEnabledSafe(lpparam.classLoader, JumpConstants.KEY_ENABLE_LIQUID_GLASS_NAV)) {
                            enableWebLayer(v)
                        }
                    } catch (_: Throwable) {}
                }
            })
        } catch (t: Throwable) {
            ConfigManager.logError("[LiquidGlass] WebView 构造 Hook 失败", t)
        }

        try {
            // ── RadioGroup.check 双向同步：切页 → 水滴联动 ──
            XposedHelpers.findAndHookMethod(
                RadioGroup::class.java,
                "check",
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val group = param.thisObject as? RadioGroup ?: return
                        val checkedId = param.args[0] as Int
                        val activity = unwrapActivity(group.context) ?: return
                        val glass = findGlassPill(activity) ?: return
                        val tabMap = glass.getTag(TAG_TAB_MAP) as? List<*> ?: return

                        @Suppress("UNCHECKED_CAST")
                        val tabs = tabMap as? List<Pair<String, Int>> ?: return
                        val newIndex = tabs.indexOfFirst { it.second == checkedId }
                        if (newIndex >= 0 && glass.selectedIndex != newIndex) {
                            setSelectedQuiet(glass, newIndex)
                        }
                        // 切页后新 Fragment 的主 RecyclerView 也需要底部留白
                        val cId = HookUtils.getCachedResId(activity, "containerFra").takeIf { it != 0 } ?: return
                        val cFra = activity.findViewById<View>(cId) as? ViewGroup
                        cFra?.postDelayed({
                            padVisibleFragmentRV(cFra, contentPadPx(activity, GlassStyle.load()), true)
                        }, 300)
                    }
                }
            )

            // ── onCreate → 初始挂载 ──
            XposedHelpers.findAndHookMethod(
                mainActivityClass,
                "onCreate",
                android.os.Bundle::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val activity = param.thisObject as? Activity ?: return
                        mainActivityRef = WeakReference(activity)
                        activity.window?.decorView?.post {
                            runSafe("onCreate") { applyLiquidGlass(activity, lpparam.classLoader) }
                        }
                    }
                }
            )

            // ── onResume → 热更新 + 重绘 ──
            XposedHelpers.findAndHookMethod(
                mainActivityClass,
                "onResume",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val activity = param.thisObject as? Activity ?: return
                        mainActivityRef = WeakReference(activity)
                        activity.window?.decorView?.post {
                            runSafe("onResume") {
                                applyLiquidGlass(activity, lpparam.classLoader)
                                findGlassPill(activity)?.invalidate()
                            }
                        }
                    }
                }
            )

            ConfigManager.log("[Hook] 液态玻璃视觉调度器已加载")
        } catch (t: Throwable) {
            ConfigManager.logError("[LiquidGlass] Hook 总线异常", t)
        }
    }

    // ════════════════════════════════════════════════════
    //  核心：挂载 / 卸载 / 热更新
    // ════════════════════════════════════════════════════

    /**
     * 供「液态玻璃参数微调」保存后调用。
     * 设置页是另一个 Activity（SettingActivity），里面没有 myTab，
     * 所以必须作用到记录下来的 MainActivity 上。
     */
    @JvmStatic
    fun refreshFromSettings() {
        val activity = mainActivityRef?.get() ?: return
        if (activity.isFinishing) return
        activity.window?.decorView?.post {
            runSafe("refreshFromSettings") {
                applyLiquidGlass(activity, activity.classLoader)
                findGlassPill(activity)?.let { it.requestLayout(); it.invalidate() }
            }
        }
    }

    /** 当前 Activity 里的真实玻璃底栏（不在主页则为 null） */
    fun pillOf(activity: Activity): JumpGlassTabBar? = findGlassPill(activity)

    /**
     * 把一组「未保存」的参数直接作用到真实底栏，设置面板拖滑块时实时看效果。
     * 不写 SharedPreferences；面板取消时由 [refreshFromSettings] 还原为已保存的参数。
     */
    fun applyStyleLive(activity: Activity, s: GlassStyle.Params) {
        try {
            val pill = findGlassPill(activity) ?: return
            val frame = activity.findViewById<FrameLayout>(android.R.id.content) ?: return
            GlassStyle.applyTo(pill, s, activity.resources.displayMetrics.density)
            pill.layoutParams = pillParams(activity, s)
            ensureShadow(activity, frame, pill, s)
            pill.post { pill.applyLabelStyle() }
            pill.requestLayout()
            pill.invalidate()
        } catch (t: Throwable) {
            ConfigManager.logError("[LiquidGlass] applyStyleLive 异常", t)
        }
    }

    fun applyLiquidGlass(activity: Activity, classLoader: ClassLoader) {
        val isEnabled = ConfigManager.isFeatureEnabledSafe(
            classLoader, JumpConstants.KEY_ENABLE_LIQUID_GLASS_NAV
        )

        val myTabId = HookUtils.getCachedResId(activity, "myTab").takeIf { it != 0 } ?: return
        val myTab = activity.findViewById<View>(myTabId) ?: return
        val rgMainTab = myTab.parent as? RadioGroup ?: return
        val clMainContent = rgMainTab.parent as? ViewGroup ?: return
        val contentFrame = activity.findViewById<FrameLayout>(android.R.id.content) ?: return
        // 取不到资源 id 就不碰（以前这里回退到写死的 0x7F0A02F7，宿主一升级就可能指到别的 View 上）
        val containerFra = HookUtils.getCachedResId(activity, "containerFra")
            .takeIf { it != 0 }?.let { activity.findViewById<View>(it) }

        val existing = findGlassPill(activity)

        if (!isEnabled) {
            uninstall(existing, rgMainTab, clMainContent, containerFra, activity)
            return
        }

        val style = GlassStyle.load()
        val density = activity.resources.displayMetrics.density

        // ── 1. 内容穿透：containerFra 全屏 ──
        if (containerFra != null) {
            val lp = containerFra.layoutParams
            if (lp != null && lp.height != ViewGroup.LayoutParams.MATCH_PARENT) {
                if (containerFra.getTag(TAG_SAVED_HEIGHT) == null) {
                    containerFra.setTag(TAG_SAVED_HEIGHT, lp.height)
                }
                lp.height = ViewGroup.LayoutParams.MATCH_PARENT
                containerFra.layoutParams = lp
                ConfigManager.log("[LiquidGlass] containerFra 已拉伸至 MATCH_PARENT")
            }
            // 只给当前可见 Fragment 的主滚动容器加底部留白（不递归全量）
            val extraPad = contentPadPx(activity, style)
            containerFra.post { padVisibleFragmentRV(containerFra as? ViewGroup, extraPad, true) }
        }

        // ── 2. 真正禁用原底栏触摸 ──
        //    alpha=0 只是视觉隐藏，用户仍能摸到 RadioButton
        //    必须遍历子控件禁用 click/longClick/focusable
        //    保留 RadioGroup.isEnabled=true 让 check() 程序调用仍然生效
        disableBarTouch(rgMainTab)

        // 隐藏分割线
        for (i in 0 until clMainContent.childCount) {
            val child = clMainContent.getChildAt(i)
            if (child !== containerFra && child !== rgMainTab && child.tag != TAG_GLASS) {
                val h = child.layoutParams?.height ?: -1
                // 0 在 ConstraintLayout 里是 MATCH_CONSTRAINT（不是细线），只处理 1px ~ 2dp 的分割线
                if (h in 1..dp(activity, 2)) child.visibility = View.GONE
            }
        }

        // ── 3. 动态读取可见 Tab ──
        val visibleTabs = readVisibleTabs(rgMainTab)
        if (visibleTabs.isEmpty()) return

        // ── 4. 热更新已有实例 ──
        if (existing != null) {
            // 「隐藏 Jump 赏 / 抽奖」等开关会改变可见 Tab，必须同步到玻璃条，
            // 否则 Tab 数量、文字、点击映射都会和原底栏错位
            syncTabsIfChanged(existing, rgMainTab)
            existing.sampleSource = containerFra
            existing.webGuard = { hasVisibleWebView() }
            applyWebLayers()
            GlassStyle.applyTo(existing, style, density)
            existing.layoutParams = pillParams(activity, style)
            ensureShadow(activity, contentFrame, existing, style)
            watchHostTabs(activity, rgMainTab)
            existing.post { existing.applyLabelStyle() }
            existing.requestLayout()
            existing.invalidate()
            return
        }

        // ── 5. 首次创建 LiquidGlassTabBar ──
        val tabBar = JumpGlassTabBar(activity).apply {
            tag = TAG_GLASS
            setTag(TAG_TAB_MAP, visibleTabs)
            setTabs(visibleTabs.map { LiquidGlassTabBar.TabItem(it.first) })

            // 初始选中与原生底栏对齐
            val currentChecked = rgMainTab.checkedRadioButtonId
            val initIndex = visibleTabs.indexOfFirst { it.second == currentChecked }
            if (initIndex >= 0) selectedIndex = initIndex

            sampleSource = containerFra
            webGuard = { hasVisibleWebView() }
            GlassStyle.applyTo(this, style, density)

            // 点击切页
            onTabSelected = { index ->
                runSafe("onTabSelected") {
                    @Suppress("UNCHECKED_CAST")
                    val tabs = (getTag(TAG_TAB_MAP) as? List<Pair<String, Int>>) ?: emptyList()
                    if (!suppressSelect && index in tabs.indices) {
                        val targetId = tabs[index].second
                        if (rgMainTab.checkedRadioButtonId == targetId) {
                            activity.findViewById<View>(targetId)?.performClick()
                        } else {
                            rgMainTab.check(targetId)
                        }
                    }
                }
            }
        }

        // ── 6. 挂载到 android.R.id.content (FrameLayout) ──
        contentFrame.addView(tabBar, pillParams(activity, style))
        applyWebLayers()
        ensureShadow(activity, contentFrame, tabBar, style)
        watchHostTabs(activity, rgMainTab)

        // ── 7. Tab 文字大小/居中（setTabs 重建了标签，需要再套一次） ──
        tabBar.post { tabBar.applyLabelStyle() }

        ConfigManager.log("[LiquidGlass] ✔ 液态水滴底栏已挂载 (${visibleTabs.size} 个 Tab)")
    }

    // ════════════════════════════════════════════════════
    //  WebView 硬件层（库文档要求：玻璃背景里有 WebView 时必须给它开硬件层，否则静止页面也会每帧重绘→闪烁/白条）
    // ════════════════════════════════════════════════════

    private val webViews = ArrayList<WeakReference<View>>()

    private fun enableWebLayer(v: View) {
        if (v.layerType != View.LAYER_TYPE_HARDWARE) {
            v.setLayerType(View.LAYER_TYPE_HARDWARE, null)
            ConfigManager.log("[LiquidGlass] WebView 已开硬件层: " + v.javaClass.name)
        }
    }

    /** 对已登记的 WebView 补开硬件层（运行中才打开玻璃的情况），顺便清掉已回收的引用 */
    private fun applyWebLayers() {
        synchronized(webViews) {
            val it = webViews.iterator()
            while (it.hasNext()) {
                val v = it.next().get()
                if (v == null) it.remove() else enableWebLayer(v)
            }
        }
    }

    private fun hasVisibleWebView(): Boolean = synchronized(webViews) {
        webViews.any { it.get()?.isShown == true }
    }

    // ════════════════════════════════════════════════════
    //  跟随宿主底栏：文字 / 显隐变化实时同步
    // ════════════════════════════════════════════════════

    private fun readVisibleTabs(rg: RadioGroup): List<Pair<String, Int>> {
        val out = mutableListOf<Pair<String, Int>>()
        for (i in 0 until rg.childCount) {
            val rb = rg.getChildAt(i) as? RadioButton ?: continue
            if (rb.visibility == View.VISIBLE) out.add(rb.text.toString() to rb.id)
        }
        return out
    }

    private fun setSelectedQuiet(glass: JumpGlassTabBar, index: Int) {
        suppressSelect = true
        try { glass.selectedIndex = index } finally { suppressSelect = false }
    }

    /** 宿主 Tab 的文字/显隐变了就重建玻璃条的标签（宿主会在接口回来后改文字，比如「买游戏」→「Jump 赏」） */
    private fun syncTabsIfChanged(glass: JumpGlassTabBar, rg: RadioGroup) {
        val now = readVisibleTabs(rg)
        if (now.isEmpty()) return
        @Suppress("UNCHECKED_CAST")
        val old = glass.getTag(TAG_TAB_MAP) as? List<Pair<String, Int>>
        if (old == now) return
        glass.setTag(TAG_TAB_MAP, now)
        glass.setTabs(now.map { LiquidGlassTabBar.TabItem(it.first) })
        val idx = now.indexOfFirst { it.second == rg.checkedRadioButtonId }
        if (idx >= 0) setSelectedQuiet(glass, idx)
        glass.post { glass.applyLabelStyle() }
        ConfigManager.log("[LiquidGlass] Tab 已同步: " + now.joinToString(" | ") { it.first })
    }

    private fun watchHostTabs(activity: Activity, rg: RadioGroup) {
        if (rg.getTag(TAG_TABS_WATCHED) != null) return
        rg.setTag(TAG_TABS_WATCHED, true)
        val ref = WeakReference(activity)
        val rgRef = WeakReference(rg)
        val resync = Runnable {
            val a = ref.get() ?: return@Runnable
            val g = rgRef.get() ?: return@Runnable
            val glass = findGlassPill(a) ?: return@Runnable
            runSafe("syncTabs") { syncTabsIfChanged(glass, g) }
        }
        for (i in 0 until rg.childCount) {
            val rb = rg.getChildAt(i) as? RadioButton ?: continue
            rb.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    if (GlassStyle.devTrace()) traceTabChange(rb, "text=" + s)
                    rg.removeCallbacks(resync)
                    rg.postDelayed(resync, 80)
                }
            })
        }
        // 显隐变化（隐藏 Jump 赏 / 抽奖等）没有专门回调，用布局回调做廉价比对（节流 200ms，避免滚动时每次布局都分配列表）
        var lastLayoutSync = 0L
        rg.viewTreeObserver.addOnGlobalLayoutListener {
            val g = rgRef.get() ?: return@addOnGlobalLayoutListener
            val a = ref.get() ?: return@addOnGlobalLayoutListener
            val glass = findGlassPill(a) ?: return@addOnGlobalLayoutListener
            val now = android.os.SystemClock.uptimeMillis()
            if (now - lastLayoutSync >= 200L) {
                lastLayoutSync = now
                runSafe("syncTabsLayout") { syncTabsIfChanged(glass, g) }
            }
        }
    }

    /** 开发者「底栏变动追踪」：谁在改底栏文字，打印调用栈里宿主的前几帧，方便反编译定位 */
    fun traceTabChange(rb: View, what: String) {
        try {
            val frames = Throwable().stackTrace
                .filter { it.className.startsWith("com.vgjump") || it.className.length <= 3 }
                .take(6).joinToString(" <- ") { it.className.substringAfterLast('.') + "." + it.methodName }
            ConfigManager.log("[TabTrace] id=0x" + Integer.toHexString(rb.id) + " " + what + " :: " + frames)
        } catch (_: Throwable) {}
    }

    // ════════════════════════════════════════════════════
    //  外形、投影与留白（玻璃参数见 GlassStyle）
    // ════════════════════════════════════════════════════

    private fun ensureShadow(activity: Activity, frame: FrameLayout, bar: JumpGlassTabBar, s: GlassStyle.Params) {
        var shadow = findShadow(frame)
        if (s.shadowStrength <= 0) {
            if (shadow != null) frame.removeView(shadow)
            return
        }
        val created = shadow == null
        if (shadow == null) shadow = GlassShadowView(activity)
        val pad = shadow.configure(
            strength = s.shadowStrength / 100f,
            blurPx = dp(activity, s.shadowBlur).toFloat(),
            offsetYPx = dp(activity, s.shadowOffset).toFloat(),
            cornerPx = if (s.cornerDp <= 0) 9999f else dp(activity, s.cornerDp).toFloat()
        )
        // 比底栏每边大 pad：阴影画在多出来的一圈里（负边距在 FrameLayout 里是允许的）
        val lp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            dp(activity, s.pillHeight) + pad * 2
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            leftMargin = dp(activity, s.pillMarginH) - pad
            rightMargin = dp(activity, s.pillMarginH) - pad
            bottomMargin = dp(activity, s.pillMarginBottom) - pad
        }
        if (created) {
            frame.addView(shadow, frame.indexOfChild(bar).coerceAtLeast(0), lp)
        } else {
            shadow.layoutParams = lp
        }
    }

    private fun pillParams(ctx: Context, s: GlassStyle.Params) = FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT,
        dp(ctx, s.pillHeight)
    ).apply {
        gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        leftMargin = dp(ctx, s.pillMarginH)
        rightMargin = dp(ctx, s.pillMarginH)
        bottomMargin = dp(ctx, s.pillMarginBottom)
    }

    /** 列表底部留白 = 胶囊高度 + 底边距 + 一点余量，跟着外形设置走 */
    private fun contentPadPx(ctx: Context, s: GlassStyle.Params): Int =
        dp(ctx, s.pillHeight + s.pillMarginBottom + 12)

    // ════════════════════════════════════════════════════
    //  原底栏触摸完全禁用
    // ════════════════════════════════════════════════════

    /**
     * 真正阻断原底栏的用户触摸。
     * alpha=0 只是视觉，用户仍可盲触 RadioButton / 长按打开设置。
     * 遍历所有子 RadioButton 禁用 click + longClick + focusable。
     * 保留 RadioGroup.isEnabled=true → rgMainTab.check(id) 程序调用仍可触发切页。
     */
    private fun disableBarTouch(rgMainTab: RadioGroup) {
        rgMainTab.alpha = 0f
        rgMainTab.isClickable = false
        rgMainTab.isFocusable = false
        for (i in 0 until rgMainTab.childCount) {
            val child = rgMainTab.getChildAt(i)
            child.isClickable = false
            child.isFocusable = false
            child.isLongClickable = false
            // 清除触摸代理区域
            child.setOnTouchListener { _, _ -> true } // 吃掉所有触摸事件
        }
    }

    /** 卸载时恢复原底栏触摸 */
    private fun restoreBarTouch(rgMainTab: RadioGroup) {
        rgMainTab.alpha = 1f
        rgMainTab.isClickable = true
        rgMainTab.isFocusable = true
        for (i in 0 until rgMainTab.childCount) {
            val child = rgMainTab.getChildAt(i)
            child.isClickable = true
            child.isFocusable = true
            child.isLongClickable = true
            child.setOnTouchListener(null)
        }
    }

    // ════════════════════════════════════════════════════
    //  卸载 & 恢复
    // ════════════════════════════════════════════════════

    private fun uninstall(
        glass: JumpGlassTabBar?, rgMainTab: RadioGroup,
        clMainContent: ViewGroup, containerFra: View?, activity: Activity
    ) {
        if (glass == null) return
        (glass.parent as? ViewGroup)?.let { p ->
            findShadow(p)?.let { p.removeView(it) }
            p.removeView(glass)
        }
        restoreBarTouch(rgMainTab)

        if (containerFra != null) {
            val savedHeight = containerFra.getTag(TAG_SAVED_HEIGHT)
            if (savedHeight is Int) {
                val lp = containerFra.layoutParams
                lp.height = savedHeight
                containerFra.layoutParams = lp
            }
            restoreAllRVPadding(containerFra as? ViewGroup, 0)
        }

        for (i in 0 until clMainContent.childCount) {
            val child = clMainContent.getChildAt(i)
            if (child !== containerFra && child !== rgMainTab) {
                val h = child.layoutParams?.height ?: -1
                if (child.visibility == View.GONE && h in 1..dp(activity, 2)) {
                    child.visibility = View.VISIBLE
                }
            }
        }
        ConfigManager.log("[LiquidGlass] 已卸载，恢复原底栏")
    }

    // ════════════════════════════════════════════════════
    //  工具方法
    // ════════════════════════════════════════════════════

    private fun unwrapActivity(ctx: Context?): Activity? {
        var c = ctx
        while (c is ContextWrapper) {
            if (c is Activity) return c
            c = c.baseContext
        }
        return null
    }

    /**
     * 玻璃条和阴影都是 content 的直接子 View，只扫一层（几个子 View）。
     * 旧版用 findViewWithTag 会深度遍历整棵 View 树，而它在每次布局回调里都会被调用。
     */
    private fun findGlassPill(activity: Activity): JumpGlassTabBar? {
        val frame = activity.findViewById<ViewGroup>(android.R.id.content) ?: return null
        for (i in 0 until frame.childCount) {
            val c = frame.getChildAt(i)
            if (c is JumpGlassTabBar) return c
        }
        return null
    }

    private fun findShadow(frame: ViewGroup): GlassShadowView? {
        for (i in 0 until frame.childCount) {
            val c = frame.getChildAt(i)
            if (c is GlassShadowView) return c
        }
        return null
    }

    private fun dp(ctx: Context, v: Int): Int =
        (v * ctx.resources.displayMetrics.density + 0.5f).toInt()

    private inline fun runSafe(label: String, block: () -> Unit) {
        try { block() } catch (t: Throwable) {
            ConfigManager.logError("[LiquidGlass] $label 异常", t)
        }
    }

    // ════════════════════════════════════════════════════
    //  精准 RecyclerView 留白（只给可见 Fragment 的主列表）
    // ════════════════════════════════════════════════════

    /**
     * 只给当前 VISIBLE Fragment 的「主滚动容器」加底部留白。
     *
     * 旧版取「DFS 找到的第一个纵向 RecyclerView」，在「我的」这类页面会命中卡片里的嵌套/空列表，
     * 给它加了 80dp 底边距，卡片之间就凭空多出一块空白。现在必须同时满足：
     *   ① 是 RecyclerView / ScrollView / NestedScrollView（纵向）
     *   ② 可见、已布局，高度 ≥ 容器的 50%
     *   ③ 底边贴着容器底边（±24dp）——真正会被底栏盖住的那个
     * 满足多个时取最高的；还没布局好就稍后重试一次。
     */
    private fun padVisibleFragmentRV(containerFra: ViewGroup?, extraPad: Int, retry: Boolean) {
        if (containerFra == null) return
        var found = false
        var needRetry = false
        for (i in 0 until containerFra.childCount) {
            val fragRoot = containerFra.getChildAt(i)
            if (fragRoot.visibility != View.VISIBLE || fragRoot !is ViewGroup) continue
            if (containerFra.height <= 0 || fragRoot.height <= 0) { needRetry = true; continue }

            val target = findMainScroller(containerFra, fragRoot)
            if (target == null) { needRetry = true; continue }
            found = true

            if (target.getTag(TAG_SAVED_PADDING) == null) {
                target.setTag(TAG_SAVED_PADDING, target.paddingBottom)
            }
            if (target.paddingBottom != extraPad) {
                target.setPadding(target.paddingLeft, target.paddingTop, target.paddingRight, extraPad)
                (target as? ViewGroup)?.clipToPadding = false
                ConfigManager.log("[LiquidGlass] 留白目标: ${describeView(target)} h=${target.height} pad=$extraPad")
            }
            return
        }
        if (!found && needRetry && retry) {
            containerFra.postDelayed({ padVisibleFragmentRV(containerFra, extraPad, false) }, 450)
        }
    }

    private fun describeView(v: View): String {
        val name = try {
            if (v.id != View.NO_ID) v.resources.getResourceEntryName(v.id) else "no-id"
        } catch (_: Throwable) { "id=0x" + Integer.toHexString(v.id) }
        return v.javaClass.simpleName + "#" + name
    }

    private fun findMainScroller(container: ViewGroup, root: ViewGroup): View? {
        val cLoc = IntArray(2)
        container.getLocationInWindow(cLoc)
        val cBottom = cLoc[1] + container.height
        val tol = dp(container.context, 24)
        val vLoc = IntArray(2)
        var best: View? = null
        var bestH = 0

        fun visit(parent: ViewGroup, depth: Int) {
            if (depth > 8) return
            for (i in 0 until parent.childCount) {
                val child = parent.getChildAt(i)
                if (child.visibility != View.VISIBLE) continue
                if (isVerticalScroller(child) && child.height >= container.height / 2) {
                    child.getLocationInWindow(vLoc)
                    val vBottom = vLoc[1] + child.height
                    if (kotlin.math.abs(cBottom - vBottom) <= tol && child.height > bestH) {
                        best = child
                        bestH = child.height
                    }
                }
                if (child is ViewGroup) visit(child, depth + 1)
            }
        }
        visit(root, 0)
        return best
    }

    private fun isVerticalScroller(v: View): Boolean {
        if (v is ScrollView) return true
        var cls: Class<*>? = v.javaClass
        while (cls != null) {
            if (cls.name == "androidx.core.widget.NestedScrollView") return true
            cls = cls.superclass
        }
        return isRecyclerView(v) && isVerticalRV(v)
    }

    /** 检查 RecyclerView 是否为纵向滚动（通过 LayoutManager.canScrollVertically） */
    private fun isVerticalRV(view: View): Boolean {
        return try {
            val lm = XposedHelpers.callMethod(view, "getLayoutManager") ?: return true
            XposedHelpers.callMethod(lm, "canScrollVertically") as? Boolean ?: true
        } catch (_: Throwable) { true } // 异常时默认当做纵向
    }

    /** 卸载时恢复所有被 pad 过的 RecyclerView（限深度 3） */
    private fun restoreAllRVPadding(root: ViewGroup?, depth: Int) {
        if (root == null || depth > 8) return
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i)
            val saved = child.getTag(TAG_SAVED_PADDING) as? Int
            if (saved != null) {
                child.setPadding(child.paddingLeft, child.paddingTop, child.paddingRight, saved)
                (child as? ViewGroup)?.clipToPadding = true
                child.setTag(TAG_SAVED_PADDING, null)
            }
            if (child is ViewGroup) restoreAllRVPadding(child, depth + 1)
        }
    }

    private fun isRecyclerView(view: View): Boolean {
        var cls: Class<*>? = view.javaClass
        while (cls != null) {
            if (cls.name == "androidx.recyclerview.widget.RecyclerView") return true
            cls = cls.superclass
        }
        return false
    }
}
