package com.qm.qqzygisk.hook.utils

import android.graphics.ImageDecoder
import android.graphics.drawable.Animatable
import android.graphics.drawable.AnimatedImageDrawable
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
        decode(ImageDecoder.createSource(file), maxSize, fileLooksAnimated(file.name), file.absolutePath)

    fun decode(bytes: ByteArray, maxSize: Int): Drawable? =
        decode(
            ImageDecoder.createSource(ByteBuffer.wrap(bytes)),
            maxSize,
            bytesLookAnimated(bytes),
            "bytes:${bytes.size}",
        )

    fun bind(view: ImageView, drawable: Drawable) {
        if (view.drawable === drawable) {
            val animation = drawable as? Animatable ?: return
            if (view.isAttachedToWindow && !animation.isRunning) {
                trace("rebind-stopped-before", view, drawable)
                animation.start()
                trace("rebind-stopped-after", view, drawable)
            }
            return
        }
        trace("bind-before", view, drawable)
        clear(view)
        view.setImageDrawable(drawable)
        trace("bind-assigned", view, drawable)

        val animation = drawable as? Animatable ?: return
        val listener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) {
                trace("attach-before-start", view, drawable)
                animation.start()
                trace("attach-after-start", view, drawable)
            }

            override fun onViewDetachedFromWindow(view: View) {
                trace("detach-before-stop", view, drawable)
                animation.stop()
                trace("detach-after-stop", view, drawable)
            }
        }
        synchronized(bindings) {
            bindings[view] = AnimationBinding(animation, listener)
        }
        view.addOnAttachStateChangeListener(listener)
        if (view.isAttachedToWindow) animation.start()
        trace("bind-after", view, drawable)
    }

    fun clear(view: ImageView) {
        if (view.drawable != null) trace("clear-before", view)
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
        sourceLabel: String,
    ): Drawable? = runCatching {
        ImageDecoder.decodeDrawable(source) { decoder, info, _ ->
            Log.info(
                "[ImageAnim] decode-header source=$sourceLabel maxSize=$maxSize " +
                    "mime=${info.mimeType} animated=${info.isAnimated} hint=$maybeAnimated " +
                    "size=${info.size.width}x${info.size.height}",
            )
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
    }.onSuccess {
        Log.info("[ImageAnim] decode-result source=$sourceLabel maxSize=$maxSize ${describe(it)}")
    }.onFailure {
        Log.error("[ImageAnim] decode-failed source=$sourceLabel maxSize=$maxSize", it)
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

    /** Diagnostic snapshot only; never starts, stops, or changes the drawable. */
    fun trace(event: String, view: View, drawable: Drawable? = (view as? ImageView)?.drawable) {
        val callback = drawable?.callback
        Log.info(
            "[ImageAnim] $event view=${identity(view)} key=${view.tag} " +
                "attached=${view.isAttachedToWindow} shown=${view.isShown} visibility=${view.visibility} " +
                "windowVisibility=${view.windowVisibility} size=${view.width}x${view.height} " +
                "current=${identity((view as? ImageView)?.drawable)} ${describe(drawable)} " +
                "ownsCallback=${callback === view} callbackKey=${(callback as? View)?.tag}",
        )
    }

    private fun describe(drawable: Drawable?): String =
        "drawable=${identity(drawable)} running=${(drawable as? Animatable)?.isRunning} " +
            "repeat=${(drawable as? AnimatedImageDrawable)?.repeatCount} " +
            "visible=${drawable?.isVisible} intrinsic=${drawable?.intrinsicWidth}x${drawable?.intrinsicHeight} " +
            "bounds=${drawable?.bounds} callback=${identity(drawable?.callback)}"

    private fun identity(value: Any?): String =
        if (value == null) "null" else "${value.javaClass.name}@${Integer.toHexString(System.identityHashCode(value))}"

    private data class AnimationBinding(
        val animation: Animatable,
        val listener: View.OnAttachStateChangeListener,
    )
}
