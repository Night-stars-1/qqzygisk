package com.qm.qqzygisk.hook.app.chat

import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap

/**
 * 从宿主 `MsgRecord` 读出可以定位原消息的身份，并排除不能加框的记录。
 *
 * 只接受确实承载普通消息内容的记录：`chatType` 是单聊/群聊、`peerUid` 非空、
 * `msgSeq` 为正，且 `elements` 非空、每个元素的 `elementType` 都是可识别的正值。
 * 独立撤回提示等灰条即使身份字段与已撤回消息恰好同 key，也会因为元素类型是 8 而在这里被拒绝；
 * 空列表、缺失字段、无法识别的元素类型同样返回 null。
 * 判断只看记录本身，不看这一行是否恰好在结构上存在气泡，因此灰条不会被误当成原消息。
 *
 * 四个记录 getter 在构造时按精确形状解析一次：`getChatType():int`、`getPeerUid():String`、
 * `getMsgSeq():long`、`getElements():List 子类型`。元素 getter 按元素 Class 惰性解析并缓存，
 * 缓存用带可空 Method 的 holder 占位，缺失结果也能缓存，不会向 [ConcurrentHashMap] 写 null。
 * 全程只读取对象，不修改 `MsgRecord` 或任何元素。
 */
internal class RecalledRecordReader(
    private val recordType: Class<*>,
) {
    private companion object {
        /** `MsgElement` 的元素类型：8 是 JSON/文本灰条（撤回提示、系统提示等）。 */
        const val ELEMENT_TYPE_GRAY_TIP = 8

        const val ELEMENT_TYPE_GETTER = "getElementType"
    }

    private val chatTypeGetter: Method? = getterOf("getChatType", Int::class.javaPrimitiveType!!)
    private val peerGetter: Method? = getterOf("getPeerUid", String::class.java)
    private val seqGetter: Method? = getterOf("getMsgSeq", Long::class.javaPrimitiveType!!)
    private val elementsGetter: Method? = elementsGetterOf()

    private val elementTypeAccessors = ConcurrentHashMap<Class<*>, ElementTypeAccessor>()

    /** 非 null 表示记录形状不完整，调用方应跳过加框并输出一次告警。 */
    val unavailable: String? =
        when {
            chatTypeGetter == null -> "getChatType():int"
            peerGetter == null -> "getPeerUid():String"
            seqGetter == null -> "getMsgSeq():long"
            elementsGetter == null -> "getElements():List"
            else -> null
        }

    /**
     * 返回 [record] 的消息身份；账号为空、记录类型不符、字段缺失、会话类型不支持、
     * 元素列表为空或含灰条/未知类型时返回 null。调用方每次传入现场账号，避免换号后沿用旧 uin。
     */
    fun keyOf(
        record: Any,
        accountUin: String,
    ): RecalledMessageKey? {
        if (accountUin.isEmpty() || !recordType.isInstance(record)) return null
        val chatType = chatTypeGetter?.let { invokeInt(it, record) } ?: return null
        if (chatType != NtMsgAccess.CHAT_TYPE_C2C && chatType != NtMsgAccess.CHAT_TYPE_GROUP) return null
        val peerUid = peerGetter?.let { invokeString(it, record) }?.takeIf { it.isNotEmpty() } ?: return null
        val msgSeq = seqGetter?.let { invokeLong(it, record) }?.takeIf { it > 0L } ?: return null
        if (!hasOrdinaryElements(record)) return null
        return RecalledMessageKey(accountUin, chatType, peerUid, msgSeq)
    }

    /** 列表必须非空，且每个元素都能读出一个大于 0、不是灰条的类型。 */
    private fun hasOrdinaryElements(record: Any): Boolean {
        val getter = elementsGetter ?: return false
        val elements = runCatching { getter.invoke(record) as? List<*> }.getOrNull() ?: return false
        if (elements.isEmpty()) return false
        for (element in elements) {
            element ?: return false
            val type = elementTypeOf(element) ?: return false
            if (type <= 0 || type == ELEMENT_TYPE_GRAY_TIP) return false
        }
        return true
    }

    private fun elementTypeOf(element: Any): Int? {
        val accessor =
            elementTypeAccessors.computeIfAbsent(element.javaClass) { type ->
                val method =
                    runCatching { type.getMethod(ELEMENT_TYPE_GETTER) }.getOrNull()
                        ?.takeIf {
                            it.parameterCount == 0 &&
                                it.returnType == Int::class.javaPrimitiveType &&
                                !Modifier.isStatic(it.modifiers)
                        }
                        ?.apply { isAccessible = true }
                ElementTypeAccessor(method)
            }
        val method = accessor.method ?: return null
        return runCatching { method.invoke(element) as? Int }.getOrNull()
    }

    private fun getterOf(
        name: String,
        returnType: Class<*>,
    ): Method? =
        runCatching { recordType.getMethod(name) }.getOrNull()
            ?.takeIf {
                it.parameterCount == 0 &&
                    it.returnType == returnType &&
                    !Modifier.isStatic(it.modifiers)
            }
            ?.apply { isAccessible = true }

    private fun elementsGetterOf(): Method? =
        runCatching { recordType.getMethod("getElements") }.getOrNull()
            ?.takeIf {
                it.parameterCount == 0 &&
                    List::class.java.isAssignableFrom(it.returnType) &&
                    !Modifier.isStatic(it.modifiers)
            }
            ?.apply { isAccessible = true }

    private fun invokeInt(
        method: Method,
        target: Any,
    ): Int? = runCatching { method.invoke(target) as? Int }.getOrNull()

    private fun invokeLong(
        method: Method,
        target: Any,
    ): Long? = runCatching { method.invoke(target) as? Long }.getOrNull()

    private fun invokeString(
        method: Method,
        target: Any,
    ): String? = runCatching { method.invoke(target) as? String }.getOrNull()

    /** `computeIfAbsent` 必须返回非 null；用 holder 同时缓存“找到了”和“确实没有”。 */
    private class ElementTypeAccessor(
        val method: Method?,
    )
}
