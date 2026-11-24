package com.qm.qqzygisk.hook.app.hooker

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.view.View
import com.qm.qqzygisk.hook.app.SettingShape
import com.qm.qqzygisk.hook.app.data.HostData.appClassLoader
import com.qm.qqzygisk.hook.app.declaredMember
import com.qm.qqzygisk.hook.app.host.WeChatHostBridge
import com.qm.qqzygisk.hook.extension.hook
import com.qm.qqzygisk.hook.utils.HostTargetCache
import com.qm.qqzygisk.hook.utils.HookSettings
import com.qm.qqzygisk.hook.utils.Log
import com.qm.qqzygisk.hook.utils.registerModuleAppActivities
import com.qm.qqzygisk.hook.utils.startModuleSettings
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.WeakHashMap

/**
 * 微信 setting_new 页面；在宿主排好序后插入，避免破坏前驱链。
 *
 * 构建器、标题、键、摘要、图标、点击和可见性都按方法形状定位，不写死混淆短名。
 * 同一宿主版本把定位结果写入 `wechat-settings.json`，下次启动直接恢复。
 */
internal object WeChatSettingHooker {
    private const val ROOT_GROUP =
        "com.tencent.mm.plugin.setting.ui.setting_new.settings.SettingGroupMain"
    private const val ITEM_TITLE = "QQ Zygisk"
    private const val ITEM_KEY = "QQZygisk_Settings"
    internal const val CACHE_KEY = "wechat-settings"
    private class Binding(
        val buildMethod: Method,
        val itemBase: Class<*>,
        val template: Class<*>,
        val title: Method,
        val key: Method,
        val summary: Method,
        val summaryResource: Method,
        val icon: Method,
        val drawable: Method?,
        val visible: Method,
        val click: Method,
        val activityGetters: List<Method>,
    )

    private val clickParams: Array<Class<*>> = arrayOf(
        Context::class.java, View::class.java, Int::class.javaPrimitiveType!!,
    )

    private val installedBuilders = mutableSetOf<Method>()
    private val installedItemMethods = mutableSetOf<Method>()
    private val activityItems = WeakHashMap<Activity, WeakReference<Any>>()
    private val itemOwners = WeakHashMap<Any, WeakReference<Activity>>()
    private var installing = false
    private var failureLogged = false
    private var lastError: Throwable? = null
    private var failedLoader: ClassLoader? = null
    private var bindingLoader: ClassLoader? = null
    private var cachedBinding: Binding? = null

    @Synchronized
    fun install(): Boolean {
        if (!com.qm.qqzygisk.hook.utils.HostTargetCache.cached(CACHE_KEY)) return false
        return installNow()
    }

    fun discoverAndInstall() {
        synchronized(this) { failedLoader = null }
        if (!installNow()) throw lastError ?: IllegalStateException("微信设置入口定位失败")
    }

    @Synchronized
    private fun installNow(): Boolean {
        if (installing) return false
        val loader = appClassLoader
        if (loader === failedLoader) return false
        installing = true
        return try {
            val binding = resolve()
            if (binding.buildMethod in installedBuilders) return true
            binding.buildMethod.hook {
                after {
                    val root = args.firstOrNull() ?: return@after
                    if (root.javaClass.name != ROOT_GROUP) return@after
                    val original = result ?: return@after
                    runCatching { inject(root, original, binding) }
                        .onSuccess { updated -> result = updated }
                        .onFailure { Log.error("微信设置入口注入失败", it) }
                }
            }
            installedBuilders.add(binding.buildMethod)
            Log.info(
                "微信设置入口 hook 已安装: ${binding.buildMethod.declaringClass.name}.${binding.buildMethod.name} " +
                    "(${binding.itemBase.name} -> ${binding.template.name})",
            )
            true
        } catch (error: Throwable) {
            lastError = error
            failedLoader = loader
            if (!failureLogged) {
                failureLogged = true
                Log.error("安装微信设置入口失败", error)
            }
            false
        } finally {
            installing = false
        }
    }

