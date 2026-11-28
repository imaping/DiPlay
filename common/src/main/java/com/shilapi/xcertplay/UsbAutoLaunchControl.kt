package com.shilapi.xcertplay

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log

/**
 * Controls whether Android launches [CarPlayHostActivity] automatically when an iPhone is plugged
 * into a head-unit USB data port (`ACTION_USB_DEVICE_ATTACHED`).
 *
 * Works by toggling the dedicated `<activity-alias>` ([IPHONE_USB_ATTACH_ALIAS]) via
 * [PackageManager.setComponentEnabledSetting], so disabling it prevents both the CarPlay screen
 * and the Android system USB confirmation dialog from popping up while charging, without affecting
 * manual wired CarPlay connections or CH341 MFi dongles.
 */
internal object UsbAutoLaunchControl {
    private const val TAG = "DiPlay-UsbAutoLaunch"
    private const val PREFS_NAME = "diplay_usb_auto_launch"
    private const val KEY_AUTO_LAUNCH_ON_IPHONE_USB = "auto_launch_on_iphone_usb"
    internal const val IPHONE_USB_ATTACH_ALIAS = "com.shilapi.xcertplay.IphoneUsbAttachAlias"

    fun isEnabled(context: Context): Boolean =
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_LAUNCH_ON_IPHONE_USB, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        val app = context.applicationContext
        app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_AUTO_LAUNCH_ON_IPHONE_USB, enabled)
            .apply()
        applyComponentState(app, enabled)
    }

    fun syncComponentState(context: Context) {
        val app = context.applicationContext
        applyComponentState(app, isEnabled(app))
    }

    private fun applyComponentState(app: Context, enabled: Boolean) {
        val component = ComponentName(app, IPHONE_USB_ATTACH_ALIAS)
        val pm = app.packageManager
        val targetState = if (enabled) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        runCatching {
            val current = pm.getComponentEnabledSetting(component)
            val alreadyMatches = if (enabled) {
                current == PackageManager.COMPONENT_ENABLED_STATE_ENABLED ||
                    current == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
            } else {
                current == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }
            if (!alreadyMatches) {
                pm.setComponentEnabledSetting(component, targetState, PackageManager.DONT_KILL_APP)
                Log.i(TAG, "iPhone USB auto-launch alias enabled=$enabled")
            }
        }.onFailure { error ->
            Log.w(TAG, "Failed to update iPhone USB auto-launch alias state (enabled=$enabled)", error)
        }
    }
}
