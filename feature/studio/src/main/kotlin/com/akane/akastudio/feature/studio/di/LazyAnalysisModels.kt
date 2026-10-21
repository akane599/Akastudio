package com.akane.akastudio.feature.studio.di

import com.akane.akastudio.core.color.api.SceneType
import com.akane.akastudio.core.curator.engine.AnalysisImage
import com.akane.akastudio.core.curator.engine.AnalysisModels
import com.akane.akastudio.core.curator.ml.DetectedFace
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * [AnalysisModels] whose real models are built by [load] on [dispatcher] at first use, so app start never pays the
 * hundreds of milliseconds model loading takes. Concurrent first calls share one load. The load always runs to
 * completion, even when the call that started it is cancelled, so native models are never built twice.
 */
internal class LazyAnalysisModels(
    private val dispatcher: CoroutineDispatcher,
    private val load: () -> AnalysisModels,
) : AnalysisModels {

    private val mutex = Mutex()

    @Volatile
    private var loaded: AnalysisModels? = null

    private suspend fun models(): AnalysisModels = loaded ?: mutex.withLock {
        loaded ?: withContext(NonCancellable + dispatcher) { load() }.also { loaded = it }
    }

    override suspend fun faces(img: AnalysisImage): List<DetectedFace> = models().faces(img)

    override suspend fun embed(img: AnalysisImage): FloatArray = models().embed(img)

    override suspend fun aesthetic(img: AnalysisImage): Float? = models().aesthetic(img)

    override suspend fun scene(img: AnalysisImage): SceneType = models().scene(img)
}
