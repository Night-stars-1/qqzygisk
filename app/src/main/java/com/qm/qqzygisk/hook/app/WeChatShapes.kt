package com.qm.qqzygisk.hook.app

import com.qm.qqzygisk.hook.utils.ACC_ABSTRACT
import com.qm.qqzygisk.hook.utils.ACC_INTERFACE
import com.qm.qqzygisk.hook.utils.DexBody
import com.qm.qqzygisk.hook.utils.DexField
import com.qm.qqzygisk.hook.utils.DexId
import com.qm.qqzygisk.hook.utils.HostDex

/**
 * 微信设置入口、表情按钮和原生表情协议的形状定位。
 *
 * 不记录混淆后的类名或方法名。每一步 0 个或多个命中都失败，禁止取第一个凑合。
 * 已在 8.0.78（3180）、8.0.79（3183 与另一分发包）和 8.0.80（3220）上核对同一套规则能唯一命中。
 */
internal object WeChatShapes {
    private const val SETTINGS_ROOT =
        "com.tencent.mm.plugin.setting.ui.setting_new.settings.SettingGroupMain"
    private const val SETTINGS_PACKAGE = "com.tencent.mm.plugin.setting.ui.setting_new.settings"
    private val NAMED_TEMPLATES = listOf(
        "com.tencent.mm.plugin.setting.ui.setting_new.settings.friend_privacy.SettingActivityItemNewFriendCommonSayHi",
        "com.tencent.mm.plugin.setting.ui.setting_new.settings.friend_privacy.SettingActivityItemCommonSayHi",
    )
    private const val CHAT_FOOTER = "com.tencent.mm.pluginsdk.ui.chat.ChatFooter"
    private const val EMOJI_BUTTON = "chat_emoji_button"
    private const val FEATURE = "com.tencent.mm.feature.emoji"
    private const val EMOJI_API = "com.tencent.mm.api.IEmojiInfo"
    private const val EMOJI_INFO = "com.tencent.mm.storage.emotion.EmojiInfo"
    private const val MSG_ID = "com.tencent.mm.plugin.msg.MsgIdTalker"
    private const val PATH_LOG = "[cpan] get icon path failed. product id and md5 are null."
    private const val LIMIT_NAME = "InputLimitSendEmotionWidth"
    private const val CONTEXT = "android.content.Context"
    private const val STRING = "java.lang.String"
    private const val VIEW = "android.view.View"
    private const val CLASS_NAME = "java.lang.Class"
    private const val LIST = "java.util.List"
    private val CLICK = listOf(CONTEXT, VIEW, "int")

