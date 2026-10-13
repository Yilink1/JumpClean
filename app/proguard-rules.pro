# ==========================================
# 🛡️ 1. 保住 Xposed / LSPosed 入口类（最关键）
# ==========================================
# 替换成你真实的包名，一定要加上，不然 LSPosed 连你的模块都找不到
-keep class com.yilink.jumpclean.** { *; }

# 保留所有 Xposed 接口
-keep class de.robv.android.xposed.** { *; }
-keep class * implements de.robv.android.xposed.IXposedHookLoadPackage
-keep class * implements de.robv.android.xposed.IXposedHookInitPackageResources

# 保留 Xposed 的核心初始化方法
-keepclassmembers class * implements de.robv.android.xposed.IXposedHookLoadPackage {
    public void handleLoadPackage(de.robv.android.xposed.callbacks.XC_LoadPackage$LoadPackageParam);
}

# ==========================================
# 🛡️ 2. 保住反射和注解
# ==========================================
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes InnerClasses
-keepattributes EnclosingMethod

# ==========================================
# 🛡️ 3. 保住液态玻璃与模糊库（防止 Release 混淆导致 ClassNotFound 崩溃）
# ==========================================
-keep class com.qmdeve.liquidglass.** { *; }
-keep class com.github.QWEA0.liquidglass.** { *; }
-keep class com.example.liquidglass.** { *; }
-keep class com.example.blur.** { *; }
# ==========================================
# 🛡️ 忽略高版本 Android 缺失的系统类（专门治你现在的报错）
# ==========================================
-dontwarn android.graphics.RuntimeColorFilter
-dontwarn android.graphics.RuntimeXfermode

# 兜底：忽略所有 android.graphics 下找不到的类
-dontwarn android.graphics.**