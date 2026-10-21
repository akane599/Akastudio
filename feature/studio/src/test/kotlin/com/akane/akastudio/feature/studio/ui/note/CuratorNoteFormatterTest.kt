package com.akane.akastudio.feature.studio.ui.note

import com.akane.akastudio.core.color.api.SceneType
import com.akane.akastudio.core.curator.api.CuratorNote
import com.akane.akastudio.core.curator.api.ShotScale
import com.akane.akastudio.core.curator.api.StoryMode
import com.akane.akastudio.core.curator.api.Warmth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CuratorNoteFormatterTest {

    private val en = EnglishNoteStrings()
    private val tr = TurkishNoteStrings()

    private fun note(
        mode: StoryMode,
        scales: List<ShotScale>,
        warmth: Warmth = Warmth.GOLDEN_WARM,
        harmony: Int = 94,
        total: Int = 24,
    ) = CuratorNote(
        totalPhotos = total,
        clusterCount = 5,
        rejectedCount = 2,
        chosenMode = mode,
        scales = scales,
        dominantScene = SceneType.BEACH_SEA,
        warmth = warmth,
        harmonyPercent = harmony,
    )

    private val threeScales = listOf(ShotScale.WIDE, ShotScale.MEDIUM, ShotScale.DETAIL)

    @Test
    fun `three-frame collage matches the English spec sentence`() {
        val text = CuratorNoteFormatter.format(note(StoryMode.COLLAGE_3, threeScales), en)
        assertEquals(
            "Chosen from 24 photos: 3 frames across three scales (Wide, Portrait, Detail) " +
                "in warm golden-hour tones — 94% harmony.",
            text,
        )
    }

    @Test
    fun `three-frame collage matches the Turkish spec sentence`() {
        val text = CuratorNoteFormatter.format(note(StoryMode.COLLAGE_3, threeScales), tr)
        assertEquals(
            "24 fotoğraf arasından 3 farklı ölçekte (Mekan, Portre, Detay) ve sıcak altın saat tonlarında " +
                "%94 uyum sağlayan 3 kare kolaj için seçildi.",
            text,
        )
    }

    @Test
    fun `hero reports the frame standing alone with its score`() {
        val text = CuratorNoteFormatter.format(note(StoryMode.HERO, listOf(ShotScale.MEDIUM), harmony = 91), en)
        assertEquals(
            "Chosen from 24 photos in warm golden-hour tones — this frame stands alone (score 91%).",
            text,
        )
    }

    @Test
    fun `two-frame collage names two scales`() {
        val text = CuratorNoteFormatter.format(note(StoryMode.COLLAGE_2, listOf(ShotScale.WIDE, ShotScale.DETAIL)), en)
        assertEquals(
            "Chosen from 24 photos: 2 frames across two scales (Wide, Detail) in warm golden-hour tones — 94% harmony.",
            text,
        )
    }

    @Test
    fun `four-frame collage deduplicates a repeated scale`() {
        val scales = listOf(ShotScale.WIDE, ShotScale.MEDIUM, ShotScale.MEDIUM, ShotScale.DETAIL)
        val text = CuratorNoteFormatter.format(note(StoryMode.COLLAGE_4, scales), en)
        assertEquals(
            "Chosen from 24 photos: 4 frames across three scales (Wide, Portrait, Detail) " +
                "in warm golden-hour tones — 94% harmony.",
            text,
        )
    }

    @Test
    fun `repeated scales are listed once in first-seen order`() {
        val scales = listOf(ShotScale.DETAIL, ShotScale.WIDE, ShotScale.DETAIL)
        val text = CuratorNoteFormatter.format(note(StoryMode.COLLAGE_3, scales), en)
        assertTrue(text, text.contains("3 frames across two scales (Detail, Wide)"))
    }

    @Test
    fun `a single repeated scale uses the singular phrase`() {
        val scales = List(3) { ShotScale.DETAIL }
        assertTrue(CuratorNoteFormatter.format(note(StoryMode.COLLAGE_3, scales), en).contains("across one scale (Detail)"))
        assertTrue(CuratorNoteFormatter.format(note(StoryMode.COLLAGE_3, scales), tr).contains("tek ölçekte (Detay)"))
    }

    @Test
    fun `collage without scales uses the scale-free sentence`() {
        val text = CuratorNoteFormatter.format(note(StoryMode.COLLAGE_2, emptyList()), en)
        assertEquals("Chosen from 24 photos: 2 frames in warm golden-hour tones — 94% harmony.", text)
    }

    @Test
    fun `every warmth renders its own tone phrase`() {
        val expected = mapOf(
            Warmth.GOLDEN_WARM to "in warm golden-hour tones",
            Warmth.NEUTRAL to "in neutral tones",
            Warmth.COOL_BLUE to "in cool blue tones",
            Warmth.NIGHT_TUNGSTEN to "in tungsten night tones",
        )
        assertEquals(Warmth.entries.toSet(), expected.keys)
        Warmth.entries.forEach { warmth ->
            val collage = CuratorNoteFormatter.format(note(StoryMode.COLLAGE_3, threeScales, warmth), en)
            val hero = CuratorNoteFormatter.format(note(StoryMode.HERO, listOf(ShotScale.WIDE), warmth), en)
            assertTrue(collage, collage.contains(expected.getValue(warmth)))
            assertTrue(hero, hero.contains(expected.getValue(warmth)))
        }
    }

    @Test
    fun `percentages are clamped to 0 to 100`() {
        assertTrue(CuratorNoteFormatter.format(note(StoryMode.COLLAGE_3, threeScales, harmony = 130), en).endsWith("100% harmony."))
        assertTrue(CuratorNoteFormatter.format(note(StoryMode.HERO, threeScales, harmony = -4), en).endsWith("(score 0%)."))
    }

    @Test
    fun `single input photo uses the singular count`() {
        val text = CuratorNoteFormatter.format(note(StoryMode.HERO, listOf(ShotScale.WIDE), total = 1), en)
        assertTrue(text, text.startsWith("Chosen from 1 photo in"))
    }
}

