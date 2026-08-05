package com.nexa.mobilebridge.protocol

object NexaActions {
    // Hardware Control
    const val TOGGLE_FLASHLIGHT  = "TOGGLE_FLASHLIGHT"
    const val SET_VOLUME         = "SET_VOLUME"
    const val GET_BATTERY_STATUS = "GET_BATTERY_STATUS"
    const val GET_LOCATION       = "GET_LOCATION"
    const val SET_ALARM          = "SET_ALARM"
    const val LAUNCH_APP         = "LAUNCH_APP"
    const val SPEAK_TEXT         = "SPEAK_TEXT"
    const val TAKE_PHOTO         = "TAKE_PHOTO"
    const val TAKE_SCREENSHOT    = "TAKE_SCREENSHOT"
    
    // GodMode Discipline Actions
    const val FORCE_DND          = "FORCE_DND"
    const val DISABLE_WIFI       = "DISABLE_WIFI"
    const val TOGGLE_WIFI        = "TOGGLE_WIFI"
    const val GO_HOME_SCREEN     = "GO_HOME_SCREEN"
    const val LOCK_SCREEN        = "LOCK_SCREEN"
    const val SHOW_OVERLAY_MSG   = "SHOW_OVERLAY_MSG"

    // Telemetry Queries
    const val GET_NETWORK_INFO   = "GET_NETWORK_INFO"
    const val GET_MOTION_STATE   = "GET_MOTION_STATE"
}
