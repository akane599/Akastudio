package com.akane.akastudio.core.curator.ml

import com.akane.akastudio.core.color.api.ImagePixels
import kotlin.random.Random

/** Deterministic synthetic test images. */
internal object SyntheticImages {

    fun rgb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    fun solid(width: Int, height: Int, color: Int): ImagePixels =
        ImagePixels(width, height, IntArray(width * height) { color })

    /** Pixel colour chosen by [fill] at each (x, y). */
    fun paint(width: Int, height: Int, fill: (x: Int, y: Int) -> Int): ImagePixels =
        ImagePixels(width, height, IntArray(width * height) { fill(it % width, it / width) })

    /** Blue sky over sea over sand, with mild deterministic grain. */
    fun beach(width: Int = 192, height: Int = 144, seed: Int = 7): ImagePixels {
        val random = Random(seed)
        return paint(width, height) { _, y ->
            val n = random.nextInt(-6, 7)
            when {
                y < height / 3 -> rgb(110 + n, 175 + n, 235 + n)
                y < height * 2 / 3 -> rgb(30 + n, 100 + n, 180 + n)
                else -> rgb(232 + n, 210 + n, 165 + n)
            }
        }
    }

    /** Dark green forest with vertical trunks (strong horizontal gradients). */
    fun forest(width: Int = 192, height: Int = 144): ImagePixels = paint(width, height) { x, _ ->
        if ((x / 6) % 3 == 0) rgb(60, 40, 25) else rgb(40, 125, 45)
    }

    /** Multiplies every channel by [factor] (a brightness shift), clamped to 8 bits. */
    fun brighten(image: ImagePixels, factor: Float): ImagePixels = ImagePixels(
        image.width,
        image.height,
        IntArray(image.argb.size) {
            val p = image.argb[it]
            rgb(
                Math.round(((p ushr 16) and 0xFF) * factor),
                Math.round(((p ushr 8) and 0xFF) * factor),
                Math.round((p and 0xFF) * factor),
            )
        },
    )
}
