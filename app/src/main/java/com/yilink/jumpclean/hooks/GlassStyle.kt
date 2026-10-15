package com.yilink.jumpclean.hooks

import com.example.liquidglass.GlassAccessibilityMode
import com.example.liquidglass.GlassMaterial
import com.yilink.jumpclean.config.ConfigManager
import com.yilink.jumpclean.config.JumpConstants

/**
 * 液态玻璃底栏的全部可调参数：持久化 + 应用到 [JumpGlassTabBar]。
 * 设置弹窗的预览条和主界面的真实底栏走同一个 [applyTo]，所见即所得。
 *
 * 默认值是「通透」调校：关掉自适应染色（亮背景下会被压灰）、不染色、轻模糊。
 */
object GlassStyle {

    val TINT_COLORS = intArrayOf(0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFF0A84FF.toInt(), 0xFFFF453A.toInt())
    val SELECTED_COLORS = arrayOf<Int?>(null, 0xFF007AFF.toInt(), 0xFFFF5252.toInt(), 0xFFFF9500.toInt())

    class Params {
        var material = 0          // 0 常规 / 1 高透
        var adaptiveTint = false  // 库的自适应明暗染色（亮背景会压灰）
        var tintColor = 0         // TINT_COLORS 下标
        var tintStrength = 0      // %
        var blur = 3              // %（0 = 关闭背景模糊）
        var saturation = 130      // %
        var refraction = 14       // dp
        var bevel = 14            // dp
        var falloff = 20          // ×0.1
        var dispersion = 6        // %
        var edgeHighlight = 100   // %
        var sensor = true
        var textMode = 0          // 0 自动采样 / 1 深色字 / 2 浅色字
        var selectedColor = 0     // SELECTED_COLORS 下标
        var textSize = 13         // sp
        var pillHeight = 52       // dp
        var pillMarginH = 28      // dp
        var pillMarginBottom = 16 // dp
        var dropRefraction = 5    // dp
        var dropBevel = 9         // dp
        var dropDispersion = 4    // %
        var pressInflate = 108    // %
        var pressBoost = 220      // %
        var pressLift = 10        // %

        // —— 投影 / 页面留白 ——
        var shadowStrength = 22   // %，0 = 关
        var shadowBlur = 16       // dp
        var shadowOffset = 5      // dp
        var diagLog = false       // 玻璃诊断日志（帧率 / 底栏变动追踪 / 护眼命中），需配合总调试日志

        // —— 开发者模式 ——
        var devMode = false
        var cornerDp = 0          // 0 = 全圆胶囊
        var noFold = false
        var outward = false
        var edgeSoft = 0          // dp
        var adaptiveScale = true
        var edgeOn = true
        var a11yFull = false
        var dynamicBg = true      // 背景持续刷新（每帧重绘，最耗电的一项）
        var rimLight = 35         // %，厚度光边（外缘渐变亮线 + 内缘暗线 + 顶部高光）
        var dragJelly = 60        // %，拖动时水滴随速度拉伸/压扁
        var wallSquash = 60       // %，拖过两端时水滴被墙压扁

        fun copyFrom(o: Params) {
            material = o.material; adaptiveTint = o.adaptiveTint
            tintColor = o.tintColor; tintStrength = o.tintStrength
            blur = o.blur; saturation = o.saturation
            refraction = o.refraction; bevel = o.bevel; falloff = o.falloff
            dispersion = o.dispersion; edgeHighlight = o.edgeHighlight; sensor = o.sensor
            textMode = o.textMode; selectedColor = o.selectedColor; textSize = o.textSize
            pillHeight = o.pillHeight; pillMarginH = o.pillMarginH; pillMarginBottom = o.pillMarginBottom
            dropRefraction = o.dropRefraction; dropBevel = o.dropBevel; dropDispersion = o.dropDispersion
            pressInflate = o.pressInflate; pressBoost = o.pressBoost; pressLift = o.pressLift
            shadowStrength = o.shadowStrength; shadowBlur = o.shadowBlur; shadowOffset = o.shadowOffset
            diagLog = o.diagLog
            devMode = o.devMode; cornerDp = o.cornerDp
            noFold = o.noFold; outward = o.outward; edgeSoft = o.edgeSoft
            adaptiveScale = o.adaptiveScale; edgeOn = o.edgeOn; a11yFull = o.a11yFull
            dynamicBg = o.dynamicBg; rimLight = o.rimLight; dragJelly = o.dragJelly; wallSquash = o.wallSquash
        }
    }

    fun defaults() = Params()

