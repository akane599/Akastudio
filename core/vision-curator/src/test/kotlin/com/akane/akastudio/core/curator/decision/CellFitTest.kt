package com.akane.akastudio.core.curator.decision

import com.akane.akastudio.core.color.api.NormRect
import com.akane.akastudio.core.curator.api.FaceObservation
import com.akane.akastudio.core.curator.api.PhotoAnalysis
import com.akane.akastudio.core.curator.api.SaliencyMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.exp
import kotlin.random.Random

class CellFitTest {
    // Cell aspects of the studio templates at the default 12 px gutter.
    private val studioAspects =
        listOf(
            0.554f,
            0.796f,
            0.613f,
            0.624f,
            0.621f,
            0.781f,
            1.061f,
            1.169f,
            1.017f,
            0.617f,
            2.35f,
            0.491f,
            0.296f,
            1.892f,
            1.121f,
            0.859f,
            1.615f,
            1.164f,
            0.5625f,
        )

    private fun face(
        l: Float,
        t: Float,
        r: Float,
        b: Float,
    ) = FaceObservation(NormRect(l, t, r, b), 1f, 1f, 1f)

    private fun map(
        w: Int,
        h: Int,
        value: (x: Double, y: Double) -> Double,
    ) = SaliencyMap(
        w,
        h,
        FloatArray(w * h) { i -> value((i % w + 0.5) / w, (i / w + 0.5) / h).toFloat() },
    )

    private fun uniform(
        w: Int,
        h: Int,
    ) = map(w, h) { _, _ -> 1.0 }

    private fun blob(
        w: Int,
        h: Int,
        cx: Double = 0.5,
        cy: Double = 0.5,
        sigmaCells: Double = 3.0,
    ) = map(w, h) { x, y ->
        val dx = (x - cx) * w
        val dy = (y - cy) * h
        exp(-(dx * dx + dy * dy) / (2 * sigmaCells * sigmaCells))
    }

    private fun photo(
        id: String,
        saliency: SaliencyMap,
        faces: List<FaceObservation> = emptyList(),
    ): PhotoAnalysis = Fixtures.analysis(id).copy(saliency = saliency, faces = faces)

    private fun single(
        vararg cells: CellShape,
        id: String = "L",
    ) = LayoutCatalog(listOf(LayoutSpec(id, cells.toList())))

    private fun fits(
        fitter: CellFit,
        p: PhotoAnalysis,
        aspect: Float,
    ) = fitter.fit(listOf(p), single(CellShape.Aspect(aspect))) != null

    /** 4:3 landscape, two faces whose expanded union is 0.778 wide; saliency on the faces' band. */
    private val twoFaceLandscape =
        photo(
            "pair",
            map(64, 48) { _, y -> if (y in 0.3..0.6) 1.0 else 0.0 },
            listOf(face(0.12f, 0.35f, 0.24f, 0.55f), face(0.76f, 0.35f, 0.88f, 0.55f)),
        )

    @Test
    fun twoFaceLandscape_isInfeasibleInNarrowCells_andFeasibleInWideOnes() {
        val fitter = CellFit()
        for (aspect in listOf(0.554f, 0.796f)) {
            assertFalse("narrow cell $aspect must cut a face", fits(fitter, twoFaceLandscape, aspect))
            assertFalse(CellFit.facesFit(twoFaceLandscape.faces, 64.0 / 48, aspect.toDouble()))
        }
        for (aspect in listOf(1.121f, 1.615f, 2.35f)) {
            assertTrue("wide cell $aspect keeps both faces", fits(fitter, twoFaceLandscape, aspect))
        }
    }

    @Test
    fun mapRoundingBound_rejectsAUnionThatOnlyFitsTheNominalAspect() {
        // Expanded union 0.838 wide. A true 4:3 photo at 1.121 keeps 0.841, but a 64x48 map may come from a photo up
        // to 64/47.5 wide, which keeps only 0.832.
        val p = photo("edge", map(64, 48) { _, y -> if (y in 0.3..0.6) 1.0 else 0.0 }, listOf(face(0.09f, 0.35f, 0.21f, 0.55f), face(0.79f, 0.35f, 0.91f, 0.55f)))
        assertTrue(CellFit.facesFit(p.faces, 4.0 / 3.0, 1.121))
        assertFalse(fits(CellFit(aspectMargin = 0f), p, 1.121f))
    }

