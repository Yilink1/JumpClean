package com.yilink.jumpclean.ui

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
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
import com.example.liquidglass.LiquidGlassTabBar
import com.yilink.jumpclean.hooks.GlassShadowView
import com.yilink.jumpclean.hooks.GlassStyle
import com.yilink.jumpclean.hooks.GlassTabBar
import com.yilink.jumpclean.hooks.LiquidGlassHooks
import com.yilink.jumpclean.hooks.TabIcons

/** 日夜两套配色、真实玻璃预览，以及少量直观的外观选项。 */
object LiquidGlassPreviewDialog {
    fun show(activity: Activity, parent: Dialog? = null, onApplied: (() -> Unit)? = null) {
        val density = activity.resources.displayMetrics.density
        fun dp(value: Int) = (value * density + 0.5f).toInt()
        val dark = GlassStyle.isNight(activity)
        val bg = if (dark) 0xFF191B21.toInt() else 0xFFFAFAFC.toInt()
        val cardBg = if (dark) 0xFF252830.toInt() else Color.WHITE
        val ink = if (dark) 0xFFF0F2F7.toInt() else 0xFF202633.toInt()
        val secondary = if (dark) 0xFFA3A9B7.toInt() else 0xFF737B8A.toInt()
        val accent = if (dark) 0xFF9CCAFF.toInt() else 0xFF2165AB.toInt()
        val activeBg = if (dark) 0xFF354359.toInt() else 0xFFE9F1FC.toInt()
        val p = GlassStyle.load()
        var previewNight = dark
        var saved = false
        var syncing = false
        var expanded = false
        // 模块独立设置页没有真实底栏，使用面板内的实时玻璃预览。
        val liveBar: GlassTabBar? = null
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        fun rounded(color: Int, radius: Int = 14) = GradientDrawable().apply {
            setColor(color); cornerRadius = dp(radius).toFloat()
        }
        fun label(value: String, size: Float = 14f, color: Int = ink, bold: Boolean = false) = TextView(activity).apply {
            text = value; textSize = size; setTextColor(color)
            if (bold) setTypeface(null, Typeface.BOLD)
        }
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(20), dp(18), dp(14))
            background = rounded(bg, 24)
        }
        root.addView(label("液态玻璃", 21f, bold = true))
        root.addView(label("跟随 Jump 明暗主题，日间与夜间分别配色", 12f, secondary).apply {
            setPadding(0, dp(5), 0, dp(14))
        })

        val backdrop = PreviewScene(activity)
        val bar = GlassTabBar(activity).apply {
            setTabs(listOf("首页", "发现", "我的").map {
                LiquidGlassTabBar.TabItem(it, TabIcons.forTitle(it))
            })
            backdropSource = backdrop
        }
        val shadow = GlassShadowView(activity)
        val preview = FrameLayout(activity).apply {
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(18).toFloat())
                }
            }
            addView(backdrop, FrameLayout.LayoutParams(-1, -1))
            addView(shadow, FrameLayout.LayoutParams(1, 1))
            addView(bar, FrameLayout.LayoutParams(-1, dp(p.pillHeight)))
        }
        root.addView(preview, LinearLayout.LayoutParams(-1, dp(166)))
        backdrop.onMoved = { bar.invalidate() }
        root.addView(label("滑动背景看通透感，拖动底栏试试手感", 11f, secondary).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(7), 0, dp(3))
        })

        val content = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val refreshers = mutableListOf<() -> Unit>()
        var lastGeometry: Triple<Int, Int, Int>? = null
        var shadowNight: Boolean? = null
        fun palette() = if (previewNight) p.night else p.day
        fun updatePreview() {
            backdrop.night = previewNight
            backdrop.invalidate()
            GlassStyle.apply(bar, p, previewNight)
            val margin = dp(p.pillMarginH)
            val bottom = dp(p.pillMarginBottom)
            val geometry = Triple(p.pillHeight, p.pillMarginH, p.pillMarginBottom)
            if (lastGeometry != geometry) {
                bar.layoutParams = FrameLayout.LayoutParams(-1, dp(p.pillHeight)).apply {
                    gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                    leftMargin = margin; rightMargin = margin; bottomMargin = bottom
                }
            }
            if (lastGeometry != geometry || shadowNight != previewNight) {
                val pad = shadow.configure(GlassStyle.shadowStrength(previewNight), dp(p.shadowBlur).toFloat(),
                    dp(p.shadowOffset).toFloat(), 9999f)
                shadow.layoutParams = FrameLayout.LayoutParams(-1, dp(p.pillHeight) + pad * 2).apply {
                    gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                    leftMargin = margin - pad; rightMargin = margin - pad; bottomMargin = bottom - pad
                }
                shadowNight = previewNight
            }
            lastGeometry = geometry
            bar.invalidate()
            // 预览切换日夜不改变宿主主题，真实底栏仍使用宿主当前的配色。
            if (liveBar != null) LiquidGlassHooks.applyStyleLive(activity, p)
        }
        fun sync() {
            syncing = true
            try { refreshers.forEach { it() } } finally { syncing = false }
            updatePreview()
        }
        fun section(value: String): TextView = label(value, 12f, secondary, true).apply {
            setPadding(dp(2), dp(14), 0, dp(7)); content.addView(this)
        }
        fun card(): LinearLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = rounded(cardBg)
            content.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(7) })
        }
        fun choice(title: String, options: () -> List<String>, get: () -> Int, set: (Int) -> Unit) {
            val box = card()
            if (title.isNotEmpty()) box.addView(label(title, bold = true))
            val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
            if (title.isNotEmpty()) row.setPadding(0, dp(9), 0, 0)
            val chips = options().mapIndexed { index, _ ->
                label("", 13f).apply {
                    gravity = Gravity.CENTER
                    minHeight = dp(40)
                    setPadding(dp(4), dp(8), dp(4), dp(8))
                    setOnClickListener { set(index); sync() }
                    row.addView(this, LinearLayout.LayoutParams(0, -2, 1f).apply {
                        if (index > 0) leftMargin = dp(6)
                    })
                }
            }
            box.addView(row)
            refreshers += {
                val labels = options()
                chips.forEachIndexed { index, view ->
                    val selected = get() == index
                    view.text = labels[index]
                    view.isSelected = selected
                    view.setTextColor(if (selected) accent else secondary)
                    view.setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
                    view.background = rounded(if (selected) activeBg else Color.TRANSPARENT, 10)
                }
            }
        }
        fun slider(title: String, min: Int, max: Int, unit: String, get: () -> Int, set: (Int) -> Unit) {
            val box = card()
            val row = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
            row.addView(label(title, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
            val number = label("", 13f, accent)
            row.addView(number); box.addView(row)
            val seek = SeekBar(activity).apply {
                this.max = max - min
                progressTintList = ColorStateList.valueOf(accent)
                thumbTintList = ColorStateList.valueOf(accent)
                minimumHeight = dp(40)
            }
            seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(view: SeekBar?, value: Int, fromUser: Boolean) {
                    if (!fromUser || syncing) return
                    set(value + min); number.text = "${value + min}$unit"; updatePreview()
                }
                override fun onStartTrackingTouch(view: SeekBar?) {}
                override fun onStopTrackingTouch(view: SeekBar?) {}
            })
            box.addView(seek)
            refreshers += { seek.progress = get().coerceIn(min, max) - min; number.text = "${get()}$unit" }
        }
        fun toggle(title: String, description: String, get: () -> Boolean, set: (Boolean) -> Unit) {
            val box = card().apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val text = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                addView(label(title, bold = true))
                addView(label(description, 11f, secondary).apply { setPadding(0, dp(4), dp(8), 0) })
            }
            box.addView(text, LinearLayout.LayoutParams(0, -2, 1f))
            val switch = Switch(activity).apply {
                contentDescription = title
                thumbTintList = ColorStateList.valueOf(accent)
                setOnCheckedChangeListener { _, checked ->
                    if (!syncing) { set(checked); updatePreview() }
                }
            }
            box.addView(switch); refreshers += { switch.isChecked = get() }
        }

        // 日夜选择固定在预览下方，修改哪一套配色始终可见。
        choice("", { listOf("日间配色", "夜间配色") }, { if (previewNight) 1 else 0 }, { previewNight = it == 1 })
        val profilePicker = content.getChildAt(0)
        content.removeView(profilePicker)
        root.addView(profilePicker)
        section("材质")
        choice("磨砂强度", { listOf("轻透", "柔雾") }, { p.finish }, { p.finish = it })
        choice("边缘高光", { listOf("通透", "标准", "明显") }, { p.edge }, { p.edge = it })
        val profileTitle = section("")
        refreshers += { profileTitle.text = if (previewNight) "夜间 · 独立保存" else "日间 · 独立保存" }
        choice("玻璃底色", {
            if (previewNight) listOf("黑色", "蓝灰", "深紫") else listOf("白色", "暖白", "冰蓝")
        }, { palette().tone }, { palette().tone = it })
        slider("玻璃浓度", 8, 55, "%", { palette().opacity }, { palette().opacity = it })
        choice("选中颜色", {
            if (previewNight) listOf("柔白", "冰蓝", "浅珊瑚") else listOf("墨色", "蓝色", "珊瑚")
        }, { palette().accent }, { palette().accent = it })

        val more = label("尺寸与更多  +", 13f, accent, true).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(14), 0, dp(14))
        }
        content.addView(more)
        val moreStart = content.childCount
        slider("底栏高度", 60, 76, " dp", { p.pillHeight }, { p.pillHeight = it })
        slider("左右留白", 12, 40, " dp", { p.pillMarginH }, { p.pillMarginH = it })
        slider("底部距离", 4, 28, " dp", { p.pillMarginBottom }, { p.pillMarginBottom = it })
        toggle("持续更新背景", "视频与轮播实时透过玻璃，会增加耗电", { p.dynamicBg }, { p.dynamicBg = it })
        toggle("玻璃诊断日志", "排查问题时使用，需同时开启总调试日志", { p.diagLog }, { p.diagLog = it })
        val moreViews = (moreStart until content.childCount).map { content.getChildAt(it) }
        fun syncMore() {
            more.text = if (expanded) "收起更多  −" else "尺寸与更多  +"
            moreViews.forEach { it.visibility = if (expanded) View.VISIBLE else View.GONE }
        }
        more.setOnClickListener { expanded = !expanded; syncMore() }
        syncMore()
        val optionsScroll = ScrollView(activity).apply {
            isFillViewport = false
            addView(content)
        }
        root.addView(optionsScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        if (activity.resources.displayMetrics.heightPixels / density < 600f) {
            // 小屏或横屏时让预览一起滚动，给操作区域留空间。
            root.removeView(optionsScroll)
            optionsScroll.removeView(content)
            val body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            while (root.childCount > 0) {
                val child = root.getChildAt(0)
                root.removeView(child); body.addView(child)
            }
            body.addView(content)
            root.addView(ScrollView(activity).apply { addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
        }

        val footer = LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, 0)
        }
        fun button(title: String, primary: Boolean = false, action: () -> Unit): TextView =
            label(title, 14f, if (primary) if (dark) 0xFF152438.toInt() else Color.WHITE else secondary, true).apply {
                gravity = Gravity.CENTER; minHeight = dp(44)
                background = rounded(if (primary) accent else Color.TRANSPARENT, 12)
                setOnClickListener { action() }
                footer.addView(this, LinearLayout.LayoutParams(0, -2, 1f))
            }
        button("取消") { dialog.dismiss() }
        button("重置") {
            p.copyFrom(GlassStyle.defaults()); sync()
            Toast.makeText(activity, "已恢复日间与夜间默认外观，保存后生效", Toast.LENGTH_SHORT).show()
        }
        button("保存", true) {
            GlassStyle.save(p); saved = true
            if (onApplied != null) onApplied() else LiquidGlassHooks.refreshFromSettings()
            dialog.dismiss()
        }
        root.addView(footer)
        dialog.setOnDismissListener {
            // 取消、返回键和点外部关闭均不保存；实时预览恢复到已保存外观。
            bar.backdropSource = null
            if (!saved && liveBar != null) LiquidGlassHooks.refreshFromSettings()
            parent?.show()
        }
        if (parent != null) parent.hide()
        sync()
        dialog.setContentView(root)
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            val metrics = activity.resources.displayMetrics
            setLayout(minOf(metrics.widthPixels - dp(24), dp(480)), (metrics.heightPixels * 0.88f).toInt())
            setGravity(Gravity.CENTER)
        }
    }

    /** 简化的信息流场景；手动滚动，不在面板中常驻运行动画。 */
    private class PreviewScene(context: Context) : View(context) {
        var night = false
        var onMoved: (() -> Unit)? = null
        private var offset = 0f
        private var lastY = 0f
        private val d = resources.displayMetrics.density
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val bounds = RectF()
        private val tile = LinearGradient(0f, 0f, 160f * d, 160f * d,
            intArrayOf(0xFF84B6B0.toInt(), 0xFF8CA7CF.toInt(), 0xFFD4A7A9.toInt()), null, Shader.TileMode.CLAMP)

        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(if (night) 0xFF14171D.toInt() else 0xFFF0F3F8.toInt())
            canvas.save()
            canvas.clipRect(0f, 35f * d, width.toFloat(), height.toFloat())
            for (i in -1..3) {
                val y = (42f + i * 72f + offset) * d
                bounds.set(14f * d, y, width - 14f * d, y + 60f * d)
                paint.color = if (night) 0xFF252B35.toInt() else Color.WHITE
                canvas.drawRoundRect(bounds, 12f * d, 12f * d, paint)
                paint.shader = tile
                bounds.set(21f * d, y + 7f * d, 67f * d, y + 53f * d)
                canvas.drawRoundRect(bounds, 8f * d, 8f * d, paint)
                paint.shader = null
                paint.color = if (night) 0xFFD2D8E3.toInt() else 0xFF465369.toInt()
                paint.textSize = 12f * d
                canvas.drawText(if (i % 2 == 0) "下一站，新的冒险" else "收藏生活里的小发现", 79f * d, y + 25f * d, paint)
                paint.color = if (night) 0xFF626E81.toInt() else 0xFFC2CBD8.toInt()
                bounds.set(79f * d, y + 35f * d, width - 35f * d, y + 39f * d)
                canvas.drawRoundRect(bounds, 2f * d, 2f * d, paint)
            }
            canvas.restore()
            paint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            paint.textSize = 12f * d
            paint.color = if (night) 0xFFAEB7C8.toInt() else 0xFF6E7D92.toInt()
            canvas.drawText(if (night) "夜间 / 安静一点" else "日间 / 轻盈一点", 16f * d, 25f * d, paint)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { lastY = event.y; parent?.requestDisallowInterceptTouchEvent(true) }
                MotionEvent.ACTION_MOVE -> {
                    offset = (offset + (event.y - lastY) / d) % 72f
                    lastY = event.y; invalidate(); onMoved?.invoke()
                }
                MotionEvent.ACTION_UP -> { parent?.requestDisallowInterceptTouchEvent(false); performClick() }
                MotionEvent.ACTION_CANCEL -> parent?.requestDisallowInterceptTouchEvent(false)
            }
            return true
        }
        override fun performClick(): Boolean { super.performClick(); return true }
    }
}
