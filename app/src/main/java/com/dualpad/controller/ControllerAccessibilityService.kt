package com.dualpad.controller

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent

class ControllerAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: ControllerAccessibilityService? = null
            private set

        /** Duration of each continuation stroke segment. */
        private const val STROKE_DURATION_MS = 40L

        /** Duration of the initial stroke. */
        private const val INITIAL_STROKE_MS = 40L

        /** Cursor auto-hide after idle. */
        private const val CURSOR_IDLE_HIDE_MS = 3_000L
    }

    // ── Gesture state ──────────────────────────────────────────────────────────

    /** True while a continueStroke chain is active. Read by TouchpadView to detect breaks. */
    @Volatile var gestureActive = false
        private set

    /** Set to true by endTouch() — the next callback will dispatch the final release stroke. */
    @Volatile private var pendingRelease = false

    private var currentStroke: GestureDescription.StrokeDescription? = null

    /** Latest cursor position to move the active gesture toward. Updated by moveTouch(). */
    private var lastX = 0f
    private var lastY = 0f

    /** Actual endpoint of the last dispatched stroke segment. */
    private var strokeEndX = 0f
    private var strokeEndY = 0f

    /**
     * Alternates direction of the stationary stub each callback.
     * This keeps the gesture alive without accumulating position drift and
     * without creating degenerate zero-length paths.
     */
    private var stubToggle = false

    // ── Cursor state ───────────────────────────────────────────────────────────

    var cursorX = -1f ; private set
    var cursorY = -1f ; private set
    var screenW = 0   ; private set
    var screenH = 0   ; private set

    // ── Overlay ────────────────────────────────────────────────────────────────

    private var cursorView: CursorOverlayView? = null
    private var windowManager: WindowManager? = null

    private val handler = Handler(Looper.getMainLooper())
    private val hideCursorTask = Runnable { cursorView?.scheduleHide(400) }

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    override fun onServiceConnected() {
        instance = this
        attachCursorOverlay()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        detachCursorOverlay()
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    // ── Overlay management ─────────────────────────────────────────────────────

    private fun attachCursorOverlay() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        windowManager = wm
        val view = CursorOverlayView(this).also { cursorView = it }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        wm.addView(view, params)
    }

    private fun detachCursorOverlay() {
        cursorView?.let { windowManager?.removeView(it) }
        cursorView  = null
        windowManager = null
    }

    // ── Public API ─────────────────────────────────────────────────────────────

    fun setScreenSize(width: Int, height: Int) {
        screenW = width
        screenH = height
        if (cursorX !in 0f..width.toFloat() || cursorY !in 0f..height.toFloat()) {
            cursorX = width / 2f
            cursorY = height / 2f
            cursorView?.moveTo(cursorX, cursorY)
            resetIdleTimer()
        }
    }

    fun updateCursorPosition(x: Float, y: Float) {
        cursorX = x
        cursorY = y
        cursorView?.moveTo(x, y)
        resetIdleTimer()
    }

    /**
     * Force-resets gesture state.
     * IMPORTANT: if a gesture is currently active, dispatches a proper finger-up
     * stroke BEFORE resetting so the primary screen doesn't get a stuck touch.
     * Called on every ACTION_DOWN in TouchpadView.
     */
    fun forceResetGesture() {
        if (gestureActive) {
            val prev = currentStroke
            if (prev != null && screenW > 0) {
                val fx = strokeEndX.coerceIn(1f, screenW - 1f)
                val fy = strokeEndY.coerceIn(1f, screenH - 1f)
                val ex = (fx + 0.5f).coerceAtMost(screenW - 1f)
                val path = Path().apply { moveTo(fx, fy); lineTo(ex, fy) }
                // isContinued=false → system sends pointer-up event on primary screen
                val release = prev.continueStroke(path, 0, 16L, false)
                dispatchGesture(
                    GestureDescription.Builder().addStroke(release).build(),
                    null, null
                )
            }
        }
        gestureActive  = false
        pendingRelease = false
        currentStroke  = null
        strokeEndX     = 0f
        strokeEndY     = 0f
        stubToggle     = false
    }

    /**
     * Begins a continuous hold gesture at ([x], [y]) on the primary display.
     * The chain runs until [endTouch] is called.
     */
    fun startTouch(x: Float, y: Float) {
        if (gestureActive) return
        val safeX = x.coerceIn(1f, screenW - 1f)
        val safeY = y.coerceIn(1f, screenH - 1f)
        lastX          = safeX
        lastY          = safeY
        gestureActive  = true
        pendingRelease = false
        stubToggle     = false

        // Start with a tiny 0.5px stub so the initial stroke is valid and non-degenerate
        val stubX = (safeX + 0.5f).coerceAtMost(screenW - 1f)
        strokeEndX = stubX
        strokeEndY = safeY
        val path   = Path().apply { moveTo(safeX, safeY); lineTo(stubX, safeY) }
        val stroke = GestureDescription.StrokeDescription(path, 0, INITIAL_STROKE_MS, true)
        currentStroke = stroke

        val dispatched = dispatchGesture(
            GestureDescription.Builder().addStroke(stroke).build(),
            dragCallback, null
        )
        if (!dispatched) {
            gestureActive = false
            currentStroke = null
        }
        resetIdleTimer()
    }

    /**
     * Updates the drag target. The new position is picked up on the next callback.
     * All calls happen on the main thread — no synchronisation needed.
     */
    fun moveTouch(x: Float, y: Float) {
        lastX = x.coerceIn(1f, screenW - 1f)
        lastY = y.coerceIn(1f, screenH - 1f)
    }

    /** Signals that the finger was lifted. The next callback will send the final stroke. */
    fun endTouch() {
        pendingRelease = true
    }

    /** Single tap at the current cursor position. */
    fun performTapAtCursor() {
        if (gestureActive || cursorX < 0f) return
        val x = cursorX; val y = cursorY
        cursorView?.moveTo(x, y)
        resetIdleTimer()
        val path   = Path().apply { moveTo(x, y); lineTo(x + 1f, y + 1f) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 80L)
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    /** Swipe-based scroll fling. Used as a fallback for quick scrolls. */
    fun performScroll(fromX: Float, fromY: Float, toX: Float, toY: Float) {
        if (gestureActive) return
        val path   = Path().apply { moveTo(fromX, fromY); lineTo(toX, toY) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 150L)
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    fun performBack()    = performGlobalAction(GLOBAL_ACTION_BACK)
    fun performHome()    = startActivity(
        Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
    )
    fun performRecents() = performGlobalAction(GLOBAL_ACTION_RECENTS)

    // ── Idle cursor timer ──────────────────────────────────────────────────────

    private fun resetIdleTimer() {
        handler.removeCallbacks(hideCursorTask)
        handler.postDelayed(hideCursorTask, CURSOR_IDLE_HIDE_MS)
    }

    // ── Drag callback ──────────────────────────────────────────────────────────

    /**
     * Chained continueStroke callback.
     *
     * Movement case: builds a straight-line path from strokeEnd → lastX/lastY.
     *
     * Stationary case (finger held still): alternates ±1 px in X, picking the direction
     * that keeps the path within screen bounds. This keeps the chain alive without drift
     * and stays well below Android's long-press slop threshold.
     *
     * Release case: sends a final stroke with isContinued=false to lift the finger.
     */
    private val dragCallback = object : GestureResultCallback() {

        override fun onCompleted(gestureDescription: GestureDescription) {
            if (!gestureActive) return
            val prev = currentStroke ?: return

            val fromX = strokeEndX.coerceIn(1f, screenW - 1f)
            val fromY = strokeEndY.coerceIn(1f, screenH - 1f)
            val toX   = lastX.coerceIn(1f, screenW - 1f)
            val toY   = lastY.coerceIn(1f, screenH - 1f)

            val isStationary = Math.abs(toX - fromX) < 1f && Math.abs(toY - fromY) < 1f

            val path: Path
            val endX: Float
            val endY: Float

            if (isStationary) {
                // Alternate ±1 px in X, choosing the direction that stays within bounds.
                // This prevents degenerate zero-length paths at screen edges, which would
                // break the continueStroke chain and cause an unintended finger-up event.
                stubToggle = !stubToggle
                val delta = if (stubToggle) 1f else -1f
                val ox = if ((fromX + delta) in 1f..(screenW - 1f)) fromX + delta
                         else fromX - delta
                path = Path().apply { moveTo(fromX, fromY); lineTo(ox, fromY) }
                endX = ox; endY = fromY
            } else {
                path = Path().apply { moveTo(fromX, fromY); lineTo(toX, toY) }
                endX = toX; endY = toY
            }

            strokeEndX = endX
            strokeEndY = endY

            if (pendingRelease) {
                val finalStroke = prev.continueStroke(path, 0, STROKE_DURATION_MS, false)
                gestureActive  = false
                currentStroke  = null
                pendingRelease = false
                dispatchGesture(
                    GestureDescription.Builder().addStroke(finalStroke).build(),
                    null, null
                )
            } else {
                val nextStroke = prev.continueStroke(path, 0, STROKE_DURATION_MS, true)
                currentStroke  = nextStroke
                val dispatched = dispatchGesture(
                    GestureDescription.Builder().addStroke(nextStroke).build(),
                    this, null
                )
                if (!dispatched) {
                    gestureActive  = false
                    currentStroke  = null
                    pendingRelease = false
                }
            }
        }

        override fun onCancelled(gestureDescription: GestureDescription) {
            gestureActive  = false
            currentStroke  = null
            pendingRelease = false
            strokeEndX     = 0f
            strokeEndY     = 0f
            stubToggle     = false
        }
    }
}
