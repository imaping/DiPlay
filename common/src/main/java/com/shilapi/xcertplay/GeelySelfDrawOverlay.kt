package com.shilapi.xcertplay

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.provider.Settings
import android.text.TextPaint
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.shilapi.xcertplay.hud.ClusterTurnGuidance
import com.shilapi.xcertplay.iap2.message.LaneGuidanceLane
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal data class GeelyVectorIcon(
    val width: Float,
    val height: Float,
    val paths: List<Path>,
    val pathOpacities: List<Float> = emptyList(),
)

/**
 * Full AMap HUD vector icon dictionary (`autosdk_hud_sou2`..`sou71`, `neutral_start`,
 * `autosdk_auto_landback_0`) extracted from `Lvb0;`.
 */
internal object GeelyHudIconRepo {
    private const val SIZE_140 = 140f

    private fun single(d: String, fillType: Path.FillType = Path.FillType.EVEN_ODD): GeelyVectorIcon =
        GeelyVectorIcon(SIZE_140, SIZE_140, listOf(GeelySvgPathParser.parse(d, fillType)))

    private fun multi(
        vararg parts: Pair<String, Float>,
        fillType: Path.FillType = Path.FillType.EVEN_ODD,
    ): GeelyVectorIcon = GeelyVectorIcon(
        width = SIZE_140,
        height = SIZE_140,
        paths = parts.map { GeelySvgPathParser.parse(it.first, fillType) },
        pathOpacities = parts.map { it.second },
    )

