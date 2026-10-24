package com.qm.qqzygisk.hook.app.chat

import android.content.Context
import android.view.View
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.qm.qqzygisk.hook.app.data.HostData.appClassLoader
import com.qm.qqzygisk.hook.app.data.HostData.toAppClass
import com.qm.qqzygisk.hook.extension.hookAll
import com.qm.qqzygisk.hook.utils.Log
import com.qm.qqzygisk.hook.utils.injectModuleAppResources
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 长按菜单匹配的消息内容类型。QQ 类名在这里解析，调用方只写枚举。
 */
enum class ChatMenuType(internal val className: String?) {
    Pic("com.tencent.qqnt.kernel.nativeinterface.PicElement"),
    Ark("com.tencent.qqnt.kernel.nativeinterface.ArkElement"),
    Any(null),
}

/** 菜单项插入位置，默认 [Back]。 */
enum class ChatMenuPosition {
    Front,
    Back,
}

/**
 * QQ 聊天长按菜单入口。
 *
 * 调用 [addMenuItem] 只需要标题、类型、图标和点击回调；
 * [setMenu] 的 hook 与菜单项插入都在内部完成。
 */
object ChatMenu {
    private val installed = AtomicBoolean(false)
    private val entries = CopyOnWriteArrayList<MenuEntry>()
    private val listFields = ConcurrentHashMap<Class<*>, Field>()
    private val messageMethods = ConcurrentHashMap<Class<*>, Method>()
    private val menuTextMethods = ConcurrentHashMap<Class<*>, MenuTextMethods>()
    private val msgRecordMethods = ConcurrentHashMap<Class<*>, Method>()
    private val elementsMethods = ConcurrentHashMap<Class<*>, Method>()
    private val elementGetterMethods = ConcurrentHashMap<ElementGetterKey, Method>()
    private val typeClasses = ConcurrentHashMap<ChatMenuType, Class<*>>()

    private data class MenuEntry(
        val title: String,
        val type: ChatMenuType,
        val icon: Int,
        val visible: () -> Boolean,
        val matches: (Any) -> Boolean,
        val position: ChatMenuPosition,
        val onClick: (Context, Any) -> Unit,
        val itemId: Int = View.generateViewId(),
    )

    private data class ElementGetterKey(
        val elementClass: Class<*>,
        val elementType: Class<*>,
    )

    private data class MenuTextMethods(val title: Method, val identifier: Method)

    /**
     * 注册一个长按菜单项。首次调用时自动 hook QQ 的 setMenu。
     *
     * 相同 (标题, 类型, 位置) 的重复注册视为同一项：更新回调与图标并保留原来的 itemId，
     * 因此安装失败后重试不会产生重复菜单项。
     *
     * @param title 菜单上显示的文字
     * @param type 消息内容类型，当前消息没有该内容则不插入
     * @param icon 菜单图标资源 id
     * @param position 插在现有菜单前面还是后面，默认后面
     * @param matches 按匹配到的消息/元素内容判断是否显示，避免把专用操作加到其它卡片上
     * @param onClick 点击回调，参数为菜单 Context 和匹配到的内容对象
     */
    fun addMenuItem(
        title: String,
        type: ChatMenuType,
        icon: Int,
        visible: () -> Boolean = { true },
        position: ChatMenuPosition = ChatMenuPosition.Back,
        matches: (Any) -> Boolean = { true },
        onClick: (Context, Any) -> Unit,
    ) {
        val previous = entries.firstOrNull { it.title == title && it.type == type && it.position == position }
        val entry = MenuEntry(title, type, icon, visible, matches, position, onClick, previous?.itemId ?: View.generateViewId())
        if (previous == null) entries += entry else entries[entries.indexOf(previous)] = entry
        ensureHooked()
    }

