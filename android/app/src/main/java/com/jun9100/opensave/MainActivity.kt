package com.jun9100.opensave

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.jun9100.opensave.ui.OpenSaveScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Draw behind the status and gesture bars; Compose pads with the insets.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            OpenSaveTheme {
                OpenSaveScreen()
            }
        }
    }
}
