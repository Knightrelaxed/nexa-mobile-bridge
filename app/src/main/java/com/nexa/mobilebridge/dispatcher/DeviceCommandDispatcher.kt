package com.nexa.mobilebridge.dispatcher

import android.content.Context
import com.nexa.mobilebridge.dispatcher.handler.AppLauncherHandler
import com.nexa.mobilebridge.dispatcher.handler.BatteryHandler
import com.nexa.mobilebridge.dispatcher.handler.CameraHandler
import com.nexa.mobilebridge.dispatcher.handler.ClipboardHandler
import com.nexa.mobilebridge.dispatcher.handler.FlashlightHandler
import com.nexa.mobilebridge.dispatcher.handler.IntentHandler
import com.nexa.mobilebridge.dispatcher.handler.LocationHandler
import com.nexa.mobilebridge.dispatcher.handler.NetworkHandler
import com.nexa.mobilebridge.dispatcher.handler.OverlayHandler
import com.nexa.mobilebridge.dispatcher.handler.ScreenHandler
import com.nexa.mobilebridge.dispatcher.handler.ScreenshotHandler
import com.nexa.mobilebridge.dispatcher.handler.TtsHandler
import com.nexa.mobilebridge.dispatcher.handler.VolumeHandler
import com.nexa.mobilebridge.protocol.CommandResult
import com.nexa.mobilebridge.protocol.NexaActions
import com.nexa.mobilebridge.protocol.NexaCommand
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DeviceCommandDispatcher @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val flashlightHandler = FlashlightHandler(context)
    private val volumeHandler = VolumeHandler(context)
    private val batteryHandler = BatteryHandler(context)
    private val screenHandler = ScreenHandler(context)
    private val appLauncherHandler = AppLauncherHandler(context)
    private val networkHandler = NetworkHandler(context)
    private val locationHandler = LocationHandler(context)
    private val ttsHandler = TtsHandler(context)
    private val cameraHandler = CameraHandler(context)
    private val screenshotHandler = ScreenshotHandler(context)
    private val clipboardHandler = ClipboardHandler(context)
    private val intentHandler = IntentHandler(context)
    private val audioPlayerHandler = com.nexa.mobilebridge.dispatcher.handler.AudioPlayerHandler(context)
    private val fakeCallHandler = com.nexa.mobilebridge.dispatcher.handler.FakeCallHandler(context, audioPlayerHandler)
    private val geofenceHandler = com.nexa.mobilebridge.dispatcher.handler.GeofenceHandler(context, com.nexa.mobilebridge.core.sensor.GeofenceManager(context))
    private val lightSensorHandler = com.nexa.mobilebridge.dispatcher.handler.LightSensorHandler(context)

    suspend fun dispatch(command: NexaCommand): CommandResult = withContext(Dispatchers.Default) {
        try {
            when (command.action) {
                NexaActions.TOGGLE_FLASHLIGHT  -> flashlightHandler.execute(command)
                NexaActions.SET_VOLUME         -> volumeHandler.execute(command)
                NexaActions.FORCE_DND          -> volumeHandler.executeDnd(command)
                NexaActions.GET_BATTERY_STATUS -> batteryHandler.execute(command)
                NexaActions.GET_SCREEN_STATUS  -> screenHandler.executeGetScreenStatus(command)
                NexaActions.GET_LIGHT_STATUS   -> lightSensorHandler.executeGetLightStatus(command)
                NexaActions.GO_HOME_SCREEN     -> screenHandler.execute(command)
                NexaActions.GO_BACK            -> screenHandler.executeBack(command)
                NexaActions.SHOW_RECENTS       -> screenHandler.executeRecents(command)
                NexaActions.LOCK_SCREEN        -> screenHandler.executeLock(command)
                NexaActions.LAUNCH_APP         -> appLauncherHandler.execute(command)
                NexaActions.TOGGLE_WIFI        -> networkHandler.execute(command)
                NexaActions.GET_NETWORK_INFO   -> networkHandler.getInfo(command)
                NexaActions.GET_LOCATION       -> locationHandler.execute(command)
                NexaActions.SPEAK_TEXT         -> ttsHandler.execute(command)
                NexaActions.TAKE_PHOTO         -> cameraHandler.execute(command)
                NexaActions.TAKE_SCREENSHOT    -> screenshotHandler.execute(command)
                NexaActions.SHOW_OVERLAY_MSG   -> OverlayHandler(context).execute(command)

                // Dynamic Geofencing Actions
                NexaActions.SET_GEOFENCE       -> geofenceHandler.executeSet(command)
                NexaActions.MARK_GEOFENCE_HERE -> geofenceHandler.executeMarkCurrent(command)
                NexaActions.REMOVE_GEOFENCE    -> geofenceHandler.executeRemove(command)
                NexaActions.LIST_GEOFENCES     -> geofenceHandler.executeList(command)

                // Hands & Eyes Expansion Actions
                NexaActions.GET_CLIPBOARD       -> clipboardHandler.executeGet(command)
                NexaActions.SET_CLIPBOARD       -> clipboardHandler.executeSet(command)
                NexaActions.OPEN_INTENT         -> intentHandler.execute(command)
                NexaActions.DUMP_UI_HIERARCHY   -> executeDumpHierarchy(command)
                NexaActions.ACCESSIBILITY_CLICK -> executeAccessibilityClick(command)
                NexaActions.ACCESSIBILITY_INPUT_TEXT -> executeAccessibilityInput(command)
                NexaActions.ACCESSIBILITY_SCROLL-> executeAccessibilityScroll(command)

                // Emergency Audio Player & Fake Call Simulation Actions
                NexaActions.PLAY_RINGTONE       -> audioPlayerHandler.playRingtone(command)
                NexaActions.PLAY_MEDIA          -> audioPlayerHandler.playMedia(command)
                NexaActions.STOP_MEDIA          -> audioPlayerHandler.stopMedia(command)
                NexaActions.SIMULATE_INCOMING_CALL -> fakeCallHandler.execute(command)
                NexaActions.PLAY_AUDIO_STREAM   -> audioPlayerHandler.playAudioStream(command)
                NexaActions.END_CALL,
                NexaActions.HANGUP              -> executeEndCall(command)

                else -> CommandResult.unknown(command.command_id, command.action)
            }
        } catch (e: SecurityException) {
            CommandResult.failure(command.command_id, "Permission denied: ${e.message}")
        } catch (e: Exception) {
            CommandResult.failure(command.command_id, "Execution error: ${e.message}")
        }
    }

    private fun executeDumpHierarchy(command: NexaCommand): CommandResult {
        val service = com.nexa.mobilebridge.NexaAccessibilityService.instance
            ?: return CommandResult.failure(command.command_id, "Nexa Accessibility Service is disabled.")
        val jsonTree = service.dumpUiHierarchy()
        return CommandResult.success(
            command.command_id,
            "UI Hierarchy dumped successfully.",
            buildJsonObject { put("nodes", jsonTree) }
        )
    }

    private fun executeAccessibilityClick(command: NexaCommand): CommandResult {
        val service = com.nexa.mobilebridge.NexaAccessibilityService.instance
            ?: return CommandResult.failure(command.command_id, "Nexa Accessibility Service is disabled.")

        val target = (command.params?.get("target") as? JsonPrimitive)?.content
        val x = (command.params?.get("x") as? JsonPrimitive)?.content?.toFloatOrNull()
        val y = (command.params?.get("y") as? JsonPrimitive)?.content?.toFloatOrNull()

        if (target != null) {
            val success = service.clickNode(target)
            return if (success) CommandResult.success(command.command_id, "Clicked node: $target")
            else CommandResult.failure(command.command_id, "Node not found or not clickable: $target")
        } else if (x != null && y != null) {
            var result: CommandResult? = null
            val latch = CountDownLatch(1)
            service.clickCoordinates(x, y) { success, err ->
                result = if (success) CommandResult.success(command.command_id, "Clicked at ($x, $y)")
                else CommandResult.failure(command.command_id, "Click failed: $err")
                latch.countDown()
            }
            latch.await(3, TimeUnit.SECONDS)
            return result ?: CommandResult.failure(command.command_id, "Click gesture timeout.")
        } else {
            return CommandResult.failure(command.command_id, "Provide 'target' (ID/Text) or ('x', 'y') coordinates.")
        }
    }

    private fun executeAccessibilityInput(command: NexaCommand): CommandResult {
        val service = com.nexa.mobilebridge.NexaAccessibilityService.instance
            ?: return CommandResult.failure(command.command_id, "Nexa Accessibility Service is disabled.")

        val text = (command.params?.get("text") as? JsonPrimitive)?.content
            ?: return CommandResult.failure(command.command_id, "Missing required parameter 'text'.")
        val target = (command.params?.get("target") as? JsonPrimitive)?.content

        val success = service.inputText(text, target)
        return if (success) CommandResult.success(command.command_id, "Input text set successfully.")
        else CommandResult.failure(command.command_id, "Failed to set input text on focused/target field.")
    }

    private fun executeAccessibilityScroll(command: NexaCommand): CommandResult {
        val service = com.nexa.mobilebridge.NexaAccessibilityService.instance
            ?: return CommandResult.failure(command.command_id, "Nexa Accessibility Service is disabled.")

        val direction = (command.params?.get("direction") as? JsonPrimitive)?.content ?: "FORWARD"
        val success = service.scroll(direction)
        return if (success) CommandResult.success(command.command_id, "Scroll $direction executed.")
        else CommandResult.failure(command.command_id, "Failed to scroll $direction.")
    }

    private fun executeEndCall(command: NexaCommand): CommandResult {
        return try {
            val intent = android.content.Intent("com.nexa.mobilebridge.CALL_REPLY_COMPLETE").apply {
                setPackage(context.packageName)
            }
            context.sendBroadcast(intent)
            audioPlayerHandler.stopAll()
            CommandResult.success(command.command_id, "Panggilan berhasil diakhiri dari server.")
        } catch (e: Exception) {
            CommandResult.failure(command.command_id, "Gagal mengakhiri panggilan: ${e.message}")
        }
    }
}

