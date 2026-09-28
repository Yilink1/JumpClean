package com.yilink.jumpclean

import android.content.Context
import android.view.View
import android.view.ViewGroup
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Field
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

object HookUtils {
    private const val TAG = "JumpClean"
    private val resIdCache = ConcurrentHashMap<String, Int>()
    private val RETRY_DELAYS_MS = longArrayOf(500L, 1500L, 3000L)

    private data class ViewOriginalState(
        val width: Int,
        val height: Int,
        val topMargin: Int,
        val bottomMargin: Int,
        val leftMargin: Int,
        val rightMargin: Int,
        val paddingTop: Int,
        val paddingBottom: Int,
        val paddingLeft: Int,
        val paddingRight: Int
    )

    private val collapsedViewStates = WeakHashMap<View, ViewOriginalState>()

    fun isCollapsed(view: View): Boolean = collapsedViewStates.containsKey(view)
    fun hasCollapsedViews(): Boolean = collapsedViewStates.isNotEmpty()

    fun log(msg: String) {
        XposedBridge.log("[$TAG] $msg")
    }

    fun err(msg: String, t: Throwable? = null) {
        XposedBridge.log("[$TAG] [ERR] $msg")
        if (t != null) XposedBridge.log(t)
    }

    fun collapseView(view: View, safeMode: Boolean = false) {
        if (!collapsedViewStates.containsKey(view)) {
            val params = view.layoutParams
            val marginParams = params as? ViewGroup.MarginLayoutParams
            collapsedViewStates[view] = ViewOriginalState(
                width = params?.width ?: ViewGroup.LayoutParams.WRAP_CONTENT,
                height = params?.height ?: ViewGroup.LayoutParams.WRAP_CONTENT,
                topMargin = marginParams?.topMargin ?: 0,
                bottomMargin = marginParams?.bottomMargin ?: 0,
                leftMargin = marginParams?.leftMargin ?: 0,
                rightMargin = marginParams?.rightMargin ?: 0,
                paddingTop = view.paddingTop,
                paddingBottom = view.paddingBottom,
                paddingLeft = view.paddingLeft,
                paddingRight = view.paddingRight
            )
        }

        if (view.visibility != View.GONE) view.visibility = View.GONE
        view.isEnabled = false
        view.isClickable = false
        view.isLongClickable = false
        view.isFocusable = false
        view.isFocusableInTouchMode = false
        if (!safeMode) {
            val params = view.layoutParams
            if (params != null && (params.height != 0 || params.width != 0)) {
                params.height = 0
                params.width = 0
                if (params is ViewGroup.MarginLayoutParams) {
                    params.topMargin = 0
                    params.bottomMargin = 0
                    params.leftMargin = 0
                    params.rightMargin = 0
                }
                view.layoutParams = params
            }
            view.setPadding(0, 0, 0, 0)
        }
    }

    fun restoreView(view: View) {
        val state = collapsedViewStates.remove(view) ?: return
        view.visibility = View.VISIBLE
        view.isEnabled = true
        view.isClickable = true
        view.isLongClickable = true
        view.isFocusable = true
        val params = view.layoutParams
        if (params != null) {
            params.height = state.height
            params.width = state.width
            if (params is ViewGroup.MarginLayoutParams) {
                params.topMargin = state.topMargin
                params.bottomMargin = state.bottomMargin
                params.leftMargin = state.leftMargin
                params.rightMargin = state.rightMargin
            }
            view.layoutParams = params
        }
        view.setPadding(state.paddingLeft, state.paddingTop, state.paddingRight, state.paddingBottom)
        view.requestLayout()
        (view.parent as? View)?.requestLayout()
        view.invalidate()
    }

    fun hidePersistently(view: View, delaysMs: LongArray = RETRY_DELAYS_MS) {
        view.visibility = View.GONE
        delaysMs.forEach { delay ->
            view.postDelayed({ view.visibility = View.GONE }, delay)
        }
    }

    fun getCachedResId(context: Context, idName: String): Int {
        return resIdCache.getOrPut(idName) {
            val id = context.resources.getIdentifier(idName, "id", context.packageName)
            if (id == 0) {
                log("⚠ 资源 ID '$idName' 未找到，布局可能已变化")
            }
            id
        }
    }

    fun safeCallStringGetter(obj: Any, methodName: String): String? {
        return try {
            XposedHelpers.callMethod(obj, methodName) as? String
        } catch (_: Exception) {
            null
        }
    }

    fun findFieldRecursively(clazz: Class<*>, fieldName: String): Field? {
        var cur: Class<*>? = clazz
        while (cur != null && cur != Any::class.java) {
            try {
                val field = cur.getDeclaredField(fieldName)
                field.isAccessible = true
                return field
            } catch (_: NoSuchFieldException) {
                cur = cur.superclass
            }
        }
        return null
    }

    fun safeGetObjectField(obj: Any, fieldName: String): Any? {
        return try {
            XposedHelpers.getObjectField(obj, fieldName)
        } catch (_: Throwable) {
            null
        }
    }
}