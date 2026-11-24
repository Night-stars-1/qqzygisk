package com.qm.qqzygisk.hook.app

import com.qm.qqzygisk.hook.utils.readHostDex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * 用真实微信安装包核对形状定位。没有安装包时跳过，避免 CI 依赖本机样本。
 * 目录默认 `E:/tmp/wechat-apks`，也可用环境变量 `WECHAT_APKS` 指向该目录。
 */
class WeChatShapeDiscoveryTest {
    @Test
    fun knownPackagesResolveToTheCheckedSymbols() {
        val dir = File(System.getenv("WECHAT_APKS") ?: "E:/tmp/wechat-apks")
        assumeTrue(dir.resolve("3183.apk").isFile)
        check("3183.apk", dir, "cc4.g", "d", "ib4.i", "la6.l", "P7", "R7", "U7", "b8", "N7", "X7", "e8", "f8", "y0", "E")
        checkEmoji("3183.apk", dir, "zj", "Bj", "Fj", "z5", "kj5.m", "kj5.n0", "b1", "nq.c", "Gj", "oy0.k0", "Zi", "e9", "M0", null)
        check("8079b.apk", dir, "fb4.g", "d", "la4.i", "ja6.l", "P7", "R7", "U7", "b8", "N7", "X7", "e8", "g8", "y0", "E")
        checkEmoji("8079b.apk", dir, "zj", "Bj", "Fj", "z5", "ni5.m", "ni5.n0", "d1", "op.c", "Gj", "ox0.k0", "Vi", "e9", "M0", null)
        check("3220.apk", dir, "hb4.g", "d", "na4.i", "pa6.l", "Q7", "S7", "V7", "c8", "O7", "Y7", "f8", "g8", "y0", "E")
        checkEmoji("3220.apk", dir, "Fj", "Hj", "Lj", "e6", "fi5.m", "fi5.n0", "b1", "rp.c", "Mj", "yx0.k0", "cj", "f9", "N0", "k60.t.Nj")
        check("3180.apk", dir, "la4.g", "d", "r94.i", "c96.l", "M7", "O7", "R7", "Y7", "K7", "U7", "b8", "c8", "B0", "C")
        checkEmoji("3180.apk", dir, "cj", "ej", "ij", "z5", "ph5.m", "ph5.n0", "U0", "op.c", "jj", "ex0.k0", "yi", "e9", "M0", null)
    }

    private fun check(
        apk: String,
        dir: File,
        builder: String,
        builderName: String,
        item: String,
        result: String,
        key: String,
        title: String,
        summary: String,
        resource: String,
        icon: String,
        drawable: String,
        visible: String,
        click: String,
        button: String,
        field: String,
    ) {
        val host = readHostDex(listOf(dir.resolve(apk)))
        val settings = WeChatShapes.settings(host)
        assertEquals(builder, settings.builderOwner)
        assertEquals(builderName, settings.builderName)
        assertEquals(item, settings.itemBase)
        assertEquals(item, settings.builderParameter)
        assertEquals(result, settings.builderReturn)
        assertEquals(key, settings.key)
        assertEquals(title, settings.title)
        assertEquals(summary, settings.summary)
        assertEquals(resource, settings.summaryResource)
        assertEquals(icon, settings.icon)
        assertEquals(drawable, settings.drawable)
        assertEquals(visible, settings.visible)
        assertEquals(click, settings.click)
        assertEquals(
            "com.tencent.mm.plugin.setting.ui.setting_new.settings.friend_privacy.SettingActivityItemCommonSayHi",
            settings.template,
        )
        val buttonShape = WeChatShapes.button(host)
        assertEquals(button, buttonShape.method)
        assertEquals(field, buttonShape.field)
        assertEquals("com.tencent.mm.ui.widget.imageview.WeImageButton", buttonShape.fieldType)
    }

    private fun checkEmoji(
        apk: String,
        dir: File,
        import: String,
        lookup: String,
        send: String,
        api: String,
        service: String,
        registry: String,
        path: String,
        limit: String,
        terminal: String,
        messageOwner: String,
        messageMethod: String,
        message: String,
        status: String,
        taskStart: String?,
    ) {
        val emoji = WeChatShapes.emoji(readHostDex(listOf(dir.resolve(apk))))
        assertEquals("com.tencent.mm.feature.emoji.s0", emoji.manager)
        assertEquals(import, emoji.importMethod)
        assertEquals(lookup, emoji.lookupMethod)
        assertEquals(send, emoji.sendMethod)
        assertEquals("com.tencent.mm.feature.emoji.api.$api", emoji.api)
        assertEquals(service, emoji.service)
        assertEquals(registry, emoji.registryOwner)
        assertEquals("c", emoji.registryMethod)
        assertEquals(path, emoji.pathMethod)
        assertEquals("com.tencent.mm.vfs.z6", emoji.vfsClass)
        assertEquals("k", emoji.vfsLength)
        assertEquals("j", emoji.vfsExists)
        assertEquals(limit, emoji.limitOwner)
        assertEquals("c", emoji.limitMethod)
        assertEquals("com.tencent.mm.feature.emoji.b0", emoji.queueOwner)
        assertEquals(terminal, emoji.terminalMethod)
        assertEquals("m", emoji.queueField)
        assertEquals("com.tencent.mm.feature.emoji.i0", emoji.itemClass)
        assertEquals("a", emoji.msgField)
        assertEquals("b", emoji.talkerField)
        assertEquals("c", emoji.emojiField)
        assertEquals(messageOwner, emoji.messageLookupOwner)
        assertEquals(messageMethod, emoji.messageLookupMethod)
        assertEquals("com.tencent.mm.storage.$message", emoji.messageClass)
        assertEquals(status, emoji.statusMethod)
        if (taskStart == null) {
            assertNull(emoji.task)
        } else {
            val task = emoji.task ?: error("$apk 缺少发送任务")
            assertEquals(taskStart, "${task.startOwner}.${task.startMethod}")
            assertEquals("com.tencent.mm.feature.emoji.kb", task.paramsClass)
            assertEquals("a", task.talkerField)
            assertEquals("b", task.emojiField)
            assertEquals("k60.p0", task.taskClass)
            assertEquals("i", task.paramsField)
            assertEquals("J", task.finishMethod)
            assertEquals("h", task.infoMethod)
            assertEquals("g", task.infoMessageField)
            assertEquals("d", task.messageIdField)
            assertEquals("e", task.messageTalkerField)
        }
    }
}
