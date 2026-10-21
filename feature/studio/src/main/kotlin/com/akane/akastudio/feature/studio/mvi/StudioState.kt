package com.akane.akastudio.feature.studio.mvi

import androidx.compose.runtime.Immutable
import com.akane.akastudio.core.color.api.ImagePixels
import com.akane.akastudio.core.color.api.MasterLook
import com.akane.akastudio.core.color.api.RenderScene
import com.akane.akastudio.core.composer.api.CellOverride
import com.akane.akastudio.core.composer.api.LayoutStyle
import com.akane.akastudio.core.composer.api.StoryTemplate
import com.akane.akastudio.core.curator.api.CurationResult
import com.akane.akastudio.core.curator.api.StoryMode
import com.akane.akastudio.core.export.api.ExportFormat
import com.akane.akastudio.core.export.api.ExportResolution
import com.akane.akastudio.core.export.api.ExportResult
import com.akane.akastudio.core.export.api.ExportStage
import com.akane.akastudio.core.ingestion.model.PhotoCandidate
import com.akane.akastudio.feature.studio.domain.StudioError
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf

/**
 * The whole studio screen state. Immutable; every change produces a new instance through `StudioViewModel`.
 *
 * @property curation The latest curation result (after any forced mode), or null before curation decides.
 * @property template The active template; its photo count always equals `selection.mode.photoCount`.
 * @property overrides Per-photo crop/zoom/pan overrides by photo id.
 * @property scene The preview scene at [com.akane.akastudio.core.composer.api.CanvasSize.STORY_1080], or null.
 * @property sceneRevision Incremented on every scene rebuild; the scope pipeline and preview key off it.
 * @property compare Before/after split position in [0, 1], or null when comparison is off.
 * @property scopePixels The latest 270x480 readback for [scope], or null when the scope is off or not yet computed.
 * @property modeDrawerOpen Whether the story-mode drawer is expanded.
 */
@Immutable
data class StudioState(
    val phase: StudioPhase = StudioPhase.Empty,
    val telemetry: TelemetryUi = TelemetryUi(),
    val curation: CurationResult? = null,
    val selection: SelectionUi = SelectionUi(),
    val template: StoryTemplate? = null,
    val layout: LayoutStyle = LayoutStyle(),
    val overrides: ImmutableMap<String, CellOverride> = persistentMapOf(),
    val grade: GradeUi = GradeUi(),
    val scene: RenderScene? = null,
    val sceneRevision: Long = 0L,
    val compare: Float? = null,
    val scope: ScopeMode = ScopeMode.OFF,
    val scopePixels: ImagePixels? = null,
    val tab: ControlTab = ControlTab.CURATION,
    val modeDrawerOpen: Boolean = false,
    val export: ExportUi = ExportUi(),
    val error: StudioError? = null,
)

/** Where the studio is in its pipeline. Only [Empty] and [Ready] are stable; the others are cancellable work. */
sealed interface StudioPhase {
    /** No photos; waiting for an import. */
    data object Empty : StudioPhase

    /** Importing sources; more imports accumulate into the same session until curation starts. */
    data object Ingesting : StudioPhase

    /** The curator is analyzing, clustering and deciding. */
    data object Curating : StudioPhase

    /** Full-resolution photos for the selection are being prepared. */
    data object Preparing : StudioPhase

    /** A story is on screen and editable. */
    data object Ready : StudioPhase

    /** An export is running; the story stays editable but mode and photo changes wait. */
    data object Exporting : StudioPhase
}

/** The pipeline step shown in the telemetry readout. */
enum class TelemetryStep { IDLE, READING, ANALYZING, DECIDING, PREPARING, DONE }

/**
 * Live pipeline telemetry, accumulated across every import of the session.
 *
 * @property sourcesTotal Picker items plus archive image entries discovered.
 * @property photosRead Sources whose metadata parse finished (accepted or skipped).
 * @property accepted Distinct candidates accepted by ingestion.
 * @property skipped Sources skipped by ingestion (junk, unsupported, duplicate, unreadable...).
 * @property analyzed Candidates the curator has analyzed so far, out of [analyzeTotal].
 * @property clusters Scene clusters found by the curator.
 * @property rejected Candidates eliminated at the curator's technical gate.
 * @property finalMode The mode of the current selection, once decided.
 * @property harmonyPercent The curator's harmony score (0..100) for the decided selection.
 */
@Immutable
data class TelemetryUi(
    val sourcesTotal: Int = 0,
    val photosRead: Int = 0,
    val accepted: Int = 0,
    val skipped: Int = 0,
    val analyzed: Int = 0,
    val analyzeTotal: Int = 0,
    val clusters: Int = 0,
    val rejected: Int = 0,
    val step: TelemetryStep = TelemetryStep.IDLE,
    val finalMode: StoryMode? = null,
    val harmonyPercent: Int? = null,
)

