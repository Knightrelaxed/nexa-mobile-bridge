package com.nexa.mobilebridge.core.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    /**
     * Connection A (/ws) OkHttpClient with 60s ping interval (NFR-BAT-01).
     */
    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .pingInterval(60, TimeUnit.SECONDS) // NFR-BAT-01: 60s idle heartbeat for battery & NAT balance
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS) // No read timeout for WS
            .retryOnConnectionFailure(true)
            .build()
    }

    /**
     * A-M2 / A-C5: Dedicated Connection B (/io/logs & /io/term) OkHttpClient with pingInterval = 0
     * so OkHttp never wakes the cellular radio while terminal is paused for screen-off;
     * Connection B manages its own explicit 15s application PING heartbeat.
     */
    @Provides
    @Singleton
    @Named("sidecar")
    fun provideSidecarOkHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .pingInterval(0, TimeUnit.SECONDS)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }

    @Provides
    @Singleton
    fun provideJson(): Json {
        return Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = true
        }
    }
}
