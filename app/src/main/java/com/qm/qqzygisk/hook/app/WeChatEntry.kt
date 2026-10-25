package com.qm.qqzygisk.hook.app

import android.app.Activity
import android.app.Instrumentation
import com.qm.qqzygisk.hook.app.data.HostData
import com.qm.qqzygisk.hook.app.hooker.EmoticonButtonHooker
import com.qm.qqzygisk.hook.app.hooker.WeChatSettingHooker
import com.qm.qqzygisk.hook.app.host.HostRuntime
import com.qm.qqzygisk.hook.app.host.WeChatHostBridge
import com.qm.qqzygisk.hook.extension.hook
import com.qm.qqzygisk.hook.parasitic.AppParasitics
import com.qm.qqzygisk.hook.utils.HookSettings
import com.qm.qqzygisk.hook.utils.Log
import com.qm.qqzygisk.hook.utils.ModuleUtils
import com.qm.qqzygisk.hook.utils.onAppLifecycle

object WeChatEntry {
    fun init(loader: ClassLoader, packageName: String) {
        HostData.init(loader)
        HostRuntime.install(WeChatHostBridge)
        Log.info("WeChat entry loaded on: $packageName ${HostData.toVerStr()}")
        onAppLifecycle {
            attachBaseContext { baseContext, _ -> HookSettings.initialize(baseContext) }
        }
        AppParasitics.registerToAppLifecycle(packageName)
        installHostActivityEntry(packageName)
        EmoticonButtonHooker.load()
    }

    private fun onHostActivity(activity: Activity, packageName: String) {
        if (activity.packageName != packageName) return
        if (activity.javaClass.name.startsWith(ModuleUtils.modulePackageName)) return
        HostData.alignHostClassLoader(activity.javaClass.classLoader)
        HookSettings.initialize(activity)
        runCatching { EmoticonButtonHooker.installHostHooks() }
            .onFailure { Log.error("安装微信聊天宿主锚点失败", it) }
        WeChatSettingHooker.install()
    }

    private fun installHostActivityEntry(packageName: String) {
        // These Instrumentation methods run before the host's onCreate. AppCompat overrides
        // Activity.setContentView/onResume, so hooking those base methods misses WeChat.
        Instrumentation::class.java.declaredMethods.filter {
            it.name == "callActivityOnCreate" && it.parameterTypes.firstOrNull() == Activity::class.java
        }.forEach { method ->
            runCatching {
                method.hook {
                    before {
                        val activity = args.firstOrNull() as? Activity ?: return@before
                        runCatching { onHostActivity(activity, packageName) }
                            .onFailure { Log.error("微信 Activity 初始化失败", it) }
                    }
                }
            }.onFailure { Log.error("安装微信 Instrumentation 入口失败", it) }
        }

        // Tinker can replace both the ClassLoader and Instrumentation. Observe the actual
        // settings class being loaded as a second entry, before its first onCreate/build.
        ClassLoader::class.java.declaredMethods.filter {
            it.name == "loadClass" && it.parameterTypes.firstOrNull() == String::class.java
        }.forEach { method ->
            runCatching {
                method.hook {
                    after {
                        val name = args.firstOrNull() as? String ?: return@after
                        if (name != "com.tencent.mm.plugin.setting.ui.setting_new.MainSettingsUI" &&
                            name != "com.tencent.mm.plugin.setting.ui.setting_new.base.BaseSettingPrefUI"
                        ) return@after
                        val hostClass = result as? Class<*> ?: return@after
                        runCatching {
                            HostData.alignHostClassLoader(hostClass.classLoader)
                            WeChatSettingHooker.install()
                        }.onFailure { Log.error("微信设置类加载入口失败", it) }
                    }
                }
            }.onFailure { Log.error("安装微信设置类加载入口失败", it) }
        }
        Log.info("微信设置入口引导已安装: Instrumentation + ClassLoader")
    }
}
