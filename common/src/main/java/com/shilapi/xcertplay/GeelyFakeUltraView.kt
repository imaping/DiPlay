package com.shilapi.xcertplay

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Picture
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.os.SystemClock
import android.view.View
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.regex.Pattern
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Full SVG Path string parser supporting M/m, L/l, H/h, V/v, C/c, S/s, Q/q, T/t, A/a, Z/z
 * and Adobe Illustrator compressed decimal sequences (e.g. `c-.55-.62-1.5-.67`).
 */
internal object GeelySvgPathParser {
    private val TOKEN_REGEX: Pattern =
        Pattern.compile("([MmLlHhVvCcSsQqTtAaZz])|([-+]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][-+]?\\d+)?)")

    fun parse(pathData: String, fillType: Path.FillType = Path.FillType.WINDING): Path {
        val path = Path()
        path.fillType = fillType
        if (pathData.isBlank()) return path

        val tokens = ArrayList<String>(128)
        val matcher = TOKEN_REGEX.matcher(pathData)
        while (matcher.find()) {
            tokens.add(matcher.group())
        }

        var idx = 0
        var cmd = ' '
        var curX = 0f
        var curY = 0f
        var startX = 0f
        var startY = 0f
        var lastCtrlX = 0f
        var lastCtrlY = 0f
        var prevCmd = ' '

        fun hasNextNumber(): Boolean {
            if (idx >= tokens.size) return false
            val t = tokens[idx]
            return t.isNotEmpty() && !t[0].isLetter()
        }

        fun nextFloat(): Float = if (idx < tokens.size) tokens[idx++].toFloatOrNull() ?: 0f else 0f

        while (idx < tokens.size) {
            val token = tokens[idx]
            if (token.length == 1 && token[0].isLetter()) {
                cmd = token[0]
                idx++
            } else if (cmd == ' ') {
                idx++
                continue
            }

            when (cmd) {
                'M', 'm' -> {
                    val dx = nextFloat()
                    val dy = nextFloat()
                    curX = if (cmd == 'm') curX + dx else dx
                    curY = if (cmd == 'm') curY + dy else dy
                    path.moveTo(curX, curY)
                    startX = curX
                    startY = curY
                    lastCtrlX = curX
                    lastCtrlY = curY
                    prevCmd = cmd
                    cmd = if (cmd == 'm') 'l' else 'L'
                }
                'L', 'l' -> {
                    while (hasNextNumber()) {
                        val dx = nextFloat()
                        val dy = nextFloat()
                        curX = if (cmd == 'l') curX + dx else dx
                        curY = if (cmd == 'l') curY + dy else dy
                        path.lineTo(curX, curY)
                        lastCtrlX = curX
                        lastCtrlY = curY
                        prevCmd = cmd
                    }
                }
                'H', 'h' -> {
                    while (hasNextNumber()) {
                        val dx = nextFloat()
                        curX = if (cmd == 'h') curX + dx else dx
                        path.lineTo(curX, curY)
                        lastCtrlX = curX
                        lastCtrlY = curY
                        prevCmd = cmd
                    }
                }
                'V', 'v' -> {
                    while (hasNextNumber()) {
                        val dy = nextFloat()
                        curY = if (cmd == 'v') curY + dy else dy
                        path.lineTo(curX, curY)
                        lastCtrlX = curX
                        lastCtrlY = curY
                        prevCmd = cmd
                    }
                }
                'C', 'c' -> {
                    while (hasNextNumber()) {
                        val x1 = if (cmd == 'c') curX + nextFloat() else nextFloat()
                        val y1 = if (cmd == 'c') curY + nextFloat() else nextFloat()
                        val x2 = if (cmd == 'c') curX + nextFloat() else nextFloat()
                        val y2 = if (cmd == 'c') curY + nextFloat() else nextFloat()
                        val x = if (cmd == 'c') curX + nextFloat() else nextFloat()
                        val y = if (cmd == 'c') curY + nextFloat() else nextFloat()
                        path.cubicTo(x1, y1, x2, y2, x, y)
                        lastCtrlX = x2
                        lastCtrlY = y2
                        curX = x
                        curY = y
                        prevCmd = cmd
                    }
                }
                'S', 's' -> {
                    while (hasNextNumber()) {
                        val reflect = prevCmd == 'C' || prevCmd == 'c' || prevCmd == 'S' || prevCmd == 's'
                        val x1 = if (reflect) 2f * curX - lastCtrlX else curX
                        val y1 = if (reflect) 2f * curY - lastCtrlY else curY
                        val x2 = if (cmd == 's') curX + nextFloat() else nextFloat()
                        val y2 = if (cmd == 's') curY + nextFloat() else nextFloat()
                        val x = if (cmd == 's') curX + nextFloat() else nextFloat()
                        val y = if (cmd == 's') curY + nextFloat() else nextFloat()
                        path.cubicTo(x1, y1, x2, y2, x, y)
                        lastCtrlX = x2
                        lastCtrlY = y2
                        curX = x
                        curY = y
                        prevCmd = cmd
                    }
                }
                'Q', 'q' -> {
                    while (hasNextNumber()) {
                        val x1 = if (cmd == 'q') curX + nextFloat() else nextFloat()
                        val y1 = if (cmd == 'q') curY + nextFloat() else nextFloat()
                        val x = if (cmd == 'q') curX + nextFloat() else nextFloat()
                        val y = if (cmd == 'q') curY + nextFloat() else nextFloat()
                        path.quadTo(x1, y1, x, y)
                        lastCtrlX = x1
                        lastCtrlY = y1
                        curX = x
                        curY = y
                        prevCmd = cmd
                    }
                }
                'T', 't' -> {
                    while (hasNextNumber()) {
                        val reflect = prevCmd == 'Q' || prevCmd == 'q' || prevCmd == 'T' || prevCmd == 't'
                        val x1 = if (reflect) 2f * curX - lastCtrlX else curX
                        val y1 = if (reflect) 2f * curY - lastCtrlY else curY
                        val x = if (cmd == 't') curX + nextFloat() else nextFloat()
                        val y = if (cmd == 't') curY + nextFloat() else nextFloat()
                        path.quadTo(x1, y1, x, y)
                        lastCtrlX = x1
                        lastCtrlY = y1
                        curX = x
                        curY = y
                        prevCmd = cmd
                    }
                }
                'A', 'a' -> {
                    while (hasNextNumber()) {
                        val rx = nextFloat()
                        val ry = nextFloat()
                        val xAxisRotation = nextFloat()
                        val largeArcFlag = nextFloat() != 0f
                        val sweepFlag = nextFloat() != 0f
                        val x = if (cmd == 'a') curX + nextFloat() else nextFloat()
                        val y = if (cmd == 'a') curY + nextFloat() else nextFloat()
                        arcToBezier(
                            path = path,
                            x0 = curX.toDouble(),
                            y0 = curY.toDouble(),
                            rxRaw = rx.toDouble(),
                            ryRaw = ry.toDouble(),
                            angleDeg = xAxisRotation.toDouble(),
                            largeArcFlag = largeArcFlag,
                            sweepFlag = sweepFlag,
                            x = x.toDouble(),
                            y = y.toDouble(),
                        )
                        curX = x
                        curY = y
                        lastCtrlX = curX
                        lastCtrlY = curY
                        prevCmd = cmd
                    }
                }
                'Z', 'z' -> {
                    path.close()
                    curX = startX
                    curY = startY
                    lastCtrlX = curX
                    lastCtrlY = curY
                    prevCmd = cmd
                    cmd = ' '
                }
                else -> {
                    cmd = ' '
                }
            }
        }
        return path
    }

