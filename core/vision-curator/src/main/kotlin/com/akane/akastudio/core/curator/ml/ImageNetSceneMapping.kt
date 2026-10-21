package com.akane.akastudio.core.curator.ml

import com.akane.akastudio.core.color.api.SceneType
import java.util.Locale

/**
 * Maps ImageNet-1k classifier labels to a [SceneType] through a keyword table.
 *
 * Only the 5 highest-scoring labels count. Each label that exactly matches a keyword (case-insensitive)
 * adds its score to that keyword's scene; the scene with the highest summed score wins when the sum is at
 * least [MIN_SCORE], otherwise the mapping abstains with `null`. Ties resolve to the scene declared first
 * in [SceneType]. Night-street labels count toward [SceneType.NIGHT_CITY] only in a dark image
 * (mean OKLab L < [NIGHT_MEAN_L]); in a brighter one they count toward [SceneType.GENERAL].
 */
object ImageNetSceneMapping {

    /** Minimum summed score for a scene to win. */
    const val MIN_SCORE = 0.15f

    /** Mean OKLab L below which night-street labels mean [SceneType.NIGHT_CITY]. */
    const val NIGHT_MEAN_L = 0.3f

    /** Number of top labels considered. */
    const val TOP_LABELS = 5

    private val sceneKeywords: Map<String, SceneType> = buildMap {
        fun put(scene: SceneType, vararg labels: String) = labels.forEach { put(it, scene) }
        put(
            SceneType.BEACH_SEA,
            "seashore", "sandbar", "lakeside", "promontory", "boathouse",
            "catamaran", "speedboat", "yawl", "liner", "breakwater",
        )
        put(
            SceneType.CAFE_INTERIOR,
            "espresso", "cup", "coffee mug", "coffeepot", "restaurant", "plate", "dining table",
            "menu", "wine bottle", "bakery", "trifle", "pizza", "croissant",
        )
        put(
            SceneType.NATURE_FOREST,
            "valley", "alp", "volcano", "cliff", "geyser", "daisy", "mushroom", "hay", "maze", "rapeseed",
        )
        put(
            SceneType.ARCHITECTURE_AUTO,
            "church", "palace", "monastery", "castle", "dome", "triumphal arch", "library", "mosque",
            "bell cote", "sports car", "convertible", "cab", "limousine", "minivan", "jeep", "racer", "pickup",
        )
    }

    private val nightKeywords: Set<String> = setOf("streetcar", "traffic light", "trolleybus", "cinema", "scoreboard")

    /**
     * Returns the winning scene for [labels] (label name to score, any order) in an image whose mean
     * OKLab lightness is [meanL], or `null` when no scene reaches [MIN_SCORE].
     */
    fun map(labels: List<Pair<String, Float>>, meanL: Float): SceneType? {
        val totals = FloatArray(SceneType.entries.size)
        labels
            .filter { it.second.isFinite() && it.second > 0f }
            .sortedByDescending { it.second }
            .take(TOP_LABELS)
            .forEach { (label, score) ->
                sceneFor(normalize(label), meanL)?.let { totals[it.ordinal] += score }
            }
        var best: SceneType? = null
        var bestScore = 0f
        for (scene in SceneType.entries) {
            val total = totals[scene.ordinal]
            if (total >= MIN_SCORE && total > bestScore) {
                best = scene
                bestScore = total
            }
        }
        return best
    }

    private fun sceneFor(label: String, meanL: Float): SceneType? = when {
        label in nightKeywords -> if (meanL < NIGHT_MEAN_L) SceneType.NIGHT_CITY else SceneType.GENERAL
        else -> sceneKeywords[label]
    }

    private fun normalize(label: String): String = label.trim().lowercase(Locale.ROOT).replace('_', ' ')
}
