package com.yilink.jumpclean

import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap

/**
 * 纯反射与 libxposed 桥接工具类
 * 替代旧版 XposedHelpers / XposedBridge，不依赖任何 de.robv.android.xposed 遗留 API
 */
object XposedCompat {

    @Volatile
    var module: XposedModule? = null

    private val classCache = ConcurrentHashMap<String, Class<*>?>()
    private val methodCache = ConcurrentHashMap<String, Method?>()
    private val fieldCache = ConcurrentHashMap<String, Field?>()

    fun findClass(className: String, classLoader: ClassLoader?): Class<*> {
        val cl = classLoader ?: XposedCompat::class.java.classLoader
        val key = className + "@" + System.identityHashCode(cl)
        return classCache.computeIfAbsent(key) {
            cl.loadClass(className)
        } ?: throw ClassNotFoundException(className)
    }

    fun findClassIfExists(className: String, classLoader: ClassLoader?): Class<*>? {
        return try {
            findClass(className, classLoader)
        } catch (_: Throwable) {
            null
        }
    }

    fun findMethodExact(clazz: Class<*>, methodName: String, vararg parameterTypes: Class<*>): Method {
        val key = clazz.name + "#" + methodName + "(" + parameterTypes.joinToString { it.name } + ")"
        return methodCache.computeIfAbsent(key) {
            var cur: Class<*>? = clazz
            while (cur != null && cur != Any::class.java) {
                try {
                    val m = cur.getDeclaredMethod(methodName, *parameterTypes)
                    m.isAccessible = true
                    return@computeIfAbsent m
                } catch (_: NoSuchMethodException) {
                    cur = cur.superclass
                }
            }
            null
        } ?: throw NoSuchMethodException("${clazz.name}#$methodName")
    }

    fun findMethodExactIfExists(clazz: Class<*>, methodName: String, vararg parameterTypes: Class<*>): Method? {
        return try {
            findMethodExact(clazz, methodName, *parameterTypes)
        } catch (_: Throwable) {
            null
        }
    }

    fun findField(clazz: Class<*>, fieldName: String): Field {
        val key = clazz.name + "#" + fieldName
        return fieldCache.computeIfAbsent(key) {
            var cur: Class<*>? = clazz
            while (cur != null && cur != Any::class.java) {
                try {
                    val f = cur.getDeclaredField(fieldName)
                    f.isAccessible = true
                    return@computeIfAbsent f
                } catch (_: NoSuchFieldException) {
                    cur = cur.superclass
                }
            }
            null
        } ?: throw NoSuchFieldException("${clazz.name}#$fieldName")
    }

    fun findFieldIfExists(clazz: Class<*>, fieldName: String): Field? {
        return try {
            findField(clazz, fieldName)
        } catch (_: Throwable) {
            null
        }
    }

    fun getObjectField(obj: Any?, fieldName: String): Any? {
        if (obj == null) return null
        return findField(obj.javaClass, fieldName).get(obj)
    }

    fun getStaticObjectField(clazz: Class<*>?, fieldName: String): Any? {
        if (clazz == null) return null
        return findField(clazz, fieldName).get(null)
    }

    fun setObjectField(obj: Any?, fieldName: String, value: Any?) {
        if (obj == null) return
        findField(obj.javaClass, fieldName).set(obj, value)
    }

    fun setStaticObjectField(clazz: Class<*>?, fieldName: String, value: Any?) {
        if (clazz == null) return
        findField(clazz, fieldName).set(null, value)
    }

    fun callMethod(obj: Any?, methodName: String, vararg args: Any?): Any? {
        if (obj == null) return null
        val argTypes = args.map { it?.javaClass ?: Any::class.java }
        val method = findBestMatchingMethod(obj.javaClass, methodName, argTypes)
            ?: throw NoSuchMethodException("${obj.javaClass.name}#$methodName")
        method.isAccessible = true
        return method.invoke(obj, *args)
    }

    fun callStaticMethod(clazz: Class<*>?, methodName: String, vararg args: Any?): Any? {
        if (clazz == null) return null
        val argTypes = args.map { it?.javaClass ?: Any::class.java }
        val method = findBestMatchingMethod(clazz, methodName, argTypes)
            ?: throw NoSuchMethodException("${clazz.name}#$methodName")
        method.isAccessible = true
        return method.invoke(null, *args)
    }

    private fun findBestMatchingMethod(clazz: Class<*>, methodName: String, argTypes: List<Class<*>>): Method? {
        var cur: Class<*>? = clazz
        while (cur != null && cur != Any::class.java) {
            for (m in cur.declaredMethods) {
                if (m.name == methodName && isAssignable(m.parameterTypes, argTypes)) {
                    return m
                }
            }
            cur = cur.superclass
        }
        return null
    }

