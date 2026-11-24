package com.qm.qqzygisk.hook.app

import com.qm.qqzygisk.hook.parasitic.AppParasitics
import com.qm.qqzygisk.hook.utils.HostDex
import com.qm.qqzygisk.hook.utils.Log
import com.qm.qqzygisk.hook.utils.readHostDex
import dalvik.system.BaseDexClassLoader
import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.Method

/** 按当前宿主 ClassLoader 的 DEX 顺序建立形状索引，同进程同加载器只解析一次。 */
internal object WeChatHostDex {
    private var loader: ClassLoader? = null
    private var cached: HostDex? = null

    fun of(classLoader: ClassLoader): HostDex {
        cached?.takeIf { loader === classLoader }?.let { return it }
        synchronized(this) {
            cached?.takeIf { loader === classLoader }?.let { return it }
            val started = android.os.SystemClock.uptimeMillis()
            val files = dexFiles(classLoader)
            val host = readHostDex(files.distinctBy { it.absolutePath })
            cached = host
            loader = classLoader
            Log.info(
                "微信 DEX 形状索引完成: files=${files.distinctBy { it.absolutePath }.size} " +
                    "elapsed=${android.os.SystemClock.uptimeMillis() - started}ms",
            )
            return host
        }
    }

    private fun dexFiles(classLoader: ClassLoader): List<File> {
        val files = ArrayList<File>()
        val pathListField = BaseDexClassLoader::class.java.getDeclaredField("pathList").apply { isAccessible = true }
        val seen = HashSet<ClassLoader>()
        var current: ClassLoader? = classLoader
        while (current != null && seen.add(current)) {
            if (current is BaseDexClassLoader) {
                val pathList = pathListField.get(current)
                if (pathList != null) {
                    val elements = pathList.javaClass.getDeclaredField("dexElements")
                        .apply { isAccessible = true }
                        .get(pathList) as Array<*>
                    for (element in elements) {
                        val name = element?.let { dexFileName(it) } ?: continue
                        if (name.isNotEmpty()) files += File(name)
                    }
                }
            }
            current = current.parent
        }
        AppParasitics.appContext?.applicationInfo?.let { info ->
            files += File(info.sourceDir)
            info.splitSourceDirs?.forEach { files += File(it) }
        }
        check(files.isNotEmpty()) { "微信 ClassLoader 中没有可读取的 DEX" }
        return files
    }

    private fun dexFileName(element: Any): String? {
        val dexFile = element.javaClass.getDeclaredField("dexFile").apply { isAccessible = true }.get(element)
            ?: return null
        return dexFile.javaClass.getDeclaredField("mFileName").apply { isAccessible = true }.get(dexFile) as? String
    }
}


internal fun Class<*>.declaredMember(name: String, params: Array<Class<*>>): Method {
    var type: Class<*>? = this
    while (type != null) {
        val found = type.declaredMethods.firstOrNull {
            it.name == name && it.parameterTypes.contentEquals(params) && (it.modifiers and 0x40) == 0
        }
        if (found != null) return found.apply { isAccessible = true }
        type = type.superclass
    }
    error("$simpleName 缺少 $name(${params.joinToString(",") { it.simpleName }})")
}

internal fun Class<*>.declaredSlot(name: String): Field {
    var type: Class<*>? = this
    while (type != null) {
        val found = type.declaredFields.firstOrNull { it.name == name }
        if (found != null) return found.apply { isAccessible = true }
        type = type.superclass
    }
    error("$simpleName 缺少字段 $name")
}
