package com.qm.qqzygisk.hook.app.chat

import android.content.Context
import com.qm.qqzygisk.hook.app.data.HostData.appClassLoader
import com.qm.qqzygisk.hook.parasitic.AppParasitics
import com.qm.qqzygisk.hook.utils.Log
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * 微信 8.0.79（code3182）：EmojiMgrImpl.importEmojiFromFile -> EmojiMgrImpl.sendEmoji。
 *
 * 反射合同：标记接口 `com.tencent.mm.feature.emoji.api.z5` 的唯一直接父接口决定注册表——
 * 本包是 `kj5.m` + `kj5.n0.c(Class)`，旧分发变体是 `ni5.m` + `ni5.n0`；管理器
 * `com.tencent.mm.feature.emoji.s0` 的 `zj(Context,String):String`、`Bj(String):IEmojiInfo`、
 * `Fj(Context,String,String):boolean` 两个变体不变；缓存路径 getter 本包是 `IEmojiInfo.b1()`，
 * 旧变体是 `d1()`（本包 `d1` 只剩 `d1(int)` setter）。
 */
internal object WeChatEmoticonSender {
    fun send(file: File, talker: String?): Result<Unit> = runCatching {
        val context = checkNotNull(AppParasitics.appContext) { "宿主 Application 尚未初始化" }
        val result = WeChatEmoticonSubmission(NativeApi(context, WeChatEmojiProtocol.resolve(appClassLoader)))
            .send(file, talker).getOrThrow()
        Log.info("已提交微信原生表情发送: format=${result.format} bytes=${result.size} md5=${result.md5.take(8)}")
    }.recoverCatching { error ->
        throw if (error is InvocationTargetException) error.targetException else error
    }

    private class NativeApi(
        private val context: Context,
        private val protocol: WeChatEmojiProtocol,
    ) : WeChatEmoticonApi {
        /** 协议解析不碰注册表实例；到这里才向注册表要服务，微信没就绪时原样失败。 */
        private val manager: Any by lazy {
            checkNotNull(protocol.lookup.invoke(null, protocol.apiClass)) { "微信原生表情服务尚未就绪" }
        }

        private val importMethod: Method by lazy {
            manager.javaClass.getMethod("zj", Context::class.java, String::class.java)
        }
        private val lookupMethod: Method by lazy {
            manager.javaClass.getMethod("Bj", String::class.java)
        }
        private val sendMethod: Method by lazy {
            manager.javaClass.getMethod("Fj", Context::class.java, String::class.java, String::class.java)
        }

        override fun importFile(file: WeChatEmoticonFile): ImportedWeChatEmoticon? {
            // 微信原生导入（s0.zj -> EmojiMgrImpl.I）：原字节写入表情根目录/<md5>，按文件头判定
            // GIF 并登记 catalog=65/type=2；不重编码位图或缩略图。
            val md5 = importMethod.invoke(manager, context, file.file.absolutePath) as? String
            if (md5.isNullOrBlank()) return null
            val emoji = lookupMethod.invoke(manager, md5) ?: return null
            val path = protocol.emojiPathGetter.invoke(emoji) as? String ?: return null
            return ImportedWeChatEmoticon(
                md5 = md5,
                size = (protocol.vfsLength.invoke(null, path) as Number).toLong(),
                nativeType = (protocol.emojiType.invoke(emoji) as Number).toInt(),
                readable = protocol.vfsExists.invoke(null, path) == true,
            )
        }

        override fun submit(talker: String, md5: String): Boolean =
            // s0.Fj -> EmojiMgrImpl.W 交给微信表情消息引擎；这里只提交这一次。
            sendMethod.invoke(manager, context, talker, md5) == true
    }
}

/**
 * 微信原生表情服务的反射协议（只解析，不含实例）。
 *
 * 注册表位置由标记接口唯一的直接父接口决定：`z5 extends kj5.m` -> `kj5.n0`，
 * 旧分发变体 `z5 extends ni5.m` -> `ni5.n0`。缓存路径 getter 按分发变体的既定协议取，
 * 不在运行时猜路径语义。
 */
internal class WeChatEmojiProtocol private constructor(
    val apiClass: Class<*>,
    val lookup: Method,
    val emojiType: Method,
    val emojiPathGetter: Method,
    val vfsExists: Method,
    val vfsLength: Method,
) {
    companion object {
        private const val API_CLASS = "com.tencent.mm.feature.emoji.api.z5"
        private const val REGISTRY_NAME = "n0"
        private const val LOOKUP_METHOD = "c"
        private const val EMOJI_API_CLASS = "com.tencent.mm.api.IEmojiInfo"
        private const val VFS_CLASS = "com.tencent.mm.vfs.z6"

        /** 缓存路径 getter：本包 kj5 是 b1()，旧变体 ni5 是 d1()。 */
        private val PATH_GETTER_NAMES = listOf("b1", "d1")

        fun resolve(loader: ClassLoader): WeChatEmojiProtocol {
            val apiClass = Class.forName(API_CLASS, false, loader)
            val serviceType = apiClass.interfaces.singleOrNull()
                ?: error("微信 ${apiClass.name} 没有唯一的标记父接口")
            val packageName = serviceType.name.substringBeforeLast('.', "")
            check(packageName.isNotEmpty()) { "微信 ${serviceType.name} 没有包名" }

            val registry = Class.forName("$packageName.$REGISTRY_NAME", false, loader)
            val lookup = registry.getMethod(LOOKUP_METHOD, Class::class.java)
            check(Modifier.isStatic(lookup.modifiers) && lookup.returnType.isAssignableFrom(serviceType)) {
                "微信表情服务注册表 ${registry.name}.$LOOKUP_METHOD(Class) 形状已变化"
            }

            val emojiApi = Class.forName(EMOJI_API_CLASS, false, loader)
            val emojiType = emojiApi.getMethod("getType")
            check(emojiType.returnType == java.lang.Integer.TYPE) {
                "微信 ${EMOJI_API_CLASS}.getType 签名已变化: ${emojiType.returnType.name}"
            }

            val vfs = Class.forName(VFS_CLASS, false, loader)
            val exists = vfs.getMethod("j", String::class.java)
            val length = vfs.getMethod("k", String::class.java)
            check(exists.returnType == java.lang.Boolean.TYPE && length.returnType == java.lang.Long.TYPE) {
                "微信 ${VFS_CLASS} 的 j/k 签名已变化"
            }

            return WeChatEmojiProtocol(
                apiClass = apiClass,
                lookup = lookup,
                emojiType = emojiType,
                emojiPathGetter = resolvePathGetter(packageName, emojiApi),
                vfsExists = exists,
                vfsLength = length,
            )
        }

        /** 只按实际存在的无参 String getter 唯一选择；两个都在时按分发变体取。 */
        private fun resolvePathGetter(packageName: String, emojiApi: Class<*>): Method {
            val declared = PATH_GETTER_NAMES.mapNotNull { name ->
                runCatching { emojiApi.getMethod(name) }.getOrNull()
            }.filter { it.parameterTypes.isEmpty() && it.returnType == String::class.java }
            check(declared.isNotEmpty()) {
                "微信 ${emojiApi.name} 没有缓存路径 getter（候选: ${PATH_GETTER_NAMES.joinToString()}）"
            }
            if (declared.size == 1) return declared.single()
            val preferred = when (packageName) {
                "kj5" -> "b1"
                "ni5" -> "d1"
                else -> error("微信 $packageName 分发变体的缓存路径 getter 不明确")
            }
            return declared.first { it.name == preferred }
        }
    }
}
