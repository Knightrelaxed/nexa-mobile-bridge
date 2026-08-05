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

            val location = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                suspendCancellableCoroutine<Location?> { continuation ->
                    val provider = if (isGpsEnabled) LocationManager.GPS_PROVIDER else LocationManager.NETWORK_PROVIDER
                    val cancellationSignal = android.os.CancellationSignal()
                    
                    continuation.invokeOnCancellation {
                        cancellationSignal.cancel()
                    }
                    
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
            } else {
                locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                    ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            }

            if (location != null) {
                CommandResult(
                    command_id = command.command_id,
                    status = ResultStatus.SUCCESS,
                    message = "Location retrieved successfully",
                    data = kotlinx.serialization.json.buildJsonObject {
                        put("latitude", kotlinx.serialization.json.JsonPrimitive(location.latitude))
                        put("longitude", kotlinx.serialization.json.JsonPrimitive(location.longitude))
                        put("accuracy", kotlinx.serialization.json.JsonPrimitive(location.accuracy))
                        put("provider", kotlinx.serialization.json.JsonPrimitive(location.provider))
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
