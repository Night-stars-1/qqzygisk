package com.qm.qqzygisk.hook.app

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.DialogInterface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.view.View
import android.widget.FrameLayout
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.qm.qqzygisk.R
import com.qm.qqzygisk.hook.app.chat.ModuleContext
import com.qm.qqzygisk.hook.app.chat.WeChatEmojiProtocol
import com.qm.qqzygisk.hook.app.data.HostData
import com.qm.qqzygisk.hook.app.hooker.WeChatEmoticonButtonHooker
import com.qm.qqzygisk.hook.app.hooker.WeChatSettingHooker
import com.qm.qqzygisk.hook.parasitic.AppParasitics
import com.qm.qqzygisk.hook.utils.HostTargetCache
import com.qm.qqzygisk.hook.utils.Log
import com.qm.qqzygisk.hook.utils.ModuleUtils
import com.qm.qqzygisk.hook.utils.injectModuleAppResources
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.result.ClassData
import org.luckypray.dexkit.result.FieldData
import org.luckypray.dexkit.result.FieldUsingType
import org.luckypray.dexkit.result.MethodData
import java.io.File
import java.io.FileOutputStream
import java.lang.reflect.Modifier
import java.util.ArrayDeque
import java.util.zip.ZipFile

/**
 * 进入微信后用 DexKit 定位设置入口、表情按钮和表情发送。
 * 同版本缓存已齐时不再打开安装包；第一次定位用弹窗显示进度。
 */
internal object WeChatDexKit {
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
    private val IMAGE_VIEWS = listOf(
        "android.widget.ImageView",
        "android.widget.ImageButton",
        "androidx.appcompat.widget.AppCompatImageView",
        "androidx.appcompat.widget.AppCompatImageButton",
    )
    private val ACTIVITIES = listOf(
        "android.app.Activity",
        "androidx.appcompat.app.AppCompatActivity",
        "androidx.fragment.app.FragmentActivity",
        "androidx.activity.ComponentActivity",
    )

    private val main = Handler(Looper.getMainLooper())
    private var started = false
    private var nativeLoaded = false
    private var bridge: DexKitBridge? = null
    private var loader: ClassLoader? = null
    private data class ScanProgress(val value: Int, val text: String, val failed: Boolean = false)

    // 窗口和扫描进度只在主线程读写，切换 Activity 后用最后一步恢复弹窗。
    private var scanProgress: ScanProgress? = null
    private var resumedActivity: Activity? = null
    private var application: Application? = null
    private var dialog: AlertDialog? = null
    private var progressView: LinearProgressIndicator? = null

