package com.akane.akastudio.studio

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.util.Half
import android.util.Log
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.akane.akastudio.AkaStudioApp
import com.akane.akastudio.MainActivity
import com.akane.akastudio.core.color.api.GradeParams
import com.akane.akastudio.core.color.api.HalfFloatImage
import com.akane.akastudio.core.color.api.HarmonizationParams
import com.akane.akastudio.core.color.api.LabStats
import com.akane.akastudio.core.color.api.NormRect
import com.akane.akastudio.core.color.api.Rect4f
import com.akane.akastudio.core.color.api.RenderScene
import com.akane.akastudio.core.color.api.SceneBackground
import com.akane.akastudio.core.color.api.SceneLayer
import com.akane.akastudio.core.color.math.ColorMath
import com.akane.akastudio.core.composer.api.ComposerPhoto
import com.akane.akastudio.core.composer.crop.SmartCropSolver
import com.akane.akastudio.core.curator.api.CurationOption
import com.akane.akastudio.core.curator.api.PhotoAnalysis
import com.akane.akastudio.core.curator.api.StoryMode
import com.akane.akastudio.core.curator.decision.DecisionEngine
import com.akane.akastudio.core.curator.engine.asPixelSource
import com.akane.akastudio.core.curator.metrics.ScreenCaptureDetector
import com.akane.akastudio.core.curator.ml.CuratorModelsFactory
import com.akane.akastudio.core.export.api.ExportFormat
import com.akane.akastudio.core.export.api.ExportResolution
import com.akane.akastudio.core.ingestion.api.IngestionRequest
import com.akane.akastudio.core.ingestion.createAnalysisDecoder
import com.akane.akastudio.core.ingestion.decode.FullResolutionDecoder
import com.akane.akastudio.core.ingestion.model.IngestionEvent
import com.akane.akastudio.core.ingestion.model.PhotoCandidate
import com.akane.akastudio.core.ingestion.model.PhotoSource
import com.akane.akastudio.core.ingestion.model.stableCandidateId
import com.akane.akastudio.feature.studio.mvi.SceneBuilder
import com.akane.akastudio.feature.studio.mvi.StudioIntent
import com.akane.akastudio.feature.studio.mvi.StudioPhase
import com.akane.akastudio.feature.studio.mvi.StudioViewModel
import com.akane.akastudio.feature.studio.ui.studioViewModelFactory
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Collections
import java.util.zip.ZipInputStream
import kotlin.concurrent.thread
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Manual diagnosis trace (skipped unless `qaTraceZip` is given): imports one zip through the real studio exactly as
 * the document picker's result does (`StudioIntent.ImportZip`), waits for Ready and dumps the whole decision chain:
 * ingestion (with skip reasons from a side ingestion of the same uri), every analysis keyed by archive entry name,
 * pairwise near-duplicate matrices, clusters, the per-mode alternatives, the scene's grades, offscreen renders that
 * isolate harmonization and the look, and the real PNG16 export, with per-cell colour statistics.
 *
 * Arguments: `qaTraceZip` (device path under /sdcard/Download/AkaStudioQA/), optional `qaTraceUserExport` (a PNG of
 * the user's own export to measure with the same cells). Output: `<external files>/curation-trace/<run>/`. The local
 * copy of the zip and the exported MediaStore row are deleted at the end. Production behaviour is not changed.
 */
@RunWith(AndroidJUnit4::class)
class CurationTraceDeviceTest {
    private val phaseLog = Collections.synchronizedList(mutableListOf<String>())

    @Volatile private var polling = true

    private val standaloneStore = ViewModelStore()

    @Test
    fun traceZipCuration() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Manual curation trace requires qaTraceZip", arguments.containsKey("qaTraceZip"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val zipPath = checkNotNull(arguments.getString("qaTraceZip"))
        require(zipPath.startsWith("/sdcard/Download/AkaStudioQA/")) { "the zip must stay under /sdcard/Download/AkaStudioQA/" }
        val userExportPath = arguments.getString("qaTraceUserExport")
        userExportPath?.let {
            require(it.startsWith("/sdcard/Download/AkaStudioQA/")) { "user export must be under /sdcard/Download/AkaStudioQA/" }
        }
        val run = "trace_${System.currentTimeMillis()}"
        val outDir = File(checkNotNull(context.getExternalFilesDir(null)), "curation-trace/$run").apply { check(mkdirs()) }
        val zipName = zipPath.substringAfterLast('/')
        val localZip = File(context.cacheDir, "curation-trace/$zipName").apply { parentFile?.mkdirs() }
        val root = JSONObject()
        val md = StringBuilder()
        val exports = mutableListOf<Uri>()
        var sideSession: com.akane.akastudio.core.ingestion.api.IngestionSession? = null
        var scenario: ActivityScenario<MainActivity>? = null
        val pid = Process.myPid()
        Log.i(TAG, "START run=$run pid=$pid zip=$zipPath out=$outDir")

        try {
            // region environment
            val pkg = context.packageManager.getPackageInfo(context.packageName, 0)
            root.put(
                "env",
                JSONObject()
                    .put("run", run)
                    .put("pid", pid)
                    .put("package", context.packageName)
                    .put("versionName", pkg.versionName)
                    .put("versionCode", pkg.longVersionCode)
                    .put("lastUpdateTime", pkg.lastUpdateTime)
                    .put("device", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} API ${android.os.Build.VERSION.SDK_INT}")
                    .put("zip", zipPath),
            )
            // endregion

            // region zip copy and entry list
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("cat $zipPath")).use { input ->
                localZip.outputStream().use { input.copyTo(it) }
            }
            check(localZip.length() > 1000) { "zip copy is empty (${localZip.length()} bytes)" }
            val zipUri = Uri.fromFile(localZip).toString()
            val entryNames =
                ZipInputStream(localZip.inputStream()).use { zip ->
                    generateSequence { zip.nextEntry }.filter { !it.isDirectory }.map { it.name }.toList()
                }
            val idToName = entryNames.associateBy { stableCandidateId(zipUri + "\u0000" + it) }
            Log.i(TAG, "zip ${localZip.length()} bytes, ${entryNames.size} entries")
            // endregion

            // region side ingestion (skip reasons are not kept in StudioState)
            val container = (context.applicationContext as AkaStudioApp).container
            val sideStart = SystemClock.elapsedRealtime()
            // Kept open until the end: the candidate probes read its extracted files.
            val session = container.newIngestionSession().also { sideSession = it }
            val events = runBlocking { session.ingest(listOf(IngestionRequest.ZipArchive(zipUri, zipName))).toList() }
            val sideMs = SystemClock.elapsedRealtime() - sideStart
            val sideCandidates = LinkedHashMap<String, PhotoCandidate>()
            val skipped = JSONArray()
            val failures = JSONArray()
            events.forEach { e ->
                when (e) {
                    is IngestionEvent.CandidateReady -> sideCandidates.putIfAbsent(e.candidate.id, e.candidate)
                    is IngestionEvent.Completed -> e.candidates.forEach { sideCandidates.putIfAbsent(it.id, it) }
                    is IngestionEvent.Skipped -> skipped.put(JSONObject().put("name", e.displayName).put("reason", e.reason.name))
                    is IngestionEvent.SourceFailed -> failures.put(JSONObject().put("name", e.displayName).put("error", e.error.toString()))
                    is IngestionEvent.Progress -> Unit
                }
            }
            val sideTelemetry = events.lastOrNull()?.let { telemetryOf(it) }
            // endregion

            // region studio run
            val resetOk = resetHwm()
            // The real MainActivity when it can resume; on a locked (dozing) phone the activity never resumes, so the
            // same real container and StudioViewModel run from a ViewModelStore instead (no UI, same pipeline).
            // qaTraceHeadless=true skips the activity (the phone may be in live use); the pipeline is the same.
            val headless = arguments.getString("qaTraceHeadless") == "true"
            // qaTraceExportOnly=true: no offscreen variant/solo renders and a filtered logcat; the real export stays.
            val exportOnly = arguments.getString("qaTraceExportOnly") == "true"
            scenario =
                if (headless) {
                    null
                } else {
                    runCatching {
                        ActivityScenario.launch<MainActivity>(
                            Intent(context, MainActivity::class.java),
                            ActivityOptions.makeBasic().setLaunchDisplayId(0).toBundle(),
                        )
                    }.onFailure { Log.w(TAG, "MainActivity did not resume; driving the view model from a ViewModelStore", it) }
                        .getOrNull()
                }
            val sc = scenario
            val model: StudioViewModel =
                if (sc != null) {
                    var result: StudioViewModel? = null
                    sc.onActivity {
                        result =
                            ViewModelProvider(
                                it,
                                studioViewModelFactory((it.application as AkaStudioApp).container),
                            )[StudioViewModel::class.java]
                    }
                    checkNotNull(result)
                } else {
                    var result: StudioViewModel? = null
                    instrumentation.runOnMainSync {
                        result = ViewModelProvider(standaloneStore, studioViewModelFactory(container))[StudioViewModel::class.java]
                    }
                    checkNotNull(result)
                }
            root.getJSONObject("env").put(
                "driver",
                if (sc !=
                    null
                ) {
                    "MainActivity"
                } else if (headless) {
                    "ViewModelStore (headless by request)"
                } else {
                    "ViewModelStore (activity did not resume)"
                },
            )

            fun send(intent: StudioIntent) = instrumentation.runOnMainSync { model.onIntent(intent) }

            val t0 = SystemClock.elapsedRealtime()
            val poller =
                thread(name = "AkaTracePoll", isDaemon = true) {
                    var last: StudioPhase? = null
                    while (polling) {
                        val p = model.state.value.phase
                        if (p != last) {
                            val hwm = status()["VmHWM"] ?: -1
                            phaseLog += "${SystemClock.elapsedRealtime() - t0}\t${p.javaClass.simpleName}\t$hwm"
                            last = p
                        }
                        SystemClock.sleep(20)
                    }
                }
            send(StudioIntent.ImportZip(zipUri, zipName))
            awaitCondition("studio Ready", CURATION_TIMEOUT_MS) {
                val s = model.state.value
                if (s.phase == StudioPhase.Empty && s.error != null) throw AssertionError("Studio returned to Empty: ${s.error}")
                s.phase == StudioPhase.Ready
            }
            val readyMs = SystemClock.elapsedRealtime() - t0
            val hwmReady = status()["VmHWM"] ?: -1
            SystemClock.sleep(500)
            val state = model.state.value
            val curation = checkNotNull(state.curation) { "Ready without a curation" }
            checkNotNull(state.scene) { "Ready without a scene" }
            // endregion

            fun nameOf(id: String) =
                idToName[id] ?: sideCandidates[id]?.source?.displayName
                    ?: state.selection.slots.values
                        .firstOrNull { it.id == id }
                        ?.source
                        ?.displayName
                    ?: id.take(12)

            // region ingestion section
            val accepted = JSONArray()
            sideCandidates.values.sortedBy { nameOf(it.id) }.forEach { c ->
                val m = c.metadata
                accepted.put(
                    JSONObject()
                        .put("id", c.id)
                        .put("name", nameOf(c.id))
                        .put("entry", (c.source as? PhotoSource.ExtractedFile)?.archiveEntryName)
                        .put("format", c.format.name)
                        .put("byteSize", c.byteSize)
                        .put("width", c.widthPx)
                        .put("height", c.heightPx)
                        .put("captureEpochMillis", m.captureEpochMillis)
                        .put("captureHasUtcOffset", m.captureHasUtcOffset)
                        .put("colorSpace", m.colorSpace.name)
                        .put("orientation", m.orientation.name)
                        .put("gainMap", m.hasUltraHdrGainMap)
                        .put("focal35", m.focalLength35mm)
                        .put("fNumber", m.fNumber)
                        .put("exposureS", m.exposureTimeSeconds)
                        .put("iso", m.iso)
                        .put("analyzed", c.id in curation.analyses),
                )
            }
            val idsMatch =
                curation.analyses.keys.all { it in sideCandidates.keys } && sideCandidates.keys.all { it in curation.analyses.keys }
            root.put(
                "ingestion",
                JSONObject()
                    .put("zipEntries", JSONArray(entryNames))
                    .put("sideIngestionMs", sideMs)
                    .put("sideTelemetry", sideTelemetry)
                    .put("accepted", accepted)
                    .put("skipped", skipped)
                    .put("sourceFailed", failures)
                    .put("analysesCount", curation.analyses.size)
                    .put("sideIdsEqualAnalysisIds", idsMatch)
                    .put(
                        "entriesNotAnalyzed",
                        JSONArray(
                            entryNames.filter { n ->
                                idToName.entries.none {
                                    it.value == n &&
                                        it.key in curation.analyses
                                }
                            },
                        ),
                    ).put("telemetryUi", state.telemetry.toString()),
            )
            // endregion

            // region analyses
            val analyses = curation.analyses.values.sortedBy { nameOf(it.candidateId) }
            val clusterOf = curation.clusters.flatMap { c -> c.memberIds.map { it to c.id } }.toMap()
            val analysesJson = JSONObject()
            analyses.forEach { a -> analysesJson.put(nameOf(a.candidateId), analysisJson(a, clusterOf[a.candidateId])) }
            root.put("analyses", analysesJson)
            // endregion

            // region pairwise
            val names = analyses.map { nameOf(it.candidateId) }
            val n = analyses.size
            val cos = Array(n) { i -> FloatArray(n) { j -> cosine(analyses[i].embedding, analyses[j].embedding) } }
            val ph = Array(n) { i -> IntArray(n) { j -> java.lang.Long.bitCount(analyses[i].pHash xor analyses[j].pHash) } }
            val dh = Array(n) { i -> IntArray(n) { j -> java.lang.Long.bitCount(analyses[i].dHash xor analyses[j].dHash) } }
            val flagged = JSONArray()
            for (i in 0 until n) {
                for (j in i + 1 until n) {
                    val byCos = cos[i][j] > 0.82f
                    val byP = ph[i][j] <= 10
                    val byD = dh[i][j] <= 8
                    if (byCos || byP || byD) {
                        flagged.put(
                            JSONObject()
                                .put(
                                    "a",
                                    names[i],
                                ).put("b", names[j])
                                .put("cosine", d(cos[i][j]))
                                .put("pHash", ph[i][j])
                                .put("dHash", dh[i][j])
                                .put("byCosine", byCos)
                                .put("byPHash", byP)
                                .put("byDHash", byD)
                                .put("bothUnrejected", analyses[i].rejectReason == null && analyses[j].rejectReason == null),
                        )
                    }
                }
            }

            fun pairOf(
                a: String,
                b: String,
            ): JSONObject? {
                val i = names.indexOf(a)
                val j = names.indexOf(b)
                if (i < 0 || j < 0) return null
                return JSONObject()
                    .put("a", a)
                    .put("b", b)
                    .put("cosine", d(cos[i][j]))
                    .put("pHash", ph[i][j])
                    .put("dHash", dh[i][j])
            }
            root.put(
                "pairwise",
                JSONObject()
                    .put("names", JSONArray(names))
                    .put("cosine", JSONArray(cos.map { row -> JSONArray(row.map { d(it) }) }))
                    .put("pHashHamming", JSONArray(ph.map { JSONArray(it.toList()) }))
                    .put("dHashHamming", JSONArray(dh.map { JSONArray(it.toList()) }))
                    .put("wouldCluster", flagged)
                    .put("heicVsJpg_210025", pairOf("20261017_210025.heic", "20261017_210025.jpg") ?: JSONObject.NULL),
            )
            // endregion

            // region decision
            fun optionJson(o: CurationOption) =
                JSONObject()
                    .put("mode", o.mode.name)
                    .put("photos", JSONArray(o.photoIds.map(::nameOf)))
                    .put("score", d(o.score))
                    .put("layoutId", o.layoutId ?: JSONObject.NULL)
                    .put(
                        "synergy",
                        o.synergy?.let {
                            JSONObject()
                                .put(
                                    "color",
                                    d(it.color),
                                ).put("rhythm", d(it.rhythm))
                                .put("context", d(it.context))
                                .put("psi", d(it.psi))
                        }
                            ?: JSONObject.NULL,
                    )
            val alternatives = JSONObject()
            curation.alternatives.toSortedMap().forEach { (mode, o) -> alternatives.put(mode.name, optionJson(o)) }
            root.put(
                "decision",
                JSONObject()
                    .put(
                        "clusters",
                        JSONArray(
                            curation.clusters.map { c ->
                                JSONObject()
                                    .put(
                                        "id",
                                        c.id,
                                    ).put("champion", nameOf(c.championId))
                                    .put("members", JSONArray(c.memberIds.map(::nameOf)))
                                    .put("championQ", d(curation.analyses[c.championId]?.quality ?: Float.NaN))
                            },
                        ),
                    ).put(
                        "rejected",
                        JSONArray(
                            curation.rejectedIds.map {
                                JSONObject().put("name", nameOf(it)).put("reason", curation.analyses[it]?.rejectReason?.name)
                            },
                        ),
                    ).put("alternatives", alternatives)
                    .put("chosen", optionJson(curation.chosen))
                    .put("note", curation.note.toString())
                    .put("suggestedLook", curation.suggestedLook.name)
                    .put("aiRecommendedMode", state.aiRecommendedMode?.name)
                    .put("selectionMode", state.selection.mode?.name)
                    .put("selection", JSONArray(state.selection.photoIds.map(::nameOf)))
                    .put("template", state.template?.name)
                    .put("layout", state.layout.toString())
                    .put("overrides", state.overrides.toString())
                    .put(
                        "captureTimes",
                        JSONObject(curation.captureTimes.mapKeys { nameOf(it.key) }.mapValues { it.value ?: JSONObject.NULL }),
                    ),
            )
            // endregion

            // region per-story trace: scene, renders, solo renders, real export
            val renderer = container.renderer
            val identityGrade = GradeParams(lookIntensity = 0f, harmonization = HarmonizationParams.IDENTITY)
            val userExport: Triple<Int, Int, IntArray>? =
                userExportPath?.let { path ->
                    val bytes =
                        ParcelFileDescriptor
                            .AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("cat $path"))
                            .use { it.readBytes() }
                    if (bytes.size > 1000) decodeArgb(bytes) else null
                }

            fun traceStory(prefix: String): JSONObject {
                val st = model.state.value
                val sc0 = checkNotNull(st.scene) { "$prefix: no scene" }
                val layerNames = st.selection.photoIds.map(::nameOf)
                val out = JSONObject()
                out.put("mode", st.selection.mode?.name)
                out.put("photos", JSONArray(layerNames))
                out.put("template", st.template?.name)
                out.put(
                    "scene",
                    JSONObject()
                        .put("canvas", "${sc0.canvasWidth}x${sc0.canvasHeight}")
                        .put("background", sc0.background.toString())
                        .put("overlays", sc0.overlays.size)
                        .put(
                            "layers",
                            JSONArray(
                                sc0.layers.mapIndexed { i, l ->
                                    JSONObject()
                                        .put("slot", i)
                                        .put("photo", layerNames.getOrNull(i))
                                        .put("imageKey", l.imageKey)
                                        .put("dst", rectJson(l.dst))
                                        .put("crop", normJson(l.crop))
                                        .put("rotationDegrees", d(l.rotationDegrees))
                                        .put("cornerRadiusPx", d(l.cornerRadiusPx))
                                        .put("grade", gradeJson(l.grade))
                                },
                            ),
                        ).put("gradeUi", st.grade.toString())
                        .put("layout", st.layout.toString())
                        .put("export", st.export.toString()),
                )
                val cells = sc0.layers.map { it.dst }
                val stats = JSONObject()

                fun variant(
                    name: String,
                    map: (GradeParams) -> GradeParams,
                ) {
                    val s = sc0.copy(layers = sc0.layers.map { it.copy(grade = map(it.grade)) })
                    val argb = renderArgb(runBlocking { renderer.renderOffscreen(s) })
                    savePng(argb, sc0.canvasWidth, sc0.canvasHeight, File(outDir, "${prefix}_render_$name.png"))
                    stats.put(name, cellStats(argb, sc0.canvasWidth, sc0.canvasHeight, cells, layerNames))
                }
                if (!exportOnly) {
                    variant("a_identity") { identityGrade }
                    variant("b_harmonization_only") { it.copy(lookIntensity = 0f) }
                    variant("c_look_only") { it.copy(harmonization = HarmonizationParams.IDENTITY) }
                    variant("d_combined") { it }
                }

                // Each chosen photo alone, identity grade: its scene crop, and the full frame, fitted into the canvas.
                val solo = JSONObject()
                if (!exportOnly) {
                    sc0.layers.forEachIndexed { i, layer ->
                        val cand = st.selection.slots[i]
                        val w = cand?.widthPx ?: 1
                        val h = cand?.heightPx ?: 1
                        for ((tag, crop, rot) in listOf(
                            Triple("crop", layer.crop, layer.rotationDegrees),
                            Triple("full", NormRect.FULL, 0f),
                        )) {
                            val aspect = (crop.width * w) / (crop.height * h)
                            val dst = fit(aspect, sc0.canvasWidth.toFloat(), sc0.canvasHeight.toFloat())
                            val s =
                                RenderScene(
                                    sc0.canvasWidth,
                                    sc0.canvasHeight,
                                    SceneBackground.Solid(BLACK),
                                    listOf(
                                        SceneLayer(
                                            imageKey = layer.imageKey,
                                            dst = dst,
                                            crop = crop,
                                            rotationDegrees = rot,
                                            grade = identityGrade,
                                        ),
                                    ),
                                )
                            val argb = renderArgb(runBlocking { renderer.renderOffscreen(s) })
                            val file = "${prefix}_solo_${i}_${tag}_${layerNames[i].replace('.', '_')}.png"
                            savePng(argb, sc0.canvasWidth, sc0.canvasHeight, File(outDir, file))
                            solo.put(file, cellStats(argb, sc0.canvasWidth, sc0.canvasHeight, listOf(dst), listOf(layerNames[i])))
                        }
                    }
                }
                out.put("soloRenders", solo)

                // The real export: PNG16 at 1080, read back from MediaStore, then its row is deleted.
                val before =
                    model.state.value.export.lastResult
                        ?.uri
                send(StudioIntent.SetExportFormat(ExportFormat.PNG_16))
                send(StudioIntent.SetExportResolution(ExportResolution.HD_1080))
                val exportStart = SystemClock.elapsedRealtime()
                send(StudioIntent.Export(share = false))
                var written: Uri? = null
                awaitCondition("$prefix export", EXPORT_TIMEOUT_MS) {
                    val s = model.state.value
                    if (s.error != null) throw AssertionError("$prefix export failed: ${s.error}")
                    val last = s.export.lastResult
                    if (last != null && last.uri != before && s.export.progress == null && s.phase == StudioPhase.Ready) {
                        written = Uri.parse(last.uri)
                    }
                    written != null
                }
                val uri = checkNotNull(written)
                exports += uri
                out.put("exportMs", SystemClock.elapsedRealtime() - exportStart)
                out.put(
                    "exportResult",
                    model.state.value.export.lastResult
                        .toString(),
                )
                val exportBytes = checkNotNull(context.contentResolver.openInputStream(uri)).use { it.readBytes() }
                File(outDir, "${prefix}_export_app_PNG16.png").writeBytes(exportBytes)
                runCatching { context.contentResolver.delete(uri, null, null) }.onSuccess { exports -= uri }
                val exportArgb = decodeArgb(exportBytes)
                stats.put("e_export_app", cellStats(exportArgb.third, exportArgb.first, exportArgb.second, cells, layerNames))
                userExport?.let { u ->
                    stats.put(
                        "f_export_user",
                        cellStats(u.third, u.first, u.second, cells, layerNames).put("size", "${u.first}x${u.second}"),
                    )
                }
                out.put("cellStats", stats)
                return out
            }

            root.put("story_ai", traceStory("ai"))
            // endregion

            // region what-if decisions (DecisionEngine is pure; the no-NIMA formula mirrors PhotoAnalyzer's)
            val analysisList = curation.analyses.values.toList()
            val baseline = DecisionEngine.decide(analysisList, curation.captureTimes)
            val noNima =
                analysisList.map { a ->
                    val aes = (0.6f * a.composition.coerceIn(0f, 1f) + 0.4f * colorfulness(a)).coerceIn(0f, 1f)
                    a.copy(aesthetic = aes, quality = (0.45f * a.technical.total + 0.55f * aes).coerceIn(0f, 1f))
                }
            val noNimaDecision = DecisionEngine.decide(noNima, curation.captureTimes)

            fun decisionJson(dec: com.akane.akastudio.core.curator.decision.Decision) =
                JSONObject()
                    .put("chosen", optionJson(dec.chosen))
                    .put("alternatives", JSONObject().apply { dec.alternatives.forEach { (m, o) -> put(m.name, optionJson(o)) } })
                    .put("champions", JSONArray(dec.champions.map(::nameOf)))
                    .put("clusters", JSONArray(dec.clusters.map { c -> JSONArray(c.memberIds.map(::nameOf)) }))
            root.put(
                "whatIf",
                JSONObject()
                    .put("baselineRedecide", decisionJson(baseline))
                    .put("noNimaAesthetic", decisionJson(noNimaDecision))
                    .put(
                        "noNimaQ",
                        JSONObject().apply {
                            noNima.sortedByDescending { it.quality }.forEach { a ->
                                put(
                                    nameOf(a.candidateId),
                                    JSONObject().put("Q", d(a.quality)).put("A", d(a.aesthetic)).put("colorfulness", d(colorfulness(a))),
                                )
                            }
                        },
                    ).put(
                        "inferredNimaMean",
                        JSONObject().apply {
                            analysisList.forEach { a ->
                                // A = 0.7 * clamp((m - 3) / 4) + 0.3 * c  =>  m = 3 + 4 * (A - 0.3 c) / 0.7 (clamped values stay ambiguous)
                                put(nameOf(a.candidateId), d(3f + 4f * (a.aesthetic - 0.3f * a.composition.coerceIn(0f, 1f)) / 0.7f))
                            }
                        },
                    ),
            )
            // endregion

            // region forced mode (reproduces a user-chosen or differently-decided layout)
            arguments.getString("qaTraceForceMode")?.let { modeName ->
                val mode = StoryMode.valueOf(modeName)
                send(StudioIntent.ForceMode(mode))
                awaitCondition("forced $mode Ready", CURATION_TIMEOUT_MS) {
                    val s = model.state.value
                    if (s.error != null) throw AssertionError("force $mode failed: ${s.error}")
                    s.phase == StudioPhase.Ready && s.selection.mode == mode && s.scene?.layers?.size == mode.photoCount
                }
                // Optional exact reproduction of an earlier layout: qaTraceForceTemplate (StoryTemplate name) and
                // qaTraceForcePhotos (comma-separated archive entry names, slot order), applied through the UI intents.
                arguments.getString("qaTraceForceTemplate")?.let { name ->
                    val template =
                        com.akane.akastudio.core.composer.api.StoryTemplate
                            .valueOf(name)
                    send(StudioIntent.SelectTemplate(template))
                    awaitCondition("template $name Ready", CURATION_TIMEOUT_MS) {
                        val s = model.state.value
                        if (s.error != null) throw AssertionError("template $name failed: ${s.error}")
                        s.phase == StudioPhase.Ready && s.template == template
                    }
                }
                arguments.getString("qaTraceForcePhotos")?.split(',')?.map { it.trim() }?.forEachIndexed { slot, entry ->
                    val id = checkNotNull(idToName.entries.firstOrNull { it.value == entry }?.key) { "no candidate for $entry" }
                    if (model.state.value.selection.photoIds
                            .getOrNull(slot) != id
                    ) {
                        send(StudioIntent.SwapCandidate(slot, id))
                        awaitCondition("slot $slot = $entry Ready", CURATION_TIMEOUT_MS) {
                            val s = model.state.value
                            if (s.error != null) throw AssertionError("swap $slot $entry failed: ${s.error}")
                            s.phase == StudioPhase.Ready && s.selection.photoIds.getOrNull(slot) == id &&
                                s.scene?.layers?.size == mode.photoCount
                        }
                    }
                }
                SystemClock.sleep(300)
                val forced = model.state.value
                root.put(
                    "forced",
                    JSONObject()
                        .put("requested", mode.name)
                        .put("chosen", forced.curation?.chosen?.let(::optionJson) ?: JSONObject.NULL)
                        .put("story", traceStory("forced_${mode.name}")),
                )
            }
            // endregion

            // region all candidates: decoder output per photo, and the user's export cells matched against each one
            if (arguments.getString("qaTraceAllCandidates") == "true") {
                val st = model.state.value
                val sc0 = checkNotNull(st.scene)
                val anchorId = st.selection.photoIds[0]
                val anchorCand = checkNotNull(st.selection.slots[0])
                val anchorAnalysis = checkNotNull(curation.analyses[anchorId])
                val anchorPhoto =
                    ComposerPhoto(
                        id = anchorId,
                        imageKey = sc0.layers[0].imageKey,
                        widthPx = anchorCand.widthPx,
                        heightPx = anchorCand.heightPx,
                        saliency = anchorAnalysis.saliency,
                        faces = anchorAnalysis.faces,
                        palette = anchorAnalysis.palette,
                        horizonTiltDegrees = sc0.layers[0].rotationDegrees,
                        metadata = anchorCand.metadata,
                    )
                val fullDecoder = FullResolutionDecoder(context.contentResolver)
                val probes = JSONObject()
                val matchCells = listOf(1, 3)
                val matches = matchCells.associateWith { mutableListOf<Pair<String, Double>>() }
                val identity = GradeParams(lookIntensity = 0f, harmonization = HarmonizationParams.IDENTITY)
                sideCandidates.values.sortedBy { nameOf(it.id) }.forEach { c ->
                    val name = nameOf(c.id)
                    val p = JSONObject()
                    val file = (c.source as? PhotoSource.ExtractedFile)?.path
                    // 1. What the platform reports and returns without a target colour space.
                    if (file != null) {
                        runCatching {
                            var header = ""
                            val raw =
                                ImageDecoder.decodeBitmap(ImageDecoder.createSource(File(file))) { decoder, info, _ ->
                                    header = "mime=${info.mimeType} cs=${info.colorSpace?.name} size=${info.size}"
                                    decoder.setTargetSize((info.size.width / 12).coerceAtLeast(1), (info.size.height / 12).coerceAtLeast(1))
                                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                                }
                            p.put("header", header)
                            p
                                .put(
                                    "rawConfig",
                                    raw.config?.name,
                                ).put("rawColorSpace", raw.colorSpace?.name)
                                .put("rawHasGainmap", raw.hasGainmap())
                            p.put("rawOkL_viaColorConvert", d(meanOkL(raw)))
                            raw.recycle()
                        }.onFailure { p.put("rawError", it.toString()) }
                    }
                    // 2. The production full-resolution decode (small edge): what the renderer receives.
                    runCatching {
                        val decoded = runBlocking { fullDecoder.decode(c, 512) }
                        val b = decoded.bitmap
                        p
                            .put(
                                "prodConfig",
                                b.config?.name,
                            ).put("prodColorSpace", b.colorSpace?.name)
                            .put("prodHasGainmap", decoded.hasGainMap)
                        p.put("prodOkL_treatingComponentsAsTagged", d(meanOkL(b)))
                        b.recycle()
                    }.onFailure { p.put("prodError", it.toString()) }
                    p.put("analysisLabMeanL", d(curation.analyses[c.id]?.labStats?.meanL ?: Float.NaN))
                    // 3. Through the production preparer: identity-grade full frame, and the user's quad cells.
                    runCatching {
                        val prepared = runBlocking { container.preparer.prepare(listOf(c), curation.analyses, hero = false) }.single()
                        try {
                            val aspect = c.widthPx.toFloat() / c.heightPx
                            val dst = fit(aspect, sc0.canvasWidth.toFloat(), sc0.canvasHeight.toFloat())
                            val solo =
                                RenderScene(
                                    sc0.canvasWidth,
                                    sc0.canvasHeight,
                                    SceneBackground.Solid(BLACK),
                                    listOf(SceneLayer(imageKey = prepared.imageKey, dst = dst, crop = NormRect.FULL, grade = identity)),
                                )
                            val soloArgb = renderArgb(runBlocking { renderer.renderOffscreen(solo) })
                            p.put(
                                "renderIdentityFull",
                                cellStats(soloArgb, sc0.canvasWidth, sc0.canvasHeight, listOf(dst), listOf(name)).getJSONObject("cell0"),
                            )
                            if (userExport != null && st.template == com.akane.akastudio.core.composer.api.StoryTemplate.QUAD_GRID) {
                                val quad =
                                    SceneBuilder.build(
                                        st,
                                        listOf(anchorPhoto, prepared, anchorPhoto, prepared),
                                        container.harmonizer,
                                        container.composer,
                                    )
                                val qArgb = renderArgb(runBlocking { renderer.renderOffscreen(quad) })
                                matchCells.forEach { cell ->
                                    val diff = meanAbsDiff(userExport.third, qArgb, sc0.canvasWidth, quad.layers[cell].dst)
                                    matches.getValue(cell) += name to diff
                                    p.put("userCell${cell}MeanAbsDiff", d(diff))
                                }
                            }
                        } finally {
                            runBlocking { container.preparer.release(listOf(c.id)) }
                        }
                    }.onFailure { p.put("renderError", it.toString()) }
                    probes.put(name, p)
                    Log.i(TAG, "probe $name $p")
                }
                root.put("candidateProbes", probes)
                root.put(
                    "userCellMatches",
                    JSONObject().apply {
                        matches.forEach { (cell, list) ->
                            put(
                                "cell$cell",
                                JSONArray(
                                    list
                                        .sortedBy {
                                            it.second
                                        }.take(5)
                                        .map { JSONObject().put("photo", it.first).put("meanAbsDiff", d(it.second)) },
                                ),
                            )
                        }
                    },
                )
            }
            // endregion

            // region timing
            polling = false
            poller.join(1000)
            root.put(
                "timing",
                JSONObject()
                    .put("phases", JSONArray(synchronized(phaseLog) { phaseLog.toList() }))
                    .put("importToReadyMs", readyMs)
                    .put("hwmResetAccepted", resetOk)
                    .put("vmHwmAtReadyKb", hwmReady)
                    .put("vmHwmEndKb", status()["VmHWM"] ?: -1),
            )
            // endregion

            File(outDir, "trace.json").writeText(root.toString(2))
            md.append(summary(root))
            File(outDir, "summary.md").writeText(md.toString())
            Log.i(TAG, "PASS run=$run out=$outDir")
        } finally {
            polling = false
            if (!File(outDir, "trace.json").exists()) runCatching { File(outDir, "trace_partial.json").writeText(root.toString(2)) }
            runCatching {
                val proc = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-v", "threadtime", "--pid=$pid"))
                if (arguments.getString("qaTraceExportOnly") == "true") {
                    // Only this harness's lines and warnings/errors: the full pid log is several MB.
                    val keep = Regex("""^\S+ \S+ +\d+ +\d+ [WEF] |AkaTrace|NimaScorer|Studio""")
                    File(outDir, "logcat_filtered.txt").writeText(
                        proc.inputStream
                            .bufferedReader()
                            .lineSequence()
                            .filter {
                                keep.containsMatchIn(it)
                            }.joinToString("\n"),
                    )
                } else {
                    File(outDir, "logcat_pid.txt").writeBytes(proc.inputStream.readBytes())
                }
                proc.waitFor()
            }
            try {
                scenario?.close()
                instrumentation.runOnMainSync { standaloneStore.clear() }
            } finally {
                exports.forEach { runCatching { context.contentResolver.delete(it, null, null) } }
                localZip.delete()
                runCatching { sideSession?.close() }
            }
        }
    }

    /**
     * Decoder-only probe (skipped unless `qaDecoderProbeZip`): for each table shot in the zip, the production
     * FullResolutionDecoder at 3072 and 1536, and raw ImageDecoder decodes without a target colour space/size and
     * with only a linear target, each reported as bitmap facts plus the mean of its pixels converted to sRGB 8-bit.
     */
    @Test
    fun decoderProbe() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Manual decoder probe requires qaDecoderProbeZip", arguments.containsKey("qaDecoderProbeZip"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val zipPath = checkNotNull(arguments.getString("qaDecoderProbeZip"))
        require(zipPath.startsWith("/sdcard/Download/AkaStudioQA/")) { "the zip must stay under /sdcard/Download/AkaStudioQA/" }
        val run = "decoder_${System.currentTimeMillis()}"
        val outDir = File(checkNotNull(context.getExternalFilesDir(null)), "curation-trace/$run").apply { check(mkdirs()) }
        val work =
            File(context.cacheDir, "decoder-probe").apply {
                deleteRecursively()
                mkdirs()
            }
        val linear = android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.LINEAR_EXTENDED_SRGB)
        val srgb = android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB)
        val rows = JSONArray()
        val md =
            StringBuilder(
                "# Decoder probe $run\n\n| file | variant | config | colorSpace | alpha | premul | gainmap | WxH | mean sRGB8 R/G/B | luma Y | OK L | ms |\n|---|---|---|---|---|---|---|---|---|---|---|---|\n",
            )
        try {
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("cat $zipPath")).use { input ->
                ZipInputStream(input).use { zip ->
                    generateSequence { zip.nextEntry }.forEach { e ->
                        if (!e.isDirectory && (e.name.startsWith("20261017_2059") || e.name.startsWith("20261017_2100"))) {
                            File(work, e.name).outputStream().use { zip.copyTo(it) }
                        }
                    }
                }
            }
            val decoder = FullResolutionDecoder(context.contentResolver)

            fun facts(b: Bitmap): JSONObject {
                var r = 0.0
                var g = 0.0
                var bl = 0.0
                var l = 0.0
                var n = 0
                val sx = (b.width / 160).coerceAtLeast(1)
                val sy = (b.height / 160).coerceAtLeast(1)
                for (y in 0 until b.height step sy) {
                    for (x in 0 until b.width step sx) {
                        val c = b.getColor(x, y)
                        val s = c.convert(srgb)
                        r += (s.red().coerceIn(0f, 1f) * 255f).roundToInt()
                        g += (s.green().coerceIn(0f, 1f) * 255f).roundToInt()
                        bl += (s.blue().coerceIn(0f, 1f) * 255f).roundToInt()
                        val lin = c.convert(linear)
                        l += ColorMath.linearSrgbToOkLab(lin.red(), lin.green(), lin.blue()).l
                        n++
                    }
                }
                return JSONObject()
                    .put("config", b.config?.name)
                    .put("colorSpace", b.colorSpace?.name)
                    .put("hasAlpha", b.hasAlpha())
                    .put("premultiplied", b.isPremultiplied)
                    .put("hasGainmap", b.hasGainmap())
                    .put("size", "${b.width}x${b.height}")
                    .put("meanR", d(r / n))
                    .put("meanG", d(g / n))
                    .put("meanB", d(bl / n))
                    .put("lumaY", d(0.2126 * r / n + 0.7152 * g / n + 0.0722 * bl / n))
                    .put("okL", d(l / n))
            }

            work.listFiles().orEmpty().sortedBy { it.name }.forEach { f ->
                val candidate =
                    PhotoCandidate(
                        id = f.name,
                        source = PhotoSource.ExtractedFile(f.path, f.name, f.name),
                        format =
                            if (f.name.endsWith(
                                    ".jpg",
                                )
                            ) {
                                com.akane.akastudio.core.ingestion.model.ImageFormat.JPEG
                            } else {
                                com.akane.akastudio.core.ingestion.model.ImageFormat.HEIF
                            },
                        byteSize = f.length(),
                        widthPx = 0,
                        heightPx = 0,
                        metadata = com.akane.akastudio.core.ingestion.model.CaptureMetadata.EMPTY,
                    )
                val variants =
                    listOf<Pair<String, () -> Bitmap>>(
                        "prod_3072" to { runBlocking { decoder.decode(candidate, 3072) }.bitmap },
                        "prod_1536" to { runBlocking { decoder.decode(candidate, 1536) }.bitmap },
                        "raw_noTarget" to {
                            ImageDecoder.decodeBitmap(ImageDecoder.createSource(f)) { dec, _, _ ->
                                dec.allocator =
                                    ImageDecoder.ALLOCATOR_SOFTWARE
                            }
                        },
                        "raw_linearTargetOnly" to {
                            ImageDecoder.decodeBitmap(ImageDecoder.createSource(f)) { dec, _, _ ->
                                dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                                dec.setTargetColorSpace(linear)
                            }
                        },
                    )
                variants.forEach { (name, block) ->
                    val t0 = SystemClock.elapsedRealtime()
                    val row = JSONObject().put("file", f.name).put("variant", name)
                    runCatching {
                        val b = block()
                        val ms = SystemClock.elapsedRealtime() - t0
                        val fx = facts(b)
                        b.recycle()
                        fx.keys().forEach { row.put(it, fx.get(it)) }
                        row.put("ms", ms)
                    }.onFailure { row.put("error", it.toString()) }
                    rows.put(row)
                    Log.i(TAG, "DECODER $row")
                    md.append(
                        "| ${f.name} | $name | ${row.opt(
                            "config",
                        )} | ${row.opt("colorSpace")} | ${row.opt("hasAlpha")} | ${row.opt("premultiplied")} | " +
                            "${row.opt(
                                "hasGainmap",
                            )} | ${row.opt(
                                "size",
                            )} | ${row.opt("meanR")}/${row.opt("meanG")}/${row.opt("meanB")} | ${row.opt("lumaY")} | " +
                            "${row.opt("okL")} | ${row.opt("ms")} |" +
                            (row.optString("error").takeIf { it.isNotEmpty() }?.let { " error=$it" } ?: "") +
                            "\n",
                    )
                }
            }
        } finally {
            File(outDir, "decoder_probe.json").writeText(rows.toString(2))
            File(outDir, "decoder_probe.md").writeText(md.toString())
            runCatching {
                val proc =
                    Runtime.getRuntime().exec(
                        arrayOf("logcat", "-d", "-v", "threadtime", "-s", "AkaDiag:D", "AkaTrace:I", "--pid=${Process.myPid()}"),
                    )
                File(outDir, "logcat_akadiag.txt").writeBytes(proc.inputStream.readBytes())
                proc.waitFor()
            }
            work.deleteRecursively()
        }
    }

    /**
     * Data pull for the plan review (skipped unless `qaDataPullZip`): one headless curation of the zip through the
     * real container and StudioViewModel (same path as [traceZipCuration] with qaTraceHeadless), then per photo the
     * 64 px saliency map, real [SmartCropSolver] crops at the review's cell aspects (rotation 0), NIMA means under
     * three input preprocessings (scored by a second model instance; production preprocessing is not changed),
     * screen-capture block statistics, faces, and bit-exact Q/A/embeddings for cross-run determinism.
     *
     * Arguments: `qaDataPullZip` (under /sdcard/Download/AkaStudioQA/), optional `qaDataPullLabel`, and
     * `qaDataPullExtras=false` to run only the curation, timing and determinism dump. No export, no MediaStore rows.
     * Output: `<external files>/curation-trace/datapull_<label>_<ms>/`.
     */
    @Test
    fun dataPull() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Manual data pull requires qaDataPullZip", arguments.containsKey("qaDataPullZip"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val zipPath = checkNotNull(arguments.getString("qaDataPullZip"))
        require(zipPath.startsWith("/sdcard/Download/AkaStudioQA/")) { "the zip must stay under /sdcard/Download/AkaStudioQA/" }
        val extras = arguments.getString("qaDataPullExtras") != "false"
        val label = arguments.getString("qaDataPullLabel") ?: "run"
        val run = "datapull_${label}_${System.currentTimeMillis()}"
        val outDir = File(checkNotNull(context.getExternalFilesDir(null)), "curation-trace/$run").apply { check(mkdirs()) }
        val zipName = zipPath.substringAfterLast('/')
        val localZip = File(context.cacheDir, "curation-trace/$zipName").apply { parentFile?.mkdirs() }
        val root = JSONObject()
        val md = StringBuilder("# Data pull $run\n\n")
        var sideSession: com.akane.akastudio.core.ingestion.api.IngestionSession? = null
        var variantModels: com.akane.akastudio.core.curator.ml.CuratorModels? = null
        val pid = Process.myPid()
        Log.i(TAG, "DATAPULL START run=$run pid=$pid extras=$extras")
        try {
            val pkg = context.packageManager.getPackageInfo(context.packageName, 0)
            root.put(
                "env",
                JSONObject()
                    .put("run", run)
                    .put("pid", pid)
                    .put("versionCode", pkg.longVersionCode)
                    .put("lastUpdateTime", pkg.lastUpdateTime)
                    .put("device", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} API ${android.os.Build.VERSION.SDK_INT}")
                    .put("zip", zipPath)
                    .put("driver", "ViewModelStore (headless)")
                    .put("extras", extras),
            )

            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("cat $zipPath")).use { input ->
                localZip.outputStream().use { input.copyTo(it) }
            }
            check(localZip.length() > 1000) { "zip copy is empty (${localZip.length()} bytes)" }
            val zipUri = Uri.fromFile(localZip).toString()
            val entryNames =
                ZipInputStream(localZip.inputStream()).use { zip ->
                    generateSequence { zip.nextEntry }.filter { !it.isDirectory }.map { it.name }.toList()
                }
            val idToName = entryNames.associateBy { stableCandidateId(zipUri + "\u0000" + it) }

            // Side ingestion: the candidates (metadata, extracted files) the analysis decoder reads again below.
            val container = (context.applicationContext as AkaStudioApp).container
            val session = container.newIngestionSession().also { sideSession = it }
            val events = runBlocking { session.ingest(listOf(IngestionRequest.ZipArchive(zipUri, zipName))).toList() }
            val sideCandidates = LinkedHashMap<String, PhotoCandidate>()
            events.forEach { e ->
                when (e) {
                    is IngestionEvent.CandidateReady -> sideCandidates.putIfAbsent(e.candidate.id, e.candidate)
                    is IngestionEvent.Completed -> e.candidates.forEach { sideCandidates.putIfAbsent(it.id, it) }
                    else -> Unit
                }
            }

            // region curation (headless, production container)
            val resetOk = resetHwm()
            var created: StudioViewModel? = null
            instrumentation.runOnMainSync {
                created = ViewModelProvider(standaloneStore, studioViewModelFactory(container))[StudioViewModel::class.java]
            }
            val model = checkNotNull(created)
            val t0 = SystemClock.elapsedRealtime()
            val poller =
                thread(name = "AkaTracePoll", isDaemon = true) {
                    var last: StudioPhase? = null
                    while (polling) {
                        val p = model.state.value.phase
                        if (p != last) {
                            phaseLog += "${SystemClock.elapsedRealtime() - t0}\t${p.javaClass.simpleName}\t${status()["VmHWM"] ?: -1}"
                            last = p
                        }
                        SystemClock.sleep(20)
                    }
                }
            instrumentation.runOnMainSync { model.onIntent(StudioIntent.ImportZip(zipUri, zipName)) }
            awaitCondition("studio Ready", CURATION_TIMEOUT_MS) {
                val s = model.state.value
                if (s.phase == StudioPhase.Empty && s.error != null) throw AssertionError("Studio returned to Empty: ${s.error}")
                s.phase == StudioPhase.Ready
            }
            val readyMs = SystemClock.elapsedRealtime() - t0
            val hwmReady = status()["VmHWM"] ?: -1
            polling = false
            poller.join(1000)
            val state = model.state.value
            val curation = checkNotNull(state.curation) { "Ready without a curation" }

            fun nameOf(id: String) = idToName[id] ?: sideCandidates[id]?.source?.displayName ?: id.take(12)

            val phases = synchronized(phaseLog) { phaseLog.toList() }

            fun phaseStart(name: String) =
                phases
                    .firstOrNull { it.split('\t')[1] == name }
                    ?.split('\t')
                    ?.get(0)
                    ?.toLongOrNull()

            fun phaseHwm(name: String) =
                phases
                    .firstOrNull { it.split('\t')[1] == name }
                    ?.split('\t')
                    ?.get(2)
                    ?.toLongOrNull()
            val curatingMs = phaseStart("Curating")?.let { c -> phaseStart("Preparing")?.minus(c) }
            root.put(
                "timing",
                JSONObject()
                    .put("phases", JSONArray(phases))
                    .put("importToReadyMs", readyMs)
                    .put("ingestingMs", phaseStart("Ingesting")?.let { i -> phaseStart("Curating")?.minus(i) } ?: JSONObject.NULL)
                    .put("curatingMs_analysisAndDecision", curatingMs ?: JSONObject.NULL)
                    .put("vmHwmAtCuratingStartKb", phaseHwm("Curating") ?: JSONObject.NULL)
                    .put("vmHwmAtPreparingStartKb_peakThroughAnalysis", phaseHwm("Preparing") ?: JSONObject.NULL)
                    .put("hwmResetAccepted", resetOk)
                    .put("vmHwmAtReadyKb", hwmReady),
            )
            md.append(
                "## Timing\n\nimport→Ready $readyMs ms; curating (analysis+decision) $curatingMs ms; VmHWM at Preparing ${phaseHwm(
                    "Preparing",
                )} kB, at Ready $hwmReady kB (reset accepted $resetOk).\n\n",
            )
            // endregion

            // region determinism dump (exact bits)
            fun bits(f: Float) = "%08x".format(java.lang.Float.floatToRawIntBits(f))

            fun option(o: CurationOption) =
                JSONObject()
                    .put(
                        "mode",
                        o.mode.name,
                    ).put("photos", JSONArray(o.photoIds.map(::nameOf)))
                    .put("score", d(o.score))
                    .put("scoreBits", bits(o.score))
            val det = JSONObject()
            curation.analyses.values.sortedBy { nameOf(it.candidateId) }.forEach { a ->
                val buf =
                    java.nio.ByteBuffer
                        .allocate(a.embedding.size * 4)
                        .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                a.embedding.forEach { buf.putFloat(it) }
                det.put(
                    nameOf(a.candidateId),
                    JSONObject()
                        .put("Q", d(a.quality))
                        .put("Qbits", bits(a.quality))
                        .put("A", d(a.aesthetic))
                        .put("Abits", bits(a.aesthetic))
                        .put("composition", d(a.composition))
                        .put("compositionBits", bits(a.composition))
                        .put("T", d(a.technical.total))
                        .put("Tbits", bits(a.technical.total))
                        .put("pHash", "%016x".format(a.pHash))
                        .put("dHash", "%016x".format(a.dHash))
                        .put("faces", a.faces.size)
                        .put("reject", a.rejectReason?.name ?: JSONObject.NULL)
                        .put("embeddingB64", android.util.Base64.encodeToString(buf.array(), android.util.Base64.NO_WRAP)),
                )
            }
            root.put("determinism", det)
            root.put(
                "decision",
                JSONObject()
                    .put("chosen", option(curation.chosen))
                    .put(
                        "alternatives",
                        JSONObject().apply {
                            curation.alternatives.toSortedMap().forEach { (m, o) ->
                                put(m.name, option(o))
                            }
                        },
                    ).put("clusters", JSONArray(curation.clusters.map { c -> JSONArray(c.memberIds.map(::nameOf)) }))
                    .put("rejected", JSONArray(curation.rejectedIds.map(::nameOf))),
            )
            md.append("## Decision\n\nChosen ${root.getJSONObject("decision").get("chosen")}\n\n")
            // endregion

            if (extras) {
                Log.i(TAG, "DATAPULL extras start (a second CuratorModels instance compiles NIMA below)")
                val pixels = createAnalysisDecoder(context).asPixelSource()
                val models = CuratorModelsFactory.create(context).also { variantModels = it }
                root.getJSONObject("env").put("variantModelsDegraded", JSONArray(models.degraded.toList()))
                val photos = JSONObject()
                val saliencyMaps = JSONObject()
                val nimaMd =
                    StringBuilder(
                        "## NIMA mean by input variant\n\n| photo | prod (centre crop) | centre sanity | squash | pad black | pad grey | implied A from prod | analysis A | analysis bitmap |\n|---|---|---|---|---|---|---|---|---|\n",
                    )
                val screenMd =
                    StringBuilder(
                        "## Screen-capture features (analysis bitmap, 8x8 blocks)\n\nDetector = ScreenCaptureDetector.evaluate (skips the scan, fraction 0, when camera EXIF is present); RGB zero-var = its zeroVarianceBlockFraction run for every photo; luma columns are this test's own Rec.601 statistics.\n\n| photo | WxH | aspect | analysis WxH | detector cameraExif | detector zeroVar | detector threshold | detector flag | analysis.screenCapture | RGB zero-var | luma zero-var | luma var<1 | focal35 | exposure s | ISO | f-number |\n|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|\n",
                    )
                val cropMd =
                    StringBuilder(
                        "## SmartCropSolver crops (rotation 0): scale / kept saliency / prominent faces inside (raw box, 1.15x box)\n\n| photo | faces | " +
                            CELL_ASPECTS.joinToString(" | ") +
                            " |\n|---|---|" +
                            CELL_ASPECTS.joinToString("") { "---|" } +
                            "\n",
                    )
                val faceMd =
                    StringBuilder(
                        "## Faces (selfie burst / landscape pair / 2339xx)\n\n| photo | capture ms | faces: box l,t,r,b area |\n|---|---|---|\n",
                    )
                sideCandidates.values.sortedBy { nameOf(it.id) }.forEach { c ->
                    val name = nameOf(c.id)
                    val a = curation.analyses[c.id]
                    val p = JSONObject()
                    if (a == null) {
                        photos.put(name, p.put("error", "not analyzed"))
                        return@forEach
                    }
                    val image = runBlocking { pixels.load(c) }
                    try {
                        val bmp = checkNotNull(image.bitmap)
                        val px = image.pixels
                        val w = c.widthPx.takeIf { it > 0 } ?: px.width
                        val h = c.heightPx.takeIf { it > 0 } ?: px.height
                        p.put("widthPx", w).put("heightPx", h).put("aspect", d(w.toDouble() / h))
                        p.put("analysisBitmap", "${bmp.width}x${bmp.height} ${bmp.config?.name} ${bmp.colorSpace?.name}")

                        // NIMA: every variant is a 224x224 bitmap, which the scorer's centre crop passes through unchanged.
                        val src = if (bmp.config == Bitmap.Config.ARGB_8888) bmp else checkNotNull(bmp.copy(Bitmap.Config.ARGB_8888, false))
                        val side = minOf(src.width, src.height)
                        val square = Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
                        val variants =
                            linkedMapOf(
                                "centreSanity" to Bitmap.createScaledBitmap(square, NIMA_EDGE, NIMA_EDGE, true),
                                "squash" to Bitmap.createScaledBitmap(src, NIMA_EDGE, NIMA_EDGE, true),
                                "padBlack" to letterbox(src, BLACK),
                                "padGrey" to letterbox(src, GREY),
                            )
                        val nima = JSONObject()
                        val prod = runBlocking { models.aesthetics.score(bmp) }
                        nima.put("prod", prod?.let { d(it) } ?: JSONObject.NULL)
                        variants.forEach { (k, v) ->
                            nima.put(
                                k,
                                runBlocking { models.aesthetics.score(v) }?.let { d(it) } ?: JSONObject.NULL,
                            )
                        }
                        variants.values.forEach { if (it !== src) it.recycle() }
                        if (square !== src) square.recycle()
                        if (src !== bmp) src.recycle()
                        val impliedA = prod?.let { 0.7f * ((it - 3f) / 4f).coerceIn(0f, 1f) + 0.3f * a.composition.coerceIn(0f, 1f) }
                        nima.put("impliedAFromProd", impliedA?.let { d(it) } ?: JSONObject.NULL).put("analysisA", d(a.aesthetic))
                        p.put("nima", nima)
                        nimaMd.append(
                            "| $name | ${nima.opt(
                                "prod",
                            )} | ${nima.opt(
                                "centreSanity",
                            )} | ${nima.opt(
                                "squash",
                            )} | ${nima.opt(
                                "padBlack",
                            )} | ${nima.opt(
                                "padGrey",
                            )} | ${nima.opt("impliedAFromProd")} | ${d(a.aesthetic)} | ${p.opt("analysisBitmap")} |\n",
                        )

                        // Screen-capture features on the analysis pixels.
                        var blocks = 0
                        var zero = 0
                        var low = 0
                        val luma = DoubleArray(64)
                        for (by in 0 until px.height / 8) {
                            for (bx in 0 until px.width / 8) {
                                var k = 0
                                var mean = 0.0
                                for (y in 0 until 8) {
                                    for (x in 0 until 8) {
                                        val v = px.argb[(by * 8 + y) * px.width + bx * 8 + x]
                                        val l = 0.299 * ((v shr 16) and 0xFF) + 0.587 * ((v shr 8) and 0xFF) + 0.114 * (v and 0xFF)
                                        luma[k++] = l
                                        mean += l
                                    }
                                }
                                mean /= 64.0
                                var variance = 0.0
                                for (l in luma) variance += (l - mean) * (l - mean)
                                variance /= 64.0
                                blocks++
                                if (variance == 0.0) zero++
                                if (variance < 1.0) low++
                            }
                        }
                        val m = c.metadata
                        val detector = ScreenCaptureDetector.evaluate(px, m)
                        val rgbZero = ScreenCaptureDetector.zeroVarianceBlockFraction(px.argb, px.width, px.height)
                        p.put(
                            "screen",
                            JSONObject()
                                .put("detectorCameraExif", detector.cameraExif)
                                .put("detectorZeroVarianceFraction", d(detector.zeroVarianceFraction))
                                .put("detectorThreshold", d(detector.threshold))
                                .put("detectorScreenCapture", detector.screenCapture)
                                .put("analysisScreenCapture", a.screenCapture)
                                .put("rgbZeroVarianceFraction", d(rgbZero))
                                .put("analysisSize", "${px.width}x${px.height}")
                                .put("blocks", blocks)
                                .put("zeroVarFraction", d(zero.toDouble() / blocks))
                                .put("varBelow1Fraction", d(low.toDouble() / blocks))
                                .put("focal35", m.focalLength35mm ?: JSONObject.NULL)
                                .put("exposureS", m.exposureTimeSeconds ?: JSONObject.NULL)
                                .put("iso", m.iso ?: JSONObject.NULL)
                                .put("fNumber", m.fNumber ?: JSONObject.NULL)
                                .put("captureEpochMillis", m.captureEpochMillis ?: JSONObject.NULL),
                        )
                        screenMd.append(
                            "| $name | ${w}x$h | ${d(
                                w.toDouble() / h,
                            )} | ${px.width}x${px.height} | ${detector.cameraExif} | ${d(
                                detector.zeroVarianceFraction,
                            )} | ${d(detector.threshold)} | ${detector.screenCapture} | ${a.screenCapture} | ${d(rgbZero)} | ${d(
                                zero.toDouble() / blocks,
                            )} | ${d(
                                low.toDouble() / blocks,
                            )} | ${m.focalLength35mm} | ${m.exposureTimeSeconds} | ${m.iso} | ${m.fNumber} |\n",
                        )

                        // Saliency map and faces.
                        val sal = a.saliency
                        saliencyMaps.put(
                            name,
                            JSONObject().put("width", sal.width).put("height", sal.height).put(
                                "values",
                                JSONArray(
                                    sal.values.map {
                                        Math.round(it * 1000.0) /
                                            1000.0
                                    },
                                ),
                            ),
                        )
                        val faces =
                            JSONArray(
                                a.faces.map { f ->
                                    val area = f.box.width.coerceAtLeast(0f) * f.box.height.coerceAtLeast(0f)
                                    JSONObject().put("box", normJson(f.box)).put("area", d(area)).put(
                                        "prominent",
                                        area >= PROMINENT_FACE_AREA,
                                    )
                                },
                            )
                        p.put("faces", faces)
                        if (FACE_SERIES.any { name.contains(it) }) {
                            faceMd.append(
                                "| $name | ${m.captureEpochMillis} | " +
                                    a.faces.joinToString("; ") { f ->
                                        "%.3f,%.3f,%.3f,%.3f a=%.4f".format(
                                            f.box.left,
                                            f.box.top,
                                            f.box.right,
                                            f.box.bottom,
                                            f.box.width * f.box.height,
                                        )
                                    } + " |\n",
                            )
                        }

                        // Real solver crops, as DefaultCollageComposer calls it (upright candidate size, rotation 0).
                        val photo =
                            ComposerPhoto(
                                id = c.id,
                                imageKey = "datapull",
                                widthPx = w,
                                heightPx = h,
                                saliency = sal,
                                faces = a.faces,
                                palette = a.palette,
                                horizonTiltDegrees = 0f,
                                metadata = c.metadata,
                            )
                        val prominent = a.faces.filter { it.box.width * it.box.height >= PROMINENT_FACE_AREA }
                        val crops = JSONObject()
                        val cells =
                            CELL_ASPECTS.map { aspect ->
                                val r = SmartCropSolver.solve(photo, aspect, 0f)
                                val maxH = minOf(h.toDouble(), w / aspect.toDouble())
                                val scale = r.height * h / maxH
                                val kept = keptSaliency(sal, r)
                                val rawIn = prominent.all { inside(it.box, r, 1.0) }
                                val expIn = prominent.all { inside(it.box, r, 1.15) }
                                val allIn = a.faces.all { inside(it.box, r, 1.0) }
                                crops.put(
                                    aspect.toString(),
                                    JSONObject()
                                        .put("crop", normJson(r))
                                        .put("scale", d(scale))
                                        .put("keptSaliency", d(kept))
                                        .put("prominentFacesInsideRaw", rawIn)
                                        .put("prominentFacesInsideExpanded", expIn)
                                        .put("allFacesInsideRaw", allIn)
                                        .put("prominentFaces", prominent.size),
                                )
                                "%.2f/%.2f/%s".format(scale, kept, if (prominent.isEmpty()) "-" else "${yn(rawIn)}${yn(expIn)}")
                            }
                        p.put("crops", crops)
                        cropMd.append("| $name | ${a.faces.size}(${prominent.size}) | ${cells.joinToString(" | ")} |\n")
                    } finally {
                        image.bitmap?.recycle()
                    }
                    photos.put(name, p)
                    Log.i(TAG, "DATAPULL $name nima=${p.opt("nima")}")
                }
                root.put("photos", photos)
                File(outDir, "saliency_maps.json").writeText(saliencyMaps.toString())

                // Per-class NIMA means.
                val nimaClasses =
                    linkedMapOf(
                        "table" to listOf("205957", "210001", "210007", "210010", "210023", "210025"),
                        "people_2233xx" to listOf("_2233"),
                        "people_2234xx" to listOf("_2234"),
                        "people_2339xx" to listOf("_2339"),
                        "screenshot" to listOf("Screenshot"),
                    )
                nimaMd.append("\nClass means (n):\n\n| class | n | prod | squash | pad black | pad grey |\n|---|---|---|---|---|---|\n")
                val classMeans = JSONObject()
                nimaClasses.forEach { (cls, keys) ->
                    val members =
                        photos
                            .keys()
                            .asSequence()
                            .filter { k ->
                                keys.any { k.contains(it) }
                            }.mapNotNull { photos.getJSONObject(it).optJSONObject("nima") }
                            .toList()

                    fun mean(v: String) =
                        members
                            .mapNotNull { it.opt(v) as? Number }
                            .map { it.toDouble() }
                            .takeIf { it.isNotEmpty() }
                            ?.average()
                    val row = JSONObject().put("n", members.size)
                    listOf("prod", "squash", "padBlack", "padGrey").forEach { v -> row.put(v, mean(v)?.let { d(it) } ?: JSONObject.NULL) }
                    classMeans.put(cls, row)
                    nimaMd.append(
                        "| $cls | ${members.size} | ${row.opt(
                            "prod",
                        )} | ${row.opt("squash")} | ${row.opt("padBlack")} | ${row.opt("padGrey")} |\n",
                    )
                }
                root.put("nimaClassMeans", classMeans)
                md
                    .append(nimaMd)
                    .append('\n')
                    .append(screenMd)
                    .append('\n')
                    .append(cropMd)
                    .append('\n')
                    .append(faceMd)
                    .append('\n')
            }
            File(outDir, "datapull.json").writeText(root.toString(1))
            File(outDir, "datapull.md").writeText(md.toString())
            Log.i(TAG, "DATAPULL PASS run=$run out=$outDir")
        } finally {
            polling = false
            if (!File(outDir, "datapull.json").exists()) runCatching { File(outDir, "datapull_partial.json").writeText(root.toString(1)) }
            runCatching {
                val proc = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-v", "threadtime", "--pid=$pid"))
                val keep =
                    Regex(
                        "NimaScorer|CuratorModels|litert|tflite|AkaTrace|gpu_registry|GPU|accelerator|XNNPACK|delegate",
                        RegexOption.IGNORE_CASE,
                    )
                File(outDir, "logcat_models.txt").writeText(
                    proc.inputStream
                        .bufferedReader()
                        .lineSequence()
                        .filter {
                            keep.containsMatchIn(it)
                        }.joinToString("\n"),
                )
                proc.waitFor()
            }
            try {
                runCatching { variantModels?.close() }
                instrumentation.runOnMainSync { standaloneStore.clear() }
            } finally {
                localZip.delete()
                runCatching { sideSession?.close() }
            }
        }
    }

    /** [src] scaled to fit [NIMA_EDGE] on its long edge, centred on a [NIMA_EDGE]² canvas filled with [pad]. */
    private fun letterbox(
        src: Bitmap,
        pad: Int,
    ): Bitmap {
        val out = Bitmap.createBitmap(NIMA_EDGE, NIMA_EDGE, Bitmap.Config.ARGB_8888)
        out.eraseColor(pad)
        val s = NIMA_EDGE.toFloat() / maxOf(src.width, src.height)
        val w = (src.width * s).roundToInt().coerceIn(1, NIMA_EDGE)
        val h = (src.height * s).roundToInt().coerceIn(1, NIMA_EDGE)
        val scaled = Bitmap.createScaledBitmap(src, w, h, true)
        android.graphics.Canvas(out).drawBitmap(scaled, ((NIMA_EDGE - w) / 2).toFloat(), ((NIMA_EDGE - h) / 2).toFloat(), null)
        if (scaled !== src) scaled.recycle()
        return out
    }

    /** Fraction of the raw (piecewise-constant) saliency map's mass inside the normalized crop [r]. */
    private fun keptSaliency(
        map: com.akane.akastudio.core.curator.api.SaliencyMap,
        r: NormRect,
    ): Double {
        var total = 0.0
        var kept = 0.0
        for (y in 0 until map.height) {
            for (x in 0 until map.width) {
                val v = map.values[y * map.width + x].toDouble()
                total += v
                val ox = minOf(r.right.toDouble(), (x + 1.0) / map.width) - maxOf(r.left.toDouble(), x.toDouble() / map.width)
                val oy = minOf(r.bottom.toDouble(), (y + 1.0) / map.height) - maxOf(r.top.toDouble(), y.toDouble() / map.height)
                if (ox > 0 && oy > 0) kept += v * ox * oy * map.width * map.height
            }
        }
        return if (total > 0) kept / total else 0.0
    }

    /** True when [box] expanded by [factor] about its centre (clamped to the image) lies inside [crop]. */
    private fun inside(
        box: NormRect,
        crop: NormRect,
        factor: Double,
    ): Boolean {
        val cx = (box.left + box.right) / 2.0
        val cy = (box.top + box.bottom) / 2.0
        val hw = (box.right - box.left) * factor / 2
        val hh = (box.bottom - box.top) * factor / 2
        val eps = 1e-4
        return maxOf(0.0, cx - hw) >= crop.left - eps && minOf(1.0, cx + hw) <= crop.right + eps &&
            maxOf(0.0, cy - hh) >= crop.top - eps && minOf(1.0, cy + hh) <= crop.bottom + eps
    }

    private fun yn(b: Boolean) = if (b) "Y" else "N"

    // region json helpers

    private fun d(f: Float): Any = if (f.isFinite()) (f * 1e5).roundToInt() / 1e5 else f.toString()

    private fun d(v: Double): Any = if (v.isFinite()) Math.round(v * 1e5) / 1e5 else v.toString()

    private fun rectJson(r: Rect4f) =
        JSONObject()
            .put("l", d(r.left))
            .put("t", d(r.top))
            .put("r", d(r.right))
            .put("b", d(r.bottom))

    private fun normJson(r: NormRect) =
        JSONObject()
            .put("l", d(r.left))
            .put("t", d(r.top))
            .put("r", d(r.right))
            .put("b", d(r.bottom))
            .put("w", d(r.width))
            .put("h", d(r.height))

    private fun labJson(s: LabStats) =
        JSONObject()
            .put("meanL", d(s.meanL))
            .put("meanA", d(s.meanA))
            .put("meanB", d(s.meanB))
            .put("stdL", d(s.stdL))
            .put("stdA", d(s.stdA))
            .put("stdB", d(s.stdB))
            .put("weight", d(s.weight))

    private fun gradeJson(g: GradeParams) =
        JSONObject()
            .put("look", g.look.name)
            .put("lookIntensity", d(g.lookIntensity))
            .put(
                "harmonization",
                g.harmonization.let {
                    JSONObject()
                        .put(
                            "scaleL",
                            d(it.scaleL),
                        ).put("offsetL", d(it.offsetL))
                        .put("scaleA", d(it.scaleA))
                        .put("offsetA", d(it.offsetA))
                        .put("scaleB", d(it.scaleB))
                        .put("offsetB", d(it.offsetB))
                },
            ).put("exposureEv", d(g.exposureEv))
            .put("temperature", d(g.temperature))
            .put("tint", d(g.tint))
            .put("contrast", d(g.contrast))
            .put("saturation", d(g.saturation))
            .put("halation", d(g.halation))
            .put("grain", d(g.grain))
            .put("skinProtection", d(g.skinProtection))

    private fun analysisJson(
        a: PhotoAnalysis,
        cluster: Int?,
    ): JSONObject {
        var norm = 0.0
        a.embedding.forEach { norm += it.toDouble() * it }
        return JSONObject()
            .put("id", a.candidateId)
            .put("cluster", cluster ?: JSONObject.NULL)
            .put(
                "technical",
                JSONObject()
                    .put(
                        "sharpness",
                        d(a.technical.sharpness),
                    ).put("exposure", d(a.technical.exposure))
                    .put("faceQuality", d(a.technical.faceQuality))
                    .put("total", d(a.technical.total)),
            ).put("aesthetic", d(a.aesthetic))
            .put("composition", d(a.composition))
            .put("quality", d(a.quality))
            .put("scene", a.scene.name)
            .put("shotScale", a.shotScale.name)
            .put("cctKelvin", d(a.cctKelvin))
            .put("labStats", labJson(a.labStats))
            .put(
                "palette",
                JSONArray(
                    a.palette.map {
                        JSONObject()
                            .put("L", d(it.lab.l))
                            .put("a", d(it.lab.a))
                            .put("b", d(it.lab.b))
                            .put("w", d(it.weight))
                    },
                ),
            ).put(
                "faces",
                JSONArray(
                    a.faces.map {
                        JSONObject()
                            .put(
                                "box",
                                normJson(it.box),
                            ).put("leftEye", d(it.leftEyeOpenness))
                            .put("rightEye", d(it.rightEyeOpenness))
                            .put("sharpness", d(it.sharpness))
                    },
                ),
            ).put("pHash", "%016x".format(a.pHash))
            .put("dHash", "%016x".format(a.dHash))
            .put("embeddingDim", a.embedding.size)
            .put("embeddingNorm", d(sqrt(norm)))
            .put("embeddingAllZero", a.embedding.all { it == 0f })
            .put("rejectReason", a.rejectReason?.name ?: JSONObject.NULL)
    }

    private fun telemetryOf(e: IngestionEvent): JSONObject? {
        val t =
            when (e) {
                is IngestionEvent.Completed -> e.telemetry
                is IngestionEvent.Progress -> e.telemetry
                is IngestionEvent.CandidateReady -> e.telemetry
                is IngestionEvent.Skipped -> e.telemetry
                is IngestionEvent.SourceFailed -> e.telemetry
            }
        return JSONObject()
            .put("sourcesTotal", t.sourcesTotal)
            .put("filesRead", t.filesRead)
            .put("accepted", t.candidatesAccepted)
            .put("skipped", t.skipped)
            .put("bytesExtracted", t.bytesExtracted)
    }

    // endregion

    /** PhotoAnalyzer.colorfulness: clamp(weighted mean OKLCh chroma of the palette / 0.12). */
    private fun colorfulness(a: PhotoAnalysis): Float {
        var weight = 0.0
        var chroma = 0.0
        for (swatch in a.palette) {
            val w = swatch.weight.toDouble().coerceAtLeast(0.0)
            weight += w
            chroma += w * ColorMath.okLabToOkLch(swatch.lab)[1]
        }
        if (weight <= 0.0) return 0f
        return ((chroma / weight) / 0.12).toFloat().coerceIn(0f, 1f)
    }

    /** Mean OKLab L of [b] sampled on a grid: each pixel's components read in the bitmap's own colour space, converted to linear sRGB. */
    private fun meanOkL(b: Bitmap): Float {
        val linear = android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.LINEAR_EXTENDED_SRGB)
        var sum = 0.0
        var n = 0
        val stepX = (b.width / 96).coerceAtLeast(1)
        val stepY = (b.height / 96).coerceAtLeast(1)
        for (y in 0 until b.height step stepY) {
            for (x in 0 until b.width step stepX) {
                val c = b.getColor(x, y).convert(linear)
                sum += ColorMath.linearSrgbToOkLab(c.red(), c.green(), c.blue()).l
                n++
            }
        }
        return (sum / n).toFloat()
    }

    /** Mean absolute 8-bit channel difference of [a] and [b] (same width [w]) inside [r] (inset by 8 px). */
    private fun meanAbsDiff(
        a: IntArray,
        b: IntArray,
        w: Int,
        r: Rect4f,
    ): Double {
        var sum = 0L
        var n = 0L
        for (y in (r.top + 8).toInt() until (r.bottom - 8).toInt() step 2) {
            for (x in (r.left + 8).toInt() until (r.right - 8).toInt() step 2) {
                val p = a[y * w + x]
                val q = b[y * w + x]
                sum +=
                    kotlin.math.abs(((p shr 16) and 0xFF) - ((q shr 16) and 0xFF)) +
                    kotlin.math.abs(((p shr 8) and 0xFF) - ((q shr 8) and 0xFF)) +
                    kotlin.math.abs((p and 0xFF) - (q and 0xFF))
                n += 3
            }
        }
        return sum.toDouble() / n
    }

    // region pixels

    private fun cosine(
        a: FloatArray,
        b: FloatArray,
    ): Float {
        if (a.size != b.size || a.isEmpty()) return 0f
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (k in a.indices) {
            dot += a[k].toDouble() * b[k]
            na += a[k].toDouble() * a[k]
            nb += b[k].toDouble() * b[k]
        }
        if (na <= 0.0 || nb <= 0.0) return 0f
        return (dot / (sqrt(na) * sqrt(nb))).toFloat()
    }

    /** Premultiplied linear half-float composited over black (alpha ignored), sRGB-encoded 8-bit ARGB. */
    private fun renderArgb(img: HalfFloatImage): IntArray {
        val out = IntArray(img.width * img.height)

        fun enc(s: Short): Int = (ColorMath.linearToSrgb(Half.toFloat(s).coerceIn(0f, 1f)) * 255f).roundToInt().coerceIn(0, 255)
        for (i in out.indices) {
            val o = i * 4
            out[i] = (0xFF shl 24) or (enc(img.rgba[o]) shl 16) or (enc(img.rgba[o + 1]) shl 8) or enc(img.rgba[o + 2])
        }
        return out
    }

    private fun savePng(
        argb: IntArray,
        w: Int,
        h: Int,
        file: File,
    ) {
        val bmp = Bitmap.createBitmap(argb, w, h, Bitmap.Config.ARGB_8888)
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
    }

    /** Decodes [bytes] in software and returns (width, height, sRGB 8-bit ARGB). */
    private fun decodeArgb(bytes: ByteArray): Triple<Int, Int, IntArray> {
        val bmp =
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(java.nio.ByteBuffer.wrap(bytes))) { decoder, _, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.setTargetColorSpace(android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB))
            }
        val px = IntArray(bmp.width * bmp.height)
        val argb = if (bmp.config == Bitmap.Config.ARGB_8888) bmp else bmp.copy(Bitmap.Config.ARGB_8888, false)
        argb.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        return Triple(bmp.width, bmp.height, px)
    }

    /** Per-cell mean sRGB, mean OKLab L/a/b and 5th/95th percentile Rec.709 luma of the gamma-encoded values (0..255). */
    private fun cellStats(
        argb: IntArray,
        w: Int,
        h: Int,
        rects: List<Rect4f>,
        labels: List<String>,
    ): JSONObject {
        val out = JSONObject()
        rects.forEachIndexed { i, r ->
            val x0 = (r.left + INSET).toInt().coerceIn(0, w - 1)
            val x1 = (r.right - INSET).toInt().coerceIn(x0 + 1, w)
            val y0 = (r.top + INSET).toInt().coerceIn(0, h - 1)
            val y1 = (r.bottom - INSET).toInt().coerceIn(y0 + 1, h)
            var sr = 0.0
            var sg = 0.0
            var sb = 0.0
            var sl = 0.0
            var sa = 0.0
            var sbb = 0.0
            val hist = IntArray(256)
            var count = 0
            for (y in y0 until y1 step 2) {
                for (x in x0 until x1 step 2) {
                    val c = argb[y * w + x]
                    val rr = (c shr 16) and 0xFF
                    val gg = (c shr 8) and 0xFF
                    val bb = c and 0xFF
                    sr += rr
                    sg += gg
                    sb += bb
                    val lab = ColorMath.argbToOkLab(c)
                    sl += lab.l
                    sa += lab.a
                    sbb += lab.b
                    hist[(0.2126 * rr + 0.7152 * gg + 0.0722 * bb).roundToInt().coerceIn(0, 255)]++
                    count++
                }
            }

            fun pct(p: Double): Int {
                val target = (count * p).toLong()
                var acc = 0L
                for (k in 0..255) {
                    acc += hist[k]
                    if (acc > target) return k
                }
                return 255
            }
            out.put(
                "cell$i",
                JSONObject()
                    .put("photo", labels.getOrNull(i))
                    .put("rect", rectJson(r))
                    .put("meanR", d(sr / count))
                    .put(
                        "meanG",
                        d(sg / count),
                    ).put("meanB", d(sb / count))
                    .put(
                        "okL",
                        d(sl / count),
                    ).put(
                        "okA",
                        d(sa / count),
                    ).put("okB", d(sbb / count))
                    .put("lumaP5", pct(0.05))
                    .put("lumaP95", pct(0.95))
                    .put("lumaP50", pct(0.5)),
            )
        }
        return out
    }

    private fun fit(
        aspect: Float,
        cw: Float,
        ch: Float,
    ): Rect4f {
        val (w, h) = if (aspect > cw / ch) cw to cw / aspect else ch * aspect to ch
        val l = (cw - w) / 2f
        val t = (ch - h) / 2f
        return Rect4f(l, t, l + w, t + h)
    }

    // endregion

    // region summary

    private fun summary(root: JSONObject): String =
        buildString {
            val env = root.getJSONObject("env")
            appendLine("# Curation trace ${env.getString("run")}")
            appendLine()
            appendLine(
                "App ${env.opt(
                    "package",
                )} ${env.opt(
                    "versionName",
                )} (${env.opt("versionCode")}), installed ${env.opt("lastUpdateTime")}, ${env.opt("device")}. Zip ${env.opt("zip")}.",
            )
            appendLine()
            val ing = root.getJSONObject("ingestion")
            appendLine("## Ingestion")
            appendLine()
            appendLine(
                "Entries ${ing.getJSONArray(
                    "zipEntries",
                ).length()}, accepted ${ing.getJSONArray(
                    "accepted",
                ).length()}, analyzed ${ing.getInt("analysesCount")}, side ids equal analysis ids: ${ing.get("sideIdsEqualAnalysisIds")}.",
            )
            appendLine(
                "Skipped: ${ing.getJSONArray(
                    "skipped",
                )}. Source failures: ${ing.getJSONArray("sourceFailed")}. Not analyzed: ${ing.getJSONArray("entriesNotAnalyzed")}.",
            )
            appendLine("TelemetryUi: ${ing.getString("telemetryUi")}")
            appendLine()
            appendLine("| name | fmt | WxH | colorspace | orient | gainmap |")
            appendLine("|---|---|---|---|---|---|")
            val acc = ing.getJSONArray("accepted")
            for (i in 0 until acc.length()) {
                val a = acc.getJSONObject(i)
                appendLine(
                    "| ${a.get(
                        "name",
                    )} | ${a.get(
                        "format",
                    )} | ${a.get("width")}x${a.get("height")} | ${a.get("colorSpace")} | ${a.get("orientation")} | ${a.get("gainMap")} |",
                )
            }
            appendLine()
            appendLine("## Analyses (sorted by Q)")
            appendLine()
            appendLine("| name | Q | T | sharp | expo | faceQ | A | comp | faces | scene | scale | CCT | L | cluster | reject |")
            appendLine("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|")
            val an = root.getJSONObject("analyses")
            an.keys().asSequence().map { it to an.getJSONObject(it) }.sortedByDescending { it.second.optDouble("quality") }.forEach { (name, a) ->
                val t = a.getJSONObject("technical")
                appendLine(
                    "| $name | ${a.get(
                        "quality",
                    )} | ${t.get(
                        "total",
                    )} | ${t.get(
                        "sharpness",
                    )} | ${t.get("exposure")} | ${t.get("faceQuality")} | ${a.get("aesthetic")} | ${a.get("composition")} | " +
                        "${a.getJSONArray(
                            "faces",
                        ).length()} | ${a.get(
                            "scene",
                        )} | ${a.get(
                            "shotScale",
                        )} | ${a.get(
                            "cctKelvin",
                        )} | ${a.getJSONObject("labStats").get("meanL")} | ${a.get("cluster")} | ${a.get("rejectReason")} |",
                )
            }
            appendLine()
            val dec = root.getJSONObject("decision")
            appendLine("## Decision")
            appendLine()
            appendLine("Chosen: ${dec.getJSONObject("chosen")}")
            appendLine()
            appendLine(
                "aiRecommendedMode ${dec.opt(
                    "aiRecommendedMode",
                )}, template ${dec.opt("template")}, look ${dec.opt("suggestedLook")}, layout ${dec.opt("layout")}",
            )
            appendLine()
            appendLine("Note: ${dec.opt("note")}")
            appendLine()
            val alts = dec.getJSONObject("alternatives")
            alts.keys().forEach { appendLine("- $it: ${alts.get(it)}") }
            appendLine()
            appendLine("Clusters:")
            val cl = dec.getJSONArray("clusters")
            for (i in 0 until cl.length()) appendLine("- ${cl.get(i)}")
            appendLine()
            appendLine("Rejected: ${dec.getJSONArray("rejected")}")
            appendLine()
            val pw = root.getJSONObject("pairwise")
            appendLine("## Near-duplicate pairs (cos > 0.82 or pHash <= 10 or dHash <= 8)")
            appendLine()
            appendLine("210025 heic vs jpg: ${pw.get("heicVsJpg_210025")}")
            appendLine()
            val f = pw.getJSONArray("wouldCluster")
            for (i in 0 until f.length()) appendLine("- ${f.get(i)}")
            appendLine()
            val wi = root.optJSONObject("whatIf")
            if (wi != null) {
                appendLine("## What-if decisions")
                appendLine()
                appendLine("Baseline re-decide chosen: ${wi.getJSONObject("baselineRedecide").get("chosen")}")
                appendLine()
                val nn = wi.getJSONObject("noNimaAesthetic")
                appendLine("No-NIMA aesthetic chosen: ${nn.get("chosen")}")
                appendLine()
                val nalts = nn.getJSONObject("alternatives")
                nalts.keys().forEach { appendLine("- $it: ${nalts.get(it)}") }
                appendLine()
                appendLine("No-NIMA Q: ${wi.get("noNimaQ")}")
                appendLine()
                appendLine("Inferred NIMA mean: ${wi.get("inferredNimaMean")}")
                appendLine()
            }

            fun story(
                title: String,
                st: JSONObject,
            ) {
                appendLine("## $title: ${st.opt("mode")} ${st.opt("template")} ${st.opt("photos")}")
                appendLine()
                val sc = st.getJSONObject("scene")
                val layers = sc.getJSONArray("layers")
                for (i in 0 until layers.length()) appendLine("- ${layers.get(i)}")
                appendLine()
                appendLine(
                    "GradeUi: ${sc.opt(
                        "gradeUi",
                    )}; layout ${sc.opt("layout")}; export ${st.opt("exportResult")} in ${st.opt("exportMs")} ms",
                )
                appendLine()
                appendLine("| render | cell | photo | R | G | B | OK L | OK a | OK b | luma p5 | p50 | p95 |")
                appendLine("|---|---|---|---|---|---|---|---|---|---|---|---|")
                val cs = st.getJSONObject("cellStats")
                cs.keys().forEach { render ->
                    val r = cs.getJSONObject(render)
                    r.keys().asSequence().filter { it.startsWith("cell") }.forEach { cell ->
                        val c = r.getJSONObject(cell)
                        appendLine(
                            "| $render | $cell | ${c.opt(
                                "photo",
                            )} | ${c.get("meanR")} | ${c.get("meanG")} | ${c.get("meanB")} | ${c.get("okL")} | ${c.get("okA")} | " +
                                "${c.get("okB")} | ${c.get("lumaP5")} | ${c.get("lumaP50")} | ${c.get("lumaP95")} |",
                        )
                    }
                }
                appendLine()
                val solo = st.getJSONObject("soloRenders")
                appendLine("Solo renders (identity grade):")
                solo.keys().forEach { k ->
                    val c = solo.getJSONObject(k).getJSONObject("cell0")
                    appendLine(
                        "- $k: RGB ${c.get(
                            "meanR",
                        )}/${c.get(
                            "meanG",
                        )}/${c.get(
                            "meanB",
                        )} OK L ${c.get("okL")} luma p5/p50/p95 ${c.get("lumaP5")}/${c.get("lumaP50")}/${c.get("lumaP95")}",
                    )
                }
                appendLine()
            }
            story("AI story", root.getJSONObject("story_ai"))
            root
                .optJSONObject(
                    "forced",
                )?.let { story("Forced ${it.opt("requested")} (chosen ${it.opt("chosen")})", it.getJSONObject("story")) }
            root.optJSONObject("candidateProbes")?.let { pr ->
                appendLine("## Decoder probe per candidate")
                appendLine()
                appendLine(
                    "| name | header | raw config/cs | raw OK L | prod config/cs | prod OK L | analysis L | render identity OK L | luma p5/p50/p95 | user cell1 diff | user cell3 diff |",
                )
                appendLine("|---|---|---|---|---|---|---|---|---|---|---|")
                pr.keys().forEach { k ->
                    val o = pr.getJSONObject(k)
                    val r = o.optJSONObject("renderIdentityFull")
                    appendLine(
                        "| $k | ${o.opt(
                            "header",
                        )} | ${o.opt(
                            "rawConfig",
                        )}/${o.opt("rawColorSpace")} gm=${o.opt("rawHasGainmap")} | ${o.opt("rawOkL_viaColorConvert")} | " +
                            "${o.opt(
                                "prodConfig",
                            )}/${o.opt(
                                "prodColorSpace",
                            )} | ${o.opt("prodOkL_treatingComponentsAsTagged")} | ${o.opt("analysisLabMeanL")} | ${r?.opt("okL")} | " +
                            "${r?.opt(
                                "lumaP5",
                            )}/${r?.opt(
                                "lumaP50",
                            )}/${r?.opt("lumaP95")} | ${o.opt("userCell1MeanAbsDiff")} | ${o.opt("userCell3MeanAbsDiff")} |" +
                            (o.optString("rawError").takeIf { it.isNotEmpty() }?.let { " rawError=$it" } ?: "") +
                            (o.optString("prodError").takeIf { it.isNotEmpty() }?.let { " prodError=$it" } ?: "") +
                            (o.optString("renderError").takeIf { it.isNotEmpty() }?.let { " renderError=$it" } ?: ""),
                    )
                }
                appendLine()
                appendLine("User export cell matches (lowest mean abs diff first): ${root.opt("userCellMatches")}")
                appendLine()
            }
            appendLine("## Timing")
            appendLine()
            appendLine(root.getJSONObject("timing").toString(2))
        }

    // endregion

    private fun status(): Map<String, Long> =
        File("/proc/self/status")
            .readLines()
            .mapNotNull { line ->
                val key = line.substringBefore(':')
                val kb =
                    line
                        .substringAfter(':')
                        .trim()
                        .removeSuffix("kB")
                        .trim()
                        .toLongOrNull()
                if (kb != null && key.startsWith("Vm")) key to kb else null
            }.toMap()

    private fun resetHwm(): Boolean =
        runCatching {
            File("/proc/self/clear_refs").writeText("5")
            val s = status()
            (s["VmHWM"] ?: Long.MAX_VALUE) <= (s["VmRSS"] ?: 0L) + 4096
        }.getOrDefault(false)

    private companion object {
        const val TAG = "AkaTrace"
        const val EXPORT_TIMEOUT_MS = 240_000L
        const val INSET = 4f
        const val BLACK = 0xFF000000.toInt()
        const val GREY = 0xFF808080.toInt()
        const val NIMA_EDGE = 224

        /** PhotoAnalyzer.PROMINENT_FACE_AREA (internal there). */
        const val PROMINENT_FACE_AREA = 0.06f
        val CELL_ASPECTS = listOf(0.554f, 0.613f, 0.796f, 1.017f, 1.121f, 1.615f, 2.35f)
        val FACE_SERIES = listOf("223406", "223421", "223424", "223426", "223437", "223440", "_2339")
    }
}
