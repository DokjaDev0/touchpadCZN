package com.dualpad.controller

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.view.View

/**
 * A full-screen transparent overlay that renders a small cursor dot on the primary display.
 *
 * Added to the WindowManager as [android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY]
 * so it floats above all apps without intercepting any touch events.
 *
 * Visual design: white filled circle + dark border + centre dot.
 * Radius is intentionally small (≈ 9 dp) so it does not obscure content.
 */
class CursorOverlayView(context: Context) : View(context) {

    // ── Dimensions (dp → px) ───────────────────────────────────────────────────

    private val density = context.resources.displayMetrics.density

    /** Outer cursor radius in pixels. */
    private val CURSOR_RADIUS = 9f * density

    /** Centre-dot radius in pixels. */
    private val DOT_RADIUS = 2.5f * density

    // ── Paints ─────────────────────────────────────────────────────────────────

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#222222")
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#444444")
        style = Paint.Style.FILL
    }

    // ── State ──────────────────────────────────────────────────────────────────

    private var cx = 0f
    private var cy = 0f

    /** Alpha of the cursor, 0.0 (invisible) … 1.0 (fully visible). */
    private var cursorAlpha = 0f

    private val handler = Handler(Looper.getMainLooper())
    private var fadeAnimator: ValueAnimator? = null

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Moves the cursor to ([x], [y]) and makes it immediately visible,
     * cancelling any pending hide animation.
     */
    fun moveTo(x: Float, y: Float) {
        fadeAnimator?.cancel()
        handler.removeCallbacksAndMessages(null)
        cx = x
        cy = y
        cursorAlpha = 1f
        invalidate()
    }

    /**
     * Schedules a fade-out animation to start after [delayMs] milliseconds.
     * Calling [moveTo] before the delay expires cancels the hide.
     */
    fun scheduleHide(delayMs: Long = 600) {
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed(::fadeOut, delayMs)
    }

    // ── Drawing ────────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        if (cursorAlpha <= 0f) return

        fillPaint.alpha   = (210 * cursorAlpha).toInt()
        borderPaint.alpha = (255 * cursorAlpha).toInt()
        dotPaint.alpha    = (255 * cursorAlpha).toInt()

        canvas.drawCircle(cx, cy, CURSOR_RADIUS, fillPaint)
        canvas.drawCircle(cx, cy, CURSOR_RADIUS, borderPaint)
        canvas.drawCircle(cx, cy, DOT_RADIUS,    dotPaint)
    }

    // ── Animation ──────────────────────────────────────────────────────────────

    private fun fadeOut() {
        fadeAnimator = ValueAnimator.ofFloat(cursorAlpha, 0f).apply {
            duration = 350
            addUpdateListener {
                cursorAlpha = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }
}
