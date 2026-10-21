package com.akane.akastudio.feature.studio.ui.note

import com.akane.akastudio.core.curator.api.CuratorNote
import com.akane.akastudio.core.curator.api.StoryMode

/** Turns a structured [CuratorNote] into one localized sentence for the canvas caption. */
object CuratorNoteFormatter {
    private const val MIN_PERCENT = 0
    private const val MAX_PERCENT = 100

    /**
     * Formats [note] with [res].
     *
     * Hero notes report the frame's score; collage notes report the frame count, the distinct shot
     * scales in first-seen order (repeats are listed once) and the harmony percentage. Percentages
     * are clamped to 0..100.
     */
    fun format(note: CuratorNote, res: NoteStrings): String {
        val photos = res.photos(note.totalPhotos)
        val tone = res.tone(note.warmth)
        val percent = res.percent(note.harmonyPercent.coerceIn(MIN_PERCENT, MAX_PERCENT))
        if (note.chosenMode == StoryMode.HERO) {
            return res.heroSentence(photos = photos, tone = tone, score = percent)
        }
        val frames = res.frames(note.chosenMode.photoCount)
        val distinct = note.scales.distinct()
        if (distinct.isEmpty()) {
            return res.collageSentenceWithoutScales(photos = photos, frames = frames, tone = tone, harmony = percent)
        }
        return res.collageSentence(
            photos = photos,
            frames = frames,
            scales = res.distinctScales(distinct.size),
            scaleList = distinct.joinToString(res.listSeparator) { res.scaleName(it) },
            tone = tone,
            harmony = percent,
        )
    }
}