/**
 * The photos in the story.
 *
 * @property mode The story mode, or null before curation decides.
 * @property photoIds Candidate ids in slot order (slot 0 is the hero/chromatic anchor).
 * @property slots Slot index to its candidate.
 * @property alternatives Slot index to swap options: other usable members of that photo's scene cluster (bursts
 *   and near-duplicates), best quality first, excluding photos already in the story.
 */
@Immutable
data class SelectionUi(
    val mode: StoryMode? = null,
    val photoIds: ImmutableList<String> = persistentListOf(),
    val slots: ImmutableMap<Int, PhotoCandidate> = persistentMapOf(),
    val alternatives: ImmutableMap<Int, ImmutableList<String>> = persistentMapOf(),
)

/**
 * The global grade applied to every photo. Ranges match [GradeField]; harmonization is planned per photo on top.
 *
 * @property intensity Look intensity, 0..1.
 * @property harmonizationStrength How strongly collage photos are pulled toward the anchor's color, 0..1.
 */
@Immutable
data class GradeUi(
    val look: MasterLook = MasterLook.PURE_NATURAL_PRO,
    val intensity: Float = 1f,
    val harmonizationStrength: Float = 0.8f,
    val temperature: Float = 0f,
    val tint: Float = 0f,
    val contrast: Float = 0f,
    val saturation: Float = 0f,
    val halation: Float = 0f,
    val grain: Float = 0f,
    val skinProtection: Float = 0.6f,
) {
    /** The current value of [field]. */
    fun value(field: GradeField): Float = when (field) {
        GradeField.INTENSITY -> intensity
        GradeField.HARMONIZATION_STRENGTH -> harmonizationStrength
        GradeField.TEMPERATURE -> temperature
        GradeField.TINT -> tint
        GradeField.CONTRAST -> contrast
        GradeField.SATURATION -> saturation
        GradeField.HALATION -> halation
        GradeField.GRAIN -> grain
        GradeField.SKIN_PROTECTION -> skinProtection
    }

    /** A copy with [field] set to [value] clamped to the field's range. */
    fun with(field: GradeField, value: Float): GradeUi {
        val v = value.coerceIn(field.min, field.max)
        return when (field) {
            GradeField.INTENSITY -> copy(intensity = v)
            GradeField.HARMONIZATION_STRENGTH -> copy(harmonizationStrength = v)
            GradeField.TEMPERATURE -> copy(temperature = v)
            GradeField.TINT -> copy(tint = v)
            GradeField.CONTRAST -> copy(contrast = v)
            GradeField.SATURATION -> copy(saturation = v)
            GradeField.HALATION -> copy(halation = v)
            GradeField.GRAIN -> copy(grain = v)
            GradeField.SKIN_PROTECTION -> copy(skinProtection = v)
        }
    }
}

/** An adjustable grade slider and its valid range (matching `GradeParams`). */
enum class GradeField(val min: Float, val max: Float) {
    INTENSITY(0f, 1f),
    HARMONIZATION_STRENGTH(0f, 1f),
    TEMPERATURE(-1f, 1f),
    TINT(-1f, 1f),
    CONTRAST(-1f, 1f),
    SATURATION(-1f, 1f),
    HALATION(0f, 1f),
    GRAIN(0f, 1f),
    SKIN_PROTECTION(0f, 1f),
}

/** Which video scope is shown over the preview. */
enum class ScopeMode { OFF, LUMA_WAVEFORM, RGB_PARADE, HISTOGRAM }

/** The bottom control panel's tabs. */
enum class ControlTab { CURATION, LAYOUT, COLOR, EXPORT }

/**
 * Export settings and status.
 *
 * @property hdrBoost Whether to embed an Ultra HDR gain map. Defaults to true when any chosen photo carries a
 *   gain map, until the user sets it explicitly ([hdrBoostUserSet]).
 * @property progress The running export's stage, or null when no export runs.
 * @property lastResult The last finished export in this session.
 * @property sharing Whether the running export was started with "Export & share".
 */
@Immutable
data class ExportUi(
    val format: ExportFormat = ExportFormat.SOCIAL_STORY_JPEG,
    val resolution: ExportResolution = ExportResolution.HD_1080,
    val hdrBoost: Boolean = false,
    val hdrBoostUserSet: Boolean = false,
    val progress: ExportProgressUi? = null,
    val lastResult: ExportResult? = null,
    val sharing: Boolean = false,
)

/** A running export's current [stage] and its completion [fraction] in [0, 1]. */
@Immutable
data class ExportProgressUi(val stage: ExportStage, val fraction: Float)
