package com.akane.akastudio.feature.studio.ui.note

import com.akane.akastudio.core.curator.api.ShotScale
import com.akane.akastudio.core.curator.api.Warmth

/**
 * Localized fragments and sentence templates for [CuratorNoteFormatter].
 *
 * Fragments are already-inflected phrases ("24 photos", "across three scales") so each language
 * controls its own plural rules and word order; sentence templates receive those phrases and may
 * place them in any order.
 */
interface NoteStrings {
    /** Separator between items in the scale list, e.g. ", ". */
    val listSeparator: String

    /** Count of input photos, e.g. "24 photos". */
    fun photos(count: Int): String

    /** Count of frames in the chosen story, e.g. "3 frames". */
    fun frames(count: Int): String

    /** Count of distinct shot scales, e.g. "across three scales". */
    fun distinctScales(count: Int): String

    /** Display name of one shot scale, e.g. "Wide". */
    fun scaleName(scale: ShotScale): String

    /** Adjective phrase that precedes "tones", e.g. "warm golden-hour". */
    fun tone(warmth: Warmth): String

    /** A whole percentage, e.g. "94%". */
    fun percent(value: Int): String

    /** Single-frame sentence. */
    fun heroSentence(photos: String, tone: String, score: String): String

    /** Collage sentence that names the distinct scales. */
    fun collageSentence(
        photos: String,
        frames: String,
        scales: String,
        scaleList: String,
        tone: String,
        harmony: String,
    ): String

    /** Collage sentence used when the note carries no scales. */
    fun collageSentenceWithoutScales(photos: String, frames: String, tone: String, harmony: String): String
}
