package com.akane.akastudio.core.curator.decision

import com.akane.akastudio.core.color.api.OkLab
import com.akane.akastudio.core.color.api.PaletteSwatch
import com.akane.akastudio.core.color.math.ColorMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlin.math.exp
import kotlin.math.sqrt
import kotlin.random.Random

class PaletteHarmonyTest {

    private fun randomPalette(random: Random, size: Int = 5): List<PaletteSwatch> = List(size) {
        PaletteSwatch(
            OkLab(random.nextFloat(), random.nextFloat() * 0.6f - 0.3f, random.nextFloat() * 0.6f - 0.3f),
            0.02f + random.nextFloat(),
        )
    }

    private fun hued(hueDeg: Float, chroma: Float = 0.12f, weight: Float = 1f) =
        PaletteSwatch(ColorMath.okLchToOkLab(0.6f, chroma, hueDeg), weight)

    @Test
    fun identicalPalettes_haveZeroDistance() {
        val p = randomPalette(Random(1))
        assertEquals(0f, PaletteHarmony.emd(p, p), 1e-6f)
    }

    @Test
    fun emd_isSymmetric() {
        val random = Random(2)
        repeat(20) {
            val p = randomPalette(random)
            val q = randomPalette(random)
            assertEquals(PaletteHarmony.emd(p, q), PaletteHarmony.emd(q, p), 1e-6f)
        }
    }

    @Test
    fun oneColourPalettes_giveTheirEuclideanDistance() {
        val p = listOf(PaletteSwatch(OkLab(0.2f, 0.1f, -0.05f), 0.7f))
        val q = listOf(PaletteSwatch(OkLab(0.8f, -0.1f, 0.15f), 3f))
        val expected = sqrt(0.6 * 0.6 + 0.2 * 0.2 + 0.2 * 0.2).toFloat()
        assertEquals(expected, PaletteHarmony.emd(p, q), 1e-6f)
    }

    @Test
    fun handSolvedCase_movesOnlyTheSurplus() {
        // Two points 1.0 apart on L; p is 50/50, q is 25/75: optimal plan moves 0.25 of mass a distance of 1.0.
        val p = listOf(PaletteSwatch(OkLab(0f, 0f, 0f), 0.5f), PaletteSwatch(OkLab(1f, 0f, 0f), 0.5f))
        val q = listOf(PaletteSwatch(OkLab(0f, 0f, 0f), 1f), PaletteSwatch(OkLab(1f, 0f, 0f), 3f))
        assertEquals(0.25f, PaletteHarmony.emd(p, q), 1e-6f)
    }

    @Test
    fun emd_matchesExactVertexEnumerationOnFiftyRandomCases() {
        val random = Random(42)
        repeat(50) { case ->
            val p = randomPalette(random)
            val q = randomPalette(random)
            val expected = BruteForceTransport.minCost(p, q)
            assertEquals("case $case", expected, PaletteHarmony.emd(p, q).toDouble(), 1e-4)
        }
    }

    @Test
    fun emd_rejectsInvalidPalettes() {
        val ok = Fixtures.GREY
        assertThrows(IllegalArgumentException::class.java) { PaletteHarmony.emd(emptyList(), ok) }
        assertThrows(IllegalArgumentException::class.java) {
            PaletteHarmony.emd(listOf(PaletteSwatch(OkLab(0.5f, 0f, 0f), 0f)), ok)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PaletteHarmony.emd(listOf(PaletteSwatch(OkLab(0.5f, 0f, 0f), -1f)), ok)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PaletteHarmony.emd(listOf(PaletteSwatch(OkLab(0.5f, 0f, 0f), Float.NaN)), ok)
        }
    }

    @Test
    fun dominantHue_isHighestWeightChromaticSwatch_orNullWhenNeutral() {
        val palette = listOf(
            PaletteSwatch(OkLab(0.5f, 0f, 0f), 0.6f),       // heaviest but neutral
            hued(40f, weight = 0.3f),
            hued(200f, weight = 0.1f),
        )
        assertEquals(40f, PaletteHarmony.dominantHue(palette)!!, 1e-3f)
        assertNull(PaletteHarmony.dominantHue(listOf(PaletteSwatch(OkLab(0.5f, 0.02f, 0.02f), 1f))))
    }

    @Test
    fun wheelHarmony_pairTable() {
        // Exact boundaries on raw hues.
        assertEquals(1.0, PaletteHarmony.pairWheel(10f, 40f), 0.0)      // analogous, Δh = 30
        assertEquals(0.5, PaletteHarmony.pairWheel(10f, 41f), 0.0)      // Δh = 31: no relation
        assertEquals(0.9, PaletteHarmony.pairWheel(0f, 155f), 0.0)      // complementary, |Δh-180| = 25
        assertEquals(0.5, PaletteHarmony.pairWheel(0f, 154f), 0.0)      // |Δh-180| = 26
        assertEquals(0.8, PaletteHarmony.pairWheel(0f, 100f), 0.0)      // triadic, |Δh-120| = 20
        assertEquals(0.8, PaletteHarmony.pairWheel(0f, 140f), 0.0)      // triadic, |Δh-120| = 20
        assertEquals(0.5, PaletteHarmony.pairWheel(0f, 99f), 0.0)       // |Δh-120| = 21
        assertEquals(1.0, PaletteHarmony.pairWheel(null, 99f), 0.0)     // neutral
        // Through palettes (hues recovered from OKLab, so away from boundaries).
        fun pair(h1: Float, h2: Float) = PaletteHarmony.wheelHarmony(listOf(listOf(hued(h1)), listOf(hued(h2))))
        assertEquals(1.0f, pair(350f, 15f), 1e-6f)         // analogous across 0°, Δh = 25
        assertEquals(0.9f, pair(0f, 200f), 1e-6f)          // complementary, Δh = 160
        assertEquals(0.8f, pair(10f, 250f), 1e-6f)         // triadic, Δh = 120
        assertEquals(0.5f, pair(0f, 60f), 1e-6f)           // none
        assertEquals(1.0f, PaletteHarmony.wheelHarmony(listOf(listOf(hued(0f)), Fixtures.GREY)), 1e-6f) // neutral
        assertEquals(1.0f, PaletteHarmony.wheelHarmony(listOf(listOf(hued(0f)))), 1e-6f)            // no pairs
    }

