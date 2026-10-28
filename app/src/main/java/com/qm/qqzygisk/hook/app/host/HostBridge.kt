package com.qm.qqzygisk.hook.app.host

import android.app.Activity
import android.view.View
import java.io.File

/**
 * 面板里可选的发送方式。
 *
 * 名称（[name]）会作为设置项持久化，历史值 `IMAGE` / `EMOTICON` 与旧的
 * `ChatImageSender.SendType` 一致，改动枚举常量名会让用户已保存的选择失效。
 */
internal enum class SendKind(
    val label: String,
    val contentDescription: String,
) {
    IMAGE("图片", "发送为图片"),
    EMOTICON("表情", "发送为表情"),
}

/** 发送前解析会话的结果。 */
internal sealed interface SessionTarget {
    /** 会话仍然可用，[target] 为宿主自己的会话标识或绑定数据。 */
    class Ready(val target: Any) : SessionTarget

    /** 面板打开后聊天已切换。 */
    data object Changed : SessionTarget

    /** 会话已不可用（Activity 结束、View 解绑等）。 */
    data object Unavailable : SessionTarget
}

/**
 * 面板打开时捕获的会话句柄。
 *
 * 每次发送前都要重新 [resolve]，避免把图片发到切换后的会话，也避免静默发错人。
 */
internal interface ChatSessionRef {
    fun resolve(): SessionTarget
}

/**
 * 宿主差异的唯一出口。
 *
 * 模块主体（面板、长按识别、设置项渲染）只依赖这个接口；各宿主独立实现，
 * 互不引用对方的宿主类。新增宿主时只需要再写一份实现并在入口处安装。
 */
internal interface HostBridge {
    /** 宿主包名 */
    val packageName: String

    /** 面板里可选的发送方式。 */
    val supportedKinds: List<SendKind>

    /** 新用户或旧版发送方式不可用时采用的默认类型。 */
    val defaultSendKind: SendKind get() = supportedKinds.first()

    /** 宿主独立保存面板发送方式，允许新宿主建立自己的默认行为。 */
    val panelActionPreferenceKey: String get() = "emoticon_panel_send_type"

    /** null 使用通用识别；宿主可严格限制为好友聊天里的表情按钮。 */
    fun matchesEmoticonButton(view: View): Boolean? = null

    /** 在宿主聊天创建之前安装会话绑定 hook（可选）。 */
    fun installBeforeChat() {}

    /** 从被长按的 View 解析当前会话；解析不出时返回 null。 */
    fun sessionFor(view: View): ChatSessionRef?

    /** 发送本地文件到 [session] 指向的会话。 */
    fun send(file: File, kind: SendKind, session: ChatSessionRef?): Result<Unit>

    /**
     * [complete] 报告启动失败或发送终态；同步宿主沿用现有发送逻辑。
     *
     * [onEnqueued] 只在本次发送确实入队、面板可以关闭时调用，之后仍以 [complete]
     * 回调真实终态；同步宿主不调用它。
     */
    fun sendAsync(
        file: File,
        kind: SendKind,
        session: ChatSessionRef?,
        onEnqueued: () -> Unit,
        complete: (Result<Unit>) -> Unit,
    ) {
        complete(runCatching { send(file, kind, session).getOrThrow() })
    }

    /** 宿主设置页入口安装（可选）。 */
    fun installSettingsEntry(activity: Activity): Boolean = false

    /** 模块 Activity 寄生时使用的宿主代理 Activity。 */
    val proxyActivityClass: Class<*>? get() = null
}

internal object HostRuntime {
    @Volatile
    private var installed: HostBridge? = null

    fun install(bridge: HostBridge) {
        installed = bridge
    }

    val current: HostBridge
        get() = installed ?: error("HostBridge 尚未安装，宿主入口没有调用 HostRuntime.install")
}