    private val activityCallbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: Activity) {
            resumedActivity = activity
            main.post {
                if (resumedActivity === activity) showDialog(activity)
            }
        }

        override fun onActivityPaused(activity: Activity) = detachDialog(activity)
        override fun onActivityDestroyed(activity: Activity) = detachDialog(activity)
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    }

    fun start(activity: Activity) {
        if (started) return
        if (cached()) {
            installCached()
            return
        }
        started = true
        scanProgress = ScanProgress(0, "正在用 DexKit 适配微信")
        application = activity.application.also { it.registerActivityLifecycleCallbacks(activityCallbacks) }
        Thread(::scan, "qqzygisk-wechat-dexkit").apply { isDaemon = true }.start()
    }

    fun settings(): SettingShape = settingsShape()

    fun button(): ButtonShape = buttonShape()

    fun emoji(): EmojiShape = emojiShape()

    private fun cached(): Boolean =
        HostTargetCache.cached(WeChatSettingHooker.CACHE_KEY) &&
            HostTargetCache.cached(WeChatEmoticonButtonHooker.CACHE_KEY) &&
            HostTargetCache.cached(WeChatEmojiProtocol.CACHE_KEY)

    private fun installCached() {
        WeChatSettingHooker.install()
        WeChatEmoticonButtonHooker.install()
        WeChatEmojiProtocol.warm(HostData.appClassLoader)
    }

    private fun scan() {
        try {
            progress(5, "正在打开微信安装包")
            loadNative()
            val hostLoader = HostData.appClassLoader
            DexKitBridge.create(hostLoader, useMemoryDexFile = false).use { opened ->
                bridge = opened
                loader = hostLoader
                try {
                    progress(30, "正在定位设置入口")
                    WeChatSettingHooker.discoverAndInstall()
                    progress(55, "正在定位表情按钮")
                    WeChatEmoticonButtonHooker.discoverAndInstall()
                    progress(80, "正在定位表情发送")
                    WeChatEmojiProtocol.scan(hostLoader)
                    progress(100, "微信适配完成")
                } finally {
                    bridge = null
                    loader = null
                }
            }
            main.postDelayed(::finishProgress, 600)
        } catch (error: Throwable) {
            Log.error("微信 DexKit 定位失败", error)
            progress(0, error.message ?: "微信适配失败", failed = true)
        }
    }

    private fun showDialog(activity: Activity) {
        val state = scanProgress ?: return
        if (activity.isFinishing || activity.isDestroyed || dialog != null) return
        runCatching {
            activity.injectModuleAppResources()
            val themed = ModuleContext(
                activity,
                R.style.Theme_QQZygisk_WeChatScan,
                WeChatDexKit::class.java.classLoader ?: activity.classLoader,
            )
            val builder = MaterialAlertDialogBuilder(themed)
            val context = builder.context
            val inset = (24 * context.resources.displayMetrics.density).toInt()
            val indicator = LinearProgressIndicator(context).apply {
                max = 100
                progress = state.value
            }
            val content = FrameLayout(context).apply {
                setPadding(inset, inset / 2, inset, inset)
                addView(indicator, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT)
            }
            val created = builder
                .setTitle("QQ Zygisk")
                .setMessage(state.text)
                .setView(content)
                .setCancelable(false)
                .setPositiveButton("关闭") { _, _ -> finishProgress() }
                .create()
            created.setOwnerActivity(activity)
            created.show()
            created.showCloseButton(state.failed)
            dialog = created
            progressView = indicator
        }.onFailure { Log.warn("微信适配进度弹窗未显示", it) }
    }

    private fun progress(value: Int, text: String, failed: Boolean = false) {
        main.post {
            scanProgress = ScanProgress(value, text, failed)
            progressView?.setProgressCompat(value, true)
            dialog?.setMessage(text)
            dialog?.showCloseButton(failed)
        }
    }

    private fun AlertDialog.showCloseButton(show: Boolean) {
        getButton(DialogInterface.BUTTON_POSITIVE).visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun detachDialog(activity: Activity) {
        if (resumedActivity === activity) resumedActivity = null
        if (dialog?.ownerActivity === activity) dismissDialog()
    }

    private fun finishProgress() {
        scanProgress = null
        resumedActivity = null
        application?.unregisterActivityLifecycleCallbacks(activityCallbacks)
        application = null
        dismissDialog()
    }

    private fun dismissDialog() {
        val window = dialog
        dialog = null
        progressView = null
        try {
            window?.dismiss()
        } catch (error: IllegalArgumentException) {
            Log.warn("微信适配弹窗的窗口已移除", error)
        }
    }

    private fun loadNative() {
        if (nativeLoaded) return
        synchronized(this) {
            if (nativeLoaded) return
            val apk = File(ModuleUtils.moduleAppFilePath)
            check(apk.isFile) { "模块安装包不存在: ${apk.path}" }
            val abi = if (Process.is64Bit()) "arm64-v8a" else "armeabi-v7a"
            val context = checkNotNull(AppParasitics.appContext) { "宿主 Application 尚未初始化" }
            val out = File(context.getDir("qqzygisk-dexkit", Context.MODE_PRIVATE), "libdexkit.so")
            ZipFile(apk).use { zip ->
                val entry = zip.getEntry("lib/$abi/libdexkit.so")
                    ?: error("模块安装包没有 lib/$abi/libdexkit.so")
                if (!out.isFile || out.length() != entry.size) {
                    val tmp = File(out.parentFile, "libdexkit.so.tmp")
                    zip.getInputStream(entry).use { input ->
                        FileOutputStream(tmp).use { output -> input.copyTo(output) }
                    }
                    if (out.exists() && !out.delete()) error("无法替换 ${out.path}")
                    if (!tmp.renameTo(out)) error("无法安装 ${out.path}")
                }
            }
            System.load(out.absolutePath)
            nativeLoaded = true
        }
    }

    private fun kit(): DexKitBridge = checkNotNull(bridge) { "DexKit 尚未打开" }

    private fun hostLoader(): ClassLoader = checkNotNull(loader) { "微信 ClassLoader 尚未就绪" }

    private fun settingsShape(): SettingShape {
        val chain = hierarchy(SETTINGS_ROOT)
        check(chain.size > 1) { "微信 $SETTINGS_ROOT 不在当前安装包里" }
        val builders = ArrayList<MethodData>()
        for (base in chain) {
            builders += kit().findMethod {
                matcher { paramTypes = listOf(base.name, LIST) }
            }.filter { declaredInstance(it) && it.returnTypeName != "void" }
        }
        val build = only(builders, "微信设置构建器") { "${it.className}.${it.name}(${it.paramTypeNames[0]})" }
        val item = build.paramTypeNames[0]
        val methods = instanceMethods(classData(item))
        val key = only(
            methods.filter { Modifier.isAbstract(it.modifiers) && it.paramCount == 0 && it.returnTypeName == STRING },
            "微信设置项键",
        ) { it.name }
        val title = only(methods.filter { isTitle(it, item) }, "微信设置项标题") { it.name }
        val resource = only(methods.filter { isSummaryResource(it) }, "微信设置项摘要资源") { it.name }
        val summary = only(methods.filter { isSummary(it, item, resource.name) }, "微信设置项摘要") { it.name }
        val integerNames = methods
            .filter { it.paramCount == 0 && it.returnTypeName == "java.lang.Integer" }
            .map { it.name }
            .toSet()
        val iconNames = LinkedHashSet<String>()
        for (body in methods) {
            if (body.name == resource.name) continue
            for (call in body.invokes) {
                if (call.className == item && call.paramCount == 0 && call.name in integerNames) iconNames += call.name
            }
        }
        val icon = only(iconNames.toList(), "微信设置项图标") { it }
        val drawables = methods
            .filter { it.paramCount == 0 && it.returnTypeName == "android.graphics.drawable.Drawable" }
            .map { it.name }
        val drawable = when (drawables.size) {
            0 -> null
            1 -> drawables[0]
            else -> error("微信设置项图标 Drawable 无法唯一确定(${drawables.size} 个)")
        }
        val click = only(
            methods.filter { Modifier.isAbstract(it.modifiers) && it.returnTypeName == "void" && it.paramTypeNames == CLICK },
            "微信设置项点击",
        ) { it.name }
        val template = template(item)
        val visibles = instanceMethods(classData(template)).filter { it.paramCount == 0 && it.returnTypeName == "boolean" }
        val visible = only(visibles, "微信设置项可见性") { it.name }.name
        check(hierarchy(template).any { type ->
            type.methods.any { it.name == click.name && it.paramTypeNames == CLICK }
        }) { "微信设置项模板没有点击方法 ${click.name}" }
        check(hierarchy(item).any { type ->
            type.methods.any { it.name == "onCreate" && it.paramTypeNames == listOf("android.os.Bundle") }
        }) { "微信设置项没有 onCreate(Bundle)" }
        val getters = ArrayList<String>()
        for (type in hierarchy(item)) {
            for (body in type.methods) {
                if (Modifier.isStatic(body.modifiers) || body.paramCount != 0) continue
                if (isActivity(body.returnTypeName)) getters += body.name
            }
        }
        check(getters.isNotEmpty()) { "微信设置项没有 Activity getter" }
        return SettingShape(
            builderOwner = build.className,
            builderName = build.name,
            builderParameter = item,
            builderReturn = build.returnTypeName,
            itemBase = item,
            template = template,
            key = key.name,
            title = title.name,
            summary = summary.name,
            summaryResource = resource.name,
            icon = icon,
            drawable = drawable,
            visible = visible,
            click = click.name,
            activityGetters = getters.distinct(),
        )
    }

    private fun buttonShape(): ButtonShape {
        val footer = classData(CHAT_FOOTER)
        val hits = footer.methods.filter {
            !Modifier.isStatic(it.modifiers) && it.paramCount == 0 && EMOJI_BUTTON in it.usingStrings
        }
        val method = only(hits, "微信表情按钮初始化") { it.name }
        val ordered = steps(method)
        val field = if (ordered != null) {
            val marker = ordered.indexOfFirst { it is Text && it.value == EMOJI_BUTTON }
            check(marker >= 0) { "微信 ChatFooter 没有表情按钮标记" }
            val image = ordered.take(marker).mapNotNull { step ->
                val use = step as? FieldUse ?: return@mapNotNull null
                if (use.write || use.field.className != CHAT_FOOTER || !isImageView(use.field.typeName)) null else use.field
            }
            image.lastOrNull() ?: error("微信 ChatFooter 没有表情按钮字段")
        } else {
            val reads = method.usingFields.map { it.field }.filter {
                it.className == CHAT_FOOTER && isImageView(it.typeName)
            }.distinctBy { it.name }
            only(reads, "微信表情按钮字段") { it.name }
        }
        return ButtonShape(CHAT_FOOTER, method.name, field.name, field.typeName)
    }

    private fun emojiShape(): EmojiShape {
        val classes = directClasses(FEATURE).filter { Modifier.isInterface(it.modifiers).not() }
        val managers = ArrayList<ManagerCandidate>()
        for (type in classes) {
            val methods = type.methods.filter { !it.isBridge() }
            val imports = methods.filter { signature(it, listOf(CONTEXT, STRING), STRING) }
            val lookups = methods.filter { signature(it, listOf(STRING), EMOJI_API) }
            val sends = methods.filter { signature(it, listOf(CONTEXT, STRING, STRING), "boolean") }
            if (imports.size != 1 || lookups.isEmpty() || sends.size != 1) continue
            val shapes = lookups.map { it.invokes.joinToString(";") { call -> call.descriptor } }.toSet()
            check(shapes.size == 1) { "微信 ${type.name} 表情回读实现不一致" }
            managers += ManagerCandidate(type.name, imports[0], lookups.minBy { it.name }, sends[0])
        }
        val manager = only(managers, "微信表情服务实现") { it.owner }
        val api = only(
            classData(manager.owner).interfaces.filter { it.name.startsWith("$FEATURE.api.") },
            "微信表情服务接口",
        ) { it.name }
        val service = only(api.interfaces.toList(), "微信表情服务父接口") { it.name }
        val registries = LinkedHashMap<String, MethodData>()
        for (body in classData(manager.owner).methods) {
            for (call in body.invokes) {
                if (call.paramTypeNames != listOf(CLASS_NAME) || call.returnTypeName != service.name) continue
                val declared = classData(call.className).methods.firstOrNull {
                    it.name == call.name && it.paramTypeNames == call.paramTypeNames && it.returnTypeName == call.returnTypeName
                } ?: call
                if (Modifier.isStatic(declared.modifiers)) registries.putIfAbsent(declared.descriptor, declared)
            }
        }
        val registry = only(registries.values.toList(), "微信表情服务注册表") { it.descriptor }
        val emojiInfo = classData(EMOJI_INFO)
        val pathMethods = emojiInfo.methods.filter {
            !Modifier.isStatic(it.modifiers) && it.paramCount == 0 && it.returnTypeName == STRING && PATH_LOG in it.usingStrings
        }
        val pathNames = pathMethods.map { it.name }.toSet()
        val paths = pathMethods.filter { body ->
            body.invokes.none { it.className == EMOJI_INFO && it.paramCount == 0 && it.name in pathNames }
        }
        val path = only(paths, "微信表情路径") { it.name }
        val lengths = LinkedHashMap<String, MethodData>()
        for (body in emojiInfo.methods) {
            for (call in body.invokes) {
                if (call.className.startsWith("com.tencent.mm.vfs.") && call.paramTypeNames == listOf(STRING) && call.returnTypeName == "long") {
                    lengths.putIfAbsent(call.descriptor, call)
                }
            }
        }
        val length = only(lengths.values.toList(), "微信表情文件长度") { it.descriptor }
        val created = path.invokes.filter { it.className == length.className && it.returnTypeName == "boolean" }.map { it.name }.toSet()
        val exists = LinkedHashMap<String, MethodData>()
        for (body in emojiInfo.methods) {
            if (body.invokes.none { it.className == EMOJI_INFO && it.name == path.name && it.paramCount == 0 }) continue
            for (call in body.invokes) {
                if (call.className == length.className && call.paramTypeNames == listOf(STRING) &&
                    call.returnTypeName == "boolean" && call.name !in created
                ) exists.putIfAbsent(call.name, call)
            }
        }
        val exist = only(exists.values.toList(), "微信表情文件存在判断") { it.descriptor }
        val limits = kit().findMethod {
            matcher {
                usingEqStrings(LIMIT_NAME)
                returnType = "int"
                paramCount = 0
                modifiers = Modifier.STATIC
            }
        }
        val limit = only(limits, "微信表情尺寸上限") { it.descriptor }
        val queue = queue()
        val status = only(statusGetters(queue.lookup.returnTypeName, queue.setter), "微信消息状态读取") { it }
        val task = task()
        return EmojiShape(
            manager = manager.owner,
            importMethod = manager.import.name,
            lookupMethod = manager.lookup.name,
            sendMethod = manager.send.name,
            api = api.name,
            service = service.name,
            registryOwner = registry.className,
            registryMethod = registry.name,
            pathMethod = path.name,
            vfsClass = length.className,
            vfsLength = length.name,
            vfsExists = exist.name,
            limitOwner = limit.className,
            limitMethod = limit.name,
            queueOwner = queue.owner,
            terminalMethod = queue.terminal.name,
            queueField = queue.list.name,
            itemClass = queue.itemOwner,
            msgField = queue.msg.name,
            talkerField = queue.talker.name,
            emojiField = queue.emojiField,
            messageLookupOwner = queue.lookup.className,
            messageLookupMethod = queue.lookup.name,
            messageClass = queue.lookup.returnTypeName,
            statusMethod = status,
            task = task,
        )
    }

    private fun queue(): QueueShape {
        val queues = ArrayList<QueueShape>()
        for (type in directClasses(FEATURE)) {
            val terminals = type.methods.filter { body ->
                declaredInstance(body) && body.paramTypeNames == listOf("int", "int") && body.returnTypeName == "void" &&
                    body.invokes.any { it.paramTypeNames == listOf(STRING, "long") } &&
                    body.usingFields.any { it.field.className == type.name && it.field.typeName == LIST }
            }
            if (terminals.size != 1) continue
            val terminal = terminals[0]
            val ordered = steps(terminal) ?: continue
            val lookupAt = ordered.indexOfFirst { step ->
                val call = (step as? Call)?.method
                call != null && call.paramTypeNames == listOf(STRING, "long")
            }
            if (lookupAt <= 0) continue
            val reads = ordered.take(lookupAt).mapNotNull { step ->
                val use = step as? FieldUse
                if (use != null && !use.write && !Modifier.isStatic(use.field.modifiers)) use.field else null
            }
            val lists = reads.filter { it.className == type.name && it.typeName == LIST }
            val itemReads = reads.filter { it.className != type.name }
            val owners = itemReads.map { it.className }.distinct()
            if (owners.size != 1 || lists.isEmpty()) continue
            val itemOwner = owners[0]
            val msg = itemReads.lastOrNull { it.typeName == "long" } ?: continue
            val talker = itemReads.lastOrNull { it.typeName == STRING } ?: continue
            val emojiFields = classData(itemOwner).fields.filter { it.typeName == EMOJI_INFO }
            if (emojiFields.size != 1) continue
            val lookup = (ordered[lookupAt] as Call).method
            val setters = LinkedHashMap<String, MethodData>()
            for (call in terminal.invokes) {
                if (call.paramTypeNames == listOf("int") && call.returnTypeName == "void" &&
                    (call.className == lookup.returnTypeName || call.className.startsWith("com.tencent.mm.storage."))
                ) setters.putIfAbsent(call.descriptor, call)
            }
            if (setters.size != 1) continue
            queues += QueueShape(type.name, terminal, lists.last(), itemOwner, msg, talker, emojiFields[0].name, lookup, setters.values.first())
        }
        return only(queues, "微信表情上传队列") { it.owner }
    }

    private fun statusGetters(message: String, setter: MethodData): List<String> {
        val getters = ArrayList<String>()
        val seen = HashSet<String>()
        for (type in hierarchy(message)) {
            for (body in type.methods) {
                if (body.name == setter.name && body.paramTypeNames == setter.paramTypeNames) continue
                val found = ArrayList<String>()
                body.invokes.forEachIndexed { index, call ->
                    if (call.name != setter.name || call.paramTypeNames != listOf("int")) return@forEachIndexed
                    val previous = body.invokes.take(index).lastOrNull() ?: return@forEachIndexed
                    if (previous.paramCount == 0 && previous.returnTypeName == "int" && previous.name != "getType") {
                        found += previous.name
                    }
                }
                if (found.size == 1 && seen.add(found[0])) getters += found[0]
            }
        }
        return getters
    }

    private fun task(): TaskShape? {
        val params = ArrayList<Pair<ClassData, MethodData>>()
        for (type in directClasses(FEATURE)) {
            for (body in type.methods) {
                if (body.isConstructor && body.paramTypeNames == listOf(STRING, EMOJI_API, "int")) params += type to body
            }
        }
        if (params.isEmpty()) return null
        val (type, ctor) = only(params, "微信表情发送参数") { it.first.name }
        val ordered = steps(ctor) ?: error("无法按指令顺序还原 ${type.name} 的构造")
        val emojiAt = ordered.indexOfFirst { step ->
            val use = step as? FieldUse
            use != null && use.write && !Modifier.isStatic(use.field.modifiers) &&
                use.field.className == type.name && use.field.typeName == EMOJI_API
        }
        val talkerSteps = if (emojiAt < 0) ordered else ordered.take(emojiAt)
        val talkers = talkerSteps.mapNotNull { step ->
            val use = step as? FieldUse ?: return@mapNotNull null
            if (use.write && !Modifier.isStatic(use.field.modifiers) && use.field.className == type.name && use.field.typeName == STRING) {
                use.field.name
            } else null
        }.distinct()
        val emojis = ctor.usingFields.filter {
            it.usingType == FieldUsingType.Write && !Modifier.isStatic(it.field.modifiers) &&
                it.field.className == type.name && it.field.typeName == EMOJI_API
        }.map { it.field.name }.distinct()
        val talker = only(talkers, "微信表情发送参数里的会话") { it }
        val emoji = only(emojis, "微信表情发送参数里的表情") { it }
        val starts = kit().findMethod {
            matcher {
                paramTypes = listOf(type.name)
                returnType = "boolean"
            }
        }.filter { declaredInstance(it) }
        val start = only(starts, "微信表情任务启动") { it.descriptor }
        val parameterFields = kit().findField {
            matcher { type(type.name) }
        }.filter { field ->
            classData(field.className).methods.any { body ->
                declaredInstance(body) && body.paramCount == 0 && body.invokes.any { it.name == "getMsgId" && it.paramCount == 0 }
            }
        }
        val parameterField = only(parameterFields, "微信表情发送任务字段") { "${it.className}.${it.name}" }
        val taskMethods = classData(parameterField.className).methods.filter { declaredInstance(it) && it.paramCount == 0 }
        val finish = only(
            taskMethods.filter { body -> body.invokes.any { it.name == "getMsgId" && it.paramCount == 0 } },
            "微信表情任务完成",
        ) { it.name }
        val infos = taskMethods.mapNotNull { body ->
            val fields = runCatching { classData(body.returnTypeName).fields.filter { it.typeName == MSG_ID } }.getOrDefault(emptyList())
            if (fields.size == 1) body to fields[0] else null
        }
        val (info, identity) = only(infos, "微信表情任务消息") { it.first.name }
        val identityClass = classData(MSG_ID)
        val messageId = only(identityClass.fields.filter { it.typeName == "long" }, "微信 MsgIdTalker 消息号") { it.name }
        val messageTalker = only(identityClass.fields.filter { it.typeName == STRING }, "微信 MsgIdTalker 会话") { it.name }
        return TaskShape(
            paramsClass = type.name,
            talkerField = talker,
            emojiField = emoji,
            startOwner = start.className,
            startMethod = start.name,
            taskClass = parameterField.className,
            paramsField = parameterField.name,
            finishMethod = finish.name,
            infoMethod = info.name,
            infoClass = info.returnTypeName,
            infoMessageField = identity.name,
            identityClass = MSG_ID,
            messageIdField = messageId.name,
            messageTalkerField = messageTalker.name,
        )
    }

    private fun template(itemBase: String): String {
        val named = NAMED_TEMPLATES.filter { isConcreteItem(it, itemBase) && declaresBoolean(it) }
        if (named.isNotEmpty()) return named[0]
        return only(
            classesIn(SETTINGS_PACKAGE).filter { isConcreteItem(it.name, itemBase) && declaresBoolean(it.name) },
            "微信设置项模板",
        ) { it.name }.name
    }

    private fun isConcreteItem(name: String, itemBase: String): Boolean {
        val type = kit().getClassData(name) ?: return false
        if (Modifier.isAbstract(type.modifiers) || Modifier.isInterface(type.modifiers)) return false
        if (name != itemBase && !isSubclass(name, itemBase)) return false
        return type.methods.any { it.isConstructor && it.paramCount == 1 && isActivity(it.paramTypeNames[0]) }
    }

    private fun declaresBoolean(name: String): Boolean = classData(name).methods.any {
        !Modifier.isStatic(it.modifiers) && it.paramCount == 0 && it.returnTypeName == "boolean"
    }

    private fun isTitle(body: MethodData, item: String): Boolean =
        !Modifier.isAbstract(body.modifiers) && body.paramCount == 0 && body.returnTypeName == STRING &&
            body.invokes.any { it.name == "getString" } &&
            body.invokes.any { it.paramCount == 0 && it.returnTypeName == "int" && it.className == item }

    private fun isSummaryResource(body: MethodData): Boolean =
        !Modifier.isAbstract(body.modifiers) && body.paramCount == 0 && body.returnTypeName == STRING &&
            body.invokes.any { it.name == "intValue" } && body.invokes.any { it.name == "getString" }

    private fun isSummary(body: MethodData, item: String, resource: String): Boolean =
        !Modifier.isAbstract(body.modifiers) && body.paramCount == 0 && body.returnTypeName == STRING &&
            body.invokes.any { it.className == item && it.name == resource } &&
            body.invokes.none { it.name == "getString" }

    private fun signature(body: MethodData, params: List<String>, returnType: String): Boolean =
        declaredInstance(body) && body.paramTypeNames == params && body.returnTypeName == returnType

    private fun declaredInstance(method: MethodData): Boolean =
        !method.isConstructor && !Modifier.isStatic(method.modifiers) && !method.isBridge()

    private fun MethodData.isBridge(): Boolean = (modifiers and 0x40) != 0

    private fun instanceMethods(type: ClassData): List<MethodData> = type.methods.filter { declaredInstance(it) }

    private fun hierarchy(start: String): List<ClassData> {
        val names = ArrayList<ClassData>()
        var current = kit().getClassData(start)
        val seen = HashSet<String>()
        while (current != null && seen.add(current.name)) {
            names += current
            current = current.superClass
        }
        return names
    }

    private fun classData(name: String): ClassData = kit().getClassData(name) ?: error("微信 $name 不在当前安装包里")

    private fun classesIn(pkg: String): List<ClassData> = kit().findClass { searchPackages(pkg) }

    private fun directClasses(pkg: String): List<ClassData> = classesIn(pkg).filter { isDirect(it.name, pkg) }

    private fun isDirect(name: String, pkg: String): Boolean {
        if (!name.startsWith("$pkg.")) return false
        val rest = name.substring(pkg.length + 1)
        return rest.isNotEmpty() && '.' !in rest && '$' !in rest
    }

    private fun isSubclass(name: String, ancestor: String): Boolean {
        if (name == ancestor) return true
        val seen = HashSet<String>()
        var current = kit().getClassData(name)
        while (current != null && seen.add(current.name)) {
            if (current.name == ancestor) return true
            current = current.superClass
        }
        return false
    }

    private fun isActivity(name: String): Boolean {
        if (name in ACTIVITIES) return true
        return runCatching {
            Activity::class.java.isAssignableFrom(Class.forName(name, false, hostLoader()))
        }.getOrDefault(false)
    }

    private fun isImageView(name: String): Boolean {
        if (name in IMAGE_VIEWS) return true
        return runCatching {
            android.widget.ImageView::class.java.isAssignableFrom(Class.forName(name, false, hostLoader()))
        }.getOrDefault(false)
    }

    private fun steps(method: MethodData): List<Step>? {
        val fields = ArrayDeque(method.usingFields)
        val calls = ArrayDeque(method.invokes)
        val texts = ArrayDeque(method.usingStrings)
        val out = ArrayList<Step>()
        try {
            for (op in method.opCodes) {
                when (op) {
                    0x1a, 0x1b -> out += Text(texts.removeFirst())
                    in 0x52..0x6d -> {
                        val use = fields.removeFirst()
                        val write = op in 0x59..0x5f || op in 0x67..0x6d
                        out += FieldUse(use.field, write || use.usingType == FieldUsingType.Write)
                    }
                    0x6e, 0x6f, 0x70, 0x71, 0x72, 0x74, 0x75, 0x76, 0x77, 0x78 -> out += Call(calls.removeFirst())
                }
            }
        } catch (_: NoSuchElementException) {
            return null
        }
        if (fields.isNotEmpty() || calls.isNotEmpty() || texts.isNotEmpty()) return null
        return out
    }

    private fun <T> only(items: List<T>, label: String, describe: (T) -> String): T {
        val chosen = ArrayList<T>()
        val seen = HashSet<String>()
        for (item in items) {
            if (seen.add(describe(item))) chosen += item
        }
        if (chosen.size == 1) return chosen[0]
        val preview = chosen.take(8).joinToString(" | ") { describe(it) }
        error("$label 无法唯一确定(${chosen.size} 个): $preview")
    }

    private class ManagerCandidate(val owner: String, val import: MethodData, val lookup: MethodData, val send: MethodData)

    private class QueueShape(
        val owner: String,
        val terminal: MethodData,
        val list: FieldData,
        val itemOwner: String,
        val msg: FieldData,
        val talker: FieldData,
        val emojiField: String,
        val lookup: MethodData,
        val setter: MethodData,
    )

    private sealed interface Step
    private class Call(val method: MethodData) : Step
    private class FieldUse(val field: FieldData, val write: Boolean) : Step
    private class Text(val value: String) : Step
}
