package com.yilink.jumpclean.hooks

import android.app.Activity
import android.graphics.Color
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.yilink.jumpclean.HookUtils
import com.yilink.jumpclean.config.ConfigManager
import com.yilink.jumpclean.config.JumpConstants
import com.yilink.jumpclean.ui.SettingsDialog
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.util.Collections
import java.util.WeakHashMap

object SettingsEntryHooks {

    private const val TAG_JUMPCLEAN_SETTING_ENTRY = "jumpclean_setting_entry_view"
    private val hookedSettingAdapters = Collections.newSetFromMap(WeakHashMap<Class<*>, Boolean>())
    private val hookedClickListeners = Collections.newSetFromMap(WeakHashMap<Class<*>, Boolean>())

    // 仅精准挂载官方主设置页，排除 SettingChildActivity（避免账户与安全等二级页面重复显示）
    private val TARGET_ACTIVITY_NAMES = setOf(
        "com.vgjump.jump.ui.my.setting.SettingActivity"
    )

    fun hook(lpparam: XC_LoadPackage.LoadPackageParam) {
        hookSettingsEntry(lpparam)
        hookSettingActivityEntry(lpparam)
    }

    /**
     * 官方设置页入口注入：
     * 精准匹配主设置页 SettingActivity。
     * 双保险机制：
     * 1. 首选：通过 RecyclerView Adapter (BRVAH BaseQuickAdapter) 动态注入原生 SettingItem 数据
     * 2. 兜底：若数据注入未生效，直接在 RecyclerView 父容器顶部插入 1:1 官方样式原生条目
     */
    private fun hookSettingActivityEntry(lpparam: XC_LoadPackage.LoadPackageParam) {
        // 1. Hook android.app.Activity.onResume（系统基类，绝对不会触发 NoSuchMethodError）
        try {
            XposedHelpers.findAndHookMethod(
                Activity::class.java,
                "onResume",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val activity = param.thisObject as? Activity ?: return
                        if (isTargetSettingActivity(activity)) {
                            ConfigManager.initAppContext(activity)
                            scheduleInjection(activity, "onResume")
                        }
                    }
                }
            )
            ConfigManager.log("✔ 已注册 Activity.onResume 设置页监听")
        } catch (e: Throwable) {
            ConfigManager.logError("注册 Activity.onResume 监听失败", e)
        }

        // 2. Hook android.app.Activity.onWindowFocusChanged（界面完全渲染就绪的最终保障）
        try {
            XposedHelpers.findAndHookMethod(
                Activity::class.java,
                "onWindowFocusChanged",
                Boolean::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val hasFocus = param.args[0] as? Boolean ?: false
                        if (!hasFocus) return
                        val activity = param.thisObject as? Activity ?: return
                        if (isTargetSettingActivity(activity)) {
                            scheduleInjection(activity, "onWindowFocusChanged")
                        }
                    }
                }
            )
            ConfigManager.log("✔ 已注册 Activity.onWindowFocusChanged 设置页监听")
        } catch (e: Throwable) {
            ConfigManager.logError("注册 Activity.onWindowFocusChanged 监听失败", e)
        }

        // 3. Hook RecyclerView.setAdapter（当 Adapter 绑定到 View 时即时挂载）
        try {
            val rvClass = XposedHelpers.findClassIfExists("androidx.recyclerview.widget.RecyclerView", lpparam.classLoader)
            if (rvClass != null) {
                XposedBridge.hookAllMethods(rvClass, "setAdapter", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val rv = param.thisObject as? View ?: return
                        val activity = rv.context as? Activity ?: return
                        if (isTargetSettingActivity(activity)) {
                            val adapter = param.args.getOrNull(0) ?: return
                            ConfigManager.log("RecyclerView.setAdapter 触发: ${activity.javaClass.simpleName}, adapter=${adapter.javaClass.name}")
                            hookAdapterBindForSettingsEntry(adapter.javaClass)
                            rv.post {
                                scheduleInjection(activity, "setAdapter")
                            }
                        }
                    }
                })
                ConfigManager.log("✔ 已注册 RecyclerView.setAdapter 监听")
            }
        } catch (e: Throwable) {
            ConfigManager.logError("注册 RecyclerView.setAdapter 监听失败", e)
        }

        // 4. 对 SettingActivity 的 initData 进行安全拦截尝试
        for (className in TARGET_ACTIVITY_NAMES) {
            try {
                val activityClass = XposedHelpers.findClassIfExists(className, lpparam.classLoader) ?: continue
                XposedBridge.hookAllMethods(activityClass, "initData", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val activity = param.thisObject as? Activity ?: return
                        ConfigManager.initAppContext(activity)
                        scheduleInjection(activity, "initData")
                    }
                })
            } catch (e: Throwable) {
                ConfigManager.log("设置页 $className initData hook 尝试跳过: ${e.message}")
            }
        }
    }

    private fun isTargetSettingActivity(activity: Activity): Boolean {
        val name = activity.javaClass.name
        return name in TARGET_ACTIVITY_NAMES ||
                (name.endsWith(".SettingActivity") && !name.contains("Child"))
    }

    /**
     * 多阶段调度注入，防异步数据加载延迟覆盖
     */
    private fun scheduleInjection(activity: Activity, triggerSource: String) {
        activity.runOnUiThread {
            tryInject(activity, "$triggerSource[immediate]")
        }
        activity.window?.decorView?.let { decor ->
            decor.post {
                tryInject(activity, "$triggerSource[post]")
            }
            decor.postDelayed({
                tryInject(activity, "$triggerSource[delayed150]")
            }, 150L)
            decor.postDelayed({
                tryInject(activity, "$triggerSource[delayed400]")
            }, 400L)
        }
    }

    /**
     * 执行双保险注入流程
     */
    @Synchronized
    private fun tryInject(activity: Activity, source: String) {
        if (activity.isFinishing || activity.isDestroyed) return

        // 检查是否已经存在（数据层或视图层任一存在均不重复添加）
        if (hasSettingItemInData(activity)) {
            return
        }
        val decor = activity.window?.decorView as? ViewGroup
        if (decor?.findViewWithTag<View>(TAG_JUMPCLEAN_SETTING_ENTRY) != null) {
            return
        }

        // 优先方案 1：向 Adapter 数据列表插入原生 SettingItem
        val injectedData = injectSettingItemIntoAdapter(activity)
        if (injectedData) {
            ConfigManager.log("✔ [$source] 已通过 Adapter 数据层成功插入 JumpClean 条目")
            return
        }

        // 兜底方案 2：直接在 RecyclerView 上方插入 1:1 原生风格条目 View
        val injectedView = injectSettingEntryView(activity)
        if (injectedView) {
            ConfigManager.log("✔ [$source] 已通过 View 容器层成功插入 JumpClean 条目")
        }
    }

    private fun findRecyclerView(root: View?): View? {
        if (root == null) return null
        if (root.javaClass.name.contains("RecyclerView")) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                val child = root.getChildAt(i)
                val found = findRecyclerView(child)
                if (found != null) return found
            }
        }
        return null
    }

    private fun getSettingRecyclerView(activity: Activity): View? {
        val rvId = HookUtils.getCachedResId(activity, "rvSetting")
        val rvById = if (rvId != 0) activity.findViewById<View>(rvId) else null
        return rvById ?: findRecyclerView(activity.window?.decorView)
    }

    private fun hasSettingItemInData(activity: Activity): Boolean {
        val rv = getSettingRecyclerView(activity) ?: return false
        val adapter = try { XposedHelpers.callMethod(rv, "getAdapter") } catch (_: Throwable) { null } ?: return false
        val dataList = getAdapterDataList(adapter) ?: return false
        return dataList.any { item ->
            HookUtils.safeCallStringGetter(item, "getTitle") == JumpConstants.SETTING_ITEM_TITLE
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun getAdapterDataList(adapter: Any): MutableList<Any>? {
        return try {
            XposedHelpers.callMethod(adapter, "getData") as? MutableList<Any>
        } catch (_: Throwable) { null }
            ?: (try { XposedHelpers.getObjectField(adapter, "mData") as? MutableList<Any> } catch (_: Throwable) { null })
            ?: (try { XposedHelpers.getObjectField(adapter, "f") as? MutableList<Any> } catch (_: Throwable) { null })
    }

    private fun createSettingItem(classLoader: ClassLoader): Any? {
        val cls = XposedHelpers.findClassIfExists("com.vgjump.jump.bean.my.SettingItem", classLoader) ?: return null

        // 1. 优先使用已在 Jump 3.30.27 与 3.30.29 全面对齐验证的标准 6 参数主构造器
        // (drawRes: Integer?, title: String, desc: String, moreContent: String, extraUri: String, extraStr: String)
        try {
            val ctor = cls.getConstructor(
                java.lang.Integer::class.java,
                String::class.java,
                String::class.java,
                String::class.java,
                String::class.java,
                String::class.java
            )
            return ctor.newInstance(
                null,
                JumpConstants.SETTING_ITEM_TITLE,
                "",
                "",
                "1",
                null
            )
        } catch (e: Throwable) {
            ConfigManager.log("标准 6 参数 SettingItem 构造器未匹配: ${e.message}，尝试备用反射")
        }

        // 2. 动态扫描所有构造器兜底
        for (constructor in cls.constructors) {
            val paramTypes = constructor.parameterTypes
            try {
                val args = Array<Any?>(paramTypes.size) { i ->
                    when {
                        paramTypes[i] == String::class.java -> if (i == 1) JumpConstants.SETTING_ITEM_TITLE else ""
                        paramTypes[i] == java.lang.Integer::class.java -> null
                        paramTypes[i] == Int::class.javaPrimitiveType -> 0
                        paramTypes[i] == java.lang.Boolean::class.java -> false
                        paramTypes[i] == Boolean::class.javaPrimitiveType -> false
                        else -> null
                    }
                }
                return constructor.newInstance(*args)
            } catch (_: Throwable) {}
        }
        return null
    }

    private fun injectSettingItemIntoAdapter(activity: Activity): Boolean {
        val rv = getSettingRecyclerView(activity) ?: return false
        val adapter = try { XposedHelpers.callMethod(rv, "getAdapter") } catch (_: Throwable) { null } ?: return false
        val dataList = getAdapterDataList(adapter) ?: return false

        hookAdapterBindForSettingsEntry(adapter.javaClass)

        val alreadyExists = dataList.any { item ->
            HookUtils.safeCallStringGetter(item, "getTitle") == JumpConstants.SETTING_ITEM_TITLE
        }
        if (alreadyExists) return true

        val customItem = createSettingItem(activity.classLoader) ?: return false

        var added = false
        try {
            XposedHelpers.callMethod(adapter, "addData", 0, customItem)
            added = true
        } catch (_: Throwable) {}

        if (!added) {
            try {
                dataList.add(0, customItem)
                try {
                    XposedHelpers.callMethod(adapter, "notifyItemInserted", 0)
                } catch (_: Throwable) {
                    XposedHelpers.callMethod(adapter, "notifyDataSetChanged")
                }
                added = true
            } catch (e: Throwable) {
                ConfigManager.logError("向 dataList 插入 customItem 失败", e)
            }
        }

        return added
    }

    /**
     * 解决子类未重写 onBindViewHolder 导致的 Hook 穿透问题：
     * 递归遍历 adapterClass 及其所有父类（直到 RecyclerView.Adapter），
     * 确保 100% 挂载到实际实现 onBindViewHolder 的基类（如 BaseQuickAdapter）。
     * 同时 Hook setOnItemClickListener，双保险拦截点击分发。
     */
    private fun hookAdapterBindForSettingsEntry(adapterClass: Class<*>) {
        synchronized(hookedSettingAdapters) {
            if (!hookedSettingAdapters.add(adapterClass)) return
        }

        // 1. 递归沿继承链向上查找并 Hook 所有层级的 onBindViewHolder
        var curClass: Class<*>? = adapterClass
        var hookedBindCount = 0
        while (curClass != null && curClass != Any::class.java) {
            for (method in curClass.declaredMethods) {
                if (method.name == "onBindViewHolder" && method.parameterTypes.size >= 2) {
                    try {
                        XposedBridge.hookMethod(method, object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam) {
                                try {
                                    val holder = param.args.getOrNull(0) ?: return
                                    val itemView = (XposedHelpers.getObjectField(holder, "itemView") as? View) ?: return
                                    val position = param.args.getOrNull(1) as? Int ?: return
                                    val adapter = param.thisObject ?: return
                                    val dataList = getAdapterDataList(adapter) ?: return
                                    val item = dataList.getOrNull(position) ?: return
                                    val title = HookUtils.safeCallStringGetter(item, "getTitle")

                                    if (title == JumpConstants.SETTING_ITEM_TITLE) {
                                        itemView.isClickable = true
                                        itemView.setOnClickListener {
                                            val ctx = (itemView.context as? Activity) ?: return@setOnClickListener
                                            SettingsDialog.show(ctx)
                                        }
                                    }
                                } catch (_: Throwable) {}
                            }
                        })
                        hookedBindCount++
                    } catch (e: Throwable) {
                        ConfigManager.log("Hook ${curClass.name}#onBindViewHolder 失败: ${e.message}")
                    }
                }
            }
            curClass = curClass.superclass
        }
        ConfigManager.log("✔ 已为 ${adapterClass.name} 及其继承链挂载 $hookedBindCount 个 onBindViewHolder 监听")

        // 2. 递归 Hook setOnItemClickListener（BRVAH 点击事件分发拦截）
        curClass = adapterClass
        while (curClass != null && curClass != Any::class.java) {
            for (method in curClass.declaredMethods) {
                if (method.name == "setOnItemClickListener") {
                    try {
                        XposedBridge.hookMethod(method, object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                val listener = param.args.getOrNull(0) ?: return
                                hookItemClickListener(listener.javaClass)
                            }
                        })
                    } catch (_: Throwable) {}
                }
            }
            curClass = curClass.superclass
        }
    }

    /**
     * 拦截 BRVAH 的 OnItemClickListener 回调
     */
    private fun hookItemClickListener(listenerClass: Class<*>) {
        synchronized(hookedClickListeners) {
            if (!hookedClickListeners.add(listenerClass)) return
        }

        var cur: Class<*>? = listenerClass
        while (cur != null && cur != Any::class.java) {
            for (method in cur.declaredMethods) {
                val params = method.parameterTypes
                if (params.size == 3 && (params[2] == Int::class.javaPrimitiveType || params[2] == java.lang.Integer::class.java)) {
                    try {
                        XposedBridge.hookMethod(method, object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                try {
                                    val adapter = param.args.getOrNull(0) ?: return
                                    val position = (param.args.getOrNull(2) as? Int) ?: return
                                    val dataList = getAdapterDataList(adapter) ?: return
                                    val item = dataList.getOrNull(position) ?: return
                                    val title = HookUtils.safeCallStringGetter(item, "getTitle")

                                    if (title == JumpConstants.SETTING_ITEM_TITLE) {
                                        param.result = null // 阻止官方逻辑处理此位置
                                        val view = param.args.getOrNull(1) as? View
                                        val activity = (view?.context as? Activity) ?: return
                                        SettingsDialog.show(activity)
                                    }
                                } catch (_: Throwable) {}
                            }
                        })
                        ConfigManager.log("✔ 已拦截 BRVAH ItemClickListener: ${listenerClass.name}#${method.name}")
                    } catch (_: Throwable) {}
                }
            }
            cur = cur.superclass
        }
    }

    /**
     * View 层兜底注入：在官方 RecyclerView 同级的最顶部插入一个 1:1 原生风格条目
     */
    private fun injectSettingEntryView(activity: Activity): Boolean {
        return try {
            val decorView = activity.window?.decorView as? ViewGroup ?: return false
            if (decorView.findViewWithTag<View>(TAG_JUMPCLEAN_SETTING_ENTRY) != null) {
                return true
            }

            val rv = getSettingRecyclerView(activity) ?: return false
            val parent = (rv.parent as? ViewGroup) ?: return false
            val index = parent.indexOfChild(rv)
            if (index < 0) return false

            val dp = { value: Float -> (value * activity.resources.displayMetrics.density).toInt() }
            val isDark = (activity.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                    android.content.res.Configuration.UI_MODE_NIGHT_YES

            val primaryTextColor = if (isDark) Color.parseColor("#F5F5F7") else Color.parseColor("#1D1D1F")
            val secondaryTextColor = if (isDark) Color.parseColor("#8E8E93") else Color.parseColor("#86868B")

            val itemView = LinearLayout(activity).apply {
                tag = TAG_JUMPCLEAN_SETTING_ENTRY
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(15.5f), 0, dp(15.5f), 0)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(45f)
                )

                val typedValue = TypedValue()
                if (activity.theme.resolveAttribute(android.R.attr.selectableItemBackground, typedValue, true)) {
                    setBackgroundResource(typedValue.resourceId)
                }

                isClickable = true
                isFocusable = true
                setOnClickListener {
                    SettingsDialog.show(activity)
                }
            }

            val titleView = TextView(activity).apply {
                text = JumpConstants.SETTING_ITEM_TITLE
                textSize = 15f
                setTextColor(primaryTextColor)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }

            val arrowView = TextView(activity).apply {
                text = "›"
                textSize = 20f
                setTextColor(secondaryTextColor)
                gravity = Gravity.CENTER
            }

            itemView.addView(titleView)
            itemView.addView(arrowView)

            parent.addView(itemView, index)
            ConfigManager.log("✔ 已在官方设置页成功注入 JumpClean 视图入口 (父容器: ${parent.javaClass.simpleName}, Index: $index)")
            true
        } catch (e: Exception) {
            ConfigManager.logError("注入官方设置页视图入口失败", e)
            false
        }
    }

    /**
     * 快捷入口：长按首页底栏「我的」Tab 直接弹出设置
     */
    private fun hookSettingsEntry(lpparam: XC_LoadPackage.LoadPackageParam) {
        val mainActivityClass = XposedHelpers.findClassIfExists("com.vgjump.jump.ui.main.MainActivity", lpparam.classLoader) ?: return
        try {
            XposedHelpers.findAndHookMethod(mainActivityClass, "onCreate",
                android.os.Bundle::class.java, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val activity = param.thisObject as Activity
                            ConfigManager.initAppContext(activity)
                            HookUtils.getCachedResId(activity, "myTab").takeIf { it != 0 }?.let { id ->
                                activity.findViewById<View>(id)?.setOnLongClickListener {
                                    SettingsDialog.show(activity)
                                    true
                                }
                            }
                        } catch (e: Exception) {
                            ConfigManager.logError("设置入口注入异常", e)
                        }
                    }
                }
            )
            ConfigManager.log("✔ 快捷入口（长按「我的」Tab）已安装")
        } catch (e: Exception) {
            ConfigManager.logError("✘ 快捷入口 Hook 失败", e)
        }
    }
}
