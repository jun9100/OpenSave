package com.jun9100.opensave

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.jun9100.opensave.ui.OpenSaveScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            OpenSaveTheme {
                OpenSaveScreen()
            }
        }
    }
}
