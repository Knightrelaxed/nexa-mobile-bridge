package com.nexa.mobilebridge

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.nexa.mobilebridge.NexaBridgeService
import com.nexa.mobilebridge.theme.NexaMobileBridgeTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NexaMobileBridgeTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainNavigation(
                        onToggleService = { start ->
                            val intent = Intent(this, NexaBridgeService::class.java)
                            if (start) {
                                startForegroundService(intent)
                            } else {
                                stopService(intent)
                            }
                        }
                    )
                }
            }
        }
    }
}
