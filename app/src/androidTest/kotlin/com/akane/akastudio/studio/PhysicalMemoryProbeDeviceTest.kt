package com.akane.akastudio.studio

import android.app.ActivityOptions
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Debug
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.akane.akastudio.AkaStudioApp
import com.akane.akastudio.MainActivity
import com.akane.akastudio.core.color.api.MasterLook
import com.akane.akastudio.core.export.api.ExportFormat
import com.akane.akastudio.core.export.api.ExportResolution
import com.akane.akastudio.feature.studio.mvi.StudioIntent
import com.akane.akastudio.feature.studio.mvi.StudioPhase
import com.akane.akastudio.feature.studio.mvi.StudioViewModel
import com.akane.akastudio.feature.studio.ui.studioViewModelFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Collections
import kotlin.concurrent.thread

/**
 * Manual memory probe for the physical phone (skipped unless `qaMemoryInputDir` is given). It drives the real app
 * process (real ViewModel, MediaStore uris, models, GL and export) through a fixed phase plan with one input set and
 * one setting set, and records process memory:
 *
 * - at every phase boundary: `/proc/self/status` (VmRSS, VmHWM, RssAnon/File/Shmem), `Debug.MemoryInfo` summary
 *   stats (total PSS/RSS, Java heap, native heap, graphics, private other), the native allocator and Java heap;
 * - per phase: the peak RSS from VmHWM after resetting it through `/proc/self/clear_refs` (recorded when the kernel
 *   refuses the reset), plus a 50 ms `/proc/self/statm` RSS series.
 *
 * Both input sets carry the same bytes under different MediaStore rows, so a story swap (L1: the replacement is
 * prepared while the retired story's photos are still held) and a discard-then-import control use identical inputs.
 * The instrumentation's own classes add a constant offset to every reading.
 */
@RunWith(AndroidJUnit4::class)
class PhysicalMemoryProbeDeviceTest {
    private val samples = Collections.synchronizedList(mutableListOf<String>())
    private val snapshots = mutableListOf<String>()
    private val peaks = mutableListOf<String>()

    @Volatile private var phase = "init"

    @Volatile private var sampling = true

    @Test
    fun storySwapDiscardAndUhdExportsMemoryProbe() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Manual memory probe requires qaMemoryInputDir", arguments.containsKey("qaMemoryInputDir"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val inputDir = checkNotNull(arguments.getString("qaMemoryInputDir"))
        require(inputDir.startsWith("/sdcard/Download/AkaStudioQA/")) { "inputs must stay under /sdcard/Download/AkaStudioQA/" }
        val swaps = arguments.getString("qaMemorySwaps")?.toInt() ?: 4
        val burst = arguments.getString("qaMemoryExportBurst")?.toInt() ?: 3
        val idleMs = (arguments.getString("qaMemoryIdleSeconds")?.toLong() ?: 10L) * 1000L
        val run = "mem_${System.currentTimeMillis()}"
        val outDir = File(context.filesDir, "akadroidqa/$run").apply { check(mkdirs()) }
        val inserted = mutableListOf<Uri>()
        val exports = mutableListOf<Uri>()

        val names =
            shell("ls $inputDir")
                .lines()
                .map(String::trim)
                .filter { it.endsWith(".jpg") }
                .sorted()
        check(names.size >= 2) { "need at least two inputs in $inputDir, found $names" }
        val bytes = names.associateWith { name -> shellBytes("cat $inputDir/$name") }
        bytes.forEach { (name, data) -> check(data.size > 1000) { "empty input $name" } }
        val setA = bytes.map { (name, data) -> insert(context, "${run}_A_$name", "Pictures/AkaStudioQA/$run/A", data) }
        inserted += setA
        val setB = bytes.map { (name, data) -> insert(context, "${run}_B_$name", "Pictures/AkaStudioQA/$run/B", data) }
        inserted += setB
        Log.i(TAG, "inputs ${names.size} bytes=${bytes.values.sumOf { it.size }} run=$run")

        val sampler =
            thread(name = "AkaMemSampler", isDaemon = true) {
                val start = SystemClock.elapsedRealtime()
                while (sampling) {
                    val rss =
                        runCatching {
                            File("/proc/self/statm")
                                .readText()
                                .trim()
                                .split(" ")[1]
                                .toLong() * 4
                        }.getOrDefault(-1)
                    samples += "${SystemClock.elapsedRealtime() - start}\t${SystemClock.elapsedRealtime()}\t$phase\t$rss"
                    SystemClock.sleep(SAMPLE_MS)
                }
            }

        val scenario =
            ActivityScenario.launch<MainActivity>(
                Intent(context, MainActivity::class.java),
                ActivityOptions.makeBasic().setLaunchDisplayId(0).toBundle(),
            )