    @Test
    fun aspectMargin_flipsFeasibilityNearTheBoundary() {
        // Expanded union 0.830 wide: inside the rounding bound (0.832), outside it with the 0.5% margin (0.828).
        val p = photo("edge2", map(64, 48) { _, y -> if (y in 0.3..0.6) 1.0 else 0.0 }, listOf(face(0.094f, 0.35f, 0.214f, 0.55f), face(0.786f, 0.35f, 0.906f, 0.55f)))
        assertTrue(fits(CellFit(aspectMargin = 0f), p, 1.121f))
        assertFalse(fits(CellFit(), p, 1.121f))
    }

    @Test
    fun landscapePairNearBothEdges_fitsTheWideMosaicCellOverTheWholeGutterRange() {
        // Two faces at opposite edges of a 4:3 photo, expanded union 0.853 wide. QUAD_MOSAIC's bottom cell spans
        // 1.158 (48 px gutter) to 1.172 (no gutter); its narrow end still keeps 0.855 under the rounding bound.
        val p = photo("pair-edges", map(64, 48) { _, y -> if (y in 0.3..0.6) 1.0 else 0.0 }, listOf(face(0.03f, 0.19f, 0.28f, 0.55f), face(0.73f, 0.31f, 0.855f, 0.51f)))
        val mosaicBottom = CellShape.Aspect(1.1687f, minWidthOverHeight = 1.1582f, maxWidthOverHeight = 1.1719f)
        assertTrue(CellFit().fit(listOf(p), single(mosaicBottom)) != null)
        assertNull(CellFit().fit(listOf(p), single(CellShape.Aspect(1.121f))))
    }

    @Test
    fun cellAspectRange_mustFitAtItsNarrowEnd() {
        // 3:4 portrait with one wide face (expanded width 0.713). QUAD_GRID at 12 px keeps at least 0.727 of the width
        // under the rounding bound and margin; at the 48 px gutter end (0.527) only 0.692.
        val p = photo("wide-face", blob(48, 64), listOf(face(0.19f, 0.3f, 0.81f, 0.6f)))
        val fitter = CellFit()
        assertTrue(fitter.fit(listOf(p), single(CellShape.Aspect(0.554f))) != null)
        assertNull(fitter.fit(listOf(p), single(CellShape.Aspect(0.554f, minWidthOverHeight = 0.527f, maxWidthOverHeight = 0.5625f))))
    }

    @Test
    fun imageAspectBounds_spanHalfAMapCellOnTheShortEdge() {
        val (lo, hi) = CellFit.imageAspectBounds(SaliencyMap(64, 48, FloatArray(64 * 48)))
        assertEquals(64.0 / 48.5, lo, 1e-12)
        assertEquals(64.0 / 47.5, hi, 1e-12)
        val (plo, phi) = CellFit.imageAspectBounds(SaliencyMap(48, 64, FloatArray(48 * 64)))
        assertEquals(47.5 / 64, plo, 1e-12)
        assertEquals(48.5 / 64, phi, 1e-12)
    }

    @Test
    fun noFacePhotoWithCentralSaliency_fitsEveryStudioAspect() {
        val fitter = CellFit()
        for ((w, h) in listOf(48 to 64, 64 to 48, 64 to 64, 36 to 64, 64 to 36)) {
            val p = photo("c$w-$h", blob(w, h))
            for (aspect in studioAspects) assertTrue("${w}x$h at $aspect", fits(fitter, p, aspect))
        }
    }

    @Test
    fun fullHeightCrop_cannotBeRejectedByTheShareFloor_wherever_theSaliencySitsVertically() {
        // 3:4 portrait at QUAD_GRID's 0.554: the crop keeps the full height and 0.739 of the width, so the share is
        // 0.739 for any saliency that is uniform across the width, bottom subject or salient ceiling alike. The
        // floor cannot steer such a crop away from a ceiling; that needs a saliency or solver change.
        val bottom = photo("bottom", map(48, 64) { _, y -> if (y >= 0.4) 1.0 else 0.0 })
        val ceiling = photo("ceiling", map(48, 64) { _, y -> if (y < 0.4) 1.0 else 0.0 })
        val fitter = CellFit()
        assertTrue(fits(fitter, bottom, 0.554f))
        assertTrue(fits(fitter, ceiling, 0.554f))
        val a = fitter.evaluate(bottom.saliency, bottom.faces, 0.554).subjectShare
        val b = fitter.evaluate(ceiling.saliency, ceiling.faces, 0.554).subjectShare
        assertEquals(0.554 / 0.75, a.toDouble(), 1e-4)
        assertEquals(a, b, 1e-6f)
    }

