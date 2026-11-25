package com.qm.qqzygisk.hook.app.hooker

import com.qm.qqzygisk.hook.app.base.BaseHooker
import com.qm.qqzygisk.hook.app.data.HostData.toAppClass
import com.qm.qqzygisk.hook.utils.HookSettings
import com.qm.qqzygisk.hook.utils.HostClassScanner
import com.qm.qqzygisk.hook.utils.Log
import com.qm.qqzygisk.hook.utils.ModuleUtils
import com.v7878.unsafe.invoke.EmulatedStackFrame
import com.v7878.unsafe.invoke.Transformers
import com.v7878.vmtools.HookTransformer
import com.v7878.vmtools.Hooks
import com.v7878.vmtools.Hooks.EntryPointType
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.lang.invoke.MethodHandle
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 调整群聊 @ 面板里成员的顺序。
 *
 * QQ 在 `AIOAtSelectMemberUseCase` 里把成员分成「★ / A–Z / #」后，交给
 * `buildIndexAndSubmit` 提交。这里挂钩那个提交构造器：非成员行（全体成员等）留在原位，
 * 只重排能读出 [MemberInfo] 的行，并按新顺序重写侧边索引，避免字母还指向旧位置。
 * 默认模式不改列表。
 */
object AtMemberOrderHooker : BaseHooker() {
    const val KEY = "at_member_order"
    const val MODE_DEFAULT = 0
    const val MODE_RECENT_SPEAK = 1
    const val MODE_RECENT_JOIN = 2
    const val MODE_ROLE = 3

    override val key = KEY
    override val name = "@ 排序"
    override val description = "调整群聊 @ 成员列表的顺序。默认保持 QQ 原来的特别关心置顶和拼音排序。"
    override val isShow = false

    data class Mode(
        val id: Int,
        val title: String,
        val detail: String,
    )

    val modes = listOf(
        Mode(MODE_DEFAULT, "默认", "特别关心置顶，其余按拼音"),
        Mode(MODE_RECENT_SPEAK, "最近发言", "最后发言的在前"),
        Mode(MODE_RECENT_JOIN, "最近入群", "新入群的在前"),
        Mode(MODE_ROLE, "群主管理员优先", "群主、管理员，然后是其他成员"),
    )

    fun mode(): Int {
        val value = HookSettings.getInt(KEY, MODE_DEFAULT)
        return if (modes.any { it.id == value }) value else MODE_DEFAULT
    }

    fun modeTitle(id: Int): String = modes.first { it.id == id }.title

    private const val USE_CASE_CLASS = "com.tencent.mobileqq.aio.input.at.business.AIOAtSelectMemberUseCase"
    private const val BUSINESS_PACKAGE = "com.tencent.mobileqq.aio.input.at.business"
    private const val MEMBER_INFO_CLASS = "com.tencent.qqnt.kernel.nativeinterface.MemberInfo"
    private const val ROLE_CLASS = "com.tencent.qqnt.kernelpublic.nativeinterface.MemberRole"

    /** 构造器实参：0 是正在构造的提交对象，1 是用例，2/3 是已分组的成员列表和字母索引。 */
    private const val FRAME_LIST_INDEX = 2
    private const val FRAME_INDEX_INDEX = 3
    private const val XP_LIST_INDEX = 1
    private const val XP_INDEX_INDEX = 2

    private class Prepared(
        val constructor: Constructor<*>,
        val infoType: Class<*>,
        val lastSpeakTime: Field,
        val joinTime: Field,
        val role: Field,
        val owner: Any,
        val admin: Any,
    )

    private val infoMethods = HashMap<Class<*>, Method?>()
    private val reorderFailureLogged = AtomicBoolean(false)
    private lateinit var prepared: Prepared

    override fun initOnce() {
        prepared = prepare()
        if (ModuleUtils.isXpEnvironment) {
            installXposed(prepared.constructor)
        } else {
            installNative(prepared.constructor)
        }
        Log.info("@ 列表排序已安装: ${prepared.constructor.declaringClass.name}")
    }

    private fun prepare(): Prepared {
        val useCase = USE_CASE_CLASS.toAppClass()
        val infoType = MEMBER_INFO_CLASS.toAppClass()
        val roleType = ROLE_CLASS.toAppClass()
        val names = HostClassScanner.classesInPackage(BUSINESS_PACKAGE)
            .filter { it.startsWith("$USE_CASE_CLASS$") }
        val constructors = names.mapNotNull { HostClassScanner.load(it) }.flatMap { type ->
            type.declaredConstructors.filter { isSubmitConstructor(it, useCase) }
        }
        val constructor = constructors.singleOrNull()
            ?: error(
                "$USE_CASE_CLASS 的列表提交构造器匹配到 ${constructors.size} 个，预期唯一",
            )
        return Prepared(
            constructor = constructor,
            infoType = infoType,
            lastSpeakTime = infoType.getField("lastSpeakTime"),
            joinTime = infoType.getField("joinTime"),
            role = infoType.getField("role"),
            owner = checkNotNull(roleType.getField("OWNER").get(null)) { "MemberRole.OWNER" },
            admin = checkNotNull(roleType.getField("ADMIN").get(null)) { "MemberRole.ADMIN" },
        )
    }

