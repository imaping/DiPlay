package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.host.R

/**
 * Default CarPlay "back to car" button identity (icon and label) for the Geely build.
 *
 * Kept in a standalone file to minimize merge conflicts with upstream [AirPlayPersistence]
 * and [CarBrandBadge].
 */
object GeelyCarButtonDefaults {
    const val DEFAULT_OEM_LABEL = "吉利"
    private const val LEGACY_UPSTREAM_OEM_LABEL = "BYD"
    private const val KEY_OEM_LABEL_MIGRATED_V1 = "geely_oem_label_migrated_v1"

    val DEFAULT_LABEL_RES: Int
        get() = R.string.geely_car_brand_default

    val DEFAULT_ICON_RAW_RES: Int
        get() = R.raw.ic_car_geely

    /**
     * Loads the CarPlay OEM button label, performing a one-time migration from the legacy
     * upstream `"BYD"` default to [DEFAULT_OEM_LABEL] (`"吉利"`) without overwriting a custom
     * label the user explicitly entered.
     */
    fun loadOemLabel(context: Context, prefsName: String, keyOemLabel: String): String {
        val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_OEM_LABEL_MIGRATED_V1, false)) {
            val stored = prefs.getString(keyOemLabel, null)
            val editor = prefs.edit().putBoolean(KEY_OEM_LABEL_MIGRATED_V1, true)
            if (stored.isNullOrBlank() || stored == LEGACY_UPSTREAM_OEM_LABEL) {
                editor.putString(keyOemLabel, DEFAULT_OEM_LABEL)
            }
            editor.apply()
        }
        return prefs.getString(keyOemLabel, DEFAULT_OEM_LABEL)
            // iOS hides the car icon without a label.
            .orEmpty().ifBlank { DEFAULT_OEM_LABEL }
    }
}