    fun settings(host: HostDex): SettingShape {
        val chain = ArrayList<String>()
        var current: String? = SETTINGS_ROOT
        while (current != null) {
            chain += current
            current = host.superName(current)
        }
        check(chain.size > 1) { "微信 $SETTINGS_ROOT 不在当前安装包里" }
        val builders = ArrayList<DexId>()
        for (base in chain) {
            host.forEachMethod(listOf(base, LIST), null) { ident ->
                if (ident.name == "<init>" || ident.returnType == "void") return@forEachMethod
                declaredInstance(host, ident)?.let { builders += it }
            }
        }
        val build = only(builders, "微信设置构建器") { "${it.owner}.${it.name}(${it.parameterTypes[0]})" }
        val item = build.parameterTypes[0]
        val methods = instanceMethods(host, item)
        val key = only(
            methods.filter { it.id.isAbstract() && it.id.parameterTypes.isEmpty() && it.id.returnType == STRING },
            "微信设置项键",
        ) { it.id.name }
        val title = only(methods.filter { isTitle(it, item) }, "微信设置项标题") { it.id.name }
        val resource = only(methods.filter { isSummaryResource(it) }, "微信设置项摘要资源") { it.id.name }
        val summary = only(
            methods.filter { isSummary(it, item, resource.id.name) },
            "微信设置项摘要",
        ) { it.id.name }
        val iconNames = LinkedHashSet<String>()
        val integerNames = methods
            .filter { it.id.parameterTypes.isEmpty() && it.id.returnType == "java.lang.Integer" }
            .map { it.id.name }
            .toSet()
        for (body in methods) {
            if (body.id.name == resource.id.name) continue
            for (call in body.invokes) {
                if (call.owner == item && call.parameterTypes.isEmpty() && call.name in integerNames) {
                    iconNames += call.name
                }
            }
        }
        val icon = only(iconNames.toList(), "微信设置项图标") { it }
        val drawables = methods
            .filter { it.id.parameterTypes.isEmpty() && it.id.returnType == "android.graphics.drawable.Drawable" }
            .map { it.id.name }
        val drawable = when (drawables.size) {
            0 -> null
            1 -> drawables[0]
            else -> error("微信设置项图标 Drawable 无法唯一确定(${drawables.size} 个)")
        }
        val click = only(
            methods.filter { it.id.isAbstract() && it.id.returnType == "void" && it.id.parameterTypes == CLICK },
            "微信设置项点击",
        ) { it.id.name }
        val template = template(host, item)
        val visibles = host.methods(template).filter {
            !it.id.isStatic() && !it.id.isAbstract() && it.id.parameterTypes.isEmpty() && it.id.returnType == "boolean"
        }
        val visible = only(visibles, "微信设置项可见性") { it.id.name }.id.name
        check(hierarchy(host, template).any { type ->
            host.methods(type).any { it.id.name == click.id.name && it.id.parameterTypes == CLICK }
        }) { "微信设置项模板没有点击方法 ${click.id.name}" }
        check(hierarchy(host, item).any { type ->
            host.methods(type).any { it.id.name == "onCreate" && it.id.parameterTypes == listOf("android.os.Bundle") }
        }) { "微信设置项没有 onCreate(Bundle)" }
        val getters = ArrayList<String>()
        for (type in hierarchy(host, item)) {
            for (body in host.methods(type)) {
                if (body.id.isStatic() || body.id.parameterTypes.isNotEmpty()) continue
                if (host.isActivity(body.id.returnType)) getters += body.id.name
            }
        }
        check(getters.isNotEmpty()) { "微信设置项没有 Activity getter" }
        return SettingShape(
            builderOwner = build.owner,
            builderName = build.name,
            builderParameter = item,
            builderReturn = build.returnType,
            itemBase = item,
            template = template,
            key = key.id.name,
            title = title.id.name,
            summary = summary.id.name,
            summaryResource = resource.id.name,
            icon = icon,
            drawable = drawable,
            visible = visible,
            click = click.id.name,
            activityGetters = getters,
        )
    }

    fun button(host: HostDex): ButtonShape {
        check(host.hasClass(CHAT_FOOTER)) { "微信 $CHAT_FOOTER 不在当前安装包里" }
        val hits = host.methods(CHAT_FOOTER).filter { body ->
            body.complete && EMOJI_BUTTON in body.strings && !body.id.isStatic()
        }
        val method = only(hits.filter { it.id.parameterTypes.isEmpty() }, "微信表情按钮初始化") { it.id.name }
        val marker = method.steps.indexOfFirst { it is com.qm.qqzygisk.hook.utils.DexStep.Text && it.value == EMOJI_BUTTON }
        check(marker >= 0) { "微信 ChatFooter 没有表情按钮标记" }
        val image = method.steps.take(marker).mapNotNull { step ->
            val field = (step as? com.qm.qqzygisk.hook.utils.DexStep.Field)?.field ?: return@mapNotNull null
            if (field.write || field.owner != CHAT_FOOTER || !host.isImageView(field.type)) null else field
        }
        val field = image.lastOrNull() ?: error("微信 ChatFooter 没有表情按钮字段")
        return ButtonShape(CHAT_FOOTER, method.id.name, field.name, field.type)
    }

