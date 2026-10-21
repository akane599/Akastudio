package com.akane.akastudio.feature.studio.ui.canvas.scope

/** Placement math for the scope's axis labels, kept free of Compose so it can be tested on the JVM. */
internal object ScopeLabelLayout {

    /**
     * Start offset (top or left) of a label [extent] long, centred on [centre] and kept inside `[0, available]`.
     *
     * Null when the label does not fit, which happens while the panel animates in from zero size: the caller skips
     * that label rather than drawing it outside the plot. Never throws.
     */
    fun start(centre: Float, extent: Float, available: Float): Float? {
        val room = available - extent
        if (!(room >= 0f)) return null // also rejects NaN, which coerceIn would pass through unclamped
        return (centre - extent / 2f).coerceIn(0f, room)
    }
}
