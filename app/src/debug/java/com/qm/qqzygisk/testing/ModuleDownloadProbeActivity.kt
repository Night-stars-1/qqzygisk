package com.qm.qqzygisk.testing

import android.app.Activity
import android.os.Bundle
import android.os.Process
import android.util.AtomicFile
import android.util.Base64
import com.qm.qqzygisk.hook.utils.ModuleDownload
import com.qm.qqzygisk.hook.utils.ModuleDownloadProgress
import com.qm.qqzygisk.hook.utils.ModuleDownloadStatus
import com.qm.qqzygisk.hook.utils.ModuleDownloads
import com.qm.qqzygisk.hook.utils.ModuleRelease
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

/**
 * Debug-only（release 不包含），单独进程 `:module_download_probe` 的真实下载宿主。
 *
 * 用于验证“进程被系统/用户强停后 load 自动续传”的完整业务流：instrumentation 在主进程
 * 运行本机回环 TLS 服务器并通过本 Activity 创建下载，随后按快照里的 pid 杀掉这个副进程
 * （同 UID，只杀该进程），再以 [MODE_RESTORE] 启动一个新进程，由
 * [ModuleDownloads.load] 自行恢复 `.part` 续传。
 *
 * 测试用 PKCS12 证书由 instrumentation 从 test APK assets 读出后以 Base64 extra 传入，
 * 只安装在本探针进程的 HttpsURLConnection 默认 trust，不写入系统证书、不影响 release
 * TLS，也不提供任何生产下载入口。所有进度/pid/公开 URI 以 AtomicFile 写入本应用私有
 * 目录的 `qqzygisk-download-probe.json`，供同 UID 的 instrumentation 读取。
 */
class ModuleDownloadProbeActivity : Activity() {

    companion object {
        const val EXTRA_MODE = "com.qm.qqzygisk.testing.extra.DOWNLOAD_PROBE_MODE"
        const val EXTRA_ZIP_URL = "com.qm.qqzygisk.testing.extra.DOWNLOAD_PROBE_ZIP_URL"
        const val EXTRA_VERSION = "com.qm.qqzygisk.testing.extra.DOWNLOAD_PROBE_VERSION"
        const val EXTRA_VERSION_CODE = "com.qm.qqzygisk.testing.extra.DOWNLOAD_PROBE_VERSION_CODE"
        const val EXTRA_KEYSTORE = "com.qm.qqzygisk.testing.extra.DOWNLOAD_PROBE_KEYSTORE"
        const val EXTRA_KEYSTORE_PASSWORD =
            "com.qm.qqzygisk.testing.extra.DOWNLOAD_PROBE_KEYSTORE_PASSWORD"
        const val EXTRA_RUN_TOKEN =
            "com.qm.qqzygisk.testing.extra.DOWNLOAD_PROBE_RUN_TOKEN"
        const val MODE_START = "start"
        const val MODE_RESTORE = "restore"
        const val SNAPSHOT_NAME = "qqzygisk-download-probe.json"

        /** 进程内串行化快照写入：Activity 重建时新旧协程不会写同一个 `.new`。 */
        private val snapshotLock = Any()

        /** 只有最新 Activity 实例可以写快照，旧实例的晚到写入/Error 一律丢弃。 */
        private val activeToken = AtomicReference<Any?>(null)
    }

    private val instanceToken = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val probeMode: String by lazy {
        intent.getStringExtra(EXTRA_MODE) ?: MODE_START
    }
    private val probeToken: String by lazy {
        intent.getStringExtra(EXTRA_RUN_TOKEN).orEmpty()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        activeToken.set(instanceToken)
        try {
            installTestTls()
        } catch (error: Throwable) {
            writeError(error)
            return
        }
        scope.launch {
            try {
                runDownloadFlow()
            } catch (cancelled: CancellationException) {
                // Activity 重建/销毁触发的取消不是下载失败，绝不能写 Error 覆盖新实例的快照。
                throw cancelled
            } catch (error: Throwable) {
                writeError(error)
            }
        }
    }

    override fun onDestroy() {
        activeToken.compareAndSet(instanceToken, null)
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun runDownloadFlow() {
        val app = applicationContext
        val download: ModuleDownload = when (probeMode) {
            MODE_RESTORE -> requireNotNull(ModuleDownloads.load(app)) { "load 未恢复任何下载任务" }
            else -> {
                val url = requireNotNull(intent.getStringExtra(EXTRA_ZIP_URL)) { "缺少下载地址" }
                val version = intent.getStringExtra(EXTRA_VERSION) ?: "probe"
                val versionCode = intent.getLongExtra(EXTRA_VERSION_CODE, 1L)
                ModuleDownloads.start(app, ModuleRelease(version, versionCode, url, null))
            }
        }
        writeSnapshot(download, ModuleDownloads.query(app, download))
        ModuleDownloads.observe(app, download).collect { progress ->
            writeSnapshot(progress.download, progress)
        }
    }

    private fun installTestTls() {
        val encoded = requireNotNull(intent.getStringExtra(EXTRA_KEYSTORE)) { "缺少测试证书" }
        val password = intent.getStringExtra(EXTRA_KEYSTORE_PASSWORD) ?: "loopback"
        val store = KeyStore.getInstance("PKCS12")
        Base64.decode(encoded, Base64.NO_WRAP).inputStream().use { input ->
            store.load(input, password.toCharArray())
        }
        val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        trust.init(store)
        val context = SSLContext.getInstance("TLS")
        context.init(null, trust.trustManagers, null)
        HttpsURLConnection.setDefaultSSLSocketFactory(context.socketFactory)
    }

    private fun writeSnapshot(download: ModuleDownload, progress: ModuleDownloadProgress) {
        writeJson(JSONObject().apply {
            put("pid", Process.myPid())
            put("mode", probeMode)
            put("run", probeToken)
            put("id", download.id)
            put("version", download.version)
            put("versionCode", download.versionCode)
            put("fileName", download.fileName)
            put("status", progress.status.name)
            put("received", progress.receivedBytes)
            put("total", progress.totalBytes)
            put("message", progress.message)
            if (progress.status == ModuleDownloadStatus.Complete) {
                // 只有真实可读的公开 URI 才写入快照；失败让 runDownloadFlow 抛错，
                // 主测试会看到 status=Error 而不是一直等到超时。
                val uri = ModuleDownloads.openIntent(applicationContext, download).data
                    ?: error("完成的任务没有返回公开 URI")
                put("uri", uri.toString())
            }
        })
    }

    private fun writeError(error: Throwable) {
        writeJson(JSONObject().apply {
            put("pid", Process.myPid())
            put("run", probeToken)
            put("status", "Error")
            put("message", error.message ?: "")
            put("error", error.javaClass.name)
        })
    }

    private fun writeJson(json: JSONObject) {
        if (activeToken.get() !== instanceToken) return
        synchronized(snapshotLock) {
            // 双检：等锁期间 Activity 可能已被重建/销毁。
            if (activeToken.get() !== instanceToken) return
            val atomic = AtomicFile(File(filesDir, SNAPSHOT_NAME))
            val output = atomic.startWrite()
            try {
                output.write(json.toString().toByteArray(Charsets.UTF_8))
                output.flush()
                runCatching { output.fd.sync() }
                atomic.finishWrite(output)
            } catch (error: Throwable) {
                atomic.failWrite(output)
                throw error
            }
        }
    }
}
