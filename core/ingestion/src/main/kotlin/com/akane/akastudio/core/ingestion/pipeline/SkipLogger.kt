package com.akane.akastudio.core.ingestion.pipeline

import android.util.Log
import com.akane.akastudio.core.ingestion.model.SkipReason

/** Records why a source was skipped, so a field report of "N skipped" carries its reasons. */
fun interface SkipLogger {
    fun skipped(displayName: String, reason: SkipReason, cause: Throwable?)
}

/** Debug-level logcat output under [TAG]. */
object LogcatSkipLogger : SkipLogger {
    const val TAG = "AkaIngestion"

    override fun skipped(displayName: String, reason: SkipReason, cause: Throwable?) {
        Log.d(TAG, "Skipped $reason: $displayName", cause)
    }
}
