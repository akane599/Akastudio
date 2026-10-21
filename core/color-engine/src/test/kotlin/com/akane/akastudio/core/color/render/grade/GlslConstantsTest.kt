package com.akane.akastudio.core.color.render.grade

import com.akane.akastudio.core.color.analysis.SkinMask
import com.akane.akastudio.core.color.api.OkLab
import com.akane.akastudio.core.color.grade.FilmGrain
import com.akane.akastudio.core.color.grade.Halation
import com.akane.akastudio.core.color.grade.Mat3
import com.akane.akastudio.core.color.math.ColorMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Random
import java.util.concurrent.TimeUnit
import kotlin.math.cbrt

/**
 * The generated GLSL constants against their Kotlin sources, and every `grade/grade_*.glsl` compiled with
 * glslc after the prelude is inserted (skipped, with a log line, when no NDK glslc is installed).
 */
class GlslConstantsTest {

    private val gradeDir: File = listOf(
        File("src/main/assets/shaders/grade"),
        File("core/color-engine/src/main/assets/shaders/grade"),
    ).firstOrNull { it.isDirectory } ?: throw AssertionError("grade shader directory not found from ${File("").absolutePath}")

    private val gradeShaders: List<File> =
        gradeDir.listFiles { file -> file.name.startsWith("grade_") && file.name.endsWith(".glsl") }.orEmpty().sortedBy { it.name }

    @Test
    fun emittedMatricesEqualKotlinMatricesBitForBit() {
        val prelude = GlslConstants.prelude
        val expected = mapOf(
            "OKLAB_M1" to GlslConstants.OKLAB_M1,
            "OKLAB_M2" to GlslConstants.OKLAB_M2,
            "OKLAB_M2_INV" to GlslConstants.OKLAB_M2_INV,
            "OKLAB_M1_INV" to GlslConstants.OKLAB_M1_INV,
            "BRADFORD" to Mat3.BRADFORD,
            "BRADFORD_INV" to Mat3.BRADFORD_INV,
            "SRGB_TO_XYZ" to Mat3.SRGB_TO_XYZ,
            "XYZ_TO_SRGB" to Mat3.XYZ_TO_SRGB,
        )
        for ((name, rowMajor) in expected) {
            val literals = declaration(prelude, "mat3", name)
            assertEquals("$name element count", 9, literals.size)
            for (column in 0 until 3) for (row in 0 until 3) {
                val emitted = literals[column * 3 + row].toFloat()
                val kotlin = rowMajor[row * 3 + column].toFloat()
                assertEquals("$name[$row][$column] bits", kotlin.toRawBits(), emitted.toRawBits())
            }
        }
    }

    @Test
    fun emittedVectorsAndScalarsEqualKotlinConstantsBitForBit() {
        val prelude = GlslConstants.prelude
        val vectors = mapOf(
            "LUMA_709" to floatArrayOf(ColorMath.relativeLuminance(1f, 0f, 0f), ColorMath.relativeLuminance(0f, 1f, 0f), ColorMath.relativeLuminance(0f, 0f, 1f)),
            "HALATION_TINT" to Halation.TINT,
            "HALATION_WEIGHTS" to Halation.WEIGHTS,
        )
        for ((name, values) in vectors) {
            val literals = declaration(prelude, "vec3", name)
            assertEquals("$name component count", 3, literals.size)
            for (i in 0 until 3) assertEquals("$name[$i] bits", values[i].toRawBits(), literals[i].toFloat().toRawBits())
        }
        val hueLo = privateFloat(SkinMask::class.java, "HUE_LO_DEG")
        val hueHi = privateFloat(SkinMask::class.java, "HUE_HI_DEG")
        val scalars = mapOf(
            "SKIN_L_LO" to privateFloat(SkinMask::class.java, "L_LO"),
            "SKIN_L_HI" to privateFloat(SkinMask::class.java, "L_HI"),
            "SKIN_L_FEATHER" to privateFloat(SkinMask::class.java, "L_FEATHER"),
            "SKIN_C_LO" to privateFloat(SkinMask::class.java, "C_LO"),
            "SKIN_C_HI" to privateFloat(SkinMask::class.java, "C_HI"),
            "SKIN_C_FEATHER" to privateFloat(SkinMask::class.java, "C_FEATHER"),
            "SKIN_HUE_FEATHER_DEG" to privateFloat(SkinMask::class.java, "HUE_FEATHER_DEG"),
            "SKIN_HUE_CENTRE_DEG" to (hueLo + hueHi) / 2f,
            "SKIN_HUE_HALF_WIDTH_DEG" to (hueHi - hueLo) / 2f,
            "HALATION_THRESHOLD" to Halation.THRESHOLD,
            "HALATION_KNEE" to Halation.KNEE,
            "GRAIN_UNIT_VARIANCE" to privateFloat(FilmGrain::class.java, "UNIT_VARIANCE"),
        )
        for ((name, value) in scalars) {
            val literals = Regex("const float $name = ([^;]+);").find(prelude)?.groupValues?.get(1)
                ?: throw AssertionError("$name not emitted")
            assertEquals("$name bits", value.toRawBits(), literals.trim().toFloat().toRawBits())
        }
    }

