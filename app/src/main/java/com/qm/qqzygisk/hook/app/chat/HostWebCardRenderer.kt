package com.qm.qqzygisk.hook.app.chat

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import com.qm.qqzygisk.hook.utils.Log
import org.json.JSONObject
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.ConcurrentHashMap

/**
 * 用宿主自己的模板卡片通路渲染普通网页卡片。
 *
 * 宿主链路（qq1/qq2/qq3 均已核对）：
 * `QRoute.api(ITemplateMsgApi).createTemplateMsgView(context)` 得到宿主 `template.b`
 * （实现 `...template.share.TemplateMsgView`），`template.h` 由整段卡片 JSON 装配，
 * 再由宿主自己的挂起绑定方法 `b.m0(info, targetWidth, continuation)` 在
 * `Dispatchers.Main` 上调用宿主模板引擎绘制（`TemplateMsgView$show$2` → `com.tencent.qqnt.rich`）。
 *
 * 首帧：宿主 m0 里是 `BuildersKt.withContext(Dispatchers.getMain(), show$2, continuation)`。
 * 只有 continuation 上下文本身就带同一个 Main 拦截器，kotlinx 才走未派发快路径
 * （`startUndispatchedOrReturn`），在当次主线程调用栈内画完并同步返回；若上下文为空，
 * 绘制会被 post 到主线程队列，首次绑定返回空视图、下一轮消息才长高，列表随高度变化弹一下。
 * 因此本类要求 [create] 在主线程调用，并让代理 continuation 返回宿主自己的 Main dispatcher。
 *
 * 目标模板用的是宿主**内置**图文模板 `com.tencent.tuwen.lua;news`
 * （`...component.template.template.b` 静态注册，模板 JSON 的 `templateId` 为
 * `com.tencent.tuwen`，数据绑定就是 `$$title` / `$$desc` / `$$preview` / `$$jumpUrl`）。
 * 而 `com.tencent.structmsg;news` 不在内置表里、宿主 fallback 配置
 * （`TemplateMsgFallbackConfigParser`，config id 107203）默认又是空表，
 * 因此这里沿用同一份 meta，只把 app 换成内置模板的注册名，避免交付依赖服务端下发的通道。
 *
 * 点击：share 版 `TemplateMsgView$show$2` 给模板引擎的回调是 null，模板根节点
 * （`viewId=app`，`clickable=true`，`schema=$$jumpUrl`）仍会被装上 OnClickListener 并消费点击，
 * 但只会埋点不会跳转（`richui` 的 viewdata 处理里回调为 null 时仅记日志）。
 * 因此绑定完成后按“最外层可见的 `...richui.view.RichUILinearLayout`”定位该 schema 根节点，
 * 替换其 OnClickListener，
 * 只在用户点击时调用宿主 AIO 自己的链接入口 [openJumpUrl]
 * （`jump_a` 默认实现：`QRoute.api(IAIOArkApi).jumpUrl(url, msgRecord, activity, extra)`，
 * 主路径为 `IArkAPIService.dispatchOpenURL`）。不提前访问链接、不自动发送、不改标题/封面/URL。
 *
 * 本类只做反射装配，不绘制模块自有卡片、不联网、不写消息记录：
 * 任何宿主符号缺失、QRoute 未初始化、宿主返回 null、绑定抛异常、宿主渲染为空
 * 或无法定位可点击的 schema 节点，都返回 null / 走 [create] 的失败回调，调用方必须保留原卡片。
 */
internal class HostWebCardRenderer(private val loader: ClassLoader) {
    /**
     * 宿主生成的卡片视图。
     *
     * [release] 采用宿主自己的复用方式（`TemplateMsgView.m0` 每次绑定前同样是
     * `removeAllViews()`）：清空宿主模板引擎绘制的子 View 并脱离父容器，宿主模板视图
     * 本身没有更进一步的公开释放接口。
     */
    class Card internal constructor(
        val view: ViewGroup,
        private val onRelease: () -> Unit,
    ) {
        fun release() {
            onRelease()
        }
    }

    /** 宿主模板通路一次解析结果；宿主类/方法缺失时不缓存，允许更晚的时机重试。 */
    private class Shape(
        val api: Any,
        val createView: Method,
        val asView: Method,
        val bind: Method,
        val infoConstructor: Constructor<*>,
        val infoParser: Method,
        val suspended: Any,
        val continuationType: Class<*>,
        val mainContext: Any,
    )

