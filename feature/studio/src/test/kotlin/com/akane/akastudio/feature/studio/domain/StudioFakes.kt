package com.akane.akastudio.feature.studio.domain

import com.akane.akastudio.core.color.api.HalfFloatImage
import com.akane.akastudio.core.color.api.HarmonizationParams
import com.akane.akastudio.core.color.api.ImagePixels
import com.akane.akastudio.core.color.api.LabStats
import com.akane.akastudio.core.color.api.MasterLook
import com.akane.akastudio.core.color.api.NormRect
import com.akane.akastudio.core.color.api.PreviewOptions
import com.akane.akastudio.core.color.api.Rect4f
import com.akane.akastudio.core.color.api.RenderScene
import com.akane.akastudio.core.color.api.SceneBackground
import com.akane.akastudio.core.color.api.SceneLayer
import com.akane.akastudio.core.color.api.SceneType
import com.akane.akastudio.core.color.api.StoryRenderer
import com.akane.akastudio.core.composer.api.CellPlacement
import com.akane.akastudio.core.composer.api.CollageComposer
import com.akane.akastudio.core.composer.api.ComposerPhoto
import com.akane.akastudio.core.composer.api.CompositionRequest
import com.akane.akastudio.core.curator.api.CurationEngine
import com.akane.akastudio.core.curator.api.CurationOption
import com.akane.akastudio.core.curator.api.CurationProgress
import com.akane.akastudio.core.curator.api.CurationResult
import com.akane.akastudio.core.curator.api.CuratorNote
import com.akane.akastudio.core.curator.api.PhotoAnalysis
import com.akane.akastudio.core.curator.api.RejectReason
import com.akane.akastudio.core.curator.api.SaliencyMap
import com.akane.akastudio.core.curator.api.SceneCluster
import com.akane.akastudio.core.curator.api.ShotScale
import com.akane.akastudio.core.curator.api.StoryMode
import com.akane.akastudio.core.curator.api.TechnicalScore
import com.akane.akastudio.core.curator.api.Warmth
import com.akane.akastudio.core.export.api.ExportProgress
import com.akane.akastudio.core.export.api.ExportRequest
import com.akane.akastudio.core.export.api.ExportResult
import com.akane.akastudio.core.export.api.ExportStage
import com.akane.akastudio.core.export.api.StoryExporter
import com.akane.akastudio.core.ingestion.api.IngestionRequest
import com.akane.akastudio.core.ingestion.api.IngestionSession
import com.akane.akastudio.core.ingestion.model.CaptureMetadata
import com.akane.akastudio.core.ingestion.model.ImageFormat
import com.akane.akastudio.core.ingestion.model.IngestionEvent
import com.akane.akastudio.core.ingestion.model.IngestionTelemetry
import com.akane.akastudio.core.ingestion.model.PhotoCandidate
import com.akane.akastudio.core.ingestion.model.PhotoSource
import com.akane.akastudio.core.ingestion.model.SkipReason
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler

/** Builders for the frozen core types used by the studio tests. */
object TestData {
    fun candidate(id: String, gainMap: Boolean = false, captureMillis: Long? = null) = PhotoCandidate(
        id = id,
        source = PhotoSource.ContentUri("content://media/$id", "$id.jpg"),
        format = ImageFormat.JPEG,
        byteSize = 1_000L,
        widthPx = 4000,
        heightPx = 3000,
        metadata = CaptureMetadata.EMPTY.copy(hasUltraHdrGainMap = gainMap, captureEpochMillis = captureMillis),
    )

    fun labStats(meanL: Float) = LabStats(meanL, 0.01f, -0.02f, 0.2f, 0.05f, 0.05f, 100f)

    fun analysis(id: String, quality: Float = 0.5f, meanL: Float = 0.5f, rejected: RejectReason? = null) = PhotoAnalysis(
        candidateId = id,
        technical = TechnicalScore(1f, 1f, 1f, 1f),
        aesthetic = 0.5f,
        composition = 0.5f,
        quality = quality,
        embedding = floatArrayOf(1f),
        dHash = 0L,
        pHash = 0L,
        palette = emptyList(),
        labStats = labStats(meanL),
        cctKelvin = 5500f,
        shotScale = ShotScale.MEDIUM,
        scene = SceneType.GENERAL,
        faces = emptyList(),
        saliency = SaliencyMap(1, 1, floatArrayOf(0f)),
        rejectReason = rejected,
    )