    private fun arcToBezier(
        path: Path,
        x0: Double,
        y0: Double,
        rxRaw: Double,
        ryRaw: Double,
        angleDeg: Double,
        largeArcFlag: Boolean,
        sweepFlag: Boolean,
        x: Double,
        y: Double,
    ) {
        if (x0 == x && y0 == y) return
        var rx = abs(rxRaw)
        var ry = abs(ryRaw)
        if (rx == 0.0 || ry == 0.0) {
            path.lineTo(x.toFloat(), y.toFloat())
            return
        }
        val phi = Math.toRadians(angleDeg % 360.0)
        val cosPhi = cos(phi)
        val sinPhi = sin(phi)

        val dx2 = (x0 - x) / 2.0
        val dy2 = (y0 - y) / 2.0
        val x1p = cosPhi * dx2 + sinPhi * dy2
        val y1p = -sinPhi * dx2 + cosPhi * dy2

        var rxSq = rx * rx
        var rySq = ry * ry
        val x1pSq = x1p * x1p
        val y1pSq = y1p * y1p

        val radiiCheck = x1pSq / rxSq + y1pSq / rySq
        if (radiiCheck > 1.0) {
            val scale = sqrt(radiiCheck)
            rx *= scale
            ry *= scale
            rxSq = rx * rx
            rySq = ry * ry
        }

        val sign = if (largeArcFlag == sweepFlag) -1.0 else 1.0
        val sq = ((rxSq * rySq - rxSq * y1pSq - rySq * x1pSq) / (rxSq * y1pSq + rySq * x1pSq)).coerceAtLeast(0.0)
        val coef = sign * sqrt(sq)
        val cxp = coef * ((rx * y1p) / ry)
        val cyp = coef * (-(ry * x1p) / rx)

        val cx = cosPhi * cxp - sinPhi * cyp + (x0 + x) / 2.0
        val cy = sinPhi * cxp + cosPhi * cyp + (y0 + y) / 2.0

        fun vectorAngle(ux: Double, uy: Double, vx: Double, vy: Double): Double {
            val dot = ux * vx + uy * vy
            val len = hypot(ux, uy) * hypot(vx, vy)
            val ang = acos((dot / len).coerceIn(-1.0, 1.0))
            return if (ux * vy - uy * vx < 0.0) -ang else ang
        }

        val ux = (x1p - cxp) / rx
        val uy = (y1p - cyp) / ry
        val vx = (-x1p - cxp) / rx
        val vy = (-y1p - cyp) / ry

        val theta1 = vectorAngle(1.0, 0.0, ux, uy)
        var dTheta = vectorAngle(ux, uy, vx, vy)
        if (!sweepFlag && dTheta > 0) dTheta -= 2.0 * PI
        else if (sweepFlag && dTheta < 0) dTheta += 2.0 * PI

        val segments = ceil(abs(dTheta) / (PI / 2.0)).toInt().coerceAtLeast(1)
        val delta = dTheta / segments
        val t = (8.0 / 3.0) * sin(delta / 4.0) * sin(delta / 4.0) / sin(delta / 2.0)

        var curTheta = theta1
        var cosT1 = cos(curTheta)
        var sinT1 = sin(curTheta)

        for (i in 0 until segments) {
            val nextTheta = curTheta + delta
            val cosT2 = cos(nextTheta)
            val sinT2 = sin(nextTheta)

            val e1x = cosPhi * rx * (cosT1 - t * sinT1) - sinPhi * ry * (sinT1 + t * cosT1) + cx
            val e1y = sinPhi * rx * (cosT1 - t * sinT1) + cosPhi * ry * (sinT1 + t * cosT1) + cy
            val e2x = cosPhi * rx * (cosT2 + t * sinT2) - sinPhi * ry * (sinT2 - t * cosT2) + cx
            val e2y = sinPhi * rx * (cosT2 + t * sinT2) + cosPhi * ry * (sinT2 - t * cosT2) + cy
            val ex = cosPhi * rx * cosT2 - sinPhi * ry * sinT2 + cx
            val ey = sinPhi * rx * cosT2 + cosPhi * ry * sinT2 + cy

            path.cubicTo(
                e1x.toFloat(),
                e1y.toFloat(),
                e2x.toFloat(),
                e2y.toFloat(),
                ex.toFloat(),
                ey.toFloat(),
            )
            curTheta = nextTheta
            cosT1 = cosT2
            sinT1 = sinT2
        }
    }
}

/**
 * Parses and caches vector instrument SVG assets under `assets/cluster-visual/themes/`:
 * - `dark.svg`
 * - `highlight.svg`
 * - `fuel.svg`
 *
 * Supports `<defs>`, `<mask>`, `<clipPath>`, `<linearGradient data-sweep-angle="...">`, `<g>`,
 * `<path>`, `<circle>`, `<line>`, `<rect>`, and CSS `style="..."` attributes.
 * Note: `<text>` elements in `dark.svg`/`highlight.svg` are intentionally skipped because
 * dial scale numbers are dynamically rendered for Speedometer (0..240) and Tachometer (0..8).
 */
internal object GeelyVectorSvgCache {
    private data class CacheKey(
        val assetPath: String,
        val widthPx: Int,
        val heightPx: Int,
        val tintColor: Int?,
        val sweepDeg: Int,
    )

    private val cache = ConcurrentHashMap<CacheKey, Picture>()

    fun getOrLoad(
        context: Context,
        assetPath: String,
        widthPx: Int,
        heightPx: Int,
        tintColor: Int? = null,
        sweepAngleDeg: Float? = null,
    ): Picture? {
        val roundedSweep = sweepAngleDeg?.let { (it * 2f).roundToInt() } ?: -1
        val key = CacheKey(assetPath, widthPx, heightPx, tintColor, roundedSweep)
        cache[key]?.let { return it }
        val loaded = runCatching {
            renderSvgToPicture(context, assetPath, widthPx, heightPx, tintColor, sweepAngleDeg)
        }.getOrNull() ?: return null
        if (cache.size > 48) cache.clear()
        cache[key] = loaded
        return loaded
    }

