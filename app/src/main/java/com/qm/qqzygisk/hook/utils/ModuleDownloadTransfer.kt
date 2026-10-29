package com.qm.qqzygisk.hook.utils

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 模块 ZIP 的真实网络传输：HttpURLConnection 手动跟随有限次 HTTPS 重定向。
 *
 * 这里只负责“一次请求”的语义（状态码、Content-Range、验证器），本地 `.part` 文件的
 * 所有权、截断与是否追加由 [ModuleDownloads] 决定。所有成员都是阻塞的，
 * 调用方必须在 IO 线程执行；[close] 可以从任意线程调用以中断进行中的读取。
 * 连接建立阶段的句柄通过 `onConnection` 提前交给调用方，取消时同样可以断开。
 *
 * 安全约束：请求地址与每一跳重定向都必须是 HTTPS；不支持降级到 HTTP。
 * 断点续传只在持有 strong ETag（或 Last-Modified）时通过 `Range` + `If-Range` 发起，
 * 弱 ETag 不能用于 `If-Range`，此时退回整包下载，由调用方先截断再写。
 */
internal class ModuleDownloadTransfer private constructor(
    private val connection: HttpURLConnection,
    val stream: InputStream,
    val partial: Boolean,
    val totalBytes: Long,
    val contentRangeStart: Long,
    val contentRangeTotal: Long,
    val etag: String?,
    val lastModified: String?,
    val finalUrl: String,
) : Closeable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            stream.close()
        } catch (_: Throwable) {
        }
        try {
            connection.disconnect()
        } catch (_: Throwable) {
        }
    }

    /** 服务器拒绝 Range 请求（HTTP 416），调用方可以在同一任务身份下从头重新下载。 */
    class ResumeRejected(message: String) : IOException(message)

    /** 206 响应与本地已有部分文件/验证器不匹配：必须当作错误，不能拼接出损坏的 ZIP。 */
    class InvalidRange(message: String) : IOException(message)

    companion object {
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 15_000
        private const val MAX_REDIRECTS = 5
        private const val USER_AGENT = "qqhook-module-updater"
        private val contentRange = Regex("^bytes\\s+(\\d+)-(\\d+)/(\\d+|\\*)$", RegexOption.IGNORE_CASE)

        private class Validator(val value: String, val isDate: Boolean)

        private class ByteRange(val start: Long, val end: Long, val total: Long)

        /**
         * 发起一次下载请求。
         *
         * @param offset 本地已有字节数；只有存在可用的 strong 验证器时才会附带 `Range`/`If-Range`。
         * @param expectedTotal 上一次响应声明的总长度（-1 表示未知），用于校验 206 的完整性。
         * @param onConnection 连接一建立就回调（此时还没有发送请求），让调用方能够在取消时断开它。
         * @throws ResumeRejected 服务器不接受断点请求（416）。
         * @throws InvalidRange 206 响应的位置、长度或验证器与本地不一致。
         */
        fun open(
            url: String,
            offset: Long = 0L,
            etag: String? = null,
            lastModified: String? = null,
            expectedTotal: Long = -1L,
            onConnection: ((HttpURLConnection) -> Unit)? = null,
        ): ModuleDownloadTransfer {
            var current = https(url)
            var redirects = 0
            while (true) {
                val connection = current.openConnection() as? HttpURLConnection
                    ?: throw IOException("下载链接不支持 HTTP 下载")
                try {
                    onConnection?.invoke(connection)
                    configure(connection)
                    val sent = if (offset > 0L) resumableValidator(etag, lastModified) else null
                    if (sent != null) {
                        connection.setRequestProperty("Range", "bytes=$offset-")
                        connection.setRequestProperty("If-Range", sent.value)
                    }
                    val status = connection.responseCode
                    if (status in 300..399) {
                        val location = connection.getHeaderField("Location")
                            ?: throw IOException("下载重定向缺少目标地址（HTTP $status）")
                        redirects++
                        if (redirects > MAX_REDIRECTS) throw IOException("下载链接重定向过多")
                        val next = try {
                            URL(current, location)
                        } catch (error: MalformedURLException) {
                            throw IOException("下载重定向地址无效", error)
                        }
                        current = https(next.toExternalForm())
                        connection.disconnect()
                        continue
                    }
                    val responseEtag = connection.getHeaderField("ETag")
                    val responseModified = connection.getHeaderField("Last-Modified")
                    when (status) {
                        HttpURLConnection.HTTP_OK -> {
                            val length = connection.contentLengthLong
                            return ModuleDownloadTransfer(
                                connection = connection,
                                stream = connection.inputStream,
                                partial = false,
                                totalBytes = length,
                                contentRangeStart = -1L,
                                contentRangeTotal = length,
                                etag = responseEtag,
                                lastModified = responseModified,
                                finalUrl = current.toExternalForm(),
                            )
                        }

                        HttpURLConnection.HTTP_PARTIAL -> {
                            val range = parseContentRange(connection.getHeaderField("Content-Range"))
                                ?: throw InvalidRange("服务器返回了无法识别的断点响应")
                            if (sent == null || offset <= 0L || range.start != offset) {
                                throw InvalidRange("服务器返回的断点位置与本地文件不一致")
                            }
                            if (range.end < range.start) throw InvalidRange("服务器返回的断点范围无效")
                            // 总长度未知（`bytes x-y/*`）时无法证明 EOF 就是实体末尾，
                            // 继续追加可能把不完整内容标成完成：退回整包重下。
                            if (range.total <= 0L) {
                                throw ResumeRejected("服务器未返回完整长度，无法校验断点")
                            }
                            if (range.total <= range.end) {
                                throw InvalidRange("服务器返回的断点总长度无效")
                            }
                            val declared = connection.contentLengthLong
                            if (declared >= 0L && declared != range.end - range.start + 1L) {
                                throw InvalidRange("服务器返回的断点长度与声明不一致")
                            }
                            // 用 If-Range 验证过的那一个验证器做复核，验证器不同就不能追加。
                            val responded = if (sent.isDate) responseModified else responseEtag
                            if (responded != null && responded.trim() != sent.value) {
                                throw InvalidRange("服务器上的文件已变化，请重新下载")
                            }
                            if (expectedTotal > 0L && range.total != expectedTotal) {
                                throw InvalidRange("下载文件在服务器上已变化，请重新下载")
                            }
                            return ModuleDownloadTransfer(
                                connection = connection,
                                stream = connection.inputStream,
                                partial = true,
                                totalBytes = range.total,
                                contentRangeStart = range.start,
                                contentRangeTotal = range.total,
                                etag = responseEtag,
                                lastModified = responseModified,
                                finalUrl = current.toExternalForm(),
                            )
                        }

                        // 416 Range Not Satisfiable（HttpURLConnection 没有对应常量）
                        416 -> throw ResumeRejected("服务器无法继续此断点（HTTP 416）")

                        else -> throw IOException("下载失败：HTTP $status")
                    }
                } catch (error: Throwable) {
                    connection.disconnect()
                    throw error
                }
            }
        }

        private fun configure(connection: HttpURLConnection) {
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.setRequestProperty("User-Agent", USER_AGENT)
            // 关闭压缩，保证进度就是真实下载字节数，也让 Range 偏移可用。
            connection.setRequestProperty("Accept-Encoding", "identity")
        }

        private fun https(value: String): URL {
            val url = try {
                URL(value)
            } catch (error: MalformedURLException) {
                throw IOException("下载链接无效：${error.message ?: "地址格式错误"}", error)
            }
            if (!url.protocol.equals("https", ignoreCase = true)) throw IOException("下载链接必须使用 HTTPS")
            return url
        }

        /**
         * `If-Range` 只能使用 strong 验证器：优先 strong ETag，其次 Last-Modified；
         * 弱 ETag（`W/"…"`）或没有验证器时返回 null，调用方只能整包重新下载。
         */
        private fun resumableValidator(etag: String?, lastModified: String?): Validator? {
            val tag = etag?.trim()?.takeIf { it.isNotEmpty() && !it.startsWith("W/", ignoreCase = true) }
            if (tag != null) return Validator(tag, isDate = false)
            val modified = lastModified?.trim()?.takeIf { it.isNotEmpty() }
            if (modified != null) return Validator(modified, isDate = true)
            return null
        }

        /** 解析 `Content-Range: bytes <start>-<end>/<total>`；`*` 表示总长度未知。 */
        private fun parseContentRange(value: String?): ByteRange? {
            val match = value?.trim()?.let { contentRange.find(it) } ?: return null
            val start = match.groupValues[1].toLongOrNull() ?: return null
            val end = match.groupValues[2].toLongOrNull() ?: return null
            val total = if (match.groupValues[3] == "*") -1L else match.groupValues[3].toLongOrNull() ?: return null
            return ByteRange(start, end, total)
        }
    }
}
