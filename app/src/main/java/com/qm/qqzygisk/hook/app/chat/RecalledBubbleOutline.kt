package com.qm.qqzygisk.hook.app.chat

import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup

/** Reads the internal background's real perimeter, never the outer click wrapper's bounds. */
internal class RecalledBubbleOutline {
    private var bubble: View? = null
    private var target: View? = null
    private var targetDrawable: Drawable? = null
    private val outline = Outline()
    private val outlineRect = Rect()
    private val drawRect = RectF()
    private val mappedRect = RectF()
    private val primaryBounds = RectF()
    private val allBounds = RectF()
    private val transform = Matrix()
    private val level = Matrix()
    private val ninePatchOutline = RecalledNinePatchOutline()
    private var largestArea = 0f
    private var unresolvedArea = 0f
    private var drawnTarget: View? = null
    private var drawnDrawable: Drawable? = null
    private var drawnWidth = 0
    private var drawnHeight = 0
    private var drawnRadius = 0f
    private val drawnRect = Rect()
    private val drawnTransform = Matrix()
    private val visibleRect = Rect()

    fun bind(bubble: View?) {
        this.bubble = bubble
        invalidate()
        drawnTarget = null
        drawnDrawable = null
        if (bubble == null) ninePatchOutline.clear()
    }

    fun invalidate() {
        target = null
        targetDrawable = null
    }

    /** Child RenderNode transforms can change without invalidating the outer overlay. */
    fun needsRedraw(): Boolean {
        val host = bubble ?: return false
        if (!host.getGlobalVisibleRect(visibleRect)) return false
        var ancestor: View? = host
        while (ancestor != null) {
            if (ancestor.alpha <= 0f) return false
            ancestor = ancestor.parent as? View
        }
        return try {
            if (!resolve(host)) drawnTarget != null
            else {
                val view = target ?: return drawnTarget != null
                view !== drawnTarget || targetDrawable !== drawnDrawable ||
                    view.width != drawnWidth || view.height != drawnHeight ||
                    outlineRect != drawnRect || outline.radius != drawnRadius || transform != drawnTransform
            }
        } catch (_: Throwable) {
            drawnTarget != null
        }
    }

    fun draw(canvas: Canvas, paint: Paint) {
        val host = bubble ?: return
        drawnTarget = null
        drawnDrawable = null
        try {
            if (!resolve(host)) return
            val halfStroke = paint.strokeWidth / 2f
            drawRect.set(outlineRect)
            drawRect.inset(halfStroke, halfStroke)
            if (drawRect.isEmpty) return
            val radius = (outline.radius - halfStroke).coerceIn(
                0f, minOf(drawRect.width(), drawRect.height()) / 2f,
            )
            val save = canvas.save()
            try {
                // Keep the background's own coordinate system, including asymmetric scale.
                canvas.concat(transform)
                canvas.drawRoundRect(drawRect, radius, radius, paint)
            } finally {
                canvas.restoreToCount(save)
            }
            drawnTarget = target
            drawnDrawable = targetDrawable
            drawnWidth = target?.width ?: 0
            drawnHeight = target?.height ?: 0
            drawnRect.set(outlineRect)
            drawnRadius = outline.radius
            drawnTransform.set(transform)
        } catch (_: Throwable) {
            // Third-party skin drawables must not interrupt QQ's drawing pass.
            invalidate()
        }
    }

    private fun resolve(host: View): Boolean {
        val previous = target
        if (previous == null || previous.background !== targetDrawable ||
            !visibleWithin(previous, host) || hasOuterBackground(previous, host)
        ) select(host)
        val view = target ?: return false
        val background = targetDrawable ?: return false
        if (!readOutline(background, view.resources)) {
            invalidate()
            return false
        }
        return mapToHost(view, host)
    }

    private fun select(host: View) {
        invalidate()
        largestArea = 0f
        unresolvedArea = 0f
        allBounds.setEmpty()
        scan(host, host)
        // A single message background must enclose the other backgrounds (badges,
        // quoted content, etc.). Disjoint backgrounds do not justify framing one at random.
        if (target == null || !primaryBounds.contains(allBounds) || unresolvedArea >= largestArea) {
            invalidate()
        }
    }

    private fun scan(view: View, host: View) {
        if (view.visibility != View.VISIBLE || view.alpha <= 0f) return
        val background = view.background
        if (background != null && visibleBackground(background) && view.width > 0 && view.height > 0 &&
            mapToHost(view, host)
        ) {
            if (readOutline(background, view.resources)) {
                mappedRect.set(outlineRect)
                transform.mapRect(mappedRect)
                val area = mappedRect.width() * mappedRect.height()
                if (area > 0f) {
                    allBounds.union(mappedRect)
                    if (area > largestArea) {
                        largestArea = area
                        primaryBounds.set(mappedRect)
                        target = view
                        targetDrawable = background
                    }
                }
            } else {
                // An unresolved full-size skin must not turn a nested button into
                // the "message background". Bounds are a veto only, never painted.
                mappedRect.set(0f, 0f, view.width.toFloat(), view.height.toFloat())
                transform.mapRect(mappedRect)
                unresolvedArea = maxOf(unresolvedArea, mappedRect.width() * mappedRect.height())
            }
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) scan(view.getChildAt(index), host)
        }
    }

    private fun visibleWithin(view: View, host: View): Boolean {
        var current = view
        while (true) {
            if (current.visibility != View.VISIBLE || current.alpha <= 0f) return false
            if (current === host) return true
            current = current.parent as? View ?: return false
        }
    }

    private fun hasOuterBackground(view: View, host: View): Boolean {
        var current = view
        while (current !== host) {
            current = current.parent as? View ?: return true
            val background = current.background
            if (background != null && visibleBackground(background)) return true
        }
        return false
    }

    @Suppress("DEPRECATION")
    private fun visibleBackground(background: Drawable): Boolean =
        background.alpha > 0 && background.opacity != PixelFormat.TRANSPARENT

    private fun readOutline(background: Drawable, resources: Resources): Boolean {
        outline.setEmpty()
        outline.alpha = 0f // setEmpty does not reset alpha; Drawable's default outline is unknown.
        background.getOutline(outline)
        if (outline.alpha <= 0f || !outline.getRect(outlineRect) || outlineRect.isEmpty) {
            if (!ninePatchOutline.read(background, resources, outline)) return false
            if (!outline.getRect(outlineRect) || outlineRect.isEmpty) return false
        }
        return true
    }

    private fun mapToHost(view: View, host: View): Boolean {
        transform.reset()
        var current = view
        while (current !== host) {
            val parent = current.parent as? View ?: return false
            level.set(current.matrix)
            level.postTranslate((current.left - parent.scrollX).toFloat(), (current.top - parent.scrollY).toFloat())
            transform.postConcat(level)
            current = parent
        }
        // View's overlay is drawn inside its scrolled canvas, unlike its background.
        transform.postTranslate(host.scrollX.toFloat(), host.scrollY.toFloat())
        return true
    }
}
