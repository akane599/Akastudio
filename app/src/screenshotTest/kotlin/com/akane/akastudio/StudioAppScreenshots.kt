package com.akane.akastudio

import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.app.ActivityOptionsCompat
import com.akane.akastudio.core.color.api.GradeParams
import com.akane.akastudio.core.color.api.LabStats
import com.akane.akastudio.core.color.api.MasterLook
import com.akane.akastudio.core.color.api.PaletteSwatch
import com.akane.akastudio.core.color.api.SceneType
import com.akane.akastudio.core.color.math.ColorMath
import com.akane.akastudio.core.composer.api.CanvasSize
import com.akane.akastudio.core.composer.api.ComposerPhoto
import com.akane.akastudio.core.composer.api.CompositionRequest
import com.akane.akastudio.core.composer.api.LayoutStyle
import com.akane.akastudio.core.composer.api.StoryTemplate
import com.akane.akastudio.core.composer.compose.DefaultCollageComposer
import com.akane.akastudio.core.curator.api.CurationOption
import com.akane.akastudio.core.curator.api.CurationResult
import com.akane.akastudio.core.curator.api.CuratorNote
import com.akane.akastudio.core.curator.api.PhotoAnalysis
import com.akane.akastudio.core.curator.api.SaliencyMap
import com.akane.akastudio.core.curator.api.SceneCluster
import com.akane.akastudio.core.curator.api.ShotScale
import com.akane.akastudio.core.curator.api.StoryMode
import com.akane.akastudio.core.curator.api.SynergyBreakdown
import com.akane.akastudio.core.curator.api.TechnicalScore
import com.akane.akastudio.core.curator.api.Warmth
import com.akane.akastudio.core.ingestion.model.CaptureMetadata
import com.akane.akastudio.core.ingestion.model.ImageFormat
import com.akane.akastudio.core.ingestion.model.PhotoCandidate
import com.akane.akastudio.core.ingestion.model.PhotoSource
import com.akane.akastudio.feature.studio.mvi.ControlTab
import com.akane.akastudio.feature.studio.mvi.GradeUi
import com.akane.akastudio.feature.studio.mvi.SelectionUi
import com.akane.akastudio.feature.studio.mvi.StudioPhase
import com.akane.akastudio.feature.studio.mvi.StudioState
import com.akane.akastudio.feature.studio.mvi.TelemetryStep
import com.akane.akastudio.feature.studio.mvi.TelemetryUi
import com.akane.akastudio.feature.studio.ui.StudioScreen
import com.akane.akastudio.feature.studio.ui.theme.DarkroomTheme
import com.android.tools.screenshot.PreviewTest
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableMap

// Rendered on the JVM: ./gradlew :app:updateDebugScreenshotTest (write refs) / validateDebugScreenshotTest (diff)

@PreviewTest @Preview(name = "studio-empty", widthDp = 360, heightDp = 760, showBackground = true, backgroundColor = 0xFF0B0C0E)
@Composable fun StudioAppEmpty() = StudioAppShot(StudioState())

@PreviewTest @Preview(name = "studio-ready", widthDp = 360, heightDp = 760, showBackground = true, backgroundColor = 0xFF0B0C0E)
@Composable fun StudioAppReady() = StudioAppShot(StudioAppFixtures.ready())

/** The whole studio screen in the Darkroom theme, with no renderer (placeholder canvas) and inert pickers. */
@Composable
private fun StudioAppShot(state: StudioState) = DarkroomTheme {
    CompositionLocalProvider(LocalActivityResultRegistryOwner provides InertResultRegistryOwner) {
        StudioScreen(state = state, onIntent = {}, renderer = null, hitTest = { _, _ -> null })
    }
}

private object InertResultRegistryOwner : ActivityResultRegistryOwner {
    override val activityResultRegistry: ActivityResultRegistry = object : ActivityResultRegistry() {
        override fun <I, O> onLaunch(
            requestCode: Int,
            contract: ActivityResultContract<I, O>,
            input: I,
            options: ActivityOptionsCompat?,
        ) = Unit
    }
}

/** A curated 3-photo city-break story, composed by the real collage composer. */
private object StudioAppFixtures {
    private val ids = listOf("p1", "p2", "p3")

    private fun swatch(l: Float, c: Float, h: Float, w: Float) = PaletteSwatch(ColorMath.okLchToOkLab(l, c, h), w)

