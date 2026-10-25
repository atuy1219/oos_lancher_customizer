package com.atuy.oos_lancher_customizer

import android.content.Context
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/** Cache successful AND absent lookups, scoped to the actual runtime class. */
internal object CachedReflection {
    private data class FieldKey(val type: Class<*>, val name: String)
    private data class MethodKey(val type: Class<*>, val name: String, val args: List<Class<*>>)
    private data class Found<T>(val member: T?)
    private val fields = ConcurrentHashMap<FieldKey, Found<Field>>()
    private val methods = ConcurrentHashMap<MethodKey, Found<Method>>()
    private val contextFields = ConcurrentHashMap<Class<*>, List<Field>>()

    fun field(type: Class<*>, name: String): Field? =
        fields.computeIfAbsent(FieldKey(type, name)) {
            var current: Class<*>? = type
            var result: Field? = null
            while (current != null && result == null) {
                val owner = current
                result = runCatching { owner.getDeclaredField(name).apply { isAccessible = true } }.getOrNull()
                current = owner.superclass
            }
            Found(result)
        }.member

    fun method(type: Class<*>, name: String, vararg args: Class<*>): Method? =
        methods.computeIfAbsent(MethodKey(type, name, args.toList())) {
            var current: Class<*>? = type
            var result: Method? = null
            while (current != null && result == null) {
                val owner = current
                result = runCatching {
                    owner.getDeclaredMethod(name, *args).apply { isAccessible = true }
                }.getOrNull()
                current = owner.superclass
            }
            Found(result)
        }.member

    fun read(owner: Any?, name: String): Any? = owner?.let {
        runCatching { field(it.javaClass, name)?.get(it) }.getOrNull()
    }

    fun call(owner: Any, name: String): Any? =
        runCatching { method(owner.javaClass, name)?.invoke(owner) }.getOrNull()

    fun context(owner: Any?): Context? {
        if (owner == null) return null
        if (owner is Context) return owner.applicationContext
        val candidates = contextFields.computeIfAbsent(owner.javaClass) { type ->
            buildList {
                var current: Class<*>? = type
                while (current != null) {
                    for (field in current.declaredFields) {
                        if (Context::class.java.isAssignableFrom(field.type)) {
                            runCatching { field.isAccessible = true; add(field) }
                        }
                    }
                    current = current.superclass
                }
            }
        }
        return candidates.firstNotNullOfOrNull { field ->
            (runCatching { field.get(owner) }.getOrNull() as? Context)?.applicationContext
        }
    }
}
