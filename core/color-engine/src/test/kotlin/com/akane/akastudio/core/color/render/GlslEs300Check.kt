package com.akane.akastudio.core.color.render

/**
 * Source scan for GLSL ES 3.10 additions that GLSL ES 3.00 lacks.
 *
 * glslc only compiles ES shaders at `#version 310 es` or later (it targets SPIR-V), so the glslc tests rewrite
 * the sources' `#version 300 es` line and compile them under the 3.10 grammar. That grammar is a superset: a 3.1-only
 * built-in would compile locally and then fail on a GLES 3.0 driver. This scan closes the gap by rejecting what
 * GLSL ES 3.10 added (GLSL ES 3.10 specification, "Changes from GLSL ES 3.00"): compute, image, atomic, SSBO and
 * multisample-texture identifiers, the new integer and packing built-ins, layout qualifiers other than the 3.00 set,
 * `location` on vertex outputs and fragment inputs, and arrays of arrays of built-in types.
 *
 * It does not see overload-only additions (such as `mix` with an integer or boolean `x`); those still need a device.
 */
internal object GlslEs300Check {

    private val ES31_ONLY_IDENTIFIERS: Set<String> = setOf(
        // Texture gather.
        "textureGather", "textureGatherOffset", "textureGatherOffsets",
        // Images.
        "imageLoad", "imageStore", "imageSize", "imageAtomicAdd", "imageAtomicMin", "imageAtomicMax", "imageAtomicAnd",
        "imageAtomicOr", "imageAtomicXor", "imageAtomicExchange", "imageAtomicCompSwap",
        "image2D", "iimage2D", "uimage2D", "image3D", "iimage3D", "uimage3D", "imageCube", "iimageCube", "uimageCube",
        "image2DArray", "iimage2DArray", "uimage2DArray",
        // Multisample textures.
        "sampler2DMS", "isampler2DMS", "usampler2DMS",
        // Atomics, counters and barriers.
        "atomic_uint", "atomicCounter", "atomicCounterIncrement", "atomicCounterDecrement",
        "atomicAdd", "atomicMin", "atomicMax", "atomicAnd", "atomicOr", "atomicXor", "atomicExchange", "atomicCompSwap",
        "barrier", "memoryBarrier", "memoryBarrierAtomicCounter", "memoryBarrierBuffer", "memoryBarrierImage",
        "memoryBarrierShared", "groupMemoryBarrier",
        // Shader storage buffers and memory qualifiers.
        "buffer", "coherent", "volatile", "restrict", "readonly", "writeonly",
        // Integer, float-decomposition and packing built-ins.
        "bitfieldExtract", "bitfieldInsert", "bitfieldReverse", "bitCount", "findLSB", "findMSB",
        "uaddCarry", "usubBorrow", "umulExtended", "imulExtended", "frexp", "ldexp",
        "packUnorm4x8", "packSnorm4x8", "unpackUnorm4x8", "unpackSnorm4x8",
        // Built-in variables.
        "gl_NumWorkGroups", "gl_WorkGroupSize", "gl_WorkGroupID", "gl_LocalInvocationID", "gl_GlobalInvocationID",
        "gl_LocalInvocationIndex", "gl_HelperInvocation",
    )

    /** `gl_Max*` resource limits that 3.10 introduced for images, atomic counters and compute. */
    private val ES31_ONLY_LIMIT = Regex("^gl_Max\\w*(Image|Atomic|Compute|ShaderOutputResources)\\w*$")

    /** Layout qualifier identifiers GLSL ES 3.00 accepts (section 4.3.8). */
    private val ES30_LAYOUT_IDS = setOf("location", "shared", "packed", "std140", "row_major", "column_major")

    private val IDENTIFIER = Regex("\\b[A-Za-z_]\\w*\\b")
    private val LAYOUT = Regex("\\blayout\\s*\\(([^)]*)\\)")
    private val LOCATION_ON_INTERFACE = Regex(
        "\\blayout\\s*\\([^)]*\\blocation\\b[^)]*\\)\\s*(?:(?:flat|smooth|centroid|highp|mediump|lowp)\\s+)*(in|out)\\b",
    )
    private val ARRAY_OF_ARRAYS = Regex(
        "\\b(?:float|int|uint|bool|[biu]?vec[234]|mat[234](?:x[234])?)\\s*(?:\\[[^\\]]*]\\s*)?\\w*\\s*\\[[^\\]]*]\\s*\\[",
    )

    /** Every GLSL ES 3.10-only use in [source] of shader [stage] (`vert` or `frag`); empty when it is valid 3.00. */
    fun violations(source: String, stage: String): List<String> {
        val code = stripComments(source)
        val found = ArrayList<String>()
        IDENTIFIER.findAll(code).map { it.value }.distinct().forEach { id ->
            if (id in ES31_ONLY_IDENTIFIERS || ES31_ONLY_LIMIT.matches(id)) found += "'$id' is GLSL ES 3.10+"
        }
        LAYOUT.findAll(code).forEach { match ->
            match.groupValues[1].split(',').map { it.substringBefore('=').trim() }.filter { it.isNotEmpty() }.forEach { id ->
                if (id !in ES30_LAYOUT_IDS) found += "layout qualifier '$id' is GLSL ES 3.10+"
            }
        }
        LOCATION_ON_INTERFACE.findAll(code).forEach { match ->
            val direction = match.groupValues[1]
            if ((stage == "vert" && direction == "out") || (stage == "frag" && direction == "in")) {
                found += "layout(location) on a $stage '$direction' variable is GLSL ES 3.10+"
            }
        }
        ARRAY_OF_ARRAYS.findAll(code).forEach { found += "array of arrays '${it.value.trim()}' is GLSL ES 3.10+" }
        return found
    }

    private fun stripComments(source: String): String =
        source.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), " ").replace(Regex("//[^\n]*"), " ")
}