        fun vm(): StudioViewModel {
            var result: StudioViewModel? = null
            scenario.onActivity {
                val container = (it.application as AkaStudioApp).container
                result = ViewModelProvider(it, studioViewModelFactory(container))[StudioViewModel::class.java]
            }
            return checkNotNull(result)
        }

        fun send(intent: StudioIntent) {
            val model = vm()
            scenario.onActivity { model.onIntent(intent) }
        }

        fun awaitReady(label: String) =
            awaitCondition(label, CURATION_TIMEOUT_MS) {
                val state = vm().state.value
                if (state.phase == StudioPhase.Empty && state.error != null) throw AssertionError("$label failed: ${state.error}")
                state.phase == StudioPhase.Ready
            }

        fun awaitEmpty(label: String) = awaitCondition(label, UI_TIMEOUT_MS) { vm().state.value.phase == StudioPhase.Empty }

        /** Runs [block] as phase [name]: snapshot before, HWM reset, block, HWM read, snapshot after. */
        fun phase(
            name: String,
            block: () -> Unit,
        ) {
            snapshot("$name:start")
            val reset = resetHwm()
            phase = name
            val t0 = SystemClock.elapsedRealtime()
            block()
            val ms = SystemClock.elapsedRealtime() - t0
            val hwm = status()["VmHWM"] ?: -1
            phase = "after:$name"
            peaks += "$name\t$ms\t$reset\t$hwm"
            Log.i(TAG, "PHASE $name ms=$ms hwmReset=$reset VmHWM=${hwm}kB")
            snapshot("$name:end")
        }

        fun idle(
            name: String,
            ms: Long,
        ) = phase(name) { SystemClock.sleep(ms) }

        fun export(
            name: String,
            format: ExportFormat,
        ) = phase(name) {
            val before =
                vm()
                    .state.value.export.lastResult
                    ?.uri
            send(StudioIntent.SetExportFormat(format))
            send(StudioIntent.SetExportResolution(ExportResolution.UHD_4K))
            send(StudioIntent.Export(share = false))
            var uri: Uri? = null
            awaitCondition("$name export", EXPORT_TIMEOUT_MS) {
                val state = vm().state.value
                if (state.error != null) throw AssertionError("$name failed: ${state.error}")
                val last = state.export.lastResult
                if (last != null && last.uri != before && state.export.progress == null) uri = Uri.parse(last.uri)
                uri != null
            }
            val written = checkNotNull(uri)
            exports += written
            val data = checkNotNull(context.contentResolver.openInputStream(written)).use { it.readBytes() }
            val text = String(data, Charsets.ISO_8859_1)
            val gain = text.contains("hdrgm") && text.contains("MPF")
            val last =
                checkNotNull(
                    vm()
                        .state.value.export.lastResult,
                )
            Log.i(TAG, "EXPORT $name format=${last.format} ${last.width}x${last.height} bytes=${data.size} gainMap=$gain")
            peaks += "$name:file\t${last.format}\t${last.width}x${last.height}\t${data.size}\tgainMap=$gain"
            if (format == ExportFormat.ULTRA_HDR_JPEG) assertTrue("$name must carry a gain map", gain)
            if (format == ExportFormat.SOCIAL_STORY_JPEG) assertTrue("$name must not carry a gain map", !gain)
        }

