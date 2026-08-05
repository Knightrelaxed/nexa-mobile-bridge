package com.nexa.mobilebridge.dispatcher.handler

import android.content.Context
import android.media.AudioManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.nexa.mobilebridge.protocol.CommandResult
import com.nexa.mobilebridge.protocol.NexaCommand
import com.nexa.mobilebridge.protocol.ResultStatus
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import kotlin.coroutines.resume

class TtsHandler(private val context: Context) : TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = null
    private var isInitialized = false

    init {
        tts = TextToSpeech(context, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            // Prefer Indonesian Locale
            val locale = Locale("id", "ID")
            if (tts?.isLanguageAvailable(locale) == TextToSpeech.LANG_AVAILABLE) {
                tts?.language = locale
            } else {
                tts?.language = Locale.ENGLISH
            }
            isInitialized = true
        }
    }

    suspend fun execute(command: NexaCommand): CommandResult {
        if (!isInitialized || tts == null) {
            return CommandResult.failure(command.command_id, "TTS engine not ready")
        }
        
        val text = command.params?.get("text")?.let { 
            if (it is kotlinx.serialization.json.JsonPrimitive) it.content else it.toString() 
        }
        
        if (text.isNullOrBlank()) {
            return CommandResult.failure(command.command_id, "Missing 'text' parameter")
        }

        // Unmute and set volume to ~70% so the AI can be heard
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val targetVol = (maxVol * 0.7).toInt()
        
        // Only increase volume if it's currently lower than target
        val currentVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        if (currentVol < targetVol) {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVol, AudioManager.FLAG_SHOW_UI)
        }

        return suspendCancellableCoroutine { continuation ->
            val utteranceId = "NexaTTS_${System.currentTimeMillis()}"
            
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                
                override fun onDone(utteranceId: String?) {
                    if (continuation.isActive) {
                        continuation.resume(
                            CommandResult(
                                command_id = command.command_id,
                                status = ResultStatus.SUCCESS,
                                message = "TTS spoken successfully"
                            )
                        )
                    }
                }
                
                override fun onError(utteranceId: String?) {
                    if (continuation.isActive) {
                        continuation.resume(
                            CommandResult.failure(command.command_id, "TTS playback error")
                        )
                    }
                }
            })

            val params = Bundle().apply {
                putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
            }
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
        }
    }
}
