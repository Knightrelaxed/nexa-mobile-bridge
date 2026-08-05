package com.nexa.mobilebridge.core.network

import com.nexa.mobilebridge.data.local.NexaPreferencesDataStore
import com.nexa.mobilebridge.protocol.TaskerEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NexaHttpClient @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val json: Json,
    private val preferences: NexaPreferencesDataStore
) {
    suspend fun sendTaskerEvent(event: TaskerEvent): Boolean = withContext(Dispatchers.IO) {
        try {
            val config = preferences.getConfig()
            val webhookUrl = config.taskerWebhook
            val authToken = config.authToken

            if (webhookUrl.isBlank()) return@withContext false

            val jsonBody = json.encodeToString(event)
            val requestBody = jsonBody.toRequestBody("application/json; charset=utf-8".toMediaType())

            val requestBuilder = Request.Builder()
                .url(webhookUrl)
                .post(requestBody)

            if (authToken.isNotBlank()) {
                requestBuilder.header("Authorization", "Bearer $authToken")
            }

            val request = requestBuilder.build()

            okHttpClient.newCall(request).execute().use { response ->
                return@withContext response.isSuccessful
            }
        } catch (e: IOException) {
            e.printStackTrace()
            return@withContext false
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext false
        }
    }
}
