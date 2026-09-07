package com.nexa.mobilebridge.core.sensor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofenceStatusCodes
import com.google.android.gms.location.GeofencingEvent
import com.nexa.mobilebridge.NexaBridgeService

class GeofenceBroadcastReceiver : BroadcastReceiver() {
    private val TAG = "GeofenceReceiver"

    override fun onReceive(context: Context, intent: Intent) {
        val geofencingEvent = GeofencingEvent.fromIntent(intent) ?: return
        if (geofencingEvent.hasError()) {
            val errorMessage = GeofenceStatusCodes.getStatusCodeString(geofencingEvent.errorCode)
            Log.e(TAG, "Geofencing Error: $errorMessage")
            return
        }

        val geofenceTransition = geofencingEvent.geofenceTransition
        if (geofenceTransition == Geofence.GEOFENCE_TRANSITION_ENTER || geofenceTransition == Geofence.GEOFENCE_TRANSITION_EXIT) {
            
            val triggeringGeofences = geofencingEvent.triggeringGeofences ?: return
            for (geofence in triggeringGeofences) {
                val geofenceId = geofence.requestId
                val transitionString = if (geofenceTransition == Geofence.GEOFENCE_TRANSITION_ENTER) "ENTER" else "EXIT"
                
                Log.i(TAG, " Geofence Transition: $transitionString for $geofenceId")
                
                // Forward the event to the NexaBridgeService
                val serviceIntent = Intent(context, NexaBridgeService::class.java).apply {
                    action = "ACTION_GEOFENCE_EVENT"
                    putExtra("geofence_id", geofenceId)
                    putExtra("transition", transitionString)
                }
                
                // Since this might be called in the background, we use startForegroundService 
                // In Android 8+, if the service is not running, startService from background will crash.
                // NexaBridgeService is a foreground service and will call startForeground in onStartCommand.
                try {
                    androidx.core.content.ContextCompat.startForegroundService(context, serviceIntent)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to forward intent to NexaBridgeService: ${e.message}")
                }
            }
        } else {
            Log.e(TAG, "Invalid transition type: $geofenceTransition")
        }
    }
}
