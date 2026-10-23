package com.qm.qqzygisk.hook.app.chat

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.qm.qqzygisk.R
import com.qm.qqzygisk.hook.utils.Log
import java.lang.reflect.Field
import java.util.Collections
import java.util.WeakHashMap

/**
 * 被防撤回保留的原消息上方的“已撤回”标记。
 *
 * 标记是宿主消息行里新插入的一个 framework [TextView]，放在气泡上方、与气泡同级：
 * 不把文字塞进气泡、不替换或包裹气泡，因此气泡的内容、内边距、点击区域和原 MsgRecord
 * 都不会被改动。标记本身不可点击，只占原消息上方的一行独立空间。
 *
 * 布局参数完全来自气泡自己的 `LayoutParams`（宿主可能是它自己的 AndroidX 副本，
 * 不能按模块的 `ConstraintLayout.LayoutParams` 强转）：用实际的 LP class 拷贝构造克隆，
 * 继承水平约束/偏向，把顶边锚到气泡原来的锚点，再把气泡挂到标记下方。每个消息行只保存
 * 一份状态在行自身的资源 tag 上，重复绑定先恢复、再重新计算，反复刷新不会累加间距或
 * 生成第二个 TextView。
 *
 * 可见行用一个弱引用集合索引：集合的值只是标记位，不持有行、行子 View 或 key，
 * 所以被复用的行不会因为这里残留强引用而泄漏 Activity。
 */
internal object RecalledBadge {
    /** 标记文字，供界面与测试定位。 */
    const val LABEL_TEXT = "已撤回"

    /** 标记的无障碍描述，说明它标的是被保留的撤回消息。 */
    const val LABEL_DESCRIPTION = "已撤回的消息（已保留）"

    private const val TEXT_SIZE_SP = 12f

    /** 标记与气泡之间的小间距，避免文字贴着气泡。 */
    private const val GAP_DP = 2

    /** ConstraintLayout 的父容器 id。 */
    private const val PARENT_ID = 0

    private const val NO_ID = -1

    /** 行状态在根 View 上的 tag key（模块自己的 id 常量，不会和宿主 tag 冲突）。 */
    private val STATE_TAG = R.id.qqz_recalled_badge

    private val FALLBACK_COLOR = 0xFF888888.toInt()

    private val HORIZONTAL_INT_FIELDS =
        arrayOf(
            "startToStart",
            "startToEnd",
            "endToStart",
            "endToEnd",
            "leftToLeft",
            "leftToRight",
            "rightToLeft",
            "rightToRight",
        )

    /** 已绑定过的消息行，弱引用；值是常量标记，绝不强引用行本身或它的子 View。 */
    private val boundRows: MutableSet<View> = Collections.newSetFromMap(WeakHashMap<View, Boolean>())

    /** 已经提示过“这类 LP 不支持”的 class，避免每行刷日志。 */
    private val reportedTypes = Collections.synchronizedSet(HashSet<String>())