    @Test
    fun subjectShareFloor_rejectsACropThatLosesTheSubject() {
        // Three equal saliency strips (left edge, centre, right edge) of a 4:3 photo; a 0.554 cell keeps a 0.416-wide
        // window, which can hold only one strip: a third of the mass, below the 0.5 floor.
        val p = photo("strips", map(64, 48) { x, _ -> if (x < 0.1 || x in 0.45..0.55 || x > 0.9) 1.0 else 0.0 })
        val share = CellFit().evaluate(p.saliency, p.faces, 0.554).subjectShare
        assertTrue("share $share", share <= 0.34f)
        assertFalse(fits(CellFit(), p, 0.554f))
        assertTrue(fits(CellFit(subjectShareFloor = 0f), p, 0.554f))
    }

    @Test
    fun subjectShare_isMeasuredOverWindowsThatKeepTheFaces() {
        // Saliency on the far right, a face on the far left: the face-keeping window loses the salient strip.
        val p = photo("split", map(64, 48) { x, _ -> if (x > 0.85) 1.0 else 0.0 }, listOf(face(0.02f, 0.4f, 0.12f, 0.6f)))
        assertEquals(0f, CellFit().evaluate(p.saliency, p.faces, 0.554).subjectShare, 1e-6f)
        val noFace = p.copy(faces = emptyList())
        assertEquals(1f, CellFit().evaluate(noFace.saliency, noFace.faces, 0.554).subjectShare, 1e-6f)
    }

    @Test
    fun uncroppedCell_alwaysFits() {
        assertFalse(fits(CellFit(), twoFaceLandscape, 0.554f))
        val fit = CellFit().fit(listOf(twoFaceLandscape), single(CellShape.Uncropped, id = "MATTE"))
        assertEquals("MATTE", fit?.layoutId)
    }

    @Test
    fun noLayoutForTheCount_orNoPhotos_yieldsNull() {
        val catalog = single(CellShape.Aspect(1f), CellShape.Aspect(1f))
        val p = photo("a", blob(64, 64))
        assertNull(CellFit().fit(listOf(p), catalog))
        assertNull(CellFit().fit(listOf(p), LayoutCatalog.EMPTY))
        assertNull(CellFit().fit(emptyList(), catalog))
    }

    @Test
    fun anchorStaysInSlotZero_evenWhenSwappingWouldFit() {
        val portrait = photo("portrait", blob(48, 64))
        val catalog = single(CellShape.Aspect(0.6f), CellShape.Aspect(1.6f))
        assertNull("the anchor cannot move to the wide cell", CellFit().fit(listOf(twoFaceLandscape, portrait), catalog))
        assertEquals(listOf("portrait", "pair"), CellFit().fit(listOf(portrait, twoFaceLandscape), catalog)?.orderedIds)
    }

    @Test
    fun tailSlotsArePermutedToFindAFeasibleOrder() {
        val anchor = photo("anchor", blob(48, 64))
        val other = photo("other", blob(48, 64))
        val catalog = single(CellShape.Aspect(0.75f), CellShape.Aspect(0.6f), CellShape.Aspect(1.6f))
        val fit = CellFit().fit(listOf(anchor, twoFaceLandscape, other), catalog)
        assertEquals(listOf("anchor", "other", "pair"), fit?.orderedIds)
        assertEquals(6, CellFit.tailPermutations(4).size)
        assertTrue(CellFit.tailPermutations(4).all { it[0] == 0 })
    }

    @Test
    fun feasibleLayouts_areRankedByKeptArea_beforeCatalogueOrder() {
        val a = photo("a", blob(64, 48))
        val b = photo("b", blob(64, 48))
        val catalog =
            LayoutCatalog(
                listOf(
                    LayoutSpec("ASYM", listOf(CellShape.Aspect(0.859f), CellShape.Aspect(1.615f))),
                    LayoutSpec("CINE", listOf(CellShape.Aspect(1.121f), CellShape.Aspect(1.121f))),
                ),
            )
        assertEquals("CINE", CellFit().fit(listOf(a, b), catalog)?.layoutId)
    }

