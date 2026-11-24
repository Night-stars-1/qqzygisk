package com.qm.qqzygisk.hook.app.chat

import android.content.Context
import android.os.Looper
import com.qm.qqzygisk.hook.app.EmojiShape
import com.qm.qqzygisk.hook.app.TaskShape
import com.qm.qqzygisk.hook.app.WeChatHostDex
import com.qm.qqzygisk.hook.app.WeChatShapes
import com.qm.qqzygisk.hook.app.data.HostData.appClassLoader
import com.qm.qqzygisk.hook.app.declaredMember
import com.qm.qqzygisk.hook.app.declaredSlot
import com.qm.qqzygisk.hook.parasitic.AppParasitics
import com.qm.qqzygisk.hook.utils.HostTargetCache
import com.qm.qqzygisk.hook.utils.Log
import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * 微信原生表情发送入口。
 *
 * 注册表、导入/回读/发送、缓存路径、尺寸上限和上传队列都由 [WeChatShapes] 按形状定位，
 * 不写死混淆短名。同一宿主版本写入 `wechat-emoji.json`，下次启动直接恢复。
 * 未适配结构在发送前失败。
 */
internal object WeChatEmoticonSender {
    /**
     * 返回值表示启动、协议或尺寸检查结果：超限静态 JPEG/PNG 生成临时发送副本，
     * 超限 GIF/WEBP 直接失败。[complete] 只在原生上传与消息发送进入终态
     * （或拒绝、异常、超时）后恰好回调一次；启动失败时不会调用 [complete]。
     */
    fun send(
        file: File,
        talker: String?,
        currentTalker: () -> String,
        complete: (Result<Unit>) -> Unit,
    ): Result<Unit> = runCatching {
        check(Looper.myLooper() === Looper.getMainLooper()) { "表情发送必须在主线程启动" }
        val context = checkNotNull(AppParasitics.appContext) { "宿主 Application 尚未初始化" }
        val protocol = WeChatEmojiProtocol.resolve(appClassLoader)
        val completion = WeChatEmoticonCompletion.forLoader(appClassLoader, protocol).getOrThrow()
        val source = WeChatEmoticonFile.inspect(file)
        WeChatEmoticonSendImage.prepare(
            source,
            protocol.readSizeLimit(),
            WeChatEmoticonSendImage.directory(context),
        ).use { prepared ->
            WeChatEmoticonSubmission(NativeApi(context, protocol, completion))
                .send(prepared.source, talker, currentTalker, complete).getOrThrow()
        }
    }.recoverCatching { error ->
        throw if (error is InvocationTargetException) error.targetException else error
    }

    private class NativeApi(
        private val context: Context,
        private val protocol: WeChatEmojiProtocol,
        private val completion: WeChatEmoticonCompletion,
    ) : WeChatEmoticonApi {
        /** 协议解析不碰注册表实例；到这里才向注册表要服务，微信没就绪时原样失败。 */
        private val manager: Any by lazy {
            checkNotNull(protocol.lookup.invoke(null, protocol.apiClass)) { "微信原生表情服务尚未就绪" }
                .also { check(protocol.managerClass.isInstance(it)) { "微信原生表情服务实现已变化" } }
        }

        override fun importFile(file: WeChatEmoticonFile): ImportedWeChatEmoticon? {
            val md5 = protocol.importMethod.invoke(manager, context, file.file.absolutePath) as? String
            if (md5.isNullOrBlank()) return null
            val emoji = protocol.lookupMethod.invoke(manager, md5) ?: return null
            if (!(protocol.emojiMd5.invoke(emoji) as? String).equals(md5, ignoreCase = true)) return null
            val path = protocol.emojiPathGetter.invoke(emoji) as? String ?: return null
            return ImportedWeChatEmoticon(
                md5 = md5,
                size = (protocol.vfsLength.invoke(null, path) as Number).toLong(),
                nativeType = (protocol.emojiType.invoke(emoji) as Number).toInt(),
                readable = protocol.vfsExists.invoke(null, path) == true,
            )
        }

        override fun submit(talker: String, md5: String, complete: (Result<Unit>) -> Unit): Result<Unit> =
            completion.submit(talker, md5, complete) {
                protocol.sendMethod.invoke(manager, context, talker, md5) == true
            }
    }
}

