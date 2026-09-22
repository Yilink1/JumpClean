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
            appContextRef = WeakReference(context.applicationContext)
        }
    }

    fun getValidAppContext(): Context? {
        appContextRef?.get()?.let { return it }
        return try {
            val activityThread = XposedHelpers.findClass("android.app.ActivityThread", targetClassLoader)
            (XposedHelpers.callStaticMethod(activityThread, "currentApplication") as? Context)?.also {
                appContextRef = WeakReference(it)
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
            JumpConstants.KEY_HIDE_LOTTERY_TAB -> true

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
            JumpConstants.KEY_EXP_BLOCK_OFFICIAL_PROMO_POST,
            JumpConstants.KEY_ENABLE_KEYWORD_BLOCK -> false

            else -> false
        }
    }

    fun isFeatureEnabled(context: Context, key: String): Boolean {
        return try {
            val defaultVal = getDefaultFeatureValue(key)
            context.getSharedPreferences(JumpConstants.PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(key, defaultVal)
        } catch (e: Exception) {
            logError("读取设置失败: $key", e)
            getDefaultFeatureValue(key)
        }
    }

    fun isFeatureEnabledSafe(classLoader: ClassLoader? = null, key: String): Boolean {
        val cl = classLoader ?: targetClassLoader ?: ConfigManager::class.java.classLoader

        try {
            val activityThread = XposedHelpers.findClass("android.app.ActivityThread", cl)
            val currentApp = XposedHelpers.callStaticMethod(activityThread, "currentApplication") as? Context
            if (currentApp != null) {
                return isFeatureEnabled(currentApp, key)
            }
        } catch (_: Exception) {}

        return try {
            val candidatePaths = listOf(
                "/data/user/0/com.vgjump.jump/shared_prefs/${JumpConstants.PREFS_NAME}.xml",
                "/data/data/com.vgjump.jump/shared_prefs/${JumpConstants.PREFS_NAME}.xml"
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
    }

    fun log(msg: String) {
        if (isFeatureEnabledSafe(targetClassLoader, JumpConstants.KEY_ENABLE_DEBUG_LOG)) {
            HookUtils.log(msg)
        }
    }

    fun logError(msg: String, e: Throwable? = null) {
        if (isFeatureEnabledSafe(targetClassLoader, JumpConstants.KEY_ENABLE_DEBUG_LOG)) {
            HookUtils.err(msg, e)
        }
    }

    fun recordOfficialPromoBlocked(adId: String, content: String) {
        try {
            log("✔ [数据层剔除] 物理移除 Jump小酱推广帖子: adId=$adId, content=$content")

            val app = getValidAppContext()
            if (app == null) {
                logError("✘ 计数失败：AppContext 为空，无法写入 SharedPreferences")
                return
            }

            val sp = app.getSharedPreferences(JumpConstants.PREFS_NAME, Context.MODE_PRIVATE)
            if (blockedPromoCounter.get() == -1) {
                val currentSaved = sp.getInt(JumpConstants.KEY_BLOCKED_OFFICIAL_PROMO_COUNT, 0)
                blockedPromoCounter.set(currentSaved)
                log("[计数器初始化] 从 SP 读取到初始值: $currentSaved")
            }

            val newCount = blockedPromoCounter.incrementAndGet()
            log("[计数器递增] 内存当前计数值: $newCount")

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
