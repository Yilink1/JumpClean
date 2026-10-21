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
import android.view.KeyEvent
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
import com.yilink.jumpclean.hooks.GlassStyle
import com.yilink.jumpclean.hooks.GlassTabBar
import com.yilink.jumpclean.hooks.LiquidGlassHooks
import com.yilink.jumpclean.hooks.TabIcons

/** 日夜两套配色、真实玻璃预览，以及少量直观的外观选项。 */
object LiquidGlassSettingsDialog {
    fun show(activity: Activity, live: Boolean = false, parent: Dialog? = null, onApplied: (() -> Unit)? = null) {
        val liveBar = if (live) LiquidGlassHooks.pillOf(activity) else null
        if (liveBar == null) {
            // 模块独立设置页：没有真实底栏，走带实时玻璃预览的旧弹窗。
            LiquidGlassPreviewDialog.show(activity, parent, onApplied)
        } else {
            // 宿主快捷面板：顶部卡片，下面直接露出真实底栏。
            showQuickSheet(activity, liveBar, parent, onApplied)
        }
    }

    private fun showQuickSheet(
        activity: Activity,
        liveBar: GlassTabBar,
        parent: Dialog?,
        onApplied: (() -> Unit)? = null
    ) {
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
        val contentFrame = activity.findViewById<FrameLayout>(android.R.id.content) ?: return
        var backAction: (() -> Unit)? = null
        val overlay = object : FrameLayout(activity) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                    val action = backAction
                    if (action != null) {
                        action()
                        return true
                    }
                }
                return super.dispatchKeyEvent(event)
            }
        }.apply {
            isClickable = true
            isFocusable = true
            isFocusableInTouchMode = true
            clipChildren = false
        }
        var closed = false
        val metrics = activity.resources.displayMetrics
        val statusBarRes = activity.resources.getIdentifier("status_bar_height", "dimen", "android")
        val statusBar = if (statusBarRes > 0) activity.resources.getDimensionPixelSize(statusBarRes) else dp(24)
        val sheetTop = statusBar + dp(8)
        val barArea = if (liveBar != null) dp(p.pillHeight + p.pillMarginBottom + 10) else 0
        val sheetHeight = minOf(
            (metrics.heightPixels * 0.63f).toInt(),
            metrics.heightPixels - barArea - sheetTop - dp(12)
        )
            .coerceAtLeast(dp(320))

        fun rounded(color: Int, radius: Int = 14) = GradientDrawable().apply {
            setColor(color); cornerRadius = dp(radius).toFloat()
        }
        fun sheetBackground(color: Int) = GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(24).toFloat()
        }
        fun label(value: String, size: Float = 14f, color: Int = ink, bold: Boolean = false) = TextView(activity).apply {
            text = value; textSize = size; setTextColor(color)
            if (bold) setTypeface(null, Typeface.BOLD)
        }
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(12))
            background = sheetBackground(bg)
            elevation = dp(16).toFloat()
        }
        root.addView(
            View(activity).apply {
                background = rounded(if (dark) 0xFF3A3F4A.toInt() else 0xFFD5D9E0.toInt(), 2)
            },
            LinearLayout.LayoutParams(dp(36), dp(4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(8)
            }
        )
        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(label("液态玻璃", 20f, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
        val resetButton = label("重置", 14f, secondary, true).apply {
            gravity = Gravity.CENTER
            minWidth = dp(48)
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        header.addView(resetButton)
        val done = label("完成", 14f, accent, true).apply {
            gravity = Gravity.CENTER
            minWidth = dp(48)
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        header.addView(done)
        root.addView(header)
        root.addView(label("编辑哪一套配色，真实底栏就实时应用哪一套；上方内容可直接滑动", 11f, secondary).apply {
            setPadding(0, dp(2), 0, dp(8))
        })

        val content = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val refreshers = mutableListOf<() -> Unit>()
        fun palette() = if (previewNight) p.night else p.day
        fun updatePreview() {
            // 直接作用到真实底栏；previewNight 只决定编辑/预览哪一套配色。
            if (liveBar != null) LiquidGlassHooks.applyStyleLive(activity, p, previewNight)
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

        fun close(after: (() -> Unit)? = null) {
            if (closed) return
            closed = true
            val distance = -(root.height.takeIf { it > 0 } ?: sheetHeight).toFloat()
            root.animate().translationY(distance).setDuration(180).withEndAction {
                contentFrame.removeView(overlay)
                parent?.show()
                after?.invoke()
            }.start()
        }

        fun save() {
            if (closed) return
            GlassStyle.save(p)
            saved = true
            close {
                if (onApplied != null) onApplied() else LiquidGlassHooks.refreshFromSettings()
            }
        }
        done.setOnClickListener { save() }
        backAction = {
            if (!saved && liveBar != null) LiquidGlassHooks.refreshFromSettings()
            close()
        }
        resetButton.setOnClickListener {
            p.copyFrom(GlassStyle.defaults())
            sync()
            Toast.makeText(activity, "已恢复日间与夜间默认外观", Toast.LENGTH_SHORT).show()
        }

        if (parent != null) parent.hide()
        sync()
        overlay.addView(root, FrameLayout.LayoutParams(-1, sheetHeight).apply {
            gravity = Gravity.TOP
            topMargin = sheetTop
        })
        contentFrame.addView(overlay, FrameLayout.LayoutParams(-1, sheetHeight).apply {
            gravity = Gravity.TOP
            topMargin = sheetTop
        })
        overlay.requestFocus()
        root.translationY = -sheetHeight.toFloat()
        root.post { root.animate().translationY(0f).setDuration(240).start() }
    }

    /**
     * 简化的信息流场景 + 静态底栏预览。
     *
     * 面板里刻意不再放真实的 LiquidGlassTabBar：那会在对话框内再启动一套
     * 背景捕获/模糊/布局，滚动时容易在主线程反复 requestLayout。
     * 这里只画配色、浓度、透镜和图标，足够调参，也不会和真实底栏抢资源。
     */
    private class PreviewScene(context: Context) : View(context) {
        var night = false
        private var offset = 0f
        private var lastY = 0f
        private var tint = Color.WHITE
        private var tintAlpha = 0.44f
        private var accent = Color.BLACK
        private var normal = Color.GRAY
        private var barHeightDp = 64
        private var marginHDp = 24
        private var marginBottomDp = 12
        private val d = resources.displayMetrics.density
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val bounds = RectF()
        private val barRect = RectF()
        private val lensRect = RectF()
        private val icons = listOf(
            TabIcons.forTitle("首页"),
            TabIcons.forTitle("发现"),
            TabIcons.forTitle("我的")
        )
        private val labels = listOf("首页", "发现", "我的")
        private val tile = LinearGradient(0f, 0f, 160f * d, 160f * d,
            intArrayOf(0xFF84B6B0.toInt(), 0xFF8CA7CF.toInt(), 0xFFD4A7A9.toInt()), null, Shader.TileMode.CLAMP)

        fun applyStyle(p: GlassStyle.Params, previewNight: Boolean) {
            night = previewNight
            val palette = if (night) p.night else p.day
            tint = GlassStyle.tintColor(palette, night)
            tintAlpha = palette.opacity.coerceIn(8, 55) / 100f
            accent = GlassStyle.accentColor(palette, night)
            normal = GlassStyle.normalTextColor(night)
            barHeightDp = p.pillHeight
            marginHDp = p.pillMarginH
            marginBottomDp = p.pillMarginBottom
            invalidate()
        }

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
            drawBar(canvas)
        }

        private fun drawBar(canvas: Canvas) {
            val barH = barHeightDp * d
            val left = marginHDp * d
            val right = width - marginHDp * d
            val bottom = height - marginBottomDp * d
            val top = bottom - barH
            if (right <= left || barH <= 0f) return
            barRect.set(left, top, right, bottom)
            val radius = barH / 2f

            paint.shader = null
            paint.style = Paint.Style.FILL
            paint.color = Color.argb(if (night) 80 else 45, 0, 0, 0)
            canvas.drawRoundRect(left, top + 3f * d, right, bottom + 4f * d, radius, radius, paint)

            paint.color = Color.argb(
                (tintAlpha * 255f).toInt().coerceIn(0, 255),
                Color.red(tint), Color.green(tint), Color.blue(tint)
            )
            canvas.drawRoundRect(barRect, radius, radius, paint)

            val tabWidth = (right - left) / 3f
            val lensPadX = 3f * d
            val lensPadY = 4f * d
            lensRect.set(left + lensPadX, top + lensPadY, left + tabWidth - lensPadX, bottom - lensPadY)
            val lensRadius = lensRect.height() / 2f
            paint.color = Color.argb(if (night) 34 else 30, 255, 255, 255)
            canvas.drawRoundRect(lensRect, lensRadius, lensRadius, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.2f * d
            paint.color = Color.argb(if (night) 170 else 215, 255, 255, 255)
            canvas.drawRoundRect(lensRect, lensRadius, lensRadius, paint)
            paint.style = Paint.Style.FILL
            paint.strokeWidth = 1f * d
            paint.color = Color.argb(if (night) 130 else 150, 255, 255, 255)
            canvas.drawRoundRect(
                left + d, top + d, right - d, bottom - d,
                radius - d, radius - d, paint
            )

            val iconSize = 21f * d
            val iconTop = top + 10f * d
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = 10f * d
            paint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            labels.forEachIndexed { index, text ->
                val cx = left + tabWidth * (index + 0.5f)
                val color = if (index == 0) accent else normal
                val icon = icons[index]
                icon.setTintList(ColorStateList.valueOf(color))
                icon.setBounds(
                    (cx - iconSize / 2f).toInt(),
                    iconTop.toInt(),
                    (cx + iconSize / 2f).toInt(),
                    (iconTop + iconSize).toInt()
                )
                icon.draw(canvas)
                paint.color = color
                canvas.drawText(text, cx, bottom - 11f * d, paint)
            }
            paint.textAlign = Paint.Align.LEFT
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { lastY = event.y; parent?.requestDisallowInterceptTouchEvent(true) }
                MotionEvent.ACTION_MOVE -> {
                    offset = (offset + (event.y - lastY) / d) % 72f
                    lastY = event.y
                    invalidate()
                }
                MotionEvent.ACTION_UP -> { parent?.requestDisallowInterceptTouchEvent(false); performClick() }
                MotionEvent.ACTION_CANCEL -> parent?.requestDisallowInterceptTouchEvent(false)
            }
            return true
        }
        override fun performClick(): Boolean { super.performClick(); return true }
    }
}