    fun composerPhoto(id: String) = ComposerPhoto(
        id = id,
        imageKey = "key-$id",
        widthPx = 4000,
        heightPx = 3000,
        saliency = SaliencyMap(1, 1, floatArrayOf(0f)),
        faces = emptyList(),
        palette = emptyList(),
        horizonTiltDegrees = 0f,
        metadata = CaptureMetadata.EMPTY,
    )

    /**
     * A result over [analyses] choosing [chosenMode]; [options] gives the photo ids per feasible mode (must include
     * [chosenMode]).
     */
    fun result(
        chosenMode: StoryMode,
        options: Map<StoryMode, List<String>>,
        analyses: List<PhotoAnalysis>,
        clusters: List<SceneCluster>,
        look: MasterLook = MasterLook.PORTRA_400_GOLDEN,
        harmonyPercent: Int = 87,
    ): CurationResult {
        val alternatives = options.mapValues { (mode, ids) -> CurationOption(mode, ids, 0.9f, null) }
        return CurationResult(
            chosen = alternatives.getValue(chosenMode),
            alternatives = alternatives,
            analyses = analyses.associateBy { it.candidateId },
            clusters = clusters,
            rejectedIds = analyses.filter { it.rejectReason != null }.map { it.candidateId },
            note = CuratorNote(
                totalPhotos = analyses.size,
                clusterCount = clusters.size,
                rejectedCount = analyses.count { it.rejectReason != null },
                chosenMode = chosenMode,
                scales = emptyList(),
                dominantScene = SceneType.GENERAL,
                warmth = Warmth.NEUTRAL,
                harmonyPercent = harmonyPercent,
            ),
            suggestedLook = look,
        )
    }

    /** An ingestion flow accepting [candidates] then skipping [skipped] files, waiting [stepMs] between events. */
    fun ingestion(candidates: List<PhotoCandidate>, skipped: Int = 0, stepMs: Long = 0L): Flow<IngestionEvent> = flow {
        var t = IngestionTelemetry(sourcesTotal = candidates.size + skipped)
        emit(IngestionEvent.Progress(t))
        for (c in candidates) {
            if (stepMs > 0) delay(stepMs)
            t = t.copy(filesRead = t.filesRead + 1, candidatesAccepted = t.candidatesAccepted + 1, bytesExtracted = t.bytesExtracted + 1_000)
            emit(IngestionEvent.CandidateReady(c, t))
        }
        repeat(skipped) { i ->
            t = t.copy(filesRead = t.filesRead + 1, skipped = t.skipped + 1)
            emit(IngestionEvent.Skipped("junk$i.txt", SkipReason.UNSUPPORTED_TYPE, t))
        }
        if (stepMs > 0) delay(stepMs)
        emit(IngestionEvent.Completed(candidates, t))
    }
}

class FakeIngestionSession(private val scripts: ArrayDeque<Flow<IngestionEvent>>) : IngestionSession {
    override val workDir: File = File("build/fake-ingest")
    val requests = mutableListOf<IngestionRequest>()
    var closeCount = 0
        private set

    /** Thrown by [close] after it is counted. */
    var closeFailWith: Exception? = null

    override fun ingest(requests: List<IngestionRequest>): Flow<IngestionEvent> {
        this.requests += requests
        return scripts.removeFirstOrNull() ?: throw IllegalStateException("No ingestion script for $requests")
    }

    override fun close() {
        closeCount++
        closeFailWith?.let { throw it }
    }
}

class FakeCurationEngine(var result: CurationResult, private val stepMs: Long = 100L) : CurationEngine {
    val curated = mutableListOf<List<PhotoCandidate>>()
    val decideCalls = mutableListOf<StoryMode?>()
    var failWith: Exception? = null

    /** Runs inside [decide], standing in for main-thread work that happens while the decision is computed. */
    var onDecide: (() -> Unit)? = null