    val icons: Map<String, GeelyVectorIcon> by lazy {
        val map = HashMap<String, GeelyVectorIcon>(36)
        map["autosdk_hud_sou2"] = single(
            "M66.71,29L90,29C107.67,29 122,43.33 122,61L122,136L100,136L100,61C100,55.48 95.52,51 90,51L66.56,51L68.95,73.58C69.06,74.59 68.32,75.5 67.31,75.6C66.86,75.65 66.42,75.53 66.05,75.27L18.56,41.57C18.15,41.28 18.05,40.7 18.34,40.29C18.4,40.2 18.47,40.13 18.56,40.07L66.04,5.86C66.87,5.27 68.02,5.45 68.62,6.28C68.88,6.65 69,7.1 68.95,7.55L66.71,29Z"
        )
        map["autosdk_hud_sou3"] = single(
            "M72.6,51L50,51C44.48,51 40,55.48 40,61L40,136L18,136L18,61C18,43.33 32.33,29 50,29L72.13,29L70.05,9.42C69.94,8.41 70.68,7.5 71.69,7.4C72.14,7.35 72.58,7.47 72.95,7.73L120.44,41.43C120.85,41.72 120.95,42.3 120.66,42.71C120.6,42.8 120.53,42.87 120.44,42.93L72.96,77.14C72.13,77.73 70.98,77.55 70.38,76.72C70.12,76.35 70,75.9 70.05,75.45L72.6,51Z"
        )
        map["autosdk_hud_sou4"] = single(
            "M63.42,47.01L48.82,65.02C48.53,65.38 48.13,65.61 47.68,65.68C46.68,65.85 45.73,65.16 45.57,64.16L36.18,6.39C36.16,6.29 36.16,6.19 36.18,6.09C36.26,5.59 36.74,5.25 37.24,5.33L94.65,15.08C95.1,15.16 95.5,15.39 95.78,15.74C96.42,16.53 96.3,17.69 95.5,18.33L79.1,31.59L89.97,42.49C100.92,54.04 107.99,68.83 108,86L108,136L86,136L85.99,86.35C85.87,75.17 81.53,65.49 74.36,57.98L63.42,47.01Z"
        )
        map["autosdk_hud_sou5"] = single(
            "M61.34,31.14L45.5,18.33C44.7,17.69 44.58,16.53 45.22,15.74C45.5,15.39 45.9,15.16 46.35,15.08L103.76,5.33C104.26,5.25 104.74,5.59 104.82,6.09C104.84,6.19 104.84,6.29 104.82,6.39L95.43,64.16C95.27,65.16 94.32,65.85 93.32,65.68C92.87,65.61 92.47,65.38 92.18,65.02L77.13,46.46L65.64,57.98C58.47,65.49 54.13,75.17 54.01,86.35L54,136L32,136L32,86C32.01,68.83 39.08,54.04 50.03,42.49L61.34,31.14Z"
        )
        map["autosdk_hud_sou6"] = single(
            "M121.99,136L100.02,136L100.02,27.36C99.92,27.45 99.82,27.54 99.71,27.64L37.3,96.85L21,82.11L83.94,12.31L84.21,12.05C91.03,5.46 98.8,2.49 106.88,4.75C116.56,7.45 122.32,14.97 121.99,24.9L121.99,136ZM16.18,102.61L25.57,44.84C25.73,43.84 26.68,43.15 27.68,43.32C28.13,43.39 28.53,43.62 28.82,43.98L49.89,69.96L75.5,90.67C76.3,91.31 76.42,92.47 75.78,93.26C75.5,93.61 75.1,93.84 74.65,93.92L17.24,103.67C16.74,103.75 16.26,103.41 16.18,102.91C16.16,102.81 16.16,102.71 16.18,102.61Z"
        )
        map["autosdk_hud_sou7"] = single(
            "M100.01,58.99L112.18,43.98C112.47,43.62 112.87,43.39 113.32,43.32C114.32,43.15 115.27,43.84 115.43,44.84L124.82,102.61C124.84,102.71 124.84,102.81 124.82,102.91C124.74,103.41 124.26,103.75 123.76,103.67L66.35,93.92C65.9,93.84 65.5,93.61 65.22,93.26C64.58,92.47 64.7,91.31 65.5,90.67L84.87,75.01L41.38,27.63C41.25,27.5 41.12,27.39 40.99,27.28L41.01,136L19.03,136L19.01,24.46C18.68,14.67 24.57,7.36 34.19,4.73C42.21,2.53 49.94,5.43 56.75,11.9L57.03,12.16L100.01,58.99Z"
        )
        map["autosdk_hud_sou8"] = single(
            "M35.4,85.63L35.4,53.02L35.37,53.02L35.37,51.96C35.36,51.5 35.37,51.54 35.37,51.07C35.37,25.66 55.66,5.06 81.07,5.06C106.49,5.06 127.09,25.66 127.09,51.07C127.09,51.54 127.08,52 127.07,52.47L127,52.47L127,136L105,136L105,52.47L104.74,52.47C104.76,52 104.78,51.54 104.78,51.07C104.78,37.98 94.16,27.37 81.07,27.37C67.98,27.37 57.43,37.92 57.43,51.01L57.43,53.02L57.41,53.02L57.41,85.6L78.88,83.32C79.89,83.21 80.79,83.94 80.9,84.95C80.94,85.4 80.83,85.84 80.57,86.21L47.07,133.51C46.78,133.92 46.21,134.02 45.8,133.73C45.71,133.67 45.64,133.6 45.58,133.51L11.58,86.22C10.99,85.39 11.18,84.24 12,83.65C12.36,83.39 12.81,83.27 13.26,83.31L35.4,85.63Z"
        )
        map["autosdk_hud_sou9"] = single(
            "M79.97,54.13L79.97,136.05L58,136.05L58,54.2L36.54,56.49C35.52,56.59 34.62,55.86 34.51,54.84C34.46,54.39 34.58,53.94 34.84,53.57L68.49,5.89C68.79,5.47 69.36,5.37 69.78,5.67C69.86,5.73 69.93,5.8 69.99,5.88L104.15,53.57C104.75,54.4 104.56,55.55 103.73,56.15C103.37,56.42 102.92,56.54 102.47,56.49L79.97,54.13Z"
        )
        map["autosdk_hud_sou10"] = multi(
            "M39.2,86l3.8,0l-19.68,48l-12.32,0z" to 1.0f,
            "M95.5,9C106.05,9 115.97,13.21 123.43,20.85C130.89,28.49 135,38.65 135,49.46C135,64.18 127.19,77.75 114.6,84.88C112.76,86.23 106.93,90.63 103.68,94.57C97.61,101.91 97.22,109.25 97.21,109.32C97.17,110.21 96.51,110.91 95.66,111C95.38,111.01 95.31,111 95.24,110.99C94.45,110.87 93.83,110.21 93.76,109.37C93.76,109.3 93.16,101.72 87.31,94.56C84.19,90.73 78.24,86.23 76.37,84.86C63.81,77.73 56,64.17 56,49.46C56,38.65 60.11,28.49 67.57,20.85C75.03,13.21 84.95,9 95.5,9Z" to 1.0f,
            "M50.67,88L50.8,93.38L47.2,93.38L47.35,88L50.67,88ZM51.17,101.06L51.4,110.48L46.6,110.48L46.86,101.06L51.17,101.06ZM51.72,118.59L52,128L46,128L46.31,118.59L51.72,118.59Z" to 1.0f,
            "M55,86l3.74,0l28.26,48l-12.1,0z" to 1.0f,
        )
        map["autosdk_hud_sou11"] = multi(
            "M56.5,111C27.51,111 4,87.49 4,58.5C4,29.51 27.51,6 56.5,6C85.49,6 109,29.51 109,58.5C109,87.49 85.49,111 56.5,111ZM56.5,98.15C78.4,98.15 96.15,80.4 96.15,58.5C96.15,36.6 78.4,18.85 56.5,18.85C34.6,18.85 16.85,36.6 16.85,58.5C16.85,80.4 34.6,98.15 56.5,98.15Z" to 0.2f,
            "M91.1,54.97L67.54,57.49C66.52,57.59 65.62,56.86 65.51,55.84C65.46,55.39 65.58,54.94 65.84,54.57L99.49,6.89C99.79,6.47 100.36,6.37 100.78,6.67C100.86,6.73 100.93,6.8 100.99,6.88L135.15,54.57C135.75,55.4 135.56,56.55 134.73,57.15C134.37,57.42 133.92,57.54 133.47,57.49L110,55.02L110,58.01C109.76,82.52 93.27,103.34 71,110.31C66.35,112.93 64.01,117.68 64.01,123L64.01,136L45,136L45,118.02C45,106.64 52.52,96.67 63.39,93.5C79.4,89.37 91.03,75.4 91.03,58.12L91.1,54.97Z" to 1.0f,
        )
        map["autosdk_hud_sou12"] = multi(
            "M121,135C120.72,106.22 97.34,83 68.5,83C39.66,83 16.28,106.22 16,135L28.85,135C29.13,113.32 46.76,95.86 68.5,95.86C90.24,95.86 107.87,113.32 108.15,135L121,135Z" to 0.2f,
            "M78.29,55.02L78.29,68.98C78.29,74.47 81.24,79.53 86,82.25L85.8,82.67C107.97,89.83 124,110.55 124,135L105.57,135C105.57,117.9 93.81,103.53 77.89,99.48L78.01,98.99C67.36,96 60,86.29 60,75.21L60,54.92L35.53,57.49C35.08,57.54 34.63,57.42 34.27,57.15C33.44,56.55 33.25,55.4 33.85,54.57L68.01,6.88C68.07,6.8 68.14,6.73 68.22,6.67C68.64,6.37 69.21,6.47 69.51,6.89L103.16,54.57C103.42,54.94 103.54,55.39 103.49,55.84C103.38,56.86 102.48,57.59 101.46,57.49L78.29,55.02Z" to 1.0f,
        )
        map["autosdk_hud_sou15"] = multi(
            "M123,117C123,126.39 99.05,134 69.5,134C39.95,134 16,126.39 16,117C16,109.49 31.31,103.12 52.55,100.87L53.94,102.79C37.56,104.41 26,108.37 26,113C26,119.08 45.92,124 70.5,124C95.08,124 115,119.08 115,113C115,108.02 101.61,103.81 83.25,102.46L84.61,100.69C106.8,102.76 123,109.28 123,117Z" to 1.0f,
            "M68.5,9C79.05,9 88.97,13.21 96.43,20.85C103.89,28.49 108,38.65 108,49.46C108,64.18 100.19,77.75 87.6,84.88C85.76,86.23 79.93,90.63 76.68,94.57C70.61,101.91 70.22,109.25 70.21,109.32C70.17,110.21 69.51,110.91 68.66,111C68.38,111.01 68.31,111 68.24,110.99C67.45,110.87 66.83,110.21 66.76,109.37C66.76,109.3 66.16,101.72 60.31,94.56C57.19,90.73 51.24,86.23 49.37,84.86C36.81,77.73 29,64.17 29,49.46C29,38.65 33.11,28.49 40.57,20.85C48.03,13.21 57.95,9 68.5,9Z" to 1.0f,
        )
        map["autosdk_hud_sou16"] = single(
            "M70.5,6C101.15,6 126,30.78 126,61.35L126,134L112.13,134L112.13,61.35C112.13,38.42 93.49,19.84 70.5,19.84C47.51,19.84 28.88,38.42 28.88,61.35L28.88,134L15,134L15,61.35C15,31.09 39.35,6.5 69.58,6.01L70.5,6ZM81.77,111.51L103.54,134L37.63,134L59.23,111.51L81.77,111.51ZM70.5,28.49C88.7,28.49 103.45,43.2 103.45,61.35L103.45,123.04L89.58,109.51L89.58,61.35C89.58,50.84 81.04,42.32 70.5,42.32C59.96,42.32 51.42,50.84 51.42,61.35L51.42,109.51L37.55,123.22L37.55,61.35C37.55,43.2 52.3,28.49 70.5,28.49Z"
        )
        map["autosdk_hud_sou17"] = multi(
            "M82.5,111C111.49,111 135,87.49 135,58.5C135,29.51 111.49,6 82.5,6C53.51,6 30,29.51 30,58.5C30,87.49 53.51,111 82.5,111ZM82.81,98C60.99,98 43.31,80.32 43.31,58.5C43.31,36.68 60.99,19 82.81,19C104.62,19 122.31,36.68 122.31,58.5C122.31,80.32 104.62,98 82.81,98Z" to 0.2f,
            "M29,55.02L5.53,57.49C5.08,57.54 4.63,57.42 4.27,57.15C3.44,56.55 3.25,55.4 3.85,54.57L38.01,6.88C38.07,6.8 38.14,6.73 38.22,6.67C38.64,6.37 39.21,6.47 39.51,6.89L73.16,54.57C73.42,54.94 73.54,55.39 73.49,55.84C73.38,56.86 72.48,57.59 71.46,57.49L47.99,54.98L47.98,57C47.98,74.28 59.57,89.15 75.63,93.12C86.47,96.45 94,106.37 94,117.75L94,136L75.05,136L75.05,122.14C75.05,116.81 72.63,112.72 67.93,110.23C45.7,103.13 29.24,82.51 29,58L29,55.02Z" to 1.0f,
        )
        map["autosdk_hud_sou18"] = multi(
            "M18,135C18.28,106.22 41.66,83 70.5,83C99.34,83 122.72,106.22 123,135L110.15,135C109.87,113.32 92.24,95.86 70.5,95.86C48.76,95.86 31.13,113.32 30.85,135L18,135Z" to 0.2f,
            "M61.7,55.02L38.54,57.49C37.52,57.59 36.62,56.86 36.51,55.84C36.46,55.39 36.58,54.94 36.84,54.57L70.49,6.89C70.79,6.47 71.36,6.37 71.78,6.67C71.86,6.73 71.93,6.8 71.99,6.88L106.15,54.57C106.75,55.4 106.56,56.55 105.73,57.15C105.37,57.42 104.92,57.54 104.47,57.49L80,54.92L80,75.37C80,86.48 72.65,96.24 62.02,99.25C46.21,103.32 33.9,118.84 33.9,136L16,136C16,111.53 32.06,89.68 53.99,82.44C58.75,79.71 61.7,74.63 61.7,69.12L61.7,55.02Z" to 1.0f,
        )
        map["autosdk_hud_sou19"] = single(
            "M82.22,84.67L82.22,50.07C82.22,36.98 71.61,26.37 58.52,26.37C45.43,26.37 34.81,36.98 34.81,50.07L34.81,136L12.5,136L12.5,50.07C12.5,24.66 33.1,4.06 58.52,4.06C83.93,4.06 104.53,24.66 104.53,50.07L104.53,84.54L125.74,82.31C126.19,82.27 126.64,82.39 127,82.65C127.82,83.24 128.01,84.39 127.42,85.22L93.42,132.51C92.79,133.02 92.22,132.92 91.93,132.51L58.43,85.21C58.17,84.84 58.06,84.4 58.1,83.95C58.21,82.94 59.11,82.21 60.12,82.32L82.22,84.67Z"
        )
        map["autosdk_hud_sou20"] = single(
            "M80,53.23L80,95L59,95L59,52.98L35.54,55.49C34.52,55.59 33.62,54.86 33.51,53.84C33.46,53.39 33.58,52.94 33.84,52.57L67.49,4.89C67.79,4.47 68.36,4.37 68.78,4.67L103.15,52.57C103.75,53.4 103.56,54.55 102.73,55.15C102.37,55.42 101.92,55.54 101.47,55.49L80,53.23ZM59,104L80,104L80,116L59,116L59,104ZM59,124L80,124L80,136L59,136L59,124Z"
        )
        map["autosdk_hud_sou70"] = multi(
            "M20.24,16.83l11.28,-4.1l42.75,117.46l-11.28,4.1z" to 0.2f,
            "M93.12,7.46C93.18,7.54 93.23,7.63 93.27,7.72L113.93,62.62C114.29,63.58 113.81,64.65 112.85,65.01C112.43,65.17 111.96,65.17 111.54,65.01L90.42,56.9L83.88,81.33C81.62,89.77 80.47,98.47 80.47,107.21L80.47,135.55L58.5,135.55L58.34,105.45C58.29,96.53 59.44,87.65 61.75,79.04L69.18,51.29L47.86,47.94C46.85,47.78 46.17,46.84 46.33,45.83C46.4,45.38 46.63,44.98 46.97,44.69L91.82,7.34C92.21,7.01 92.79,7.07 93.12,7.46Z" to 1.0f,
        )
        map["autosdk_hud_sou71"] = multi(
            "M119.76,16.83l-11.28,-4.1l-42.75,117.46l11.28,4.1z" to 0.2f,
            "M46.88,7.46C46.82,7.54 46.77,7.63 46.73,7.72L26.07,62.62C25.71,63.58 26.19,64.65 27.15,65.01C27.57,65.17 28.04,65.17 28.46,65.01L49.58,56.9L56.12,81.33C58.38,89.77 59.53,98.47 59.53,107.21L59.53,135.55L81.5,135.55L81.66,105.45C81.71,96.53 80.56,87.65 78.25,79.04L70.82,51.29L92.14,47.94C93.15,47.78 93.83,46.84 93.67,45.83C93.6,45.38 93.37,44.98 93.03,44.69L48.18,7.34C47.79,7.01 47.21,7.07 46.88,7.46Z" to 1.0f,
        )
        map["neutral_start"] = multi(
            "M70,7C38,7 14,31 14,61C14,86 43,113 70,135C97,113 126,86 126,61C126,31 102,7 70,7ZM70,24C91,24 109,40 109,61C109,82 91,98 70,98C49,98 31,82 31,61C31,40 49,24 70,24Z" to 1.0f,
            "M48,41L92,41L99,58L99,82L89,82L89,75L51,75L51,82L41,82L41,58ZM54,48L49,59L91,59L86,48ZM49,65L49,70L57,70L57,65ZM83,65L83,70L91,70L91,65Z" to 1.0f,
        )
        map["autosdk_auto_landback_0"] = GeelyVectorIcon(
            width = 44f,
            height = 58f,
            paths = listOf(
                GeelySvgPathParser.parse(
                    "M25,21.865L25,49.52L19,49.52L19,21.855L13.053,22.496C12.778,22.526 12.532,22.324 12.503,22.045C12.49,21.921 12.522,21.798 12.592,21.697L21.727,8.607C21.807,8.493 21.963,8.466 22.075,8.547C22.098,8.563 22.117,8.583 22.133,8.606L31.406,21.695C31.567,21.922 31.516,22.24 31.292,22.404C31.193,22.477 31.07,22.51 30.948,22.497L25,21.865Z"
                )
            ),
        )
        map
    }

