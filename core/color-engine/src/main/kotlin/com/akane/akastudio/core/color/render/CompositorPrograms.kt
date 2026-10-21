package com.akane.akastudio.core.color.render

import android.content.res.AssetManager
import com.akane.akastudio.core.color.gl.ShaderProgram

/** The compositor's linked programs (one per [ShaderCatalog] entry). GL thread only. */
internal class CompositorPrograms private constructor(private val programs: Map<ProgramSpec, ShaderProgram>) {
    val layer: ShaderProgram get() = of(ShaderCatalog.LAYER)
    val copy: ShaderProgram get() = of(ShaderCatalog.COPY)
    val blur: ShaderProgram get() = of(ShaderCatalog.BLUR)
    val overlay: ShaderProgram get() = of(ShaderCatalog.OVERLAY)
    val present: ShaderProgram get() = of(ShaderCatalog.PRESENT)
    val solid: ShaderProgram get() = of(ShaderCatalog.SOLID)

    fun release() {
        programs.values.forEach { it.release() }
    }

    private fun of(spec: ProgramSpec): ShaderProgram =
        programs[spec] ?: throw IllegalStateException("program ${spec.fragment} was not loaded")

    companion object {
        /** Compiles and links every catalog program; on failure frees the ones already linked and rethrows. */
        fun load(assets: AssetManager): CompositorPrograms {
            val loaded = LinkedHashMap<ProgramSpec, ShaderProgram>()
            try {
                ShaderCatalog.ALL.forEach { spec -> loaded[spec] = ShaderProgram.load(assets, spec.vertex, spec.fragment) }
            } catch (e: RuntimeException) {
                loaded.values.forEach { it.release() }
                throw e
            }
            return CompositorPrograms(loaded)
        }
    }
}
