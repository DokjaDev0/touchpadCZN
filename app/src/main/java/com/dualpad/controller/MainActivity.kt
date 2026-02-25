package com.dualpad.controller

import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Display
import androidx.appcompat.app.AppCompatActivity
import com.dualpad.controller.databinding.ActivityMainBinding

/**
 * Entry point of the application.
 *
 * Guides the user through the two-step setup:
 * 1. Enable the [ControllerAccessibilityService] in system settings.
 * 2. Launch [SecondaryDisplayPresentation] on the detected secondary display.
 *
 * The activity also listens for display connect/disconnect events via
 * [DisplayManager.DisplayListener] so the UI updates automatically when
 * a secondary display is plugged in or removed.
 */
class MainActivity : AppCompatActivity(), DisplayManager.DisplayListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var displayManager: DisplayManager

    /** Reference to the currently shown secondary-display controller, if any. */
    private var presentation: SecondaryDisplayPresentation? = null

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        displayManager = getSystemService(DISPLAY_SERVICE) as DisplayManager
        displayManager.registerDisplayListener(this, null)

        binding.btnAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        binding.btnLaunch.setOnClickListener {
            launchController()
        }

        refreshDisplayInfo()
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
    }

    override fun onDestroy() {
        super.onDestroy()
        displayManager.unregisterDisplayListener(this)
        presentation?.dismiss()
    }

    // ── DisplayListener ────────────────────────────────────────────────────────

    override fun onDisplayAdded(displayId: Int) = runOnUiThread {
        refreshDisplayInfo()
        refreshUi()
    }

    override fun onDisplayRemoved(displayId: Int) = runOnUiThread {
        if (displayId != Display.DEFAULT_DISPLAY) {
            presentation?.dismiss()
            presentation = null
        }
        refreshDisplayInfo()
        refreshUi()
    }

    override fun onDisplayChanged(displayId: Int) = runOnUiThread { refreshDisplayInfo() }

    // ── Helpers ────────────────────────────────────────────────────────────────

    /**
     * Returns true if our [ControllerAccessibilityService] is listed among the
     * currently enabled accessibility services.
     */
    private fun isAccessibilityServiceEnabled(): Boolean {
        val component = "$packageName/${ControllerAccessibilityService::class.java.canonicalName}"
        val enabled   = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.split(":").any { it.trim().equals(component, ignoreCase = true) }
    }

    /** Returns the first display that is not the default (primary) display, or null. */
    private fun getSecondaryDisplay(): Display? =
        displayManager.getDisplays().firstOrNull { it.displayId != Display.DEFAULT_DISPLAY }

    /** Returns the real pixel dimensions of the primary display. */
    private fun getPrimaryMetrics(): DisplayMetrics =
        DisplayMetrics().also {
            displayManager.getDisplay(Display.DEFAULT_DISPLAY)?.getRealMetrics(it)
        }

    /** Populates the display-info text view with the current list of detected displays. */
    private fun refreshDisplayInfo() {
        val info = buildString {
            displayManager.getDisplays().forEach { d ->
                val m     = DisplayMetrics().also { d.getRealMetrics(it) }
                val label = if (d.displayId == Display.DEFAULT_DISPLAY) "Primary" else "Secondary"
                appendLine("Display ${d.displayId} [$label]  ${m.widthPixels} × ${m.heightPixels} px")
            }
        }.trimEnd()
        binding.tvDisplayInfo.text = info.ifEmpty { "No displays detected." }
    }

    /** Updates button states and status text to reflect the current system state. */
    private fun refreshUi() {
        val hasSecondary    = getSecondaryDisplay() != null
        val a11yEnabled     = isAccessibilityServiceEnabled()
        val serviceRunning  = ControllerAccessibilityService.instance != null

        when {
            !hasSecondary -> {
                binding.tvStatus.text = getString(R.string.status_no_secondary)
                binding.btnAccessibility.isEnabled = false
                binding.btnLaunch.isEnabled = false
            }
            !a11yEnabled -> {
                binding.tvStatus.text = getString(R.string.status_need_accessibility)
                binding.btnAccessibility.isEnabled = true
                binding.btnAccessibility.text = getString(R.string.btn_enable_accessibility)
                binding.btnLaunch.isEnabled = false
            }
            !serviceRunning -> {
                binding.tvStatus.text = getString(R.string.status_service_inactive)
                binding.btnAccessibility.isEnabled = false
                binding.btnAccessibility.text = getString(R.string.btn_accessibility_enabled)
                binding.btnLaunch.isEnabled = true
            }
            else -> {
                binding.tvStatus.text = getString(R.string.status_ready)
                binding.btnAccessibility.isEnabled = false
                binding.btnAccessibility.text = getString(R.string.btn_accessibility_enabled)
                binding.btnLaunch.isEnabled = true
            }
        }
    }

    /** Creates and shows a [SecondaryDisplayPresentation] on the secondary display. */
    private fun launchController() {
        val secondary = getSecondaryDisplay() ?: run {
            binding.tvStatus.text = getString(R.string.status_no_secondary)
            return
        }
        val primary = getPrimaryMetrics()

        presentation?.dismiss()
        presentation = SecondaryDisplayPresentation(this, secondary, primary.widthPixels, primary.heightPixels)
        presentation?.show()

        binding.tvStatus.text = getString(R.string.status_controller_active)
    }
}
