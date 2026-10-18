package com.yilink.jumpclean.config

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.yilink.jumpclean.HookUtils
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

sealed interface KeywordMatcher {
    fun matches(text: String): Boolean
}

class PlainKeywordMatcher(private val word: String) : KeywordMatcher {
    override fun matches(text: String): Boolean = text.contains(word, ignoreCase = true)
}

class RegexKeywordMatcher(private val regex: Regex) : KeywordMatcher {
    override fun matches(text: String): Boolean = regex.containsMatchIn(text)
}

object ConfigManager {

    var targetClassLoader: ClassLoader? = null
        private set

    private var appContextRef: WeakReference<Context>? = null

    // 内存安全拦截计数器
    private val blockedPromoCounter = AtomicInteger(-1)

    // ── 热路径配置缓存 ──
    // hook 回调（View 显隐/列表绑定）每帧可能触发数十次，直接读 SP 有锁开销，
    // 兜底路径还会读 XML 文件。这里全部走内存缓存，靠 SP 变更监听失效。
    private val boolPrefCache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    private val intPrefCache = java.util.concurrent.ConcurrentHashMap<String, Int>()

    // SP 持有 listener 弱引用，必须自己保持强引用，否则 GC 后缓存永不失效
    private val prefChangeListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null) {
            boolPrefCache.clear(); intPrefCache.clear()
        } else {
            boolPrefCache.remove(key); intPrefCache.remove(key)
        }
    }
    @Volatile private var prefListenerHooked = false

    // XML 兜底路径的 TTL 缓存（仅进程极早期 Context 不可用时使用）
    private val fallbackBoolCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Boolean>>()
    private const val FALLBACK_TTL_MS = 2000L

    private fun prefsOf(ctx: Context) =
        ctx.getSharedPreferences(JumpConstants.PREFS_NAME, Context.MODE_PRIVATE).also { sp ->
            if (!prefListenerHooked) {
                synchronized(this) {
                    if (!prefListenerHooked) {
                        sp.registerOnSharedPreferenceChangeListener(prefChangeListener)
                        prefListenerHooked = true
                    }
                }
            }
        }

    @Volatile
    private var cachedMatchers: List<KeywordMatcher>? = null

    // SP 异步防抖 Handler
    private val debounceHandler by lazy { Handler(Looper.getMainLooper()) }
    private val saveCounterRunnable = Runnable {
        val app = getValidAppContext() ?: return@Runnable
        val total = blockedPromoCounter.get()
        if (total >= 0) {
            app.getSharedPreferences(JumpConstants.PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putInt(JumpConstants.KEY_BLOCKED_OFFICIAL_PROMO_COUNT, total)
                .apply()
        }
    }

    fun init(lpparam: XC_LoadPackage.LoadPackageParam) {
        targetClassLoader = lpparam.classLoader
    }

    fun initAppContext(context: Context) {
        if (appContextRef?.get() == null) {
            val app = context.applicationContext ?: context
            appContextRef = WeakReference(app)
            HookUtils.onContextReady(app)
        }
    }

    fun getValidAppContext(): Context? {
        appContextRef?.get()?.let { return it }
        return try {
            val activityThread = XposedHelpers.findClass("android.app.ActivityThread", targetClassLoader)
            (XposedHelpers.callStaticMethod(activityThread, "currentApplication") as? Context)?.also {
                initAppContext(it)
            }
        } catch (_: Throwable) {
            null
        }
    }

    fun getDefaultFeatureValue(key: String): Boolean {
        return when (key) {
            JumpConstants.KEY_SKIP_SPLASH,
            JumpConstants.KEY_HIDE_VOUCHER_POPUP,
            JumpConstants.KEY_HIDE_BANNER,
            JumpConstants.KEY_HIDE_TOPIC_LIST,
            JumpConstants.KEY_HIDE_HOT_DISCUSS,
            JumpConstants.KEY_HIDE_DISCOVER_TOP_AD,
            JumpConstants.KEY_HIDE_DISCOVER_BANNER,
            JumpConstants.KEY_HIDE_POST_AD,
            JumpConstants.KEY_HIDE_WEB_TAB,
            JumpConstants.KEY_HIDE_LOTTERY_TAB,
            JumpConstants.KEY_EXP_BLOCK_OFFICIAL_PROMO_POST,
            JumpConstants.KEY_HIDE_GAME_MEMBER_GUIDE,
            JumpConstants.KEY_HIDE_GAME_FIND_AD,
            JumpConstants.KEY_HIDE_GAME_DYNAMIC_BUBBLE,
            JumpConstants.KEY_HIDE_GAME_PRICE_ADS,
            JumpConstants.KEY_ENABLE_LIQUID_GLASS_NAV -> true

            JumpConstants.KEY_HIDE_PUBLISH_TOPIC,
            JumpConstants.KEY_HIDE_PHOTO_WALL,
            JumpConstants.KEY_HIDE_MEMBER_CARD,
            JumpConstants.KEY_HIDE_MY_ORDER,
            JumpConstants.KEY_HIDE_MSG_PUSH_GUIDE,
            JumpConstants.KEY_HIDE_WIDGET_VIP_TAG,
            JumpConstants.KEY_ENABLE_COPY,
            JumpConstants.KEY_HIDE_CONTENT_MEMBER_MASK,
            JumpConstants.KEY_RESTORE_POST_YEAR,
            JumpConstants.KEY_ENABLE_DEBUG_LOG,
            JumpConstants.KEY_ENABLE_KEYWORD_BLOCK,
            JumpConstants.KEY_HIDE_GAME_BOTTOM_TRIAL_AD,
            JumpConstants.KEY_HIDE_GAME_SECOND_HAND,
            JumpConstants.KEY_BLOCK_NOTIFICATION_AD,
            JumpConstants.KEY_SHOW_BEAR_TERMINAL -> false

            else -> false
        }
    }

    fun isFeatureEnabled(context: Context, key: String): Boolean {
        boolPrefCache[key]?.let { return it }
        return try {
            val defaultVal = getDefaultFeatureValue(key)
            val value = prefsOf(context).getBoolean(key, defaultVal)
            boolPrefCache[key] = value
            value
        } catch (e: Exception) {
            logError("读取设置失败: $key", e)
            getDefaultFeatureValue(key)
        }
    }

    fun getIntPref(key: String, defaultVal: Int): Int {
        intPrefCache[key]?.let { return it }
        val ctx = getValidAppContext() ?: return defaultVal
        return try {
            val value = prefsOf(ctx).getInt(key, defaultVal)
            intPrefCache[key] = value
            value
        } catch (_: Throwable) {
            defaultVal
        }
    }

    fun setIntPref(key: String, value: Int) {
        intPrefCache[key] = value
        val ctx = getValidAppContext() ?: return
        try {
            prefsOf(ctx).edit().putInt(key, value).apply()
        } catch (_: Throwable) {}
    }

    fun getBooleanPref(key: String, defaultVal: Boolean): Boolean {
        boolPrefCache[key]?.let { return it }
        val ctx = getValidAppContext() ?: return defaultVal
        return try {
            val value = prefsOf(ctx).getBoolean(key, defaultVal)
            boolPrefCache[key] = value
            value
        } catch (_: Throwable) {
            defaultVal
        }
    }

    fun setBooleanPref(key: String, value: Boolean) {
        boolPrefCache[key] = value
        val ctx = getValidAppContext() ?: return
        try {
            prefsOf(ctx).edit().putBoolean(key, value).apply()
        } catch (_: Throwable) {}
    }

    fun isFeatureEnabledSafe(classLoader: ClassLoader? = null, key: String): Boolean {
        val app = getValidAppContext()
        if (app != null) {
            return isFeatureEnabled(app, key)
        }

        val cl = classLoader ?: targetClassLoader ?: ConfigManager::class.java.classLoader

        try {
            val activityThread = XposedHelpers.findClass("android.app.ActivityThread", cl)
            val currentApp = XposedHelpers.callStaticMethod(activityThread, "currentApplication") as? Context
            if (currentApp != null) {
                initAppContext(currentApp)
                return isFeatureEnabled(currentApp, key)
            }
        } catch (_: Exception) {}

        // Context 就绪前的极早期才走到这里；加 2 秒 TTL 防止主线程反复读文件
        fallbackBoolCache[key]?.let { (ts, v) ->
            if (System.currentTimeMillis() - ts < FALLBACK_TTL_MS) return v
        }
        val result = try {
            val candidatePaths = listOf(
                "/data/user/0/${JumpConstants.TARGET_PACKAGE}/shared_prefs/${JumpConstants.PREFS_NAME}.xml",
                "/data/data/${JumpConstants.TARGET_PACKAGE}/shared_prefs/${JumpConstants.PREFS_NAME}.xml"
            )
            val spFile = candidatePaths.map { File(it) }.firstOrNull { it.exists() }
            if (spFile != null) {
                val content = spFile.readText()
                if (content.contains("""name="$key" value="true"""") || content.contains("""name="$key">true<""")) {
                    true
                } else if (content.contains("""name="$key" value="false"""") || content.contains("""name="$key">false<""")) {
                    false
                } else {
                    getDefaultFeatureValue(key)
                }
            } else {
                getDefaultFeatureValue(key)
            }
        } catch (_: Exception) {
            getDefaultFeatureValue(key)
        }
        fallbackBoolCache[key] = System.currentTimeMillis() to result
        return result
    }

    private val lastErrorToastTime = AtomicLong(0L)

    fun log(msg: String) {
        if (isFeatureEnabledSafe(targetClassLoader, JumpConstants.KEY_ENABLE_DEBUG_LOG)) {
            HookUtils.log(msg)
        }
    }

    fun logError(msg: String, e: Throwable? = null) {
        HookUtils.err(msg, e)

        // 仅在开启调试日志时，向开发者提供 Toast 异常提醒（3秒防抖防刷屏）
        if (isFeatureEnabledSafe(targetClassLoader, JumpConstants.KEY_ENABLE_DEBUG_LOG)) {
            val now = System.currentTimeMillis()
            if (now - lastErrorToastTime.get() > 3000L) {
                lastErrorToastTime.set(now)
                getValidAppContext()?.let { ctx ->
                    debounceHandler.post {
                        try {
                            android.widget.Toast.makeText(
                                ctx,
                                "[JumpClean] 发现 Hook 异常: $msg",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        } catch (_: Throwable) {}
                    }
                }
            }
        }
    }

    fun recordOfficialPromoBlocked() {
        try {
            val app = getValidAppContext() ?: return

            val sp = app.getSharedPreferences(JumpConstants.PREFS_NAME, Context.MODE_PRIVATE)
            if (blockedPromoCounter.get() == -1) {
                val currentSaved = sp.getInt(JumpConstants.KEY_BLOCKED_OFFICIAL_PROMO_COUNT, 0)
                blockedPromoCounter.set(currentSaved)
            }

            blockedPromoCounter.incrementAndGet()

            debounceHandler.removeCallbacks(saveCounterRunnable)
            debounceHandler.postDelayed(saveCounterRunnable, 1000L)
        } catch (e: Throwable) {
            logError("记录拦截计数异常", e)
        }
    }

    fun getBlockedMatchers(context: Context): List<KeywordMatcher> {
        cachedMatchers?.let { return it }
        synchronized(this) {
            cachedMatchers?.let { return it }
            val raw = context.getSharedPreferences(JumpConstants.PREFS_NAME, Context.MODE_PRIVATE)
                .getString(JumpConstants.KEY_BLOCKED_KEYWORDS, "") ?: ""
            val list = raw.split(",", "，", "\n")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .mapNotNull { word ->
                    if (word.startsWith("regex:")) {
                        try {
                            RegexKeywordMatcher(Regex(word.removePrefix("regex:")))
                        } catch (_: Exception) {
                            null
                        }
                    } else {
                        PlainKeywordMatcher(word)
                    }
                }
            cachedMatchers = list
            return list
        }
    }

    fun invalidateKeywordMatchers() {
        synchronized(this) {
            cachedMatchers = null
        }
    }
}