    override fun curate(candidates: List<PhotoCandidate>): Flow<CurationProgress> = flow {
        curated += candidates
        delay(stepMs)
        emit(CurationProgress.Analyzing(0, candidates.size))
        delay(stepMs)
        emit(CurationProgress.Analyzing(candidates.size, candidates.size))
        delay(stepMs)
        failWith?.let { throw it }
        emit(CurationProgress.Clustered(result.clusters.size, result.rejectedIds.size))
        delay(stepMs)
        emit(CurationProgress.Decided(result))
    }

    override fun decide(result: CurationResult, forcedMode: StoryMode?): CurationResult {
        decideCalls += forcedMode
        onDecide?.invoke()
        val option = if (forcedMode == null) result.chosen else result.alternatives.getValue(forcedMode)
        return result.copy(chosen = option, note = result.note.copy(chosenMode = option.mode))
    }
}

class FakePreparer(var delayMs: Long = 50L) : PhotoPreparer {
    val prepareCalls = mutableListOf<Pair<List<String>, Boolean>>()
    val released = mutableListOf<String>()
    var failWith: Exception? = null

    /** Thrown by the next [prepare] only (after its delay), then cleared. */
    var failOnce: Exception? = null
    var dropFromResult: String? = null

    /** How long [release] suspends after recording its ids. */
    var releaseDelayMs: Long = 0L

    /** Thrown by [release] after its ids are recorded and its delay has passed. */
    var releaseFailWith: Exception? = null

    override suspend fun prepare(
        candidates: List<PhotoCandidate>,
        analyses: Map<String, PhotoAnalysis>,
        hero: Boolean,
    ): List<ComposerPhoto> {
        prepareCalls += candidates.map { it.id } to hero
        delay(delayMs)
        failWith?.let { throw it }
        failOnce?.let {
            failOnce = null
            throw it
        }
        return candidates.filter { it.id != dropFromResult }.map { TestData.composerPhoto(it.id) }
    }

    override suspend fun release(ids: Collection<String>) {
        released += ids
        if (releaseDelayMs > 0) delay(releaseDelayMs)
        releaseFailWith?.let { throw it }
    }
}

/** Encodes the inputs into the params: slot i gets offsetL = strength * i and offsetA = stats[i].meanL. */
class FakeHarmonizer : HarmonizationPlanner {
    val calls = mutableListOf<Triple<List<LabStats>, Int, Float>>()
    var wrongSize = false

    override fun plan(stats: List<LabStats>, anchorIndex: Int, strength: Float): List<HarmonizationParams> {
        calls += Triple(stats, anchorIndex, strength)
        val planned = stats.mapIndexed { i, s -> expected(i, s.meanL, strength) }
        return if (wrongSize) planned.drop(1) else planned
    }

    companion object {
        fun expected(index: Int, meanL: Float, strength: Float) =
            HarmonizationParams(1f, strength * index, 1f, meanL, 1f, 0f)
    }
}

/** Stacks one layer per photo, carrying the request's grade, corner radius and canvas size. */
class FakeComposer : CollageComposer {
    val requests = mutableListOf<CompositionRequest>()
    var failWith: Exception? = null

    override fun layout(request: CompositionRequest): List<CellPlacement> {
        val cell = request.canvas.height.toFloat() / request.photos.size
        return request.photos.mapIndexed { i, p ->
            CellPlacement(p.id, Rect4f(0f, i * cell, request.canvas.width.toFloat(), (i + 1) * cell), NormRect.FULL, 0f)
        }
    }

    override fun compose(request: CompositionRequest): RenderScene {
        requests += request
        failWith?.let { throw it }
        val byId = request.photos.associateBy { it.id }
        return RenderScene(
            canvasWidth = request.canvas.width,
            canvasHeight = request.canvas.height,
            background = SceneBackground.Solid(0xFF121316.toInt()),
            layers = layout(request).map { placement ->
                SceneLayer(
                    imageKey = byId.getValue(placement.photoId).imageKey,
                    dst = placement.dst,
                    crop = request.overrides[placement.photoId]?.crop ?: placement.crop,
                    rotationDegrees = placement.rotationDegrees,
                    cornerRadiusPx = request.style.cornerRadiusPx,
                    grade = request.grades.getValue(placement.photoId),
                )
            },
        )
    }

