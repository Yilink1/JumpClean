package com.yilink.jumpclean.ui

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.animation.ValueAnimator
import android.animation.TimeInterpolator
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.Window
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.yilink.jumpclean.hooks.GlassShadowView
import com.yilink.jumpclean.hooks.GlassStyle
import com.yilink.jumpclean.hooks.JumpGlassTabBar
import com.yilink.jumpclean.hooks.LiquidGlassHooks
import kotlin.reflect.KMutableProperty0

/**
 * 液态玻璃参数微调。
 * 顶部是一条「预览底栏」，和主界面共用同一套 GlassStyle.applyTo，
 * 滑动任何参数都能立刻在预览上看到效果；在预览条上按住/滑动可以试水滴手感。
 */
object LiquidGlassSettingsDialog {

    fun show(activity: Activity, live: Boolean = false, parent: Dialog? = null, onApplied: (() -> Unit)? = null) {
        val dm = activity.resources.displayMetrics
        val density = dm.density
        val dp = { value: Int -> (value * density + 0.5f).toInt() }
        val isDark = (activity.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES

        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val dialogBg = if (isDark) Color.parseColor("#202022") else Color.parseColor("#FFFFFF")
        val primaryText = if (isDark) Color.parseColor("#F5F5F7") else Color.parseColor("#1D1D1F")
        val secondaryText = if (isDark) Color.parseColor("#8E8E93") else Color.parseColor("#86868B")
        val cardBg = if (isDark) Color.parseColor("#2C2C2E") else Color.parseColor("#F5F5F7")
        val accentRed = Color.parseColor("#FF5252")

        val p = GlassStyle.load()
        val refreshers = mutableListOf<() -> Unit>()
        // 从主页进来（长按「我的」）时，面板只占上半屏并直接作用于真实底栏；
        // 否则（官方设置页里）退回到内置预览条
        val liveBar = if (live) LiquidGlassHooks.pillOf(activity) else null
        val snapshot = GlassStyle.Params().also { it.copyFrom(p) }

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(14))
            background = GradientDrawable().apply {
                setColor(dialogBg)
                if (liveBar != null) {
                    val r = dp(22).toFloat()
                    cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, r, r, r, r)
                } else {
                    cornerRadius = dp(20).toFloat()
                }
            }
        }

        root.addView(TextView(activity).apply {
            text = "液态玻璃参数微调"
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setTextColor(primaryText)
        })
        // 实时模式没有内置预览，这行只在内置预览模式出现
        if (liveBar == null) root.addView(TextView(activity).apply {
            text = "上面是实时预览：点背景换底色，在底栏上按住或滑动试水滴"
            textSize = 12f
            setTextColor(secondaryText)
            setPadding(0, dp(2), 0, dp(10))
        })

        // ───────── 预览 ─────────
        val backdrop = PreviewBackdrop(activity)
        val bar = JumpGlassTabBar(activity)
        bar.setTabs(listOf("首页", "发现", "我的"))
        bar.sampleSource = backdrop

        // 预览宽度比真机窄，但边距/高度按真实 dp 显示（底边距现在能看出来了）
        fun barParams() = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(p.pillHeight)
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            val m = dp(p.pillMarginH.coerceAtMost(70))
            leftMargin = m
            rightMargin = m
            bottomMargin = dp(p.pillMarginBottom.coerceAtMost(48))
        }

        val shadowView = GlassShadowView(activity)

        val previewFrame = FrameLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(150)
            ).apply { bottomMargin = dp(4) }
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(14).toFloat())
                }
            }
            addView(backdrop, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            ))
            addView(shadowView, FrameLayout.LayoutParams(1, 1))
            addView(bar, barParams())
        }
        backdrop.setOnClickListener {
            backdrop.mode = (backdrop.mode + 1) % 3
            backdrop.invalidate()
            bar.invalidate()
        }
        // 背景持续滚动：折射、彩虹色散、高光都要有「细节在动」才看得出来
        val scrollAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 7000
            repeatCount = ValueAnimator.INFINITE
            interpolator = TimeInterpolator { it }
            addUpdateListener {
                backdrop.phase = it.animatedValue as Float
                backdrop.invalidate()
                bar.invalidate()
            }
        }
        scrollAnim.start()
        if (liveBar != null) { scrollAnim.cancel(); parent?.hide() }
        if (liveBar == null) root.addView(previewFrame)
        root.addView(TextView(activity).apply {
            text = if (liveBar != null) "实时作用于真实底栏：可以直接在底栏上滑动/按住试水滴；关闭面板即保留当前效果，不满意点「撤销」"
            else "预览窄于真机，边距和高度按真实 dp 显示；背景自动滚动，方便看折射与彩虹"
            textSize = 10.5f
            setTextColor(secondaryText)
            setPadding(dp(2), 0, 0, dp(6))
        })

        // 实测读数：玻璃真实的重绘帧率与 CPU 录制耗时，实时模式下始终显示在面板顶部（不依赖任何日志开关）
        val perfView = TextView(activity).apply {
            textSize = 11.5f
            setTextColor(secondaryText)
            setPadding(dp(4), 0, 0, dp(8))
            text = "玻璃实测：采样中…"
        }
        val perfTick = object : Runnable {
            override fun run() {
                if (!perfView.isAttachedToWindow) return
                val bar2 = liveBar ?: return
                val st = bar2.lastFrameStats
                perfView.text = if (st == null) "玻璃实测：暂无帧统计（底栏还没重绘过）"
                else "玻璃实测：${st.drawFps} fps · CPU 录制 ${"%.2f".format(st.totalMs)} ms · ${st.effectName}" +
                    if (p.dynamicBg) "（背景持续刷新：开）" else "（背景持续刷新：关）"
                perfView.postDelayed(this, 1000)
            }
        }
        if (liveBar != null) {
            liveBar.statsForced = true
            root.addView(perfView)
            perfView.post(perfTick)
        }

        // 唯一的关闭回调：内置预览模式只停动画；实时模式所见即所得，关掉就保留当前效果
        dialog.setOnDismissListener {
            scrollAnim.cancel()
            if (liveBar != null) {
                perfView.removeCallbacks(perfTick)
                liveBar.statsForced = false
                GlassStyle.save(p)
                try { LiquidGlassHooks.refreshFromSettings() } catch (_: Throwable) {}
                parent?.show()
            }
        }

        fun refreshPreview() {
            if (liveBar != null) {
                LiquidGlassHooks.applyStyleLive(activity, p)
                return
            }
            GlassStyle.applyTo(bar, p, density)
            bar.layoutParams = barParams()
            if (p.shadowStrength <= 0) {
                shadowView.visibility = View.GONE
            } else {
                shadowView.visibility = View.VISIBLE
                val pad = shadowView.configure(
                    p.shadowStrength / 100f, dp(p.shadowBlur).toFloat(), dp(p.shadowOffset).toFloat(),
                    if (p.cornerDp <= 0) 9999f else dp(p.cornerDp).toFloat()
                )
                val m = dp(p.pillMarginH.coerceAtMost(70))
                shadowView.layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(p.pillHeight) + pad * 2
                ).apply {
                    gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                    leftMargin = m - pad
                    rightMargin = m - pad
                    bottomMargin = dp(p.pillMarginBottom.coerceAtMost(48)) - pad
                }
            }
            bar.invalidate()
        }
        refreshPreview()

        // ───────── 行构造器 ─────────
        val scrollContent = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }

        fun section(title: String) {
            scrollContent.addView(TextView(activity).apply {
                text = title
                textSize = 12f
                setTypeface(null, Typeface.BOLD)
                setTextColor(secondaryText)
                setPadding(dp(4), dp(8), 0, dp(6))
            })
        }

        fun card(): LinearLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                setColor(cardBg)
                cornerRadius = dp(12).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }

        fun titleText(text: String) = TextView(activity).apply {
            this.text = text
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(primaryText)
        }

        fun slider(title: String, unit: String, min: Int, max: Int, prop: KMutableProperty0<Int>) {
            val container = card()
            val header = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val titleTxt = titleText(title).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val valTxt = TextView(activity).apply {
                textSize = 13f
                setTextColor(accentRed)
                setTypeface(null, Typeface.BOLD)
            }
            header.addView(titleTxt)
            header.addView(valTxt)
            container.addView(header)

            val seek = SeekBar(activity)
            seek.max = max - min
            fun sync() {
                val v = prop.get().coerceIn(min, max)
                seek.progress = v - min
                valTxt.text = "$v$unit"
            }
            sync()
            seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    val v = progress + min
                    valTxt.text = "$v$unit"
                    if (fromUser) {
                        prop.set(v)
                        refreshPreview()
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
            container.addView(seek)
            scrollContent.addView(container)
            refreshers += { sync() }
        }

        fun choice(title: String, options: List<String>, prop: KMutableProperty0<Int>) {
            val container = card()
            container.addView(titleText(title))
            val rowView = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(8), 0, 0)
            }
            val chips = options.mapIndexed { index, label ->
                TextView(activity).apply {
                    text = label
                    textSize = 12.5f
                    gravity = Gravity.CENTER
                    setPadding(dp(4), dp(7), dp(4), dp(7))
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                        .apply { if (index > 0) leftMargin = dp(6) }
                    setOnClickListener {
                        prop.set(index)
                        refreshers.forEach { it() }
                        refreshPreview()
                    }
                }
            }
            chips.forEach { rowView.addView(it) }
            container.addView(rowView)
            scrollContent.addView(container)
            fun sync() {
                chips.forEachIndexed { index, tv ->
                    val on = index == prop.get()
                    tv.setTextColor(if (on) Color.WHITE else primaryText)
                    tv.setTypeface(null, if (on) Typeface.BOLD else Typeface.NORMAL)
                    tv.background = GradientDrawable().apply {
                        setColor(if (on) accentRed else if (isDark) Color.parseColor("#3A3A3C") else Color.parseColor("#E5E5EA"))
                        cornerRadius = dp(8).toFloat()
                    }
                }
            }
            sync()
            refreshers += { sync() }
        }

        fun toggle(title: String, desc: String, prop: KMutableProperty0<Boolean>, onChange: (() -> Unit)? = null) {
            val container = card().apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val label = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                addView(titleText(title))
                addView(TextView(activity).apply {
                    text = desc
                    textSize = 11f
                    setTextColor(secondaryText)
                })
            }
            val sw = Switch(activity)
            sw.isChecked = prop.get()
            sw.setOnCheckedChangeListener { _, checked ->
                prop.set(checked)
                refreshPreview()
                onChange?.invoke()
            }
            container.addView(label)
            container.addView(sw)
            scrollContent.addView(container)
            refreshers += { sw.isChecked = prop.get() }
        }

        // ───────── 参数 ─────────
        section("材质与通透")
        choice("材质", listOf("常规", "高透（会压暗）"), p::material)
        toggle("自适应明暗染色", "开启后亮背景会被压灰，想通透就关掉", p::adaptiveTint)
        choice("染色色调", listOf("白", "黑", "蓝", "红"), p::tintColor)
        slider("染色强度", "%", 0, 60, p::tintStrength)
        slider("背景模糊（0 = 关闭）", "%", 0, 30, p::blur)
        slider("饱和度", "%", 80, 220, p::saturation)

        section("折射与高光")
        slider("折射高度", "dp", 0, 30, p::refraction)
        slider("边缘斜面宽度", "dp", 2, 30, p::bevel)
        slider("折射集中度（×0.1）", "", 0, 40, p::falloff)
        slider("彩虹色散", "%", 0, 30, p::dispersion)
        slider("边缘高光", "%", 0, 100, p::edgeHighlight)
        slider("厚度光边（立体感）", "%", 0, 100, p::rimLight)
        toggle("重力感应高光", "倾斜手机时光泽跟着流动", p::sensor)

        section("水滴（选中指示）")
        slider("水滴折射高度", "dp", 0, 16, p::dropRefraction)
        slider("水滴斜面宽度", "dp", 2, 20, p::dropBevel)
        slider("水滴色散", "%", 0, 20, p::dropDispersion)
        slider("按住放大", "%", 100, 115, p::pressInflate)
        slider("按住折射增强", "%", 100, 400, p::pressBoost)
        slider("按住提亮", "%", 0, 40, p::pressLift)
        slider("拖动果冻感", "%", 0, 100, p::dragJelly)
        slider("撞墙挤压", "%", 0, 100, p::wallSquash)

        section("投影")
        slider("投影强度（0 = 关）", "%", 0, 60, p::shadowStrength)
        slider("投影模糊", "dp", 4, 40, p::shadowBlur)
        slider("投影下移", "dp", 0, 16, p::shadowOffset)

        section("文字与外形")
        choice("文字配色", listOf("自动", "深色字", "浅色字"), p::textMode)
        choice("选中文字色", listOf("自动", "蓝", "红", "橙"), p::selectedColor)
        slider("文字大小", "sp", 10, 18, p::textSize)
        slider("胶囊高度", "dp", 40, 72, p::pillHeight)
        slider("左右边距", "dp", 0, 60, p::pillMarginH)
        slider("底边距", "dp", 0, 48, p::pillMarginBottom)

        // ───────── 开发者模式 ─────────
        val devViews = mutableListOf<View>()
        fun syncDev() { devViews.forEach { it.visibility = if (p.devMode) View.VISIBLE else View.GONE } }
        section("开发者")
        toggle("开发者模式", "显示更多库的细项参数", p::devMode) { syncDev() }
        val devStart = scrollContent.childCount
        section("库的细项（开发者）")
        slider("胶囊圆角（0 = 全圆）", "dp", 0, 40, p::cornerDp)
        toggle("折射不翻折", "边缘只拉伸延展，不出现压缩镜像环", p::noFold)
        toggle("外向折射（凸透镜）", "边缘把形状外的背景弯进来，更像厚玻璃", p::outward)
        slider("边缘柔化", "dp", 0, 12, p::edgeSoft)
        toggle("尺寸自适应缩放", "小控件按短边自动收斜面/折射，关掉则原样渲染", p::adaptiveScale)
        toggle("边缘高光（白边）", "贴边那圈白色细亮线；强度见上面的「边缘高光」", p::edgeOn)
        toggle("强制完整效果", "系统开了「高对比度文字」时也不退化成不透明", p::a11yFull)
        toggle("背景持续刷新", "最耗电的一项（每帧重绘）。关掉后只在滚动/触摸时重绘；页面里有动画、轮播、视频时玻璃后面不会实时更新", p::dynamicBg)
        toggle("玻璃诊断日志", "每 3 秒一行帧率 + 底栏文字/红点变动追踪 + 护眼命中次数。需同时开启设置里的「开启调试日志」；平时请关着，否则会刷屏并持续写日志文件", p::diagLog)
        for (i in devStart until scrollContent.childCount) devViews += scrollContent.getChildAt(i)
        syncDev()

        val scrollView = ScrollView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
            addView(scrollContent)
        }
        root.addView(scrollView)

        // ───────── 按钮栏 ─────────
        val btnRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, 0)
        }
        val resetBtn = TextView(activity).apply {
            text = "恢复默认"
            textSize = 14f
            setTextColor(secondaryText)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            setOnClickListener {
                p.copyFrom(GlassStyle.defaults())
                refreshers.forEach { it() }
                refreshPreview()
                Toast.makeText(activity, "已恢复默认（通透调校）", Toast.LENGTH_SHORT).show()
            }
        }
        val undoBtn = TextView(activity).apply {
            text = "撤销本次"
            textSize = 14f
            setTextColor(secondaryText)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            setOnClickListener {
                p.copyFrom(snapshot)
                refreshers.forEach { it() }
                refreshPreview()
                Toast.makeText(activity, "已撤销到打开面板时的状态", Toast.LENGTH_SHORT).show()
            }
        }
        val saveBtn = TextView(activity).apply {
            text = if (liveBar != null) "完成" else "保存并生效"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(accentRed)
                cornerRadius = dp(10).toFloat()
            }
            setPadding(dp(18), dp(8), dp(18), dp(8))
            setOnClickListener {
                GlassStyle.save(p)
                if (liveBar == null) {
                    Toast.makeText(activity, "参数已保存，返回首页即生效", Toast.LENGTH_SHORT).show()
                }
                onApplied?.invoke()
                dialog.dismiss()
            }
        }
        btnRow.addView(resetBtn)
        if (liveBar != null) btnRow.addView(undoBtn)
        btnRow.addView(saveBtn)
        root.addView(btnRow)

        dialog.setContentView(root)
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            if (liveBar != null) {
                // 全宽、铺到状态栏下面贴住屏幕最顶，高度约占屏幕 69%，下面留出约 1/3 让页面能照常滑动；
                // 面板外的触摸直接落到下面的页面
                setGravity(Gravity.TOP or Gravity.CENTER_HORIZONTAL)
                addFlags(
                    android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                )
                setLayout(ViewGroup.LayoutParams.MATCH_PARENT, (dm.heightPixels * 0.69f).toInt())
                clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                setFlags(
                    android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                )
                attributes = attributes.apply { y = 0 }
                // 窗口真正的屏幕位置要显示出来才量得到：按底栏顶边反推高度，并给状态栏让出顶部内边距
                root.post {
                    try {
                        val barLoc = IntArray(2)
                        liveBar.getLocationOnScreen(barLoc)
                        val rootLoc = IntArray(2)
                        root.getLocationOnScreen(rootLoc)
                        // 目标下沿 = 屏幕 69%，但绝不盖住真实底栏
                        val bottom = minOf((dm.heightPixels * 0.69f).toInt(), barLoc[1] - dp(6))
                        val h = (bottom - rootLoc[1]).coerceAtLeast(dp(260))
                        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, h)
                        val sbId = activity.resources.getIdentifier("status_bar_height", "dimen", "android")
                        val sb = if (sbId > 0) activity.resources.getDimensionPixelSize(sbId) else 0
                        val need = (sb - rootLoc[1]).coerceAtLeast(0)
                        root.setPadding(root.paddingLeft, dp(14) + need, root.paddingRight, root.paddingBottom)
                    } catch (_: Throwable) {}
                }
            } else {
                setLayout(dm.widthPixels - dp(16), (dm.heightPixels * 0.9f).toInt())
            }
        }
    }

    /** 预览用背景：文字 + 色块 + 条纹，提供足够的高频细节让折射看得出来 */
    private class PreviewBackdrop(context: Context) : View(context) {
        var mode = 0 // 0 浅色 / 1 深色 / 2 彩色
        var phase = 0f // 0..1 循环，驱动背景滚动
        private val d = context.resources.displayMetrics.density
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var gradient: Shader? = null

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            gradient = LinearGradient(
                0f, 0f, w.toFloat(), h.toFloat(),
                intArrayOf(0xFFFF9A9E.toInt(), 0xFFA18CFF.toInt(), 0xFF5EC8FF.toInt()),
                null, Shader.TileMode.CLAMP
            )
        }

        override fun onDraw(c: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            when (mode) {
                0 -> c.drawColor(0xFFF4F4F6.toInt())
                1 -> c.drawColor(0xFF1C1C1E.toInt())
                else -> {
                    paint.shader = gradient
                    c.drawRect(0f, 0f, w, h, paint)
                    paint.shader = null
                }
            }
            val ink = when (mode) { 0 -> 0xFF222222.toInt(); 1 -> 0xFFE5E5EA.toInt(); else -> Color.WHITE }
            val block = when (mode) { 0 -> 0xFFDADAE0.toInt(); 1 -> 0xFF3A3A3C.toInt(); else -> 0x55FFFFFF }

            val period = 22 * d
            val shift = phase * period
            // 竖条纹（横向滚动一个周期后无缝衔接）
            paint.color = block
            var x = 6 * d - shift
            while (x < w) {
                c.drawRoundRect(RectF(x, 40 * d, x + 10 * d, h), 3 * d, 3 * d, paint)
                x += period
            }
            // 色块：整屏宽度循环滚动
            val span = w + 60 * d
            fun cx(base: Float) = ((w * base - phase * span) % span + span) % span - 30 * d
            paint.color = 0xFFFF5252.toInt(); c.drawCircle(cx(0.18f), h * 0.62f, 16 * d, paint)
            paint.color = 0xFF34C759.toInt(); c.drawCircle(cx(0.50f), h * 0.55f, 20 * d, paint)
            paint.color = 0xFFFFB020.toInt(); c.drawCircle(cx(0.82f), h * 0.64f, 14 * d, paint)
            // 文字
            paint.color = ink
            paint.textSize = 15 * d
            paint.typeface = Typeface.DEFAULT_BOLD
            c.drawText("Liquid Glass 液态玻璃 Aa 123", 12 * d - shift, 24 * d, paint)
            c.drawText("Liquid Glass 液态玻璃 Aa 123", 12 * d - shift + 230 * d, 24 * d, paint)
        }
    }
}