/** Mirrors res/values/strings.xml without Android resources. */
private class EnglishNoteStrings : NoteStrings {
    private val words = listOf("one", "two", "three", "four")
    override val listSeparator = ", "
    override fun photos(count: Int) = if (count == 1) "1 photo" else "$count photos"
    override fun frames(count: Int) = if (count == 1) "1 frame" else "$count frames"
    override fun distinctScales(count: Int): String {
        val word = words.getOrNull(count - 1) ?: count.toString()
        return if (count == 1) "across $word scale" else "across $word scales"
    }
    override fun scaleName(scale: ShotScale) = when (scale) {
        ShotScale.WIDE -> "Wide"
        ShotScale.MEDIUM -> "Portrait"
        ShotScale.DETAIL -> "Detail"
    }
    override fun tone(warmth: Warmth) = when (warmth) {
        Warmth.GOLDEN_WARM -> "warm golden-hour"
        Warmth.NEUTRAL -> "neutral"
        Warmth.COOL_BLUE -> "cool blue"
        Warmth.NIGHT_TUNGSTEN -> "tungsten night"
    }
    override fun percent(value: Int) = "$value%"
    override fun heroSentence(photos: String, tone: String, score: String) =
        "Chosen from $photos in $tone tones — this frame stands alone (score $score)."
    override fun collageSentence(
        photos: String,
        frames: String,
        scales: String,
        scaleList: String,
        tone: String,
        harmony: String,
    ) = "Chosen from $photos: $frames $scales ($scaleList) in $tone tones — $harmony harmony."
    override fun collageSentenceWithoutScales(photos: String, frames: String, tone: String, harmony: String) =
        "Chosen from $photos: $frames in $tone tones — $harmony harmony."
}

/** Mirrors res/values-tr/strings.xml without Android resources. */
private class TurkishNoteStrings : NoteStrings {
    override val listSeparator = ", "
    override fun photos(count: Int) = "$count fotoğraf"
    override fun frames(count: Int) = "$count kare"
    override fun distinctScales(count: Int) = if (count == 1) "tek ölçekte" else "$count farklı ölçekte"
    override fun scaleName(scale: ShotScale) = when (scale) {
        ShotScale.WIDE -> "Mekan"
        ShotScale.MEDIUM -> "Portre"
        ShotScale.DETAIL -> "Detay"
    }
    override fun tone(warmth: Warmth) = when (warmth) {
        Warmth.GOLDEN_WARM -> "sıcak altın saat"
        Warmth.NEUTRAL -> "nötr"
        Warmth.COOL_BLUE -> "soğuk mavi"
        Warmth.NIGHT_TUNGSTEN -> "gece tungsten"
    }
    override fun percent(value: Int) = "%$value"
    override fun heroSentence(photos: String, tone: String, score: String) =
        "$photos arasından $tone tonlarında seçilen bu kare tek başına öne çıkıyor (puan $score)."
    override fun collageSentence(
        photos: String,
        frames: String,
        scales: String,
        scaleList: String,
        tone: String,
        harmony: String,
    ) = "$photos arasından $scales ($scaleList) ve $tone tonlarında $harmony uyum sağlayan $frames kolaj için seçildi."
    override fun collageSentenceWithoutScales(photos: String, frames: String, tone: String, harmony: String) =
        "$photos arasından $tone tonlarında $harmony uyum sağlayan $frames kolaj için seçildi."
}