    override fun hitTest(request: CompositionRequest, xPx: Float, yPx: Float): Int? =
        layout(request).indexOfFirst { yPx >= it.dst.top && yPx < it.dst.bottom }.takeIf { it >= 0 }
}

class FakeRenderer : StoryRenderer {
    val previewCalls = mutableListOf<RenderScene>()

    /** How long [renderPreviewPixels] suspends after recording its scene. */
    var previewDelayMs: Long = 0L

    /** Thrown by [renderPreviewPixels] after its delay. */
    var previewFailWith: Exception? = null

    override suspend fun registerImage(key: String, bitmap: android.graphics.Bitmap) =
        throw UnsupportedOperationException("The studio never registers images itself")

    override suspend fun unregisterImage(key: String) =
        throw UnsupportedOperationException("The studio never unregisters images itself")

    override suspend fun renderToSurface(
        scene: RenderScene,
        surface: android.view.Surface,
        surfaceWidth: Int,
        surfaceHeight: Int,
        options: PreviewOptions,
    ) = throw UnsupportedOperationException("Surface rendering belongs to the UI")

    override suspend fun renderOffscreen(scene: RenderScene): HalfFloatImage =
        throw UnsupportedOperationException("Offscreen rendering belongs to the exporter")

    /** Every pixel holds the 1-based call number, so a test can tell which render produced a readback. */
    override suspend fun renderPreviewPixels(scene: RenderScene, width: Int, height: Int): ImagePixels {
        previewCalls += scene
        val call = previewCalls.size
        if (previewDelayMs > 0) delay(previewDelayMs)
        previewFailWith?.let { throw it }
        return ImagePixels(width, height, IntArray(width * height) { call })
    }

    override suspend fun maxTextureSize(): Int = 8192

    var closeCount = 0
        private set

    override fun close() {
        closeCount++
    }
}

class FakeExporter(private val stepMs: Long = 100L) : StoryExporter {
    val requests = mutableListOf<ExportRequest>()
    var failWith: Exception? = null

    override fun export(request: ExportRequest): Flow<ExportProgress> = flow {
        requests += request
        emit(ExportProgress.Stage(ExportStage.RENDER, 0f))
        delay(stepMs)
        failWith?.let { throw it }
        emit(ExportProgress.Stage(ExportStage.ENCODE, 0.5f))
        delay(stepMs)
        emit(
            ExportProgress.Done(
                ExportResult(
                    uri = "content://media/external/images/media/42",
                    mimeType = "image/jpeg",
                    width = request.resolution.width,
                    height = request.resolution.height,
                    byteSize = 123_456L,
                    format = request.format,
                ),
            ),
        )
    }
}

/**
 * Distinct main, default and io dispatchers on one [scheduler]. Because they differ, `withContext` really dispatches
 * and re-checks cancellation on the way back, as it does in production; one shared dispatcher would hide that.
 */
fun studioTestDispatchers(scheduler: TestCoroutineScheduler) = StudioDispatchers(
    main = StandardTestDispatcher(scheduler, "main"),
    default = StandardTestDispatcher(scheduler, "default"),
    io = StandardTestDispatcher(scheduler, "io"),
)

/** Fake dependencies; every session pops its ingestion flows from [ingestScripts]. */
class FakeStudioDependencies(
    override val dispatchers: StudioDispatchers,
    override val curation: FakeCurationEngine,
    override val errors: StudioErrorMapper = DefaultStudioErrorMapper(),
) : StudioDependencies {
    val ingestScripts = ArrayDeque<Flow<IngestionEvent>>()
    val sessions = mutableListOf<FakeIngestionSession>()
    override val composer = FakeComposer()
    override val renderer = FakeRenderer()
    override val exporter = FakeExporter()
    override val preparer = FakePreparer()
    override val harmonizer = FakeHarmonizer()

    override fun newIngestionSession(): IngestionSession = FakeIngestionSession(ingestScripts).also { sessions += it }
}
