package com.nexa.mobilebridge.core.sensor

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingClient
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.Executor
import kotlin.coroutines.resume

class GeofenceManager(private val context: Context) {

    private val TAG = "GeofenceManager"
    private val PREFS_NAME = "nexa_geofence_zones"
    private val PREFS_KEY_ZONES = "active_zones_json"

    private val geofencingClient: GeofencingClient = LocationServices.getGeofencingClient(context)
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }

    private val geofencePendingIntent: PendingIntent by lazy {
        val intent = Intent(context, GeofenceBroadcastReceiver::class.java)
        PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
    }

    /**
     * Membaca semua zona geofence dari penyimpanan lokal dan mendaftarkannya ke Google Play Services.
     * Dipanggil saat service menyala atau setelah HP selesai restart.
     */
    fun loadAndRegisterAll() {
        val zones = getAllGeofences()
        if (zones.isEmpty()) {
            Log.i(TAG, "ℹ Belum ada geofence tersimpan. Menunggu perintah penandaan lokasi.")
            return
        }

        if (!hasLocationPermissions()) {
            Log.w(TAG, " Izin lokasi belum diberikan. Tidak bisa mendaftarkan ${zones.size} geofence.")
            return
        }

        Log.i(TAG, " Mendaftarkan ${zones.size} zona Geofence aktif ke Google Play Services...")
        registerWithPlayServices(zones)
    }

    /**
     * Menambahkan atau memperbarui titik geofence secara dinamis.
     */
    fun addOrUpdateGeofence(zone: GeofenceZone): Boolean {
        val current = getAllGeofences().toMutableList()
        current.removeAll { it.id.equals(zone.id, ignoreCase = true) }
        current.add(zone)
        saveGeofences(current)

        if (hasLocationPermissions()) {
            registerWithPlayServices(current)
            Log.i(TAG, " Geofence [${zone.id}] (${zone.name}) berhasil disimpan & didaftarkan.")
            return true
        }
        return false
    }

    /**
     * Mengambil lokasi GPS presisi saat ini dan langsung menjadikannya titik Geofence.
     */
    @SuppressLint("MissingPermission")
    suspend fun markCurrentLocation(
        id: String,
        name: String = id,
        radiusMeters: Float = 100f
    ): GeofenceZone? {
        val location = fetchCurrentLocation() ?: return null
        val zone = GeofenceZone(
            id = id.uppercase(),
            name = if (name.isNotBlank()) name else id,
            latitude = location.latitude,
            longitude = location.longitude,
            radiusMeters = radiusMeters,
            createdAt = System.currentTimeMillis()
        )
        addOrUpdateGeofence(zone)
        return zone
    }

    /**
     * Menghapus titik geofence berdasarkan ID.
     */
    fun removeGeofence(id: String): Boolean {
        val current = getAllGeofences().toMutableList()
        val removed = current.removeAll { it.id.equals(id, ignoreCase = true) }
        if (!removed) return false

        saveGeofences(current)
        geofencingClient.removeGeofences(listOf(id.uppercase())).run {
            addOnSuccessListener { Log.i(TAG, " Geofence [$id] berhasil dihapus dari Play Services.") }
            addOnFailureListener { e -> Log.e(TAG, "Gagal menghapus [$id] dari Play Services: ${e.message}") }
        }
        return true
    }

    /**
     * Menghapus semua geofence.
     */
    fun clearAllGeofences(): Boolean {
        saveGeofences(emptyList())
        geofencingClient.removeGeofences(geofencePendingIntent).run {
            addOnSuccessListener { Log.i(TAG, " Semua Geofence berhasil dibersihkan.") }
            addOnFailureListener { e -> Log.e(TAG, "Gagal membersihkan geofence: ${e.message}") }
        }
        return true
    }

    /**
     * Menghentikan monitoring geofence saat service dimatikan (tanpa menghapus data tersimpan).
     */
    fun stopGeofencing() {
        geofencingClient.removeGeofences(geofencePendingIntent).run {
            addOnSuccessListener { Log.i(TAG, " Geofence monitoring dihentikan.") }
            addOnFailureListener { e -> Log.e(TAG, "Gagal menghentikan geofence monitoring: ${e.message}") }
        }
    }

    /**
     * Mengembalikan daftar semua geofence yang tersimpan.
     */
    fun getAllGeofences(): List<GeofenceZone> {
        val rawJson = prefs.getString(PREFS_KEY_ZONES, null) ?: return emptyList()
        return try {
            json.decodeFromString<List<GeofenceZone>>(rawJson)
        } catch (e: Exception) {
            Log.e(TAG, "Gagal mem-parse geofences dari SharedPreferences: ${e.message}")
            emptyList()
        }
    }

    // ─────────────────────────────────────────────
    // Internal Play Services Registration
    // ─────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private fun registerWithPlayServices(zones: List<GeofenceZone>) {
        if (zones.isEmpty() || !hasLocationPermissions()) return

        val geofenceList = zones.map { zone ->
            Geofence.Builder()
                .setRequestId(zone.id.uppercase())
                .setCircularRegion(zone.latitude, zone.longitude, zone.radiusMeters)
                .setExpirationDuration(Geofence.NEVER_EXPIRE)
                .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT)
                .build()
        }

        val request = GeofencingRequest.Builder()
            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER or GeofencingRequest.INITIAL_TRIGGER_EXIT)
            .addGeofences(geofenceList)
            .build()

        geofencingClient.addGeofences(request, geofencePendingIntent).run {
            addOnSuccessListener {
                Log.d(TAG, " ${geofenceList.size} Geofence berhasil aktif di background.")
            }
            addOnFailureListener { e ->
                Log.e(TAG, " Gagal mengaktifkan Geofence: ${e.message}")
            }
        }
    }

    private fun saveGeofences(zones: List<GeofenceZone>) {
        val rawJson = json.encodeToString(zones)
        prefs.edit().putString(PREFS_KEY_ZONES, rawJson).apply()
    }

    @SuppressLint("MissingPermission")
    private suspend fun fetchCurrentLocation(): Location? {
        if (!hasLocationPermissions()) return null

        // 1. Google Play Services Fused Location
        val fusedClient = LocationServices.getFusedLocationProviderClient(context)
        var location: Location? = suspendCancellableCoroutine { continuation ->
            fusedClient.lastLocation
                .addOnSuccessListener { if (continuation.isActive) continuation.resume(it) }
                .addOnFailureListener { if (continuation.isActive) continuation.resume(null) }
        }

        // 2. Fallback to LocationManager if null
        if (location == null) {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            location = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)
        }

        // 3. Fallback to getCurrentLocation (Android 11+)
        if (location == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val isNetwork = lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            val provider = if (isNetwork) LocationManager.NETWORK_PROVIDER else LocationManager.GPS_PROVIDER

            location = suspendCancellableCoroutine { continuation ->
                val cancellationSignal = android.os.CancellationSignal()
                continuation.invokeOnCancellation { cancellationSignal.cancel() }
                val executor = Executor { it.run() }
                lm.getCurrentLocation(provider, cancellationSignal, executor) { loc ->
                    if (continuation.isActive) continuation.resume(loc)
                }
            }
        }

        return location
    }

    private fun hasLocationPermissions(): Boolean {
        val fineLocation = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val backgroundLocation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        return fineLocation && backgroundLocation
    }
}
