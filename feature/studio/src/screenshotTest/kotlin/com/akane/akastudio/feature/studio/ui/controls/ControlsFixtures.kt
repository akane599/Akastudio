package com.akane.akastudio.feature.studio.ui.controls

import com.akane.akastudio.core.color.api.LabStats
import com.akane.akastudio.core.color.api.MasterLook
import com.akane.akastudio.core.color.api.PaletteSwatch
import com.akane.akastudio.core.color.api.SceneType
import com.akane.akastudio.core.color.math.ColorMath
import com.akane.akastudio.core.composer.api.LayoutStyle
import com.akane.akastudio.core.composer.api.StoryTemplate
import com.akane.akastudio.core.curator.api.CurationOption
import com.akane.akastudio.core.curator.api.CurationResult
import com.akane.akastudio.core.curator.api.CuratorNote
import com.akane.akastudio.core.curator.api.PhotoAnalysis
import com.akane.akastudio.core.curator.api.RejectReason
import com.akane.akastudio.core.curator.api.SaliencyMap
import com.akane.akastudio.core.curator.api.SceneCluster
import com.akane.akastudio.core.curator.api.ShotScale
import com.akane.akastudio.core.curator.api.StoryMode
import com.akane.akastudio.core.curator.api.SynergyBreakdown
import com.akane.akastudio.core.curator.api.TechnicalScore
import com.akane.akastudio.core.curator.api.Warmth
import com.akane.akastudio.core.export.api.ExportFormat
import com.akane.akastudio.core.export.api.ExportResolution
import com.akane.akastudio.core.export.api.ExportResult
import com.akane.akastudio.core.export.api.ExportStage
import com.akane.akastudio.feature.studio.mvi.ControlTab
import com.akane.akastudio.feature.studio.mvi.ExportProgressUi
import com.akane.akastudio.feature.studio.mvi.ExportUi
import com.akane.akastudio.feature.studio.mvi.GradeUi
import com.akane.akastudio.feature.studio.mvi.SelectionUi
import com.akane.akastudio.feature.studio.mvi.StudioPhase
import com.akane.akastudio.feature.studio.mvi.StudioState
import kotlinx.collections.immutable.persistentListOf

/** Fake curation data for the controls screenshots: a golden-hour beach trip, a 3-photo collage pick. */
internal object ControlsFixtures {

    private fun swatch(l: Float, c: Float, h: Float, w: Float) = PaletteSwatch(ColorMath.okLchToOkLab(l, c, h), w)

    private fun analysis(
        id: String,
        quality: Float,
        scale: ShotScale,
        palette: List<PaletteSwatch>,
        rejected: RejectReason? = null,
    ) = PhotoAnalysis(
        candidateId = id,
        technical = TechnicalScore(sharpness = quality, exposure = quality, faceQuality = 1f, total = quality),
        aesthetic = quality,
        composition = quality,
        quality = quality,
        embedding = floatArrayOf(1f, 0f),
        dHash = id.hashCode().toLong(),
        pHash = id.hashCode().toLong(),
        palette = palette,
        labStats = LabStats(0.6f, 0.01f, 0.03f, 0.2f, 0.04f, 0.05f, 100f),
        cctKelvin = 4300f,
        shotScale = scale,
        scene = SceneType.BEACH_SEA,
        faces = emptyList(),
        saliency = SaliencyMap(1, 1, floatArrayOf(1f)),
        rejectReason = rejected,
    )

    private val goldenBeach = listOf(
        swatch(0.78f, 0.08f, 75f, 0.35f), swatch(0.62f, 0.10f, 55f, 0.25f), swatch(0.48f, 0.06f, 230f, 0.2f),
        swatch(0.9f, 0.03f, 85f, 0.12f), swatch(0.25f, 0.02f, 40f, 0.08f),
    )
    private val warmPortrait = listOf(
        swatch(0.66f, 0.07f, 50f, 0.3f), swatch(0.4f, 0.04f, 35f, 0.25f), swatch(0.82f, 0.05f, 70f, 0.2f),
        swatch(0.3f, 0.03f, 250f, 0.15f), swatch(0.92f, 0.01f, 80f, 0.1f),
    )
    private val cafeDetail = listOf(
        swatch(0.48f, 0.06f, 60f, 0.35f), swatch(0.7f, 0.05f, 80f, 0.2f), swatch(0.3f, 0.03f, 45f, 0.2f),
        swatch(0.85f, 0.02f, 90f, 0.15f), swatch(0.55f, 0.08f, 30f, 0.1f),
    )
    private val seaBlue = listOf(
        swatch(0.55f, 0.09f, 235f, 0.4f), swatch(0.75f, 0.05f, 220f, 0.25f), swatch(0.85f, 0.04f, 80f, 0.15f),
        swatch(0.35f, 0.05f, 245f, 0.12f), swatch(0.95f, 0.01f, 90f, 0.08f),
    )

