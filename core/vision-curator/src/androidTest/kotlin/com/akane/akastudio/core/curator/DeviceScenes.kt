package com.akane.akastudio.core.curator

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.Log
import java.io.ByteArrayOutputStream
import kotlin.random.Random

/**
 * Deterministic device-drawn test scenes for the curator's on-device models. Every scene is drawn with
 * android.graphics.Canvas, then given a seeded per-pixel grain so the technical gate sees real texture.
 */
internal object DeviceScenes {

    const val WIDTH = 512
    const val HEIGHT = 384

    /** Synthetic scene layouts; each is visually unrelated to the others. */
    enum class Kind { SUNSET, CITY, FOREST, BEACH, FLOWERS, MOUNTAIN, TABLE, STRIPES }

    /** [kind] drawn at [width]×[height], its content shifted by ([dx], [dy]) px, with grain seeded by [seed]. */
    fun scene(
        kind: Kind,
        seed: Int,
        width: Int = WIDTH,
        height: Int = HEIGHT,
        dx: Float = 0f,
        dy: Float = 0f,
        grain: Int = DEFAULT_GRAIN,
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(90, 90, 90))
        canvas.save()
        canvas.translate(dx, dy)
        val w = width.toFloat()
        val h = height.toFloat()
        val random = Random(kind.ordinal * 7919 + 17)
        when (kind) {
            Kind.SUNSET -> drawSunset(canvas, w, h, random)
            Kind.CITY -> drawCity(canvas, w, h, random)
            Kind.FOREST -> drawForest(canvas, w, h, random)
            Kind.BEACH -> drawBeach(canvas, w, h, random)
            Kind.FLOWERS -> drawFlowers(canvas, w, h, random)
            Kind.MOUNTAIN -> drawMountain(canvas, w, h, random)
            Kind.TABLE -> drawTable(canvas, w, h, random)
            Kind.STRIPES -> drawStripes(canvas, w, h)
        }
        canvas.restore()
        addGrain(bitmap, seed, grain)
        return bitmap
    }

    /** A frontal, eyes-open cartoon portrait (head, hair, eyes, brows, nose, mouth, shoulders), 384×512. */
    fun face(seed: Int, width: Int = 384, height: Int = 512): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bitmap)
        val w = width.toFloat()
        val h = height.toFloat()
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0f, 0f, h, Color.rgb(176, 192, 208), Color.rgb(120, 136, 152), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, p)
        p.shader = null

        val cx = w / 2f
        val cy = h * 0.43f
        val rx = w * 0.27f
        val ry = h * 0.26f
        // Shoulders and neck.
        p.color = Color.rgb(40, 60, 90)
        c.drawOval(RectF(cx - w * 0.48f, h * 0.80f, cx + w * 0.48f, h * 1.25f), p)
        p.color = Color.rgb(205, 150, 112)
        c.drawRect(cx - rx * 0.42f, cy + ry * 0.6f, cx + rx * 0.42f, h * 0.86f, p)
        // Hair behind the head.
        p.color = Color.rgb(48, 32, 22)
        c.drawOval(RectF(cx - rx * 1.12f, cy - ry * 1.18f, cx + rx * 1.12f, cy + ry * 0.35f), p)
        // Ears.
        p.color = Color.rgb(214, 160, 120)
        c.drawOval(RectF(cx - rx * 1.13f, cy - ry * 0.12f, cx - rx * 0.86f, cy + ry * 0.26f), p)
        c.drawOval(RectF(cx + rx * 0.86f, cy - ry * 0.12f, cx + rx * 1.13f, cy + ry * 0.26f), p)
        // Head with soft shading.
        p.shader = RadialGradient(cx, cy - ry * 0.1f, rx * 1.15f, Color.rgb(241, 196, 160), Color.rgb(196, 138, 98), Shader.TileMode.CLAMP)
        c.drawOval(RectF(cx - rx, cy - ry, cx + rx, cy + ry), p)
        p.shader = null
        // Fringe.
        p.color = Color.rgb(48, 32, 22)
        val fringe = Path().apply {
            moveTo(cx - rx * 1.02f, cy - ry * 0.25f)
            quadTo(cx - rx * 0.9f, cy - ry * 1.15f, cx, cy - ry * 1.05f)
            quadTo(cx + rx * 0.9f, cy - ry * 1.15f, cx + rx * 1.02f, cy - ry * 0.25f)
            quadTo(cx + rx * 0.6f, cy - ry * 0.75f, cx, cy - ry * 0.68f)
            quadTo(cx - rx * 0.6f, cy - ry * 0.75f, cx - rx * 1.02f, cy - ry * 0.25f)
            close()
        }
        c.drawPath(fringe, p)

        val eyeY = cy - ry * 0.08f
        val eyeDx = rx * 0.40f
        val eyeRx = rx * 0.20f
        val eyeRy = ry * 0.075f
        for (sign in listOf(-1f, 1f)) {
            val ex = cx + sign * eyeDx
            // Brow.
            p.color = Color.rgb(52, 36, 26)
            p.style = Paint.Style.STROKE
            p.strokeWidth = ry * 0.045f
            p.strokeCap = Paint.Cap.ROUND
            val brow = Path().apply {
                moveTo(ex - eyeRx * 1.1f, eyeY - eyeRy * 2.3f)
                quadTo(ex, eyeY - eyeRy * 3.4f, ex + eyeRx * 1.1f, eyeY - eyeRy * 2.4f)
            }
            c.drawPath(brow, p)
            p.style = Paint.Style.FILL
            // Eye white, iris, pupil, highlight.
            p.color = Color.rgb(246, 246, 242)
            c.drawOval(RectF(ex - eyeRx, eyeY - eyeRy, ex + eyeRx, eyeY + eyeRy), p)
            p.color = Color.rgb(92, 58, 26)
            c.drawCircle(ex, eyeY, eyeRy * 0.95f, p)
            p.color = Color.rgb(12, 8, 6)
            c.drawCircle(ex, eyeY, eyeRy * 0.45f, p)
            p.color = Color.WHITE
            c.drawCircle(ex + eyeRy * 0.3f, eyeY - eyeRy * 0.3f, eyeRy * 0.18f, p)
            // Upper lid line.
            p.color = Color.rgb(40, 26, 20)
            p.style = Paint.Style.STROKE
            p.strokeWidth = ry * 0.02f
            c.drawArc(RectF(ex - eyeRx, eyeY - eyeRy, ex + eyeRx, eyeY + eyeRy), 190f, 160f, false, p)
            p.style = Paint.Style.FILL
        }
        // Nose: bridge shadow and nostrils.
        p.color = Color.rgb(176, 120, 86)
        p.style = Paint.Style.STROKE
        p.strokeWidth = ry * 0.025f
        val nose = Path().apply {
            moveTo(cx - rx * 0.05f, eyeY + eyeRy)
            quadTo(cx - rx * 0.12f, cy + ry * 0.25f, cx - rx * 0.02f, cy + ry * 0.3f)
        }
        c.drawPath(nose, p)
        p.style = Paint.Style.FILL
        p.color = Color.rgb(120, 70, 50)
        c.drawOval(RectF(cx - rx * 0.17f, cy + ry * 0.29f, cx - rx * 0.07f, cy + ry * 0.34f), p)
        c.drawOval(RectF(cx + rx * 0.07f, cy + ry * 0.29f, cx + rx * 0.17f, cy + ry * 0.34f), p)
        // Mouth.
        p.color = Color.rgb(184, 84, 90)
        c.drawOval(RectF(cx - rx * 0.36f, cy + ry * 0.5f, cx + rx * 0.36f, cy + ry * 0.64f), p)
        p.color = Color.rgb(110, 40, 46)
        p.style = Paint.Style.STROKE
        p.strokeWidth = ry * 0.018f
        c.drawLine(cx - rx * 0.34f, cy + ry * 0.57f, cx + rx * 0.34f, cy + ry * 0.57f, p)
        p.style = Paint.Style.FILL
        addGrain(bitmap, seed, 3)
        return bitmap
    }

    /** A copy of [source] downscaled by [factor] and scaled back up with bilinear filtering: a defocus blur. */
    fun blurred(source: Bitmap, factor: Int): Bitmap {
        val small = Bitmap.createScaledBitmap(source, source.width / factor, source.height / factor, true)
        val back = Bitmap.createScaledBitmap(small, source.width, source.height, true)
        if (small !== back) small.recycle()
        return back
    }

    /** [source] multiplied by [gain] per channel. */
    fun darkened(source: Bitmap, gain: Float): Bitmap {
        val out = source.copy(Bitmap.Config.ARGB_8888, true)
        val px = IntArray(out.width * out.height)
        out.getPixels(px, 0, out.width, 0, 0, out.width, out.height)
        for (i in px.indices) {
            val c = px[i]
            px[i] = Color.rgb(
                (Color.red(c) * gain).toInt(),
                (Color.green(c) * gain).toInt(),
                (Color.blue(c) * gain).toInt(),
            )
        }
        out.setPixels(px, 0, out.width, 0, 0, out.width, out.height)
        return out
    }

    /** [source] round-tripped through the platform JPEG encoder at [quality]. */
    fun recompressed(source: Bitmap, quality: Int): Bitmap {
        val bytes = ByteArrayOutputStream().use { out ->
            check(source.compress(Bitmap.CompressFormat.JPEG, quality, out)) { "JPEG compress failed" }
            out.toByteArray()
        }
        val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        return checkNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)) { "JPEG decode failed" }
    }

    /** A flat [argb] bitmap with no texture. */
    fun flat(argb: Int, width: Int = WIDTH, height: Int = HEIGHT): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(argb) }

    private const val DEFAULT_GRAIN = 6

    private fun addGrain(bitmap: Bitmap, seed: Int, amplitude: Int) {
        if (amplitude <= 0) return
        val random = Random(seed)
        val px = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(px, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        for (i in px.indices) {
            val n = random.nextInt(-amplitude, amplitude + 1)
            val c = px[i]
            px[i] = Color.rgb(
                (Color.red(c) + n).coerceIn(0, 255),
                (Color.green(c) + n).coerceIn(0, 255),
                (Color.blue(c) + n).coerceIn(0, 255),
            )
        }
        bitmap.setPixels(px, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    }

    private fun gradient(c: Canvas, top: Float, bottom: Float, w: Float, from: Int, to: Int) {
        val p = Paint().apply { shader = LinearGradient(0f, top, 0f, bottom, from, to, Shader.TileMode.CLAMP) }
        c.drawRect(-w, top, 2 * w, bottom, p)
    }

    private fun drawSunset(c: Canvas, w: Float, h: Float, r: Random) {
        gradient(c, -h, h * 0.68f, w, Color.rgb(70, 30, 110), Color.rgb(255, 140, 60))
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 230, 120) }
        c.drawCircle(w * 0.62f, h * 0.6f, w * 0.08f, p)
        gradient(c, h * 0.68f, 2 * h, w, Color.rgb(40, 40, 90), Color.rgb(10, 10, 40))
        p.color = Color.rgb(255, 190, 110)
        repeat(30) {
            val y = h * 0.7f + r.nextFloat() * h * 0.28f
            val x = w * 0.62f + (r.nextFloat() - 0.5f) * w * 0.3f
            c.drawRect(x - 12f, y, x + 12f, y + 2f, p)
        }
        p.color = Color.rgb(20, 12, 24)
        val hills = Path().apply {
            moveTo(-w, h * 0.7f)
            lineTo(w * 0.15f, h * 0.52f)
            lineTo(w * 0.35f, h * 0.68f)
            lineTo(-w, h * 0.68f)
            close()
        }
        c.drawPath(hills, p)
    }

    private fun drawCity(c: Canvas, w: Float, h: Float, r: Random) {
        gradient(c, -h, h, w, Color.rgb(120, 180, 235), Color.rgb(210, 230, 245))
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        var x = -20f
        while (x < w + 20f) {
            val bw = 30f + r.nextFloat() * 50f
            val top = h * (0.15f + r.nextFloat() * 0.45f)
            val grey = 60 + r.nextInt(110)
            p.color = Color.rgb(grey, grey, grey + 10)
            c.drawRect(x, top, x + bw, 2 * h, p)
            var wy = top + 8f
            while (wy < h) {
                var wx = x + 5f
                while (wx < x + bw - 8f) {
                    p.color = if (r.nextInt(3) == 0) Color.rgb(250, 220, 120) else Color.rgb(40, 50, 70)
                    c.drawRect(wx, wy, wx + 6f, wy + 9f, p)
                    wx += 11f
                }
                wy += 15f
            }
            x += bw + 4f
        }
    }

    private fun drawForest(c: Canvas, w: Float, h: Float, r: Random) {
        gradient(c, -h, h * 0.5f, w, Color.rgb(150, 190, 160), Color.rgb(200, 220, 190))
        gradient(c, h * 0.5f, 2 * h, w, Color.rgb(60, 90, 40), Color.rgb(40, 60, 25))
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        repeat(45) {
            val x = r.nextFloat() * (w + 60f) - 30f
            val base = h * (0.55f + r.nextFloat() * 0.45f)
            val th = h * (0.25f + r.nextFloat() * 0.35f)
            val tw = th * 0.35f
            p.color = Color.rgb(80, 50, 30)
            c.drawRect(x - 3f, base - th * 0.1f, x + 3f, base + 8f, p)
            p.color = Color.rgb(20 + r.nextInt(40), 80 + r.nextInt(80), 20 + r.nextInt(40))
            val tree = Path().apply {
                moveTo(x, base - th)
                lineTo(x - tw, base)
                lineTo(x + tw, base)
                close()
            }
            c.drawPath(tree, p)
        }
    }

    private fun drawBeach(c: Canvas, w: Float, h: Float, r: Random) {
        gradient(c, -h, h * 0.42f, w, Color.rgb(60, 150, 230), Color.rgb(190, 230, 250))
        gradient(c, h * 0.42f, h * 0.62f, w, Color.rgb(20, 120, 160), Color.rgb(60, 200, 200))
        gradient(c, h * 0.62f, 2 * h, w, Color.rgb(235, 215, 170), Color.rgb(210, 185, 135))
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = Color.WHITE
        repeat(25) {
            val x = r.nextFloat() * w
            val y = h * 0.6f + r.nextFloat() * 6f
            c.drawRect(x, y, x + 30f, y + 3f, p)
        }
        p.color = Color.rgb(180, 160, 120)
        repeat(300) { c.drawCircle(r.nextFloat() * w, h * 0.64f + r.nextFloat() * h * 0.36f, 1.5f, p) }
        val cx = w * 0.3f
        val top = h * 0.5f
        for (k in 0 until 6) {
            p.color = if (k % 2 == 0) Color.rgb(220, 40, 40) else Color.WHITE
            c.drawArc(RectF(cx - 70f, top, cx + 70f, top + 90f), 180f + k * 30f, 30f, true, p)
        }
        p.color = Color.rgb(90, 70, 50)
        c.drawRect(cx - 2f, top + 45f, cx + 2f, h * 0.85f, p)
    }

    private fun drawFlowers(c: Canvas, w: Float, h: Float, r: Random) {
        gradient(c, -h, 2 * h, w, Color.rgb(90, 160, 60), Color.rgb(30, 90, 30))
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val petals = intArrayOf(Color.rgb(230, 40, 60), Color.rgb(250, 210, 40), Color.rgb(220, 60, 200), Color.WHITE, Color.rgb(250, 120, 30))
        repeat(140) {
            val x = r.nextFloat() * w
            val y = r.nextFloat() * h
            val rad = 6f + r.nextFloat() * 14f
            p.color = petals[r.nextInt(petals.size)]
            c.drawCircle(x, y, rad, p)
            p.color = Color.rgb(250, 230, 90)
            c.drawCircle(x, y, rad * 0.35f, p)
        }
    }

    private fun drawMountain(c: Canvas, w: Float, h: Float, r: Random) {
        gradient(c, -h, h * 0.6f, w, Color.rgb(170, 200, 230), Color.rgb(235, 240, 245))
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val peaks = listOf(0.1f to 0.22f, 0.45f to 0.12f, 0.8f to 0.25f)
        for ((px, py) in peaks) {
            p.color = Color.rgb(100 + r.nextInt(30), 105 + r.nextInt(30), 120 + r.nextInt(30))
            val m = Path().apply {
                moveTo(w * px, h * py)
                lineTo(w * px - w * 0.35f, h * 0.62f)
                lineTo(w * px + w * 0.35f, h * 0.62f)
                close()
            }
            c.drawPath(m, p)
            p.color = Color.rgb(248, 250, 252)
            val snow = Path().apply {
                moveTo(w * px, h * py)
                lineTo(w * px - w * 0.08f, h * py + h * 0.1f)
                lineTo(w * px + w * 0.08f, h * py + h * 0.1f)
                close()
            }
            c.drawPath(snow, p)
        }
        gradient(c, h * 0.62f, 2 * h, w, Color.rgb(40, 90, 140), Color.rgb(20, 50, 90))
        p.color = Color.rgb(200, 220, 240)
        repeat(20) {
            val y = h * 0.64f + r.nextFloat() * h * 0.3f
            val x = r.nextFloat() * w
            c.drawRect(x, y, x + 40f, y + 2f, p)
        }
    }

    private fun drawTable(c: Canvas, w: Float, h: Float, r: Random) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        var y = -h
        while (y < 2 * h) {
            val tone = 110 + r.nextInt(40)
            p.color = Color.rgb(tone, (tone * 0.62f).toInt(), (tone * 0.35f).toInt())
            c.drawRect(-w, y, 2 * w, y + 24f, p)
            p.color = Color.rgb(70, 40, 20)
            c.drawRect(-w, y + 23f, 2 * w, y + 24f, p)
            y += 24f
        }
        p.color = Color.rgb(245, 245, 240)
        c.drawCircle(w * 0.5f, h * 0.5f, h * 0.38f, p)
        p.color = Color.rgb(225, 225, 220)
        c.drawCircle(w * 0.5f, h * 0.5f, h * 0.30f, p)
        val foods = intArrayOf(Color.rgb(200, 60, 40), Color.rgb(90, 160, 50), Color.rgb(240, 200, 80), Color.rgb(120, 70, 40))
        repeat(25) {
            val a = r.nextFloat() * 6.283f
            val d = r.nextFloat() * h * 0.22f
            p.color = foods[r.nextInt(foods.size)]
            c.drawCircle(w * 0.5f + d * kotlin.math.cos(a), h * 0.5f + d * kotlin.math.sin(a), 8f + r.nextFloat() * 10f, p)
        }
    }

    private fun drawStripes(c: Canvas, w: Float, h: Float) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val colors = intArrayOf(Color.rgb(30, 60, 160), Color.rgb(240, 240, 230), Color.rgb(200, 40, 40))
        var x = -w
        var k = 0
        c.save()
        c.rotate(25f, w / 2f, h / 2f)
        while (x < 2 * w) {
            p.color = colors[k % colors.size]
            c.drawRect(x, -h, x + 28f, 2 * h, p)
            x += 28f
            k++
        }
        c.restore()
    }
}

/** Logs one timing line under [TIMING_TAG]; the report reads them back from logcat. */
internal fun logTiming(what: String, millis: Double) {
    Log.i(TIMING_TAG, "%s = %.1f ms".format(what, millis))
}

/** Logs one measured value under [TIMING_TAG]. */
internal fun logValue(what: String, value: Any?) {
    Log.i(TIMING_TAG, "$what = $value")
}

internal const val TIMING_TAG = "CuratorDeviceSuite"

/** Runs [block] and returns its result with the elapsed wall time in milliseconds. */
internal inline fun <T> timed(block: () -> T): Pair<T, Double> {
    val start = System.nanoTime()
    val result = block()
    return result to (System.nanoTime() - start) / 1e6
}
