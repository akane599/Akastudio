package com.akane.akastudio

import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/** On-device smoke: the launcher activity starts on the real AkaStudioApp, wires its container and reaches RESUMED. */
@RunWith(AndroidJUnit4::class)
class MainActivityDeviceSmokeTest {

    @Test
    fun mainActivityLaunchesAndResumes() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
            scenario.onActivity { activity ->
                val app = activity.application
                assertEquals(AkaStudioApp::class.java, app.javaClass)
                assertNotNull((app as AkaStudioApp).container)
            }
        }
    }
}
