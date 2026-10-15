package com.yilink.jumpclean.hooks

import java.util.IdentityHashMap

/** 只在当前线程、同一 Adapter 的一轮嵌套调用内复用清洗结果。 */
internal class AdapterDataPass {
    private val active = ThreadLocal<IdentityHashMap<Any, Scope>>()

    class Scope {
        internal var depth = 0
        private val processed = IdentityHashMap<Collection<*>, Array<Any?>>()

        fun shouldProcess(data: Collection<*>): Boolean {
            val snapshot = processed[data] ?: return true
            if (snapshot.size != data.size) return true
            // 比较全部元素引用，不用 size/尾元素 hash 猜测中间数据是否被替换。
            val iterator = data.iterator()
            for (item in snapshot) {
                if (!iterator.hasNext() || iterator.next() !== item) return true
            }
            return iterator.hasNext()
        }

        fun remember(data: Collection<*>) {
            // 在清洗后记录，过滤导致的长度变化不会触发下一层无意义的重扫。
            processed[data] = data.toTypedArray()
        }
    }

    fun enter(adapter: Any): Scope {
        val scopes = active.get() ?: IdentityHashMap<Any, Scope>().also { active.set(it) }
        val scope = scopes[adapter] ?: Scope().also { scopes[adapter] = it }
        scope.depth++
        return scope
    }

    fun leave(adapter: Any) {
        val scopes = active.get() ?: return
        val scope = scopes[adapter] ?: return
        scope.depth--
        if (scope.depth == 0) scopes.remove(adapter)
        if (scopes.isEmpty()) active.remove()
    }
}
