package com.qm.qqzygisk.hook.app.hooker

import android.view.View
import android.widget.ImageView
import com.qm.qqzygisk.hook.app.data.HostData.appClassLoader
import com.qm.qqzygisk.hook.app.declaredMember
import com.qm.qqzygisk.hook.app.declaredSlot
import com.qm.qqzygisk.hook.extension.hook
import com.qm.qqzygisk.hook.utils.HostTargetCache
import com.qm.qqzygisk.hook.utils.Log
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** 按 ChatFooter 里 `chat_emoji_button` 的字段读取定位表情按钮，不写死混淆后的方法名。 */
internal object WeChatEmoticonButtonHooker {
    internal const val CACHE_KEY = "wechat-emoji-button"
    private val installed = mutableSetOf<Method>()
    private var failureLogged = false
    private var lastError: Throwable? = null
    private var failedLoader: ClassLoader? = null
    private var bindingLoader: ClassLoader? = null
    private var cached: Pair<Method, Field>? = null

    @Synchronized
    fun install(): Boolean {
        if (!HostTargetCache.cached(CACHE_KEY)) return false
        return installNow()
    }

    fun discoverAndInstall() {
        synchronized(this) { failedLoader = null }
        if (!installNow()) throw lastError ?: IllegalStateException("微信表情按钮定位失败")
    }

    @Synchronized
    private fun installNow(): Boolean {
        val loader = appClassLoader
        if (loader === failedLoader) return false
        return try {
            val (initialize, button) = resolve()
            if (initialize !in installed) {
                initialize.hook {
                    after {
                        val view = button.get(instance) as? View ?: return@after
                        EmoticonButtonHooker.attachToView(view)
                    }
                }
                installed += initialize
                Log.info(
                    "微信聊天表情按钮定位已安装：${initialize.declaringClass.simpleName}.${initialize.name} " +
                        "字段 ${button.name}",
                )
            }
            true
        } catch (error: Throwable) {
            lastError = error
            failedLoader = loader
            if (!failureLogged) {
                failureLogged = true
                Log.warn("微信聊天表情按钮定位未安装（${error.javaClass.simpleName}），保留通用图标识别")
            }
            false
        }
    }

    private fun resolve(): Pair<Method, Field> {
        val loader = appClassLoader
        if (bindingLoader === loader) cached?.let { return it }
        val binding = HostTargetCache.resolve(CACHE_KEY, loader) { targets ->
            if (targets.cached) targets.method("init") to targets.field("button")
            else discover(loader, targets)
        }
        cached = binding
        bindingLoader = loader
        failedLoader = null
        return binding
    }

    private fun discover(loader: ClassLoader, targets: HostTargetCache.Targets): Pair<Method, Field> {
        val shape = com.qm.qqzygisk.hook.app.WeChatDexKit.button()
        val footer = Class.forName(shape.owner, false, loader)
        val method = footer.declaredMember(shape.method, emptyArray())
        val field = footer.declaredSlot(shape.field)
        check(!Modifier.isStatic(method.modifiers)) { "微信 ${footer.name}.${method.name} 不是实例方法" }
        check(!Modifier.isStatic(field.modifiers) && ImageView::class.java.isAssignableFrom(field.type)) {
            "微信 ${footer.name}.${field.name} 不是表情按钮"
        }
        targets.method("init") { method }
        targets.field("button") { field }
        Log.info("微信表情按钮形状: ${shape.method} ${shape.field}:${shape.fieldType}")
        return method to field
    }
}
