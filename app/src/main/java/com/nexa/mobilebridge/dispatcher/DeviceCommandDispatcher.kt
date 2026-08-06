package com.nexa.mobilebridge.dispatcher

import android.content.Context
import com.nexa.mobilebridge.dispatcher.handler.AppLauncherHandler
import com.nexa.mobilebridge.dispatcher.handler.BatteryHandler
import com.nexa.mobilebridge.dispatcher.handler.CameraHandler
import com.nexa.mobilebridge.dispatcher.handler.FlashlightHandler
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

    suspend fun dispatch(command: NexaCommand): CommandResult = withContext(Dispatchers.Default) {
        try {
            when (command.action) {
                NexaActions.TOGGLE_FLASHLIGHT  -> flashlightHandler.execute(command)
                NexaActions.SET_VOLUME         -> volumeHandler.execute(command)
                NexaActions.FORCE_DND          -> volumeHandler.executeDnd(command)
                NexaActions.GET_BATTERY_STATUS -> batteryHandler.execute(command)
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
                else -> CommandResult.unknown(command.command_id, command.action)
            }
        } catch (e: SecurityException) {
            CommandResult.failure(command.command_id, "Permission denied: ${e.message}")
        } catch (e: Exception) {
            CommandResult.failure(command.command_id, "Execution error: ${e.message}")
        }
    }
}