    /** 宿主 AIO 链接入口；与 `jump.d` 默认分支 `jump.a` 使用的是同一个 API。 */
    private class JumpShape(
        val api: Any,
        val jumpUrl: Method,
        val newExtra: () -> Any,
    )

    /** 交给宿主模板的卡片 JSON 与保留的原始跳转链接。 */
    private class HostCard(val json: String, val jumpUrl: String)

    private var shape: Shape? = null
    private var jumpShape: JumpShape? = null
    private val failedStages = ConcurrentHashMap.newKeySet<String>()

    /**
     * 用 [webJson]（[BiliMiniAppWebCard.convert] 的输出）创建宿主原生网页卡片。
     *
     * 必须在主线程调用（创建 View 并按宿主约定在 `Dispatchers.Main` 上绑定），
     * 并且 [context] 必须是 QQ 进程内的 Activity/Application Context。
     *
     * @param onUnavailable 宿主异步绑定结束后模板视图仍是空的（宿主自己判定渲染失败的
     * 同一条件）、或定位不到可点击的 schema 节点时回调；同步完成时直接返回 null。
     * @return 宿主视图，或 null 表示宿主通路不可用
     */
    fun create(context: Context, webJson: String, onUnavailable: () -> Unit = {}): Card? {
        // 宿主显示段是 `withContext(Dispatchers.Main)`：只有调用线程就是主线程，同一拦截器的
        // 未派发快路径才会原地执行模板绘制；否则宿主把绘制排进主线程消息队列，本次只能返回
        // 空视图、下一轮消息才长出内容（聊天列表因此弹一下）。这里明确拒绝错误线程，
        // 让调用方保持原卡片，而不是交付一个“先空后长高”的 View。
        if (Looper.myLooper() != Looper.getMainLooper()) {
            if (failedStages.add("not-main-thread")) {
                Log.warn("B 站网页卡片 renderer[not-main-thread] 必须在主线程创建宿主卡片")
            }
            return null
        }
        val shape = this.shape ?: resolve() ?: return null
        val hostCard = hostTemplateJson(webJson) ?: return null
        val hostView = atStage("create-view") { shape.createView.invoke(shape.api, context) }
            ?: return unavailable("create-view-null")
        val info = atStage("create-info") { shape.infoConstructor.newInstance() } ?: return null
        if (atStage("parse-info") { shape.infoParser.invoke(info, hostCard.json); true } != true) return null
        val view = atStage("as-view") { shape.asView.invoke(hostView) as? ViewGroup }
            ?: return unavailable("as-view-null")
        val continuation = continuation(shape) { onRendered(view, hostCard.jumpUrl, onUnavailable) }
        val bound = atStage("bind-view") {
            shape.bind.invoke(hostView, info, targetWidthPx(context), continuation)
        } ?: return null
        // 同步完成必须同时具备实际内容与可用点击。
        if (bound !== shape.suspended && !rendered(view, hostCard.jumpUrl)) {
            return unavailable("render-or-click-empty")
        }
        return Card(view) {
            runCatching {
                view.removeAllViews()
                (view.parent as? ViewGroup)?.removeView(view)
            }
        }
    }

    /**
     * 把卡片 JSON 对齐到宿主内置图文模板的注册键。
     *
     * 宿主内置表只注册了 `com.tencent.tuwen.lua;news`（`templateId=com.tencent.tuwen`），
     * 其数据绑定字段与 [BiliMiniAppWebCard] 产出的 `meta.news` 完全一致
     * （title/desc/preview/jumpUrl，tag 作为尾部标签），因此只替换 `app`，其余原样交给宿主。
     * `view` 不是 `news` 时不猜测模板，直接放弃。
     */
    private fun hostTemplateJson(webJson: String): HostCard? = runCatching {
        val json = JSONObject(webJson)
        if (json.optString("view") != TEMPLATE_VIEW) return null
        val jumpUrl = json.optJSONObject("meta")
            ?.optJSONObject(TEMPLATE_VIEW)
            ?.optString("jumpUrl")
            ?.takeIf { it.isNotBlank() }
            ?: return null
        json.put("app", TEMPLATE_APP)
        HostCard(json.toString(), jumpUrl)
    }.getOrNull()

