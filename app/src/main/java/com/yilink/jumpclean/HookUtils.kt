package com.yilink.jumpclean

import de.robv.android.xposed.XposedBridge

object HookUtils {
    fun log(msg: String) {
        XposedBridge.log("[JumpClean] $msg")
    }

    fun err(msg: String, t: Throwable? = null) {
        XposedBridge.log("[JumpClean] [ERR] $msg")
        if (t != null) XposedBridge.log(t)
    }
}