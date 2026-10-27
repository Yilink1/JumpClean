package com.yilink.jumpclean.hooks

import android.content.Context
import android.content.res.Configuration
import com.example.liquidglass.GlassMaterial
import com.yilink.jumpclean.config.ConfigManager
import com.yilink.jumpclean.config.JumpConstants

/**
 * 底栏配置：只保存用户看得懂的选项，并且只调用库的公开 API。
 *
 * 折射、斜面、色散、透镜尺寸、按压动画都由 LiquidGlassTabBar 自己决定，
 * 这里不再保存第二套光学参数。
 */
object GlassStyle {
    private const val PREFIX = "lg_v4_"

    class Palette(var tone: Int = 0, var opacity: Int = 44, var accent: Int = 0) {
        fun copyFrom(other: Palette) {
            tone = other.tone
            opacity = other.opacity
            accent = other.accent
        }
    }

    class Params {
        val day = Palette(opacity = 44)
        val night = Palette(opacity = 42)
        var finish = 0      // 0 轻透 / 1 柔雾
        var edge = 1        // 0 通透 / 1 标准 / 2 明显
        var dynamicBg = false
        var diagLog = false
        var pillHeight = 60
        var pillMarginH = 24
        var pillMarginBottom = 12
        val shadowBlur = 16
        val shadowOffset = 4
        val cornerDp = 0

        fun copyFrom(other: Params) {
            day.copyFrom(other.day)
            night.copyFrom(other.night)
            finish = other.finish
            edge = other.edge
            dynamicBg = other.dynamicBg
            diagLog = other.diagLog
            pillHeight = other.pillHeight
            pillMarginH = other.pillMarginH
            pillMarginBottom = other.pillMarginBottom
        }
    }

    fun defaults() = Params()

    fun isNight(context: Context): Boolean =
        context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES

    fun load(): Params = Params().apply {
        // v4 使用独立 key：v3 里遗留的自绘光学参数不再影响新外观。
        fun palette(name: String, target: Palette) {
            target.tone = i("${name}_tone", target.tone).coerceIn(0, 2)
            target.opacity = i("${name}_opacity", target.opacity).coerceIn(8, 55)
            target.accent = i("${name}_accent", target.accent).coerceIn(0, 2)
        }
        palette("day", day)
        palette("night", night)
        finish = i("finish", finish).coerceIn(0, 1)
        edge = i("edge", edge).coerceIn(0, 2)
        dynamicBg = ConfigManager.getBooleanPref(PREFIX + "dynamic", dynamicBg)
        diagLog = ConfigManager.getBooleanPref("lg_diag", false)
        pillHeight = i("pill_height", pillHeight).coerceIn(52, 76)
        pillMarginH = i("pill_margin_h", pillMarginH).coerceIn(12, 40)
        pillMarginBottom = i("pill_margin_bottom", pillMarginBottom).coerceIn(4, 28)
    }

    fun save(p: Params) {
        val context = ConfigManager.getValidAppContext() ?: return
        val edit = context.getSharedPreferences(JumpConstants.PREFS_NAME, Context.MODE_PRIVATE).edit()
        fun palette(name: String, value: Palette) {
            edit.putInt(PREFIX + "${name}_tone", value.tone.coerceIn(0, 2))
            edit.putInt(PREFIX + "${name}_opacity", value.opacity.coerceIn(8, 55))
            edit.putInt(PREFIX + "${name}_accent", value.accent.coerceIn(0, 2))
        }
        palette("day", p.day)
        palette("night", p.night)
        edit.putInt(PREFIX + "finish", p.finish.coerceIn(0, 1))
            .putInt(PREFIX + "edge", p.edge.coerceIn(0, 2))
            .putBoolean(PREFIX + "dynamic", p.dynamicBg)
            .putBoolean("lg_diag", p.diagLog)
            .putInt(PREFIX + "pill_height", p.pillHeight.coerceIn(52, 76))
            .putInt(PREFIX + "pill_margin_h", p.pillMarginH.coerceIn(12, 40))
            .putInt(PREFIX + "pill_margin_bottom", p.pillMarginBottom.coerceIn(4, 28))
            .apply()
    }

    fun devTrace(): Boolean =
        ConfigManager.getBooleanPref(JumpConstants.KEY_ENABLE_DEBUG_LOG, false) &&
            ConfigManager.getBooleanPref("lg_diag", false)

    fun tintColor(palette: Palette, night: Boolean): Int = if (night) {
        when (palette.tone) {
            1 -> 0xFF1A2638.toInt()
            2 -> 0xFF282034.toInt()
            else -> 0xFF0B0D12.toInt()
        }
    } else {
        when (palette.tone) {
            1 -> 0xFFFFF6EA.toInt()
            2 -> 0xFFE8F3FF.toInt()
            else -> 0xFFFFFFFF.toInt()
        }
    }

    fun accentColor(palette: Palette, night: Boolean): Int = when (palette.accent) {
        1 -> if (night) 0xFF8FC4FF.toInt() else 0xFF1769BE.toInt()
        2 -> if (night) 0xFFFFACA3.toInt() else 0xFFB93F3D.toInt()
        else -> if (night) 0xFFF0F2F7.toInt() else 0xFF202633.toInt()
    }

    fun normalTextColor(night: Boolean): Int =
        if (night) 0xD0E8EDF7.toInt() else 0xC03A4252.toInt()

    fun shadowStrength(night: Boolean): Float = if (night) 0.36f else 0.30f

    fun edgeHighlightOpacity(preset: Int, night: Boolean): Float = when (preset.coerceIn(0, 2)) {
        0 -> if (night) 42f else 58f
        2 -> 100f
        else -> if (night) 62f else 82f
    }

    /** 唯一把配置作用到真实底栏的入口。 */
    fun apply(bar: GlassTabBar, p: Params, previewNight: Boolean? = null) {
        val night = previewNight ?: isNight(bar.context)
        val palette = if (night) p.night else p.day
        // REGULAR + 明确的 tint：避免 CLEAR 自带的压暗层让白天发灰。
        bar.material = GlassMaterial.REGULAR
        bar.enableAdaptiveTint = false
        bar.enableBackdropBlur = true
        // 库在 overLight=true 时会额外压 20% 黑（亮背景保文字对比）。
        // 我们要的是白色玻璃，所以走材质自带的白色底 tint；模糊半径用 blurAmount 补回来。
        bar.overLight = false
        bar.blurAmount = if (p.finish == 1) 0.50f else 0.42f
        bar.saturation = if (night) 108f else 118f
        bar.enableOptimizedCapture = true
        bar.globalDownsampleFactor = 0.5f
        bar.downsampleScale = 2
        bar.enableEdgeHighlight = true
        bar.edgeHighlightOpacity = edgeHighlightOpacity(p.edge, night)
        bar.cornerRadius = 999f
        bar.enableDynamicBackground = p.dynamicBg
        bar.setGlassTint(tintColor(palette, night), palette.opacity.coerceIn(8, 55) / 100f)
        bar.selectedTintColor = accentColor(palette, night)
        bar.setLabelColors(accentColor(palette, night), normalTextColor(night))
        bar.invalidate()
    }

    private fun i(key: String, default: Int) = ConfigManager.getIntPref(PREFIX + key, default)
}
