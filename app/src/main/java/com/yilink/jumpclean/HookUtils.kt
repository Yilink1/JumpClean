package com.yilink.jumpclean

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.os.Handler
import android.os.Looper
import com.yilink.jumpclean.config.ConfigManager
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.io.File
import java.lang.reflect.Field
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

object HookUtils {
    private const val TAG = "JumpClean"
    private const val MAX_LOG_HISTORY = 200
    private const val LOG_FILE_NAME = "jumpclean_diagnostic.log"

    private const val PREFS_DIAG_LOGS = "jumpclean_diag_logs"
    private const val KEY_DIAG_CONTENT = "diag_logs_content"

    private val logHistory = ConcurrentLinkedDeque<String>()
    private val isLoadedFromDisk = AtomicBoolean(false)

    private val logDiskExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "JumpClean-LogDisk").apply { isDaemon = true }
    }
    private val flushHandler by lazy { Handler(Looper.getMainLooper()) }
    private val flushRunnable = Runnable {
        saveLogs(sync = false)
    }

    private val resIdCache = ConcurrentHashMap<String, Int>()
    private val RETRY_DELAYS_MS = longArrayOf(500L, 1500L, 3000L)

    private fun getCurrentTimeString(): String {
        return try {
            SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        } catch (_: Throwable) {
            ""
        }
    }

    fun onContextReady(context: Context) {
        loadHistoricalLogsIfNeeded(context)
    }

    private fun loadHistoricalLogsIfNeeded(context: Context? = null) {
        if (isLoadedFromDisk.get()) return
        val ctx = context?.applicationContext ?: ConfigManager.getValidAppContext() ?: return
        if (!isLoadedFromDisk.compareAndSet(false, true)) return
        try {
            val sp = ctx.getSharedPreferences(PREFS_DIAG_LOGS, Context.MODE_PRIVATE)
            val raw = sp.getString(KEY_DIAG_CONTENT, null)
            val historicalLines = if (!raw.isNullOrBlank()) {
                raw.split("\n").filter { it.isNotBlank() }
            } else {
                val file = File(ctx.filesDir, LOG_FILE_NAME)
                if (file.exists()) file.readLines().filter { it.isNotBlank() } else emptyList()
            }

            if (historicalLines.isNotEmpty()) {
                val currentMemory = logHistory.toList()
                logHistory.clear()
                val merged = (historicalLines + currentMemory).takeLast(MAX_LOG_HISTORY)
                merged.forEach { logHistory.addLast(it) }
            }
        } catch (_: Throwable) {
            isLoadedFromDisk.set(false)
        }
    }

    private fun saveLogs(context: Context? = null, sync: Boolean = false) {
        val ctx = context?.applicationContext ?: ConfigManager.getValidAppContext() ?: return
        val content = logHistory.joinToString("\n")
        try {
            val sp = ctx.getSharedPreferences(PREFS_DIAG_LOGS, Context.MODE_PRIVATE)
            if (sync) {
                sp.edit().putString(KEY_DIAG_CONTENT, content).commit()
            } else {
                sp.edit().putString(KEY_DIAG_CONTENT, content).apply()
            }
        } catch (_: Throwable) {}

        logDiskExecutor.execute {
            try {
                val file = File(ctx.filesDir, LOG_FILE_NAME)
                file.parentFile?.mkdirs()
                file.writeText(content)
            } catch (_: Throwable) {}
        }
    }

    private val REGEX_LOG_COUNT = Regex("""^(.*?) \(x(\d+)\)$""")

    private fun parseLogLine(line: String): Triple<String, String, Int> {
        val ts = if (line.startsWith("[") && line.length >= 10 && line[9] == ']') line.substring(0, 10) else ""
        val body = if (ts.isNotEmpty()) line.substring(10).trimStart() else line
        val match = REGEX_LOG_COUNT.matchEntire(body)
        val base = match?.groupValues?.get(1) ?: body
        val count = match?.groupValues?.get(2)?.toIntOrNull() ?: 1
        return Triple(ts, base, count)
    }

    private fun appendHistory(line: String, immediateFlush: Boolean = false) {
        loadHistoricalLogsIfNeeded()
        synchronized(logHistory) {
            val (currentTimestamp, currentBody, _) = parseLogLine(line)

            // 智能折叠：检查最近 4 条日志内是否存在相同的拦截主体（支持交替多端点刷新的合并折叠）
            val list = logHistory.toList()
            val lookbackLimit = minOf(4, list.size)
            var matchIndex = -1
            var matchBase = ""
            var matchCount = 1

            for (i in (list.size - 1) downTo (list.size - lookbackLimit)) {
                val item = list[i]
                val (_, itemBase, itemCount) = parseLogLine(item)
                if (itemBase == currentBody) {
                    matchIndex = i
                    matchBase = itemBase
                    matchCount = itemCount
                    break
                }
            }

            if (matchIndex != -1) {
                val newCount = matchCount + 1
                val updatedLine = if (currentTimestamp.isNotEmpty()) "$currentTimestamp $matchBase (x$newCount)" else "$matchBase (x$newCount)"
                logHistory.clear()
                list.forEachIndexed { idx, oldLine ->
                    if (idx == matchIndex) {
                        logHistory.addLast(updatedLine)
                    } else {
                        logHistory.addLast(oldLine)
                    }
                }
            } else {
                logHistory.addLast(line)
                while (logHistory.size > MAX_LOG_HISTORY) {
                    logHistory.pollFirst()
                }
            }
        }
        if (immediateFlush) {
            saveLogs(sync = true)
        } else {
            flushHandler.removeCallbacks(flushRunnable)
            flushHandler.postDelayed(flushRunnable, 300L)
        }
    }

    fun getRecentLogs(context: Context? = null): List<String> {
        loadHistoricalLogsIfNeeded(context)
        return logHistory.toList()
    }

    fun clearRecentLogs(context: Context? = null) {
        logHistory.clear()
        flushHandler.removeCallbacks(flushRunnable)
        val ctx = context?.applicationContext ?: ConfigManager.getValidAppContext()
        if (ctx != null) {
            try {
                ctx.getSharedPreferences(PREFS_DIAG_LOGS, Context.MODE_PRIVATE)
                    .edit()
                    .clear()
                    .commit()
            } catch (_: Throwable) {}
            logDiskExecutor.execute {
                try {
                    val file = File(ctx.filesDir, LOG_FILE_NAME)
                    if (file.exists()) file.delete()
                } catch (_: Throwable) {}
            }
        }
    }

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
        val time = getCurrentTimeString()
        val formatted = if (time.isNotEmpty()) "[$time] $msg" else msg
        appendHistory(formatted)
        XposedBridge.log("[$TAG] $msg")
    }

    fun err(msg: String, t: Throwable? = null) {
        val time = getCurrentTimeString()
        val errorDetail = if (t != null) {
            val cause = t.cause?.let { " (Caused by ${it.javaClass.simpleName}: ${it.message})" } ?: ""
            " (${t.javaClass.simpleName}: ${t.message})$cause"
        } else ""
        val formatted = if (time.isNotEmpty()) "[$time] [ERR] $msg$errorDetail" else "[ERR] $msg$errorDetail"
        appendHistory(formatted, immediateFlush = true)
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

    fun getCachedResId(context: Context, idName: String, warnIfNotFound: Boolean = false): Int {
        return resIdCache.getOrPut(idName) {
            val id = context.resources.getIdentifier(idName, "id", context.packageName)
            if (id == 0 && warnIfNotFound) {
                log("⚠ 资源 ID '$idName' 未找到，布局可能已变化")
            }
            id
        }
    }

    fun safeCallStringGetter(obj: Any, methodName: String): String? {
        return try {
            XposedHelpers.callMethod(obj, methodName) as? String
        } catch (_: Throwable) {
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