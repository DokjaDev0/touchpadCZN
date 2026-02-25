package com.dualpad.controller

import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Display
import android.view.Gravity
import android.view.Window
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * A [Presentation] shown on the secondary display — pixel-art style.
 *
 * Layout (top → bottom):
 * 1. [TouchpadView]       — full-area touchpad (cursor, tap, hold-drag/scroll).
 * 2. Sensitivity row      — two speed-level buttons (Normal 2× / Fast 3.5×).
 * 3. Toolbar              — Nav Lock toggle + service status dot.
 */
class SecondaryDisplayPresentation(
    context: Context,
    display: Display,
    private val primaryW: Int,
    private val primaryH: Int
) : Presentation(context, display) {

    private lateinit var touchpadView: TouchpadView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window?.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        window?.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT
        )

        ControllerAccessibilityService.instance?.setScreenSize(primaryW, primaryH)

        touchpadView = TouchpadView(context, primaryW, primaryH)

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0F0F23"))
        }

        root.addView(
            touchpadView,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        root.addView(
            buildSensitivityPicker(),
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        )
        root.addView(
            buildToolbar(),
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        )

        setContentView(root)
    }

    // ── Sensitivity picker ─────────────────────────────────────────────────────

    private fun buildSensitivityPicker(): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity     = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#080818"))
            setPadding(8, 4, 8, 4)
        }

        val label = TextView(context).apply {
            text     = "SPEED:"
            textSize = 10f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor("#00AAFF"))
            setPadding(0, 0, 8, 0)
            gravity  = Gravity.CENTER_VERTICAL
        }

        val levels = listOf(
            Triple("Normal", SettingsManager.SENSITIVITY_NORMAL, "2x"),
            Triple("Fast",   SettingsManager.SENSITIVITY_FAST,   "3.5x")
        )

        val currentSens = SettingsManager.getSensitivity(context)
        val buttons = mutableListOf<Button>()

        val accentColor = Color.parseColor("#E94560")
        val dimColor    = Color.parseColor("#1A1A3A")
        val dimText     = Color.parseColor("#4466AA")

        fun refreshSelection(selectedValue: Float) {
            buttons.forEachIndexed { i, btn ->
                val isSelected = levels[i].second == selectedValue
                btn.setBackgroundColor(if (isSelected) accentColor else dimColor)
                btn.setTextColor(if (isSelected) Color.WHITE else dimText)
                btn.typeface = if (isSelected) Typeface.MONOSPACE else Typeface.MONOSPACE
            }
        }

        val btnParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            .also { it.setMargins(3, 0, 3, 0) }

        for ((name, value, mult) in levels) {
            val btn = Button(context).apply {
                text     = "$name [$mult]"
                textSize = 10f
                typeface = Typeface.MONOSPACE
                setPadding(4, 4, 4, 4)
                setOnClickListener {
                    SettingsManager.setSensitivity(context, value)
                    refreshSelection(value)
                }
            }
            buttons.add(btn)
            row.addView(btn, btnParams)
        }

        val closestLevel = levels.minByOrNull { Math.abs(it.second - currentSens) }?.second
            ?: SettingsManager.SENSITIVITY_NORMAL
        refreshSelection(closestLevel)

        row.addView(label, 0, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        return row
    }

    // ── Toolbar ─────────────────────────────────────────────────────────────────

    private fun buildToolbar(): LinearLayout {
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#060612"))
            gravity = Gravity.CENTER_VERTICAL
        }

        val lockBtn = Button(context).apply {
            text     = "[+] NAV-LOCK"
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor("#E94560"))
            setBackgroundColor(Color.parseColor("#1A1A3A"))
            setPadding(4, 8, 4, 8)
        }
        lockBtn.setOnClickListener {
            val newState = !touchpadView.isLocked
            touchpadView.setLocked(newState)
            setCancelable(!newState)
            lockBtn.text = if (newState) "[-] NAV-FREE" else "[+] NAV-LOCK"
            lockBtn.setTextColor(
                Color.parseColor(if (newState) "#FF2200" else "#E94560")
            )
            lockBtn.setBackgroundColor(
                Color.parseColor(if (newState) "#3A0000" else "#1A1A3A")
            )
        }

        val isConnected = ControllerAccessibilityService.instance != null
        val statusDot = TextView(context).apply {
            text      = if (isConnected) " ■ " else " □ "
            typeface  = Typeface.MONOSPACE
            setTextColor(if (isConnected) Color.parseColor("#00FF44") else Color.parseColor("#FF0044"))
            textSize  = 14f
            setPadding(8, 0, 8, 0)
            gravity   = Gravity.CENTER
        }

        bar.addView(lockBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            .also { it.setMargins(2, 2, 2, 2) })
        bar.addView(statusDot, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT))
        return bar
    }
}