    private val analyses: List<PhotoAnalysis> = listOf(
        analysis("p1", 0.91f, ShotScale.WIDE, goldenBeach),
        analysis("p2", 0.86f, ShotScale.MEDIUM, warmPortrait),
        analysis("p3", 0.82f, ShotScale.DETAIL, cafeDetail),
        analysis("p4", 0.84f, ShotScale.WIDE, goldenBeach.map { it.copy(lab = it.lab.copy(l = it.lab.l - 0.06f)) }),
        analysis("p5", 0.79f, ShotScale.WIDE, goldenBeach.reversed()),
        analysis("p6", 0.8f, ShotScale.MEDIUM, warmPortrait.map { it.copy(lab = it.lab.copy(l = it.lab.l + 0.05f)) }),
        analysis("p7", 0.74f, ShotScale.WIDE, seaBlue),
        analysis("r1", 0.31f, ShotScale.MEDIUM, warmPortrait.reversed(), RejectReason.BLURRY),
        analysis("r2", 0.42f, ShotScale.MEDIUM, warmPortrait, RejectReason.EYES_CLOSED),
        analysis("r3", 0.28f, ShotScale.WIDE, goldenBeach.map { it.copy(lab = it.lab.copy(l = 0.95f)) }, RejectReason.BAD_EXPOSURE),
        analysis("r4", 0f, ShotScale.WIDE, emptyList(), RejectReason.UNREADABLE),
    )

    private val clusters = listOf(
        SceneCluster(0, listOf("p1", "p4", "p5"), championId = "p1"),
        SceneCluster(1, listOf("p2", "p6"), championId = "p2"),
        SceneCluster(2, listOf("p3"), championId = "p3"),
        SceneCluster(3, listOf("p7"), championId = "p7"),
    )

    private val options = mapOf(
        StoryMode.HERO to CurationOption(StoryMode.HERO, listOf("p1"), 0.91f, null),
        StoryMode.COLLAGE_2 to CurationOption(
            StoryMode.COLLAGE_2, listOf("p1", "p2"), 0.84f, SynergyBreakdown(0.88f, 0.72f, 0.8f, 0.8f),
        ),
        StoryMode.COLLAGE_3 to CurationOption(
            StoryMode.COLLAGE_3, listOf("p1", "p2", "p3"), 0.87f, SynergyBreakdown(0.91f, 0.84f, 0.86f, 0.87f),
        ),
        StoryMode.COLLAGE_4 to CurationOption(
            StoryMode.COLLAGE_4, listOf("p1", "p2", "p3", "p7"), 0.78f, SynergyBreakdown(0.74f, 0.81f, 0.69f, 0.75f),
        ),
    )

    /** The curator's result: 11 photos, 4 scenes, 4 rejected, a 3-photo collage at 87% harmony. */
    val curation = CurationResult(
        chosen = options.getValue(StoryMode.COLLAGE_3),
        alternatives = options,
        analyses = analyses.associateBy { it.candidateId },
        clusters = clusters,
        rejectedIds = listOf("r1", "r2", "r3", "r4"),
        note = CuratorNote(
            totalPhotos = analyses.size,
            clusterCount = clusters.size,
            rejectedCount = 4,
            chosenMode = StoryMode.COLLAGE_3,
            scales = listOf(ShotScale.WIDE, ShotScale.MEDIUM, ShotScale.DETAIL),
            dominantScene = SceneType.BEACH_SEA,
            warmth = Warmth.GOLDEN_WARM,
            harmonyPercent = 87,
        ),
        suggestedLook = MasterLook.PORTRA_400_GOLDEN,
    )

    /** A ready studio showing [tab], with a lightly adjusted grade. */
    fun state(tab: ControlTab = ControlTab.CURATION, drawerOpen: Boolean = false): StudioState = StudioState(
        phase = StudioPhase.Ready,
        curation = curation,
        selection = SelectionUi(mode = StoryMode.COLLAGE_3, photoIds = persistentListOf("p1", "p2", "p3")),
        template = StoryTemplate.TRIO_BENTO,
        layout = LayoutStyle(gutterPx = 12f, cornerRadiusPx = 16f, showExifCaption = true),
        grade = GradeUi(
            look = MasterLook.PORTRA_400_GOLDEN,
            intensity = 0.85f,
            temperature = 0.3f,
            contrast = 0.12f,
            saturation = -0.08f,
            grain = 0.25f,
        ),
        compare = 0.5f,
        tab = tab,
        modeDrawerOpen = drawerOpen,
        export = ExportUi(
            format = ExportFormat.SOCIAL_STORY_JPEG,
            resolution = ExportResolution.UHD_4K,
            hdrBoost = true,
            lastResult = ExportResult(
                uri = "content://media/external/images/media/42",
                mimeType = "image/jpeg",
                width = 2160,
                height = 3840,
                byteSize = 8_400_000L,
                format = ExportFormat.SOCIAL_STORY_JPEG,
            ),
        ),
    )

    /**
     * The user forced Hero over the curator's 3-photo collage, drawer open. The curation is what `decide` returns
     * for the forced mode (its note names Hero); the AI recommendation stays on the collage.
     */
    fun userPick(): StudioState = state(drawerOpen = true).copy(
        curation = curation.copy(
            chosen = options.getValue(StoryMode.HERO),
            note = curation.note.copy(chosenMode = StoryMode.HERO),
        ),
        aiRecommendedMode = StoryMode.COLLAGE_3,
        selection = SelectionUi(mode = StoryMode.HERO, photoIds = persistentListOf("p1")),
        template = StoryTemplate.HERO_FULL_BLEED,
    )

    /** An export running at the encode stage, 72% done. */
    fun exporting(): StudioState = state(ControlTab.EXPORT).let {
        it.copy(
            phase = StudioPhase.Exporting,
            export = it.export.copy(
                format = ExportFormat.PNG_16,
                progress = ExportProgressUi(ExportStage.ENCODE, 0.72f),
                lastResult = null,
            ),
        )
    }
}