/** 新版发送任务的反射面；旧版本没有这条链路时为 null，只观察传统上传队列。 */
internal class WeChatEmojiTask(
    val start: Method,
    val finish: Method,
    val taskTalker: Field,
    val taskEmoji: Field,
    val taskParameters: Field,
    val taskInfo: Method,
    val infoMessage: Field,
    val messageId: Field,
    val messageTalker: Field,
)

/**
 * 微信原生表情服务的反射协议（只解析，不含实例）。
 */
internal class WeChatEmojiProtocol private constructor(
    val apiClass: Class<*>,
    val lookup: Method,
    val managerClass: Class<*>,
    val importMethod: Method,
    val lookupMethod: Method,
    val sendMethod: Method,
    val emojiMd5: Method,
    val emojiType: Method,
    val emojiPathGetter: Method,
    val vfsExists: Method,
    val vfsLength: Method,
    val sizeLimit: Method,
    val queueOwner: Class<*>,
    val terminal: Method,
    val queueField: Field,
    val itemClass: Class<*>,
    val msgIdField: Field,
    val talkerField: Field,
    val emojiField: Field,
    val messageLookup: Method,
    val statusMethod: Method,
    val task: WeChatEmojiTask?,
) {
    /**
     * 读取宿主 `InputLimitSendEmotionWidth`（宽高同一上限）。默认值不能由模块假设，
     * 只在发送前向宿主查询；非正数视为协议不可用。
     */
    fun readSizeLimit(): Int {
        val value = (sizeLimit.invoke(null) as Number).toInt()
        check(value > 0) { "微信表情尺寸上限无效：$value" }
        return value
    }

    companion object {
        private const val CACHE_KEY = "wechat-emoji"
        private const val EMOJI_API_CLASS = "com.tencent.mm.api.IEmojiInfo"
        private const val EMOJI_INFO_CLASS = "com.tencent.mm.storage.emotion.EmojiInfo"

        private val memory = java.util.WeakHashMap<ClassLoader, WeChatEmojiProtocol>()
        private val failures = java.util.WeakHashMap<ClassLoader, Throwable>()

        fun resolve(loader: ClassLoader): WeChatEmojiProtocol {
            memory[loader]?.let { return it }
            synchronized(this) {
                memory[loader]?.let { return it }
                failures[loader]?.let { throw it }
                try {
                    val protocol = HostTargetCache.resolve(CACHE_KEY, loader) { targets ->
                        if (targets.cached) restore(loader, targets) else discover(loader, targets)
                    }
                    memory[loader] = protocol
                    return protocol
                } catch (error: Throwable) {
                    failures[loader] = error
                    throw error
                }
            }
        }

        private fun discover(loader: ClassLoader, targets: HostTargetCache.Targets): WeChatEmojiProtocol {
            val shape = WeChatShapes.emoji(WeChatHostDex.of(loader))
            val protocol = bind(loader, shape)
            remember(targets, protocol, shape.task != null)
            Log.info("微信表情协议形状: ${shape.manager} ${shape.importMethod}/${shape.lookupMethod}/${shape.sendMethod} task=${shape.task != null}")
            return protocol
        }

        private fun bind(loader: ClassLoader, shape: EmojiShape): WeChatEmojiProtocol {
            val emojiApi = Class.forName(EMOJI_API_CLASS, false, loader)
            val emojiInfo = Class.forName(EMOJI_INFO_CLASS, false, loader)
            val apiClass = Class.forName(shape.api, false, loader)
            val service = Class.forName(shape.service, false, loader)
            check(apiClass.isInterface && apiClass.interfaces.singleOrNull() == service) {
                "微信表情服务接口 ${apiClass.name} 的标记父接口已变化"
            }
            val registry = Class.forName(shape.registryOwner, false, loader)
            val lookup = registry.declaredMember(shape.registryMethod, arrayOf(Class::class.java))
            check(
                Modifier.isStatic(lookup.modifiers) && lookup.returnType == service,
            ) { "微信表情服务注册表 ${registry.name}.${lookup.name} 签名已变化" }
            val managerClass = Class.forName(shape.manager, false, loader)
            check(apiClass.isAssignableFrom(managerClass)) { "微信表情服务实现 ${managerClass.name} 已变化" }
            val importMethod = managerClass.declaredMember(shape.importMethod, arrayOf(Context::class.java, String::class.java))
            val lookupMethod = managerClass.declaredMember(shape.lookupMethod, arrayOf(String::class.java))
            val sendMethod = managerClass.declaredMember(
                shape.sendMethod,
                arrayOf(Context::class.java, String::class.java, String::class.java),
            )
            check(
                !Modifier.isStatic(importMethod.modifiers) && importMethod.returnType == String::class.java &&
                    !Modifier.isStatic(lookupMethod.modifiers) && lookupMethod.returnType == emojiApi &&
                    !Modifier.isStatic(sendMethod.modifiers) && sendMethod.returnType == java.lang.Boolean.TYPE,
            ) { "微信原生表情服务导入、回读或发送签名已变化" }
            val limitClass = Class.forName(shape.limitOwner, false, loader)
            val sizeLimit = limitClass.declaredMember(shape.limitMethod, emptyArray())
            check(Modifier.isStatic(sizeLimit.modifiers) && sizeLimit.returnType == Integer.TYPE) {
                "微信 ${shape.limitOwner}.${sizeLimit.name} 签名已变化，期望 public static int"
            }
            val emojiMd5 = emojiApi.getMethod("getMd5")
            check(!Modifier.isStatic(emojiMd5.modifiers) && emojiMd5.returnType == String::class.java) {
                "微信 $EMOJI_API_CLASS.getMd5 签名已变化"
            }
            val emojiType = emojiApi.getMethod("getType")
            check(!Modifier.isStatic(emojiType.modifiers) && emojiType.returnType == Integer.TYPE) {
                "微信 $EMOJI_API_CLASS.getType 签名已变化: ${emojiType.returnType.name}"
            }
            val emojiPathGetter = runCatching { emojiApi.getMethod(shape.pathMethod) }
                .getOrElse { emojiInfo.declaredMember(shape.pathMethod, emptyArray()) }
            check(!Modifier.isStatic(emojiPathGetter.modifiers) && emojiPathGetter.parameterTypes.isEmpty() && emojiPathGetter.returnType == String::class.java) {
                "微信 $EMOJI_API_CLASS.${shape.pathMethod}() 形状已变化"
            }
            val vfs = Class.forName(shape.vfsClass, false, loader)
            val exists = vfs.declaredMember(shape.vfsExists, arrayOf(String::class.java))
            val length = vfs.declaredMember(shape.vfsLength, arrayOf(String::class.java))
            check(
                Modifier.isStatic(exists.modifiers) && Modifier.isStatic(length.modifiers) &&
                    exists.returnType == java.lang.Boolean.TYPE && length.returnType == java.lang.Long.TYPE,
            ) { "微信 ${shape.vfsClass} 的存在判断或长度签名已变化" }
            val queueOwner = Class.forName(shape.queueOwner, false, loader)
            val itemClass = Class.forName(shape.itemClass, false, loader)
            val messageClass = Class.forName(shape.messageClass, false, loader)
            val queueField = queueOwner.declaredSlot(shape.queueField)
            check(!Modifier.isStatic(queueField.modifiers) && List::class.java.isAssignableFrom(queueField.type)) {
                "微信 ${queueOwner.name}.${queueField.name} 不是发送队列"
            }
            val terminal = queueOwner.declaredMember(shape.terminalMethod, arrayOf(Integer.TYPE, Integer.TYPE))
            check(!Modifier.isStatic(terminal.modifiers) && terminal.returnType == Void.TYPE) {
                "微信 ${queueOwner.name}.${terminal.name}(int,int) 形状已变化"
            }
            val msgId = itemClass.declaredSlot(shape.msgField)
            val talker = itemClass.declaredSlot(shape.talkerField)
            val emoji = itemClass.declaredSlot(shape.emojiField)
            check(!Modifier.isStatic(msgId.modifiers) && msgId.type == java.lang.Long.TYPE) {
                "微信 ${itemClass.name}.${msgId.name} 不是 long"
            }
            check(!Modifier.isStatic(talker.modifiers) && talker.type == String::class.java) {
                "微信 ${itemClass.name}.${talker.name} 不是 String"
            }
            check(!Modifier.isStatic(emoji.modifiers) && emoji.type == emojiInfo && emojiApi.isAssignableFrom(emoji.type)) {
                "微信 ${itemClass.name}.${emoji.name} 不是 EmojiInfo"
            }
            val messageLookup = Class.forName(shape.messageLookupOwner, false, loader)
                .declaredMember(shape.messageLookupMethod, arrayOf(String::class.java, java.lang.Long.TYPE))
            check(Modifier.isStatic(messageLookup.modifiers) && messageLookup.returnType == messageClass) {
                "微信 ${shape.messageLookupOwner}.${messageLookup.name} 形状已变化"
            }
            val status = messageClass.declaredMember(shape.statusMethod, emptyArray())
            check(!Modifier.isStatic(status.modifiers) && status.returnType == Integer.TYPE) {
                "微信 ${messageClass.name}.${status.name} 形状已变化"
            }
            return WeChatEmojiProtocol(
                apiClass = apiClass,
                lookup = lookup,
                managerClass = managerClass,
                importMethod = importMethod,
                lookupMethod = lookupMethod,
                sendMethod = sendMethod,
                emojiMd5 = emojiMd5,
                emojiType = emojiType,
                emojiPathGetter = emojiPathGetter,
                vfsExists = exists,
                vfsLength = length,
                sizeLimit = sizeLimit,
                queueOwner = queueOwner,
                terminal = terminal,
                queueField = queueField,
                itemClass = itemClass,
                msgIdField = msgId,
                talkerField = talker,
                emojiField = emoji,
                messageLookup = messageLookup,
                statusMethod = status,
                task = shape.task?.let { bindTask(loader, it, emojiApi) },
            )
        }

        private fun bindTask(loader: ClassLoader, shape: TaskShape, emojiApi: Class<*>): WeChatEmojiTask {
            val params = Class.forName(shape.paramsClass, false, loader)
            val task = Class.forName(shape.taskClass, false, loader)
            val info = Class.forName(shape.infoClass, false, loader)
            val identity = Class.forName(shape.identityClass, false, loader)
            val start = Class.forName(shape.startOwner, false, loader)
                .declaredMember(shape.startMethod, arrayOf(params))
            check(!Modifier.isStatic(start.modifiers) && start.returnType == java.lang.Boolean.TYPE) {
                "微信表情任务启动 ${start.declaringClass.name}.${start.name} 签名已变化"
            }
            val finish = task.declaredMember(shape.finishMethod, emptyArray())
            check(!Modifier.isStatic(finish.modifiers)) { "微信表情任务完成 ${task.name}.${finish.name} 不是实例方法" }
            val taskInfo = task.declaredMember(shape.infoMethod, emptyArray())
            check(!Modifier.isStatic(taskInfo.modifiers) && taskInfo.returnType == info) {
                "微信表情任务消息 ${task.name}.${taskInfo.name} 形状已变化"
            }
            return WeChatEmojiTask(
                start = start,
                finish = finish,
                taskTalker = params.declaredSlot(shape.talkerField).also {
                    check(!Modifier.isStatic(it.modifiers) && it.type == String::class.java)
                },
                taskEmoji = params.declaredSlot(shape.emojiField).also {
                    check(!Modifier.isStatic(it.modifiers) && it.type == emojiApi)
                },
                taskParameters = task.declaredSlot(shape.paramsField).also {
                    check(!Modifier.isStatic(it.modifiers) && it.type == params)
                },
                taskInfo = taskInfo,
                infoMessage = info.declaredSlot(shape.infoMessageField).also {
                    check(!Modifier.isStatic(it.modifiers) && it.type == identity)
                },
                messageId = identity.declaredSlot(shape.messageIdField).also {
                    check(!Modifier.isStatic(it.modifiers) && it.type == java.lang.Long.TYPE)
                },
                messageTalker = identity.declaredSlot(shape.messageTalkerField).also {
                    check(!Modifier.isStatic(it.modifiers) && it.type == String::class.java)
                },
            )
        }

        private fun remember(targets: HostTargetCache.Targets, protocol: WeChatEmojiProtocol, hasTask: Boolean) {
            targets.type("api") { protocol.apiClass }
            targets.method("lookup") { protocol.lookup }
            targets.type("manager") { protocol.managerClass }
            targets.method("import") { protocol.importMethod }
            targets.method("lookupEmoji") { protocol.lookupMethod }
            targets.method("send") { protocol.sendMethod }
            targets.method("md5") { protocol.emojiMd5 }
            targets.method("type") { protocol.emojiType }
            targets.method("path") { protocol.emojiPathGetter }
            targets.method("vfsExists") { protocol.vfsExists }
            targets.method("vfsLength") { protocol.vfsLength }
            targets.method("limit") { protocol.sizeLimit }
            targets.type("queueOwner") { protocol.queueOwner }
            targets.method("terminal") { protocol.terminal }
            targets.field("queue") { protocol.queueField }
            targets.type("item") { protocol.itemClass }
            targets.field("msg") { protocol.msgIdField }
            targets.field("talker") { protocol.talkerField }
            targets.field("emoji") { protocol.emojiField }
            targets.method("messageLookup") { protocol.messageLookup }
            targets.method("status") { protocol.statusMethod }
            if (!hasTask) return
            val task = checkNotNull(protocol.task)
            targets.method("taskStart") { task.start }
            targets.method("taskFinish") { task.finish }
            targets.field("taskTalker") { task.taskTalker }
            targets.field("taskEmoji") { task.taskEmoji }
            targets.field("taskParameters") { task.taskParameters }
            targets.method("taskInfo") { task.taskInfo }
            targets.field("infoMessage") { task.infoMessage }
            targets.field("messageId") { task.messageId }
            targets.field("messageTalker") { task.messageTalker }
        }

        private fun restore(loader: ClassLoader, targets: HostTargetCache.Targets): WeChatEmojiProtocol {
            val task = if (!targets.values.has("taskStart")) null else WeChatEmojiTask(
                start = targets.method("taskStart"),
                finish = targets.method("taskFinish"),
                taskTalker = targets.field("taskTalker"),
                taskEmoji = targets.field("taskEmoji"),
                taskParameters = targets.field("taskParameters"),
                taskInfo = targets.method("taskInfo"),
                infoMessage = targets.field("infoMessage"),
                messageId = targets.field("messageId"),
                messageTalker = targets.field("messageTalker"),
            )
            return WeChatEmojiProtocol(
                apiClass = targets.type("api"),
                lookup = targets.method("lookup"),
                managerClass = targets.type("manager"),
                importMethod = targets.method("import"),
                lookupMethod = targets.method("lookupEmoji"),
                sendMethod = targets.method("send"),
                emojiMd5 = targets.method("md5"),
                emojiType = targets.method("type"),
                emojiPathGetter = targets.method("path"),
                vfsExists = targets.method("vfsExists"),
                vfsLength = targets.method("vfsLength"),
                sizeLimit = targets.method("limit"),
                queueOwner = targets.type("queueOwner"),
                terminal = targets.method("terminal"),
                queueField = targets.field("queue"),
                itemClass = targets.type("item"),
                msgIdField = targets.field("msg"),
                talkerField = targets.field("talker"),
                emojiField = targets.field("emoji"),
                messageLookup = targets.method("messageLookup"),
                statusMethod = targets.method("status"),
                task = task,
            ).also {
                check(it.apiClass.classLoader === loader || it.apiClass.classLoader == null)
            }
        }
    }
}

