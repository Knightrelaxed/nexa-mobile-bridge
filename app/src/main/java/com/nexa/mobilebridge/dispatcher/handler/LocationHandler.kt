package com.nexa.mobilebridge.dispatcher.handler

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.nexa.mobilebridge.protocol.CommandResult
import com.nexa.mobilebridge.protocol.NexaCommand
import com.nexa.mobilebridge.protocol.ResultStatus
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executor
import kotlin.coroutines.resume

class LocationHandler(private val context: Context) {
    
    @SuppressLint("MissingPermission")
    suspend fun execute(command: NexaCommand): CommandResult {
        return try {
            val hasFineLocation = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            val hasCoarseLocation = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
            
            if (!hasFineLocation && !hasCoarseLocation) {
                return CommandResult.failure(command.command_id, "Location permission denied.")
            }

            val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            
            val isGpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
            val isNetworkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            
            if (!isGpsEnabled && !isNetworkEnabled) {
                return CommandResult.failure(command.command_id, "Location services are disabled on the device.")
            }

            // 1. Try last known location first for instant response (works indoors/outdoors)
            var location: Location? = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)

            // 2. If null and API >= 30, request current location using NETWORK_PROVIDER or GPS
            if (location == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val provider = if (isNetworkEnabled) LocationManager.NETWORK_PROVIDER else LocationManager.GPS_PROVIDER
                location = suspendCancellableCoroutine<Location?> { continuation ->
                    val cancellationSignal = android.os.CancellationSignal()
                    continuation.invokeOnCancellation { cancellationSignal.cancel() }
                    
                    val executor = Executor { it.run() }
                    locationManager.getCurrentLocation(
                        provider,
                        cancellationSignal,
                        executor
                    ) { loc ->
                        if (continuation.isActive) {
                            continuation.resume(loc)
                        }
                    }
                }
            }

            if (location != null) {
                var addressText: String? = null
                try {
                    val geocoder = android.location.Geocoder(context, java.util.Locale("id", "ID"))
                    @Suppress("DEPRECATION")
                    val addresses = geocoder.getFromLocation(location.latitude, location.longitude, 1)
                    if (!addresses.isNullOrEmpty()) {
                        addressText = addresses[0].getAddressLine(0)
                    }
                } catch (e: Exception) {
                    // Geocoder lookup failed or offline, fallback to coordinates
                }

                CommandResult(
                    command_id = command.command_id,
                    status = ResultStatus.SUCCESS,
                    message = "Location retrieved successfully",
                    data = kotlinx.serialization.json.buildJsonObject {
                        put("latitude", kotlinx.serialization.json.JsonPrimitive(location.latitude))
                        put("longitude", kotlinx.serialization.json.JsonPrimitive(location.longitude))
                        put("accuracy", kotlinx.serialization.json.JsonPrimitive(location.accuracy))
                        put("provider", kotlinx.serialization.json.JsonPrimitive(location.provider))
                        put("address", kotlinx.serialization.json.JsonPrimitive(addressText ?: ""))
                    }
                )
            } else {
                CommandResult.failure(command.command_id, "Could not determine current location.")
            }
        } catch (e: Exception) {
            CommandResult.failure(command.command_id, "Error retrieving location: ${e.message}")
        }
    }
}