    fun emoji(host: HostDex): EmojiShape {
        val managers = ArrayList<ManagerCandidate>()
        for (name in host.classNames(FEATURE, direct = true)) {
            if (host.accessOf(name) and ACC_INTERFACE != 0) continue
            val methods = host.methods(name).filter { it.complete && !it.id.isBridge() }
            val imports = methods.filter { signature(it, listOf(CONTEXT, STRING), STRING) }
            val lookups = methods.filter { signature(it, listOf(STRING), EMOJI_API) }
            val sends = methods.filter { signature(it, listOf(CONTEXT, STRING, STRING), "boolean") }
            if (imports.size != 1 || lookups.isEmpty() || sends.size != 1) continue
            val shapes = lookups.map { it.invokes.joinToString(";") { call -> call.describe() } }.toSet()
            check(shapes.size == 1) { "微信 $name 表情回读实现不一致" }
            managers += ManagerCandidate(name, imports[0].id, lookups.minBy { it.id.name }.id, sends[0].id)
        }
        val manager = only(managers, "微信表情服务实现") { it.owner }
        val api = only(
            host.interfacesOf(manager.owner).filter { it.startsWith("$FEATURE.api.") },
            "微信表情服务接口",
        ) { it }
        val service = only(host.interfacesOf(api), "微信表情服务父接口") { it }
        val called = LinkedHashMap<String, DexId>()
        for (body in host.methods(manager.owner)) {
            for (call in body.invokes) {
                if (call.parameterTypes == listOf(CLASS_NAME) && call.returnType == service) {
                    called.putIfAbsent(call.describe(), call)
                }
            }
        }
        val registries = LinkedHashMap<String, DexId>()
        for (call in called.values) {
            val declared = host.methods(call.owner).firstOrNull {
                it.id.name == call.name && it.id.parameterTypes == call.parameterTypes && it.id.returnType == call.returnType
            }?.id ?: call
            if (declared.isStatic()) registries.putIfAbsent(declared.describe(), declared)
        }
        val registry = only(registries.values.toList(), "微信表情服务注册表") { it.describe() }
        val pathMethods = host.methods(EMOJI_INFO).filter {
            it.complete && !it.id.isStatic() && it.id.parameterTypes.isEmpty() &&
                it.id.returnType == STRING && PATH_LOG in it.strings
        }
        val pathNames = pathMethods.map { it.id.name }.toSet()
        val paths = pathMethods.filter { body ->
            body.invokes.none { call ->
                call.owner == EMOJI_INFO && call.parameterTypes.isEmpty() && call.name in pathNames
            }
        }
        val path = only(paths, "微信表情路径") { it.id.name }
        val lengths = LinkedHashMap<String, DexId>()
        for (body in host.methods(EMOJI_INFO)) {
            if (!body.complete) continue
            for (call in body.invokes) {
                if (call.owner.startsWith("com.tencent.mm.vfs.") && call.parameterTypes == listOf(STRING) && call.returnType == "long") {
                    lengths.putIfAbsent(call.describe(), call)
                }
            }
        }
        val length = only(lengths.values.toList(), "微信表情文件长度") { it.describe() }
        val created = path.invokes.filter { it.owner == length.owner && it.returnType == "boolean" }.map { it.name }.toSet()
        val exists = LinkedHashMap<String, DexId>()
        for (body in host.methods(EMOJI_INFO)) {
            if (!body.complete || body.invokes.none { it.owner == EMOJI_INFO && it.name == path.id.name && it.parameterTypes.isEmpty() }) {
                continue
            }
            for (call in body.invokes) {
                if (call.owner == length.owner && call.parameterTypes == listOf(STRING) &&
                    call.returnType == "boolean" && call.name !in created
                ) {
                    exists.putIfAbsent(call.name, call)
                }
            }
        }
        val exist = only(exists.values.toList(), "微信表情文件存在判断") { it.describe() }
        val limits = host.methodsReferencing(LIMIT_NAME, emptyList(), "int", staticOnly = true)
        val limit = only(limits, "微信表情尺寸上限") { it.describe() }
        val queue = queue(host, STRING)
        val status = only(statusGetters(host, queue.lookup.returnType, queue.setter), "微信消息状态读取") { it }
        val task = task(host, EMOJI_API, STRING)
        return EmojiShape(
            manager = manager.owner,
            importMethod = manager.import.name,
            lookupMethod = manager.lookup.name,
            sendMethod = manager.send.name,
            api = api,
            service = service,
            registryOwner = registry.owner,
            registryMethod = registry.name,
            pathMethod = path.id.name,
            vfsClass = length.owner,
            vfsLength = length.name,
            vfsExists = exist.name,
            limitOwner = limit.owner,
            limitMethod = limit.name,
            queueOwner = queue.owner,
            terminalMethod = queue.terminal.id.name,
            queueField = queue.list.name,
            itemClass = queue.itemOwner,
            msgField = queue.msg.name,
            talkerField = queue.talker.name,
            emojiField = queue.emojiField,
            messageLookupOwner = queue.lookup.owner,
            messageLookupMethod = queue.lookup.name,
            messageClass = queue.lookup.returnType,
            statusMethod = status,
            task = task,
        )
    }