    /** `(用例, List, List, …)`：两个列表分别是排好的成员和侧边字母索引。 */
    private fun isSubmitConstructor(constructor: Constructor<*>, useCase: Class<*>): Boolean {
        val parameters = constructor.parameterTypes
        return parameters.size >= 3 &&
            parameters[0] == useCase &&
            List::class.java.isAssignableFrom(parameters[1]) &&
            List::class.java.isAssignableFrom(parameters[2])
    }

    private fun installNative(constructor: Constructor<*>) {
        Hooks.hook(
            constructor,
            EntryPointType.DIRECT,
            HookTransformer { original: MethodHandle, frame: EmulatedStackFrame ->
                val accessor = frame.accessor()
                apply(
                    accessor.getValue(FRAME_LIST_INDEX),
                    accessor.getValue(FRAME_INDEX_INDEX),
                )
                Transformers.invokeExact(original, frame)
            },
            EntryPointType.DIRECT,
        )
    }

    private fun installXposed(constructor: Constructor<*>) {
        XposedBridge.hookMethod(
            constructor,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    apply(
                        param.args.getOrNull(XP_LIST_INDEX),
                        param.args.getOrNull(XP_INDEX_INDEX),
                    )
                }
            },
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun apply(items: Any?, index: Any?) {
        val mode = mode()
        if (mode == MODE_DEFAULT) return
        if (items !is MutableList<*> || index !is MutableList<*>) return
        try {
            reorder(items as MutableList<Any>, index as MutableList<Any>, mode)
        } catch (th: Throwable) {
            if (reorderFailureLogged.compareAndSet(false, true)) {
                Log.error("@ 列表排序失败，保持 QQ 原顺序", th)
            }
        }
    }

    private fun reorder(items: MutableList<Any>, index: MutableList<Any>, mode: Int) {
        val slots = ArrayList<Int>()
        val members = ArrayList<Any>()
        for (position in items.indices) {
            if (memberInfo(items[position]) != null) {
                slots.add(position)
                members.add(items[position])
            }
        }
        if (slots.size < 2) return
        val sorted = members.withIndex().sortedWith(comparator(mode))
        slots.forEachIndexed { offset, slot -> items[slot] = sorted[offset].value }
        rewriteIndex(index, items, mode)
    }

    private fun comparator(mode: Int): Comparator<IndexedValue<Any>> = when (mode) {
        MODE_RECENT_SPEAK -> compareByDescending<IndexedValue<Any>> { speakTime(it.value) }
            .thenBy { it.index }
        MODE_RECENT_JOIN -> compareByDescending<IndexedValue<Any>> { joinTime(it.value) }
            .thenBy { it.index }
        else -> compareBy<IndexedValue<Any>> { roleRank(it.value) }.thenBy { it.index }
    }

    private fun memberInfo(item: Any): Any? {
        val method = infoMethods.getOrPut(item.javaClass) {
            item.javaClass.declaredMethods.singleOrNull {
                it.parameterCount == 0 && it.returnType == prepared.infoType
            }?.also { it.isAccessible = true }
        } ?: return null
        return method.invoke(item)
    }

    private fun speakTime(item: Any): Int =
        prepared.lastSpeakTime.getInt(memberInfo(item))

    private fun joinTime(item: Any): Int =
        prepared.joinTime.getInt(memberInfo(item))

    private fun roleRank(item: Any): Int = when (prepared.role.get(memberInfo(item))) {
        prepared.owner -> 0
        prepared.admin -> 1
        else -> 2
    }

    /**
     * 侧边索引是 `Pair(字母, 起始下标)`。自定义顺序后原来的 A–Z 会指错人，
     * 所以按新顺序重写；写不出来就清空，避免点字母跳到错误成员。
     */
    private fun rewriteIndex(index: MutableList<Any>, items: List<Any>, mode: Int) {
        val sample = index.firstOrNull() ?: return
        val constructor = sample.javaClass.declaredConstructors.firstOrNull { it.parameterCount == 2 }
            ?: run {
                index.clear()
                return
            }
        constructor.isAccessible = true
        val markers = if (mode == MODE_ROLE) roleMarkers(items) else listOf("·" to 0)
        val created = markers.map { (label, position) ->
            constructor.newInstance(label, position)
        }
        index.clear()
        index.addAll(created)
    }

    private fun roleMarkers(items: List<Any>): List<Pair<String, Int>> {
        val markers = ArrayList<Pair<String, Int>>(3)
        var seenOwner = false
        var seenAdmin = false
        var seenMember = false
        for ((position, item) in items.withIndex()) {
            if (memberInfo(item) == null) continue
            when (roleRank(item)) {
                0 -> if (!seenOwner) {
                    seenOwner = true
                    markers.add("群" to position)
                }
                1 -> if (!seenAdmin) {
                    seenAdmin = true
                    markers.add("管" to position)
                }
                else -> if (!seenMember) {
                    seenMember = true
                    markers.add("员" to position)
                }
            }
        }
        return markers.ifEmpty { listOf("·" to 0) }
    }
}