    fun iconKeyForGuidance(iconCode: Int, roundaboutExit: Int): String = when (iconCode) {
        2 -> "autosdk_hud_sou2"
        3 -> "autosdk_hud_sou3"
        4 -> "autosdk_hud_sou4"
        5 -> "autosdk_hud_sou5"
        6 -> "autosdk_hud_sou6"
        7 -> "autosdk_hud_sou7"
        8 -> "autosdk_hud_sou8"
        9 -> "autosdk_hud_sou9"
        10 -> "autosdk_hud_sou10"
        11 -> "autosdk_hud_sou11"
        12 -> "autosdk_hud_sou12"
        15 -> "autosdk_hud_sou15"
        16 -> "autosdk_hud_sou16"
        17 -> "autosdk_hud_sou17"
        18 -> "autosdk_hud_sou18"
        19 -> "autosdk_hud_sou19"
        20 -> "autosdk_hud_sou20"
        65 -> "autosdk_hud_sou70"
        66 -> "autosdk_hud_sou71"
        else -> if (roundaboutExit > 0) "autosdk_hud_sou11" else "autosdk_hud_sou9"
    }
}

/**
 * Self-draw navigation renderer on a virtual `1920x1080` canvas (`Lv3;.q`, `Lv3;.h`, `Lv3;.j`, `Lv3;.s`, `Lv3;.y`):
 * - Lower area (`Y: 720..1080`):
 *   - W-HUD crop source (`default: x=0, y=720, w=228, h=186`)
 *   - Classic instrument cluster navigation card crop source (`default: x=1165, y=720, w=364, h=112`)
 * - Upper area (`Y: 0..720`, active when fullScreen or preview):
 *   - Full-screen map navigation card (`default: x=595, y=118, w=728, h=112`)
 *   - Lane guidance bar (`0x5204`)
 */