    /**
     * 解析宿主模板通路。
     *
     * `template.h` 的整包 JSON 解析方法在 qq1/qq2/qq3 均名为 `o(String)`（三个 APK 一致），
     * 同类签名还有 p/q/t/u/r 五个单字段 setter，因此按已核对的名字 + 形状双重确认，
     * 不做运行期试探调用。
     */
    private fun resolve(): Shape? {
        val resolved = atStage("resolve-template-api") { resolveOrThrow() } ?: return null
        shape = resolved
        return resolved
    }

    private fun resolveOrThrow(): Shape {
        val apiType = hostClass(TEMPLATE_MSG_API)
        val viewType = hostClass(TEMPLATE_VIEW_TYPE)
        val infoType = hostClass(TEMPLATE_INFO)
        val api = qRouteApi(apiType) ?: error("QRoute 未初始化")
        val createView = apiType.methods.single {
            it.parameterCount == 1 && it.parameterTypes[0] == Context::class.java && it.returnType == viewType
        }
        val asView = viewType.methods.single {
            it.parameterCount == 0 && View::class.java.isAssignableFrom(it.returnType)
        }
        val bind = viewType.methods.single {
            it.returnType == Any::class.java && it.parameterCount == 3 &&
                it.parameterTypes[0] == infoType &&
                it.parameterTypes[1] == Integer::class.java &&
                it.parameterTypes[2].isInterface
        }
        // 不能用模块里也存在的 Kotlin 类名字面量做 Class.forName：R8 会把名字改成模块自己的
        // 混淆名，QQ loader 无法加载。类型及包名必须来自宿主实际绑定方法，而非模块 Kotlin。
        val continuationType = bind.parameterTypes[2]
        continuationType.getMethod("resumeWith", Any::class.java)
        val contextType = continuationType.getMethod("getContext").returnType
        val coroutinePackage = contextType.`package`!!.name
        val infoConstructor = infoType.getDeclaredConstructor().apply { isAccessible = true }
        val infoParser = infoType.getDeclaredMethod("o", String::class.java).apply {
            check(returnType == Void.TYPE && !Modifier.isStatic(modifiers))
            isAccessible = true
        }
        val suspended = hostClass("$coroutinePackage.intrinsics.IntrinsicsKt")
            .getMethod("getCOROUTINE_SUSPENDED")
            .invoke(null) ?: error("缺少 COROUTINE_SUSPENDED")
        // 宿主模板的绘制段是 `BuildersKt.withContext(Dispatchers.getMain(), show$2, continuation)`。
        // 只要 continuation 的上下文里已经是同一个 Main dispatcher，withContext 就走未派发
        // 快路径，在调用线程原地执行 show$2（它没有挂起点），m0 返回时视图即完整；
        // 上下文若是 EmptyCoroutineContext，则退化为派发到主线程消息队列，当次返回空视图，
        // 下一轮主线程消息才长高。故这里返回宿主自己的 Main dispatcher。
        // 类名只从宿主上下文包派生（`kotlin.coroutines` → `kotlinx.coroutines`），
        // 不在源码里写完整类名字面量，避免 R8 把候选名改写成模块自己的混淆类。
        val dispatchersType = hostClass(kotlinxDispatchersName(coroutinePackage))
        val getMain = dispatchersType.getMethod("getMain").apply {
            check(Modifier.isStatic(modifiers) && parameterCount == 0)
            check(contextType.isAssignableFrom(returnType))
        }
        val mainContext = getMain.invoke(null) ?: error("缺少 Dispatchers.Main")
        check(contextType.isInstance(mainContext)) { "宿主 Dispatchers.Main 不是 CoroutineContext" }
        return Shape(api, createView, asView, bind, infoConstructor, infoParser, suspended, continuationType, mainContext)
    }

    /**
     * 宿主上下文类型所在工具库的 dispatcher 入口：`kotlin.coroutines` → `kotlinx.coroutines.Dispatchers`。
     *
     * 按包名分段拼接，避免出现可被 R8 重写的完整类名字面量（模块也依赖 Kotlin/协程类型时尤其危险）。
     */
    private fun kotlinxDispatchersName(coroutinePackage: String): String {
        val library = coroutinePackage.substringBeforeLast('.', missingDelimiterValue = "")
        check(library.isNotEmpty()) { "无法从 $coroutinePackage 派生协程库包名" }
        return library + "x." + coroutinePackage.substringAfterLast('.') + ".Dispatchers"
    }

