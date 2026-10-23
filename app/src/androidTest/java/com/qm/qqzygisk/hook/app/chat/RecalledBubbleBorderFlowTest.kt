package com.qm.qqzygisk.hook.app.chat

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.PixelCopy
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class RecalledBubbleBorderFlowTest {
    @Test
    fun originalMessageOutlineFollowsItsBackgroundAcrossSkinChangesAndRecycling() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        fun <T> main(action: () -> T): T {
            val result = AtomicReference<Result<T>>()
            instrumentation.runOnMainSync { result.set(runCatching(action)) }
            return result.get().getOrThrow()
        }
        val activity = instrumentation.startActivitySync(Intent().setClassName(
            context.packageName, "com.qm.qqzygisk.testing.DialogProbeActivity",
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val scene = main { Scene(activity) }
        val key = RecalledMessageKey("outline_account", 2, "123456789", 771122L)
        val handler = Handler(Looper.getMainLooper())
        fun snapshot(): Bitmap {
            val image = Bitmap.createBitmap(701, 360, Bitmap.Config.ARGB_8888)
            val done = CountDownLatch(1)
            val status = AtomicReference<Int>()
            main {
                assertTrue("Verify the actual hardware-accelerated surface", scene.stage.isHardwareAccelerated)
                // Software ViewGroup.drawChild can skip overlays on backgroundless groups.
                // Capture only after the real UI frame is committed, as QQ does on screen.
                scene.stage.viewTreeObserver.registerFrameCommitCallback {
                    handler.post {
                        val location = IntArray(2)
                        scene.stage.getLocationInWindow(location)
                        PixelCopy.request(activity.window,
                            Rect(location[0], location[1], location[0] + 701, location[1] + 360),
                            image, { result -> status.set(result); done.countDown() }, handler)
                    }
                }
                scene.stage.invalidate()
            }
            assertTrue("The bubble frame must reach the screen", done.await(15, TimeUnit.SECONDS))
            assertEquals(PixelCopy.SUCCESS, status.get().toInt())
            return image
        }
        fun proof(image: Bitmap, name: String) {
            if (InstrumentationRegistry.getArguments().getString("borderProof") == "true") {
                File(context.cacheDir, name).outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
        try {
            main {
                activity.setShowWhenLocked(true)
                activity.setTurnScreenOn(true)
                activity.setContentView(scene.stage)
                // Bind before the first layout/draw, when drawable bounds are still empty.
                RecalledBadge.bind(scene.stage, scene.wrapper, key, true)
                RecalledBadge.bind(scene.gray, scene.gray, null, true)
            }
            snapshot().also {
                assertBounds(Rect(172, 116, 411, 238), redBounds(it))
                assertTrue("Read the actual 18px radius, not fixed dp corners", isRed(it.getPixel(190, 117)))
                proof(it, "recalled-border-tight-default.png")
                it.recycle()
            }
            val bodyPadding = main {
                assertSame(scene.originalSkin, scene.foreground.background)
                assertSame(scene.params, scene.wrapper.layoutParams)
                assertEquals("dawdwa", scene.body.text.toString())
                assertEquals(Rect(0, 0, 289, 175), scene.originalSkin.bounds)
                listOf(scene.body.paddingLeft, scene.body.paddingTop, scene.body.paddingRight, scene.body.paddingBottom)
            }

            main { scene.wrapper.alpha = 0f }
            snapshot().also { assertTrue(redBounds(it).isEmpty); it.recycle() }
            main { scene.wrapper.alpha = 1f }
            snapshot().also { assertBounds(Rect(172, 116, 411, 238), redBounds(it)); it.recycle() }

            val replacement = main {
                // QQ skins move the background to the parent totalView. No rebind,
                // no outer size change: a cached foreground target must be discarded.
                scene.foreground.background = null
                skin(31f, 12, 14, 19, 13).also { scene.content.background = it }
            }
            snapshot().also {
                assertBounds(Rect(161, 103, 419, 251), redBounds(it))
                assertFalse("Follow the skin's larger rounded corner", isRed(it.getPixel(169, 106)))
                proof(it, "recalled-border-tight-skin.png")
                it.recycle()
            }
            main {
                assertSame(replacement, scene.content.background)
                scene.content.pivotX = 0f; scene.content.pivotY = 0f
                scene.content.translationX = 9f; scene.content.translationY = 5f
                scene.content.scaleX = 0.9f; scene.content.scaleY = 0.8f
            }
            snapshot().also { assertBounds(Rect(169, 105, 401, 224), redBounds(it), 1); it.recycle() }
            main {
                scene.content.scaleX = 1f; scene.content.scaleY = 1f
                scene.content.translationX = 0f; scene.content.translationY = 0f
                scene.params.width = 329
                scene.wrapper.layoutParams = scene.params
            }
            snapshot().also { assertBounds(Rect(161, 103, 459, 251), redBounds(it)); it.recycle() }

            main {
                // A delayed notification after reuse cannot retain the previous identity.
                RecalledBadge.refresh({ null }, { true }, { scene.wrapper })
            }
            snapshot().also { assertTrue(redBounds(it).isEmpty); it.recycle() }
            main {
                RecalledBadge.refresh({ if (it === scene.stage) key else null }, { true }, { scene.wrapper })
            }
            snapshot().also { assertBounds(Rect(161, 103, 459, 251), redBounds(it)); it.recycle() }
            main { RecalledBadge.bind(scene.stage, scene.wrapper, key, false) }
            snapshot().also { assertTrue(redBounds(it).isEmpty); it.recycle() }
            main {
                assertSame(scene.params, scene.wrapper.layoutParams)
                assertSame(replacement, scene.content.background)
                assertEquals("dawdwa", scene.body.text.toString())
                assertEquals("撤回提示（已保留）", scene.gray.text.toString())
                assertEquals(bodyPadding, listOf(scene.body.paddingLeft, scene.body.paddingTop, scene.body.paddingRight, scene.body.paddingBottom))
                assertEquals(3, scene.content.childCount)
            }
        } finally {
            main { RecalledBadge.clear(scene.stage); RecalledBadge.clear(scene.gray); activity.finish() }
        }
    }

    private class Scene(activity: Activity) {
        val stage = FrameLayout(activity).apply { setBackgroundColor(0xFF101112.toInt()) }
        val wrapper = FrameLayout(activity)
        val content = FrameLayout(activity)
        val foreground = View(activity)
        val body = TextView(activity).apply {
            text = "dawdwa"; textSize = 16f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
        }
        val originalSkin = LayerDrawable(arrayOf(
            GradientDrawable().apply { setColor(0xFF252627.toInt()); cornerRadius = 18f },
        )).apply { setLayerInset(0, 23, 27, 27, 26) }
        val params = FrameLayout.LayoutParams(289, 175).apply { leftMargin = 149; topMargin = 89 }
        val gray = TextView(activity).apply { text = "撤回提示（已保留）"; setTextColor(Color.LTGRAY) }
        init {
            foreground.background = originalSkin
            content.addView(foreground, FrameLayout.LayoutParams(-1, -1))
            content.addView(body, FrameLayout.LayoutParams(-1, -1))
            // Real nested background: the badge must not become the message perimeter.
            content.addView(View(activity).apply {
                background = GradientDrawable().apply { setColor(Color.GRAY); cornerRadius = 3f }
            }, FrameLayout.LayoutParams(10, 8).apply { leftMargin = 38; topMargin = 36 })
            wrapper.addView(content, FrameLayout.LayoutParams(-1, -1))
            stage.addView(wrapper, params)
            stage.addView(gray, FrameLayout.LayoutParams(-2, -2).apply { topMargin = 267; leftMargin = 149 })
        }
    }

    private fun skin(radius: Float, left: Int, top: Int, right: Int, bottom: Int) = InsetDrawable(
        GradientDrawable().apply { setColor(0xFF252627.toInt()); cornerRadius = radius }, left, top, right, bottom,
    )

    private fun isRed(pixel: Int) = Color.red(pixel) > 180 && Color.green(pixel) < 110 && Color.blue(pixel) < 110

    private fun redBounds(image: Bitmap): Rect {
        var left = image.width; var top = image.height; var right = -1; var bottom = -1
        for (y in 0 until image.height) for (x in 0 until image.width) {
            if (!isRed(image.getPixel(x, y))) continue
            left = minOf(left, x); top = minOf(top, y); right = maxOf(right, x); bottom = maxOf(bottom, y)
        }
        return if (right < left) Rect() else Rect(left, top, right + 1, bottom + 1)
    }

    private fun assertBounds(expected: Rect, actual: Rect, tolerance: Int = 0) {
        assertTrue("Expected visible bubble $expected, not wrapper; actual red pixels $actual",
            kotlin.math.abs(expected.left - actual.left) <= tolerance && kotlin.math.abs(expected.top - actual.top) <= tolerance &&
                kotlin.math.abs(expected.right - actual.right) <= tolerance && kotlin.math.abs(expected.bottom - actual.bottom) <= tolerance)
    }
}