internal object GeelySelfDrawRenderer {
    private val cardBgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 1.5f }
    private val exitTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
    }
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    private val DEMO_GUIDANCE = ClusterTurnGuidance(
        icon = 3,
        roundaboutExit = 0,
        distanceMeters = 350,
        road = "迎宾大道 / 世纪大道辅路",
        arrivalEpochSeconds = (System.currentTimeMillis() / 1000L) + 1500L,
        remainingSeconds = 1500L,
        remainingMeters = 12500L,
    )

    private val DEMO_LANES = listOf(
        LaneGuidanceLane(index = 0, status = 0, angle = -90, highlightAngle = null),
        LaneGuidanceLane(index = 1, status = 2, angle = 0, highlightAngle = 0),
        LaneGuidanceLane(index = 2, status = 2, angle = 0, highlightAngle = 90),
        LaneGuidanceLane(index = 3, status = 0, angle = 90, highlightAngle = null),
    )

    fun drawAll(
        canvas: Canvas,
        config: GeelySelfDrawConfig,
        guidance: ClusterTurnGuidance?,
        lanes: List<LaneGuidanceLane>,
        nightMode: Boolean,
        fullScreen: Boolean,
        nowMs: Long,
        forcePreview: Boolean = false,
    ): Boolean {
        val isPreview = forcePreview || config.previewEnabled
        val activeGuidance = guidance ?: if (isPreview) DEMO_GUIDANCE else null
        val activeLanes = if (lanes.isNotEmpty()) lanes else if (isPreview) DEMO_LANES else emptyList()
        if (activeGuidance == null && activeLanes.isEmpty()) return false

        val cardBaseColor = if (nightMode) Color.rgb(38, 50, 65) else Color.rgb(239, 239, 239)
        val cardTextColor = if (nightMode) Color.WHITE else Color.BLACK
        val cardSubTextColor = if (nightMode) Color.argb(190, 220, 232, 242) else Color.argb(180, 30, 40, 50)

        var animating = false

        // 1. W-HUD Hardware Crop Source (default x=0, y=720, w=228, h=186)
        if (config.hudEnabled && config.hudArea.isValid && activeGuidance != null) {
            withClippedArea(canvas, config.hudArea) { w, h ->
                val showHudLanes = config.laneEnabled && activeLanes.isNotEmpty() &&
                    (config.laneX == null || config.laneY == null)
                val contentBottom = if (showHudLanes) h - 54f else h.toFloat()

                // Left turn icon (80x80)
                drawTurnIcon(
                    canvas = canvas,
                    guidance = activeGuidance,
                    left = 0f,
                    top = 0f,
                    right = min(80f, w * 0.42f),
                    bottom = min(80f, contentBottom * 0.62f),
                    color = Color.WHITE,
                )

                // Right distance (92..w, 12..62)
                val (distNum, distUnit) = formatDistanceParts(activeGuidance.distanceMeters)
                drawDistanceWithUnit(
                    canvas = canvas,
                    numberText = distNum,
                    unitText = distUnit,
                    left = 88f,
                    top = 12f,
                    right = w.toFloat(),
                    bottom = 64f,
                    color = Color.WHITE,
                    alignCenter = false,
                )

                // Bottom road name (0..w, 84..126)
                if (drawAutoScrollText(
                        canvas = canvas,
                        text = activeGuidance.road.ifBlank { "继续行驶" },
                        left = 0f,
                        top = 84f,
                        right = w.toFloat(),
                        bottom = min(126f, contentBottom),
                        color = Color.WHITE,
                        nowMs = nowMs,
                        maxTextSize = 30f,
                    )
                ) {
                    animating = true
                }

                // Embedded HUD lane guidance when no standalone lane position is set
                if (showHudLanes) {
                    drawLaneArray(
                        canvas = canvas,
                        lanes = activeLanes,
                        left = 0f,
                        top = h - 50f,
                        right = w.toFloat(),
                        bottom = h.toFloat(),
                        color = Color.WHITE,
                    )
                }
            }
        }

        // 2. Classic Cluster Navigation Card Crop Source (default x=1165, y=720, w=364, h=112)
        if (config.cardEnabled && config.cardArea.isValid && activeGuidance != null) {
            val area = config.cardArea
            val isFullWidthInSingleSlot = !config.fullscreenCardEnabled &&
                fullScreen &&
                area.y < GeelyOverlayArea.VIDEO_HEIGHT &&
                area.width >= 600
            withClippedArea(canvas, area) { w, h ->
                drawCardGradientBackground(canvas, w.toFloat(), h.toFloat(), cardBaseColor, nightMode)
                if (isFullWidthInSingleSlot) {
                    if (drawFullscreenCardContent(
                            canvas = canvas,
                            w = w.toFloat(),
                            h = h.toFloat(),
                            guidance = activeGuidance,
                            primaryColor = cardTextColor,
                            secondaryColor = cardSubTextColor,
                            nowMs = nowMs,
                        )
                    ) {
                        animating = true
                    }
                } else {
                    if (drawCompactCardContent(
                            canvas = canvas,
                            w = w.toFloat(),
                            h = h.toFloat(),
                            guidance = activeGuidance,
                            primaryColor = cardTextColor,
                            nowMs = nowMs,
                        )
                    ) {
                        animating = true
                    }
                }
            }
        }

        // 3. Fullscreen Map Navigation Card (default x=595, y=118, w=728, h=112)
        if (config.fullscreenCardEnabled && (fullScreen || isPreview) &&
            config.fullscreenCardArea.isValid && activeGuidance != null
        ) {
            withClippedArea(canvas, config.fullscreenCardArea) { w, h ->
                drawCardGradientBackground(canvas, w.toFloat(), h.toFloat(), cardBaseColor, nightMode)
                if (drawFullscreenCardContent(
                        canvas = canvas,
                        w = w.toFloat(),
                        h = h.toFloat(),
                        guidance = activeGuidance,
                        primaryColor = cardTextColor,
                        secondaryColor = cardSubTextColor,
                        nowMs = nowMs,
                    )
                ) {
                    animating = true
                }
            }
        }

        // 4. Standalone Lane Guidance Bar (when laneX/laneY configured or in fullscreen map mode)
        if (config.laneEnabled && activeLanes.isNotEmpty()) {
            val laneCount = activeLanes.size.coerceIn(1, 12)
            val laneBoxWidth = (laneCount * 54).coerceIn(140, 648)
            val laneBoxHeight = 76
            val laneArea: GeelyOverlayArea? = when {
                config.laneX != null && config.laneY != null ->
                    GeelyOverlayArea(config.laneX, config.laneY, laneBoxWidth, laneBoxHeight).clamp()
                fullScreen || isPreview -> {
                    val anchor = config.fullscreenCardArea
                    val lx = anchor.x + (anchor.width - laneBoxWidth) / 2
                    val ly = (anchor.bottom + 8).coerceAtMost(GeelyOverlayArea.VIDEO_HEIGHT - laneBoxHeight - 8)
                    GeelyOverlayArea(lx, ly, laneBoxWidth, laneBoxHeight).clamp()
                }
                else -> null
            }
            if (laneArea != null && laneArea.isValid) {
                withClippedArea(canvas, laneArea) { w, h ->
                    drawCardGradientBackground(canvas, w.toFloat(), h.toFloat(), cardBaseColor, nightMode)
                    drawLaneArray(
                        canvas = canvas,
                        lanes = activeLanes,
                        left = 8f,
                        top = 6f,
                        right = w - 8f,
                        bottom = h - 6f,
                        color = cardTextColor,
                    )
                }
            }
        }

        return animating
    }

    private fun drawCompactCardContent(
        canvas: Canvas,
        w: Float,
        h: Float,
        guidance: ClusterTurnGuidance,
        primaryColor: Int,
        nowMs: Long,
    ): Boolean {
        val iconSize = min(86f, h - 24f).coerceAtLeast(44f)
        val iconTop = (h - iconSize) / 2f
        drawTurnIcon(
            canvas = canvas,
            guidance = guidance,
            left = 18f,
            top = iconTop,
            right = 18f + iconSize,
            bottom = iconTop + iconSize,
            color = primaryColor,
        )

        val textLeft = 18f + iconSize + 14f
        val textRight = (w - 16f).coerceAtLeast(textLeft + 40f)
        val (distNum, distUnit) = formatDistanceParts(guidance.distanceMeters)
        drawDistanceWithUnit(
            canvas = canvas,
            numberText = distNum,
            unitText = distUnit,
            left = textLeft,
            top = 12f,
            right = textRight,
            bottom = h * 0.54f,
            color = primaryColor,
            alignCenter = false,
        )

        return drawAutoScrollText(
            canvas = canvas,
            text = guidance.road.ifBlank { "继续行驶" },
            left = textLeft,
            top = h * 0.57f,
            right = textRight,
            bottom = h - 12f,
            color = primaryColor,
            nowMs = nowMs,
            maxTextSize = 28f,
        )
    }

    private fun drawFullscreenCardContent(
        canvas: Canvas,
        w: Float,
        h: Float,
        guidance: ClusterTurnGuidance,
        primaryColor: Int,
        secondaryColor: Int,
        nowMs: Long,
    ): Boolean {
        val iconSize = min(86f, h - 24f).coerceAtLeast(44f)
        val iconTop = (h - iconSize) / 2f
        drawTurnIcon(
            canvas = canvas,
            guidance = guidance,
            left = 18f,
            top = iconTop,
            right = 18f + iconSize,
            bottom = iconTop + iconSize,
            color = primaryColor,
        )

        val hasRouteTotals = (guidance.remainingMeters ?: 0L) > 0L ||
            (guidance.remainingSeconds ?: 0L) > 0L ||
            (guidance.arrivalEpochSeconds ?: 0L) > 0L
        val dividerX = if (hasRouteTotals && w >= 520f) w * 0.595f else w - 16f
        val textLeft = 18f + iconSize + 14f
        val midRight = (dividerX - 14f).coerceAtLeast(textLeft + 60f)

        val (distNum, distUnit) = formatDistanceParts(guidance.distanceMeters)
        drawDistanceWithUnit(
            canvas = canvas,
            numberText = distNum,
            unitText = distUnit,
            left = textLeft,
            top = 12f,
            right = midRight,
            bottom = h * 0.54f,
            color = primaryColor,
            alignCenter = false,
        )

        var animating = drawAutoScrollText(
            canvas = canvas,
            text = guidance.road.ifBlank { "继续行驶" },
            left = textLeft,
            top = h * 0.57f,
            right = midRight,
            bottom = h - 12f,
            color = primaryColor,
            nowMs = nowMs,
            maxTextSize = 28f,
        )

        if (hasRouteTotals && w >= 520f) {
            dividerPaint.color = primaryColor
            dividerPaint.alpha = 77
            canvas.drawLine(dividerX, 20f, dividerX, h - 20f, dividerPaint)

            val rightLeft = dividerX + 16f
            val rightEnd = w - 18f
            val remMeters = guidance.remainingMeters?.toInt() ?: 0
            val remTopText = if (remMeters > 0) {
                val (rNum, rUnit) = formatDistanceParts(remMeters)
                "剩余 $rNum $rUnit"
            } else {
                "正在导航"
            }
            val remSec = guidance.remainingSeconds ?: 0L
            val etaSec = guidance.arrivalEpochSeconds ?: 0L
            val remBottomText = buildString {
                if (remSec > 0L) {
                    val mins = (remSec / 60L).coerceAtLeast(1L)
                    if (mins >= 60L) {
                        append("${mins / 60L}小时${mins % 60L}分")
                    } else {
                        append("${mins}分钟")
                    }
                }
                if (etaSec > 0L) {
                    if (isNotEmpty()) append(" · ")
                    append(timeFormat.format(Date(etaSec * 1000L)))
                    append("到达")
                }
            }.ifBlank { "路线引导中" }

            if (drawAutoScrollText(
                    canvas = canvas,
                    text = remTopText,
                    left = rightLeft,
                    top = 16f,
                    right = rightEnd,
                    bottom = h * 0.52f,
                    color = primaryColor,
                    nowMs = nowMs,
                    maxTextSize = 26f,
                )
            ) {
                animating = true
            }
            if (drawAutoScrollText(
                    canvas = canvas,
                    text = remBottomText,
                    left = rightLeft,
                    top = h * 0.56f,
                    right = rightEnd,
                    bottom = h - 14f,
                    color = secondaryColor,
                    nowMs = nowMs,
                    maxTextSize = 23f,
                )
            ) {
                animating = true
            }
        }
        return animating
    }

    /** Matches `Lv3;.j`: multi-stop vertical alpha gradient rounded card (radius = 10f). */
    private fun drawCardGradientBackground(
        canvas: Canvas,
        w: Float,
        h: Float,
        baseColor: Int,
        nightMode: Boolean,
    ) {
        val rgb = baseColor and 0x00FFFFFF
        val topAlpha = if (nightMode) 216 else 235
        val midAlpha = if (nightMode) 182 else 210
        val botAlpha = if (nightMode) 148 else 185
        cardBgPaint.shader = LinearGradient(
            0f,
            0f,
            0f,
            h,
            intArrayOf(
                (topAlpha shl 24) or rgb,
                (midAlpha shl 24) or rgb,
                (botAlpha shl 24) or rgb,
            ),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRoundRect(0f, 0f, w, h, 10f, 10f, cardBgPaint)
        cardBgPaint.shader = null
    }

    /** Matches `Lv3;.h`: draws AMap HUD vector turn icon + optional roundabout exit number. */
    private fun drawTurnIcon(
        canvas: Canvas,
        guidance: ClusterTurnGuidance,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        color: Int,
    ) {
        val boxW = right - left
        val boxH = bottom - top
        if (boxW <= 4f || boxH <= 4f) return

        val key = GeelyHudIconRepo.iconKeyForGuidance(guidance.icon, guidance.roundaboutExit)
        val icon = GeelyHudIconRepo.icons[key] ?: GeelyHudIconRepo.icons["autosdk_hud_sou9"] ?: return
        val maxDim = max(icon.width, icon.height).coerceAtLeast(1f)
        val scale = min(boxW, boxH) / maxDim
        val drawW = icon.width * scale
        val drawH = icon.height * scale
        val dx = left + (boxW - drawW) / 2f
        val dy = top + (boxH - drawH) / 2f

        val save = canvas.save()
        canvas.clipRect(left, top, right, bottom)
        canvas.translate(dx, dy)
        canvas.scale(scale, scale)

        iconPaint.color = color
        val baseAlpha = Color.alpha(color)
        for (i in icon.paths.indices) {
            val weight = icon.pathOpacities.getOrNull(i) ?: 1f
            iconPaint.alpha = (baseAlpha * weight).roundToInt().coerceIn(0, 255)
            canvas.drawPath(icon.paths[i], iconPaint)
        }
        canvas.restoreToCount(save)

        // Draw roundabout exit number inside ring if exit > 0
        if (guidance.roundaboutExit in 1..19 && (guidance.icon == 11 || guidance.icon == 17)) {
            val numSave = canvas.save()
            canvas.clipRect(left, top, right, bottom)
            exitTextPaint.color = color
            exitTextPaint.textSize = min(boxW, boxH) * 0.27f
            val cx = left + boxW * 0.42f
            val cy = top + boxH * 0.54f
            canvas.drawText(guidance.roundaboutExit.toString(), cx, cy, exitTextPaint)
            canvas.restoreToCount(numSave)
        }
    }

    /** Matches `Lv3;.s`: renders lane guidance arrows and lane dividers. */
    private fun drawLaneArray(
        canvas: Canvas,
        lanes: List<LaneGuidanceLane>,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        color: Int,
    ) {
        if (lanes.isEmpty()) return
        val boxW = right - left
        val boxH = bottom - top
        if (boxW <= 10f || boxH <= 10f) return

        val arrowIcon = GeelyHudIconRepo.icons["autosdk_auto_landback_0"] ?: return
        val visibleLanes = lanes.take(12)
        val count = visibleLanes.size
        val cellW = (boxW / count.toFloat()).coerceAtMost(56f)
        val totalW = cellW * count
        val startX = left + (boxW - totalW) / 2f
        val arrowSize = min(cellW * 0.78f, boxH * 0.82f)
        val scale = arrowSize / max(arrowIcon.width, arrowIcon.height)

        for (i in visibleLanes.indices) {
            val lane = visibleLanes[i]
            val cellLeft = startX + i * cellW
            val cellCenterX = cellLeft + cellW / 2f
            val cellCenterY = top + boxH / 2f

            val rawAngles = listOfNotNull(lane.angle, lane.highlightAngle)
            val distinctAngles = rawAngles.filter { it in -180..180 }.distinct().ifEmpty { listOf(0) }
            for (angle in distinctAngles) {
                val isRecommended = lane.recommended &&
                    (lane.highlightAngle == angle || (lane.highlightAngle == null && distinctAngles.size == 1))
                val alpha = if (isRecommended) 255 else 115

                val save = canvas.save()
                canvas.rotate(angle.toFloat(), cellCenterX, cellCenterY)
                canvas.translate(
                    cellCenterX - (arrowIcon.width * scale) / 2f,
                    cellCenterY - (arrowIcon.height * scale) / 2f,
                )
                canvas.scale(scale, scale)
                iconPaint.color = color
                iconPaint.alpha = alpha
                for (p in arrowIcon.paths) {
                    canvas.drawPath(p, iconPaint)
                }
                canvas.restoreToCount(save)
            }

            // Vertical lane divider line
            if (i < count - 1) {
                val divX = cellLeft + cellW
                dividerPaint.color = color
                dividerPaint.alpha = 77
                canvas.drawLine(divX, top + boxH * 0.18f, divX, bottom - boxH * 0.18f, dividerPaint)
            }
        }
    }

    private fun drawDistanceWithUnit(
        canvas: Canvas,
        numberText: String,
        unitText: String,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        color: Int,
        alignCenter: Boolean,
    ) {
        val boxW = right - left
        val boxH = bottom - top
        if (boxW <= 4f || boxH <= 4f) return

        val numPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            textSize = (boxH * 0.82f).coerceIn(18f, 44f)
        }
        val unitPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            textSize = numPaint.textSize * 0.65f
        }

        val gap = 4f
        var numW = numPaint.measureText(numberText)
        var unitW = if (unitText.isNotEmpty()) unitPaint.measureText(unitText) else 0f
        val totalW = numW + (if (unitW > 0f) gap + unitW else 0f)
        if (totalW > boxW && totalW > 1f) {
            val shrink = (boxW / totalW).coerceIn(0.5f, 1f)
            numPaint.textSize *= shrink
            unitPaint.textSize *= shrink
            numW = numPaint.measureText(numberText)
            unitW = if (unitText.isNotEmpty()) unitPaint.measureText(unitText) else 0f
        }

        val bounds = Rect()
        numPaint.getTextBounds(numberText, 0, numberText.length, bounds)
        val baselineY = top + (boxH + bounds.height()) / 2f - 2f
        val startX = if (alignCenter) {
            left + (boxW - (numW + if (unitW > 0f) gap + unitW else 0f)) / 2f
        } else {
            left
        }

        val save = canvas.save()
        canvas.clipRect(left, top, right, bottom)
        canvas.drawText(numberText, startX, baselineY, numPaint)
        if (unitText.isNotEmpty()) {
            canvas.drawText(unitText, startX + numW + gap, baselineY, unitPaint)
        }
        canvas.restoreToCount(save)
    }

    /** Matches `Lv3;.y`: draws single-line text with automatic horizontal marquee scrolling if overflowed. */
    private fun drawAutoScrollText(
        canvas: Canvas,
        text: String,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        color: Int,
        nowMs: Long,
        maxTextSize: Float,
    ): Boolean {
        val clean = text.substringBefore('\n').trim()
        val boxW = right - left
        val boxH = bottom - top
        if (clean.isEmpty() || boxW <= 4f || boxH <= 4f) return false

        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            textSize = min(maxTextSize, boxH * 0.78f).coerceAtLeast(15f)
        }
        val textW = paint.measureText(clean)
        val bounds = Rect()
        paint.getTextBounds(clean, 0, clean.length, bounds)
        val baselineY = top + (boxH + bounds.height()) / 2f - 2f

        val save = canvas.save()
        canvas.clipRect(left, top, right, bottom)
        var animating = false
        if (textW <= boxW) {
            canvas.drawText(clean, left, baselineY, paint)
        } else {
            animating = true
            val overflow = textW - boxW + 24f
            val scrollDurationMs = ((overflow / 35f) * 1000f).toLong().coerceAtLeast(1200L)
            val pauseMs = 1500L
            val cycleMs = pauseMs + scrollDurationMs + pauseMs
            val phase = nowMs % cycleMs
            val offset = when {
                phase < pauseMs -> 0f
                phase < pauseMs + scrollDurationMs -> {
                    val f = (phase - pauseMs).toFloat() / scrollDurationMs.toFloat()
                    overflow * f
                }
                else -> overflow
            }
            canvas.drawText(clean, left - offset, baselineY, paint)
        }
        canvas.restoreToCount(save)
        return animating
    }

    private fun formatDistanceParts(meters: Int): Pair<String, String> {
        val m = meters.coerceAtLeast(0)
        return when {
            m >= 10_000 -> "${m / 1000}" to "公里"
            m >= 1_000 -> String.format(Locale.US, "%.1f", m / 1000f).removeSuffix(".0") to "公里"
            else -> "$m" to "米"
        }
    }

    private inline fun withClippedArea(
        canvas: Canvas,
        area: GeelyOverlayArea,
        block: (width: Int, height: Int) -> Unit,
    ) {
        if (!area.isValid) return
        val save = canvas.save()
        canvas.clipRect(area.x, area.y, area.right, area.bottom)
        canvas.translate(area.x.toFloat(), area.y.toFloat())
        block(area.width, area.height)
        canvas.restoreToCount(save)
    }
}

