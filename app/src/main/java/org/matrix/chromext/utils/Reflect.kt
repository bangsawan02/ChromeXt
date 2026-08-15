package org.matrix.chromext.utils

import java.lang.reflect.Field
import java.lang.reflect.Method

typealias MethodCondition = Method.() -> Boolean

typealias FieldCondition = Field.() -> Boolean

fun findMethod(clz: Class<*>, findSuper: Boolean = false, condition: MethodCondition): Method {
  return findMethodOrNull(clz, findSuper, condition) ?: throw NoSuchMethodException()
}

fun findMethodOrNull(
    clz: Class<*>,
    findSuper: Boolean = false,
    condition: MethodCondition
): Method? {
  var c: Class<*>? = clz
  while (c != null) {
    val methods = runCatching { c.declaredMethods }.getOrNull() ?: emptyArray()
    for (it in methods) {
      val matches = runCatching { it.condition() }.getOrDefault(false)
      if (matches) {
        runCatching { it.isAccessible = true }
        return it
      }
    }
    if (!findSuper) break
    c = c.superclass
  }
  return null
}

fun Any.invokeMethod(vararg args: Any?, condition: MethodCondition): Any? {
  findMethodOrNull(this::class.java, true, condition)?.let {
    return it(this, *args)
  }
  throw NoSuchMethodException()
}

fun Any.invokeMethodOrNull(vararg args: Any?, condition: MethodCondition): Any? {
  return runCatching {
    findMethodOrNull(this::class.java, true, condition)?.invoke(this, *args)
  }.getOrNull()
}

fun findField(clz: Class<*>, findSuper: Boolean = false, condition: FieldCondition): Field {
  return findFieldOrNull(clz, findSuper, condition) ?: throw NoSuchFieldException()
}

fun findFieldOrNull(clz: Class<*>, findSuper: Boolean = false, condition: FieldCondition): Field? {
  var c: Class<*>? = clz
  while (c != null) {
    val fields = runCatching { c.declaredFields }.getOrNull() ?: emptyArray()
    for (it in fields) {
      val matches = runCatching { it.condition() }.getOrDefault(false)
      if (matches) {
        runCatching { it.isAccessible = true }
        return it
      }
    }
    if (!findSuper) break
    c = c.superclass
  }
  return null
}

fun findFirstFieldByType(clz: Class<*>, type: Class<*>, findSuper: Boolean = true): Field? {
  return findFieldOrNull(clz, findSuper) { type.isAssignableFrom(this.type) }
}

fun findFieldsByType(clz: Class<*>, type: Class<*>, findSuper: Boolean = true): List<Field> {
  val result = mutableListOf<Field>()
  var c: Class<*>? = clz
  while (c != null) {
    val fields = runCatching { c.declaredFields }.getOrNull() ?: emptyArray()
    for (f in fields) {
      if (type.isAssignableFrom(f.type)) {
        runCatching { f.isAccessible = true }
        result.add(f)
      }
    }
    if (!findSuper) break
    c = c.superclass
  }
  return result
}