    /**
     * 安装菜单 hook。只有安装成功才置位，宿主尚未就绪导致失败时保持未安装状态，
     * 以便在更晚的宿主生命周期时机重试。
     */
    private fun ensureHooked() {
        if (installed.get()) return
        synchronized(installed) {
            if (installed.get()) return

            val layoutClass = listOf(
                "com.tencent.qqnt.aio.menu.ui.QQCustomMenuExpandableLayout",
                "com.tencent.qqnt.aio.menu.ui.QQCustomMenuNoIconLayout",
            ).firstNotNullOfOrNull { className ->
                runCatching { Class.forName(className, false, appClassLoader) }.getOrNull()
            } ?: error("未找到 QQ 聊天菜单布局类")

            val setMenuMethods = layoutClass.resolve().method {
                name = "setMenu"
            }
            check(setMenuMethods.isNotEmpty()) { "未找到 QQ 聊天菜单 setMenu 方法" }

            setMenuMethods.hookAll {
                before {
                    val layout = instance as? View ?: return@before
                    val menuContainer = args.getOrNull(0) ?: return@before
                    runCatching {
                        insertItems(layout, menuContainer)
                    }.onFailure {
                        Log.error("添加聊天菜单项失败", it)
                    }
                }
            }
            installed.set(true)
        }
    }

    private fun insertItems(layout: View, menuContainer: Any) {
        val visibleEntries = entries.filter { it.visible() }
        if (visibleEntries.isEmpty()) return

        layout.context.injectModuleAppResources()

        val listField = listFields[menuContainer.javaClass] ?: findListField(menuContainer.javaClass)
            .also { listFields[menuContainer.javaClass] = it }
        @Suppress("UNCHECKED_CAST")
        val items = listField.get(menuContainer) as? MutableList<Any> ?: return
        if (items.isEmpty() || items.any(ChatMenuItemFactory::isGenerated)) return

        val template = items.first()
        val baseClass = findMenuItemBaseClass(template.javaClass)
        val messageMethod = messageMethods[baseClass] ?: baseClass.declaredMethods.first {
            it.parameterCount == 0 &&
                it.returnType.name == "com.tencent.mobileqq.aio.msg.AIOMsgItem"
        }.apply {
            isAccessible = true
            messageMethods[baseClass] = this
        }
        val message = messageMethod.invoke(template) ?: return
        val methods = baseClass.declaredMethods.filter {
            it.parameterCount == 0 && !Modifier.isStatic(it.modifiers) && !Modifier.isFinal(it.modifiers)
        }
        val stringMethods = methods.filter {
            it.returnType == String::class.java && Modifier.isAbstract(it.modifiers)
        }
        val intMethods = methods.filter { it.returnType == Int::class.javaPrimitiveType }
        val clickMethods = methods.filter {
            it.returnType == Void.TYPE && Modifier.isAbstract(it.modifiers)
        }

        val textMethods = menuTextMethods[baseClass] ?: resolveMenuTextMethods(items, stringMethods)
            .also { menuTextMethods[baseClass] = it }
        check(intMethods.size in 1..2) { "菜单项 int 方法数量异常: ${baseClass.name}" }
        check(clickMethods.size == 1) { "菜单项点击方法数量异常: ${baseClass.name}" }

        val iconMethod = if (intMethods.size == 2) {
            findIconMethod(layout.context, items, intMethods) ?: intMethods.first()
        } else {
            null
        }
        val idMethod = intMethods.first { it != iconMethod }
        val clickMethod = clickMethods.single()

        val frontItems = mutableListOf<Any>()
        val backItems = mutableListOf<Any>()
        visibleEntries.forEach { entry ->
            val clickTarget = when (entry.type) {
                ChatMenuType.Any -> message.takeIf(entry.matches) ?: return@forEach
                else -> findMessageElement(message, entry.type.resolveClass(), entry.matches) ?: return@forEach
            }
            val item = ChatMenuItemFactory.create(
                baseClass = baseClass,
                message = message,
                title = entry.title,
                icon = entry.icon,
                id = entry.itemId,
                titleMethod = textMethods.title,
                identifierMethod = textMethods.identifier,
                iconMethod = iconMethod,
                idMethod = idMethod,
                clickMethod = clickMethod,
                callback = Runnable { entry.onClick(layout.context, clickTarget) },
            )
            if (entry.position == ChatMenuPosition.Front) {
                frontItems += item
            } else {
                backItems += item
            }
        }
        items.addAll(0, frontItems)
        items.addAll(backItems)
    }