    fun load(): Params {
        val d = Params()
        return Params().apply {
            material = i("lg_material", d.material)
            adaptiveTint = ConfigManager.getBooleanPref("lg_adaptive_tint", d.adaptiveTint)
            tintColor = i("lg_tint_color", d.tintColor).coerceIn(0, TINT_COLORS.size - 1)
            tintStrength = i("lg_tint_strength", d.tintStrength)
            blur = i("lg_blur", d.blur)
            saturation = i("lg_saturation", d.saturation)
            // 以下四项沿用旧版的 key，已保存过的值继续有效
            refraction = i(JumpConstants.KEY_LIQUID_GLASS_REFRACTION, d.refraction)
            bevel = i(JumpConstants.KEY_LIQUID_GLASS_BEVEL, d.bevel)
            dispersion = i(JumpConstants.KEY_LIQUID_GLASS_DISPERSION, d.dispersion)
            sensor = ConfigManager.getBooleanPref(JumpConstants.KEY_LIQUID_GLASS_SENSOR, d.sensor)
            falloff = i("lg_falloff", d.falloff)
            edgeHighlight = i("lg_edge_highlight", d.edgeHighlight)
            textMode = i("lg_text_mode", d.textMode).coerceIn(0, 2)
            selectedColor = i("lg_selected_color", d.selectedColor).coerceIn(0, SELECTED_COLORS.size - 1)
            textSize = i("lg_text_size", d.textSize)
            pillHeight = i("lg_pill_height", d.pillHeight)
            pillMarginH = i("lg_pill_margin_h", d.pillMarginH)
            pillMarginBottom = i("lg_pill_margin_bottom", d.pillMarginBottom)
            dropRefraction = i("lg_drop_refraction", d.dropRefraction)
            dropBevel = i("lg_drop_bevel", d.dropBevel)
            dropDispersion = i("lg_drop_dispersion", d.dropDispersion)
            pressInflate = i("lg_press_inflate", d.pressInflate)
            pressBoost = i("lg_press_boost", d.pressBoost)
            pressLift = i("lg_press_lift", d.pressLift)
            shadowStrength = i("lg_shadow_strength", d.shadowStrength)
            shadowBlur = i("lg_shadow_blur", d.shadowBlur)
            shadowOffset = i("lg_shadow_offset", d.shadowOffset)
            diagLog = ConfigManager.getBooleanPref("lg_diag", d.diagLog)
            devMode = ConfigManager.getBooleanPref("lg_dev_mode", d.devMode)
            cornerDp = i("lg_corner", d.cornerDp)
            noFold = ConfigManager.getBooleanPref("lg_no_fold", d.noFold)
            outward = ConfigManager.getBooleanPref("lg_outward", d.outward)
            edgeSoft = i("lg_edge_soft", d.edgeSoft)
            adaptiveScale = ConfigManager.getBooleanPref("lg_adaptive_scale", d.adaptiveScale)
            edgeOn = ConfigManager.getBooleanPref("lg_edge_on", d.edgeOn)
            a11yFull = ConfigManager.getBooleanPref("lg_a11y_full", d.a11yFull)
            dynamicBg = ConfigManager.getBooleanPref("lg_dynamic_bg", d.dynamicBg)
            rimLight = i("lg_rim", d.rimLight)
            dragJelly = i("lg_jelly", d.dragJelly)
            wallSquash = i("lg_wall", d.wallSquash)
        }
    }

    /**
     * 玻璃诊断输出（每 3 秒一行帧率、底栏文字/红点变动追踪、护眼命中次数）。
     * 必须「开启调试日志」总开关 + 玻璃面板里的「玻璃诊断日志」同时开启：
     * 它每 3 秒写一行，会把 200 行日志缓冲冲掉，还会持续写日志文件，所以不能跟着总开关默认输出。
     */
    fun devTrace(): Boolean =
        ConfigManager.getBooleanPref(JumpConstants.KEY_ENABLE_DEBUG_LOG, false) &&
            ConfigManager.getBooleanPref("lg_diag", false)

