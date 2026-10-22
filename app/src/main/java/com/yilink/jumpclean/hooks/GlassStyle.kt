package com.yilink.jumpclean.hooks

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.util.TypedValue
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
    private const val PREFIX = "lg_v6_"

    class Palette(var tone: Int = 0, var opacity: Int = 18, var accent: Int = 0) {
        fun copyFrom(other: Palette) {
            tone = other.tone
            opacity = other.opacity
            accent = other.accent
        }
    }

    class Params {
        val day = Palette(opacity = 18)
        val night = Palette(opacity = 28)
        var finish = 0      // 0 轻透 / 1 柔雾
        var edge = 1        // 0 通透 / 1 标准 / 2 明显
        var dynamicBg = false
        var diagLog = false
        var pillHeight = 64
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

    fun isNight(context: Context): Boolean {
        // 1) 应用主题自己声明了明暗时，优先跟 Jump 的主题走。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val attrs = context.theme.obtainStyledAttributes(intArrayOf(android.R.attr.isLightTheme))
            try {
                if (attrs.hasValue(0)) return !attrs.getBoolean(0, true)
            } finally {
                attrs.recycle()
            }
        }

        // 2) 没有声明时，用状态栏/窗口背景的亮度判断（Jump 手动深色主题不一定改 uiMode）。
        val activity = context as? Activity
        if (activity != null) {
            @Suppress("DEPRECATION")
            val status = activity.window?.statusBarColor ?: Color.TRANSPARENT
            if (status != Color.TRANSPARENT) {
                val lum = luminance(status)
                if (lum < 0.35f) return true
                if (lum > 0.65f) return false
            }
            val tv = TypedValue()
            if (context.theme.resolveAttribute(android.R.attr.windowBackground, tv, true) &&
                tv.type >= TypedValue.TYPE_FIRST_COLOR_INT &&
                tv.type <= TypedValue.TYPE_LAST_COLOR_INT
            ) {
                val lum = luminance(tv.data)
                if (lum < 0.35f) return true
                if (lum > 0.65f) return false
            }
            val decorBg = activity.window?.decorView?.background
            if (decorBg is ColorDrawable) {
                val lum = luminance(decorBg.color)
                if (lum < 0.35f) return true
                if (lum > 0.65f) return false
            }
        }

        // 3) 最后才退回系统 uiMode。
        return context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
    }

    private fun luminance(color: Int): Float {
        val r = Color.red(color) / 255f
        val g = Color.green(color) / 255f
        val b = Color.blue(color) / 255f
        return 0.299f * r + 0.587f * g + 0.114f * b
    }

    fun load(): Params = Params().apply {
        // v6 使用独立 key：旧版自绘光学参数不再影响新外观。
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
        pillHeight = i("pill_height", pillHeight).coerceIn(60, 76)
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
            .putInt(PREFIX + "pill_height", p.pillHeight.coerceIn(60, 76))
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
            else -> 0xFFF5F7FA.toInt()
        }
    }

    fun accentColor(palette: Palette, night: Boolean): Int = when (palette.accent) {
        1 -> if (night) 0xFF8FC4FF.toInt() else 0xFF1769BE.toInt()
        2 -> if (night) 0xFFFFACA3.toInt() else 0xFFB93F3D.toInt()
        else -> if (night) 0xFFF0F2F7.toInt() else 0xFF202633.toInt()
    }

    fun normalTextColor(night: Boolean): Int =
        if (night) 0xE8E8EDF7.toInt() else 0xE03A4252.toInt()

    fun shadowStrength(night: Boolean): Float = if (night) 0.42f else 0.38f

    fun edgeHighlightOpacity(preset: Int, night: Boolean): Float = when (preset.coerceIn(0, 2)) {
        0 -> if (night) 48f else 60f
        2 -> 100f
        else -> if (night) 82f else 100f
    }

    /** 唯一把配置作用到真实底栏的入口。 */
    fun apply(bar: GlassTabBar, p: Params, previewNight: Boolean? = null) {
        val night = previewNight ?: isNight(bar.context)
        val palette = if (night) p.night else p.day
        // REGULAR + 明确的 tint：避免 CLEAR 自带的压暗层让白天发灰。
        bar.material = GlassMaterial.REGULAR
        bar.enableAdaptiveTint = false
        bar.enableBackdropBlur = true
        // 库在 overLight=true 时会额外压 20% 黑；夜间用黑色底，白天用材质白色底。
        bar.overLight = night
        val soft = p.finish == 1
        bar.blurAmount = if (night) {
            if (soft) 0.14f else 0.10f
        } else {
            if (soft) 0.34f else 0.30f
        }
        bar.saturation = if (night) 132f else 128f
        // 信息流本身很重：玻璃捕获按 1/2 分辨率、模糊图再降采样，
        // 并在关掉额外的色差整图拷贝，避免把宿主推向 OOM。
        bar.enableOptimizedCapture = true
        bar.globalDownsampleFactor = 0.5f
        bar.downsampleScale = 3
        bar.highQualityBlur = false
        bar.enableEdgeHighlight = true
        bar.edgeHighlightOpacity = edgeHighlightOpacity(p.edge, night)
        // 液态玻璃的关键：边缘把背景“折”进来，再带一点色散。
        val density = bar.resources.displayMetrics.density
        bar.enableChromaticDispersion = false
        bar.dispersionStrength = 0.20f
        bar.bevelWidth = 18f * density
        bar.refractionHeight = 10f * density
        bar.refractionFalloff = 0.70f
        bar.refractionNoFold = true
        bar.refractionOutward = false
        bar.edgeSoftness = 1f * density
        bar.adaptiveLensScale = true
        bar.cornerRadius = 999f
        bar.enableDynamicBackground = p.dynamicBg
        bar.setGlassTint(tintColor(palette, night), palette.opacity.coerceIn(8, 55) / 100f)
        bar.selectedTintColor = accentColor(palette, night)
        bar.setLabelColors(accentColor(palette, night), normalTextColor(night))
        bar.restoreQuality()
        bar.setDropletNight(night, palette.opacity)
        bar.invalidate()
    }

    private fun i(key: String, default: Int) = ConfigManager.getIntPref(PREFIX + key, default)
}