    @Test
    fun hero_prefersFullBleedForPortraits_andTheMatteForLandscapes() {
        val catalog =
            LayoutCatalog(
                listOf(
                    LayoutSpec("FULL", listOf(CellShape.Aspect(0.5625f))),
                    LayoutSpec("MATTE", listOf(CellShape.Uncropped)),
                ),
            )
        assertEquals("FULL", CellFit().fit(listOf(photo("p", uniform(48, 64))), catalog)?.layoutId)
        assertEquals("MATTE", CellFit().fit(listOf(photo("l", blob(64, 48))), catalog)?.layoutId)
    }

    @Test
    fun score_isTheMeanSubjectShareOfTheChosenAssignment() {
        val p = photo("u", uniform(48, 64))
        val fit = CellFit().fit(listOf(p), single(CellShape.Aspect(0.554f)))
        assertEquals(0.554f / 0.75f, requireNotNull(fit).score, 1e-4f)
    }

    @Test
    fun memoisedResults_equalUncachedResults_andReusedIdsWithNewDataAreReEvaluated() {
        val random = Random(42)
        val photos =
            (0 until 8).map { i ->
                val (w, h) = listOf(48 to 64, 64 to 48, 64 to 64)[random.nextInt(3)]
                val faces =
                    List(random.nextInt(3)) {
                        val x = random.nextFloat() * 0.8f
                        val y = random.nextFloat() * 0.8f
                        face(x, y, x + 0.05f + random.nextFloat() * 0.15f, y + 0.05f + random.nextFloat() * 0.15f)
                    }
                photo("p$i", blob(w, h, random.nextDouble(0.2, 0.8), random.nextDouble(0.2, 0.8), 4.0), faces)
            }
        val catalog =
            LayoutCatalog(
                listOf(
                    LayoutSpec("D1", listOf(CellShape.Aspect(1.121f), CellShape.Aspect(1.121f))),
                    LayoutSpec("D2", listOf(CellShape.Aspect(0.859f), CellShape.Aspect(1.615f))),
                    LayoutSpec("T1", listOf(CellShape.Aspect(1.017f), CellShape.Aspect(0.617f), CellShape.Aspect(0.617f))),
                    LayoutSpec("T2", listOf(CellShape.Aspect(2.35f), CellShape.Aspect(2.35f), CellShape.Aspect(2.35f))),
                    LayoutSpec("Q1", List(4) { CellShape.Aspect(0.554f) }),
                    LayoutSpec("Q2", listOf(0.621f, 0.781f, 1.061f, 1.169f).map { CellShape.Aspect(it) }),
                ),
            )
        val cached = CellFit()
        val uncached = CellFit(cacheCapacity = 0)
        val tiny = CellFit(cacheCapacity = 3)
        var nonNull = 0
        for (m in 2..4) {
            for (start in photos.indices) {
                val set = (0 until m).map { photos[(start + it * 3) % photos.size] }.distinctBy { it.candidateId }
                if (set.size != m) continue
                val expected = uncached.fit(set, catalog)
                if (expected != null) nonNull++
                repeat(2) {
                    assertEquals(expected, cached.fit(set, catalog))
                    assertEquals(expected, tiny.fit(set, catalog))
                }
            }
        }
        assertTrue("fixture should produce some feasible sets", nonNull > 0)

        val narrow = single(CellShape.Aspect(0.554f))
        val reused = photo("same-id", blob(64, 48))
        assertTrue(fits(cached, reused, 0.554f))
        val replaced = reused.copy(faces = twoFaceLandscape.faces)
        assertFalse("same id, new faces: must not hit the old entry", cached.fit(listOf(replaced), narrow) != null)
        val newMap = reused.copy(saliency = map(64, 48) { x, _ -> if (x < 0.1 || x in 0.45..0.55 || x > 0.9) 1.0 else 0.0 })
        assertFalse("same id, new saliency map: must not hit the old entry", cached.fit(listOf(newMap), narrow) != null)
    }
}
