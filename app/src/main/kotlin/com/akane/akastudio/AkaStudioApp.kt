package com.akane.akastudio

import android.app.Application
import com.akane.akastudio.core.ingestion.purgeStaleIngestionDirs
import com.akane.akastudio.feature.studio.di.StudioContainer

/** The process: owns the one [StudioContainer] every studio screen shares. */
class AkaStudioApp : Application() {

    /** The process-wide studio dependencies; heavy parts are built on first use. */
    lateinit var container: StudioContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = StudioContainer(this)
        // Session folders left behind by a killed process; deleting them touches disk, so keep it off the main thread.
        Thread({ purgeStaleIngestionDirs(this) }, "ingest-purge").start()
    }
}
