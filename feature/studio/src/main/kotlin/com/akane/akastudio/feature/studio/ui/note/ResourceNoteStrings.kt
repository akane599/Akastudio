package com.akane.akastudio.feature.studio.ui.note

import android.content.Context
import android.content.res.Resources
import com.akane.akastudio.core.curator.api.ShotScale
import com.akane.akastudio.core.curator.api.Warmth
import com.akane.akastudio.feature.studio.R

/** [NoteStrings] backed by the studio's string and plurals resources for the context's locale. */
class ResourceNoteStrings(context: Context) : NoteStrings {
    private val resources: Resources = context.resources
    private val countWords: Array<String> = resources.getStringArray(R.array.note_count_words)

    override val listSeparator: String = resources.getString(R.string.note_list_separator)

    override fun photos(count: Int): String =
        resources.getQuantityString(R.plurals.note_photos, count, count)

    override fun frames(count: Int): String =
        resources.getQuantityString(R.plurals.note_frames, count, count)

    override fun distinctScales(count: Int): String =
        resources.getQuantityString(R.plurals.note_scales, count, countWords.getOrNull(count - 1) ?: count.toString())

    override fun scaleName(scale: ShotScale): String = resources.getString(
        when (scale) {
            ShotScale.WIDE -> R.string.shot_scale_wide
            ShotScale.MEDIUM -> R.string.shot_scale_medium
            ShotScale.DETAIL -> R.string.shot_scale_detail
        },
    )

    override fun tone(warmth: Warmth): String = resources.getString(
        when (warmth) {
            Warmth.GOLDEN_WARM -> R.string.note_tone_golden_warm
            Warmth.NEUTRAL -> R.string.note_tone_neutral
            Warmth.COOL_BLUE -> R.string.note_tone_cool_blue
            Warmth.NIGHT_TUNGSTEN -> R.string.note_tone_night_tungsten
        },
    )

    override fun percent(value: Int): String = resources.getString(R.string.note_percent, value)

    override fun heroSentence(photos: String, tone: String, score: String): String =
        resources.getString(R.string.note_hero, photos, tone, score)

    override fun collageSentence(
        photos: String,
        frames: String,
        scales: String,
        scaleList: String,
        tone: String,
        harmony: String,
    ): String = resources.getString(R.string.note_collage, photos, frames, scales, scaleList, tone, harmony)

    override fun collageSentenceWithoutScales(photos: String, frames: String, tone: String, harmony: String): String =
        resources.getString(R.string.note_collage_no_scales, photos, frames, tone, harmony)
}