    /** The OKLab literals restated in GlslConstants reproduce ColorMath's private ones exactly. */
    @Test
    fun okLabMatricesReproduceColorMathExactly() {
        val random = Random(7)
        repeat(2000) {
            val r = random.nextFloat() * 1.4f - 0.2f
            val g = random.nextFloat() * 1.4f - 0.2f
            val b = random.nextFloat() * 1.4f - 0.2f
            val expected = ColorMath.linearSrgbToOkLab(r, g, b)
            val m1 = GlslConstants.OKLAB_M1
            val m2 = GlslConstants.OKLAB_M2
            val l = cbrt(m1[0] * r + m1[1] * g + m1[2] * b)
            val m = cbrt(m1[3] * r + m1[4] * g + m1[5] * b)
            val s = cbrt(m1[6] * r + m1[7] * g + m1[8] * b)
            assertEquals(expected.l.toRawBits(), (m2[0] * l + m2[1] * m + m2[2] * s).toFloat().toRawBits())
            assertEquals(expected.a.toRawBits(), (m2[3] * l + m2[4] * m + m2[5] * s).toFloat().toRawBits())
            assertEquals(expected.b.toRawBits(), (m2[6] * l + m2[7] * m + m2[8] * s).toFloat().toRawBits())

            val lab = OkLab(expected.l, expected.a, expected.b)
            val back = ColorMath.okLabToLinearSrgb(lab)
            val inv2 = GlslConstants.OKLAB_M2_INV
            val inv1 = GlslConstants.OKLAB_M1_INV
            val lp = lab.l + inv2[1] * lab.a + inv2[2] * lab.b
            val mp = lab.l + inv2[4] * lab.a + inv2[5] * lab.b
            val sp = lab.l + inv2[7] * lab.a + inv2[8] * lab.b
            val l3 = lp * lp * lp
            val m3 = mp * mp * mp
            val s3 = sp * sp * sp
            assertEquals(back[0].toRawBits(), (inv1[0] * l3 + inv1[1] * m3 + inv1[2] * s3).toFloat().toRawBits())
            assertEquals(back[1].toRawBits(), (inv1[3] * l3 + inv1[4] * m3 + inv1[5] * s3).toFloat().toRawBits())
            assertEquals(back[2].toRawBits(), (inv1[6] * l3 + inv1[7] * m3 + inv1[8] * s3).toFloat().toRawBits())
        }
        GlslConstants.OKLAB_M2_INV.filterIndexed { i, _ -> i % 3 == 0 }.forEach { assertEquals(1.0, it, 0.0) }
    }

    @Test
    fun catalogMatchesGradeShaderSources() {
        val byName = gradeShaders.associateBy { GradeShaderCatalog.DIR + it.name }
        assertEquals(
            "every grade shader belongs to a grading program",
            byName.keys,
            GradeShaderCatalog.GRADING.map { it.fragment }.toSet(),
        )
        val vertex = File(gradeDir.parentFile, "quad.vert.glsl").readText()
        for (spec in GradeShaderCatalog.GRADING) {
            val fragment = byName.getValue(spec.fragment).readText()
            assertEquals("${spec.fragment} must start with #version 300 es", "#version 300 es", fragment.lineSequence().first().trim())
            val declared = uniformNames(vertex) + uniformNames(fragment)
            for (uniform in spec.uniforms) {
                assertTrue("${spec.fragment}: uniform $uniform not declared (declared: $declared)", uniform in declared)
            }
        }
    }

