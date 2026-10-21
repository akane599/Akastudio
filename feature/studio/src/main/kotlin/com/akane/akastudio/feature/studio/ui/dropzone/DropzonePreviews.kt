package com.akane.akastudio.feature.studio.ui.dropzone

import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.app.ActivityOptionsCompat
import com.akane.akastudio.core.color.api.MasterLook
import com.akane.akastudio.core.color.api.SceneType
import com.akane.akastudio.core.curator.api.CurationOption
import com.akane.akastudio.core.curator.api.CurationResult
import com.akane.akastudio.core.curator.api.CuratorNote
import com.akane.akastudio.core.curator.api.ShotScale
import com.akane.akastudio.core.curator.api.StoryMode
import com.akane.akastudio.core.curator.api.SynergyBreakdown
import com.akane.akastudio.core.curator.api.Warmth
import com.akane.akastudio.core.ingestion.model.IngestionError
import com.akane.akastudio.feature.studio.domain.StudioError
import com.akane.akastudio.feature.studio.mvi.StudioPhase
import com.akane.akastudio.feature.studio.mvi.StudioState
import com.akane.akastudio.feature.studio.mvi.TelemetryStep
import com.akane.akastudio.feature.studio.mvi.TelemetryUi
import com.akane.akastudio.feature.studio.ui.theme.DarkroomTheme

/** Representative Dropzone states for previews and screenshot tests: one per phase the screen draws. */
internal object DropzoneSamples {
    private const val SOURCES = 41
    private const val ACCEPTED = 38
    private const val SKIPPED = 3
    private const val READ_SO_FAR = 27
    private const val ANALYZED = 23
    private const val CLUSTERS = 11
    private const val REJECTED = 4
    private const val HARMONY = 94
    private const val COLLAGE_SCORE = 0.94f

    val empty: StudioState = StudioState()

    val ingesting: StudioState = StudioState(
        phase = StudioPhase.Ingesting,
        telemetry = TelemetryUi(
            sourcesTotal = SOURCES,
            photosRead = READ_SO_FAR,
            accepted = READ_SO_FAR - SKIPPED,
            skipped = SKIPPED,
            step = TelemetryStep.READING,
        ),
    )

    /** Analysis at 23 of 38, about 60%. */
    val curating: StudioState = StudioState(
        phase = StudioPhase.Curating,
        telemetry = TelemetryUi(
            sourcesTotal = SOURCES,
            photosRead = SOURCES,
            accepted = ACCEPTED,
            skipped = SKIPPED,
            analyzed = ANALYZED,
            analyzeTotal = ACCEPTED,
            step = TelemetryStep.ANALYZING,
        ),
    )

    private val note = CuratorNote(
        totalPhotos = ACCEPTED,
        clusterCount = CLUSTERS,
        rejectedCount = REJECTED,
        chosenMode = StoryMode.COLLAGE_3,
        scales = listOf(ShotScale.WIDE, ShotScale.MEDIUM, ShotScale.DETAIL),
        dominantScene = SceneType.BEACH_SEA,
        warmth = Warmth.GOLDEN_WARM,
        harmonyPercent = HARMONY,
    )

    private val chosen = CurationOption(
        mode = StoryMode.COLLAGE_3,
        photoIds = listOf("a", "b", "c"),
        score = COLLAGE_SCORE,
        synergy = SynergyBreakdown(color = 0.95f, rhythm = 0.9f, context = 0.97f, psi = COLLAGE_SCORE),
    )

    val decided: StudioState = curating.copy(
        phase = StudioPhase.Preparing,
        telemetry = curating.telemetry.copy(
            analyzed = ACCEPTED,
            clusters = CLUSTERS,
            rejected = REJECTED,
            step = TelemetryStep.PREPARING,
            finalMode = StoryMode.COLLAGE_3,
            harmonyPercent = HARMONY,
        ),
        curation = CurationResult(
            chosen = chosen,
            alternatives = mapOf(StoryMode.COLLAGE_3 to chosen),
            analyses = emptyMap(),
            clusters = emptyList(),
            rejectedIds = emptyList(),
            note = note,
            suggestedLook = MasterLook.PORTRA_400_GOLDEN,
        ),
    )

    /** A failed archive import: ingestion resets to the empty phase and keeps the error. */
    val error: StudioState = StudioState(
        error = StudioError.Ingestion(IngestionError.InvalidArchive("central directory missing")),
    )
}

/** An activity-result registry that never launches anything, so previews can host the real pickers. */
private object PreviewResultRegistryOwner : ActivityResultRegistryOwner {
    override val activityResultRegistry: ActivityResultRegistry = object : ActivityResultRegistry() {
        override fun <I, O> onLaunch(
            requestCode: Int,
            contract: ActivityResultContract<I, O>,
            input: I,
            options: ActivityOptionsCompat?,
        ) = Unit
    }
}

/** Renders the public [DropzoneScreen] for [state] in the Darkroom theme, with inert pickers. */
@Composable
internal fun DropzonePreviewHost(state: StudioState) = DarkroomTheme {
    CompositionLocalProvider(LocalActivityResultRegistryOwner provides PreviewResultRegistryOwner) {
        DropzoneScreen(state = state, onIntent = {})
    }
}

@Preview(name = "Dropzone empty", widthDp = 360, heightDp = 760, showBackground = true, backgroundColor = 0xFF0B0C0E)
@Composable
private fun DropzoneEmptyPreview() = DropzonePreviewHost(DropzoneSamples.empty)

@Preview(name = "Dropzone ingesting", widthDp = 360, heightDp = 760, showBackground = true, backgroundColor = 0xFF0B0C0E)
@Composable
private fun DropzoneIngestingPreview() = DropzonePreviewHost(DropzoneSamples.ingesting)

@Preview(name = "Dropzone curating", widthDp = 360, heightDp = 760, showBackground = true, backgroundColor = 0xFF0B0C0E)
@Composable
private fun DropzoneCuratingPreview() = DropzonePreviewHost(DropzoneSamples.curating)

@Preview(name = "Dropzone decided", widthDp = 360, heightDp = 760, showBackground = true, backgroundColor = 0xFF0B0C0E)
@Composable
private fun DropzoneDecidedPreview() = DropzonePreviewHost(DropzoneSamples.decided)

@Preview(name = "Dropzone error", widthDp = 360, heightDp = 760, showBackground = true, backgroundColor = 0xFF0B0C0E)
@Composable
private fun DropzoneErrorPreview() = DropzonePreviewHost(DropzoneSamples.error)