    /**
     * LP 字段缓存按 Class 身份分组：宿主自己的 AndroidX 与模块的 AndroidX 即使同名也是不同的
     * Class，不能只按类名混用。WeakHashMap 让 Class（及其 ClassLoader）可回收。
     */
    private val fieldCacheLock = Any()
    private val fieldCache: MutableMap<Class<*>, MutableMap<String, Field?>> = WeakHashMap()

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    /**
     * 把一行的标记更新成 [recalled] 描述的状态。
     *
     * [row] 是宿主 AIO 消息行的根 View（会被列表复用）；[bubble] 是该行里的气泡，
     * 标记插在它上方；[key] 是该行当前消息的身份，null 表示这行没有可标记的消息
     * （灰条、时间戳、模型还没准备好）。[recalled] 由调用方判定，通常是
     * “防撤回开启且 [RecalledMessages.contains] 命中”。
     *
     * 只写入 [bubble] 的 top 约束/间距并插入自己的 TextView；不碰气泡内容与子 View。
     * 返回这一行当前是否显示标记。
     */
    fun bind(
        row: View,
        bubble: View?,
        key: RecalledMessageKey?,
        recalled: Boolean,
    ): Boolean {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Log.warn("已撤回标记只能在主线程绑定")
            return false
        }
        boundRows.add(row)
        val state = stateOf(row)
        state.key = key
        // 永远从宿主原始约束重新计算：先恢复上一次写入，重复绑定不会累加。
        restore(state)
        if (!recalled || key == null || bubble == null) return false
        val parent = bubble.parent as? ViewGroup ?: return false
        return show(parent, bubble, state)
    }

    /**
     * 立刻撤掉一行的标记并恢复气泡原约束（消息行开始绑定新模型或不再是撤回消息时）。
     */
    fun clear(row: View) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Log.warn("已撤回标记只能在主线程清除")
            return
        }
        val state = row.getTag(STATE_TAG) as? RowState ?: return
        state.key = null
        restore(state)
    }

    /**
     * 撤回标记变化后刷新所有已绑定行里仍然可见的匹配消息。
     *
     * 只在主线程改 View；[recalled] 判定是否展示，[bubbleOf] 在行还没有记录到气泡时
     * 重新从行结构里定位气泡（默认只用绑定过的气泡）。
     */
    fun refresh(
        recalled: (RecalledMessageKey) -> Boolean,
        bubbleOf: (View) -> View? = { null },
    ) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { refresh(recalled, bubbleOf) }
            return
        }
        val rows =
            synchronized(boundRows) {
                boundRows.toList()
            }
        rows.forEach { row ->
            val state = row.getTag(STATE_TAG) as? RowState ?: return@forEach
            val key = state.key ?: return@forEach
            val bubble = bubbleOf(row) ?: state.bubble
            runCatching { bind(row, bubble, key, recalled(key)) }
                .onFailure { Log.warn("刷新已撤回标记失败", it) }
        }
    }

    /** 在 [parent] 里把标记放到 [bubble] 正上方，并把气泡排到标记下方。 */
    private fun show(
        parent: ViewGroup,
        bubble: View,
        state: RowState,
    ): Boolean {
        val source = bubble.layoutParams ?: return false
        val label = state.label ?: newLabel(bubble.context).also { state.label = it }
        applyAppearance(label, bubble.context)
        val target = cloneLayoutParams(source) ?: return unsupported(source, "缺少 LayoutParams 拷贝构造")
        copyHorizontal(source, target)
        target.width = ViewGroup.LayoutParams.WRAP_CONTENT
        target.height = ViewGroup.LayoutParams.WRAP_CONTENT
        val topToTop = readInt(source, "topToTop") ?: return unsupported(source, "缺少 topToTop")
        val topToBottom = readInt(source, "topToBottom") ?: return unsupported(source, "缺少 topToBottom")
        val topMargin = readInt(source, "topMargin") ?: return unsupported(source, "缺少 topMargin")
        // 标记接替气泡原来的顶边锚点；两侧都为空时贴父容器顶部，不会浮到布局外。
        val labelTopToTop =
            when {
                topToBottom != NO_ID -> NO_ID
                topToTop != NO_ID -> topToTop
                else -> PARENT_ID
            }
        val labelTopToBottom = if (topToBottom != NO_ID) topToBottom else NO_ID
        if (!writeInt(target, "topToTop", labelTopToTop) || !writeInt(target, "topToBottom", labelTopToBottom)) {
            return unsupported(source, "缺少可写的顶边约束")
        }
        writeInt(target, "topMargin", topMargin)
        // 标记不参与下方链式约束，避免和气泡/后续元素形成链。
        writeInt(target, "bottomToTop", NO_ID)
        writeInt(target, "bottomToBottom", NO_ID)
        // 重复绑定不重建也不重插：只有换父容器时才重新挂到气泡前一个位置。
        if (label.parent !== parent) {
            detach(label)
            val index = parent.indexOfChild(bubble)
            parent.addView(label, if (index >= 0) index else parent.childCount, target)
        } else {
            label.layoutParams = target
        }
        label.visibility = View.VISIBLE
        state.bubble = bubble
        state.topToTop = topToTop
        state.topToBottom = topToBottom
        state.topMargin = topMargin
        val bubbleParams = bubble.layoutParams
        val applied =
            writeInt(bubbleParams, "topToTop", NO_ID) &&
                writeInt(bubbleParams, "topToBottom", label.id) &&
                writeInt(bubbleParams, "topMargin", dp(bubble.context, GAP_DP))
        if (!applied) {
            writeInt(bubbleParams, "topToTop", topToTop)
            writeInt(bubbleParams, "topToBottom", topToBottom)
            writeInt(bubbleParams, "topMargin", topMargin)
            state.bubble = null
            label.visibility = View.GONE
            return unsupported(source, "缺少可写的 topToBottom/topMargin")
        }
        state.applied = true
        bubble.requestLayout()
        return true
    }

    /** 回到宿主原始布局：气泡恢复保存的顶边约束与间距，标记 GONE 留着复用。 */
    private fun restore(state: RowState) {
        val bubble = state.bubble
        if (state.applied && bubble != null) {
            val params = bubble.layoutParams
            if (params != null) {
                writeInt(params, "topToTop", state.topToTop)
                writeInt(params, "topToBottom", state.topToBottom)
                writeInt(params, "topMargin", state.topMargin)
                bubble.requestLayout()
            }
        }
        state.applied = false
        state.bubble = null
        val label = state.label ?: return
        if (label.visibility != View.GONE) label.visibility = View.GONE
    }

    private fun detach(label: View) {
        (label.parent as? ViewGroup)?.removeView(label)
    }

    private fun stateOf(row: View): RowState {
        (row.getTag(STATE_TAG) as? RowState)?.let { return it }
        return RowState().also { row.setTag(STATE_TAG, it) }
    }

    private fun newLabel(context: Context): TextView =
        TextView(context).apply {
            id = View.generateViewId()
            // 不设 maxLines/ellipsize：字体放大时换行显示，不截字。
            isClickable = false
            isLongClickable = false
            isFocusable = false
        }

    private fun applyAppearance(
        label: TextView,
        context: Context,
    ) {
        label.text = LABEL_TEXT
        label.contentDescription = LABEL_DESCRIPTION
        // 每次绑定都按当前 sp 重算，系统字体缩放变化后不用等重建视图。
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, TEXT_SIZE_SP)
        // 跟随宿主明暗主题的次要文字颜色，不引入模块自己的固定色。
        label.setTextColor(secondaryTextColor(context))
    }

    private fun secondaryTextColor(context: Context): Int {
        val value = TypedValue()
        if (!context.theme.resolveAttribute(android.R.attr.textColorSecondary, value, true)) {
            return FALLBACK_COLOR
        }
        if (value.resourceId != 0) {
            return runCatching { context.resources.getColor(value.resourceId, context.theme) }
                .getOrDefault(FALLBACK_COLOR)
        }
        return if (value.type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT) {
            value.data
        } else {
            FALLBACK_COLOR
        }
    }

    /**
     * 用气泡 LP 的实际 class 克隆一份，复制不了就不显示（提示一次），绝不强转成模块的 LP。
     */
    private fun cloneLayoutParams(source: ViewGroup.LayoutParams): ViewGroup.LayoutParams? {
        if (reportedTypes.contains(source.javaClass.name)) return null
        val cloned =
            runCatching {
                source.javaClass
                    .getConstructor(ViewGroup.LayoutParams::class.java)
                    .newInstance(source) as? ViewGroup.LayoutParams
            }.getOrNull()
        if (cloned == null) {
            unsupported(source, "缺少 LayoutParams 拷贝构造")
            return null
        }
        return cloned
    }

    private fun copyHorizontal(
        source: ViewGroup.LayoutParams,
        target: ViewGroup.LayoutParams,
    ) {
        HORIZONTAL_INT_FIELDS.forEach { name ->
            val value = readInt(source, name) ?: return@forEach
            writeInt(target, name, value)
        }
        readFloat(source, "horizontalBias")?.let { writeFloat(target, "horizontalBias", it) }
    }

    private fun unsupported(
        params: ViewGroup.LayoutParams,
        reason: String,
    ): Boolean {
        if (reportedTypes.add(params.javaClass.name)) {
            Log.warn("已撤回标记跳过: ${params.javaClass.name} $reason")
        }
        return false
    }

    private fun readInt(
        params: ViewGroup.LayoutParams,
        name: String,
    ): Int? = field(params.javaClass, name)?.let { runCatching { it.getInt(params) }.getOrNull() }

    private fun writeInt(
        params: ViewGroup.LayoutParams?,
        name: String,
        value: Int,
    ): Boolean {
        val member = params?.let { field(it.javaClass, name) } ?: return false
        return runCatching { member.setInt(params, value) }.isSuccess
    }

    private fun readFloat(
        params: ViewGroup.LayoutParams,
        name: String,
    ): Float? = field(params.javaClass, name)?.let { runCatching { it.getFloat(params) }.getOrNull() }

    private fun writeFloat(
        params: ViewGroup.LayoutParams,
        name: String,
        value: Float,
    ): Boolean {
        val field = field(params.javaClass, name) ?: return false
        return runCatching { field.setFloat(params, value) }.isSuccess
    }

    /** 按实际 LP class 查 public 字段；按 Class 身份缓存（含“不存在”），不抛 NPE。 */
    private fun field(
        type: Class<*>,
        name: String,
    ): Field? {
        synchronized(fieldCacheLock) {
            fieldCache[type]?.let { cached ->
                if (cached.containsKey(name)) return cached[name]
            }
        }
        val found = runCatching { type.getField(name) }.getOrNull()?.apply { isAccessible = true }
        synchronized(fieldCacheLock) {
            fieldCache.getOrPut(type) { HashMap() }[name] = found
        }
        return found
    }

    private fun dp(
        context: Context,
        value: Int,
    ): Int = (value * context.resources.displayMetrics.density + 0.5f).toInt()

    /** 一个消息行的标记状态，跟着行 View 的 tag 走。 */
    private class RowState {
        /** 自建的标记；行复用时复用同一个 TextView，不重复创建。 */
        var label: TextView? = null

        /** 被本次标记改动过顶边约束的气泡。 */
        var bubble: View? = null

        /** 该行当前消息身份；null 表示这行没有可标记的消息。 */
        var key: RecalledMessageKey? = null

        /** 是否已经写入过气泡的顶边约束（恢复时据此判断）。 */
        var applied: Boolean = false

        var topToTop: Int = NO_ID
        var topToBottom: Int = NO_ID
        var topMargin: Int = 0
    }
}
