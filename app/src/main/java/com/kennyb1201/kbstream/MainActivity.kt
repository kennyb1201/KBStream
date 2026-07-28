package com.kennyb1201.kbstream

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Box(modifier = androidx.compose.ui.Modifier.fillMaxSize()) {
                    // TODO: HomeScreen() — catalog rails go here
                    Text("KBStream — hello world")
                }
            }
        }
    }
}
