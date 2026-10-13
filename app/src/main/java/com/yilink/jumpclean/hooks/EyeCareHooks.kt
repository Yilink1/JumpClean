package com.yilink.jumpclean.hooks

import android.app.Activity
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.drawable.ColorDrawable
import android.widget.TextView
import android.content.res.ColorStateList
import com.yilink.jumpclean.config.ConfigManager
import com.yilink.jumpclean.config.JumpConstants
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * 夜间护眼：把宿主夜间模式的纯黑 #000000 底色换成深灰，可选再把纯白文字压成柔白。
 *
 * 依据（来自对 Jump 3.30.30 的反编译）：宿主的夜间底色不是主题给的，而是大量布局直接引用
 * `@color/gray_f8f7f7_night_black`（-night 变体 = #000000），所以在资源层改最稳。
 *
 * 为什么 hook ColorDrawable.draw 而不是 Resources.getValue / getColor：
 * `android:background="@color/xxx"` 的解析不会经过公开的 Resources.getValue，
 * 而是在 ResourcesImpl 里直接 new ColorDrawable（或复用预加载的 ColorDrawable 状态），
 * 构造器 / setColor 都可能被绕过；draw 是必经之路，且 getColor() 是公开 API，不需要反射。
 *
 * 只在夜间生效；切换设置后需要重启宿主（Drawable 缓存里已有的实例要等重建）。
 */
object EyeCareHooks {

    /** 夜间底色候选：由深到浅 */
    val BLACK_LEVELS = intArrayOf(0xFF121212.toInt(), 0xFF18181B.toInt(), 0xFF1C1B20.toInt(), 0xFF232327.toInt(), 0xFF2C2C31.toInt())
    val BLACK_LEVEL_NAMES = arrayOf("极深 #121212", "深灰 #18181B（默认）", "微紫 #1C1B20", "浅一点 #232327", "更浅 #2C2C31")
    const val DEFAULT_LEVEL = 1
    private const val SOFT_WHITE = 0xFFE4E4E7.toInt()

    @Volatile private var softBlack = false
    @Volatile private var softWhite = false
    @Volatile private var night = false
    @Volatile private var bgColor = BLACK_LEVELS[DEFAULT_LEVEL]
    @Volatile private var diag = false

    // 诊断计数（仅「底栏变动追踪日志」开启时输出）：用来判断哪条路径真的命中了
    @Volatile private var statColorForState = 0
    @Volatile private var statSoftened = 0
    @Volatile private var statSetText = 0
    @Volatile private var statSetSoftened = 0

    private val specsField by lazy {
        try { ColorStateList::class.java.getDeclaredField("mStateSpecs").also { it.isAccessible = true } } catch (_: Throwable) { null }
    }
    private val colorsField by lazy {
        try { ColorStateList::class.java.getDeclaredField("mColors").also { it.isAccessible = true } } catch (_: Throwable) { null }
    }

    /** 把 ColorStateList 里「恰好是纯白」的那几档换成柔白，保留其余状态；没有可改的返回 null */
    @Suppress("UNCHECKED_CAST")
    private fun softenWhite(csl: ColorStateList?): ColorStateList? {
        if (csl == null) return null
        try {
            val colors = colorsField?.get(csl) as? IntArray
            val specs = specsField?.get(csl) as? Array<IntArray>
            if (colors != null && specs != null) {
                var changed = false
                val nc = IntArray(colors.size) { i ->
                    if (colors[i] == -1) { changed = true; SOFT_WHITE } else colors[i]
                }
                return if (changed) ColorStateList(specs, nc) else null
            }
        } catch (_: Throwable) {}
        return if (!csl.isStateful && csl.defaultColor == -1) ColorStateList.valueOf(SOFT_WHITE) else null
    }

    fun hook(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            // 每次 Activity 恢复时刷新设置快照与夜间状态（AppCompat 切夜间会重建 Activity，必经 onResume）
            XposedHelpers.findAndHookMethod(Activity::class.java, "onResume", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val a = param.thisObject as? Activity ?: return
                    try {
                        night = (a.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                                Configuration.UI_MODE_NIGHT_YES
                        softBlack = ConfigManager.isFeatureEnabled(a, JumpConstants.KEY_EYE_SOFT_BLACK)
                        softWhite = ConfigManager.isFeatureEnabled(a, JumpConstants.KEY_EYE_SOFT_WHITE)
                        val lv = ConfigManager.getIntPref(JumpConstants.KEY_EYE_BLACK_LEVEL, DEFAULT_LEVEL)
                            .coerceIn(0, BLACK_LEVELS.size - 1)
                        bgColor = BLACK_LEVELS[lv]
                        diag = GlassStyle.devTrace()
                        if (diag) {
                            ConfigManager.log(
                                "[EyeCare] night=$night black=$softBlack white=$softWhite " +
                                    "getColorForState=$statColorForState softened=$statSoftened " +
                                    "setTextColor=$statSetText setSoftened=$statSetSoftened"
                            )
                        }
                    } catch (_: Throwable) {}
                }
            })

            XposedHelpers.findAndHookMethod(ColorDrawable::class.java, "draw", Canvas::class.java, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!softBlack || !night) return
                    val d = param.thisObject as? ColorDrawable ?: return
                    val c = d.color
                    // 只动「完全不透明」的近黑，半透明遮罩 / 渐变 / 图片都不碰
                    if ((c ushr 24) == 0xFF && (c and 0xFF) <= 6 && ((c shr 8) and 0xFF) <= 6 && ((c shr 16) and 0xFF) <= 6) {
                        d.color = bgColor
                    }
                }
            })

            XposedHelpers.findAndHookMethod(
                ColorStateList::class.java, "getColorForState",
                IntArray::class.java, Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!softWhite || !night) return
                        if (diag) statColorForState++
                        val r = param.result as? Int ?: return
                        if (r == -1) { param.result = SOFT_WHITE; if (diag) statSoftened++ } // 0xFFFFFFFF
                    }
                }
            )

            // 补两条「公开 API 入口」：setTextColor(int) / setTextColor(ColorStateList)。
            // 实测日志里 TextView 构造完成后的那条路径命中 0 次，已去掉（它每创建一个 TextView 都要跑一遍，纯开销）
            XposedHelpers.findAndHookMethod(TextView::class.java, "setTextColor", Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!softWhite || !night) return
                        if (diag) statSetText++
                        if (param.args[0] == -1) { param.args[0] = SOFT_WHITE; if (diag) statSetSoftened++ }
                    }
                })
            XposedHelpers.findAndHookMethod(TextView::class.java, "setTextColor", ColorStateList::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!softWhite || !night) return
                        if (diag) statSetText++
                        val n = softenWhite(param.args[0] as? ColorStateList) ?: return
                        param.args[0] = n
                        if (diag) statSetSoftened++
                    }
                })
            ConfigManager.log("[Hook] 夜间护眼（柔化纯黑 / 纯白文字）已就绪")
        } catch (t: Throwable) {
            ConfigManager.logError("✘ 夜间护眼 Hook 失败", t)
        }
    }
}