    private fun queue(host: HostDex, string: String): QueueShape {
        val queues = ArrayList<QueueShape>()
        for (owner in host.classNames(FEATURE, direct = true)) {
            val terminals = host.methods(owner).filter { body ->
                body.complete && !body.id.isStatic() && !body.id.isBridge() &&
                    body.id.parameterTypes == listOf("int", "int") && body.id.returnType == "void" &&
                    body.invokes.any { it.parameterTypes == listOf(string, "long") } &&
                    body.fields.any { it.owner == owner && it.type == LIST }
            }
            if (terminals.size != 1) continue
            val terminal = terminals[0]
            val lookupAt = terminal.steps.indexOfFirst { step ->
                val call = (step as? com.qm.qqzygisk.hook.utils.DexStep.Call)?.method
                call != null && call.parameterTypes == listOf(string, "long")
            }
            if (lookupAt <= 0) continue
            val reads = terminal.steps.take(lookupAt).mapNotNull { step ->
                val field = (step as? com.qm.qqzygisk.hook.utils.DexStep.Field)?.field
                if (field != null && !field.write && !field.static) field else null
            }
            val lists = reads.filter { it.owner == owner && it.type == LIST }
            val itemReads = reads.filter { it.owner != owner }
            val owners = itemReads.map { it.owner }.distinct()
            if (owners.size != 1 || lists.isEmpty()) continue
            val itemOwner = owners[0]
            val msg = itemReads.lastOrNull { it.type == "long" } ?: continue
            val talker = itemReads.lastOrNull { it.type == string } ?: continue
            val emojiFields = ArrayList<DexField>()
            host.forEachField(itemOwner, EMOJI_INFO) { emojiFields += it }
            if (emojiFields.size != 1) continue
            val lookup = (terminal.steps[lookupAt] as com.qm.qqzygisk.hook.utils.DexStep.Call).method
            val setters = LinkedHashMap<String, DexId>()
            for (call in terminal.invokes) {
                if (call.parameterTypes == listOf("int") && call.returnType == "void" &&
                    (call.owner == lookup.returnType || call.owner.startsWith("com.tencent.mm.storage."))
                ) {
                    setters.putIfAbsent(call.describe(), call)
                }
            }
            if (setters.size != 1) continue
            queues += QueueShape(
                owner, terminal, lists.last(), itemOwner, msg, talker, emojiFields[0].name, lookup, setters.values.first(),
            )
        }
        return only(queues, "微信表情上传队列") { it.owner }
    }

    private fun statusGetters(host: HostDex, message: String, setter: DexId): List<String> {
        val names = hierarchy(host, message)
        val getters = ArrayList<String>()
        val seen = HashSet<String>()
        for (name in names) {
            for (body in host.methods(name)) {
                if (body.id.name == setter.name && body.id.parameterTypes == setter.parameterTypes) continue
                val found = ArrayList<String>()
                body.steps.forEachIndexed { index, step ->
                    val call = (step as? com.qm.qqzygisk.hook.utils.DexStep.Call)?.method ?: return@forEachIndexed
                    if (call.name != setter.name || call.parameterTypes != listOf("int")) return@forEachIndexed
                    var previous: DexId? = null
                    for (earlier in body.steps.take(index)) {
                        val earlierCall = (earlier as? com.qm.qqzygisk.hook.utils.DexStep.Call)?.method
                        if (earlierCall != null) previous = earlierCall
                    }
                    val prior = previous
                    if (prior != null && prior.parameterTypes.isEmpty() && prior.returnType == "int" && prior.name != "getType") {
                        found += prior.name
                    }
                }
                if (found.size == 1 && seen.add(found[0])) getters += found[0]
            }
        }
        return getters
    }