        try {
            idle("p0_launch_idle", idleMs)
            phase("p1_prepare_A") {
                send(StudioIntent.ImportPhotos(setA.map(Uri::toString)))
                awaitReady("prepare A")
            }
            send(StudioIntent.SelectLook(MasterLook.MEDITERRANEAN_AZURE))
            send(StudioIntent.SetHdrBoost(true))
            idle("p1_ready_idle", idleMs * 2)
            val selectedA =
                vm()
                    .state.value.selection.photoIds.size
            Log.i(TAG, "story A slots=$selectedA mode=${vm().state.value.selection.mode}")
            for (i in 1..swaps) {
                val next = if (i % 2 == 1) setB else setA
                phase("p2_swap_$i") {
                    send(StudioIntent.ImportPhotos(next.map(Uri::toString)))
                    awaitReady("swap $i")
                }
                idle("p2_swap_${i}_idle", idleMs)
            }
            phase("p3_discard") {
                send(StudioIntent.Reset)
                awaitEmpty("discard")
            }
            idle("p3_empty_idle", idleMs)
            phase("p3_reimport_after_discard") {
                send(StudioIntent.ImportPhotos(setB.map(Uri::toString)))
                awaitReady("reimport")
            }
            send(StudioIntent.SelectLook(MasterLook.MEDITERRANEAN_AZURE))
            send(StudioIntent.SetHdrBoost(true))
            idle("p3_ready_idle", idleMs)
            export("p4_social_uhd", ExportFormat.SOCIAL_STORY_JPEG)
            idle("p4_social_idle", idleMs)
            export("p4_ultra_uhd", ExportFormat.ULTRA_HDR_JPEG)
            idle("p4_ultra_idle", idleMs)
            for (i in 1..burst) export("p5_ultra_burst_$i", ExportFormat.ULTRA_HDR_JPEG)
            idle("p5_recovery_idle", idleMs * 3)
            phase("p6_final_discard") {
                send(StudioIntent.Reset)
                awaitEmpty("final discard")
            }
            idle("p6_empty_idle", idleMs * 2)
            // Not normal operation: one explicit GC separates collectable garbage from retained Java objects.
            phase("p7_gc_probe") {
                Runtime.getRuntime().gc()
                SystemClock.sleep(2000)
            }
            assertEquals(StudioPhase.Empty, vm().state.value.phase)
            Log.i(TAG, "PASS memory probe run=$run out=$outDir")
        } finally {
            sampling = false
            sampler.join(1000)
            File(outDir, "samples.tsv").writeText("t_ms\telapsed\tphase\trss_kb\n" + synchronized(samples) { samples.joinToString("\n") })
            File(outDir, "snapshots.tsv").writeText(SNAPSHOT_HEADER + "\n" + snapshots.joinToString("\n"))
            File(outDir, "peaks.tsv").writeText("phase\tms\thwm_reset\tvmhwm_kb\n" + peaks.joinToString("\n"))
            try {
                scenario.close()
            } finally {
                exports.forEach { runCatching { context.contentResolver.delete(it, null, null) } }
                inserted.forEach { runCatching { context.contentResolver.delete(it, null, null) } }
            }
        }
    }

    private fun snapshot(label: String) {
        val s = status()
        val mi = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }

        fun stat(key: String) = mi.getMemoryStat(key)?.toLongOrNull() ?: -1
        val rt = Runtime.getRuntime()
        val line =
            listOf(
                label,
                SystemClock.elapsedRealtime(),
                s["VmRSS"],
                s["VmHWM"],
                s["RssAnon"],
                s["RssFile"],
                s["RssShmem"],
                s["VmSwap"],
                stat("summary.total-pss"),
                stat("summary.total-rss"),
                stat("summary.java-heap"),
                stat("summary.native-heap"),
                stat("summary.graphics"),
                stat("summary.private-other"),
                stat("summary.code"),
                stat("summary.system"),
                Debug.getNativeHeapAllocatedSize() / 1024,
                (rt.totalMemory() - rt.freeMemory()) / 1024,
            ).joinToString("\t")
        snapshots += line
        Log.i(TAG, "SNAP $line")
    }

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
                if (kb != null && (key.startsWith("Vm") || key.startsWith("Rss"))) key to kb else null
            }.toMap()

    /** Resets VmHWM to the current RSS; returns whether the kernel accepted it (VmHWM then close to VmRSS). */
    private fun resetHwm(): Boolean =
        runCatching {
            File("/proc/self/clear_refs").writeText("5")
            val s = status()
            (s["VmHWM"] ?: Long.MAX_VALUE) <= (s["VmRSS"] ?: 0L) + 4096
        }.getOrDefault(false)

    private fun shell(command: String): String = String(shellBytes(command))

    private fun shellBytes(command: String): ByteArray {
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() }
    }

    private fun insert(
        context: android.content.Context,
        name: String,
        relativePath: String,
        data: ByteArray,
    ): Uri {
        val resolver = context.contentResolver
        val values =
            ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, relativePath)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        val uri = checkNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
        checkNotNull(resolver.openOutputStream(uri)).use { it.write(data) }
        resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        return uri
    }

    private fun awaitCondition(
        label: String,
        timeoutMs: Long,
        condition: () -> Boolean,
    ) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (!condition()) {
            if (SystemClock.elapsedRealtime() > deadline) throw AssertionError("Timed out after $timeoutMs ms: $label")
            SystemClock.sleep(POLL_MS)
        }
    }

    private companion object {
        const val TAG = "AkaMem"
        const val SAMPLE_MS = 50L
        const val POLL_MS = 25L
        const val CURATION_TIMEOUT_MS = 180_000L
        const val EXPORT_TIMEOUT_MS = 240_000L
        const val UI_TIMEOUT_MS = 30_000L
        const val SNAPSHOT_HEADER =
            "label\telapsed\tvmrss_kb\tvmhwm_kb\trss_anon_kb\trss_file_kb\trss_shmem_kb\tvmswap_kb\t" +
                "pss_kb\tmi_rss_kb\tjava_heap_kb\tnative_heap_kb\tgraphics_kb\tprivate_other_kb\tcode_kb\tsystem_kb\t" +
                "native_alloc_kb\tjava_used_kb"
    }
}