    private fun resolve(): Binding {
        val loader = appClassLoader
        if (bindingLoader === loader) cachedBinding?.let { return it }
        val binding = HostTargetCache.resolve(CACHE_KEY, loader) { targets ->
            if (targets.cached) restore(targets) else discover(loader, targets)
        }
        cachedBinding = binding
        bindingLoader = loader
        failedLoader = null
        return binding
    }

    private fun discover(loader: ClassLoader, targets: HostTargetCache.Targets): Binding {
        val shape = com.qm.qqzygisk.hook.app.WeChatDexKit.settings()
        val itemBase = Class.forName(shape.itemBase, false, loader)
        val build = Class.forName(shape.builderOwner, false, loader).declaredMember(
            shape.builderName,
            arrayOf(itemBase, List::class.java),
        )
        check(!Modifier.isStatic(build.modifiers) && build.returnType.name == shape.builderReturn) {
            "微信设置构建器 ${build.declaringClass.name}.${build.name} 形状已变化"
        }
        val resultType = build.returnType
        if (resultType == Void.TYPE || resultType.isPrimitive || resultType.isArray ||
            resultType.declaredFields.count { !Modifier.isStatic(it.modifiers) } != 2 ||
            resultType.declaredConstructors.none { ctor ->
                ctor.parameterTypes.size == 2 && ctor.parameterTypes.none { it.isPrimitive }
            }
        ) {
            error("${build.declaringClass.name}.${build.name} 返回值不是二元组: ${resultType.name}")
        }
        val template = Class.forName(shape.template, false, loader)
        check(!Modifier.isAbstract(template.modifiers) && itemBase.isAssignableFrom(template)) {
            "微信设置项模板 ${template.name} 不能实例化"
        }
        val binding = Binding(
            buildMethod = build,
            itemBase = itemBase,
            template = template,
            title = template.declaredMember(shape.title, emptyArray()),
            key = template.declaredMember(shape.key, emptyArray()),
            summary = template.declaredMember(shape.summary, emptyArray()),
            summaryResource = template.declaredMember(shape.summaryResource, emptyArray()),
            icon = template.declaredMember(shape.icon, emptyArray()),
            drawable = shape.drawable?.let { template.declaredMember(it, emptyArray()) },
            visible = template.declaredMember(shape.visible, emptyArray()),
            click = template.declaredMember(shape.click, clickParams),
            activityGetters = shape.activityGetters.map { name ->
                itemBase.declaredMember(name, emptyArray()).also { method ->
                    check(Activity::class.java.isAssignableFrom(method.returnType)) {
                        "${itemBase.name}.$name 不是 Activity getter"
                    }
                }
            },
        )
        check(binding.activityGetters.isNotEmpty()) { "${itemBase.name} 缺少 Activity getter" }
        remember(targets, binding)
        Log.info("微信设置形状: ${shape.describe()}")
        return binding
    }

    private fun restore(targets: HostTargetCache.Targets): Binding = Binding(
        buildMethod = targets.method("build"),
        itemBase = targets.type("itemBase"),
        template = targets.type("template"),
        title = targets.method("title"),
        key = targets.method("key"),
        summary = targets.method("summary"),
        summaryResource = targets.method("summaryResource"),
        icon = targets.method("icon"),
        drawable = if (targets.values.has("drawable")) targets.method("drawable") else null,
        visible = targets.method("visible"),
        click = targets.method("click"),
        activityGetters = restoreMethods(targets, "activity"),
    )

    private fun remember(targets: HostTargetCache.Targets, binding: Binding) {
        targets.method("build") { binding.buildMethod }
        targets.type("itemBase") { binding.itemBase }
        targets.type("template") { binding.template }
        targets.method("title") { binding.title }
        targets.method("key") { binding.key }
        targets.method("summary") { binding.summary }
        targets.method("summaryResource") { binding.summaryResource }
        targets.method("icon") { binding.icon }
        binding.drawable?.let { targets.method("drawable") { it } }
        targets.method("visible") { binding.visible }
        targets.method("click") { binding.click }
        binding.activityGetters.forEachIndexed { index, method ->
            targets.method("activity$index") { method }
        }
    }

