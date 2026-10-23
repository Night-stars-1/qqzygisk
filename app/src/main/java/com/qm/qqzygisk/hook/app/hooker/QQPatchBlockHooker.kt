package com.qm.qqzygisk.hook.app.hooker

import com.qm.qqzygisk.hook.app.base.BaseHooker
import com.qm.qqzygisk.hook.app.data.HostData.toAppClass
import com.qm.qqzygisk.hook.extension.hook
import com.qm.qqzygisk.hook.utils.HookSettings
import com.qm.qqzygisk.hook.utils.Log
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 阻止 QQ 通过 RFix 加载热补丁。
 *
 * 只拦补丁引擎的加载入口：不拦 DexClassLoader、不碰业务插件、不删除已下载的补丁，
 * 也不动态卸载已经生效的补丁。被拦时返回 `LOAD_RESULT_PROCESS_DISABLE`
 * （isNoPatch=true、isLoaderSuccess=false、isSuccess=true），宿主按“本进程无补丁”处理，
 * 不会进入失败清理，因此补丁目录不会被删除。
 *
 * 三个入口都要拦：
 * - `PatchLoadEngineBase.tryLoadPatch(boolean)`：普通加载，成功后内部还会清理无效补丁，
 *   只拦更内层的实现会触发清理；
 * - `PatchLazyLoadEngine.tryLazyLoadPatch(RFixLoadResult)`：延迟加载，独立入口；
 * - `PatchLoadEngineImmediate.tryLoadPatch(boolean)`：即时加载，独立入口。
 */
object QQPatchBlockHooker : BaseHooker() {
    override val key = "block_patch_load"
    override val name = "阻止热补丁加载"
    override val description = "阻止 QQ 应用 RFix 热修复补丁，保留已下载文件，不影响 APK 升级。修改后需重启 QQ 生效。"
    override val defaultEnabled = true

    private const val ENGINE_BASE_CLASS = "com.tencent.rfix.loader.engine.PatchLoadEngineBase"
    private const val ENGINE_LAZY_CLASS = "com.tencent.rfix.loader.engine.PatchLazyLoadEngine"
    private const val ENGINE_IMMEDIATE_CLASS = "com.tencent.rfix.loader.engine.PatchLoadEngineImmediate"
    private const val RESULT_CLASS = "com.tencent.rfix.loader.entity.RFixLoadResult"
    private const val LOAD_RESULT_CLASS = "com.tencent.rfix.loader.utils.RFixConstants\$LoadResult"
    private const val NO_PATCH_RESULT = "LOAD_RESULT_PROCESS_DISABLE"

    /** 三个边界是否已全部安装；为 false 通常表示宿主结构与预期不符，本进程不会拦截 */
    @Volatile
    var installed = false
        private set

    private class Boundary(
        val method: Method,
        /** 新结果对象的 effectImmediate：普通/延迟加载为 false，即时加载为 true */
        val effectImmediate: Boolean,
        /** 入参是上一阶段的结果对象；入参为 null 时返回值保持 null */
        val takesPreviousResult: Boolean,
    ) {
        /** 每个边界每个进程最多输出一次拦截日志 */
        val blockedLogged = AtomicBoolean(false)
    }

    private class Prepared(
        val boundaries: List<Boundary>,
        private val constructor: Constructor<*>,
        private val resultField: Field,
        private val effectImmediateField: Field,
        private val noPatchResult: Any,
    ) {
        fun newResult(effectImmediate: Boolean): Any =
            constructor.newInstance().also { result ->
                resultField.set(result, noPatchResult)
                effectImmediateField.setBoolean(result, effectImmediate)
            }
    }