    fun save(p: Params) {
        ConfigManager.setIntPref("lg_material", p.material)
        ConfigManager.setBooleanPref("lg_adaptive_tint", p.adaptiveTint)
        ConfigManager.setIntPref("lg_tint_color", p.tintColor)
        ConfigManager.setIntPref("lg_tint_strength", p.tintStrength)
        ConfigManager.setIntPref("lg_blur", p.blur)
        ConfigManager.setIntPref("lg_saturation", p.saturation)
        ConfigManager.setIntPref(JumpConstants.KEY_LIQUID_GLASS_REFRACTION, p.refraction)
        ConfigManager.setIntPref(JumpConstants.KEY_LIQUID_GLASS_BEVEL, p.bevel)
        ConfigManager.setIntPref(JumpConstants.KEY_LIQUID_GLASS_DISPERSION, p.dispersion)
        ConfigManager.setBooleanPref(JumpConstants.KEY_LIQUID_GLASS_SENSOR, p.sensor)
        ConfigManager.setIntPref("lg_falloff", p.falloff)
        ConfigManager.setIntPref("lg_edge_highlight", p.edgeHighlight)
        ConfigManager.setIntPref("lg_text_mode", p.textMode)
        ConfigManager.setIntPref("lg_selected_color", p.selectedColor)
        ConfigManager.setIntPref("lg_text_size", p.textSize)
        ConfigManager.setIntPref("lg_pill_height", p.pillHeight)
        ConfigManager.setIntPref("lg_pill_margin_h", p.pillMarginH)
        ConfigManager.setIntPref("lg_pill_margin_bottom", p.pillMarginBottom)
        ConfigManager.setIntPref("lg_drop_refraction", p.dropRefraction)
        ConfigManager.setIntPref("lg_drop_bevel", p.dropBevel)
        ConfigManager.setIntPref("lg_drop_dispersion", p.dropDispersion)
        ConfigManager.setIntPref("lg_press_inflate", p.pressInflate)
        ConfigManager.setIntPref("lg_press_boost", p.pressBoost)
        ConfigManager.setIntPref("lg_press_lift", p.pressLift)
        ConfigManager.setIntPref("lg_shadow_strength", p.shadowStrength)
        ConfigManager.setIntPref("lg_shadow_blur", p.shadowBlur)
        ConfigManager.setIntPref("lg_shadow_offset", p.shadowOffset)
        ConfigManager.setBooleanPref("lg_diag", p.diagLog)
        ConfigManager.setBooleanPref("lg_dev_mode", p.devMode)
        ConfigManager.setIntPref("lg_corner", p.cornerDp)
        ConfigManager.setBooleanPref("lg_no_fold", p.noFold)
        ConfigManager.setBooleanPref("lg_outward", p.outward)
        ConfigManager.setIntPref("lg_edge_soft", p.edgeSoft)
        ConfigManager.setBooleanPref("lg_adaptive_scale", p.adaptiveScale)
        ConfigManager.setBooleanPref("lg_edge_on", p.edgeOn)
        ConfigManager.setBooleanPref("lg_a11y_full", p.a11yFull)
        ConfigManager.setBooleanPref("lg_dynamic_bg", p.dynamicBg)
        ConfigManager.setIntPref("lg_rim", p.rimLight)
        ConfigManager.setIntPref("lg_jelly", p.dragJelly)
        ConfigManager.setIntPref("lg_wall", p.wallSquash)
    }

    /** 把参数应用到底栏（不含外形尺寸：胶囊高度/边距由 LayoutParams 决定，见 LiquidGlassHooks.pillParams） */
    fun applyTo(bar: JumpGlassTabBar, p: Params, density: Float) {
        val dpf = { v: Int -> v * density }
        bar.enableDynamicBackground = p.dynamicBg
        bar.material = if (p.material == 1) GlassMaterial.CLEAR else GlassMaterial.REGULAR
        bar.enableAdaptiveTint = p.adaptiveTint
        bar.enableSensorHighlight = p.sensor
        bar.enableBackdropBlur = p.blur > 0
        bar.blurAmount = p.blur / 100f
        bar.saturation = p.saturation.toFloat()
        bar.refractionHeight = dpf(p.refraction)
        bar.bevelWidth = dpf(p.bevel)
        bar.refractionFalloff = p.falloff / 10f
        bar.dispersionStrength = p.dispersion / 100f
        bar.edgeHighlightOpacity = p.edgeHighlight.toFloat()
        bar.enableEdgeHighlight = p.edgeOn
        bar.refractionNoFold = p.noFold
        bar.refractionOutward = p.outward
        bar.edgeSoftness = dpf(p.edgeSoft)
        bar.adaptiveLensScale = p.adaptiveScale
        bar.accessibilityMode = if (p.a11yFull) GlassAccessibilityMode.FORCE_FULL else GlassAccessibilityMode.AUTO
        bar.cornerRadius = if (p.cornerDp <= 0) 999f else dpf(p.cornerDp)
        bar.rimStrength = p.rimLight / 100f
        bar.dragJelly = p.dragJelly / 100f
        bar.wallSquash = p.wallSquash / 100f
        bar.setGlassTint(TINT_COLORS[p.tintColor], p.tintStrength / 100f)
        bar.selectedTintColor = SELECTED_COLORS[p.selectedColor]
        bar.configureDroplet(
            refractionPx = dpf(p.dropRefraction),
            bevelPx = dpf(p.dropBevel),
            dispersion = p.dropDispersion / 100f,
            inflate = p.pressInflate / 100f,
            boost = p.pressBoost / 100f,
            lift = p.pressLift / 100f
        )
        bar.textMode = p.textMode
        bar.setLabelTextSize(p.textSize.toFloat())
    }

    private fun i(key: String, def: Int) = ConfigManager.getIntPref(key, def)
}