    private fun qRouteApi(apiType: Class<*>): Any? {
        val qRoute = Class.forName(Q_ROUTE, false, loader)
        val method = qRoute.getDeclaredMethod("api", Class::class.java).apply { isAccessible = true }
        return method.invoke(null, apiType)
    }

    /**
     * 宿主模板绑定是挂起函数，这里提供宿主 `kotlin.coroutines.Continuation` 的代理：
     * 只关心“绑定结束”这一个时点，异常结果不重新抛出，避免模块线程崩溃。
     *
     * [Shape.mainContext] 是宿主 `Dispatchers.getMain()` 的原对象；把它作为代理上下文，
     * 宿主 `withContext(Main)` 才会命中“同一拦截器”的未派发快路径，在主线程调用栈内
     * 完成绘制并让 `m0` 同步返回完整视图。这里不引用模块自己的协程类型或调度实现。
     */
    private fun continuation(shape: Shape, onComplete: () -> Unit): Any {
        val handler = java.lang.reflect.InvocationHandler { proxy, method, args ->
            when (method.name) {
                "getContext" -> shape.mainContext
                "resumeWith" -> {
                    onComplete()
                    null
                }
                "equals" -> proxy === args?.firstOrNull()
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "BiliMiniAppWebCardContinuation"
                else -> null
            }
        }
        return Proxy.newProxyInstance(loader, arrayOf(shape.continuationType), handler)
    }

    /** 宿主异步绑定结束后：空视图回退，否则给真实 schema 节点装上宿主跳转。 */
    private fun onRendered(view: ViewGroup, jumpUrl: String, onUnavailable: () -> Unit) {
        val handle = { if (!rendered(view, jumpUrl)) onUnavailable() }
        if (Looper.myLooper() == Looper.getMainLooper()) handle() else view.post { handle() }
    }

    /**
     * 校验宿主是否真的画出了内容，并装配点击。
     *
     * @return 视图非空且点击已装配
     */
    private fun rendered(view: ViewGroup, jumpUrl: String): Boolean =
        view.childCount > 0 && attachJump(view, jumpUrl)

    /**
     * 定位模板里真实的 schema 根节点并替换其 OnClickListener。
     *
     * 内置 tuwen 模板的根节点是 `viewId=app`、`direction=vertical`，宿主 richui 把它渲染成
     * `com.tencent.android.androidbypass.richui.view.RichUILinearLayout`；schema 只挂在根节点上，
     * 引擎装在该节点上的 OnClickListener 在回调为 null 时只埋点不跳转。
     * 运行时该 View 不带 viewId/tag（viewId 只存在 richui node 上），因此按
     * “最外层可见的 RichUILinearLayout”定位；只替换这一个节点的监听，
     * 不碰 debug 模式额外添加的辅助 overlay。
     *
     * @return 是否找到 schema 根节点并完成装配
     */
    private fun attachJump(root: ViewGroup, jumpUrl: String): Boolean {
        val jump = jumpShape ?: resolveJump()?.also { jumpShape = it } ?: return false
        val target = schemaRoot(root) ?: return false
        target.isClickable = true
        target.setOnClickListener { openJumpUrl(jump, target, jumpUrl) }
        return true
    }

    /** 从模板子树里按层序找最外层的 tuwen 根节点（竖直 RichUILinearLayout）。 */
    private fun schemaRoot(root: ViewGroup): View? {
        val pending = ArrayDeque<View>()
        for (index in root.childCount - 1 downTo 0) pending.addLast(root.getChildAt(index))
        while (pending.isNotEmpty()) {
            val view = pending.removeFirst()
            if (view.visibility == View.VISIBLE && view.javaClass.name == RICH_LINEAR_LAYOUT) return view
            if (view is ViewGroup) {
                for (index in view.childCount - 1 downTo 0) pending.addLast(view.getChildAt(index))
            }
        }
        return null
    }

    /**
     * 与 `jump.d`/`jump.a` 的默认分支一致：`IAIOArkApi.jumpUrl(url, record, activity, extra)`
     * （QRoute API，`record` 可空）。只在用户点击时执行。
     */
    private fun openJumpUrl(jump: JumpShape, anchor: View, url: String) {
        val extra = runCatching { jump.newExtra() }.getOrNull() ?: return
        runCatching { jump.jumpUrl.invoke(jump.api, url, null, anchor.context.findActivity(), extra) }
    }

