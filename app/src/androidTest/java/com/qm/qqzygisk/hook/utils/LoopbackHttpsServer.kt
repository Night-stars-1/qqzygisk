package com.qm.qqzygisk.hook.utils

import android.content.Context
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.security.KeyStore
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory

/**
 * 仅限 instrumentation 测试 APK 使用的本机回环 TLS HTTP/1.1 服务器。
 *
 * 私钥与证书只用于 `127.0.0.1/localhost` 的本地测试连接，放在 androidTest assets，
 * 不进入生产 APK，也不是任何账号或生产 TLS 凭据。客户端由 [trustLoopbackHttps] 或
 * [LoopbackTls.clientSocketFactory] 显式信任该证书，不卸载/不放宽生产 TrustManager。
 *
 * 服务器按连接顺序记录请求行与请求头（Range / If-Range），并允许脚本化响应：
 * 已知长度慢速写、chunked 未知长度、写一半 RST 截断、HTTP 错误码、忽略 Range 的整包 200。
 */
internal class LoopbackHttpsServer(
    testContext: Context,
    private val responder: (Request) -> Response,
) : Closeable {

    data class Request(
        val index: Int,
        val method: String,
        val target: String,
        val path: String,
        val headers: Map<String, String>,
    ) {
        val range: String? get() = headers["range"]
        val ifRange: String? get() = headers["if-range"]
        fun header(name: String): String? = headers[name.lowercase()]
    }

    data class Response(
        val code: Int = 200,
        val headers: Map<String, String> = emptyMap(),
        val body: ByteArray = ByteArray(0),
        /** true 时使用 Transfer-Encoding: chunked 且不发送 Content-Length。 */
        val chunked: Boolean = false,
        val writeChunkBytes: Int = 8 * 1024,
        val delayPerChunkMs: Long = 0L,
        /** 非 null 时只写这么多字节后以 RST 断开，模拟真实传输截断。 */
        val abortAfterBytes: Int? = null,
        val reason: String? = null,
    )

    private val serverContext: SSLContext = LoopbackTls.serverContext(testContext)
    private val serverSocket: SSLServerSocket =
        (serverContext.serverSocketFactory.createServerSocket(
            0,
            32,
            InetAddress.getByName(HOST),
        ) as SSLServerSocket)

    private val clients: MutableSet<Socket> =
        Collections.newSetFromMap(ConcurrentHashMap<Socket, Boolean>())
    private val connectionCounter = AtomicInteger(0)
    private val requestCounter = AtomicInteger(0)
    private val stopped = java.util.concurrent.atomic.AtomicBoolean(false)

    private val acceptThread = Thread({ acceptLoop() }, "loopback-tls-accept").apply {
        isDaemon = true
        start()
    }

    val requests = CopyOnWriteArrayList<Request>()

    /** 服务端写正文时发现客户端已断开（取消）的次数。 */
    @Volatile
    var abortedConnections: Int = 0
        private set

    val port: Int get() = serverSocket.localPort

    /** 例如 `https://127.0.0.1:41234`。 */
    val baseUrl: String get() = "https://$HOST:$port"

    fun url(path: String): String = baseUrl + path

    private fun acceptLoop() {
        while (!stopped.get()) {
            val client = try {
                serverSocket.accept()
            } catch (_: IOException) {
                break
            }
            clients.add(client)
            val index = connectionCounter.incrementAndGet()
            Thread({ handle(client, index) }, "loopback-tls-conn-$index").apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun handle(client: Socket, connection: Int) {
        try {
            client.soTimeout = 20_000
            val input = BufferedInputStream(client.getInputStream())
            val requestLine = readAsciiLine(input) ?: return
            val parts = requestLine.split(' ')
            if (parts.size < 3) return
            val headers = LinkedHashMap<String, String>()
            while (true) {
                val line = readAsciiLine(input) ?: break
                if (line.isEmpty()) break
                val separator = line.indexOf(':')
                if (separator > 0) {
                    headers[line.substring(0, separator).trim().lowercase()] =
                        line.substring(separator + 1).trim()
                }
            }
            val target = parts[1]
            val request = Request(
                index = requestCounter.getAndIncrement(),
                method = parts[0],
                target = target,
                path = target.substringBefore('?'),
                headers = headers,
            )
            requests.add(request)
            writeResponse(client, responder(request), request)
        } catch (_: IOException) {
            // 客户端在请求完成前断开：属于测试场景（取消/截断），不是夹具失败。
        } finally {
            clients.remove(client)
            runCatching { client.close() }
        }
    }

    private fun writeResponse(client: Socket, response: Response, request: Request) {
        val out = BufferedOutputStream(client.getOutputStream())
        val headers = LinkedHashMap(response.headers)
        headers.putIfAbsent("Connection", "close")
        if (response.chunked) {
            headers["Transfer-Encoding"] = "chunked"
        } else if (response.code != 204 && response.code != 304) {
            headers["Content-Length"] = response.body.size.toString()
        }
        val head = buildString {
            append("HTTP/1.1 ").append(response.code).append(' ')
                .append(response.reason ?: defaultReason(response.code)).append("\r\n")
            headers.forEach { (name, value) -> append(name).append(": ").append(value).append("\r\n") }
            append("\r\n")
        }
        runCatching { out.write(head.toByteArray(Charsets.ISO_8859_1)); out.flush() }
            .onFailure { return }

        val truncateAt = response.abortAfterBytes
        val limit = truncateAt?.coerceIn(0, response.body.size) ?: response.body.size
        var written = 0
        try {
            while (written < limit) {
                val count = minOf(response.writeChunkBytes.coerceAtLeast(1), limit - written)
                if (response.chunked) {
                    out.write((Integer.toHexString(count) + "\r\n").toByteArray(Charsets.ISO_8859_1))
                    out.write(response.body, written, count)
                    out.write("\r\n".toByteArray(Charsets.ISO_8859_1))
                } else {
                    out.write(response.body, written, count)
                }
                out.flush()
                written += count
                if (response.delayPerChunkMs > 0 && written < limit) {
                    Thread.sleep(response.delayPerChunkMs)
                }
            }
            if (truncateAt != null && truncateAt < response.body.size) {
                // 真实截断：声明了完整 Content-Length，但只送出 limit 字节后 RST。
                runCatching { client.setSoLinger(true, 0) }
                runCatching { client.close() }
                return
            }
            if (response.chunked) {
                out.write("0\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
                out.flush()
            }
            // 确保客户端读到声明的全部字节后再关闭（Connection: close 表示正文结束）。
            runCatching { out.flush() }
            if (request.method == "HEAD") return
        } catch (_: IOException) {
            if (written < response.body.size) abortedConnections++
            return
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            if (written < response.body.size) abortedConnections++
            return
        }
    }

    override fun close() {
        if (!stopped.compareAndSet(false, true)) return
        runCatching { serverSocket.close() }
        acceptThread.join(2_000)
        clients.forEach { runCatching { it.close() } }
        clients.clear()
        runCatching { acceptThread.join(2_000) }
    }

    private fun readAsciiLine(input: BufferedInputStream): String? {
        val buffer = StringBuilder(128)
        while (true) {
            val byte = input.read()
            if (byte == -1) return if (buffer.isEmpty()) null else buffer.toString()
            when (byte) {
                '\n'.code -> return buffer.toString().trimEnd('\r')
                '\r'.code -> Unit
                else -> {
                    buffer.append(byte.toChar())
                    if (buffer.length > 16 * 1024) throw IOException("请求头过长")
                }
            }
        }
    }

    private fun defaultReason(code: Int): String = when (code) {
        200 -> "OK"
        206 -> "Partial Content"
        416 -> "Range Not Satisfiable"
        500 -> "Internal Server Error"
        503 -> "Service Unavailable"
        else -> "Status"
    }

    companion object {
        const val HOST = "127.0.0.1"
    }
}

/** 只把本地测试证书装入一个独立 SSLContext；不会修改 JVM/进程默认值。 */
internal object LoopbackTls {
    private const val KEYSTORE_ASSET = "downloadtls/loopback-test.p12"
    private const val KEYSTORE_PASSWORD = "loopback"

    private fun keyStore(testContext: Context): KeyStore {
        val store = KeyStore.getInstance("PKCS12")
        testContext.assets.open(KEYSTORE_ASSET).use { input ->
            store.load(input, KEYSTORE_PASSWORD.toCharArray())
        }
        return store
    }

    fun serverContext(testContext: Context): SSLContext {
        val store = keyStore(testContext)
        val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        keyManagers.init(store, KEYSTORE_PASSWORD.toCharArray())
        return SSLContext.getInstance("TLS").apply {
            init(keyManagers.keyManagers, null, null)
        }
    }

    /** 客户端仅信任测试证书，仍然执行真实 TLS 握手与主机名校验。 */
    fun clientContext(testContext: Context): SSLContext {
        val trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        trustManagers.init(keyStore(testContext))
        return SSLContext.getInstance("TLS").apply {
            init(null, trustManagers.trustManagers, null)
        }
    }

    fun clientSocketFactory(testContext: Context): SSLSocketFactory =
        clientContext(testContext).socketFactory

    /**
     * 临时安装测试证书为进程默认 HttpsURLConnection trust，返回的 [Closeable] 必须在
     * finally 中关闭以恢复原 factory/hostnameVerifier。只改测试进程，不写系统证书。
     */
    fun installProcessDefaults(testContext: Context): Closeable {
        val factory = clientSocketFactory(testContext)
        val previousFactory = HttpsURLConnection.getDefaultSSLSocketFactory()
        val previousVerifier = HttpsURLConnection.getDefaultHostnameVerifier()
        HttpsURLConnection.setDefaultSSLSocketFactory(factory)
        return Closeable {
            HttpsURLConnection.setDefaultSSLSocketFactory(previousFactory)
            HttpsURLConnection.setDefaultHostnameVerifier(previousVerifier)
        }
    }
}

/** 真实 ZIP 字节与摘要工具：测试中的完整结果都用真实压缩包比对，不使用 JSON/文本回声。 */
internal object FlowZip {
    fun build(entries: List<Pair<String, ByteArray>>): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    fun randomBytes(seed: Long, size: Int): ByteArray {
        val bytes = ByteArray(size)
        java.util.Random(seed).nextBytes(bytes)
        return bytes
    }

    fun readEntries(zipBytes: ByteArray): Map<String, ByteArray> {
        val result = LinkedHashMap<String, ByteArray>()
        ZipInputStream(zipBytes.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                result[entry.name] = zip.readBytes()
                zip.closeEntry()
            }
        }
        return result
    }

    fun sha256(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
