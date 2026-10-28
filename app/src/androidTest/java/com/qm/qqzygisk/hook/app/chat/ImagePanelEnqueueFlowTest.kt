package com.qm.qqzygisk.hook.app.chat

import android.app.Activity
import android.app.Application
import android.app.Dialog
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.qm.qqzygisk.R
import com.qm.qqzygisk.hook.parasitic.AppParasitics
import com.qm.qqzygisk.hook.utils.HookSettings
import com.qm.qqzygisk.hook.utils.injectModuleAppResources
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * 表情面板“原生入队即关闭、真实终态才记历史”的完整 UI 流程回归。
 *
 * 使用真实 [SaveImagePanel] / [ImagePanelAction] / [ImageEntry]，只隔离宿主发送边界：
 * 测试的 performAsync 保存 complete/active/onEnqueued，并像宿主一样从后台线程交付。
 * 面板显示与关闭断言的是真实 [Dialog.isShowing]，历史断言读真实
 * [ImageFolderStore.historyFolder] 使用记录，失败提示从系统 Toast 的辅助功能事件
 * （以及 `dumpsys notification` 的 Toast 队列）读取本次唯一的错误 token，
 * 不依赖面板源码文案。
 */
@RunWith(AndroidJUnit4::class)
class ImagePanelEnqueueFlowTest {

    companion object {
        const val PROBE_ACTIVITY = "com.qm.qqzygisk.testing.DialogProbeActivity"
        const val PREFERENCES_NAME = "qqzygisk_hook_settings"
        const val LAST_FOLDER_KEY = "emoticon_panel_last_folder"
        const val TOAST_DUMP_INTERVAL_MS = 250L

        /**
         * 全部用例复用同一个测试 Activity 与任务。每个用例重新 finish/start 会让任务栈
         * 短暂回到宿主（微信），探针刚 RESUMED 就被 PAUSED，Toast 随后不可见；复用后
         * 只把已有任务重新置前，避免跨用例的任务切换。
         */
        @Volatile
        private var sharedProbe: Activity? = null

        @Volatile
        private var sharedInstrumentation: Instrumentation? = null

        @AfterClass
        @JvmStatic
        fun finishSharedProbe() {
            val instrumentation = sharedInstrumentation
            val probe = sharedProbe
            sharedProbe = null
            sharedInstrumentation = null
            if (instrumentation != null && probe != null) {
                runCatching {
                    instrumentation.runOnMainSync {
                        runCatching { if (!probe.isFinishing) probe.finish() }
                    }
                }
            }
        }
    }

    private lateinit var instrumentation: Instrumentation
    private lateinit var target: Context
    private lateinit var activity: Activity
    private lateinit var worker: ExecutorService
    private lateinit var imageRoot: File
    private lateinit var emptyRoot: File
    private var preferences: android.content.SharedPreferences? = null
    private var savedHostApplication: Application? = null

    private val savedSettings = CopyOnWriteArrayList<SavedSetting>()
    private val savedStringCache = CopyOnWriteArrayList<SavedCache>()
    private val openedDialogs = CopyOnWriteArrayList<Dialog>()
    private val toastTexts = CopyOnWriteArrayList<String>()

    private data class SavedSetting(val key: String, val present: Boolean, val value: String?)

    private data class SavedCache(val key: String, val present: Boolean, val value: String?)

