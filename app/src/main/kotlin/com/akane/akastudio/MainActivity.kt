package com.akane.akastudio

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.akane.akastudio.feature.studio.ui.StudioRoute
import com.akane.akastudio.feature.studio.ui.theme.DarkroomTheme

/** The single activity: hosts the studio edge to edge on the Darkroom theme. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Dark bar style: light system-bar icons over the always-dark Darkroom UI, whatever the system theme.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        val container = (application as AkaStudioApp).container
        setContent {
            DarkroomTheme {
                StudioRoute(container)
            }
        }
    }
}
