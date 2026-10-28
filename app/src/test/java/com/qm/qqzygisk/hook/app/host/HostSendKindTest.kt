package com.qm.qqzygisk.hook.app.host

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class HostSendKindTest {
    @Test
    fun `WeChat defaults to native emoticons and retains an explicit image option`() {
        assertEquals(listOf(SendKind.EMOTICON, SendKind.IMAGE), WeChatHostBridge.supportedKinds)
        assertEquals(SendKind.EMOTICON, WeChatHostBridge.defaultSendKind)
    }

    @Test
    fun `Douyin only offers native images and ignores the old emoji mode preference`() {
        assertEquals(listOf(SendKind.IMAGE), DouyinHostBridge.supportedKinds)
        assertEquals(SendKind.IMAGE, DouyinHostBridge.defaultSendKind)
        assertEquals("douyin_native_image_panel_send_type", DouyinHostBridge.panelActionPreferenceKey)
        assertNotEquals(WeChatHostBridge.panelActionPreferenceKey, DouyinHostBridge.panelActionPreferenceKey)
        assertNotEquals(QQHostBridge.panelActionPreferenceKey, DouyinHostBridge.panelActionPreferenceKey)
    }

    @Test
    fun `a stale Douyin emoji action cannot reach the native sender`() {
        var resolved = false
        val session = object : ChatSessionRef {
            override fun resolve(): SessionTarget { resolved = true; return SessionTarget.Unavailable }
        }
        val result = DouyinHostBridge.send(File("unused"), SendKind.EMOTICON, session)
        assertTrue(result.isFailure)
        assertFalse(resolved)
    }

    @Test
    fun `a stale asynchronous Douyin emoji action reports failure without uploading`() {
        var completed = false
        DouyinHostBridge.sendAsync(File("unused"), SendKind.EMOTICON, null, onEnqueued = {}) { result ->
            assertTrue(result.isFailure)
            completed = true
        }
        assertTrue(completed)
    }

    @Test
    fun `QQ retains its existing image default and emoji option`() {
        assertEquals(listOf(SendKind.IMAGE, SendKind.EMOTICON), QQHostBridge.supportedKinds)
        assertEquals(SendKind.IMAGE, QQHostBridge.defaultSendKind)
    }
}