    @Before
    fun setUp() {
        instrumentation = InstrumentationRegistry.getInstrumentation()
        target = instrumentation.targetContext
        worker = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "ImagePanelEnqueueFlow-callbacks").apply { isDaemon = true }
        }
        val suffix = UUID.randomUUID().toString()
        imageRoot = File(target.cacheDir, "image-panel-enqueue-$suffix").apply { mkdirs() }
        emptyRoot = File(target.cacheDir, "image-panel-enqueue-empty-$suffix").apply { mkdirs() }

        savedHostApplication = AppParasitics.hostApplication
        AppParasitics.hostApplication = target.applicationContext as Application
        HookSettings.initialize(target)
        val shared = target.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        preferences = shared
        val stringCache = hookStringCache()
        listOf(
            ImageFolderStore.pathKey,
            ImageFolderStore.FUNBOX_PATH_KEY,
            ImageFolderStore.TG_STICKERS_PATH_KEY,
            LAST_FOLDER_KEY,
        ).forEach { key ->
            savedSettings += SavedSetting(
                key = key,
                present = shared.contains(key),
                value = shared.getString(key, null),
            )
            savedStringCache += SavedCache(
                key = key,
                present = stringCache.containsKey(key),
                value = stringCache[key],
            )
        }
        HookSettings.setString(ImageFolderStore.pathKey, imageRoot.absolutePath)
        HookSettings.setString(ImageFolderStore.FUNBOX_PATH_KEY, emptyRoot.absolutePath)
        HookSettings.setString(ImageFolderStore.TG_STICKERS_PATH_KEY, emptyRoot.absolutePath)
        HookSettings.setString(LAST_FOLDER_KEY, "")

        toastTexts.clear()
        instrumentation.uiAutomation.setOnAccessibilityEventListener { event ->
            if (event.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
                val text = event.text.joinToString(" ")
                if (text.isNotBlank()) toastTexts += text
            }
        }

        ensureScreenAwake()
        ensureProbeActivity()
        instrumentation.waitForIdleSync()
    }

    @After
    fun tearDown() {
        runCatching {
            onMain {
                openedDialogs.forEach { dialog -> runCatching { if (dialog.isShowing) dialog.dismiss() } }
            }
        }
        runCatching { instrumentation.uiAutomation.setOnAccessibilityEventListener(null) }
        runCatching { worker.shutdownNow() }
        runCatching { worker.awaitTermination(2, TimeUnit.SECONDS) }
        runCatching { ImagePanelMetadata(target.filesDir, imageRoot.absolutePath).usageFile.delete() }
        runCatching {
            savedSettings.forEach { saved ->
                if (saved.present) {
                    HookSettings.setString(saved.key, saved.value.orEmpty())
                } else {
                    preferences?.edit()?.remove(saved.key)?.commit()
                }
            }
        }
        runCatching {
            val stringCache = hookStringCache()
            savedStringCache.forEach { saved ->
                if (saved.present) {
                    stringCache[saved.key] = saved.value.orEmpty()
                } else {
                    stringCache.remove(saved.key)
                }
            }
        }
        runCatching { AppParasitics.hostApplication = savedHostApplication }
        runCatching { imageRoot.deleteRecursively() }
        runCatching { emptyRoot.deleteRecursively() }
    }

    @Test
    fun enqueueClosesImmediatelyAndTerminalResultsStillReachVisibleUi() {
        val first = ImageEntry.fromFile(writePng("first.png"))
        val firstGate = AsyncGate()
        val firstAction = asyncAction("deliver", firstGate)
        val firstPanel = openPanel(listOf(firstAction), firstAction.id)
        val firstDialog = checkNotNull(onMainGet { panelDialog(firstPanel) }) { "面板必须显示真实 Dialog" }
        val firstGeneration = onMainGet { imageGeneration(firstPanel) }
        assertTrue("面板打开后必须真实显示", onMainGet { firstDialog.isShowing })

        onMain {
            performActionWithFile(firstPanel, firstAction, first, first.localFile!!, firstGeneration)
        }
        assertTrue("宿主异步边界必须收到本次发送", firstGate.invoked.await(5, TimeUnit.SECONDS))
        val firstActive = checkNotNull(firstGate.active)
        assertTrue("入队之前面板仍可解析会话", firstActive())
        assertTrue("终态之前面板不能提前关闭", onMainGet { firstDialog.isShowing })
        assertTrue("终态成功之前绝不能记录历史，实际=${historyKeys()}", historyKeys().isEmpty())

        deliver { checkNotNull(firstGate.onEnqueued)() }
        assertTrue("onEnqueued 经主线程队列后必须关闭面板", awaitMain { !firstDialog.isShowing })
        assertTrue(
            "面板关闭后 OnDismissListener 必须派发，使会话 guard 失效",
            awaitMain { !firstActive() },
        )
        assertTrue("入队只是启动结果，不能记录历史，实际=${historyKeys()}", historyKeys().isEmpty())

        deliver { checkNotNull(firstGate.complete)(Result.success(Unit)) }
        assertTrue(
            "真实成功终态必须记录历史，实际=${historyKeys()}",
            awaitCondition(5_000) { historyKeys() == listOf(first.key) },
        )
        assertFalse("终态成功后面板保持关闭", onMainGet { firstDialog.isShowing })

        // 入队关闭之后才到达的真实失败终态也必须可见：Dialog 已经 detach，不能丢提示或记历史。
        val lateFailure = ImageEntry.fromFile(writePng("late-failure.png"))
        val failureGate = AsyncGate()
        val failureAction = asyncAction("late-failure", failureGate)
        val failurePanel = openPanel(listOf(failureAction), failureAction.id)
        val failureDialog = checkNotNull(onMainGet { panelDialog(failurePanel) })
        val failureGeneration = onMainGet { imageGeneration(failurePanel) }
        onMain {
            performActionWithFile(
                failurePanel,
                failureAction,
                lateFailure,
                lateFailure.localFile!!,
                failureGeneration,
            )
        }
        assertTrue(failureGate.invoked.await(5, TimeUnit.SECONDS))
        deliver { checkNotNull(failureGate.onEnqueued)() }
        assertTrue("第二次发送同样必须立即关闭", awaitMain { !failureDialog.isShowing })

        val failureToken = "image-panel-late-failure-${UUID.randomUUID()}"
        if (!ensureProbeWindowFocused()) {
            fail("真实终态失败提示必须在前台时产生：${foregroundSnapshot()}")
        }
        deliver {
            checkNotNull(failureGate.complete)(
                Result.failure(IllegalStateException(failureToken)),
            )
        }
        assertNotNull(
            "面板关闭后的真实终态失败必须可见（已收到 Toast：${toastTexts.toList()}）",
            awaitToast(failureToken),
        )
        assertTrue(
            "失败终态不能新增历史，实际=${historyKeys()}",
            historyKeys() == listOf(first.key),
        )
    }

    @Test
    fun startupFailureKeepsPanelOpenShowsVisibleErrorAndAllowsRetry() {
        val entry = ImageEntry.fromFile(writePng("retry.png"))
        val attempts = AtomicInteger()
        val activeSnapshots = CopyOnWriteArrayList<Boolean>()
        val lastActive = AtomicReference<() -> Boolean>()
        val secondAttempt = CountDownLatch(1)
        val failureToken = "image-panel-startup-failure-${UUID.randomUUID()}"
        val action = ImagePanelAction(
            id = "retry",
            label = "重试流程",
            iconRes = R.drawable.ic_send_emoticon,
            perform = { Result.success(Unit) },
            performAsync = { _, complete, active, onEnqueued ->
                lastActive.set(active)
                activeSnapshots += active()
                when (attempts.incrementAndGet()) {
                    1 -> complete(Result.failure(IllegalStateException(failureToken)))
                    else -> {
                        onEnqueued()
                        secondAttempt.countDown()
                    }
                }
            },
        )
        val panel = openPanel(listOf(action), action.id)
        val dialog = checkNotNull(onMainGet { panelDialog(panel) }) { "面板必须显示真实 Dialog" }
        assertTrue(onMainGet { dialog.isShowing })

        if (!ensureAppInForeground()) {
            fail("启动失败提示必须在前台时产生：${foregroundSnapshot()}")
        }
        onMain { performAction(panel, entry) }
        assertNotNull(
            "启动失败必须在面板可见时提示（已收到 Toast：${toastTexts.toList()}）",
            awaitToast(failureToken),
        )
        assertTrue("启动失败不能被当成入队，面板必须保持打开", onMainGet { dialog.isShowing })
        assertEquals("启动失败只调用一次异步边界", 1, attempts.get())
        assertTrue("启动失败时面板仍可解析会话", activeSnapshots.single())
        assertTrue("启动失败不得记录历史，实际=${historyKeys()}", historyKeys().isEmpty())

        onMain { performAction(panel, entry) }
        assertTrue(
            "启动失败释放防重后必须能再次尝试，实际尝试=${attempts.get()}",
            secondAttempt.await(10, TimeUnit.SECONDS),
        )
        assertTrue("第二次真实入队必须关闭面板", awaitMain { !dialog.isShowing })
        assertEquals("第二次发送仍然只进入一次异步边界", 2, attempts.get())
        val activeAfterClose = checkNotNull(lastActive.get())
        assertTrue(
            "面板关闭后 OnDismissListener 必须派发，使会话 guard 失效",
            awaitMain { !activeAfterClose() },
        )
        assertTrue("仅入队没有真实终态，不能记录历史，实际=${historyKeys()}", historyKeys().isEmpty())
    }

    @Test
    fun manualDismissDropsLateResultAndOldTerminalCannotCloseNewPanel() {
        // 1) 未入队的人工关闭：晚到成功必须完全忽略。
        val dropped = ImageEntry.fromFile(writePng("dropped.png"))
        val droppedGate = AsyncGate()
        val droppedAction = asyncAction("dropped", droppedGate)
        val droppedPanel = openPanel(listOf(droppedAction), droppedAction.id)
        val droppedDialog = checkNotNull(onMainGet { panelDialog(droppedPanel) })
        val droppedGeneration = onMainGet { imageGeneration(droppedPanel) }
        onMain {
            performActionWithFile(droppedPanel, droppedAction, dropped, dropped.localFile!!, droppedGeneration)
        }
        assertTrue(droppedGate.invoked.await(5, TimeUnit.SECONDS))
        val droppedActive = checkNotNull(droppedGate.active)
        assertTrue(droppedActive())
        onMain { droppedDialog.dismiss() }
        assertFalse("人工关闭必须立即生效", onMainGet { droppedDialog.isShowing })
        assertTrue(
            "人工关闭后 OnDismissListener 必须派发，使会话 guard 失效",
            awaitMain { !droppedActive() },
        )

        // 2) 已入队的发送先关闭面板，真实终态稍后才到。
        val queued = ImageEntry.fromFile(writePng("queued.png"))
        val queuedGate = AsyncGate()
        val queuedAction = asyncAction("queued", queuedGate)
        val queuedPanel = openPanel(listOf(queuedAction), queuedAction.id)
        val queuedDialog = checkNotNull(onMainGet { panelDialog(queuedPanel) })
        val queuedGeneration = onMainGet { imageGeneration(queuedPanel) }
        onMain {
            performActionWithFile(queuedPanel, queuedAction, queued, queued.localFile!!, queuedGeneration)
        }
        assertTrue(queuedGate.invoked.await(5, TimeUnit.SECONDS))
        deliver { checkNotNull(queuedGate.onEnqueued)() }
        assertTrue("入队必须立即关闭", awaitMain { !queuedDialog.isShowing })

        deliver { checkNotNull(droppedGate.complete)(Result.success(Unit)) }
        instrumentation.waitForIdleSync()
        assertTrue(
            "未入队的人工关闭必须丢弃晚到成功，实际=${historyKeys()}",
            historyKeys().isEmpty(),
        )

        // 3) 旧发送的终态晚于新面板打开：可以记历史，但绝不能关掉新面板。
        val sent = ImageEntry.fromFile(writePng("sync-sent.png"))
        val syncAction = ImagePanelAction(
            id = "sync",
            label = "同步发送",
            iconRes = R.drawable.ic_send_image,
            perform = { Result.success(Unit) },
        )
        val syncPanel = openPanel(listOf(syncAction), syncAction.id)
        val syncDialog = checkNotNull(onMainGet { panelDialog(syncPanel) })
        val syncGeneration = onMainGet { imageGeneration(syncPanel) }
        assertTrue(onMainGet { syncDialog.isShowing })

        deliver { checkNotNull(queuedGate.complete)(Result.success(Unit)) }
        assertTrue(
            "已入队发送的真实成功终态必须记录历史，实际=${historyKeys()}",
            awaitCondition(5_000) { historyKeys().toSet() == setOf(queued.key) },
        )
        instrumentation.waitForIdleSync()
        assertTrue("旧操作的终态不能关闭之后新打开的面板", onMainGet { syncDialog.isShowing })

        onMain { performActionWithFile(syncPanel, syncAction, sent, sent.localFile!!, syncGeneration) }
        assertTrue(
            "同步 perform 成功仍必须记录历史，实际=${historyKeys()}",
            awaitCondition(5_000) { historyKeys().toSet() == setOf(queued.key, sent.key) },
        )
        assertFalse("同步发送成功后必须关闭面板", onMainGet { syncDialog.isShowing })
        assertFalse("人工关闭的面板不能被重新打开", onMainGet { droppedDialog.isShowing })
        assertFalse("已入队关闭的面板不能被重新打开", onMainGet { queuedDialog.isShowing })
    }

    private inner class AsyncGate {
        @Volatile var complete: ((Result<Unit>) -> Unit)? = null
        @Volatile var active: (() -> Boolean)? = null
        @Volatile var onEnqueued: (() -> Unit)? = null
        val invoked = CountDownLatch(1)
    }

    private fun asyncAction(id: String, gate: AsyncGate) = ImagePanelAction(
        id = id,
        label = "流程-$id",
        iconRes = R.drawable.ic_send_emoticon,
        perform = { Result.success(Unit) },
        performAsync = { _, complete, active, onEnqueued ->
            gate.complete = complete
            gate.active = active
            gate.onEnqueued = onEnqueued
            gate.invoked.countDown()
        },
    )

    private fun shellCommand(command: String): String = runCatching {
        instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).bufferedReader().use { it.readText() }
        }
    }.getOrDefault("")

    /** 只唤醒屏幕/收起无密码 keyguard，不修改任何系统设置。 */
    private fun ensureScreenAwake() {
        shellCommand("input keyevent KEYCODE_WAKEUP")
        shellCommand("wm dismiss-keyguard")
    }

    /**
     * 复用同一个测试 Activity 和任务，启动前确认它真正 RESUMED 且窗口获得焦点。
     * 每个用例都重新起 Activity 会让任务栈短暂退回宿主（微信），探针随即被暂停。
     */
    private fun ensureProbeActivity() {
        val existingProbe = sharedProbe
        val reusable = existingProbe != null && runCatching {
            onMainGet {
                val probe = checkNotNull(existingProbe)
                !probe.isFinishing && !probe.isDestroyed &&
                    probe.window.decorView.isAttachedToWindow
            }
        }.getOrDefault(false)
        activity = if (reusable) {
            checkNotNull(existingProbe)
        } else {
            instrumentation.startActivitySync(
                Intent()
                    .setClassName(target.packageName, PROBE_ACTIVITY)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            ).also { launched ->
                sharedProbe = launched
                sharedInstrumentation = instrumentation
            }
        }
        onMain {
            // 仅作用于测试窗口：屏幕常亮、锁屏时也展示、唤醒屏幕，不改系统设置。
            activity.setShowWhenLocked(true)
            activity.setTurnScreenOn(true)
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        focusOwnTask()
        if (!ensureProbeWindowFocused()) {
            fail("测试 Activity 必须在打开面板前处于前台：${foregroundSnapshot()}")
        }
    }

    private fun focusOwnTask() {
        val taskId = runCatching { activity.taskId }.getOrDefault(-1)
        if (taskId > 0) shellCommand("am task focus $taskId")
    }

    private fun appInForeground(): Boolean {
        val resumed = shellCommand("dumpsys activity activities | grep -F topResumedActivity")
        val probeName = PROBE_ACTIVITY.substringAfterLast('.')
        if (!resumed.contains(target.packageName) || !resumed.contains(probeName)) return false
        val focus = shellCommand("dumpsys window windows | grep -E 'mCurrentFocus|mFocusedApp'")
        return focus.contains(target.packageName)
    }

    private fun foregroundSnapshot(): String {
        val resumed = shellCommand("dumpsys activity activities | grep -F topResumedActivity").trim()
        val focus = shellCommand("dumpsys window windows | grep -E 'mCurrentFocus|mFocusedApp'").trim()
        return "resumed=[$resumed] focus=[$focus]"
    }

    private fun ensureAppInForeground(timeoutMs: Long = 10_000): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        var lastFocus = 0L
        while (SystemClock.uptimeMillis() < deadline) {
            if (appInForeground()) return true
            val now = SystemClock.uptimeMillis()
            if (now - lastFocus >= 750) {
                lastFocus = now
                focusOwnTask()
            }
            SystemClock.sleep(120)
        }
        return appInForeground()
    }

    private fun ensureProbeWindowFocused(timeoutMs: Long = 10_000): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        var lastFocus = 0L
        while (SystemClock.uptimeMillis() < deadline) {
            val windowFocused = runCatching {
                onMainGet {
                    !activity.isFinishing && !activity.isDestroyed &&
                        activity.window.decorView.hasWindowFocus()
                }
            }.getOrDefault(false)
            if (windowFocused && appInForeground()) return true
            val now = SystemClock.uptimeMillis()
            if (now - lastFocus >= 750) {
                lastFocus = now
                focusOwnTask()
            }
            SystemClock.sleep(120)
        }
        return runCatching {
            onMainGet { activity.window.decorView.hasWindowFocus() }
        }.getOrDefault(false) && appInForeground()
    }

    /** 真实面板入口：与 companion show 相同的主题/资源注入，但保留实例以观察 Dialog 和触发生产方法。 */
    private fun openPanel(actions: List<ImagePanelAction>, initialActionId: String): SaveImagePanel {
        if (!ensureProbeWindowFocused()) {
            fail("打开图片面板前测试窗口必须处于前台：${foregroundSnapshot()}")
        }
        lateinit var panel: SaveImagePanel
        onMain {
            activity.injectModuleAppResources()
            val moduleLoader = SaveImagePanel::class.java.classLoader ?: activity.classLoader
            val moduleContext = Class.forName("com.qm.qqzygisk.hook.app.chat.ModuleContext", false, moduleLoader)
                .getDeclaredConstructor(Context::class.java, Int::class.javaPrimitiveType, ClassLoader::class.java)
                .apply { isAccessible = true }
                .newInstance(activity, R.style.Theme_QQZygisk_MaterialDialog, moduleLoader) as Context
            val constructor = SaveImagePanel::class.java.declaredConstructors.first { it.parameterCount == 5 }
            constructor.isAccessible = true
            panel = constructor.newInstance(
                moduleContext,
                emptyList<String>(),
                actions,
                initialActionId,
                null,
            ) as SaveImagePanel
            panel.show()
            val dialog = checkNotNull(panelDialog(panel)) { "面板必须显示真实 Dialog" }
            openedDialogs += dialog
        }
        return panel
    }

    private fun panelDialog(panel: SaveImagePanel): Dialog? {
        val field = SaveImagePanel::class.java.getDeclaredField("panelDialog")
        field.isAccessible = true
        return field.get(panel) as Dialog?
    }

    /** 测试临时改写图片目录配置，结束后连同进程内缓存一起还原，避免影响其他用例。 */
    @Suppress("UNCHECKED_CAST")
    private fun hookStringCache(): MutableMap<String, String> {
        val field = HookSettings::class.java.getDeclaredField("stringValues")
        field.isAccessible = true
        return field.get(HookSettings) as MutableMap<String, String>
    }

    private fun imageGeneration(panel: SaveImagePanel): Int {
        val field = SaveImagePanel::class.java.getDeclaredField("imageGeneration")
        field.isAccessible = true
        return field.getInt(panel)
    }

    private fun performActionWithFile(
        panel: SaveImagePanel,
        action: ImagePanelAction,
        entry: ImageEntry,
        file: File,
        generation: Int,
    ) {
        val method = SaveImagePanel::class.java.getDeclaredMethod(
            "performActionWithFile",
            ImagePanelAction::class.java,
            ImageEntry::class.java,
            File::class.java,
            Int::class.javaPrimitiveType,
        )
        method.isAccessible = true
        method.invoke(panel, action, entry, file, generation)
    }

    private fun performAction(panel: SaveImagePanel, entry: ImageEntry) {
        val method = SaveImagePanel::class.java.getDeclaredMethod("performAction", ImageEntry::class.java)
        method.isAccessible = true
        method.invoke(panel, entry)
    }

    private fun writePng(name: String): File {
        val file = File(imageRoot, name)
        val bitmap = Bitmap.createBitmap(12, 12, Bitmap.Config.ARGB_8888)
        try {
            FileOutputStream(file).use { output ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            }
        } finally {
            bitmap.recycle()
        }
        return file
    }

    private fun historyKeys(): List<String> =
        ImageFolderStore.images(ImageFolderStore.historyFolder()).map { it.key }

    private fun deliver(block: () -> Unit) {
        worker.submit(Runnable { block() }).get(10, TimeUnit.SECONDS)
    }

    private fun onMain(block: () -> Unit) {
        val failure = AtomicReference<Throwable>()
        instrumentation.runOnMainSync {
            try {
                block()
            } catch (error: Throwable) {
                failure.set(error)
            }
        }
        failure.get()?.let { throw it }
    }

    private fun <T> onMainGet(block: () -> T): T {
        val result = AtomicReference<T>()
        val failure = AtomicReference<Throwable>()
        instrumentation.runOnMainSync {
            try {
                result.set(block())
            } catch (error: Throwable) {
                failure.set(error)
            }
        }
        failure.get()?.let { throw it }
        return result.get()
    }

    private fun awaitMain(timeoutMs: Long = 5_000, condition: () -> Boolean): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (onMainGet(condition)) return true
            SystemClock.sleep(20)
        }
        return onMainGet(condition)
    }

    private fun awaitCondition(timeoutMs: Long = 5_000, condition: () -> Boolean): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return true
            SystemClock.sleep(25)
        }
        return condition()
    }

    /**
     * 只确认本次操作唯一的错误 token 出现在系统 Toast 上：
     * 优先取 Toast 的辅助功能事件，其次读 `dumpsys notification` 的 Toast 队列，
     * 两者都不修改系统设置，也不依赖生产文案的其余部分。
     */
    private fun awaitToast(token: String, timeoutMs: Long = 8_000): String? {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        var nextDump = 0L
        while (SystemClock.uptimeMillis() < deadline) {
            toastTexts.firstOrNull { it.contains(token) }?.let { return it }
            val now = SystemClock.uptimeMillis()
            if (now >= nextDump) {
                nextDump = now + TOAST_DUMP_INTERVAL_MS
                if (toastDumpContains(token)) return "dumpsys:$token"
            }
            SystemClock.sleep(50)
        }
        return toastTexts.firstOrNull { it.contains(token) }
    }

    private fun toastDumpContains(token: String): Boolean =
        shellCommand("dumpsys notification | grep -F $token").contains(token)
}