    @Test
    fun everyGradeShaderCompilesWithGlslcAfterThePrelude() {
        assertTrue("no grade shaders found in $gradeDir", gradeShaders.isNotEmpty())
        val glslc = findGlslc()
        if (glslc == null) {
            println("GlslConstantsTest: SKIPPED glslc compile check - no \$ANDROID_HOME/ndk/*/shader-tools/linux-x86_64/glslc found")
        }
        assumeTrue("glslc not installed (needs an NDK under \$ANDROID_HOME/ndk)", glslc != null)
        val compiler = glslc ?: return
        println("GlslConstantsTest: compiling ${gradeShaders.size} grade shaders with $compiler")
        val workDir = Files.createTempDirectory("glslc-grade").toFile()
        try {
            val failures = gradeShaders.mapNotNull { file -> compile(compiler, file, workDir) }
            if (failures.isNotEmpty()) fail(failures.joinToString("\n\n"))
        } finally {
            workDir.deleteRecursively()
        }
    }

    /** Literal list of `const <type> <name> = <type>(...);` in [prelude]. */
    private fun declaration(prelude: String, type: String, name: String): List<String> {
        val match = Regex("const $type $name = $type\\(([^)]*)\\);").find(prelude) ?: throw AssertionError("$name not emitted")
        return match.groupValues[1].split(',').map { it.trim() }
    }

    private fun privateFloat(owner: Class<*>, field: String): Float =
        owner.getDeclaredField(field).apply { isAccessible = true }.getFloat(null)

    /**
     * Same approach as ShaderSourcesTest: glslc's ES front end needs `310 es`, a superset of the 3.00 grammar the
     * sources use. The prelude goes after the version line exactly as ShaderProgram inserts it on the device.
     */
    private fun compile(glslc: File, file: File, workDir: File): String? {
        val lines = file.readLines().toMutableList()
        if (lines.first().trim() == "#version 300 es") lines[0] = "#version 310 es"
        val source = lines.first() + "\n" + GlslConstants.prelude.trimEnd('\n') + "\n" + lines.drop(1).joinToString("\n", postfix = "\n")
        val prepared = File(workDir, file.name).apply { writeText(source) }
        val process = ProcessBuilder(
            glslc.absolutePath,
            "--target-env=opengl",
            "-fauto-map-locations",
            "-fauto-bind-uniforms",
            "-fshader-stage=frag",
            "-o", "/dev/null",
            prepared.absolutePath,
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return "${file.name}: glslc timed out"
        }
        return if (process.exitValue() == 0) null else "${file.name}: glslc exit ${process.exitValue()}\n$output"
    }

    /** Newest NDK first, by numeric version components of the directory name. */
    private fun findGlslc(): File? {
        val sdk = (System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT"))?.let(::File) ?: return null
        val ndks = File(sdk, "ndk").listFiles { file -> file.isDirectory }.orEmpty()
        return ndks
            .sortedWith(compareByDescending<File> { versionKey(it.name) }.thenByDescending { it.name })
            .map { File(it, "shader-tools/linux-x86_64/glslc") }
            .firstOrNull { it.canExecute() }
    }

    private fun versionKey(name: String): String =
        name.split('.').joinToString(".") { part -> part.toIntOrNull()?.let { "%010d".format(it) } ?: part }

    private fun uniformNames(source: String): Set<String> {
        val stripped = source.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), " ").replace(Regex("//[^\n]*"), " ")
        return Regex("\\buniform\\s+([^;{]+);").findAll(stripped).map { match ->
            match.groupValues[1].replace(Regex("\\[[^]]*]"), "").trim().split(Regex("\\s+")).last()
        }.toSet()
    }
}
