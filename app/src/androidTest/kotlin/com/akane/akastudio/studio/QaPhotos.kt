package com.akane.akastudio.studio

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.net.Uri
import android.provider.MediaStore
import kotlin.random.Random

/**
 * Device-generated test photos written through MediaStore into `Pictures/AkaStudioQA/<runId>`; their `content://`
 * uris stand in for the ones the system photo picker's result delivers.
 *
 * Every photo is a distinct synthetic scene (its own hue family and geometry) with hard edges and fine grain, so the
 * curator's technical gate sees it as sharp and well exposed and its embedder does not fold the set into one burst.
 */
internal object QaPhotos {

    private const val WIDTH = 1200
    private const val HEIGHT = 900
    private const val JPEG_QUALITY = 92
    private const val SCENES = 8

    /** Inserts [count] photos under `Pictures/AkaStudioQA/<runId>` and returns their content uris. */
    fun insert(context: Context, runId: String, count: Int): List<Uri> = (0 until count).map { index ->
        val bitmap = scene(index)
        try {
            write(context, "akastudio_qa_${runId}_$index.jpg", "Pictures/AkaStudioQA/$runId", bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    /** Deletes rows this process inserted; missing rows are ignored. */
    fun delete(context: Context, uris: List<Uri>) {
        uris.forEach { uri -> runCatching { context.contentResolver.delete(uri, null, null) } }
    }

    private fun write(context: Context, name: String, relativePath: String, bitmap: Bitmap): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, relativePath)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = checkNotNull(resolver.insert(collection, values)) { "MediaStore refused $name" }
        try {
            checkNotNull(resolver.openOutputStream(uri)) { "No output stream for $uri" }.use { out ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) { "JPEG encode failed for $name" }
            }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        return uri
    }

    /** Scene [index]: a two-tone gradient in its own hue, one of eight geometries, and per-pixel grain. */
    private fun scene(index: Int): Bitmap {
        val random = Random(index * 7919 + 17)
        val hue = (index * 360f / SCENES + 15f) % 360f
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(
            0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(),
            hsv(hue, 0.55f, 0.80f), hsv((hue + 40f) % 360f, 0.65f, 0.45f), Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), paint)
        paint.shader = null
        val light = hsv((hue + 180f) % 360f, 0.35f, 0.92f)
        val dark = hsv((hue + 200f) % 360f, 0.80f, 0.22f)
        when (index % SCENES) {
            0 -> stripes(canvas, paint, light, dark, horizontal = true)
            1 -> stripes(canvas, paint, light, dark, horizontal = false)
            2 -> discs(canvas, paint, light, dark, random)
            3 -> checker(canvas, paint, light, dark)
            4 -> diagonals(canvas, paint, light, dark)
            5 -> rings(canvas, paint, light, dark)
            6 -> blocks(canvas, paint, light, dark, random)
            else -> spokes(canvas, paint, light, dark)
        }
        grain(bitmap, random)
        return bitmap
    }

    private fun stripes(canvas: Canvas, paint: Paint, light: Int, dark: Int, horizontal: Boolean) {
        val bands = if (horizontal) 9 else 13
        val extent = if (horizontal) HEIGHT else WIDTH
        val step = extent / bands.toFloat()
        for (i in 0 until bands step 2) {
            paint.color = if (i % 4 == 0) light else dark
            val a = i * step
            if (horizontal) canvas.drawRect(0f, a, WIDTH.toFloat(), a + step, paint) else canvas.drawRect(a, 0f, a + step, HEIGHT.toFloat(), paint)
        }
    }

    private fun discs(canvas: Canvas, paint: Paint, light: Int, dark: Int, random: Random) {
        repeat(14) { i ->
            paint.color = if (i % 2 == 0) light else dark
            canvas.drawCircle(random.nextFloat() * WIDTH, random.nextFloat() * HEIGHT, 40f + random.nextFloat() * 120f, paint)
        }
    }

    private fun checker(canvas: Canvas, paint: Paint, light: Int, dark: Int) {
        val cell = 75f
        var y = 0f
        var row = 0
        while (y < HEIGHT) {
            var x = 0f
            var col = 0
            while (x < WIDTH) {
                if ((row + col) % 2 == 0) {
                    paint.color = if (row % 3 == 0) light else dark
                    canvas.drawRect(x, y, x + cell, y + cell, paint)
                }
                x += cell
                col++
            }
            y += cell
            row++
        }
    }

    private fun diagonals(canvas: Canvas, paint: Paint, light: Int, dark: Int) {
        paint.strokeWidth = 22f
        var x = -HEIGHT.toFloat()
        var i = 0
        while (x < WIDTH) {
            paint.color = if (i % 2 == 0) light else dark
            canvas.drawLine(x, HEIGHT.toFloat(), x + HEIGHT, 0f, paint)
            x += 70f
            i++
        }
    }

    private fun rings(canvas: Canvas, paint: Paint, light: Int, dark: Int) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 18f
        var r = 30f
        var i = 0
        while (r < WIDTH) {
            paint.color = if (i % 2 == 0) light else dark
            canvas.drawCircle(WIDTH * 0.35f, HEIGHT * 0.6f, r, paint)
            r += 46f
            i++
        }
        paint.style = Paint.Style.FILL
    }

    private fun blocks(canvas: Canvas, paint: Paint, light: Int, dark: Int, random: Random) {
        repeat(18) { i ->
            paint.color = if (i % 3 == 0) light else dark
            val left = random.nextFloat() * WIDTH
            val top = random.nextFloat() * HEIGHT
            canvas.drawRect(left, top, left + 60f + random.nextFloat() * 260f, top + 40f + random.nextFloat() * 180f, paint)
        }
    }

    private fun spokes(canvas: Canvas, paint: Paint, light: Int, dark: Int) {
        paint.strokeWidth = 14f
        val cx = WIDTH * 0.6f
        val cy = HEIGHT * 0.4f
        repeat(36) { i ->
            paint.color = if (i % 2 == 0) light else dark
            val angle = Math.toRadians(i * 10.0)
            canvas.drawLine(cx, cy, cx + (WIDTH * Math.cos(angle)).toFloat(), cy + (WIDTH * Math.sin(angle)).toFloat(), paint)
        }
    }

    /** Adds ±12 levels of per-pixel luminance grain, so every frame carries fine detail. */
    private fun grain(bitmap: Bitmap, random: Random) {
        val pixels = IntArray(WIDTH * HEIGHT)
        bitmap.getPixels(pixels, 0, WIDTH, 0, 0, WIDTH, HEIGHT)
        for (i in pixels.indices) {
            val n = random.nextInt(-12, 13)
            val p = pixels[i]
            pixels[i] = Color.rgb(
                (Color.red(p) + n).coerceIn(0, 255),
                (Color.green(p) + n).coerceIn(0, 255),
                (Color.blue(p) + n).coerceIn(0, 255),
            )
        }
        bitmap.setPixels(pixels, 0, WIDTH, 0, 0, WIDTH, HEIGHT)
    }

    private fun hsv(hue: Float, saturation: Float, value: Float): Int = Color.HSVToColor(floatArrayOf(hue, saturation, value))
}