    private fun resolveJump(): JumpShape? = runCatching {
        val apiType = Class.forName(ARK_API, false, loader)
        val extraType = Class.forName(ARK_EXTRA, false, loader)
        val api = qRouteApi(apiType) ?: return null
        val jumpUrl = apiType.methods.single {
            it.name == JUMP_URL &&
                it.parameterCount == 4 &&
                it.parameterTypes[0] == String::class.java &&
                it.parameterTypes[1].name == "com.tencent.qqnt.kernel.nativeinterface.MsgRecord" &&
                it.parameterTypes[2] == Activity::class.java &&
                it.parameterTypes[3] == extraType &&
                it.returnType == Boolean::class.javaPrimitiveType
        }
        val extraConstructor = extraType.getDeclaredConstructor().apply { isAccessible = true }
        // AIO 默认跳转使用这两个已核对的 setter；未知协议不安装可点击卡片。
        val setApp = extraType.getMethod("f", String::class.java)
        val setBiz = extraType.getMethod("g", String::class.java)
        JumpShape(api, jumpUrl) {
            extraConstructor.newInstance().also { extra ->
                setApp.invoke(extra, TEMPLATE_APP)
                setBiz.invoke(extra, "")
            }
        }
    }.getOrNull()

    private fun Context.findActivity(): Activity? {
        var current: Context? = this
        while (current is ContextWrapper) {
            if (current is Activity) return current
            current = current.baseContext
        }
        return null
    }

    private fun hostClass(name: String): Class<*> = try {
        Class.forName(name, false, loader)
    } catch (error: ClassNotFoundException) {
        if (failedStages.add(name)) Log.warn("B 站网页卡片缺少宿主类型：$name")
        throw error
    }

    private inline fun <T> atStage(stage: String, action: () -> T): T? = try {
        action()
    } catch (error: Throwable) {
        var cause = error
        while (cause is InvocationTargetException && cause.targetException != null) cause = cause.targetException
        if (failedStages.add(stage)) {
            // 不输出异常 message；宿主异常可能把卡片原文或链接包含在 message 中。
            val frames = cause.stackTrace.take(6).joinToString(" <- ") {
                "${it.className}.${it.methodName}:${it.lineNumber}"
            }
            Log.warn("B 站网页卡片 renderer[$stage] ${cause.javaClass.name} $frames")
        }
        null
    }

    private fun unavailable(stage: String): Card? {
        if (failedStages.add(stage)) Log.warn("B 站网页卡片 renderer[$stage] 返回空结果")
        return null
    }

    /** 与宿主分享卡片相同的目标宽度：min(263dp, 屏幕高度 - 72dp)。 */
    private fun targetWidthPx(context: Context): Int {
        val metrics = context.resources.displayMetrics
        val preferred = (263 * metrics.density).toInt()
        val available = metrics.heightPixels - (72 * metrics.density).toInt()
        return minOf(preferred, available).coerceAtLeast(1)
    }

    private companion object {
        const val Q_ROUTE = "com.tencent.mobileqq.qroute.QRoute"
        const val TEMPLATE_MSG_API = "com.tencent.mobileqq.aio.msg.template.ITemplateMsgApi"
        const val TEMPLATE_VIEW_TYPE = "com.tencent.mobileqq.aio.msg.template.b"
        const val TEMPLATE_INFO = "com.tencent.mobileqq.aio.msg.template.h"

        /** 宿主内置图文模板的注册键：`com.tencent.tuwen.lua;news`。 */
        const val TEMPLATE_APP = "com.tencent.tuwen.lua"
        const val TEMPLATE_VIEW = "news"

        /** 宿主 AIO 链接入口（与 `jump.a` 相同）。 */
        const val ARK_API = "com.tencent.qqnt.aio.adapter.api.IAIOArkApi"
        const val ARK_EXTRA = "com.tencent.qqnt.aio.adapter.api.b"
        const val JUMP_URL = "jumpUrl"

        /** tuwen 模板根节点（viewId=app，direction=vertical）的宿主 richui 视图类。 */
        const val RICH_LINEAR_LAYOUT = "com.tencent.android.androidbypass.richui.view.RichUILinearLayout"
    }
}
