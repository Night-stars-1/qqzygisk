package com.qm.qqzygisk.hook.utils

import android.graphics.ImageDecoder
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.view.View
import android.widget.ImageView
import java.io.File
import java.nio.ByteBuffer
import java.util.WeakHashMap
import kotlin.math.roundToInt

/** Decodes static and animated images while keeping their largest side bounded. */
object AnimatedImageLoader {
    private val bindings = WeakHashMap<ImageView, AnimationBinding>()

    fun decode(file: File, maxSize: Int): Drawable? =
        decode(ImageDecoder.createSource(file), maxSize, fileLooksAnimated(file.name))

    fun decode(bytes: ByteArray, maxSize: Int): Drawable? =
        decode(
            ImageDecoder.createSource(ByteBuffer.wrap(bytes)),
            maxSize,
            bytesLookAnimated(bytes),
        )

    fun bind(view: ImageView, drawable: Drawable) {
        if (view.drawable === drawable) {
            val animation = drawable as? Animatable ?: return
            if (view.isAttachedToWindow && !animation.isRunning) animation.start()
            return
        }
        clear(view)
        view.setImageDrawable(drawable)

        val animation = drawable as? Animatable ?: return
        val listener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) {
                animation.start()
            }

            override fun onViewDetachedFromWindow(view: View) {
                animation.stop()
            }
        }
        synchronized(bindings) {
            bindings[view] = AnimationBinding(animation, listener)
        }
        view.addOnAttachStateChangeListener(listener)
        if (view.isAttachedToWindow) animation.start()
    }

    fun clear(view: ImageView) {
        val binding = synchronized(bindings) { bindings.remove(view) }
        if (binding != null) {
            view.removeOnAttachStateChangeListener(binding.listener)
            binding.animation.stop()
        } else {
            (view.drawable as? Animatable)?.stop()
        }
        view.setImageDrawable(null)
    }

    private fun decode(
        source: ImageDecoder.Source,
        maxSize: Int,
        maybeAnimated: Boolean,
    ): Drawable? = runCatching {
        ImageDecoder.decodeDrawable(source) { decoder, info, _ ->
            // setTargetSize flattens GIF/WebP to a still BitmapDrawable.
            if (info.isAnimated || maybeAnimated) return@decodeDrawable
            val width = info.size.width
            val height = info.size.height
            val longestSide = maxOf(width, height)
            if (maxSize > 0 && longestSide > maxSize) {
                val scale = maxSize.toFloat() / longestSide
                decoder.setTargetSize(
                    (width * scale).roundToInt().coerceAtLeast(1),
                    (height * scale).roundToInt().coerceAtLeast(1),
                )
            }
        }
    }.getOrNull()

    private fun fileLooksAnimated(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".gif") || lower.endsWith(".webp") || lower.endsWith(".apng")
    }

    private fun bytesLookAnimated(bytes: ByteArray): Boolean {
        if (bytes.size >= 6 &&
            bytes[0] == 'G'.code.toByte() &&
            bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte()
        ) {
            return true
        }
        if (bytes.size >= 12 &&
            bytes[0] == 'R'.code.toByte() &&
            bytes[8] == 'W'.code.toByte() &&
            bytes[9] == 'E'.code.toByte()
        ) {
            return true
        }
        return false
    }

    private data class AnimationBinding(
        val animation: Animatable,
        val listener: View.OnAttachStateChangeListener,
    )
}
