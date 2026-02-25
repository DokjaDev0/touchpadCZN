package com.dualpad.controller

import android.content.Context
import android.graphics.*
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import kotlin.math.cos
import kotlin.math.PI
import kotlin.math.sqrt

/**
 * Full-screen touchpad — standard Android touchpad model (pixel-art style):
 *
 * | Gesture                     | Effect on primary screen            |
 * |-----------------------------|-------------------------------------|
 * | Slide                       | Move cursor only                    |
 * | Quick tap (< 150 ms)        | Click at cursor position            |
 * | Hold ~150 ms + slide        | Scroll / drag (real-time, smooth)   |
 */
class TouchpadView(
    context: Context,
    private val primaryW: Int,
    private val primaryH: Int
) : View(context) {

    private enum class State { PENDING, HOVER, DRAG }

    // ── Constants ──────────────────────────────────────────────────────────────

    private val TAP_MAX_MS = 150L
    private val TAP_SLOP   = 20f
    private val HOLD_MS    = 120L

    /**
     * Per-event pixel threshold to classify a move as a deliberate fast swipe
     * (cursor-only mode). At ~120fps a frame is ~8ms. A fast swipe covers > 15 px
     * per frame; slow hold drift stays < 5 px per frame.
     * Only exceeding this threshold cancels the hold timer → HOVER.
     */
    private val FAST_SWIPE_PX = 15f

    private val EDGE_ZONE      = 0.30f
    private val EDGE_BOOST_MAX = 3.5f

    // ── Pixel art paints ───────────────────────────────────────────────────────

    private val bgPaint         = Paint().apply { color = Color.parseColor("#0F0F23"); style = Paint.Style.FILL }
    private val scanlinePaint   = Paint().apply { color = Color.parseColor("#0A0A1E"); style = Paint.Style.FILL }
    private val gridPaint       = Paint().apply { color = Color.parseColor("#1E1E4E"); style = Paint.Style.STROKE; strokeWidth = 1f }
    private val dotPaint        = Paint().apply { color = Color.parseColor("#2244CC"); style = Paint.Style.FILL }
    private val borderPaint     = Paint().apply { color = Color.parseColor("#E94560"); style = Paint.Style.FILL }

    // Hover state: bright red pixel crosshair
    private val fingerFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FF0055"); style = Paint.Style.FILL }
    // Drag state: yellow pixel crosshair
    private val dragFillPaint   = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FFFF00"); style = Paint.Style.FILL }

    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color     = Color.parseColor("#00CCFF")
        textSize  = 19f
        textAlign = Paint.Align.CENTER
        typeface  = Typeface.MONOSPACE
    }
    private val lockBarPaint  = Paint().apply { color = Color.argb(0xBB, 0x11, 0x00, 0x00); style = Paint.Style.FILL }
    private val lockTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color          = Color.parseColor("#FF2200")
        textSize       = 17f
        textAlign      = Paint.Align.CENTER
        typeface       = Typeface.MONOSPACE
        isFakeBoldText = true
    }

    // ── Lock state ─────────────────────────────────────────────────────────────

    var isLocked = false
        private set

    fun setLocked(locked: Boolean) {
        isLocked = locked
        updateGestureExclusion()
        invalidate()
    }

    // ── Touch state ────────────────────────────────────────────────────────────

    private var state       = State.PENDING
    private var fingerX     = 0f
    private var fingerY     = 0f
    private var isTouching  = false
    private var prevFingerX = 0f
    private var prevFingerY = 0f
    private var downX       = 0f
    private var downY       = 0f
    private var downTime    = 0L

    // ── Hold timer ─────────────────────────────────────────────────────────────

    private val handler = Handler(Looper.getMainLooper())

    /** Fires after [HOLD_MS] — starts the gesture chain on the primary display. */
    private val holdRunnable = Runnable {
        val svc = ControllerAccessibilityService.instance ?: return@Runnable
        if (state == State.PENDING) {
            state = State.DRAG
            svc.startTouch(svc.cursorX, svc.cursorY)
            invalidate()
        }
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateGestureExclusion()
    }

    private fun updateGestureExclusion() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            systemGestureExclusionRects = if (isLocked && width > 0 && height > 0) {
                listOf(Rect(0, 0, width, height))
            } else {
                emptyList()
            }
        }
    }

    // ── Drawing ────────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        drawTouchpadBackground(canvas, w, h)
        drawPixelBorder(canvas, w, h)
        if (isLocked) drawLockIndicator(canvas, w)
        drawFingerIndicator(canvas)
        if (!isTouching) drawHints(canvas, w, h)
    }

    private fun drawPixelBorder(canvas: Canvas, w: Float, h: Float) {
        val b = 4f
        canvas.drawRect(0f, 0f, w, b, borderPaint)
        canvas.drawRect(0f, h - b, w, h, borderPaint)
        canvas.drawRect(0f, 0f, b, h, borderPaint)
        canvas.drawRect(w - b, 0f, w, h, borderPaint)
    }

    private fun drawLockIndicator(canvas: Canvas, w: Float) {
        canvas.drawRect(0f, 0f, w, 42f, lockBarPaint)
        canvas.drawText("[ NAV LOCKED ]", w / 2f, 28f, lockTextPaint)
    }

    private fun drawTouchpadBackground(canvas: Canvas, w: Float, h: Float) {
        canvas.drawRect(0f, 0f, w, h, bgPaint)
        val cols = 8; val rows = 6
        val cellW = w / cols; val cellH = h / rows

        // Checkerboard macro-pixel overlay — retro pixel art look
        for (col in 0 until cols) {
            for (row in 0 until rows) {
                if ((col + row) % 2 == 1) {
                    canvas.drawRect(
                        col * cellW, row * cellH,
                        (col + 1) * cellW, (row + 1) * cellH,
                        scanlinePaint
                    )
                }
            }
        }

        // Grid lines
        for (col in 1 until cols) canvas.drawLine(col * cellW, 0f, col * cellW, h, gridPaint)
        for (row in 1 until rows) canvas.drawLine(0f, row * cellH, w, row * cellH, gridPaint)

        // Pixel dots at intersections (4×4 squares)
        for (col in 1 until cols) for (row in 1 until rows) {
            val dx = col * cellW; val dy = row * cellH
            canvas.drawRect(dx - 4f, dy - 4f, dx + 4f, dy + 4f, dotPaint)
        }
    }

    private fun drawFingerIndicator(canvas: Canvas) {
        if (!isTouching) return
        val paint = if (state == State.DRAG) dragFillPaint else fingerFillPaint
        val cx = fingerX; val cy = fingerY
        val arm = 22f; val thick = 4f; val gap = 7f

        // Pixel art crosshair — rectangles only, no anti-alias curves
        canvas.drawRect(cx - arm - gap, cy - thick, cx - gap,       cy + thick, paint)  // left arm
        canvas.drawRect(cx + gap,       cy - thick, cx + arm + gap, cy + thick, paint)  // right arm
        canvas.drawRect(cx - thick, cy - arm - gap, cx + thick, cy - gap,       paint)  // top arm
        canvas.drawRect(cx - thick, cy + gap,       cx + thick, cy + arm + gap, paint)  // bottom arm
        canvas.drawRect(cx - thick, cy - thick,     cx + thick, cy + thick,     paint)  // center dot
    }

    private fun drawHints(canvas: Canvas, w: Float, h: Float) {
        val cx = w / 2f
        canvas.drawText("> SLIDE     : move cursor",  cx, h * 0.34f, hintPaint)
        canvas.drawText("> TAP       : click",         cx, h * 0.48f, hintPaint)
        canvas.drawText("> HOLD+DRAG : scroll/drag",   cx, h * 0.62f, hintPaint)
    }

    // ── Touch events ───────────────────────────────────────────────────────────

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val svc = ControllerAccessibilityService.instance ?: return true
        if (svc.cursorX < 0f || svc.cursorY < 0f) svc.setScreenSize(primaryW, primaryH)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN   -> handleDown(event, svc)
            MotionEvent.ACTION_MOVE   -> handleMove(event, svc)
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> handleUp(event, svc)
        }
        return true
    }

    private fun handleDown(event: MotionEvent, svc: ControllerAccessibilityService) {
        svc.forceResetGesture()
        downX       = event.x
        downY       = event.y
        prevFingerX = event.x
        prevFingerY = event.y
        fingerX     = event.x
        fingerY     = event.y
        downTime    = SystemClock.elapsedRealtime()
        state       = State.PENDING
        isTouching  = true
        invalidate()
        handler.postDelayed(holdRunnable, HOLD_MS)
    }

    private fun handleMove(event: MotionEvent, svc: ControllerAccessibilityService) {
        val dx = event.x - prevFingerX
        val dy = event.y - prevFingerY
        prevFingerX = event.x
        prevFingerY = event.y
        fingerX     = event.x
        fingerY     = event.y
        invalidate()

        when (state) {
            State.PENDING -> {
                // Only cancel hold timer if THIS frame's movement is a fast swipe.
                // Slow drift during a hold (< FAST_SWIPE_PX per event) is ignored,
                // so hold+slide at moderate speed still triggers DRAG correctly.
                val frameDist = sqrt(dx * dx + dy * dy)
                if (frameDist > FAST_SWIPE_PX) {
                    handler.removeCallbacks(holdRunnable)
                    state = State.HOVER
                    applyCursorMove(dx, dy, event, svc)
                }
            }
            State.HOVER -> {
                applyCursorMove(dx, dy, event, svc)
            }
            State.DRAG -> {
                val base = SettingsManager.getSensitivity(context)
                val sens = edgeSensitivity(event.x, event.y, base)
                val newX = (svc.cursorX + dx * sens).coerceIn(0f, primaryW.toFloat())
                val newY = (svc.cursorY + dy * sens).coerceIn(0f, primaryH.toFloat())
                svc.updateCursorPosition(newX, newY)
                if (svc.gestureActive) {
                    // Normal: keep feeding the active gesture chain
                    svc.moveTouch(newX, newY)
                } else {
                    // Chain broke (cancelled by system) — restart immediately at cursor pos
                    svc.startTouch(newX, newY)
                }
            }
        }
    }

    private fun applyCursorMove(dx: Float, dy: Float, event: MotionEvent,
                                svc: ControllerAccessibilityService) {
        val base = SettingsManager.getSensitivity(context)
        val sens = edgeSensitivity(event.x, event.y, base)
        val newX = (svc.cursorX + dx * sens).coerceIn(0f, primaryW.toFloat())
        val newY = (svc.cursorY + dy * sens).coerceIn(0f, primaryH.toFloat())
        svc.updateCursorPosition(newX, newY)
    }

    private fun edgeSensitivity(fx: Float, fy: Float, base: Float): Float {
        val padW = width.toFloat()
        val padH = height.toFloat()
        val nearestEdge = minOf(
            fx / padW, (padW - fx) / padW,
            fy / padH, (padH - fy) / padH
        ).coerceIn(0f, 1f)
        val boost = if (nearestEdge < EDGE_ZONE) {
            val t      = 1f - nearestEdge / EDGE_ZONE
            val smooth = (0.5f * (1f - cos(PI * t))).toFloat()
            1f + smooth * (EDGE_BOOST_MAX - 1f)
        } else 1f
        return base * boost
    }

    private fun handleUp(event: MotionEvent, svc: ControllerAccessibilityService) {
        handler.removeCallbacks(holdRunnable)
        val elapsed      = SystemClock.elapsedRealtime() - downTime
        val totalDx      = event.x - downX
        val totalDy      = event.y - downY
        val displacement = sqrt(totalDx * totalDx + totalDy * totalDy)

        when (state) {
            State.PENDING -> {
                if (elapsed < TAP_MAX_MS && displacement < TAP_SLOP) {
                    svc.performTapAtCursor()
                }
            }
            State.HOVER -> Unit
            State.DRAG  -> svc.endTouch()
        }

        isTouching = false
        invalidate()
    }
}
