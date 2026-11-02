package com.akane.akastudio.studio

import android.app.ActivityOptions
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.ViewTreeObserver
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.akane.akastudio.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Opt-in physical-display viewer of prepared QA artifacts; it does not approve an M1 product option. */
@RunWith(AndroidJUnit4::class)
class PhysicalHdrViewerDeviceTest {

    @Test
    fun viewPreparedHdrComparisonsOnThePhysicalDisplay() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Manual viewer requires qaViewerDirectory", arguments.containsKey("qaViewerDirectory"))
        val context = instrumentation.targetContext
        val allowedDirectory = File(context.filesDir, "akadroidqa").canonicalFile
        require(allowedDirectory.parentFile == context.filesDir.canonicalFile) {
            "QA artifact directory must remain app-private"
        }
        val directory = File(arguments.getString("qaViewerDirectory") ?: allowedDirectory.path)
        require(directory.isAbsolute) { "qaViewerDirectory must be absolute" }
        val canonicalDirectory = directory.canonicalFile
        require(
            canonicalDirectory == allowedDirectory ||
                canonicalDirectory.path.startsWith(allowedDirectory.path + File.separator),
        ) { "qaViewerDirectory must stay inside the app-private akadroidqa directory" }
        require(canonicalDirectory.isDirectory) { "Prepared QA directory is missing" }
        val displayId = arguments.getString("qaViewerDisplayId")?.toInt() ?: Display.DEFAULT_DISPLAY
        require(displayId == Display.DEFAULT_DISPLAY) { "Physical HDR inspection requires display 0" }
        val holdSeconds = (arguments.getString("qaViewerHoldSeconds")?.toLong() ?: 180L).coerceIn(1L, 600L)
        val autoAdvanceSeconds = arguments.getString("qaViewerAutoAdvanceSeconds")?.toLong() ?: 0L
        require(autoAdvanceSeconds == 0L || autoAdvanceSeconds in 3L..60L)
        val sampleMillis = arguments.getString("qaViewerSampleMillis")?.toLong() ?: 0L
        require(sampleMillis == 0L || sampleMillis in 100L..5_000L)
        val files = checkNotNull(canonicalDirectory.listFiles()) { "Cannot list prepared QA directory" }
            .filter { file ->
                file.isFile && SUFFIXES.any(file.name::endsWith) &&
                    file.canonicalFile.parentFile == canonicalDirectory
            }
            .sortedBy { it.name }
        require(files.isNotEmpty()) { "No prepared Social JPEG, Ultra HDR, or SDR-base QA artifacts" }
        val requestedIndex = AtomicInteger(0)
        var shownIndex = -1
        var currentBitmap: Bitmap? = null
        var imageView: ImageView? = null
        var label: TextView? = null
        var pendingDraw: ViewTreeObserver.OnDrawListener? = null
        var pendingSample: Runnable? = null
        var previousColorMode = ActivityInfo.COLOR_MODE_DEFAULT
        var previousDesiredHeadroom = 0f
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle()
        val scenario = ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java), options)
        try {
            scenario.onActivity { activity ->
                previousColorMode = activity.window.colorMode
                if (Build.VERSION.SDK_INT >= 35) {
                    previousDesiredHeadroom = activity.window.desiredHdrHeadroom
                    activity.window.setDesiredHdrHeadroom(0f)
                }
                activity.window.colorMode = ActivityInfo.COLOR_MODE_HDR
                val root = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    setBackgroundColor(Color.BLACK)
                    setOnApplyWindowInsetsListener { view, insets ->
                        val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
                        view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                        insets
                    }
                }
                label = TextView(activity).apply { setTextColor(Color.WHITE) }
                root.addView(label)
                imageView = ImageView(activity).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setBackgroundColor(Color.BLACK)
                }
                root.addView(imageView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
                val buttons = LinearLayout(activity)
                listOf("Previous" to -1, "Next" to 1).forEach { (title, offset) ->
                    buttons.addView(
                        Button(activity).apply {
                            text = title
                            setOnClickListener {
                                requestedIndex.updateAndGet { (it + offset + files.size) % files.size }
                            }
                        },
                        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
                    )
                }
                root.addView(buttons)
                activity.setContentView(root)
                root.requestApplyInsets()
                Log.i(TAG, "viewer requestedDisplayId=$displayId artifacts=${files.size} holdSeconds=$holdSeconds autoAdvanceSeconds=$autoAdvanceSeconds")
            }
            val started = SystemClock.uptimeMillis()
            val deadline = started + holdSeconds * 1_000L
            var nextSample = started
            while (SystemClock.uptimeMillis() < deadline) {
                if (autoAdvanceSeconds > 0L) {
                    requestedIndex.set(((SystemClock.uptimeMillis() - started) / (autoAdvanceSeconds * 1_000L) % files.size).toInt())
                }
                val index = requestedIndex.get()
                if (index != shownIndex) {
                    val file = files[index]
                    // The instrumentation thread owns file IO and decode; the Activity thread only installs the bitmap.
                    val bytes = file.readBytes()
                    val bitmap = checkNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size)) {
                        "Cannot decode QA artifact ${file.name}"
                    }
                    var installed = false
                    try {
                        scenario.onActivity { activity ->
                            val view = checkNotNull(imageView)
                            check(view.isAttachedToWindow) { "Viewer detached or Activity recreated; restart inspection" }
                            assertTrue("Viewer requires hardware acceleration", view.isHardwareAccelerated)
                            pendingDraw?.let { view.viewTreeObserver.removeOnDrawListener(it) }
                            pendingSample?.let(view::removeCallbacks)
                            val previous = currentBitmap
                            view.setImageBitmap(bitmap)
                            currentBitmap = bitmap
                            installed = true
                            previous?.recycle()
                            label?.text = "${index + 1}/${files.size}: ${file.name}"
                            record(activity, view, bitmap, file.name, "installed", displayId)
                            val listener = object : ViewTreeObserver.OnDrawListener {
                                private var posted = false

                                override fun onDraw() {
                                    if (posted) return
                                    posted = true
                                    view.post {
                                        if (view.viewTreeObserver.isAlive) {
                                            view.viewTreeObserver.removeOnDrawListener(this)
                                        }
                                        if (pendingDraw !== this) return@post
                                        pendingDraw = null
                                        val sample = Runnable {
                                            if (view.isAttachedToWindow && currentBitmap === bitmap && !activity.isDestroyed) {
                                                record(activity, view, bitmap, file.name, "afterDraw", displayId)
                                            }
                                        }
                                        pendingSample = sample
                                        view.postDelayed(sample, 1_000L)
                                    }
                                }
                            }
                            pendingDraw = listener
                            view.viewTreeObserver.addOnDrawListener(listener)
                            view.invalidate()
                        }
                    } finally {
                        if (!installed) bitmap.recycle()
                    }
                    shownIndex = index
                }
                if (sampleMillis > 0L && SystemClock.uptimeMillis() >= nextSample) {
                    scenario.onActivity { activity ->
                        val view = checkNotNull(imageView)
                        check(view.isAttachedToWindow) { "Viewer detached during telemetry" }
                        record(activity, view, checkNotNull(currentBitmap), files[shownIndex].name, "periodic", displayId)
                    }
                    nextSample = SystemClock.uptimeMillis() + sampleMillis
                }
                SystemClock.sleep(100L)
            }
        } finally {
            try {
                instrumentation.runOnMainSync {
                    imageView?.let { view ->
                        pendingDraw?.let { if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnDrawListener(it) }
                        pendingSample?.let(view::removeCallbacks)
                        view.setImageDrawable(null)
                    }
                    currentBitmap?.recycle()
                    currentBitmap = null
                }
                if (scenario.state != Lifecycle.State.DESTROYED) {
                    scenario.onActivity { activity ->
                        activity.window.colorMode = previousColorMode
                        if (Build.VERSION.SDK_INT >= 35) activity.window.setDesiredHdrHeadroom(previousDesiredHeadroom)
                    }
                }
            } finally {
                scenario.close()
            }
        }
    }

    private fun record(
        activity: MainActivity,
        view: ImageView,
        bitmap: Bitmap,
        name: String,
        phase: String,
        requestedDisplayId: Int,
    ) {
        val display = checkNotNull(view.display)
        assertEquals("Activity must be on physical display 0", Display.DEFAULT_DISPLAY, activity.display?.displayId)
        assertEquals("Attached viewer must be on requested display", requestedDisplayId, display.displayId)
        val available = Build.VERSION.SDK_INT >= 34 && display.isHdrSdrRatioAvailable
        val ratio = if (Build.VERSION.SDK_INT >= 34) display.hdrSdrRatio.toString() else "unsupported"
        val maximum = if (Build.VERSION.SDK_INT >= 36) display.highestHdrSdrRatio.toString() else "unsupported"
        val desired = if (Build.VERSION.SDK_INT >= 35) activity.window.desiredHdrHeadroom.toString() else "unsupported"
        val gainmap = if (Build.VERSION.SDK_INT >= 34) bitmap.hasGainmap().toString() else "unsupported"
        val gain = if (Build.VERSION.SDK_INT >= 34) bitmap.gainmap else null
        Log.i(
            TAG,
            "wallMillis=${System.currentTimeMillis()} uptimeMillis=${SystemClock.uptimeMillis()} " +
                "artifact=$name phase=$phase requestedDisplayId=$requestedDisplayId activityDisplayId=${activity.display?.displayId} " +
                "viewDisplayId=${display.displayId} requestedColorMode=${ActivityInfo.COLOR_MODE_HDR} " +
                "windowColorMode=${activity.window.colorMode} displayIsHdr=${display.isHdr} " +
                "displayIsWideColorGamut=${display.isWideColorGamut} windowIsWideColorGamut=${activity.window.isWideColorGamut} " +
                "hdrSdrRatioAvailable=$available currentHdrSdrRatio=$ratio maximumHdrSdrRatio=$maximum " +
                "desiredHdrHeadroom=$desired hardwareAccelerated=${view.isHardwareAccelerated} focused=${view.hasWindowFocus()} " +
                "hasGainmap=$gainmap bitmapConfig=${bitmap.config} colorSpace=${bitmap.colorSpace} " +
                "width=${bitmap.width} height=${bitmap.height} " +
                "gainRatioMin=${gain?.ratioMin?.contentToString()} gainRatioMax=${gain?.ratioMax?.contentToString()} " +
                "gainGamma=${gain?.gamma?.contentToString()} gainEpsilonSdr=${gain?.epsilonSdr?.contentToString()} " +
                "gainEpsilonHdr=${gain?.epsilonHdr?.contentToString()} fullGainDisplayRatio=${gain?.displayRatioForFullHdr}",
        )
    }

    private companion object {
        const val TAG = "AkaHdrQA"
        val SUFFIXES = listOf("_social.jpg", "_ultra-hdr.jpg", "_sdr-base.png")
    }
}
