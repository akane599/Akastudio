package com.akane.akastudio.core.color.device

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RectF
import android.opengl.GLES20
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.akane.akastudio.core.color.api.GradeParams
import com.akane.akastudio.core.color.api.HalfFloatImage
import com.akane.akastudio.core.color.api.MasterLook
import com.akane.akastudio.core.color.api.NormRect
import com.akane.akastudio.core.color.api.Rect4f
import com.akane.akastudio.core.color.api.RenderScene
import com.akane.akastudio.core.color.api.SceneBackground
import com.akane.akastudio.core.color.api.SceneLayer
import com.akane.akastudio.core.color.gl.GlFormat
import com.akane.akastudio.core.color.gl.GlFramebuffer
import com.akane.akastudio.core.color.gl.checkGlError
import com.akane.akastudio.core.color.render.GlStoryRenderer
import com.akane.akastudio.core.color.render.OffscreenReadbackOverrides
import com.akane.akastudio.core.color.render.RenderMath
import com.akane.akastudio.core.color.render.Mat3
import com.akane.akastudio.core.color.render.PixelRect
import com.akane.akastudio.core.color.render.WorkingReadback
import com.akane.akastudio.core.color.render.createStoryRenderer
import com.akane.akastudio.core.color.render.grade.GradeVariant
import com.akane.akastudio.core.color.render.grade.ShaderLayerGrader
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Diagnostic matrix; the original OffscreenReadbackDeviceTest remains the unchanged precision gate. */
@RunWith(AndroidJUnit4::class)
class TileRasterizationDiagnosticDeviceTest {
    @Test
    fun distinguishSamplerFilteringFromInterpolatedCoordinates(): Unit = OffscreenGl.open().use { gl ->
        assumeTrue("needs colour-renderable RGBA16F", gl.capabilities.floatColorBuffer)
        val source = LinearRgbImage(PHOTO_W, PHOTO_H, FloatArray(PHOTO_W * PHOTO_H * 3) { i ->
            val p = i / 3
            codeToLinear(when (i % 3) {
                0 -> (p % PHOTO_W) * 255 / PHOTO_W
                1 -> (p / PHOTO_W) * 255 / PHOTO_H
                else -> (p * 7) and 0xFF
            })
        }).upload()
        val grader = ShaderLayerGrader(gl.environment(), GradeVariant.FLOAT)
        try {
            val graded = grader.gradeInto(source, RectF(0f, 0f, PHOTO_W.toFloat(), PHOTO_H.toFloat()), 192, 174, GradeParams.NEUTRAL, 0)
            val texture = graded.texture
            graded.bind()
            val gradedPixels = readFloatRgba(texture.width, texture.height)
            val vertex = testAssets.open("shaders/quad.vert.glsl").bufferedReader().use { it.readText() }
            // Frozen pre-fix fragment so the diagnostic remains reproducible after production changes.
            val original = InstrumentationRegistry.getInstrumentation().context.assets
                .open("shaders/tile-original-layer.frag.glsl").bufferedReader().use { it.readText() }
            val production = testAssets.open("shaders/layer.frag.glsl").bufferedReader().use { it.readText() }
            val analytic = """
                uniform vec4 u_dstRect;
                uniform vec4 u_view;
                uniform vec2 u_targetSize;
                uniform mat3 u_uvMatrix;
                vec2 probeUv() {
                    vec2 target = vec2(gl_FragCoord.x, u_targetSize.y - gl_FragCoord.y);
                    vec2 canvas = (target - u_view.zw) / u_view.xy;
                    vec2 unit = (canvas - u_dstRect.xy) / (u_dstRect.zw - u_dstRect.xy);
                    return (u_uvMatrix * vec3(unit, 1.0)).xy;
                }
            """.trimIndent()
            val bilinear = """
                vec4 probeSample(vec2 uv) {
                    ivec2 size = textureSize(u_tex, 0);
                    vec2 pixel = uv * vec2(size) - 0.5;
                    ivec2 base = ivec2(floor(pixel));
                    vec2 f = fract(pixel);
                    vec4 a = texelFetch(u_tex, clamp(base, ivec2(0), size - 1), 0);
                    vec4 b = texelFetch(u_tex, clamp(base + ivec2(1, 0), ivec2(0), size - 1), 0);
                    vec4 c = texelFetch(u_tex, clamp(base + ivec2(0, 1), ivec2(0), size - 1), 0);
                    vec4 d = texelFetch(u_tex, clamp(base + ivec2(1, 1), ivec2(0), size - 1), 0);
                    return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
                }
            """.trimIndent()
            var originalSingle: HalfFloatImage? = null
            for ((name, fragment) in listOf(
                "direct-linear" to original,
                "direct-highp-sampler" to original.replace("uniform sampler2D", "uniform highp sampler2D"),
                "direct-nearest" to original,
                "direct-manual-bilinear" to original.replace("void main()", "$bilinear\nvoid main()").replace("texture(u_tex, v_uv)", "probeSample(v_uv)"),
                "direct-analytic-uv" to original.replace("void main()", "$analytic\nvoid main()").replace("texture(u_tex, v_uv)", "texture(u_tex, probeUv())"),
                "direct-production" to production,
            )) {
                val program = compileProgram(vertex, fragment)
                try {
                    texture.bind(0)
                    val filter = if (name == "direct-nearest") GLES20.GL_NEAREST else GLES20.GL_LINEAR
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, filter)
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, filter)
                    fun render(limit: Int): HalfFloatImage {
                        val tiles = RenderMath.planTiles(WIDTH, HEIGHT, limit, limit)
                        val width = tiles.maxOf { it.width }
                        val height = tiles.maxOf { it.height }
                        val fbo = GlFramebuffer.create(width, height, GlFormat.RGBA16F)
                        try {
                            val out = ShortArray(WIDTH * HEIGHT * 4)
                            val readback = WorkingReadback(logDomain = false)
                            GLES20.glUseProgram(program)
                            GLES20.glDisable(GLES20.GL_BLEND)
                            GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
                            fun location(name: String) = GLES20.glGetUniformLocation(program, name)
                            GLES20.glUniform1i(location("u_tex"), 0)
                            GLES20.glUniform1i(location("u_dstLog"), 0)
                            GLES20.glUniform2f(location("u_halfSize"), 96f, 87f)
                            GLES20.glUniform1f(location("u_radius"), 0f)
                            GLES20.glUniform1f(location("u_aaScale"), 1f)
                            GLES20.glUniform4f(location("u_dstRect"), 12f, 16f, 204f, 190f)
                            GLES20.glUniform2f(location("u_targetSize"), width.toFloat(), height.toFloat())
                            GLES20.glUniformMatrix3fv(location("u_uvMatrix"), 1, false, Mat3.IDENTITY.values, 0)
                            for (tile in tiles) {
                                fbo.bind()
                                GLES20.glClearColor(0f, 0f, 0f, 1f)
                                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                                GLES20.glUniform4f(location("u_view"), 1f, 1f, -tile.x.toFloat(), -tile.y.toFloat())
                                texture.bind(0)
                                gl.quad.draw()
                                readback.read(PixelRect(0, height - tile.height, tile.width, tile.height), out, WIDTH, tile.x, tile.y)
                            }
                            checkGlError("direct tile probe $name")
                            return HalfFloatImage(WIDTH, HEIGHT, out)
                        } finally {
                            fbo.release()
                        }
                    }
                    val single = render(HEIGHT)
                    if (name == "direct-linear" || name == "direct-analytic-uv" || name == "direct-production") {
                        reportCpuSamples(name, single, gradedPixels, texture.width, texture.height)
                    }
                    val tiled = render(100)
                    assertArrayEquals("$name single repeat", single.rgba, render(HEIGHT).rgba)
                    assertArrayEquals("$name tiled repeat", tiled.rgba, render(100).rgba)
                    reportDifference(name, 100, single, tiled)
                    val reference = originalSingle
                    if (reference == null) originalSingle = single else reportDifference("$name-vs-original-single", 100, reference, single)
                } finally {
                    GLES20.glDeleteProgram(program)
                }
            }
        } finally {
            grader.close()
            source.release()
        }
    }

    private fun reportCpuSamples(name: String, image: HalfFloatImage, texture: FloatArray, width: Int, height: Int) {
        var maximum = 0.0
        var squared = 0.0
        var codeMaximum = 0f
        var changed = 0
        var samples = 0
        val errors = ArrayList<Double>()
        var decodedMaximum = 0
        var decodedSquared = 0.0
        for (y in 17 until 189) for (x in 13 until 203) {
            val px = (x + 0.5 - 12.0) / 192.0 * width - 0.5
            val py = (y + 0.5 - 16.0) / 174.0 * height - 0.5
            val left = kotlin.math.floor(px).toInt()
            val top = kotlin.math.floor(py).toInt()
            val fx = px - left
            val fy = py - top
            for (c in 0..2) {
                fun texel(tx: Int, ty: Int) = texture[(ty.coerceIn(0, height - 1) * width + tx.coerceIn(0, width - 1)) * 4 + c].toDouble()
                val a = texel(left, top) * (1.0 - fx) + texel(left + 1, top) * fx
                val b = texel(left, top + 1) * (1.0 - fx) + texel(left + 1, top + 1) * fx
                val cpu = a * (1.0 - fy) + b * fy
                val rendered = RenderMath.halfToFloat(image.rgba[(y * image.width + x) * 4 + c])
                val error = abs(cpu - rendered)
                maximum = maxOf(maximum, error)
                squared += error * error
                errors += error
                if (error != 0.0) changed++
                codeMaximum = maxOf(codeMaximum, abs(srgb(cpu.toFloat()) - srgb(rendered)) * 255f)
                val decodedDifference = abs((srgb(cpu.toFloat()) * 255f).roundToInt() - (srgb(rendered) * 255f).roundToInt())
                decodedMaximum = maxOf(decodedMaximum, decodedDifference)
                decodedSquared += decodedDifference * decodedDifference
                samples++
            }
        }
        errors.sort()
        report("tile CPU full $name interiorRgb=$samples changed=$changed maxLinear=$maximum rms=${kotlin.math.sqrt(squared / samples)} " +
            "p95=${errors[(errors.lastIndex * 0.95).toInt()]} maxSrgbCodes=$codeMaximum decodedMaxCodes=$decodedMaximum " +
            "decodedRmsCodes=${kotlin.math.sqrt(decodedSquared / samples)}")
        for ((x, y) in listOf(47 to 29, 158 to 47, 84 to 103, 120 to 80, 60 to 59)) {
            // Independent double-precision bilinear reference over the exact graded texture readback.
            val px = (x + 0.5 - 12.0) / 192.0 * width - 0.5
            val py = (y + 0.5 - 16.0) / 174.0 * height - 0.5
            val left = kotlin.math.floor(px).toInt()
            val top = kotlin.math.floor(py).toInt()
            val fx = px - left
            val fy = py - top
            var worst = 0.0
            for (c in 0..2) {
                fun texel(tx: Int, ty: Int) = texture[(ty.coerceIn(0, height - 1) * width + tx.coerceIn(0, width - 1)) * 4 + c].toDouble()
                val a = texel(left, top) * (1.0 - fx) + texel(left + 1, top) * fx
                val b = texel(left, top + 1) * (1.0 - fx) + texel(left + 1, top + 1) * fx
                val cpu = a * (1.0 - fy) + b * fy
                worst = maxOf(worst, abs(cpu - RenderMath.halfToFloat(image.rgba[(y * image.width + x) * 4 + c])))
            }
            report("tile CPU bilinear $name point=($x,$y) texel=($px,$py) maxLinear=$worst")
        }
    }

    private fun compileProgram(vertex: String, fragment: String): Int {
        fun compile(type: Int, source: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            val status = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
            if (status[0] == 0) {
                val error = GLES20.glGetShaderInfoLog(shader)
                GLES20.glDeleteShader(shader)
                error(error)
            }
            return shader
        }
        val vert = compile(GLES20.GL_VERTEX_SHADER, vertex)
        val frag = try { compile(GLES20.GL_FRAGMENT_SHADER, fragment) } catch (failure: Throwable) {
            GLES20.glDeleteShader(vert)
            throw failure
        }
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vert)
        GLES20.glAttachShader(program, frag)
        GLES20.glLinkProgram(program)
        GLES20.glDeleteShader(vert)
        GLES20.glDeleteShader(frag)
        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            val error = GLES20.glGetProgramInfoLog(program)
            GLES20.glDeleteProgram(program)
            error(error)
        }
        return program
    }

    @Test
    fun isolateTileRasterizationFromBackgroundTransformGradeAndCoverage() {
        OffscreenGl.open().use { gl ->
            assumeTrue("needs colour-renderable RGBA16F", gl.capabilities.floatColorBuffer)
            report("tile probe limits: ${gl.capabilities}; 2160x3840 tiles=" +
                RenderMath.planTiles(2160, 3840, gl.capabilities.maxTargetWidth, gl.capabilities.maxTargetHeight).size)
        }
        val renderer = createStoryRenderer(InstrumentationRegistry.getInstrumentation().targetContext) as GlStoryRenderer
        val photo = srgbBitmap(PHOTO_W, PHOTO_H, IntArray(PHOTO_W * PHOTO_H) {
            argb((it % PHOTO_W) * 255 / PHOTO_W, (it / PHOTO_W) * 255 / PHOTO_H, (it * 7) and 0xFF)
        })
        val constant = srgbBitmap(PHOTO_W, PHOTO_H, IntArray(PHOTO_W * PHOTO_H) { argb(80, 120, 160) })
        val smooth = srgbBitmap(PHOTO_W, PHOTO_H, IntArray(PHOTO_W * PHOTO_H) {
            argb((it % PHOTO_W) * 255 / PHOTO_W, (it / PHOTO_W) * 255 / PHOTO_H, 100)
        })
        val hdr = linearF16Bitmap(PHOTO_W, PHOTO_H, FloatArray(PHOTO_W * PHOTO_H * 3) { j ->
            0.02f + 2.5f * ((j / 3) % PHOTO_W) / PHOTO_W * (1 + j % 3) / 3f
        })
        try {
            runBlocking {
                renderer.registerImage("photo", photo)
                renderer.registerImage("constant", constant)
                renderer.registerImage("smooth", smooth)
                renderer.registerImage("hdr", hdr)
            }
            fun render(scene: RenderScene, tile: Int?): HalfFloatImage {
                renderer.offscreenReadbackOverrides = tile?.let { OffscreenReadbackOverrides(maxTileSize = it) }
                return runBlocking { renderer.renderOffscreen(scene) }
            }
            for ((name, scene) in scenes()) {
                val single = render(scene, null)
                assertArrayEquals("$name repeat single must be deterministic", single.rgba, render(scene, null).rgba)
                for (tile in listOf(64, 100, 108, 128, 216)) {
                    val tiled = render(scene, tile)
                    reportDifference(name, tile, single, tiled)
                    if (name == "original" && tile == 100) reportEncodingProxy(single, tiled)
                }
            }
        } finally {
            renderer.close()
            listOf(photo, constant, smooth, hdr).forEach(Bitmap::recycle)
        }
    }

    private fun scenes(): List<Pair<String, RenderScene>> {
        val solid = SceneBackground.Solid(0xFF000000.toInt())
        val frosted = SceneBackground.FrostedImage("photo")
        val plain = SceneLayer("photo", Rect4f(12f, 16f, 204f, 190f), NormRect.FULL)
        val crop = plain.copy(crop = NormRect(0.05f, 0.05f, 0.95f, 0.9f))
        val rotated = crop.copy(rotationDegrees = 2.5f)
        val rounded = rotated.copy(cornerRadiusPx = 10f)
        val graded = rounded.copy(grade = GradeParams(look = MasterLook.PORTRA_400_GOLDEN))
        val hdr = SceneLayer("hdr", Rect4f(12f, 200f, 204f, 372f), NormRect.FULL)
        fun scene(background: SceneBackground = solid, vararg layers: SceneLayer) = RenderScene(WIDTH, HEIGHT, background, layers.toList())
        return listOf(
            "solid" to scene(),
            "frosted-only" to scene(frosted),
            "plain" to scene(solid, plain),
            "crop" to scene(solid, crop),
            "rotated" to scene(solid, rotated),
            "rounded" to scene(solid, rounded),
            "graded" to scene(solid, graded),
            "fractional-rounded" to scene(solid, graded.copy(dst = Rect4f(12.25f, 16.75f, 203.5f, 190.125f))),
            "graded-frosted" to scene(frosted, graded),
            "constant-rotated" to scene(solid, rotated.copy(imageKey = "constant")),
            "smooth-rotated" to scene(solid, rotated.copy(imageKey = "smooth")),
            "hdr-only" to scene(solid, hdr),
            "original" to scene(frosted, graded, hdr),
        )
    }

    private fun reportDifference(name: String, tile: Int, a: HalfFloatImage, b: HalfFloatImage) {
        val plan = RenderMath.planTiles(a.width, a.height, tile, tile)
        val tileWidth = plan.maxOf { it.width }
        val tileHeight = plan.maxOf { it.height }
        val differences = FloatArray(a.rgba.size)
        var worstIndex = 0
        var changed = 0
        var overBudget = 0
        var seamChannels = 0
        var sum = 0.0
        var squares = 0.0
        var maxCodes = 0f
        for (i in a.rgba.indices) {
            val av = RenderMath.halfToFloat(a.rgba[i])
            val bv = RenderMath.halfToFloat(b.rgba[i])
            val d = abs(av - bv)
            differences[i] = d
            if (d > differences[worstIndex]) worstIndex = i
            if (d > 0f) {
                changed++
                val x = i / 4 % a.width
                val y = i / 4 / a.width
                if (x % tileWidth <= 1 || x % tileWidth >= tileWidth - 2 ||
                    y % tileHeight <= 1 || y % tileHeight >= tileHeight - 2
                ) seamChannels++
            }
            if (d > 0.002f) overBudget++
            sum += d
            squares += d * d
            if (i % 4 != 3) maxCodes = maxOf(maxCodes, abs(srgb(av) - srgb(bv)) * 255f)
        }
        val worst = differences[worstIndex]
        differences.sort()
        val x = worstIndex / 4 % a.width
        val y = worstIndex / 4 / a.width
        report("tile probe $name limit=$tile grid=${tileWidth}x$tileHeight/${plan.size} max=$worst changed=$changed/${a.rgba.size} over.002=$overBudget " +
            "mean=${sum / differences.size} rms=${kotlin.math.sqrt(squares / differences.size)} " +
            "p99=${differences[(differences.lastIndex * 0.99).toInt()]} p999=${differences[(differences.lastIndex * 0.999).toInt()]} " +
            "seamChannels=$seamChannels worst=($x,$y,c${worstIndex % 4}) tileLocal=(${x % tileWidth},${y % tileHeight}) " +
            "values=${RenderMath.halfToFloat(a.rgba[worstIndex])},${RenderMath.halfToFloat(b.rgba[worstIndex])} maxSrgbCodes=$maxCodes")
    }

    /** Bounded SDR clamp/sRGB/JPEG proxy, deliberately separate from the real export pipeline. */
    private fun reportEncodingProxy(a: HalfFloatImage, b: HalfFloatImage) {
        fun bitmap(image: HalfFloatImage): Bitmap = srgbBitmap(image.width, image.height, IntArray(image.width * image.height) { p ->
            val rgb = IntArray(3) { c -> (srgb(RenderMath.halfToFloat(image.rgba[p * 4 + c])) * 255f).roundToInt() }
            argb(rgb[0], rgb[1], rgb[2])
        })
        val first = bitmap(a)
        val second = bitmap(b)
        try {
            for (format in listOf(Bitmap.CompressFormat.PNG, Bitmap.CompressFormat.JPEG)) {
                fun encode(source: Bitmap): ByteArray = ByteArrayOutputStream().use { stream ->
                    check(source.compress(format, 95, stream))
                    stream.toByteArray()
                }
                val bytesA = encode(first)
                val bytesB = encode(second)
                val decodedA = checkNotNull(BitmapFactory.decodeByteArray(bytesA, 0, bytesA.size))
                val decodedB = checkNotNull(BitmapFactory.decodeByteArray(bytesB, 0, bytesB.size))
                try {
                    val pixelsA = IntArray(a.width * a.height)
                    val pixelsB = IntArray(pixelsA.size)
                    decodedA.getPixels(pixelsA, 0, a.width, 0, 0, a.width, a.height)
                    decodedB.getPixels(pixelsB, 0, a.width, 0, 0, a.width, a.height)
                    var changed = 0
                    var worst = 0
                    for (i in pixelsA.indices) for (shift in listOf(0, 8, 16)) {
                        val d = abs(((pixelsA[i] ushr shift) and 255) - ((pixelsB[i] ushr shift) and 255))
                        if (d != 0) changed++
                        worst = maxOf(worst, d)
                    }
                    report("tile SDR proxy $format quality95 decodedMaxCodes=$worst changedRgbChannels=$changed/${pixelsA.size * 3} " +
                        "bytes=${bytesA.size},${bytesB.size} equalBytes=${bytesA.contentEquals(bytesB)}")
                } finally {
                    decodedA.recycle()
                    decodedB.recycle()
                }
            }
        } finally {
            first.recycle()
            second.recycle()
        }
    }

    private fun srgb(linear: Float): Float {
        val x = linear.coerceIn(0f, 1f)
        return if (x <= 0.0031308f) 12.92f * x else 1.055f * x.pow(1f / 2.4f) - 0.055f
    }

    private companion object {
        const val PHOTO_W = 96
        const val PHOTO_H = 160
        const val WIDTH = 216
        const val HEIGHT = 384
    }
}