/**
 * View rendering the `1920x1080` self-draw HUD, cluster card, fullscreen card, and lane guidance (`Led0;`).
 */
internal class GeelySelfDrawCanvasView(context: Context) : View(context) {
    var config: GeelySelfDrawConfig = GeelyClusterPrefs.load(context)
        set(value) {
            field = value
            invalidate()
        }

    var guidance: ClusterTurnGuidance? = null
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    var lanes: List<LaneGuidanceLane> = emptyList()
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    var nightMode: Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    var fullScreen: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    var forcePreview: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    init {
        isClickable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        nightMode = (newConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return

        val save = canvas.save()
        canvas.scale(
            width.toFloat() / GeelyOverlayArea.CANVAS_WIDTH.toFloat(),
            height.toFloat() / GeelyOverlayArea.CANVAS_HEIGHT.toFloat(),
        )
        val animating = GeelySelfDrawRenderer.drawAll(
            canvas = canvas,
            config = config,
            guidance = guidance,
            lanes = lanes,
            nightMode = nightMode,
            fullScreen = fullScreen,
            nowMs = SystemClock.uptimeMillis(),
            forcePreview = forcePreview,
        )
        canvas.restoreToCount(save)
        if (animating) {
            postInvalidateOnAnimation()
        }
    }
}

/**
 * Manages the standalone `TYPE_APPLICATION_OVERLAY (2038)` window on the instrument cluster `Display` (`Lcd0;`).
 * When attached, it projects W-HUD (`0, 720, 228x186`) and classic cluster turn card (`1165, 720, 364x112`)
 * even when full-screen map mode is not active.
 */
internal class GeelySelfDrawOverlayWindow(
    context: Context,
    val display: Display,
    private val log: (String) -> Unit = {},
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val displayContext: Context = appContext.createDisplayContext(display)
    private val windowManager: WindowManager? =
        displayContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
    val view = GeelySelfDrawCanvasView(displayContext)
    var attached: Boolean = false
        private set

    fun attach(): Boolean {
        if (attached) return true
        if (!Settings.canDrawOverlays(appContext)) return false
        val wm = windowManager ?: return false
        return runCatching {
            val lp = WindowManager.LayoutParams(
                GeelyOverlayArea.CANVAS_WIDTH,
                GeelyOverlayArea.CANVAS_HEIGHT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 0
                y = 0
                title = "DiPlayGeelyClusterSelfDraw"
            }
            wm.addView(view, lp)
            attached = true
            log("GeelySelfDraw: TYPE_APPLICATION_OVERLAY (2038) attached on displayId=${display.displayId}")
            true
        }.onFailure { e ->
            attached = false
            log("GeelySelfDraw: overlay window failed (${e.javaClass.simpleName}); fallback to presentation")
        }.getOrDefault(false)
    }

    override fun close() {
        if (!attached) return
        attached = false
        runCatching { windowManager?.removeViewImmediate(view) }
    }
}