    @Test
    fun wheelHarmony_isMeanOverPairs() {
        // Pairs: (0,20)=1.0 analogous, (0,185)=0.9 complementary, (20,185)=Δ165 -> 0.9. Mean = 2.8 / 3.
        val palettes = listOf(listOf(hued(0f)), listOf(hued(20f)), listOf(hued(185f)))
        assertEquals((2.8 / 3).toFloat(), PaletteHarmony.wheelHarmony(palettes), 1e-6f)
    }

    @Test
    fun colorScore_combinesEmdAndWheel() {
        val p = Fixtures.solid(0.4f, 0f, 0f)
        val q = Fixtures.solid(0.5f, 0f, 0f)      // EMD 0.1, both neutral (wheel 1)
        assertEquals((0.7 * exp(-1.0) + 0.3).toFloat(), PaletteHarmony.colorScore(listOf(p, q)), 1e-5f)
        assertEquals(1f, PaletteHarmony.colorScore(listOf(p)), 1e-6f)
        assertEquals(1f, PaletteHarmony.colorScore(listOf(p, p, p)), 1e-6f)
    }
}

/**
 * Slow, exact reference for the 5x5 transportation problem by vertex enumeration.
 *
 * Why it is exact: the feasible set {x >= 0, row sums = a, column sums = b} is a non-empty bounded polytope, so the
 * linear cost attains its minimum at a vertex. The constraint matrix has rank n + m - 1 and its bases are exactly the
 * spanning trees of the complete bipartite graph K(n,m) on rows and columns; every vertex is the unique solution
 * supported on some spanning tree (degenerate vertices on several). Solving each tree by leaf peeling and keeping
 * the cheapest non-negative solution therefore yields the LP optimum. K(5,5) has 5^4 * 5^4 = 390,625 spanning trees,
 * enumerated once as acyclic 9-edge subsets.
 */
private object BruteForceTransport {
    private const val N = 5
    private const val CELLS = N * N
    private const val TREE_EDGES = 2 * N - 1

    /** Each tree as TREE_EDGES (cell, leafNode) steps in peel order; nodes 0..4 are rows, 5..9 columns. */
    private val trees: List<IntArray> by lazy { enumerateTrees() }

    fun minCost(p: List<PaletteSwatch>, q: List<PaletteSwatch>): Double {
        require(p.size == N && q.size == N)
        val supply = PaletteHarmony.normalizedWeights(p)
        val demand = PaletteHarmony.normalizedWeights(q)
        val cost = DoubleArray(CELLS) { PaletteHarmony.groundDistance(p[it / N], q[it % N]) }
        val remaining = DoubleArray(2 * N)
        var best = Double.POSITIVE_INFINITY
        for (tree in trees) {
            for (i in 0 until N) {
                remaining[i] = supply[i]
                remaining[N + i] = demand[i]
            }
            var total = 0.0
            var feasible = true
            for (step in tree) {
                val cell = step shr 4
                val leaf = step and 0xF
                val other = if (leaf < N) N + cell % N else cell / N
                val flow = remaining[leaf]
                if (flow < -1e-12) {
                    feasible = false
                    break
                }
                remaining[leaf] = 0.0
                remaining[other] -= flow
                total += flow * cost[cell]
            }
            if (feasible && total < best) best = total
        }
        return best
    }

    private fun enumerateTrees(): List<IntArray> {
        val out = ArrayList<IntArray>(390_625)
        val chosen = IntArray(TREE_EDGES)
        fun recurse(start: Int, depth: Int, component: IntArray) {
            if (depth == TREE_EDGES) {
                out += peelOrder(chosen)
                return
            }
            for (cell in start until CELLS) {
                if (CELLS - cell < TREE_EDGES - depth) return
                val u = component[cell / N]
                val v = component[N + cell % N]
                if (u == v) continue
                val next = IntArray(2 * N) { if (component[it] == v) u else component[it] }
                chosen[depth] = cell
                recurse(cell + 1, depth + 1, next)
            }
        }
        recurse(0, 0, IntArray(2 * N) { it })
        check(out.size == 390_625) { "expected 390625 spanning trees of K(5,5), got ${out.size}" }
        return out
    }

    private fun peelOrder(cells: IntArray): IntArray {
        val alive = BooleanArray(cells.size) { true }
        val degree = IntArray(2 * N)
        for (cell in cells) {
            degree[cell / N]++
            degree[N + cell % N]++
        }
        val order = IntArray(cells.size)
        for (step in cells.indices) {
            var picked = -1
            var leaf = -1
            for (k in cells.indices) {
                if (!alive[k]) continue
                val row = cells[k] / N
                val col = N + cells[k] % N
                if (degree[row] == 1) { picked = k; leaf = row; break }
                if (degree[col] == 1) { picked = k; leaf = col; break }
            }
            check(picked >= 0) { "tree without a leaf" }
            alive[picked] = false
            degree[cells[picked] / N]--
            degree[N + cells[picked] % N]--
            order[step] = (cells[picked] shl 4) or leaf
        }
        return order
    }
}