    private fun isAssignable(params: Array<Class<*>>, args: List<Class<*>>): Boolean {
        if (params.size != args.size) return false
        for (i in params.indices) {
            val p = params[i]
            val a = args[i]
            if (p.isPrimitive) {
                if (p == java.lang.Integer.TYPE && (a == java.lang.Integer::class.java || a == Int::class.java)) continue
                if (p == java.lang.Boolean.TYPE && (a == java.lang.Boolean::class.java || a == Boolean::class.java)) continue
                if (p == java.lang.Long.TYPE && (a == java.lang.Long::class.java || a == Long::class.java)) continue
                if (p == java.lang.Float.TYPE && (a == java.lang.Float::class.java || a == Float::class.java)) continue
                if (p == java.lang.Double.TYPE && (a == java.lang.Double::class.java || a == Double::class.java)) continue
                if (p == java.lang.Byte.TYPE && (a == java.lang.Byte::class.java || a == Byte::class.java)) continue
                if (p == java.lang.Short.TYPE && (a == java.lang.Short::class.java || a == Short::class.java)) continue
                if (p == java.lang.Character.TYPE && (a == java.lang.Character::class.java || a == Char::class.java)) continue
                return false
            }
            if (!p.isAssignableFrom(a) && a != Any::class.java) return false
        }
        return true
    }

    // ========== Hook DSL 桥接 ==========

    class MethodHookParam(
        val chain: XposedInterface.Chain,
        val thisObject: Any?,
        val args: Array<Any?>
    ) {
        private var _result: Any? = null
        var isResultModified = false
            private set

        var result: Any?
            get() = _result
            set(value) {
                _result = value
                isResultModified = true
            }


        var throwable: Throwable? = null

        private val extra = java.util.HashMap<String, Any?>()

        fun setObjectExtra(key: String, value: Any?) {
            extra[key] = value
        }

        fun getObjectExtra(key: String): Any? {
            return extra[key]
        }
    }

    abstract class MethodHookCallback {
        open fun beforeHookedMethod(param: MethodHookParam) {}
        open fun afterHookedMethod(param: MethodHookParam) {}
    }

    fun hookMethod(
        method: Method,
        callback: MethodHookCallback
    ): XposedInterface.HookHandle {
        return hookExecutable(method, callback = callback)
    }

    fun hookExecutable(
        executable: Executable,
        priority: Int = XposedInterface.PRIORITY_DEFAULT,
        callback: MethodHookCallback
    ): XposedInterface.HookHandle {
        val mod = module ?: throw IllegalStateException("XposedCompat.module 未初始化")
        return mod.hook(executable)
            .setPriority(priority)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val thisObj = chain.thisObject
                val argsList = chain.args
                val argsArray = argsList.toTypedArray()
                val hookParam = MethodHookParam(chain, thisObj, argsArray)

                try {
                    callback.beforeHookedMethod(hookParam)
                } catch (t: Throwable) {
                    HookUtils.err("Hook before 异常: " + executable.name, t)
                }

                val proceedResult = if (hookParam.isResultModified) {
                    hookParam.result
                } else {
                    try {
                        val newArgs = hookParam.args
                        var argsChanged = false
                        if (newArgs.size == argsList.size) {
                            for (i in newArgs.indices) {
                                if (newArgs[i] !== argsList[i]) {
                                    argsChanged = true
                                    break
                                }
                            }
                        }
                        if (argsChanged) {
                            chain.proceed(newArgs.filterNotNull().toTypedArray())
                        } else {
                            chain.proceed()
                        }
                    } catch (t: Throwable) {
                        hookParam.throwable = t
                        throw t
                    }
                }

                hookParam.result = proceedResult
                try {
                    callback.afterHookedMethod(hookParam)
                } catch (t: Throwable) {
                    HookUtils.err("Hook after 异常: " + executable.name, t)
                }

                hookParam.result
            }
    }

    fun hookAllMethods(
        clazz: Class<*>,
        methodName: String,
        callback: MethodHookCallback
    ): List<XposedInterface.HookHandle> {
        val handles = mutableListOf<XposedInterface.HookHandle>()
        for (m in clazz.declaredMethods) {
            if (m.name == methodName) {
                handles.add(hookExecutable(m, callback = callback))
            }
        }
        return handles
    }

    fun hookAllConstructors(
        clazz: Class<*>,
        callback: MethodHookCallback
    ): List<XposedInterface.HookHandle> {
        val handles = mutableListOf<XposedInterface.HookHandle>()
        for (c in clazz.declaredConstructors) {
            handles.add(hookExecutable(c, callback = callback))
        }
        return handles
    }

    fun findAndHookMethod(
        clazz: Class<*>,
        methodName: String,
        vararg parameterTypesAndCallback: Any?
    ): XposedInterface.HookHandle {
        val callback = parameterTypesAndCallback.last() as MethodHookCallback
        val paramTypes = parameterTypesAndCallback.dropLast(1).map { it as Class<*> }.toTypedArray()
        val method = findMethodExact(clazz, methodName, *paramTypes)
        return hookExecutable(method, callback = callback)
    }

    fun findAndHookMethod(
        className: String,
        classLoader: ClassLoader?,
        methodName: String,
        vararg parameterTypesAndCallback: Any?
    ): XposedInterface.HookHandle {
        val clazz = findClass(className, classLoader)
        return findAndHookMethod(clazz, methodName, *parameterTypesAndCallback)
    }

    fun findAndHookConstructor(
        clazz: Class<*>,
        vararg parameterTypesAndCallback: Any?
    ): XposedInterface.HookHandle {
        val callback = parameterTypesAndCallback.last() as MethodHookCallback
        val paramTypes = parameterTypesAndCallback.dropLast(1).map { it as Class<*> }.toTypedArray()
        val ctor = clazz.getDeclaredConstructor(*paramTypes)
        ctor.isAccessible = true
        return hookExecutable(ctor, callback = callback)
    }
}
