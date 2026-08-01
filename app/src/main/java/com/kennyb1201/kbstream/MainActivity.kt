package com.kennyb1201.kbstream

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import com.kennyb1201.kbstream.ui.theme.KBStreamTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Let Compose draw behind system bars / into the full physical
        // display instead of letting the window manager reserve extra
        // inset space -- this is what was causing every list (cast,
        // crew, episodes, reviews) to render clipped from the bottom.
        WindowCompat.setDecorFitsSystemWindows(window, false)

        setContent {
            KBStreamTheme {
                // Your existing NavHost / root composable goes here.
                // Keep whatever you already had inside setContent --
                // only the two lines above (import + setDecorFitsSystemWindows)
                // and the theme wrapper are what changed.
                KBStreamApp()
            }
        }
    }
}