    private fun renderSvgToPicture(
        context: Context,
        assetPath: String,
        widthPx: Int,
        heightPx: Int,
        tintColor: Int?,
        sweepAngleDeg: Float?,
    ): Picture {
        val doc = context.assets.open(assetPath).use { input ->
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = false
            }
            factory.newDocumentBuilder().parse(input)
        }
        val root = doc.documentElement
        val viewBoxAttr = root.getAttribute("viewBox").trim()
        var vbWidth = root.getAttribute("width").removeSuffix("px").toFloatOrNull() ?: 500f
        var vbHeight = root.getAttribute("height").removeSuffix("px").toFloatOrNull() ?: 500f
        if (viewBoxAttr.isNotEmpty()) {
            val parts = viewBoxAttr.split(Regex("[\\s,]+")).mapNotNull { it.toFloatOrNull() }
            if (parts.size == 4) {
                vbWidth = parts[2]
                vbHeight = parts[3]
            }
        }

        val clipPaths = HashMap<String, Path>()
        val gradients = HashMap<String, Shader>()
        collectDefs(root, vbWidth, vbHeight, tintColor, sweepAngleDeg, clipPaths, gradients)

        val picture = Picture()
        val canvas = picture.beginRecording(widthPx, heightPx)
        canvas.save()
        canvas.scale(widthPx / vbWidth, heightPx / vbHeight)
        renderChildren(canvas, root, tintColor, clipPaths, gradients)
        canvas.restore()
        picture.endRecording()
        return picture
    }

    private fun collectDefs(
        parent: Element,
        vbWidth: Float,
        vbHeight: Float,
        tintColor: Int?,
        sweepAngleDeg: Float?,
        clipPaths: MutableMap<String, Path>,
        gradients: MutableMap<String, Shader>,
    ) {
        val nodes = parent.childNodes
        for (i in 0 until nodes.length) {
            val node = nodes.item(i)
            if (node.nodeType != Node.ELEMENT_NODE) continue
            val el = node as Element
            when (el.tagName) {
                "defs" -> collectDefs(el, vbWidth, vbHeight, tintColor, sweepAngleDeg, clipPaths, gradients)
                "mask", "clipPath" -> {
                    val id = el.getAttribute("id")
                    if (id.isNotEmpty()) {
                        val path = Path().apply { fillType = Path.FillType.EVEN_ODD }
                        appendShapesToPath(el, path)
                        clipPaths[id] = path
                    }
                }
                "radialGradient" -> {
                    val id = el.getAttribute("id")
                    if (id.isNotEmpty()) {
                        val cx = parseCoord(el.getAttribute("cx"), vbWidth, vbWidth / 2f)
                        val cy = parseCoord(el.getAttribute("cy"), vbHeight, vbHeight / 2f)
                        val r = parseCoord(el.getAttribute("r"), min(vbWidth, vbHeight), min(vbWidth, vbHeight) / 2f)
                            .coerceAtLeast(1f)
                        val stops = ArrayList<Pair<Float, Int>>()
                        val children = el.childNodes
                        for (j in 0 until children.length) {
                            val c = children.item(j)
                            if (c.nodeType == Node.ELEMENT_NODE && (c as Element).tagName == "stop") {
                                val offset = c.getAttribute("offset").removeSuffix("%").let { raw ->
                                    val v = raw.toFloatOrNull() ?: 0f
                                    if (c.getAttribute("offset").endsWith("%")) v / 100f else v
                                }.coerceIn(0f, 1f)
                                val baseColor = parseColor(c.getAttribute("stop-color")) ?: Color.BLACK
                                val opacity = c.getAttribute("stop-opacity").toFloatOrNull()?.coerceIn(0f, 1f) ?: 1f
                                val alpha = (Color.alpha(baseColor) * opacity).roundToInt().coerceIn(0, 255)
                                val color = Color.argb(alpha, Color.red(baseColor), Color.green(baseColor), Color.blue(baseColor))
                                stops.add(offset to color)
                            }
                        }
                        if (stops.size >= 2) {
                            val colors = IntArray(stops.size) { k -> stops[k].second }
                            val positions = FloatArray(stops.size) { k -> stops[k].first }
                            gradients[id] = RadialGradient(cx, cy, r, colors, positions, Shader.TileMode.CLAMP)
                        }
                    }
                }
                "linearGradient" -> {
                    val id = el.getAttribute("id")
                    if (id.isNotEmpty()) {
                        val sweepAttrRaw = el.getAttribute("data-sweep-angle").trim()
                        val useSweep = sweepAngleDeg != null || sweepAttrRaw.isNotEmpty()
                        val fixedGradient = el.getAttribute("data-fixed-color").equals("true", ignoreCase = true)
                        val stops = ArrayList<Pair<Float, Int>>()
                        val children = el.childNodes
                        for (j in 0 until children.length) {
                            val c = children.item(j)
                            if (c.nodeType == Node.ELEMENT_NODE && (c as Element).tagName == "stop") {
                                val offset = c.getAttribute("offset").removeSuffix("%").let { raw ->
                                    val v = raw.toFloatOrNull() ?: 0f
                                    if (c.getAttribute("offset").endsWith("%")) v / 100f else v
                                }.coerceIn(0f, 1f)
                                val parsedStop = parseColor(c.getAttribute("stop-color")) ?: Color.WHITE
                                val baseColor = if (useSweep && !fixedGradient && tintColor != null) {
                                    tintColor
                                } else {
                                    parsedStop
                                }
                                val opacity = c.getAttribute("stop-opacity").toFloatOrNull()?.coerceIn(0f, 1f) ?: 1f
                                val alpha = (Color.alpha(baseColor) * opacity).roundToInt().coerceIn(0, 255)
                                val color = Color.argb(alpha, Color.red(baseColor), Color.green(baseColor), Color.blue(baseColor))
                                stops.add(offset to color)
                            }
                        }
                        if (stops.size >= 2) {
                            if (useSweep) {
                                val sweepSpan = (sweepAngleDeg ?: sweepAttrRaw.toFloatOrNull() ?: 45f).coerceIn(8f, 270f)
                                val spanFraction = (sweepSpan / 360f).coerceIn(0.02f, 0.95f)
                                val colors = IntArray(stops.size + 1)
                                val positions = FloatArray(stops.size + 1)
                                for (k in stops.indices) {
                                    positions[k] = (stops[k].first * spanFraction).coerceIn(0f, 1f)
                                    colors[k] = stops[k].second
                                }
                                positions[stops.size] = 1f
                                colors[stops.size] = Color.TRANSPARENT
                                val shader = SweepGradient(vbWidth / 2f, vbHeight / 2f, colors, positions)
                                val matrix = Matrix()
                                matrix.postRotate(135f, vbWidth / 2f, vbHeight / 2f)
                                shader.setLocalMatrix(matrix)
                                gradients[id] = shader
                            } else {
                                val x1 = parseCoord(el.getAttribute("x1"), vbWidth, 0f)
                                val y1 = parseCoord(el.getAttribute("y1"), vbHeight, 0f)
                                val x2 = parseCoord(el.getAttribute("x2"), vbWidth, vbWidth)
                                val y2 = parseCoord(el.getAttribute("y2"), vbHeight, 0f)
                                val colors = IntArray(stops.size) { k -> stops[k].second }
                                val positions = FloatArray(stops.size) { k -> stops[k].first }
                                gradients[id] = LinearGradient(x1, y1, x2, y2, colors, positions, Shader.TileMode.CLAMP)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun appendShapesToPath(parent: Element, outPath: Path) {
        val nodes = parent.childNodes
        for (i in 0 until nodes.length) {
            val node = nodes.item(i)
            if (node.nodeType != Node.ELEMENT_NODE) continue
            val el = node as Element
            when (el.tagName) {
                "g" -> appendShapesToPath(el, outPath)
                "path" -> {
                    val d = el.getAttribute("d")
                    if (d.isNotEmpty()) {
                        outPath.addPath(GeelySvgPathParser.parse(d, Path.FillType.EVEN_ODD))
                    }
                }
                "rect" -> {
                    val x = el.getAttribute("x").toFloatOrNull() ?: 0f
                    val y = el.getAttribute("y").toFloatOrNull() ?: 0f
                    val w = el.getAttribute("width").toFloatOrNull() ?: 0f
                    val h = el.getAttribute("height").toFloatOrNull() ?: 0f
                    if (w > 0f && h > 0f) {
                        outPath.addRect(x, y, x + w, y + h, Path.Direction.CW)
                    }
                }
                "circle" -> {
                    val cx = el.getAttribute("cx").toFloatOrNull() ?: 0f
                    val cy = el.getAttribute("cy").toFloatOrNull() ?: 0f
                    val r = el.getAttribute("r").toFloatOrNull() ?: 0f
                    if (r > 0f) {
                        outPath.addCircle(cx, cy, r, Path.Direction.CW)
                    }
                }
            }
        }
    }

    private fun renderChildren(
        canvas: Canvas,
        parent: Element,
        tintColor: Int?,
        clipPaths: Map<String, Path>,
        gradients: Map<String, Shader>,
    ) {
        val nodes = parent.childNodes
        for (i in 0 until nodes.length) {
            val node = nodes.item(i)
            if (node.nodeType != Node.ELEMENT_NODE) continue
            val el = node as Element
            when (el.tagName) {
                "defs", "text", "tspan" -> continue
                "g" -> {
                    val styleMap = parseStyle(el)
                    val saveCount = canvas.save()
                    applyClipOrMask(canvas, el, styleMap, clipPaths)
                    renderChildren(canvas, el, tintColor, clipPaths, gradients)
                    canvas.restoreToCount(saveCount)
                }
                "path" -> {
                    val d = el.getAttribute("d")
                    if (d.isNotEmpty()) {
                        val styleMap = parseStyle(el)
                        val path = GeelySvgPathParser.parse(d, Path.FillType.EVEN_ODD)
                        drawStyledPath(canvas, el, styleMap, path, tintColor, clipPaths, gradients)
                    }
                }
                "circle" -> {
                    val cx = el.getAttribute("cx").toFloatOrNull() ?: 0f
                    val cy = el.getAttribute("cy").toFloatOrNull() ?: 0f
                    val r = el.getAttribute("r").toFloatOrNull() ?: 0f
                    if (r > 0f) {
                        val styleMap = parseStyle(el)
                        val path = Path().apply { addCircle(cx, cy, r, Path.Direction.CW) }
                        drawStyledPath(canvas, el, styleMap, path, tintColor, clipPaths, gradients)
                    }
                }
                "rect" -> {
                    val x = el.getAttribute("x").toFloatOrNull() ?: 0f
                    val y = el.getAttribute("y").toFloatOrNull() ?: 0f
                    val w = el.getAttribute("width").toFloatOrNull() ?: 0f
                    val h = el.getAttribute("height").toFloatOrNull() ?: 0f
                    val rx = el.getAttribute("rx").toFloatOrNull() ?: 0f
                    val ry = el.getAttribute("ry").toFloatOrNull() ?: rx
                    if (w > 0f && h > 0f) {
                        val styleMap = parseStyle(el)
                        val path = Path().apply {
                            if (rx > 0f || ry > 0f) {
                                addRoundRect(RectF(x, y, x + w, y + h), rx, ry, Path.Direction.CW)
                            } else {
                                addRect(x, y, x + w, y + h, Path.Direction.CW)
                            }
                        }
                        drawStyledPath(canvas, el, styleMap, path, tintColor, clipPaths, gradients)
                    }
                }
                "line" -> {
                    val x1 = el.getAttribute("x1").toFloatOrNull() ?: 0f
                    val y1 = el.getAttribute("y1").toFloatOrNull() ?: 0f
                    val x2 = el.getAttribute("x2").toFloatOrNull() ?: 0f
                    val y2 = el.getAttribute("y2").toFloatOrNull() ?: 0f
                    val styleMap = parseStyle(el)
                    val path = Path().apply {
                        moveTo(x1, y1)
                        lineTo(x2, y2)
                    }
                    drawStyledPath(canvas, el, styleMap, path, tintColor, clipPaths, gradients)
                }
            }
        }
    }

    private fun applyClipOrMask(
        canvas: Canvas,
        el: Element,
        styleMap: Map<String, String>,
        clipPaths: Map<String, Path>,
    ) {
        val maskRef = styleMap["mask"] ?: el.getAttribute("mask").takeIf { it.isNotEmpty() }
        val clipRef = styleMap["clip-path"] ?: el.getAttribute("clip-path").takeIf { it.isNotEmpty() }
        extractUrlId(maskRef)?.let { id ->
            clipPaths[id]?.let { canvas.clipPath(it) }
        }
        extractUrlId(clipRef)?.let { id ->
            clipPaths[id]?.let { canvas.clipPath(it) }
        }
    }

    private fun drawStyledPath(
        canvas: Canvas,
        el: Element,
        styleMap: Map<String, String>,
        path: Path,
        tintColor: Int?,
        clipPaths: Map<String, Path>,
        gradients: Map<String, Shader>,
    ) {
        val saveCount = canvas.save()
        applyClipOrMask(canvas, el, styleMap, clipPaths)

        val fillRaw = (styleMap["fill"] ?: el.getAttribute("fill")).trim()
        val strokeRaw = (styleMap["stroke"] ?: el.getAttribute("stroke")).trim()
        val strokeWidth = (styleMap["stroke-width"] ?: el.getAttribute("stroke-width"))
            .removeSuffix("px").trim().toFloatOrNull() ?: 1f
        val baseOpacity = (styleMap["opacity"] ?: el.getAttribute("opacity")).trim().toFloatOrNull()?.coerceIn(0f, 1f) ?: 1f
        val fillOpacity = ((styleMap["fill-opacity"] ?: el.getAttribute("fill-opacity")).trim().toFloatOrNull()?.coerceIn(0f, 1f) ?: 1f) * baseOpacity
        val strokeOpacity = ((styleMap["stroke-opacity"] ?: el.getAttribute("stroke-opacity")).trim().toFloatOrNull()?.coerceIn(0f, 1f) ?: 1f) * baseOpacity
        val capRaw = (styleMap["stroke-linecap"] ?: el.getAttribute("stroke-linecap")).trim().lowercase(Locale.ROOT)
        val strokeCap = when (capRaw) {
            "round" -> Paint.Cap.ROUND
            "square" -> Paint.Cap.SQUARE
            else -> Paint.Cap.BUTT
        }
        val effectiveTint = if (el.getAttribute("data-fixed-color").equals("true", ignoreCase = true)) null else tintColor
        val replaceTint = el.getAttribute("data-replace-tint").equals("true", ignoreCase = true)

        if (fillRaw != "none") {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
            val urlId = extractUrlId(fillRaw)
            if (urlId != null) {
                val shader = gradients[urlId]
                if (shader != null) {
                    paint.shader = shader
                    paint.alpha = (fillOpacity * 255f).roundToInt().coerceIn(0, 255)
                    canvas.drawPath(path, paint)
                }
            } else {
                val parsed = if (fillRaw.isEmpty()) Color.WHITE else parseColor(fillRaw)
                if (parsed != null) {
                    val tinted = when {
                        effectiveTint == null -> parsed
                        replaceTint -> effectiveTint
                        else -> applyTint(parsed, effectiveTint)
                    }
                    val alpha = (Color.alpha(tinted) * fillOpacity).roundToInt().coerceIn(0, 255)
                    paint.color = Color.argb(alpha, Color.red(tinted), Color.green(tinted), Color.blue(tinted))
                    canvas.drawPath(path, paint)
                }
            }
        }

        if (strokeRaw.isNotEmpty() && strokeRaw != "none") {
            val dashRaw = (styleMap["stroke-dasharray"] ?: el.getAttribute("stroke-dasharray")).trim()
            val dashOffset = (styleMap["stroke-dashoffset"] ?: el.getAttribute("stroke-dashoffset"))
                .removeSuffix("px").trim().toFloatOrNull() ?: 0f
            val dashEffect = parseDashArray(dashRaw, dashOffset)
            val strokeUrlId = extractUrlId(strokeRaw)
            if (strokeUrlId != null) {
                val shader = gradients[strokeUrlId]
                if (shader != null) {
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.STROKE
                        this.strokeWidth = strokeWidth
                        this.strokeCap = strokeCap
                        this.shader = shader
                        alpha = (strokeOpacity * 255f).roundToInt().coerceIn(0, 255)
                        if (dashEffect != null) pathEffect = dashEffect
                    }
                    canvas.drawPath(path, paint)
                }
            } else {
                val parsed = parseColor(strokeRaw)
                if (parsed != null) {
                    val tinted = when {
                        effectiveTint == null -> parsed
                        replaceTint -> effectiveTint
                        else -> applyTint(parsed, effectiveTint)
                    }
                    val alpha = (Color.alpha(tinted) * strokeOpacity).roundToInt().coerceIn(0, 255)
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.STROKE
                        this.strokeWidth = strokeWidth
                        this.strokeCap = strokeCap
                        color = Color.argb(alpha, Color.red(tinted), Color.green(tinted), Color.blue(tinted))
                        if (dashEffect != null) pathEffect = dashEffect
                    }
                    canvas.drawPath(path, paint)
                }
            }
        }
        canvas.restoreToCount(saveCount)
    }

    private fun parseDashArray(raw: String, offset: Float): DashPathEffect? {
        if (raw.isEmpty() || raw.equals("none", ignoreCase = true)) return null
        val nums = raw.split(Regex("[\\s,]+")).mapNotNull { it.removeSuffix("px").toFloatOrNull() }
            .filter { it >= 0f }
        if (nums.isEmpty() || nums.all { it == 0f }) return null
        val intervals = if (nums.size % 2 == 1) {
            FloatArray(nums.size * 2) { idx -> nums[idx % nums.size] }
        } else {
            nums.toFloatArray()
        }
        return DashPathEffect(intervals, offset)
    }

    private fun parseCoord(raw: String, refSize: Float, fallback: Float): Float {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return fallback
        return if (trimmed.endsWith("%")) {
            (trimmed.removeSuffix("%").toFloatOrNull() ?: return fallback) / 100f * refSize
        } else {
            trimmed.removeSuffix("px").toFloatOrNull() ?: fallback
        }
    }

    private fun applyTint(originalColor: Int, tintColor: Int): Int {
        // Keep pure black (#000000) mask lines dark, tint everything else proportionally
        if (Color.red(originalColor) == 0 && Color.green(originalColor) == 0 && Color.blue(originalColor) == 0) {
            return originalColor
        }
        val origHsv = FloatArray(3)
        val tintHsv = FloatArray(3)
        Color.colorToHSV(originalColor, origHsv)
        Color.colorToHSV(tintColor, tintHsv)
        // Preserve white ticks (#ffffff) as bright white
        if (origHsv[1] < 0.08f && origHsv[2] > 0.92f) {
            return originalColor
        }
        origHsv[0] = tintHsv[0]
        origHsv[1] = (origHsv[1] * 0.65f + tintHsv[1] * 0.35f).coerceIn(0f, 1f)
        return Color.HSVToColor(Color.alpha(originalColor), origHsv)
    }

    private fun parseStyle(el: Element): Map<String, String> {
        val raw = el.getAttribute("style").trim()
        if (raw.isEmpty()) return emptyMap()
        val map = HashMap<String, String>()
        for (part in raw.split(';')) {
            val idx = part.indexOf(':')
            if (idx > 0) {
                val k = part.substring(0, idx).trim().lowercase(Locale.ROOT)
                val v = part.substring(idx + 1).trim()
                map[k] = v
            }
        }
        return map
    }

    private fun extractUrlId(raw: String?): String? {
        if (raw == null || !raw.startsWith("url(")) return null
        return raw.removePrefix("url(").removeSuffix(")").trim().removePrefix("#").takeIf { it.isNotEmpty() }
    }

    private fun parseColor(raw: String?): Int? {
        val s = raw?.trim()?.lowercase(Locale.ROOT) ?: return null
        if (s.isEmpty() || s == "none") return null
        if (s.startsWith("#")) {
            val hex = s.substring(1)
            return when (hex.length) {
                3 -> {
                    val r = hex[0].digitToIntOrNull(16) ?: return null
                    val g = hex[1].digitToIntOrNull(16) ?: return null
                    val b = hex[2].digitToIntOrNull(16) ?: return null
                    Color.rgb(r * 17, g * 17, b * 17)
                }
                6 -> hex.toIntOrNull(16)?.let { 0xFF000000.toInt() or it }
                8 -> hex.toLongOrNull(16)?.toInt()
                else -> null
            }
        }
        return when (s) {
            "white" -> Color.WHITE
            "black" -> Color.BLACK
            else -> null
        }
    }
}

/** Smoothstep damped float interpolator matching `Lxb0;`. */
internal class GeelyDampedFloat(private val durationMs: Long) {
    var fromValue: Float = 0f
    var targetValue: Float = 0f
    var startAtMs: Long = 0L
    var initialized: Boolean = false

    fun reset() {
        initialized = false
        fromValue = 0f
        targetValue = 0f
    }

    fun updateTarget(newTarget: Float, nowMs: Long) {
        val clamped = newTarget.coerceIn(0f, 1f)
        if (!initialized) {
            fromValue = clamped
            targetValue = clamped
            startAtMs = nowMs
            initialized = true
            return
        }
        if (abs(clamped - targetValue) > 0.0005f) {
            fromValue = valueAt(nowMs)
            targetValue = clamped
            startAtMs = nowMs
        }
    }

    fun valueAt(nowMs: Long): Float {
        if (!initialized) return 0f
        if (durationMs <= 0L) return targetValue
        val t = ((nowMs - startAtMs).toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
        val smooth = t * t * (3f - 2f * t)
        return fromValue + (targetValue - fromValue) * smooth
    }

    fun isAnimating(nowMs: Long): Boolean =
        initialized && abs(targetValue - fromValue) > 0.0005f && (nowMs - startAtMs) < durationMs
}

/** 800ms RGB smoothstep color transition matching `Lkc0;`. */
internal class GeelyColorTransition(private val durationMs: Long = 800L) {
    var startColor: Int = DEFAULT_CYAN
    var targetColor: Int = DEFAULT_CYAN
    var startAtMs: Long = 0L
    var initialized: Boolean = false

    fun reset() {
        initialized = false
        startColor = DEFAULT_CYAN
        targetColor = DEFAULT_CYAN
    }

    fun updateTarget(newColor: Int, nowMs: Long) {
        if (!initialized) {
            startColor = newColor
            targetColor = newColor
            startAtMs = nowMs
            initialized = true
            return
        }
        if (newColor != targetColor) {
            startColor = colorAt(nowMs)
            targetColor = newColor
            startAtMs = nowMs
        }
    }

    fun colorAt(nowMs: Long): Int {
        if (!initialized) return DEFAULT_CYAN
        val t = ((nowMs - startAtMs).toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
        val smooth = t * t * (3f - 2f * t)
        return lerpColor(startColor, targetColor, smooth)
    }

    fun isAnimating(nowMs: Long): Boolean =
        initialized && startColor != targetColor && (nowMs - startAtMs) < durationMs

    companion object {
        const val DEFAULT_CYAN = 0xFF18CBAA.toInt()
        private const val SPEED_GREEN = 0xFF27D86C.toInt()
        private const val SPEED_AMBER = 0xFFFFC247.toInt()
        private const val SPEED_RED = 0xFFFF5964.toInt()

        fun lerpColor(c1: Int, c2: Int, t: Float): Int {
            val f = t.coerceIn(0f, 1f)
            val a = (Color.alpha(c1) + (Color.alpha(c2) - Color.alpha(c1)) * f).roundToInt()
            val r = (Color.red(c1) + (Color.red(c2) - Color.red(c1)) * f).roundToInt()
            val g = (Color.green(c1) + (Color.green(c2) - Color.green(c1)) * f).roundToInt()
            val b = (Color.blue(c1) + (Color.blue(c2) - Color.blue(c1)) * f).roundToInt()
            return Color.argb(a, r, g, b)
        }

        fun colorForSpeed(speedKmh: Float): Int {
            val v = speedKmh.coerceAtLeast(0f)
            return when {
                v <= 40f -> lerpColor(DEFAULT_CYAN, SPEED_GREEN, v / 40f)
                v <= 80f -> lerpColor(SPEED_GREEN, SPEED_AMBER, (v - 40f) / 40f)
                v <= 120f -> lerpColor(SPEED_AMBER, SPEED_RED, (v - 80f) / 40f)
                else -> SPEED_RED
            }
        }

        /** 24-bin HSV dominant hue extractor matching `Lva0;.setArtwork`. */
        fun extractDominantAlbumAccent(bitmap: Bitmap?): Int? {
            if (bitmap == null || bitmap.isRecycled) return null
            return runCatching {
                val scaled = Bitmap.createScaledBitmap(bitmap, 32, 32, true)
                val binWeights = FloatArray(24)
                val binSatSum = FloatArray(24)
                val binValSum = FloatArray(24)
                val hsv = FloatArray(3)
                for (y in 0 until scaled.height) {
                    for (x in 0 until scaled.width) {
                        val pixel = scaled.getPixel(x, y)
                        if (Color.alpha(pixel) < 128) continue
                        Color.colorToHSV(pixel, hsv)
                        val s = hsv[1]
                        val v = hsv[2]
                        if (s < 0.15f || v < 0.12f) continue
                        val bin = ((hsv[0] / 15f).toInt()).coerceIn(0, 23)
                        val weight = s * (0.4f + 0.6f * v)
                        binWeights[bin] += weight
                        binSatSum[bin] += s * weight
                        binValSum[bin] += v * weight
                    }
                }
                if (scaled !== bitmap) scaled.recycle()
                var bestBin = -1
                var bestWeight = 0f
                for (i in 0 until 24) {
                    if (binWeights[i] > bestWeight) {
                        bestWeight = binWeights[i]
                        bestBin = i
                    }
                }
                if (bestBin < 0 || bestWeight <= 0f) return null
                val avgS = (binSatSum[bestBin] / bestWeight).coerceAtLeast(0.75f).coerceAtMost(1f)
                val avgV = (binValSum[bestBin] / bestWeight).coerceAtLeast(0.92f).coerceAtMost(1f)
                Color.HSVToColor(floatArrayOf(bestBin * 15f + 7.5f, avgS, avgV))
            }.getOrNull()
        }
    }
}

/**
 * Multi-theme CarPlay Ultra cluster overlay: ambient map vignette + box-blurred album background
 * + dual 520x520 instrument dials + bottom music/status bar in `1920x720` space.
 */
internal class GeelyFakeUltraOverlayView(context: Context) : View(context) {
    var config: GeelySelfDrawConfig = GeelyClusterPrefs.load(context)
        set(value) {
            field = value
            updatePollingState()
            invalidate()
        }

    var nightMode: Boolean = GeelyClusterEnhancer.resolveEffectiveNightMode(config, context)
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    var routeActive: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    var carPlayConnected: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    var previewMode: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                updatePollingState()
                invalidate()
            }
        }

    private var rawArtwork: Bitmap? = null
    private var blurredArtwork: Bitmap? = null
    private var prevBlurredArtwork: Bitmap? = null
    private var prevRawArtwork: Bitmap? = null
    private var artworkTransitionStartMs: Long = 0L
    private var dominantAlbumColor: Int? = null
    private var nowPlayingTitle: String? = null
    private var nowPlayingActive: Boolean = false

    private val colorTransition = GeelyColorTransition(durationMs = 800L)
    private val ultraThemeRenderer = GeelyUltraThemeRenderer(context)

    @Volatile
    private var sensorSnapshot = GeelyClusterSensorSnapshot()
    private var pollFuture: ScheduledFuture<*>? = null
    private val pollInFlight = AtomicBoolean(false)

    private val blurPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val centerCoverClip = Path()
    private val centerCoverRect = RectF()
    private val timeFormatter = SimpleDateFormat("HH:mm", Locale.getDefault())

    init {
        isClickable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun setArtwork(bitmap: Bitmap?) {
        if (bitmap === rawArtwork) return
        if (bitmap == null || bitmap.isRecycled) {
            prevRawArtwork = rawArtwork
            prevBlurredArtwork = blurredArtwork
            rawArtwork = null
            blurredArtwork = null
            dominantAlbumColor = null
            artworkTransitionStartMs = SystemClock.uptimeMillis()
            invalidate()
            return
        }
        prevRawArtwork = rawArtwork
        prevBlurredArtwork = blurredArtwork
        rawArtwork = bitmap
        blurredArtwork = createBoxBlurredBitmap(bitmap, width = 192, height = 72, radius = 14)
        dominantAlbumColor = GeelyColorTransition.extractDominantAlbumAccent(bitmap)
        artworkTransitionStartMs = SystemClock.uptimeMillis()
        invalidate()
    }

    fun setMusicInfo(title: String?, playing: Boolean) {
        if (nowPlayingTitle != title || nowPlayingActive != playing) {
            nowPlayingTitle = title
            nowPlayingActive = playing
            invalidate()
        }
    }

    /** Clears CarPlay-only media/navigation state on phone disconnect while keeping dials running. */
    fun clearCarPlayData() {
        carPlayConnected = false
        routeActive = false
        prevRawArtwork = null
        prevBlurredArtwork = null
        rawArtwork = null
        blurredArtwork = null
        dominantAlbumColor = null
        nowPlayingTitle = null
        nowPlayingActive = false
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updatePollingState()
    }

    override fun onDetachedFromWindow() {
        stopPolling()
        super.onDetachedFromWindow()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        nightMode = GeelyClusterEnhancer.resolveEffectiveNightMode(config, context)
    }

    private fun updatePollingState() {
        val shouldPoll = isAttachedToWindow && (config.dialLeftEnabled || config.dialRightEnabled || config.bottomBarEnabled)
        if (!shouldPoll) {
            stopPolling()
            return
        }
        if (pollFuture != null) return
        pollFuture = GeelyEcarxSensorReader.executor.scheduleWithFixedDelay(
            {
                if (pollInFlight.compareAndSet(false, true)) {
                    try {
                        val snap = GeelyEcarxSensorReader.poll(context)
                        sensorSnapshot = snap
                        postInvalidateOnAnimation()
                    } finally {
                        pollInFlight.set(false)
                    }
                }
            },
            0L,
            250L,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun stopPolling() {
        pollFuture?.cancel(false)
        pollFuture = null
        ultraThemeRenderer.reset()
        colorTransition.reset()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return

        val save = canvas.save()
        canvas.scale(width / 1920f, height / 720f)
        val nowElapsed = SystemClock.elapsedRealtime()
        var needsNextFrame = false
        val themeStyle = config.dialThemeStyle

        val hasCarPlayFeed = carPlayConnected || previewMode
        val effectiveRouteActive = hasCarPlayFeed && routeActive

        // Resolve active dial accent color (`DEFAULT` / `SPEED` / `ALBUM`) with 800ms transition
        val targetAccent = when (config.dialColorMode) {
            GeelyDialColorMode.DEFAULT -> themeStyle.defaultAccentColor(nightMode)
            GeelyDialColorMode.SPEED -> {
                val spd = sensorSnapshot.speedKmh.valueIfFresh(nowElapsed)
                    ?: if (previewMode) 76f else 0f
                GeelyColorTransition.colorForSpeed(spd)
            }
            GeelyDialColorMode.ALBUM -> {
                (if (hasCarPlayFeed) dominantAlbumColor else null) ?: themeStyle.defaultAccentColor(nightMode)
            }
        }
        val currentAccent = if (previewMode) {
            colorTransition.reset()
            colorTransition.updateTarget(targetAccent, nowElapsed)
            targetAccent
        } else {
            colorTransition.updateTarget(targetAccent, nowElapsed)
            if (colorTransition.isAnimating(nowElapsed)) {
                needsNextFrame = true
            }
            colorTransition.colorAt(nowElapsed)
        }

        // 1. Background / Vignette / Standby Clock Backdrop
        val hasAlbumArt = hasCarPlayFeed && rawArtwork != null && rawArtwork?.isRecycled == false
        var showingCenterClock = false
        if (!effectiveRouteActive && config.albumArtEnabled && hasAlbumArt) {
            if (drawBlurredAlbumBackground(canvas)) {
                needsNextFrame = true
            }
            if (config.mapVignetteEnabled) {
                ultraThemeRenderer.drawThemeVignette(canvas, themeStyle, nightMode, currentAccent, routeActive = true)
            }
            drawCenteredAlbumCover(canvas)
        } else {
            if (config.mapVignetteEnabled || !effectiveRouteActive) {
                ultraThemeRenderer.drawThemeVignette(canvas, themeStyle, nightMode, currentAccent, effectiveRouteActive)
            }
            if (!effectiveRouteActive) {
                ultraThemeRenderer.drawStandbyWatermark(canvas, themeStyle, nightMode, currentAccent)
                showingCenterClock = true
            }
        }

        // 2. Left Dial (Speedometer + Fuel %) at (88, 98, 520x520)
        if (config.dialLeftEnabled) {
            val leftSave = canvas.save()
            canvas.translate(88f, 98f)
            canvas.clipRect(0f, 0f, 520f, 520f)
            val animatingLeft = ultraThemeRenderer.drawLeftSpeedDial(
                canvas = canvas,
                style = themeStyle,
                sensors = sensorSnapshot,
                accentColor = currentAccent,
                nightMode = nightMode,
                nowMs = nowElapsed,
                previewDemo = previewMode,
            )
            if (animatingLeft) {
                needsNextFrame = true
            }
            canvas.restoreToCount(leftSave)
        }

        // 3. Right Dial (Tachometer / Gear / Album Art when navigating) at (1312, 98, 520x520)
        if (config.dialRightEnabled) {
            val rightSave = canvas.save()
            canvas.translate(1312f, 98f)
            canvas.clipRect(0f, 0f, 520f, 520f)
            val rightArtwork = if (effectiveRouteActive && config.albumArtEnabled && hasAlbumArt) rawArtwork else null
            val animatingRight = ultraThemeRenderer.drawRightTachDial(
                canvas = canvas,
                style = themeStyle,
                sensors = sensorSnapshot,
                accentColor = currentAccent,
                nightMode = nightMode,
                nowMs = nowElapsed,
                previewDemo = previewMode,
                centerArtwork = rightArtwork,
            )
            if (animatingRight) {
                needsNextFrame = true
            }
            canvas.restoreToCount(rightSave)
        }

        // 4. Bottom status / live lyrics & song bar (`Y: 660..720`) — unified across all themes
        if (config.bottomBarEnabled) {
            val timeText = if (showingCenterClock) "" else timeFormatter.format(Date())
            val avgFuel = sensorSnapshot.avgFuelLPer100Km.valueIfFresh(nowElapsed)
                ?: if (previewMode) 6.8f else null
            val fuelText = if (avgFuel != null) String.format(Locale.US, "平均油耗 %.1f L/100km", avgFuel) else ""
            val activeSong = when {
                !hasCarPlayFeed -> null
                previewMode && effectiveRouteActive && nowPlayingTitle.isNullOrBlank() -> "阿拉斯加海湾"
                else -> nowPlayingTitle
            }
            if (ultraThemeRenderer.drawUltraBottomMusicBar(
                    canvas = canvas,
                    style = themeStyle,
                    nightMode = nightMode,
                    songTitle = activeSong?.takeIf { it.isNotBlank() },
                    isPlaying = !activeSong.isNullOrBlank() && (nowPlayingActive || previewMode),
                    accentColor = currentAccent,
                    nowMs = SystemClock.uptimeMillis(),
                    fallbackTime = timeText,
                    fallbackFuel = fuelText,
                )
            ) {
                needsNextFrame = true
            }
        }

        canvas.restoreToCount(save)
        if (needsNextFrame) {
            postInvalidateOnAnimation()
        }
    }

    private fun drawBlurredAlbumBackground(canvas: Canvas): Boolean {
        val curBlur = blurredArtwork
        val prevBlur = prevBlurredArtwork
        if (curBlur == null && prevBlur == null) return false

        val nowUptime = SystemClock.uptimeMillis()
        val progress = ((nowUptime - artworkTransitionStartMs).toFloat() / 420f).coerceIn(0f, 1f)
        val dst = RectF(0f, 0f, 1920f, 720f)

        if (prevBlur != null && !prevBlur.isRecycled && progress < 1f) {
            blurPaint.alpha = ((1f - progress) * 210f).roundToInt().coerceIn(0, 255)
            canvas.drawBitmap(prevBlur, null, dst, blurPaint)
        }
        if (curBlur != null && !curBlur.isRecycled) {
            blurPaint.alpha = (progress * 210f).roundToInt().coerceIn(0, 255)
            canvas.drawBitmap(curBlur, null, dst, blurPaint)
        }
        scrimPaint.color = if (nightMode) Color.argb(105, 5, 8, 14) else Color.argb(80, 10, 16, 24)
        canvas.drawRect(dst, scrimPaint)
        return progress < 1f
    }

    private fun drawCenteredAlbumCover(canvas: Canvas) {
        val art = rawArtwork ?: return
        if (art.isRecycled) return
        val size = 280f
        val left = (1920f - size) / 2f
        val top = 210f
        centerCoverRect.set(left, top, left + size, top + size)

        // Subtle outer shadow card
        scrimPaint.color = Color.argb(95, 0, 0, 0)
        canvas.drawRoundRect(
            left - 4f,
            top + 6f,
            left + size + 4f,
            top + size + 10f,
            28f,
            28f,
            scrimPaint,
        )

        val save = canvas.save()
        centerCoverClip.reset()
        centerCoverClip.addRoundRect(centerCoverRect, 28f, 28f, Path.Direction.CW)
        canvas.clipPath(centerCoverClip)
        blurPaint.alpha = 255
        canvas.drawBitmap(art, null, centerCoverRect, blurPaint)
        canvas.restoreToCount(save)
    }

    companion object {
        /** 3-pass separable box blur on 192x72 downsampled bitmap matching `Lpa0;` / `Lqa0;`. */
        fun createBoxBlurredBitmap(source: Bitmap, width: Int = 192, height: Int = 72, radius: Int = 14): Bitmap? =
            runCatching {
                val scaled = Bitmap.createScaledBitmap(source, width, height, true)
                val pixels = IntArray(width * height)
                val temp = IntArray(width * height)
                scaled.getPixels(pixels, 0, width, 0, 0, width, height)
                if (scaled !== source) scaled.recycle()

                repeat(3) {
                    boxBlurHorizontal(pixels, temp, width, height, radius)
                    boxBlurVertical(temp, pixels, width, height, radius)
                }
                Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
            }.getOrNull()

        private fun boxBlurHorizontal(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
            val kernel = 2 * r + 1
            for (y in 0 until h) {
                var rSum = 0
                var gSum = 0
                var bSum = 0
                val rowOffset = y * w
                for (i in -r..r) {
                    val px = src[rowOffset + i.coerceIn(0, w - 1)]
                    rSum += (px shr 16) and 0xFF
                    gSum += (px shr 8) and 0xFF
                    bSum += px and 0xFF
                }
                for (x in 0 until w) {
                    dst[rowOffset + x] = (0xFF shl 24) or
                        ((rSum / kernel) shl 16) or
                        ((gSum / kernel) shl 8) or
                        (bSum / kernel)
                    val outPx = src[rowOffset + (x - r).coerceIn(0, w - 1)]
                    val inPx = src[rowOffset + (x + r + 1).coerceIn(0, w - 1)]
                    rSum += ((inPx shr 16) and 0xFF) - ((outPx shr 16) and 0xFF)
                    gSum += ((inPx shr 8) and 0xFF) - ((outPx shr 8) and 0xFF)
                    bSum += (inPx and 0xFF) - (outPx and 0xFF)
                }
            }
        }

        private fun boxBlurVertical(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
            val kernel = 2 * r + 1
            for (x in 0 until w) {
                var rSum = 0
                var gSum = 0
                var bSum = 0
                for (i in -r..r) {
                    val px = src[i.coerceIn(0, h - 1) * w + x]
                    rSum += (px shr 16) and 0xFF
                    gSum += (px shr 8) and 0xFF
                    bSum += px and 0xFF
                }
                for (y in 0 until h) {
                    dst[y * w + x] = (0xFF shl 24) or
                        ((rSum / kernel) shl 16) or
                        ((gSum / kernel) shl 8) or
                        (bSum / kernel)
                    val outPx = src[(y - r).coerceIn(0, h - 1) * w + x]
                    val inPx = src[(y + r + 1).coerceIn(0, h - 1) * w + x]
                    rSum += ((inPx shr 16) and 0xFF) - ((outPx shr 16) and 0xFF)
                    gSum += ((inPx shr 8) and 0xFF) - ((outPx shr 8) and 0xFF)
                    bSum += (inPx and 0xFF) - (outPx and 0xFF)
                }
            }
        }
    }
}
