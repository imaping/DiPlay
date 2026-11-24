package com.shilapi.xcertplay

import android.os.Build
import java.util.Locale

/**
 * Identifies the Geely Xingyue L / Monjaro ECARX KX11 head unit (`product=kx11_*`, `model=KX11`,
 * ECARX AdaptAPI present, Android 9).
 *
 * Confirmed on a 2021 Xingyue L (`kx11_high`, Android 9): the head unit routes steering-wheel volume
 * keys by active audio focus rather than Android KeyEvents. Standard `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE`
 * maps to `STREAM_NAVI_TTS` (stream 15), and requesting duckable navigation prompt audio focus lets
 * the steering wheel adjust navigation volume during prompts and return to media volume (stream 3)
 * afterwards. Also defaults to 60 fps and location/vehicle-speed reporting enabled on this head unit.
 */
internal object EcarxKx11HeadUnit {
    internal fun matches(
        product: String?,
        device: String?,
        model: String?,
        ecarxPresent: Boolean,
    ): Boolean {
        if (!ecarxPresent) return false
        return listOfNotNull(product, device, model)
            .map { it.trim().lowercase(Locale.ROOT) }
            .any { it == "kx11" || it.startsWith("kx11_") || it.startsWith("kx11-") }
    }

    val detected: Boolean by lazy {
        runCatching {
            matches(Build.PRODUCT, Build.DEVICE, Build.MODEL, EcarxWheelSpeed.present())
        }.getOrDefault(false)
    }
}
