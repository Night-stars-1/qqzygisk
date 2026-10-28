package com.qm.qqzygisk.hook.app.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.Closeable
import java.io.File

/**
 * 发送前尺寸处理：只有超出微信原生上限的静态 JPEG/PNG 才生成等比缩小的 PNG 发送副本。
 *
 * 尺寸内不创建文件、不做全图解码，原文件字节保持不变；GIF/WEBP 超限直接拒绝，
 * 不重编码动图也不静图化。临时副本由 [PreparedWeChatEmoticon] 独占并在关闭时删除，
 * 调用方传入的文件从不删除。微信原生导入会把字节复制进自己的表情目录，因此副本
 * 只需存活到 [WeChatEmoticonSubmission.send] 返回。
 */
internal object WeChatEmoticonSendImage {
    fun directory(context: Context): File = File(context.cacheDir, "qqzygisk/wechat-emoji")

    /**
     * @param limit 宿主配置的原生表情宽高上限，由 [WeChatEmojiProtocol.readSizeLimit] 读取。
     * @param directory 临时副本目录；仅在需要缩放时创建。
     */
    fun prepare(source: LocalEmoticonFile, limit: Int, directory: File): PreparedWeChatEmoticon {
        require(limit > 0) { "微信表情尺寸上限无效：$limit" }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.file.absolutePath, bounds)
        val width = bounds.outWidth
        val height = bounds.outHeight
        check(width > 0 && height > 0) { "无法读取表情尺寸，已取消发送" }
        if (width <= limit && height <= limit) return PreparedWeChatEmoticon.of(source, null)

        val size = "${width}x$height"
        val maximum = "${limit}x$limit"
        when (source.format) {
            LocalEmoticonFormat.JPEG, LocalEmoticonFormat.PNG -> Unit
            LocalEmoticonFormat.GIF ->
                error("GIF 尺寸 $size 超出微信表情上限 $maximum，不重编码动图，已取消发送")
            LocalEmoticonFormat.WEBP ->
                error("WEBP 尺寸 $size 超出微信表情上限 $maximum，仅 JPEG/PNG 可自动缩小，已取消发送")
        }

        val longest = maxOf(width, height)
        val targetWidth = scaledDimension(width, longest, limit)
        val targetHeight = scaledDimension(height, longest, limit)
        ImageDirectoryAccess.ensureDirectory(directory)
        val copy = File.createTempFile("wechat-emoticon-", ".png", directory)
        return try {
            val decoded = checkNotNull(
                BitmapFactory.decodeFile(
                    source.file.absolutePath,
                    BitmapFactory.Options().apply {
                        // 按长边采样，长边解码不超过目标的两倍；短边由解码器保底 1 像素。
                        inSampleSize = sampleSize(width, height, targetWidth, targetHeight)
                        // 显式 8888，PNG 透明度在缩放和重新编码后保持不变。
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    },
                ),
            ) { "无法解码表情图片，已取消发送" }
            try {
                val target = if (decoded.width == targetWidth && decoded.height == targetHeight) {
                    decoded
                } else {
                    Bitmap.createScaledBitmap(decoded, targetWidth, targetHeight, true)
                }
                try {
                    copy.outputStream().use { output ->
                        check(target.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                            "无法写出缩放后的表情"
                        }
                    }
                } finally {
                    if (target !== decoded) target.recycle()
                }
            } finally {
                decoded.recycle()
            }
            PreparedWeChatEmoticon.of(LocalEmoticonFile.inspect(copy), copy)
        } catch (error: Throwable) {
            copy.delete()
            throw error
        }
    }

    /** 半像素进位取整：长边精确等于上限，短边等比且不超过上限。 */
    private fun scaledDimension(value: Int, longest: Int, limit: Int): Int =
        ((value.toLong() * limit + longest / 2) / longest).toInt().coerceIn(1, limit)

    /**
     * 只用长边目标选择 2 的幂采样：解码长边落在 [目标, 2×目标) 内；短边交给
     * BitmapFactory（其采样后尺寸至少 1 像素），之后仍按源比例精确缩放。
     */
    private fun sampleSize(width: Int, height: Int, targetWidth: Int, targetHeight: Int): Int {
        val longest = maxOf(width, height)
        val longestTarget = maxOf(targetWidth, targetHeight)
        var sample = 1
        // 先除后比较，只有 sample * 2 <= longest 时才翻倍，避免乘法溢出。
        while (longest / sample / 2 >= longestTarget) sample *= 2
        return sample
    }
}

/**
 * 一次发送实际使用的文件。
 *
 * [scaled] 为 true 时拥有一个临时 PNG 副本，[close] 删除它；尺寸内直接引用调用方原文件，
 * 从不删除。关闭是幂等的。
 */
internal class PreparedWeChatEmoticon private constructor(
    val source: LocalEmoticonFile,
    private val temporary: File?,
) : Closeable {
    val scaled: Boolean get() = temporary != null

    override fun close() {
        // File.delete() 对已不存在的文件返回 false 且不抛异常。
        temporary?.delete()
    }

    companion object {
        internal fun of(source: LocalEmoticonFile, temporary: File?): PreparedWeChatEmoticon =
            PreparedWeChatEmoticon(source, temporary)
    }
}
