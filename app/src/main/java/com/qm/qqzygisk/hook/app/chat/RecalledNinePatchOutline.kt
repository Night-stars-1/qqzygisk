package com.qm.qqzygisk.hook.app.chat

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.NinePatch
import android.graphics.Outline
import android.graphics.drawable.Drawable
import android.graphics.drawable.NinePatchDrawable
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** QQ's SkinnableNinePatchDrawable exposes its Bitmap, but not an Android Outline. */
internal class RecalledNinePatchOutline {
    private var getterType: Class<*>? = null
    private var bitmapGetter: Method? = null
    private var bitmap: Bitmap? = null
    private var generation = 0
    private var density = 0
    private var proxy: NinePatchDrawable? = null

    fun clear() {
        bitmap = null
        proxy = null
    }

    fun read(source: Drawable, resources: Resources, outline: Outline): Boolean {
        if (getterType !== source.javaClass) {
            getterType = source.javaClass
            bitmapGetter = source.javaClass.methods.singleOrNull {
                it.name == "getBitmap" && it.parameterCount == 0 &&
                    it.returnType == Bitmap::class.java && !Modifier.isStatic(it.modifiers)
            }
        }
        val image = bitmapGetter?.invoke(source) as? Bitmap ?: return false
        if (image.isRecycled) return false
        val targetDensity = resources.displayMetrics.densityDpi
        if (bitmap !== image || generation != image.generationId || density != targetDensity) {
            clear()
            val chunk = image.ninePatchChunk ?: return false
            if (!NinePatch.isNinePatchChunk(chunk)) return false
            // Shares the original immutable pixel data. Only this geometry proxy's bounds
            // and alpha change; the QQ drawable/bitmap/state/callback are never modified.
            proxy = NinePatchDrawable(resources, image, chunk, null, "qqz-recalled-outline")
            bitmap = image
            generation = image.generationId
            density = targetDensity
        }
        val geometry = proxy ?: return false
        geometry.bounds = source.bounds
        geometry.alpha = source.alpha
        outline.setEmpty()
        outline.alpha = 0f
        geometry.getOutline(outline)
        return !outline.isEmpty && outline.alpha > 0f
    }
}
