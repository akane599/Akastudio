package com.akane.akastudio.feature.studio.ui.note

import com.akane.akastudio.core.color.api.SceneType
import com.akane.akastudio.core.curator.api.CuratorNote
import com.akane.akastudio.core.curator.api.ShotScale
import com.akane.akastudio.core.curator.api.StoryMode
import com.akane.akastudio.core.curator.api.Warmth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

class CuratorNoteFormatterTest {

    private val en = XmlNoteStrings(qualifier = null, locale = Locale.ENGLISH)
    private val tr = XmlNoteStrings(qualifier = "tr", locale = Locale.forLanguageTag("tr"))

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

/**
 * [NoteStrings] read straight from the module's `res/values*` XML on the JVM and mapped exactly as
 * [ResourceNoteStrings] maps the compiled resources, so these tests check the shipped copy, not a hand copy.
 * The [qualifier] directory overlays `values`, as Android's resource fallback does.
 *
 * Supports what the note resources use: `<string>`, `<plurals>` and `<string-array>` holding plain text with
 * Android's quote and backslash escapes, and positional format arguments. Plural selection is "one" for 1 and
 * "other" otherwise, which is the CLDR cardinal rule for both English and Turkish.
 */
private class XmlNoteStrings(qualifier: String?, private val locale: Locale) : NoteStrings {
    private val strings = HashMap<String, String>()
    private val plurals = HashMap<String, Map<String, String>>()
    private val arrays = HashMap<String, List<String>>()

    init {
        load(File(RES_DIR, "values"))
        if (qualifier != null) load(File(RES_DIR, "values-$qualifier"))
    }

    private val countWords: List<String> = checkNotNull(arrays["note_count_words"]) { "missing note_count_words" }

    override val listSeparator: String = string("note_list_separator")

    override fun photos(count: Int) = quantity("note_photos", count, count)

    override fun frames(count: Int) = quantity("note_frames", count, count)

    override fun distinctScales(count: Int) =
        quantity("note_scales", count, countWords.getOrNull(count - 1) ?: count.toString())

    override fun scaleName(scale: ShotScale) = string(
        when (scale) {
            ShotScale.WIDE -> "shot_scale_wide"
            ShotScale.MEDIUM -> "shot_scale_medium"
            ShotScale.DETAIL -> "shot_scale_detail"
        },
    )

    override fun tone(warmth: Warmth) = string(
        when (warmth) {
            Warmth.GOLDEN_WARM -> "note_tone_golden_warm"
            Warmth.NEUTRAL -> "note_tone_neutral"
            Warmth.COOL_BLUE -> "note_tone_cool_blue"
            Warmth.NIGHT_TUNGSTEN -> "note_tone_night_tungsten"
        },
    )

    override fun percent(value: Int) = string("note_percent", value)

    override fun heroSentence(photos: String, tone: String, score: String) = string("note_hero", photos, tone, score)

    override fun collageSentence(
        photos: String,
        frames: String,
        scales: String,
        scaleList: String,
        tone: String,
        harmony: String,
    ) = string("note_collage", photos, frames, scales, scaleList, tone, harmony)

    override fun collageSentenceWithoutScales(photos: String, frames: String, tone: String, harmony: String) =
        string("note_collage_no_scales", photos, frames, tone, harmony)

    /** Like `Resources.getString`: the raw value without arguments, a formatted one with them. */
    private fun string(name: String, vararg args: Any): String {
        val template = checkNotNull(strings[name]) { "missing string $name" }
        return if (args.isEmpty()) template else String.format(locale, template, *args)
    }

    private fun quantity(name: String, count: Int, vararg args: Any): String {
        val items = checkNotNull(plurals[name]) { "missing plurals $name" }
        val template = checkNotNull(items[if (count == 1) "one" else "other"] ?: items["other"]) { "$name has no item for $count" }
        return String.format(locale, template, *args)
    }

    private fun load(dir: File) {
        val files = dir.listFiles { f -> f.name.endsWith(".xml") }.orEmpty().sortedBy { it.name }
        check(files.isNotEmpty()) { "no resource XML in ${dir.absolutePath}" }
        val builder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        for (file in files) {
            for (element in builder.parse(file).documentElement.childElements()) {
                val name = element.getAttribute("name")
                when (element.tagName) {
                    "string" -> strings[name] = valueOf(element)
                    "plurals" -> plurals[name] = element.childElements().associate { it.getAttribute("quantity") to valueOf(it) }
                    "string-array" -> arrays[name] = element.childElements().map(::valueOf)
                }
            }
        }
    }

    private companion object {
        /** Gradle runs unit tests with the module directory as the working directory. */
        val RES_DIR = File("src/main/res")

        fun Element.childElements(): List<Element> =
            (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>()

        /**
         * The value aapt2 compiles from [element]'s text: whitespace runs collapse to one space outside double
         * quotes, unquoted text is trimmed, the quotes are dropped, and backslash escapes are resolved.
         */
        fun valueOf(element: Element): String {
            check(element.childElements().isEmpty()) { "markup inside <${element.tagName}> is not supported" }
            val raw = element.textContent
            val out = StringBuilder()
            var quoted = false
            var i = 0
            while (i < raw.length) {
                val c = raw[i]
                when {
                    c == BACKSLASH && i + 1 < raw.length -> {
                        i++
                        out.append(
                            when (val escaped = raw[i]) {
                                'n' -> '\n'
                                't' -> '\t'
                                else -> escaped
                            },
                        )
                    }
                    c == '"' -> quoted = !quoted
                    c.isWhitespace() && !quoted -> if (out.isNotEmpty() && out.last() != ' ') out.append(' ')
                    else -> out.append(c)
                }
                i++
            }
            return if ('"' in raw) out.toString() else out.toString().trimEnd()
        }

        const val BACKSLASH = '\\'
    }
}
