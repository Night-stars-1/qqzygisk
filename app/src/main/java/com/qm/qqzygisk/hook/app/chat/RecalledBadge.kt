package com.qm.qqzygisk.hook.app.chat

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewTreeObserver
import com.qm.qqzygisk.R
import com.qm.qqzygisk.hook.utils.Log
import java.util.Collections
import java.util.WeakHashMap

/**
 * A recalled message is outlined without altering its content, skin or geometry.
 * The drawable lives in the bubble's overlay: it neither replaces the background
 * nor adds a view, padding, constraint or touch target to the message row.
 */
internal object RecalledBadge {
    private val stateTag = R.id.qqz_recalled_badge
    private val boundRows: MutableSet<View> = Collections.newSetFromMap(WeakHashMap<View, Boolean>())
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    fun bind(row: View, bubble: View?, key: RecalledMessageKey?, recalled: Boolean): Boolean {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Log.warn("撤回边框只能在主线程绑定")
            return false
        }
        boundRows.add(row)
        val state = (row.getTag(stateTag) as? RowState) ?: RowState().also { row.setTag(stateTag, it) }
        if (bubble != null && !isWithin(bubble, row)) {
            detach(state)
            state.bubble = null
            return false
        }
        if (state.bubble !== bubble) {
            detach(state)
            state.bubble = bubble
        }
        if (!recalled || key == null || bubble == null) {
            detach(state)
            return false
        }
        val border = state.border ?: BorderDrawable().also { state.border = it }
        border.configure(bubble)
        // Bounds are only the overlay's invalidation area, not the painted bubble outline.
        border.setBounds(0, 0, bubble.width, bubble.height)
        if (!state.attached) {
            val listener = state.layoutListener ?: View.OnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
                state.border?.setBounds(0, 0, view.width, view.height)
                state.border?.invalidateGeometry()
            }.also { state.layoutListener = it }
            bubble.addOnLayoutChangeListener(listener)
            bubble.overlay.add(border)
            bubble.addOnAttachStateChangeListener(state)
            if (bubble.isAttachedToWindow) state.onViewAttachedToWindow(bubble)
            state.attached = true
        }
        return true
    }

    fun clear(row: View) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Log.warn("撤回边框只能在主线程清除")
            return
        }
        val state = row.getTag(stateTag) as? RowState ?: return
        detach(state)
        state.bubble = null
    }

    /** A delayed recall notification must re-read the row's current message, not its old key. */
    fun refresh(
        identityOf: (View) -> RecalledMessageKey?,
        recalled: (RecalledMessageKey) -> Boolean,
        bubbleOf: (View) -> View?,
    ) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { refresh(identityOf, recalled, bubbleOf) }
            return
        }
        val rows = boundRows.toList()
        for (row in rows) {
            runCatching {
                val key = identityOf(row)
                if (key == null) clear(row)
                else bind(row, bubbleOf(row), key, recalled(key))
            }.onFailure {
                clear(row)
                Log.warn("刷新撤回边框失败", it)
            }
        }
    }

    private fun detach(state: RowState) {
        state.border?.clearTarget()
        state.stopObserving()
        if (!state.attached) return
        state.bubble?.let { bubble ->
            state.border?.let { bubble.overlay.remove(it) }
            state.layoutListener?.let { bubble.removeOnLayoutChangeListener(it) }
            bubble.removeOnAttachStateChangeListener(state)
        }
        state.attached = false
    }

    private fun isWithin(bubble: View, row: View): Boolean {
        var current: View? = bubble
        while (current != null) {
            if (current === row) return true
            current = current.parent as? View
        }
        return false
    }

    private class RowState : View.OnAttachStateChangeListener, ViewTreeObserver.OnPreDrawListener {
        var bubble: View? = null
        var border: BorderDrawable? = null
        var layoutListener: View.OnLayoutChangeListener? = null
        var attached = false
        private var observer: ViewTreeObserver? = null

        override fun onViewAttachedToWindow(view: View) {
            stopObserving()
            observer = view.viewTreeObserver.also { it.addOnPreDrawListener(this) }
        }

        override fun onViewDetachedFromWindow(view: View) = stopObserving()

        override fun onPreDraw(): Boolean {
            border?.invalidateIfGeometryChanged()
            return true
        }

        fun stopObserving() {
            observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(this)
            observer = null
        }
    }

    private class BorderDrawable : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFE53935.toInt()
            style = Paint.Style.STROKE
        }
        private val outline = RecalledBubbleOutline()

        fun configure(bubble: View) {
            paint.strokeWidth = 2f * bubble.resources.displayMetrics.density
            outline.bind(bubble)
            invalidateSelf()
        }

        fun invalidateGeometry() {
            outline.invalidate()
            invalidateSelf()
        }

        fun invalidateIfGeometryChanged() {
            if (outline.needsRedraw()) invalidateSelf()
        }

        fun clearTarget() {
            outline.bind(null)
        }

        override fun draw(canvas: Canvas) {
            outline.draw(canvas, paint)
        }

        override fun setAlpha(alpha: Int) {
            if (paint.alpha == alpha) return
            paint.alpha = alpha
            invalidateSelf()
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            paint.colorFilter = colorFilter
            invalidateSelf()
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }
}