    private fun restoreMethods(targets: HostTargetCache.Targets, prefix: String): List<Method> {
        val methods = ArrayList<Method>()
        var index = 0
        while (targets.values.has("$prefix$index")) {
            methods += targets.method("$prefix$index")
            index++
        }
        return methods
    }

    private fun inject(root: Any, pair: Any, binding: Binding): Any {
        val activity = activityOf(root, binding.activityGetters)
            ?: error("微信设置根项没有宿主 Activity")
        HookSettings.initialize(activity)
        val childrenField = pair.javaClass.declaredFields.firstOrNull { field ->
            if (Modifier.isStatic(field.modifiers)) return@firstOrNull false
            field.isAccessible = true
            field.get(pair) is List<*>
        } ?: error("微信设置构建结果没有子项列表")
        val children = (childrenField.get(pair) as List<*>).filterNotNull()
        val item = activityItems[activity]?.get() ?: createItem(activity, binding).also {
            activityItems[activity] = WeakReference(it)
        }
        val updated = insertWeChatSettingEntry(children, item) { it.javaClass.simpleName }
        if (updated === children) return pair
        val ctor = pair.javaClass.declaredConstructors.firstOrNull {
            it.parameterTypes.size == 2 && it.parameterTypes.none { type -> type.isPrimitive }
        } ?: error("微信设置构建结果没有 Pair 构造器")
        ctor.isAccessible = true
        Log.info("微信设置页已插入 QQ Zygisk 入口: ${activity.javaClass.name}, 原有 ${children.size} 项")
        return ctor.newInstance(root, updated)
    }

    private fun activityOf(item: Any, getters: List<Method>): Activity? {
        for (method in getters) {
            val value = runCatching { method.invoke(item) }.getOrNull()
            if (value is Activity) return value
        }
        return null
    }

    private fun createItem(activity: Activity, binding: Binding): Any {
        val ctor = binding.template.declaredConstructors.firstOrNull {
            it.parameterTypes.size == 1 && it.parameterTypes[0].isInstance(activity)
        } ?: error("微信设置项模板 ${binding.template.name} 没有接收宿主 Activity 的构造器")
        ctor.isAccessible = true
        val item = ctor.newInstance(activity)
        itemOwners[item] = WeakReference(activity)
        hookItemMethods(binding)
        val onCreate = findMethod(binding.template, "onCreate", arrayOf(Bundle::class.java))
            ?: error("微信设置项模板缺少 onCreate(Bundle)")
        onCreate.isAccessible = true
        onCreate.invoke(item, null)
        return item
    }

    private fun hookItemMethods(binding: Binding) {
        fun override(method: Method, value: Any?) {
            if (!installedItemMethods.add(method)) return
            method.hook {
                before {
                    if (itemOwners.containsKey(instance)) result = value
                }
            }
        }
        override(binding.title, ITEM_TITLE)
        override(binding.key, ITEM_KEY)
        override(binding.summary, "")
        override(binding.summaryResource, null)
        override(binding.icon, null)
        binding.drawable?.let { override(it, null) }
        override(binding.visible, true)
        if (installedItemMethods.add(binding.click)) {
            binding.click.hook {
                before {
                    val activity = itemOwners[instance]?.get() ?: return@before
                    result = null
                    runCatching {
                        val proxy = WeChatHostBridge.proxyActivityClass ?: error("未找到微信代理 Activity")
                        activity.registerModuleAppActivities(proxy)
                        activity.startModuleSettings()
                    }.onFailure { Log.error("打开 QQ Zygisk 设置失败", it) }
                }
            }
        }
    }

    private fun findMethod(cls: Class<*>, name: String, params: Array<Class<*>>): Method? =
        generateSequence(cls) { it.superclass }
            .flatMap { it.declaredMethods.asSequence() }
            .firstOrNull { it.name == name && it.parameterTypes.contentEquals(params) }
}

private fun SettingShape.describe(): String =
    "$builderOwner.$builderName($builderParameter,$builderReturn) key=$key title=$title " +
        "summary=$summary res=$summaryResource icon=$icon drawable=$drawable visible=$visible " +
        "click=$click template=$template"
