package com.qm.qqzygisk.hook.app.chat

import android.view.View
import android.view.ViewGroup
import com.qm.qqzygisk.R
import com.qm.qqzygisk.hook.extension.hook
import com.qm.qqzygisk.hook.utils.HookSettings
import com.qm.qqzygisk.hook.utils.Log
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 把“收到的 B 站小程序卡片”在宿主消息行里换成宿主自己的原生网页卡片视图。
 *
 * 宿主对同一张 Ark 卡片有两条并列的渲染链（qq1/qq2/qq3 三版形状一致）：
 *  - Ark 链：`...component.ark.AIOArkContentComponent` + `...msg.ArkMsgItem`，内容容器是组件
 *    自己构造的 `LinearLayout`（qq1 `M1()`、qq2/qq3 `Q1()` 返回）；
 *  - Template 链：`...component.template.AIOTemplateMsgComponent` + `...msg.TemplateMsgItem`
 *    （`TemplateMsgItem extends AIOMsgItem`），内容容器是组件懒加载的根 `ConstraintLayout`
 *    （qq1 `q2()`、qq2 `u2()`、qq3 `t2()` 返回），宿主模板引擎把渲染结果直接加进这个根。
 *
 * 两条链的绑定方法都是 `(int, com.tencent.aio.data.msglist.a, List<Object>) -> void`
 * （qq1 两条链都是 `L1`，qq2/qq3 都是 `P1`），因此都按签名定位，不写死混淆名；
 * 内容容器也按形状取（Ark：唯一的 `LinearLayout` 声明字段；Template：唯一的、返回 `ViewGroup`
 * 子类的无参声明方法），不假定 Template 侧是 `LinearLayout`。
 *
 * 边界：
 *  - 只读 `AIOMsgItem` 的 `getMsgRecord` / `isSelf` / `getMsgId`，不写 `MsgRecord`、`ArkElement`、
 *    `TemplateInfo`、宿主缓存的 model 或菜单数据，不改消息类型，不发消息，不访问卡片里的链接；
 *  - 本人发送、开关关闭、结构或 AppID 不匹配一律保持宿主原卡片；
 *  - 目标卡片用 [HostWebCardRenderer]（宿主 `ITemplateMsgApi` + `template.h` + `b.m0` + 内置 news 模板）
 *    渲染，模块不绘制自己的卡片；
 *  - 非目标/本人发送/开关关闭/宿主通路失败：移除模板视图并恢复宿主原本的子 View（可见性按替换前记录）；
 *  - 行复用（再次绑定）、宿主父容器重建、组件销毁（`onDestroy`）都释放模板视图并恢复，
 *    不残留不可见层；宿主模板通路在绑定后异步 `removeAllViews()` 时由容器监听幂等补挂。
 *
 * 替换状态保存在宿主内容容器自己的 keyed tag 上（与 [RecalledBadge] 同一做法），
 * 不额外持有组件/容器的全局强引用；状态随宿主视图树一起回收。
 *
 * 诊断：每条链首次进入记录组件类、消息项类与内容容器类，小程序跳过按原因每种每链仅一次，
 * 目标卡开始渲染与宿主原生模板失败各每链一次；日志只含类名与原因，不含卡片原文、URL、
 * msgId、AppID 或账号标识。
 */