    private fun task(host: HostDex, emojiApi: String, string: String): TaskShape? {
        val params = ArrayList<Pair<String, DexBody>>()
        for (name in host.classNames(FEATURE, direct = true)) {
            for (body in host.methods(name)) {
                if (body.id.name == "<init>" && body.id.parameterTypes == listOf(string, emojiApi, "int")) {
                    params += name to body
                }
            }
        }
        if (params.isEmpty()) return null
        val (name, ctor) = only(params, "微信表情发送参数") { it.first }
        val emojiAt = ctor.steps.indexOfFirst { step ->
            val field = (step as? com.qm.qqzygisk.hook.utils.DexStep.Field)?.field
            field != null && field.write && !field.static && field.owner == name && field.type == emojiApi
        }
        val talkerSteps = if (emojiAt < 0) ctor.steps else ctor.steps.take(emojiAt)
        val talkers = talkerSteps.mapNotNull { step ->
            val field = (step as? com.qm.qqzygisk.hook.utils.DexStep.Field)?.field
            if (field != null && field.write && !field.static && field.owner == name && field.type == string) field.name else null
        }.distinct()
        val emojis = ctor.fields.filter { it.write && !it.static && it.owner == name && it.type == emojiApi }
            .map { it.name }.distinct()
        val talker = only(talkers, "微信表情发送参数里的会话") { it }
        val emoji = only(emojis, "微信表情发送参数里的表情") { it }
        val starts = ArrayList<DexId>()
        host.forEachMethod(listOf(name), "boolean") { ident ->
            if (ident.name == "<init>") return@forEachMethod
            declaredInstance(host, ident)?.let { starts += it }
        }
        val start = only(starts, "微信表情任务启动") { it.describe() }
        val parameterFields = ArrayList<DexField>()
        host.forEachField(null, name) { field ->
            val ownsFinish = host.methods(field.owner).any { body ->
                body.complete && !body.id.isStatic() && body.id.parameterTypes.isEmpty() &&
                    body.invokes.any { it.name == "getMsgId" && it.parameterTypes.isEmpty() }
            }
            if (ownsFinish) parameterFields += field
        }
        val parameterField = only(parameterFields, "微信表情发送任务字段") { "${it.owner}.${it.name}" }
        val taskName = parameterField.owner
        val taskMethods = host.methods(taskName).filter { it.complete && !it.id.isStatic() && it.id.parameterTypes.isEmpty() }
        val finish = only(
            taskMethods.filter { body -> body.invokes.any { it.name == "getMsgId" && it.parameterTypes.isEmpty() } },
            "微信表情任务完成",
        ) { it.id.name }
        val infos = taskMethods.mapNotNull { body ->
            val fields = ArrayList<DexField>()
            host.forEachField(body.id.returnType, MSG_ID) { fields += it }
            if (fields.size == 1) body to fields[0] else null
        }
        val (info, identity) = only(infos, "微信表情任务消息") { it.first.id.name }
        val messageIds = ArrayList<DexField>()
        val messageTalkers = ArrayList<DexField>()
        host.forEachField(MSG_ID, "long") { messageIds += it }
        host.forEachField(MSG_ID, string) { messageTalkers += it }
        val messageId = only(messageIds, "微信 MsgIdTalker 消息号") { it.name }
        val messageTalker = only(messageTalkers, "微信 MsgIdTalker 会话") { it.name }
        return TaskShape(
            paramsClass = name,
            talkerField = talker,
            emojiField = emoji,
            startOwner = start.owner,
            startMethod = start.name,
            taskClass = taskName,
            paramsField = parameterField.name,
            finishMethod = finish.id.name,
            infoMethod = info.id.name,
            infoClass = info.id.returnType,
            infoMessageField = identity.name,
            identityClass = MSG_ID,
            messageIdField = messageId.name,
            messageTalkerField = messageTalker.name,
        )
    }

    private fun template(host: HostDex, itemBase: String): String {
        val named = NAMED_TEMPLATES.filter { isConcreteItem(host, it, itemBase) && declaresBoolean(host, it) }
        if (named.isNotEmpty()) return named[0]
        return only(
            host.classNames(SETTINGS_PACKAGE).filter { isConcreteItem(host, it, itemBase) && declaresBoolean(host, it) },
            "微信设置项模板",
        ) { it }
    }

    private fun isConcreteItem(host: HostDex, name: String, itemBase: String): Boolean {
        if (!host.hasClass(name)) return false
        val access = host.accessOf(name)
        if (access and (ACC_ABSTRACT or ACC_INTERFACE) != 0) return false
        if (name != itemBase && !host.isSubclass(name, itemBase)) return false
        return host.methods(name).any { body ->
            body.id.name == "<init>" && body.id.parameterTypes.size == 1 && host.isActivity(body.id.parameterTypes[0])
        }
    }

    private fun declaresBoolean(host: HostDex, name: String): Boolean = host.methods(name).any {
        !it.id.isStatic() && it.id.parameterTypes.isEmpty() && it.id.returnType == "boolean"
    }