    private fun findMessageElement(message: Any, elementType: Class<*>, matches: (Any) -> Boolean): Any? {
        val getMsgRecord = msgRecordMethods[message.javaClass]
            ?: message.javaClass.getMethod("getMsgRecord").also {
                msgRecordMethods[message.javaClass] = it
            }
        val msgRecord = getMsgRecord.invoke(message) ?: return null
        val getElements = elementsMethods[msgRecord.javaClass]
            ?: msgRecord.javaClass.getMethod("getElements").also {
                elementsMethods[msgRecord.javaClass] = it
            }
        val elements = getElements.invoke(msgRecord) as? Iterable<*> ?: return null
        elements.forEach { element ->
            element ?: return@forEach
            val getterKey = ElementGetterKey(element.javaClass, elementType)
            val getElement = elementGetterMethods[getterKey]
                ?: element.javaClass.methods.first {
                    it.parameterCount == 0 && elementType.isAssignableFrom(it.returnType)
                }.also {
                    elementGetterMethods[getterKey] = it
                }
            getElement.invoke(element)?.let { if (matches(it)) return it }
        }
        return null
    }

    private fun findListField(containerClass: Class<*>): Field {
        return generateSequence(containerClass) { it.superclass }
            .flatMap { it.declaredFields.asSequence() }
            .first { List::class.java.isAssignableFrom(it.type) }
            .apply { isAccessible = true }
    }

    private fun findMenuItemBaseClass(itemClass: Class<*>): Class<*> {
        var candidate = itemClass.superclass
        while (candidate != null) {
            val hasExpectedConstructor = candidate.declaredConstructors.any {
                it.parameterCount == 1 &&
                    it.parameterTypes[0].name == "com.tencent.mobileqq.aio.msg.AIOMsgItem"
            }
            if (hasExpectedConstructor && candidate.declaredMethods.any { Modifier.isAbstract(it.modifiers) }) {
                Log.info("找到 QQ 菜单项基类: ${itemClass.name} -> ${candidate.name}")
                return candidate
            }
            candidate = candidate.superclass
        }
        error("未找到 QQ 菜单项基类: ${itemClass.name}")
    }

    private fun resolveMenuTextMethods(
        items: List<Any>,
        stringMethods: List<Method>,
    ): MenuTextMethods {
        check(stringMethods.size == 2) { "菜单项标题/标识协议不明确: ${stringMethods.map { it.name }}" }
        stringMethods.forEach { it.isAccessible = true }
        // The host's own debug representation embeds its primary title. Match that
        // exact slot, not every non-empty string (subtitle/a11y/key/toString).
        val prefix = "QQCustomMenuItem{title='"
        val suffix = "', special="
        var candidates = 3
        for (item in items) {
            val description = item.toString()
            if (!description.startsWith(prefix)) continue
            val end = description.lastIndexOf(suffix)
            if (end < prefix.length) continue
            for (index in stringMethods.indices) {
                val bit = 1 shl index
                if (candidates and bit == 0) continue
                val value = (stringMethods[index].invoke(item) as? String) ?: "null"
                if (value.length != end - prefix.length ||
                    !description.regionMatches(prefix.length, value, 0, value.length)
                ) candidates = candidates and bit.inv()
            }
        }
        check(candidates == 1 || candidates == 2) {
            "无法由宿主菜单标题唯一定位 getter: ${stringMethods.map { it.name }}"
        }
        val title = if (candidates == 1) 0 else 1
        return MenuTextMethods(stringMethods[title], stringMethods[1 - title])
    }

    private fun findIconMethod(
        context: Context,
        items: List<Any>,
        methods: List<Method>,
    ): Method? {
        methods.forEach { method ->
            method.isAccessible = true
            items.forEach { item ->
                val value = runCatching { method.invoke(item) as Int }.getOrNull() ?: return@forEach
                val type = runCatching { context.resources.getResourceTypeName(value) }.getOrNull()
                if (type == "drawable" || type == "mipmap") return method
            }
        }
        return null
    }

    private fun ChatMenuType.resolveClass(): Class<*> {
        val name = className ?: error("ChatMenuType.Any 没有对应的消息元素类")
        return typeClasses.getOrPut(this) { name.toAppClass() }
    }
}
