package com.yilink.jumpclean.hooks

import android.content.Context
import android.content.res.ColorStateList
import android.widget.ImageView
import android.widget.TextView
import android.view.ViewGroup
import com.example.liquidglass.LiquidGlassTabBar

/**
 * 只做一件事：给库的 tab 文字/图标上色。
 *
 * 玻璃绘制、透镜、滑动动画、拖拽和背景采样全部留在 [LiquidGlassTabBar] 内部。
 * 这里不改透镜、不反射私有字段，只遍历库公开创建出来的 tab 子 View 设置颜色，
 * 因为库把普通态文字色写死成“亮背景黑 / 暗背景白”，无法覆盖。
 */
class GlassTabBar(context: Context) : LiquidGlassTabBar(context) {
    private var labelSelected = 0xFF202633.toInt()
    private var labelNormal = 0x8C3A4252.toInt()

    fun setLabelColors(selected: Int, normal: Int) {
        labelSelected = selected
        labelNormal = normal
        reapplyLabelColors()
    }

    /** 库在 setTabs / 切页时会重写文字色，之后需要再套一次。 */
    fun reapplyLabelColors() {
        for (i in 0 until childCount) {
            val row = getChildAt(i) as? ViewGroup ?: continue
            if (row.childCount == 0) continue
            for (j in 0 until row.childCount) {
                val tab = row.getChildAt(j) as? ViewGroup ?: continue
                val color = if (j == selectedIndex) labelSelected else labelNormal
                for (k in 0 until tab.childCount) {
                    when (val child = tab.getChildAt(k)) {
                        is TextView -> child.setTextColor(color)
                        is ImageView -> child.imageTintList = ColorStateList.valueOf(color)
                    }
                }
            }
        }
    }
}