    private fun isTitle(body: DexBody, item: String): Boolean =
        !body.id.isAbstract() && body.id.parameterTypes.isEmpty() && body.id.returnType == STRING &&
            body.invokes.any { it.name == "getString" } &&
            body.invokes.any { it.parameterTypes.isEmpty() && it.returnType == "int" && it.owner == item }

    private fun isSummaryResource(body: DexBody): Boolean =
        !body.id.isAbstract() && body.id.parameterTypes.isEmpty() && body.id.returnType == STRING &&
            body.invokes.any { it.name == "intValue" } && body.invokes.any { it.name == "getString" }

    private fun isSummary(body: DexBody, item: String, resource: String): Boolean =
        !body.id.isAbstract() && body.id.parameterTypes.isEmpty() && body.id.returnType == STRING &&
            body.invokes.any { it.owner == item && it.name == resource } &&
            body.invokes.none { it.name == "getString" }

    private fun signature(body: DexBody, params: List<String>, returnType: String): Boolean =
        !body.id.isStatic() && body.id.parameterTypes == params && body.id.returnType == returnType

    private fun declaredInstance(host: HostDex, ident: DexId): DexId? =
        host.methods(ident.owner).firstOrNull { body ->
            body.id.name == ident.name && body.id.parameterTypes == ident.parameterTypes &&
                body.id.returnType == ident.returnType && !body.id.isStatic() && !body.id.isBridge()
        }?.id

    private fun instanceMethods(host: HostDex, name: String): List<DexBody> =
        host.methods(name).filter { !it.id.isStatic() && !it.id.isBridge() && it.id.name != "<init>" }

    private fun hierarchy(host: HostDex, start: String): List<String> {
        val names = ArrayList<String>()
        var current: String? = start
        val seen = HashSet<String>()
        while (current != null && seen.add(current)) {
            names += current
            current = host.superName(current)
        }
        return names
    }

    private fun <T> only(items: List<T>, label: String, describe: (T) -> String): T {
        val chosen = ArrayList<T>()
        val seen = HashSet<String>()
        for (item in items) {
            val key = describe(item)
            if (seen.add(key)) chosen += item
        }
        if (chosen.size == 1) return chosen[0]
        val preview = chosen.take(8).joinToString(" | ") { describe(it) }
        error("$label 无法唯一确定(${chosen.size} 个): $preview")
    }

    private class ManagerCandidate(val owner: String, val import: DexId, val lookup: DexId, val send: DexId)

    private class QueueShape(
        val owner: String,
        val terminal: DexBody,
        val list: DexField,
        val itemOwner: String,
        val msg: DexField,
        val talker: DexField,
        val emojiField: String,
        val lookup: DexId,
        val setter: DexId,
    )
}

internal data class SettingShape(
    val builderOwner: String,
    val builderName: String,
    val builderParameter: String,
    val builderReturn: String,
    val itemBase: String,
    val template: String,
    val key: String,
    val title: String,
    val summary: String,
    val summaryResource: String,
    val icon: String,
    val drawable: String?,
    val visible: String,
    val click: String,
    val activityGetters: List<String>,
)

internal data class ButtonShape(
    val owner: String,
    val method: String,
    val field: String,
    val fieldType: String,
)

internal data class EmojiShape(
    val manager: String,
    val importMethod: String,
    val lookupMethod: String,
    val sendMethod: String,
    val api: String,
    val service: String,
    val registryOwner: String,
    val registryMethod: String,
    val pathMethod: String,
    val vfsClass: String,
    val vfsLength: String,
    val vfsExists: String,
    val limitOwner: String,
    val limitMethod: String,
    val queueOwner: String,
    val terminalMethod: String,
    val queueField: String,
    val itemClass: String,
    val msgField: String,
    val talkerField: String,
    val emojiField: String,
    val messageLookupOwner: String,
    val messageLookupMethod: String,
    val messageClass: String,
    val statusMethod: String,
    val task: TaskShape?,
)

internal data class TaskShape(
    val paramsClass: String,
    val talkerField: String,
    val emojiField: String,
    val startOwner: String,
    val startMethod: String,
    val taskClass: String,
    val paramsField: String,
    val finishMethod: String,
    val infoMethod: String,
    val infoClass: String,
    val infoMessageField: String,
    val identityClass: String,
    val messageIdField: String,
    val messageTalkerField: String,
)
