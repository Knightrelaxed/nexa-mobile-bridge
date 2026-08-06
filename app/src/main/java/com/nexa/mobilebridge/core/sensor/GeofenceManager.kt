package com.nexa.mobilebridge.core.sensor

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingClient
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices

class GeofenceManager(private val context: Context) {

    private val TAG = "GeofenceManager"
    private val geofencingClient: GeofencingClient = LocationServices.getGeofencingClient(context)

    // Dummy coordinates for testing (You can change these to your actual home/work coordinates)
    private val GEOFENCE_HOME_ID = "HOME"
    private val HOME_LAT = -6.2088 // Jakarta example
    private val HOME_LNG = 106.8456

    private val GEOFENCE_WORK_ID = "WORK"
    private val WORK_LAT = -6.2255 // Example
    private val WORK_LNG = 106.8080

    private val GEOFENCE_RADIUS_METERS = 100f // 100 meters

    private val geofencePendingIntent: PendingIntent by lazy {
        val intent = Intent(context, GeofenceBroadcastReceiver::class.java)
        // FLAG_UPDATE_CURRENT and FLAG_MUTABLE are required for Geofencing PendingIntents
        PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
    }

    @SuppressLint("MissingPermission") // We will ensure permission is granted before calling
    fun startGeofencing() {
        if (!hasLocationPermissions()) {
            Log.w(TAG, "Location permissions not granted. Cannot start Geofencing.")
            return
        }

        Log.i(TAG, "🟢 Memulai GPS Geofencing...")
        
        val geofences = listOf(
            createGeofence(GEOFENCE_HOME_ID, HOME_LAT, HOME_LNG),
            createGeofence(GEOFENCE_WORK_ID, WORK_LAT, WORK_LNG)
        )

        val geofencingRequest = GeofencingRequest.Builder()
            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER or GeofencingRequest.INITIAL_TRIGGER_EXIT)
            .addGeofences(geofences)
            .build()

        geofencingClient.addGeofences(geofencingRequest, geofencePendingIntent).run {
            addOnSuccessListener {
                Log.d(TAG, "✅ Geofences berhasil ditambahkan.")
            }
            addOnFailureListener { e ->
                Log.e(TAG, "❌ Gagal menambahkan Geofences: ${e.message}")
            }
        }
    }

    fun stopGeofencing() {
        Log.i(TAG, "🛑 Menghentikan GPS Geofencing...")
        geofencingClient.removeGeofences(geofencePendingIntent).run {
            addOnSuccessListener {
                Log.d(TAG, "✅ Geofences berhasil dihapus.")
            }
            addOnFailureListener { e ->
                Log.e(TAG, "❌ Gagal menghapus Geofences: ${e.message}")
            }
        }
    }

    private fun createGeofence(id: String, lat: Double, lng: Double): Geofence {
        return Geofence.Builder()
            .setRequestId(id)
            .setCircularRegion(lat, lng, GEOFENCE_RADIUS_METERS)
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT)
            .build()
    }

    private fun hasLocationPermissions(): Boolean {
        val fineLocation = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val backgroundLocation = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        return fineLocation && backgroundLocation
    }
}