    override fun initOnce() {
        installed = false
        val prepared = try {
            prepare()
        } catch (th: Throwable) {
            Log.error("QQ 热补丁引擎结构与预期不符，本进程不安装热补丁拦截: ${th.message}", th)
            return
        }
        try {
            // 全部签名、构造器、字段与枚举常量都解析成功后才安装，避免只装上一部分边界。
            prepared.boundaries.forEach { boundary ->
                boundary.method.hook {
                    before {
                        if (!installed || !HookSettings.isEnabled(key, defaultEnabled)) return@before
                        val previous = if (boundary.takesPreviousResult) args.firstOrNull() else null
                        result = if (boundary.takesPreviousResult && previous == null) {
                            null
                        } else {
                            prepared.newResult(boundary.effectImmediate)
                        }
                        if (boundary.blockedLogged.compareAndSet(false, true)) {
                            Log.info(
                                "已阻止 QQ 热补丁加载: ${boundary.method.declaringClass.name}.${boundary.method.name} " +
                                    "effectImmediate=${boundary.effectImmediate}",
                            )
                        }
                    }
                }
            }
        } catch (th: Throwable) {
            Log.error("安装 QQ 热补丁拦截失败，本进程不再重试", th)
            return
        }
        installed = true
        Log.info(
            "QQ 热补丁拦截已安装: " +
                prepared.boundaries.joinToString { "${it.method.declaringClass.simpleName}.${it.method.name}" },
        )
    }

    private fun prepare(): Prepared {
        val resultClass = RESULT_CLASS.toAppClass()
        val loadResultClass = LOAD_RESULT_CLASS.toAppClass()
        val noPatchResult: Any = loadResultClass.getField(NO_PATCH_RESULT).get(null)
            ?: error("$LOAD_RESULT_CLASS.$NO_PATCH_RESULT 为 null")
        require(loadResultClass.isInstance(noPatchResult)) {
            "$LOAD_RESULT_CLASS.$NO_PATCH_RESULT 不是 $LOAD_RESULT_CLASS 的实例"
        }
        val constructor = resultClass.getDeclaredConstructor().apply { isAccessible = true }
        val resultField = resultClass.getDeclaredField("result").apply { isAccessible = true }
        require(resultField.type == loadResultClass) {
            "$RESULT_CLASS.result 类型为 ${resultField.type.name}, 预期 $LOAD_RESULT_CLASS"
        }
        val effectImmediateField = resultClass.getDeclaredField("effectImmediate").apply { isAccessible = true }
        val booleanType = Boolean::class.javaPrimitiveType!!
        require(effectImmediateField.type == booleanType) {
            "$RESULT_CLASS.effectImmediate 类型为 ${effectImmediateField.type.name}, 预期 boolean"
        }
        return Prepared(
            boundaries = listOf(
                boundary(
                    className = ENGINE_BASE_CLASS,
                    methodName = "tryLoadPatch",
                    parameterType = booleanType,
                    returnType = resultClass,
                    effectImmediate = false,
                    takesPreviousResult = false,
                ),
                boundary(
                    className = ENGINE_LAZY_CLASS,
                    methodName = "tryLazyLoadPatch",
                    parameterType = resultClass,
                    returnType = resultClass,
                    effectImmediate = false,
                    takesPreviousResult = true,
                ),
                boundary(
                    className = ENGINE_IMMEDIATE_CLASS,
                    methodName = "tryLoadPatch",
                    parameterType = booleanType,
                    returnType = resultClass,
                    effectImmediate = true,
                    takesPreviousResult = false,
                ),
            ),
            constructor = constructor,
            resultField = resultField,
            effectImmediateField = effectImmediateField,
            noPatchResult = noPatchResult,
        )
    }

    private fun boundary(
        className: String,
        methodName: String,
        parameterType: Class<*>,
        returnType: Class<*>,
        effectImmediate: Boolean,
        takesPreviousResult: Boolean,
    ): Boundary {
        val owner = className.toAppClass()
        val parameterTypes = arrayOf(parameterType)
        val matched = owner.declaredMethods.filter {
            it.name == methodName &&
                it.returnType == returnType &&
                it.parameterTypes.contentEquals(parameterTypes)
        }
        val method = matched.singleOrNull()
            ?: error("$className.$methodName(${parameterType.name}):${returnType.name} 匹配到 ${matched.size} 个方法，预期唯一")
        return Boundary(method, effectImmediate, takesPreviousResult)
    }
}
