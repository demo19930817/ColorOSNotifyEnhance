package com.demo1993.coloros.notify.enhance.hook.reflect

import java.lang.reflect.Field
import java.lang.reflect.Method

internal object Reflection {

    fun loadClassOrNull(
        name: String,
        classLoader: ClassLoader,
        onMissing: (Throwable) -> Unit,
    ): Class<*>? = try {
        Class.forName(name, false, classLoader)
    } catch (exception: ClassNotFoundException) {
        onMissing(exception)
        null
    }

    fun findField(
        clazz: Class<*>,
        name: String,
        expectedType: Class<*>? = null,
    ): Field? {
        var current: Class<*>? = clazz
        while (current != null && current != Any::class.java) {
            current.declaredFields.firstOrNull {
                it.name == name && (expectedType == null || expectedType.isAssignableFrom(it.type))
            }?.let {
                it.isAccessible = true
                return it
            }
            current = current.superclass
        }
        return null
    }

    fun findMethod(
        clazz: Class<*>,
        name: String,
        vararg params: Class<*>,
    ): Method? = findMethodReturning(clazz, name, null, *params)

    /**
     * Finds any declared method with the given name, ignoring its parameter list. Used when the
     * vendor renamed an entry point but kept the name (for example ColorOS 17 turning
     * `initIcon()` into `initIcon(NotificationChildrenContainer)`).
     */
    fun findAnyMethod(
        clazz: Class<*>,
        name: String,
        expectedReturnType: Class<*>? = null,
    ): Method? {
        var current: Class<*>? = clazz
        while (current != null && current != Any::class.java) {
            current.declaredMethods.firstOrNull { method ->
                method.name == name &&
                    (expectedReturnType == null || expectedReturnType.isAssignableFrom(method.returnType))
            }?.let {
                it.isAccessible = true
                return it
            }
            current = current.superclass
        }
        return null
    }

    /**
     * Matches a declared method by exact parameter types. A `null` entry in [params] acts as a
     * wildcard for that position, which is needed for parameter types that the target process
     * resolves from a different class loader than the module (for example entry points taking a
     * `kotlin.jvm.functions.Function1`), where the two `Class` objects are never equal.
     */
    fun findMethodReturning(
        clazz: Class<*>,
        name: String,
        expectedReturnType: Class<*>?,
        vararg params: Class<*>?,
    ): Method? {
        var current: Class<*>? = clazz
        while (current != null && current != Any::class.java) {
            current.declaredMethods.firstOrNull { method ->
                val parameterTypes = method.parameterTypes
                method.name == name &&
                    parameterTypes.size == params.size &&
                    params.indices.all { index ->
                        params[index] == null || params[index] == parameterTypes[index]
                    } &&
                    (expectedReturnType == null || expectedReturnType.isAssignableFrom(method.returnType))
            }?.let {
                it.isAccessible = true
                return it
            }
            current = current.superclass
        }
        return null
    }
}
