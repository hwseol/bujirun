package com.mhmh2.bujirun

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.mhmh2.bujirun.ui.BujirunApp
import com.mhmh2.bujirun.ui.theme.BujirunTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        enableEdgeToEdge()
        setContent {
            BujirunTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    BujirunApp()
                }
            }
        }
    }
}
