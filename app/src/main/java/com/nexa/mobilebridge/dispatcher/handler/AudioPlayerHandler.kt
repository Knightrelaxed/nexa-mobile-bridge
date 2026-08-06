package com.nexa.mobilebridge.dispatcher.handler

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.util.Log
import com.nexa.mobilebridge.protocol.CommandResult
import com.nexa.mobilebridge.protocol.NexaCommand
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.JsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AudioPlayerHandler @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val TAG = "AudioPlayerHandler"

    companion object {
        private var activeRingtone: Ringtone? = null
        private var activeMediaPlayer: MediaPlayer? = null
    }

    @Synchronized
    fun playRingtone(command: NexaCommand): CommandResult {
        return try {
            stopAll()

            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val maxAlarmVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, maxAlarmVol, 0)

            val alarmUri: Uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)

            val mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(context, alarmUri)
                isLooping = true
                prepare()
                start()
            }
            activeMediaPlayer = mediaPlayer

            Log.i(TAG, "🔔 Emergency Alarm Ringtone started playing at 100% volume via MediaPlayer.")
            CommandResult.success(
                command.command_id,
                "Emergency Alarm Ringtone started playing at 100% volume."
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play ringtone via MediaPlayer", e)
            CommandResult.failure(command.command_id, "Failed to play ringtone: ${e.localizedMessage}")
        }
    }

    @Synchronized
    fun playMedia(command: NexaCommand): CommandResult {
        val url = (command.params?.get("url") as? JsonPrimitive)?.content
        if (url.isNullOrEmpty()) {
            return CommandResult.failure(command.command_id, "Missing 'url' parameter for PLAY_MEDIA")
        }

        return try {
            stopAll()

            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxVol, 0)

            val mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                setDataSource(context, Uri.parse(url))
                prepareAsync()
                setOnPreparedListener { start() }
                setOnCompletionListener {
                    stopAll()
                }
            }
            activeMediaPlayer = mediaPlayer

            Log.i(TAG, "🎵 Playing media URL: $url")
            CommandResult.success(command.command_id, "Media playback started: $url")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play media", e)
            CommandResult.failure(command.command_id, "Failed to play media: ${e.localizedMessage}")
        }
    }

    @Synchronized
    fun stopMedia(command: NexaCommand): CommandResult {
        stopAll()
        Log.i(TAG, "🛑 Audio playback stopped.")
        return CommandResult.success(command.command_id, "Audio playback stopped successfully.")
    }

    /**
     * Plays a server-provided audio reply encoded as Base64 PCM/WAV.
     * After playback completes, broadcasts CALL_REPLY_COMPLETE so FakeCallActivity can finish().
     *
     * Expected params: { "audio_base64": "<base64 string>", "audio_format": "PCM_16BIT_16KHZ_MONO" }
     * If audio_base64 is absent, plays nothing and signals completion immediately.
     */
    @Synchronized
    fun playAudioStream(command: NexaCommand): CommandResult {
        val audioBase64 = (command.params?.get("audio_base64") as? JsonPrimitive)?.content

        if (audioBase64.isNullOrEmpty()) {
            // No audio — just signal the activity to close
            broadcastCallReplyComplete()
            return CommandResult.success(command.command_id, "No audio in reply — call ended.")
        }

        return try {
            stopAll()
            val audioBytes = android.util.Base64.decode(audioBase64, android.util.Base64.NO_WRAP)

            // Write PCM to temp file then play via MediaPlayer
            val tempFile = java.io.File(context.cacheDir, "nexa_reply_${System.currentTimeMillis()}.pcm")
            tempFile.writeBytes(audioBytes)

            val mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                setDataSource(tempFile.absolutePath)
                prepare()
                start()
                setOnCompletionListener {
                    stopAll()
                    tempFile.delete()
                    broadcastCallReplyComplete()
                }
            }
            activeMediaPlayer = mediaPlayer

            Log.i(TAG, "🔊 Playing server audio reply (${audioBytes.size} bytes)")
            CommandResult.success(command.command_id, "Server audio reply playing.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play audio stream", e)
            broadcastCallReplyComplete() // Ensure activity still closes even on error
            CommandResult.failure(command.command_id, "Failed to play audio stream: ${e.localizedMessage}")
        }
    }

    /**
     * Broadcasts an intent to signal FakeCallActivity to finish after server reply is played.
     */
    private fun broadcastCallReplyComplete() {
        val intent = android.content.Intent("com.nexa.mobilebridge.CALL_REPLY_COMPLETE")
        intent.setPackage(context.packageName)
        context.sendBroadcast(intent)
        Log.i(TAG, "📡 CALL_REPLY_COMPLETE broadcast sent")
    }

    @Synchronized
    fun stopAll() {
        try {
            activeRingtone?.let {
                if (it.isPlaying) it.stop()
            }
            activeRingtone = null

            activeMediaPlayer?.let {
                if (it.isPlaying) it.stop()
                it.release()
            }
            activeMediaPlayer = null
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping active media", e)
        }
    }
}

