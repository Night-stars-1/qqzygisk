package com.qm.qqzygisk.hook.app

import java.lang.reflect.Field
import java.lang.reflect.Method

internal fun Class<*>.declaredMember(name: String, params: Array<Class<*>>): Method {
    var type: Class<*>? = this
    while (type != null) {
        val found = type.declaredMethods.firstOrNull {
            it.name == name && it.parameterTypes.contentEquals(params) && (it.modifiers and 0x40) == 0
        }
        if (found != null) return found.apply { isAccessible = true }
        type = type.superclass
    }
    error("$simpleName 缺少 $name(${params.joinToString(",") { it.simpleName }})")
}

internal fun Class<*>.declaredSlot(name: String): Field {
    var type: Class<*>? = this
    while (type != null) {
        val found = type.declaredFields.firstOrNull { it.name == name }
        if (found != null) return found.apply { isAccessible = true }
        type = type.superclass
    }
    error("$simpleName 缺少字段 $name")
}
