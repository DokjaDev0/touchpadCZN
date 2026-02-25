package com.dualpad.controller

import android.content.Context

/**
 * Persistent storage for user-configurable settings.
 *
 * Currently manages cursor sensitivity, which scales the delta movement
 * from the secondary touchpad before it is applied to the primary-screen cursor.
 */
object SettingsManager {

    private const val PREFS_NAME = "dualpad_prefs"
    private const val KEY_SENSITIVITY = "sensitivity"

    // ── Three discrete speed levels ────────────────────────────────────────────

    /** Cursor speed multiplier for the Normal level. */
    const val SENSITIVITY_NORMAL: Float = 2.0f

    /** Cursor speed multiplier for the Fast level. */
    const val SENSITIVITY_FAST: Float = 3.5f

    /** Default level on first launch. */
    const val SENSITIVITY_DEFAULT: Float = SENSITIVITY_NORMAL

    // ── Public API ─────────────────────────────────────────────────────────────

    /** Returns the stored sensitivity value, defaulting to [SENSITIVITY_DEFAULT]. */
    fun getSensitivity(context: Context): Float =
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getFloat(KEY_SENSITIVITY, SENSITIVITY_DEFAULT)

    /** Persists [value] as-is (callers are expected to pass one of the level constants). */
    fun setSensitivity(context: Context, value: Float) {
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putFloat(KEY_SENSITIVITY, value)
            .apply()
    }
}