    private val palette = listOf(
        swatch(0.72f, 0.07f, 70f, 0.35f), swatch(0.55f, 0.08f, 45f, 0.25f), swatch(0.45f, 0.05f, 230f, 0.2f),
        swatch(0.88f, 0.03f, 85f, 0.12f), swatch(0.25f, 0.02f, 40f, 0.08f),
    )

    private fun candidate(id: String, width: Int, height: Int) = PhotoCandidate(
        id = id,
        source = PhotoSource.ContentUri("content://media/$id", "$id.jpg"),
        format = ImageFormat.JPEG,
        byteSize = 4_000_000L,
        widthPx = width,
        heightPx = height,
        metadata = CaptureMetadata.EMPTY,
    )

    private fun analysis(id: String, quality: Float, scale: ShotScale) = PhotoAnalysis(
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
        cctKelvin = 4800f,
        shotScale = scale,
        scene = SceneType.ARCHITECTURE_AUTO,
        faces = emptyList(),
        saliency = SaliencyMap(1, 1, floatArrayOf(1f)),
        rejectReason = null,
    )

    private val candidates = listOf(candidate("p1", 3000, 4000), candidate("p2", 4000, 3000), candidate("p3", 3000, 3000))
    private val analyses = listOf(
        analysis("p1", 0.9f, ShotScale.WIDE),
        analysis("p2", 0.84f, ShotScale.MEDIUM),
        analysis("p3", 0.8f, ShotScale.DETAIL),
    )

    private val options = mapOf(
        StoryMode.HERO to CurationOption(StoryMode.HERO, listOf("p1"), 0.9f, null),
        StoryMode.COLLAGE_3 to CurationOption(
            StoryMode.COLLAGE_3, ids, 0.86f, SynergyBreakdown(0.9f, 0.82f, 0.85f, 0.86f),
        ),
    )

    private val curation = CurationResult(
        chosen = options.getValue(StoryMode.COLLAGE_3),
        alternatives = options,
        analyses = analyses.associateBy { it.candidateId },
        clusters = ids.mapIndexed { i, id -> SceneCluster(i, listOf(id), championId = id) },
        rejectedIds = emptyList(),
        note = CuratorNote(
            totalPhotos = 3,
            clusterCount = 3,
            rejectedCount = 0,
            chosenMode = StoryMode.COLLAGE_3,
            scales = listOf(ShotScale.WIDE, ShotScale.MEDIUM, ShotScale.DETAIL),
            dominantScene = SceneType.ARCHITECTURE_AUTO,
            warmth = Warmth.GOLDEN_WARM,
            harmonyPercent = 86,
        ),
        suggestedLook = MasterLook.PORTRA_400_GOLDEN,
    )

    fun ready(): StudioState {
        val template = StoryTemplate.TRIO_BENTO
        val layout = LayoutStyle(gutterPx = 12f, cornerRadiusPx = 16f)
        val photos = candidates.zip(analyses) { c, a ->
            ComposerPhoto(
                id = c.id,
                imageKey = c.id,
                widthPx = c.widthPx,
                heightPx = c.heightPx,
                saliency = a.saliency,
                faces = a.faces,
                palette = a.palette,
                horizonTiltDegrees = 0f,
                metadata = c.metadata,
            )
        }
        val scene = DefaultCollageComposer().compose(
            CompositionRequest(
                template = template,
                style = layout,
                canvas = CanvasSize.STORY_1080,
                photos = photos,
                grades = ids.associateWith { GradeParams.NEUTRAL },
            ),
        )
        return StudioState(
            phase = StudioPhase.Ready,
            telemetry = TelemetryUi(step = TelemetryStep.DONE, finalMode = StoryMode.COLLAGE_3, harmonyPercent = 86),
            curation = curation,
            selection = SelectionUi(
                mode = StoryMode.COLLAGE_3,
                photoIds = persistentListOf("p1", "p2", "p3"),
                slots = candidates.withIndex().associate { (i, c) -> i to c }.toImmutableMap(),
            ),
            template = template,
            layout = layout,
            grade = GradeUi(look = MasterLook.PORTRA_400_GOLDEN, intensity = 0.85f),
            scene = scene,
            sceneRevision = 1L,
            tab = ControlTab.COLOR,
        )
    }
}
