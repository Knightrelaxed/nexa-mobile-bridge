package com.nexa.mobilebridge.dispatcher.handler

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.nexa.mobilebridge.protocol.CommandResult
import com.nexa.mobilebridge.protocol.NexaCommand
import com.nexa.mobilebridge.protocol.ResultStatus
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executor
import kotlin.coroutines.resume

class LocationHandler(private val context: Context) {

    companion object {
        private const val TAG = "NexaLocation"
    }

    @SuppressLint("MissingPermission")
    suspend fun execute(command: NexaCommand): CommandResult {
        return try {
            val hasFine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            val hasCoarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

            if (!hasFine && !hasCoarse) {
                Log.w(TAG, "Location permission missing")
                return CommandResult.failure(command.command_id, "Izin lokasi belum diberikan di HP.")
            }

            val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val isLocationEnabled = LocationManagerCompat.isLocationEnabled(locationManager)
            if (!isLocationEnabled) {
                Log.w(TAG, "Location services toggle is disabled")
                return CommandResult.failure(command.command_id, "Layanan Lokasi (GPS) sedang dinonaktifkan di HP.")
            }

            var bestLocation: Location? = null

            // ══════════════════════════════════════════════════════════
            // TIER 1: Check cached lastLocation (instant < 50ms)
            // ══════════════════════════════════════════════════════════
            val fusedClient = LocationServices.getFusedLocationProviderClient(context)
            val cachedFused = withTimeoutOrNull(1000L) {
                suspendCancellableCoroutine { cont ->
                    fusedClient.lastLocation
                        .addOnSuccessListener { if (cont.isActive) cont.resume(it) }
                        .addOnFailureListener { if (cont.isActive) cont.resume(null) }
                }
            }

            if (cachedFused != null && isFresh(cachedFused, maxAgeMs = 5 * 60 * 1000L)) {
                Log.i(TAG, "Using fresh cached fused location: ${cachedFused.latitude}, ${cachedFused.longitude}")
                bestLocation = cachedFused
            }

            // ══════════════════════════════════════════════════════════
            // TIER 2: Fast Parallel Active Location Fix (Balanced + High Accuracy + Network)
            // ══════════════════════════════════════════════════════════
            if (bestLocation == null) {
                bestLocation = coroutineScope {
                    // Task A: Fused balanced power (uses Wi-Fi / Cell towers — works INDOORS in < 500ms)
                    val balancedDeferred = async {
                        val cts = CancellationTokenSource()
                        withTimeoutOrNull(4000L) {
                            suspendCancellableCoroutine<Location?> { cont ->
                                cont.invokeOnCancellation { cts.cancel() }
                                fusedClient.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, cts.token)
                                    .addOnSuccessListener { if (cont.isActive) cont.resume(it) }
                                    .addOnFailureListener { if (cont.isActive) cont.resume(null) }
                            }
                        }
                    }

                    // Task B: Fused high accuracy (GPS satellites)
                    val highAccDeferred = async {
                        val cts = CancellationTokenSource()
                        withTimeoutOrNull(5000L) {
                            suspendCancellableCoroutine<Location?> { cont ->
                                cont.invokeOnCancellation { cts.cancel() }
                                fusedClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token)
                                    .addOnSuccessListener { if (cont.isActive) cont.resume(it) }
                                    .addOnFailureListener { if (cont.isActive) cont.resume(null) }
                            }
                        }
                    }

                    val balanced = balancedDeferred.await()
                    val highAcc = highAccDeferred.await()

                    // Prefer high accuracy if available, else balanced
                    highAcc ?: balanced
                }
            }

            // ══════════════════════════════════════════════════════════
            // TIER 3: Android LocationManager fallback
            // ══════════════════════════════════════════════════════════
            if (bestLocation == null) {
                bestLocation = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                    ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                    ?: locationManager.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)
                    ?: cachedFused // Even if older, better than failing
            }

            if (bestLocation != null) {
                Log.i(TAG, " Location fix resolved: ${bestLocation.latitude}, ${bestLocation.longitude} (accuracy: ${bestLocation.accuracy}m)")

                var addressText: String? = null
                try {
                    val geocoder = android.location.Geocoder(context, java.util.Locale.forLanguageTag("id-ID"))
                    @Suppress("DEPRECATION")
                    val addresses = geocoder.getFromLocation(bestLocation.latitude, bestLocation.longitude, 1)
                    if (!addresses.isNullOrEmpty()) {
                        addressText = addresses[0].getAddressLine(0)
                    }
                } catch (_: Exception) {}

                CommandResult(
                    command_id = command.command_id,
                    status = ResultStatus.SUCCESS,
                    message = "Location retrieved successfully",
                    data = kotlinx.serialization.json.buildJsonObject {
                        put("latitude", kotlinx.serialization.json.JsonPrimitive(bestLocation.latitude))
                        put("longitude", kotlinx.serialization.json.JsonPrimitive(bestLocation.longitude))
                        put("accuracy", kotlinx.serialization.json.JsonPrimitive(bestLocation.accuracy))
                        put("provider", kotlinx.serialization.json.JsonPrimitive(bestLocation.provider ?: "fused"))
                        put("address", kotlinx.serialization.json.JsonPrimitive(addressText ?: ""))
                    }
                )
            } else {
                Log.e(TAG, " All location providers failed")
                CommandResult.failure(command.command_id, "Tidak dapat membaca koordinat lokasi saat ini (Timeout GPS).")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error executing location fix: ${e.message}", e)
            CommandResult.failure(command.command_id, "Error membaca lokasi: ${e.message}")
        }
    }

    private fun isFresh(location: Location, maxAgeMs: Long): Boolean {
        val age = System.currentTimeMillis() - location.time
        return age in 0..maxAgeMs
    }
}