internal class BiliReceivedCardBridge(
    private val loader: ClassLoader,
    private val settingKey: String,
    private val defaultEnabled: Boolean,
) {
    private val msgItemType: Class<*> = Class.forName(MSG_ITEM_CLASS, false, loader)
    private val recordGetter: Method = msgItemType.getMethod("getMsgRecord")
    private val selfGetter: Method = msgItemType.getMethod("isSelf")
    private val msgIdGetter: Method = msgItemType.getMethod("getMsgId")

    private val elementType: Class<*> = Class.forName(ELEMENT_CLASS, false, loader)

    /** 内核模型字段同时提供 getter 与 public 字段，按先 getter 后字段的顺序读取。 */
    private val elementsAccessor = Accessor(recordGetter.returnType, "getElements", "elements")
    private val arkElementAccessor = Accessor(elementType, "getArkElement", "arkElement")
    private val elementKindAccessor = Accessor(elementType, "getElementType", "elementType")
    private val bytesAccessor = Accessor(arkElementAccessor.type, "getBytesData", "bytesData")

    private val renderer = HostWebCardRenderer(loader)

    /** 宿主同时存在两条链；某一条形状不匹配时只停用该链，另一条继续工作。 */
    private val chains: List<Chain> = COMPONENT_CLASSES.mapNotNull { chainOrNull(it) }

    init {
        check(chains.isNotEmpty()) { "宿主没有可用的 B 站卡片渲染链" }
    }

    private val failureLogged = AtomicBoolean(false)

    /** 替换状态只挂在宿主容器上，不形成指向组件/容器的外部强引用。 */
    private class Active {
        var card: HostWebCardRenderer.Card? = null
        var cardKey: String? = null

        /** 已确认宿主渲染不出内容的消息 key，避免同一条消息反复创建模板视图。 */
        var failedKey: String? = null
        var guard: View.OnLayoutChangeListener? = null

        /** 宿主原本的子 View 及其可见性；模板视图显示期间一律 GONE。 */
        val hidden = WeakHashMap<View, Int>()
    }

    /** 内核模型访问器：优先 getter，其次同名 public 字段。 */
    private class Accessor(owner: Class<*>, getterName: String, fieldName: String) {
        private val getter: Method? = runCatching { owner.getMethod(getterName) }.getOrNull()
        private val field: Field? = runCatching { owner.getField(fieldName) }.getOrNull()

        val type: Class<*> = getter?.returnType ?: field?.type ?: error("$owner 缺少 $getterName/$fieldName")

        fun read(target: Any): Any? =
            getter?.let { runCatching { it.invoke(target) }.getOrNull() }
                ?: field?.let { runCatching { it.get(target) }.getOrNull() }
    }

    /** 卡片 JSON 或跳过原因；两者都不会被写进日志。 */
    private sealed class Content {
        class Card(val key: String, val json: String) : Content()
        class Skipped(val reason: String) : Content()
    }

    /** 宿主内容容器的取值方式：Ark 是声明字段，Template 是返回根视图的方法。 */
    private abstract class Container {
        abstract fun of(owner: Any): ViewGroup?
    }

    private class FieldContainer(field: Field) : Container() {
        private val target: Field = field.apply { isAccessible = true }

        override fun of(owner: Any): ViewGroup? =
            runCatching { target.get(owner) as? ViewGroup }.getOrNull()
    }

    private class MethodContainer(method: Method) : Container() {
        private val target: Method = method.apply { isAccessible = true }

        override fun of(owner: Any): ViewGroup? =
            runCatching { target.invoke(owner) as? ViewGroup }.getOrNull()
    }

    /**
     * 一条宿主渲染链：组件类、绑定完成方法、内容容器，以及各诊断点的“只报一次”状态。
     */
    private inner class Chain(val component: Class<*>) {
        val bind: Method = component.declaredMethods.single {
            !Modifier.isStatic(it.modifiers) &&
                it.returnType == Void.TYPE &&
                it.parameterCount == 3 &&
                it.parameterTypes[0] == Integer.TYPE &&
                it.parameterTypes[1].name == "com.tencent.aio.data.msglist.a" &&
                it.parameterTypes[2] == List::class.java
        }.apply { isAccessible = true }

        val container: Container = resolveContainer(component)

        val entryLogged = AtomicBoolean(false)
        val renderStartedLogged = AtomicBoolean(false)
        val renderFailedLogged = AtomicBoolean(false)
        val skippedReasons: MutableSet<String> = ConcurrentHashMap.newKeySet()

        fun containerOf(owner: Any): ViewGroup? = container.of(owner)
    }

    val target: String
        get() = chains.joinToString(" + ") { "${it.component.simpleName}.${it.bind.name}" }

    fun install() {
        for (chain in chains) {
            chain.bind.hook {
                after {
                    val owner = instance
                    try {
                        reconcile(chain, owner, args.getOrNull(1))
                    } catch (error: Throwable) {
                        // 任何异常都不能把宿主原本的子 View 留在隐藏状态。
                        reportFailure("B 站网页卡片绑定失败", error)
                        runCatching { drop(chain, owner) }
                    }
                }
            }
        }
        // 两条链的 onDestroy 声明位置不同（Ark 只在基类，Template 自己覆写），按解析结果去重安装。
        for (method in destroyMethods()) {
            method.hook {
                after {
                    val owner = instance
                    for (chain in chains) {
                        if (!chain.component.isInstance(owner)) continue
                        runCatching { drop(chain, owner) }
                            .onFailure { reportFailure("B 站网页卡片释放失败", it) }
                    }
                }
            }
        }
    }

    private fun reconcile(chain: Chain, owner: Any, item: Any?) {
        if (item == null || !msgItemType.isInstance(item)) {
            drop(chain, owner)
            return
        }
        val container = chain.containerOf(owner)
        if (chain.entryLogged.compareAndSet(false, true)) {
            Log.info(
                "B 站网页卡片：${chain.component.simpleName} 首次绑定，消息项 ${item.javaClass.simpleName}，" +
                    "内容容器 ${container?.javaClass?.simpleName ?: "不可用"}",
            )
        }
        if (container == null) {
            reportSkip(chain, SKIP_STRUCTURE)
            drop(chain, owner)
            return
        }
        when (val content = cardContent(item)) {
            is Content.Skipped -> {
                reportSkip(chain, content.reason)
                drop(chain, owner)
            }

            is Content.Card -> {
                val state = stateOf(container)
                // 同一条消息已确认宿主模板通路渲染不出内容时不再重复创建视图，保持原始卡片。
                if (state.failedKey == content.key) return
                if (state.card == null || state.cardKey != content.key) {
                    state.failedKey = null
                    releaseCard(container, state)
                    if (chain.renderStartedLogged.compareAndSet(false, true)) {
                        Log.info("B 站网页卡片：${chain.component.simpleName} 开始用宿主原生模板渲染目标卡片")
                    }
                    var pending: HostWebCardRenderer.Card? = null
                    val card = renderer.create(container.context, content.json) {
                        val current = pending
                        // 只处理“当前这张卡”的迟到结果；状态已被换掉/释放时不动宿主视图。
                        if (current != null && state.card === current) {
                            reportRenderFailure(chain, "宿主模板视图没有渲染出内容")
                            state.failedKey = content.key
                            restore(container, state)
                        }
                    }
                    pending = card
                    if (card == null) {
                        reportRenderFailure(chain, "宿主模板通路不可用")
                        state.failedKey = content.key
                        restore(container, state)
                        return
                    }
                    state.card = card
                    state.cardKey = content.key
                }
                state.card?.let { place(container, state, it.view) }
            }
        }
    }

    /**
     * @return 消息身份 key（同一行复用检测）与转换后的网页卡片 JSON；[Content.Skipped] 表示保持原卡片。
     */
    private fun cardContent(item: Any): Content {
        // 本人发送一律不转换；读取宿主当前账号状态，异常按不转换处理。
        if (runCatching { selfGetter.invoke(item) }.getOrNull() != false) return Content.Skipped(SKIP_SELF)
        if (!HookSettings.isEnabled(settingKey, defaultEnabled)) return Content.Skipped(SKIP_DISABLED)
        val raw = rawJson(item) ?: return Content.Skipped(SKIP_STRUCTURE)
        val web = BiliMiniAppWebCard.convert(raw, isSelf = false) ?: return Content.Skipped(SKIP_STRUCTURE)
        val msgId = runCatching { msgIdGetter.invoke(item) }.getOrNull() ?: return Content.Skipped(SKIP_STRUCTURE)
        // 只在本进程内做行复用判定，不落盘、不写消息。
        return Content.Card("$msgId:${raw.length}:${raw.hashCode()}", web)
    }

    /**
     * 与宿主自己的取值规则一致（qq1/qq2/qq3 已核对）：`ArkMsgItem$arkElement` 与
     * `TemplateMsgItem$arkElement` 都是从列表末尾向前找第一个 `elementType == 10` 的元素，
     * 读取它的 `arkElement.bytesData`；该元素没有 arkElement 时宿主同样当作没有原文。
     */
    private fun rawJson(item: Any): String? {
        val record = runCatching { recordGetter.invoke(item) }.getOrNull() ?: return null
        val elements = elementsAccessor.read(record) as? List<*> ?: return null
        for (index in elements.lastIndex downTo 0) {
            val element = elements[index] ?: continue
            if ((elementKindAccessor.read(element) as? Int) != ARK_ELEMENT_TYPE) continue
            val ark = arkElementAccessor.read(element) ?: return null
            return bytesAccessor.read(ark) as? String
        }
        return null
    }

    private fun stateOf(container: ViewGroup): Active =
        (container.getTag(STATE_TAG) as? Active)
            ?: Active().also { container.setTag(STATE_TAG, it) }

    /** 目标卡片：隐藏宿主原本的子 View，并保证宿主模板视图在容器里。 */
    private fun place(container: ViewGroup, state: Active, cardView: View) {
        hideHostChildren(container, state, cardView)
        if (cardView.parent !== container) {
            (cardView.parent as? ViewGroup)?.removeView(cardView)
            // 显式给默认 WRAP_CONTENT 参数：ViewGroup.addView 会把它转成容器自己的 LayoutParams
            // （LinearLayout / ConstraintLayout），不会把上一条链的 LinearLayout.LayoutParams 带进来。
            container.addView(
                cardView,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        if (state.guard == null) {
            // 宿主 Ark 回调、模板引擎 removeAllViews 都会重排子 View，这里只做幂等的隐藏与补挂。
            val guard = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                enforce(container, state)
            }
            state.guard = guard
            container.addOnLayoutChangeListener(guard)
        }
    }

    private fun enforce(container: ViewGroup, state: Active) {
        val cardView = state.card?.view ?: return
        if (cardView.parent !== container) {
            place(container, state, cardView)
            return
        }
        hideHostChildren(container, state, cardView)
    }

    private fun hideHostChildren(container: ViewGroup, state: Active, cardView: View) {
        // 宿主重建会换掉旧子 View，先丢弃已不在容器里的记录，避免恢复时写错对象。
        state.hidden.keys.removeAll { it.parent !== container }
        for (index in 0 until container.childCount) {
            val child = container.getChildAt(index)
            if (child === cardView) continue
            if (!state.hidden.containsKey(child)) state.hidden[child] = child.visibility
            if (child.visibility != View.GONE) child.visibility = View.GONE
        }
    }

    /** 组件解绑/异常兜底：清掉容器上的状态并恢复宿主原本的视图。 */
    private fun drop(chain: Chain, owner: Any) {
        val container = chain.containerOf(owner) ?: return
        val state = container.getTag(STATE_TAG) as? Active ?: return
        container.setTag(STATE_TAG, null)
        restore(container, state)
    }

    /** 释放模板视图并恢复宿主原本的子 View（可见性按替换前记录恢复）。 */
    private fun restore(container: ViewGroup, state: Active) {
        state.guard?.let { container.removeOnLayoutChangeListener(it) }
        state.guard = null
        releaseCard(container, state)
        for ((view, visibility) in state.hidden) {
            if (view.parent === container) view.visibility = visibility
        }
        state.hidden.clear()
    }

    private fun releaseCard(container: ViewGroup, state: Active) {
        val card = state.card ?: return
        state.card = null
        state.cardKey = null
        container.removeView(card.view)
        card.release()
    }

    /** 从未生效的链只报一次名称；不输出任何卡片内容。 */
    private fun chainOrNull(className: String): Chain? = runCatching {
        Chain(Class.forName(className, false, loader))
    }.onFailure {
        Log.warn("B 站网页卡片：宿主渲染链 ${className.substringAfterLast('.')} 不可用（${it.javaClass.simpleName}），跳过该链")
    }.getOrNull()

    /**
     * 从组件类自身解析内容容器：
     *  - 唯一的 `LinearLayout`/`FrameLayout`/`RelativeLayout`/`ConstraintLayout` 声明字段（Ark 链）；
     *  - 否则取唯一的、无参且返回 `ViewGroup` 子类的声明方法（Template 链的懒加载根视图）。
     * 形状不唯一时直接判定该链不可用，不猜。
     */
    private fun resolveContainer(component: Class<*>): Container {
        val fields = component.declaredFields.filter {
            !Modifier.isStatic(it.modifiers) && it.type.name in CONTAINER_FIELD_TYPES
        }
        if (fields.size > 1) error("$component 有多个内容容器字段")
        fields.singleOrNull()?.let { return FieldContainer(it) }

        val methods = component.declaredMethods.filter {
            !Modifier.isStatic(it.modifiers) &&
                it.parameterCount == 0 &&
                it.returnType != Void.TYPE &&
                ViewGroup::class.java.isAssignableFrom(it.returnType)
        }
        check(methods.size == 1) { "$component 的内容容器方法不唯一" }
        return MethodContainer(methods.single())
    }

    private fun destroyMethods(): List<Method> {
        val resolved = ArrayList<Method>()
        for (chain in chains) {
            val method = runCatching { chain.component.getMethod("onDestroy") }.getOrNull() ?: continue
            method.isAccessible = true
            if (resolved.none { it == method }) resolved += method
        }
        return resolved
    }

    /** 小程序跳过只报原因，且每种原因每条链只报一次，避免滚动/复用刷屏。 */
    private fun reportSkip(chain: Chain, reason: String) {
        if (chain.skippedReasons.add(reason)) {
            Log.info("B 站网页卡片：${chain.component.simpleName} 跳过（$reason），保留原始卡片")
        }
    }

    /** 宿主原生模板渲染失败每条链只报一次；只输出原因，不含卡片内容。 */
    private fun reportRenderFailure(chain: Chain, reason: String) {
        if (chain.renderFailedLogged.compareAndSet(false, true)) {
            Log.warn("B 站网页卡片：${chain.component.simpleName} 宿主原生模板渲染失败（$reason），保留原始卡片")
        }
    }

    private fun reportFailure(message: String, error: Throwable) {
        if (failureLogged.compareAndSet(false, true)) {
            Log.warn("$message（${error.javaClass.simpleName}），保留原始卡片")
        }
    }

    private companion object {
        const val MSG_ITEM_CLASS = "com.tencent.mobileqq.aio.msg.AIOMsgItem"
        const val ARK_COMPONENT_CLASS =
            "com.tencent.mobileqq.aio.msglist.holder.component.ark.AIOArkContentComponent"
        const val TEMPLATE_COMPONENT_CLASS =
            "com.tencent.mobileqq.aio.msglist.holder.component.template.AIOTemplateMsgComponent"
        const val ELEMENT_CLASS = "com.tencent.qqnt.kernel.nativeinterface.MsgElement"

        val COMPONENT_CLASSES = listOf(ARK_COMPONENT_CLASS, TEMPLATE_COMPONENT_CLASS)

        /** 宿主行内容容器的既有实现；字段命中即用字段，否则退回“返回 ViewGroup 的无参方法”。 */
        val CONTAINER_FIELD_TYPES = setOf(
            "android.widget.LinearLayout",
            "android.widget.FrameLayout",
            "android.widget.RelativeLayout",
            "androidx.constraintlayout.widget.ConstraintLayout",
        )

        /** 宿主 `MsgElement.elementType` 中 Ark 元素的类型值。 */
        const val ARK_ELEMENT_TYPE = 10

        const val SKIP_SELF = "本人发送"
        const val SKIP_DISABLED = "开关关闭"
        const val SKIP_STRUCTURE = "结构或 AppID 不匹配"

        val STATE_TAG: Int = R.id.qqz_bili_web_card
    }
}
