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
            val result = tts?.isLanguageAvailable(locale) ?: TextToSpeech.LANG_NOT_SUPPORTED
            if (result >= TextToSpeech.LANG_AVAILABLE) {
                tts?.language = locale
            } else {
                val fallbackLocale = Locale("id")
                val fallbackResult = tts?.isLanguageAvailable(fallbackLocale) ?: TextToSpeech.LANG_NOT_SUPPORTED
                if (fallbackResult >= TextToSpeech.LANG_AVAILABLE) {
                    tts?.language = fallbackLocale
                } else {
                    tts?.language = Locale.ENGLISH
                }
            }
            isInitialized = true
        }
    }

    suspend fun execute(command: NexaCommand): CommandResult {
        // Wait up to 5 seconds for TTS engine to initialize
        var retries = 50
        while (!isInitialized && retries > 0) {
            kotlinx.coroutines.delay(100)
            retries--
        }

        if (!isInitialized || tts == null) {
            return CommandResult.failure(command.command_id, "TTS engine not ready after timeout")
        }
        
        val rawText = command.params?.get("text")?.let { 
            if (it is kotlinx.serialization.json.JsonPrimitive) it.content else it.toString() 
        }
        
        if (rawText.isNullOrBlank()) {
            return CommandResult.failure(command.command_id, "Missing 'text' parameter")
        }

        // Sanitize phonetic pronunciation: replace "N.E.X.A" or "NEXA" with Title Case "Nexa" so Android TTS speaks "Nexa" as a natural word (not "en-e-ex-a")
        val text = rawText.replace(Regex("(?i)\\bN\\.?E\\.?X\\.?A\\.?\\b|N\\.E\\.X\\.A"), "Nexa")

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
                    // Notify any active FakeCallActivity that the spoken reply is complete
                    try {
                        val completeIntent = android.content.Intent("com.nexa.mobilebridge.CALL_REPLY_COMPLETE")
                        completeIntent.setPackage(context.packageName)
                        context.sendBroadcast(completeIntent)
                    } catch (_: Exception) {}

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
            val result = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
            if (result == TextToSpeech.ERROR) {
                if (continuation.isActive) {
                    continuation.resume(CommandResult.failure(command.command_id, "TTS failed to enqueue the utterance"))
                }
            }
        }
    }
}
