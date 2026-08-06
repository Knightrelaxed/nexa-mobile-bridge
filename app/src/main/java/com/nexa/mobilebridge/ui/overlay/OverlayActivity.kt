package com.nexa.mobilebridge.ui.overlay

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexa.mobilebridge.core.network.NexaWebSocketClient
import com.nexa.mobilebridge.protocol.CommandResult
import com.nexa.mobilebridge.protocol.ResultStatus
import com.nexa.mobilebridge.theme.NexaMobileBridgeTheme
import dagger.hilt.android.AndroidEntryPoint
import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Serializable
data class OverlayOption(
    val label: String,
    val action: String,
    val color: String? = null
)

@AndroidEntryPoint
class OverlayActivity : ComponentActivity() {

    @Inject
    lateinit var webSocketClient: NexaWebSocketClient
    
    @Inject
    lateinit var json: Json

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val commandId = intent.getStringExtra("COMMAND_ID") ?: "unknown"
        val message = intent.getStringExtra("MESSAGE") ?: "Pesan dari N.E.X.A"
        val optionsJson = intent.getStringExtra("OPTIONS_JSON")

        val options: List<OverlayOption> = try {
            if (!optionsJson.isNullOrBlank()) {
                json.decodeFromString<List<OverlayOption>>(optionsJson)
            } else {
                defaultOptions()
            }
        } catch (e: Exception) {
            Log.e("OverlayActivity", "Error parsing options: ${e.message}")
            defaultOptions()
        }

        setContent {
            NexaMobileBridgeTheme {
                // Dimmed background
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0x99000000)),
                    contentAlignment = Alignment.Center
                ) {
                    OverlayDialog(
                        message = message,
                        options = options,
                        onResult = { resultAction ->
                            sendResultAndClose(commandId, resultAction)
                        }
                    )
                }
            }
        }
    }

    private fun sendResultAndClose(commandId: String, action: String) {
        val payloadObj = buildJsonObject {
            put("type", "COMMAND_RESULT")
            put("command_id", commandId)
            put("status", "SUCCESS")
            put("message", "User selected: $action")
            put("data", buildJsonObject { put("action", action) })
            put("timestamp", System.currentTimeMillis())
        }
        webSocketClient.send(payloadObj.toString())
        finish()
    }
    private fun defaultOptions() = listOf(
        OverlayOption("TIDAK", "NO", "#FF5252"),
        OverlayOption("BICARA", "VOICE", "#CE93D8"),
        OverlayOption("YA", "YES", "#00E676")
    )
}

@Composable
fun OverlayDialog(message: String, options: List<OverlayOption>, onResult: (String) -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth(0.85f)
            .padding(16.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF111720))
    ) {
        Column(
            modifier = Modifier
                .padding(24.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "N.E.X.A SYSTEM",
                color = Color(0xFF00E5FF),
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                letterSpacing = 1.sp
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = message,
                color = Color(0xFFCDD6F4),
                fontFamily = FontFamily.SansSerif,
                fontSize = 18.sp,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(24.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                options.forEach { option ->
                    val btnColor = try {
                        if (option.color != null) {
                            Color(android.graphics.Color.parseColor(option.color))
                        } else {
                            Color.Gray
                        }
                    } catch(e: Exception) { 
                        Color.Gray 
                    }

                    Button(
                        onClick = { onResult(option.action) },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = btnColor),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(vertical = 10.dp, horizontal = 4.dp)
                    ) {
                        Text(
                            text = option.label,
                            fontFamily = FontFamily.SansSerif,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            maxLines = 1,
                            color = Color.White
                        )
                    }
                }
            }
        }
    }
}
