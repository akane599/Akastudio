package com.akane.akastudio.feature.studio.di

import android.content.ComponentCallbacks2
import android.content.Context
import com.akane.akastudio.core.color.api.StoryRenderer
import com.akane.akastudio.core.color.harmonize.Harmonizer
import com.akane.akastudio.core.color.render.createStoryRenderer
import com.akane.akastudio.core.composer.api.CollageComposer
import com.akane.akastudio.core.composer.compose.DefaultCollageComposer
import com.akane.akastudio.core.curator.api.CurationEngine
import com.akane.akastudio.core.curator.engine.DefaultCurationEngine
import com.akane.akastudio.core.curator.engine.asAnalysisModels
import com.akane.akastudio.core.curator.engine.asPixelSource
import com.akane.akastudio.core.curator.ml.CuratorModelsFactory
import com.akane.akastudio.core.export.api.StoryExporter
import com.akane.akastudio.core.export.store.createStoryExporter
import com.akane.akastudio.core.ingestion.api.AnalysisDecoder
import com.akane.akastudio.core.ingestion.api.IngestionSession
import com.akane.akastudio.core.ingestion.createAnalysisDecoder
import com.akane.akastudio.core.ingestion.createIngestionSession
import com.akane.akastudio.core.ingestion.decode.FullResolutionDecoder
import com.akane.akastudio.feature.studio.domain.HarmonizationPlanner
import com.akane.akastudio.feature.studio.domain.PhotoPreparer
import com.akane.akastudio.feature.studio.domain.StudioDependencies
import com.akane.akastudio.feature.studio.domain.StudioDispatchers
import com.akane.akastudio.feature.studio.domain.StudioErrorMapper
import kotlinx.coroutines.Dispatchers

/**
 * The production [StudioDependencies]: every core module wired with its concrete implementation. Create exactly one
 * per process (the app's `Application` holds it); everything heavy is built on first use.
 *
 * Lifetime: the [renderer] (one GL thread and its textures) and the curator's on-device models are process-wide and
 * are never closed. The studio view model unregisters every photo when it is cleared, so a backgrounded process
 * holds only the idle GL context and the loaded models, and the system reclaims both with the process. Closing them
 * on a trim-memory signal would leave a later view model talking to a closed renderer, so [onTrimMemory] only trims
 * the renderer's caches.
 */
class StudioContainer(context: Context) : StudioDependencies {

    private val app: Context = context.applicationContext

    private val analysisDecoder: AnalysisDecoder by lazy { createAnalysisDecoder(app) }

    override fun newIngestionSession(): IngestionSession = createIngestionSession(app)

    override val dispatchers: StudioDispatchers = StudioDispatchers(
        main = Dispatchers.Main.immediate,
        default = Dispatchers.Default,
        io = Dispatchers.IO,
    )

    override val curation: CurationEngine by lazy {
        DefaultCurationEngine(
            pixels = analysisDecoder.asPixelSource(),
            models = LazyAnalysisModels(Dispatchers.Default) { CuratorModelsFactory.create(app).asAnalysisModels() },
        )
    }

    override val composer: CollageComposer by lazy { DefaultCollageComposer() }

    private val rendererInstance = lazy { createStoryRenderer(app) }

    override val renderer: StoryRenderer by rendererInstance

    override val exporter: StoryExporter by lazy { createStoryExporter(app, renderer) }

    override val preparer: PhotoPreparer by lazy {
        DefaultPhotoPreparer(
            AndroidPreparerBackend(
                decoder = FullResolutionDecoder(app.contentResolver),
                renderer = renderer,
                analysisDecoder = analysisDecoder,
            ),
        )
    }

    override val harmonizer: HarmonizationPlanner =
        HarmonizationPlanner { stats, anchorIndex, strength -> Harmonizer.forCollage(stats, anchorIndex, strength) }

    override val errors: StudioErrorMapper = StudioContainerErrorMapper()

    /**
     * Forward of `Application.onTrimMemory`. From [ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN] up (the app's UI has
     * stopped, or the system is short of memory) the renderer frees its pooled targets, cached grades and window
     * surface; it stays usable. A renderer that was never built is left unbuilt.
     */
    fun onTrimMemory(level: Int) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN && rendererInstance.isInitialized()) renderer.trimMemory()
    }
}
