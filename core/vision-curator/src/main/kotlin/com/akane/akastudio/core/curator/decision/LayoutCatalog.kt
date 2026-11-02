package com.akane.akastudio.core.curator.decision

import com.akane.akastudio.core.curator.api.PhotoAnalysis

/** Shape of one collage cell, as far as the curator needs to know it. */
sealed interface CellShape {
    /**
     * A cropped cell with this width / height ratio (at the layout's nominal gutter). When the host can render the
     * cell at other aspects too (e.g. the user's gutter setting), [minWidthOverHeight]..[maxWidthOverHeight] spans
     * them; a fit must hold over the whole range. Both default to [widthOverHeight].
     */
    data class Aspect(
        val widthOverHeight: Float,
        val minWidthOverHeight: Float = widthOverHeight,
        val maxWidthOverHeight: Float = widthOverHeight,
    ) : CellShape {
        init {
            require(widthOverHeight.isFinite() && widthOverHeight > 0f) { "aspect must be positive: $widthOverHeight" }
            require(minWidthOverHeight.isFinite() && minWidthOverHeight > 0f && minWidthOverHeight <= widthOverHeight) {
                "min aspect must be positive and <= $widthOverHeight: $minWidthOverHeight"
            }
            require(maxWidthOverHeight.isFinite() && maxWidthOverHeight >= widthOverHeight) {
                "max aspect must be >= $widthOverHeight: $maxWidthOverHeight"
            }
        }
    }

    /** The photo is shown whole (matte/letterbox); any photo fits. */
    data object Uncropped : CellShape
}

/**
 * One layout the host can render: an opaque [id] (the host maps it back to its own template) and one [CellShape] per
 * slot, in slot order. Slot 0 is the anchor slot (colour anchor, caption/background source) and never moves.
 */
data class LayoutSpec(val id: String, val cells: List<CellShape>) {
    init {
        require(id.isNotBlank()) { "layout id must not be blank" }
        require(cells.isNotEmpty()) { "layout $id has no cells" }
    }
    val photoCount: Int get() = cells.size
}

/** The layouts the host offers, grouped by photo count. [EMPTY] disables layout-aware selection (legacy behaviour). */
data class LayoutCatalog(val layouts: List<LayoutSpec>) {
    fun forCount(photoCount: Int): List<LayoutSpec> = layouts.filter { it.photoCount == photoCount }

    companion object {
        val EMPTY = LayoutCatalog(emptyList())
    }
}

/** A verified assignment of photos to a layout; [orderedIds] keeps the anchor (index 0) where it was. */
data class LayoutFit(val layoutId: String, val orderedIds: List<String>, val score: Float)

/**
 * Decides whether a candidate set can be shown in one of the catalogue's layouts for its count without an infeasible
 * crop (a prominent face cut, or the kept subject share below a floor), and which layout/slot order fits best.
 * Implementations must keep `photos[0]` in slot 0 and must be pure.
 */
fun interface LayoutFitter {
    /** @return the best fit, or null when no layout of this count fits; an empty catalogue for the count yields null. */
    fun fit(photos: List<PhotoAnalysis>, catalog: LayoutCatalog): LayoutFit?

    companion object {
        /** Legacy behaviour: the first layout for the count fits in the given order (or null without a catalogue). */
        val AlwaysFits = LayoutFitter { photos, catalog ->
            catalog.forCount(photos.size).firstOrNull()?.let { LayoutFit(it.id, photos.map { p -> p.candidateId }, 1f) }
        }
    }
}
