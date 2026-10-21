package com.akane.akastudio.core.color.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * Checks every shader under `src/main/assets/shaders` without a device:
 * - the Kotlin [ShaderCatalog] and the GLSL agree (files, uniform names, vertex outputs feeding fragment inputs);
 * - each source compiles with the NDK's glslc (skipped, with a log line, when no NDK glslc is installed).
 */
class ShaderSourcesTest {

    private val shaderDir: File = listOf(
        File("src/main/assets/shaders"),
        File("core/color-engine/src/main/assets/shaders"),
    ).firstOrNull { it.isDirectory } ?: throw AssertionError("shader directory not found from ${File("").absolutePath}")

    private val shaders: List<File> = shaderDir.listFiles { file -> file.name.endsWith(".glsl") }.orEmpty().sortedBy { it.name }

    @Test
    fun shaderFilesFollowTheNamingScheme() {
        assertTrue("no shaders found in $shaderDir", shaders.isNotEmpty())
        for (file in shaders) {
            assertTrue("${file.name} must end in .vert.glsl or .frag.glsl", stageOf(file) != null)
            assertEquals("${file.name} must start with #version 300 es", "#version 300 es", file.readLines().first().trim())
            assertTrue("${file.name} must not use #include", file.readLines().none { it.trimStart().startsWith("#include") })
        }
    }

    @Test
    fun catalogMatchesShaderSources() {
        val byName = shaders.associateBy { it.name }
        val referenced = HashSet<String>()
        for (spec in ShaderCatalog.ALL) {
            val vertex = byName[spec.vertex] ?: throw AssertionError("missing ${spec.vertex}")
            val fragment = byName[spec.fragment] ?: throw AssertionError("missing ${spec.fragment}")
            referenced += spec.vertex
            referenced += spec.fragment
            val vertexText = vertex.readText()
            val fragmentText = fragment.readText()
            val declared = uniformNames(vertexText) + uniformNames(fragmentText)
            for (uniform in spec.uniforms) {
                assertTrue("${spec.fragment}: uniform $uniform not declared (declared: $declared)", uniform in declared)
            }
            val outputs = interfaceVariables(vertexText, "out")
            for ((name, type) in interfaceVariables(fragmentText, "in")) {
                assertEquals("${spec.fragment}: input $name must be a ${spec.vertex} output of the same type", type, outputs[name])
            }
        }
        assertEquals("every shader belongs to a program", shaders.map { it.name }.toSet(), referenced)
    }

    @Test
    fun everyShaderUsesOnlyGlslEs300Features() {
        assertTrue("no shaders found in $shaderDir", shaders.isNotEmpty())
        val failures = shaders.mapNotNull { file ->
            val stage = stageOf(file) ?: return@mapNotNull "${file.name}: unknown stage"
            GlslEs300Check.violations(file.readText(), stage).takeIf { it.isNotEmpty() }?.let { "${file.name}: $it" }
        }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
    }

    @Test
    fun everyShaderCompilesWithGlslc() {
        val glslc = findGlslc()
        if (glslc == null) {
            println("ShaderSourcesTest: SKIPPED glslc compile check - no \$ANDROID_HOME/ndk/*/shader-tools/linux-x86_64/glslc found")
        }
        assumeTrue("glslc not installed (needs an NDK under \$ANDROID_HOME/ndk)", glslc != null)
        val compiler = glslc ?: return
        println("ShaderSourcesTest: compiling ${shaders.size} shaders with $compiler")
        val workDir = Files.createTempDirectory("glslc").toFile()
        try {
            val failures = shaders.mapNotNull { file -> compile(compiler, file, workDir) }
            if (failures.isNotEmpty()) fail(failures.joinToString("\n\n"))
        } finally {
            workDir.deleteRecursively()
        }
    }

    /**
     * glslc only targets SPIR-V, and its ES front end refuses `#version 300 es` ("ES shaders for SPIR-V
     * require version 310 or higher"), and no glslc option relaxes that. Rewriting the version line to `310 es`
     * compiles the same code under a superset grammar; the device driver still sees 300 es.
     * [everyShaderUsesOnlyGlslEs300Features] rejects the 3.10-only constructs this superset would let through.
     */
    private fun compile(glslc: File, file: File, workDir: File): String? {
        val stage = stageOf(file) ?: return "${file.name}: unknown stage"
        val lines = file.readLines().toMutableList()
        if (lines.first().trim() == "#version 300 es") lines[0] = "#version 310 es"
        val prepared = File(workDir, file.name).apply { writeText(lines.joinToString("\n", postfix = "\n")) }
        val process = ProcessBuilder(
            glslc.absolutePath,
            "--target-env=opengl",
            "-fauto-map-locations",
            "-fauto-bind-uniforms",
            "-fshader-stage=$stage",
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

    private fun stageOf(file: File): String? = when {
        file.name.endsWith(".vert.glsl") -> "vert"
        file.name.endsWith(".frag.glsl") -> "frag"
        else -> null
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

    private fun stripComments(source: String): String =
        source.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), " ").replace(Regex("//[^\n]*"), " ")

    /** Names of `uniform` declarations (last identifier before `;`, ignoring array suffixes). */
    private fun uniformNames(source: String): Set<String> =
        Regex("\\buniform\\s+([^;{]+);").findAll(stripComments(source)).map { match ->
            match.groupValues[1].replace(Regex("\\[[^]]*]"), "").trim().split(Regex("\\s+")).last()
        }.toSet()

    /** Top-level `in`/`out` variables (excluding vertex attributes with a layout qualifier) as name -> type. */
    private fun interfaceVariables(source: String, qualifier: String): Map<String, String> =
        Regex("^\\s*$qualifier\\s+(?:(?:highp|mediump|lowp|flat|smooth)\\s+)*(\\w+)\\s+(\\w+)\\s*;", RegexOption.MULTILINE)
            .findAll(stripComments(source))
            .associate { it.groupValues[2] to it.groupValues[1] }
}
