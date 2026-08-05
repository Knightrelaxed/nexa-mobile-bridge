package com.nexa.mobilebridge

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class NexaBridgeApp : Application() {
    override fun onCreate() {
        super.onCreate()
    }
}
