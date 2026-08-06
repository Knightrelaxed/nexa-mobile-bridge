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
    const val GO_BACK            = "GO_BACK"
    const val SHOW_RECENTS       = "SHOW_RECENTS"

    // Hands & Eyes Expansion (Accessibility, Clipboard, Intent)
    const val DUMP_UI_HIERARCHY      = "DUMP_UI_HIERARCHY"
    const val ACCESSIBILITY_CLICK    = "ACCESSIBILITY_CLICK"
    const val ACCESSIBILITY_INPUT_TEXT = "ACCESSIBILITY_INPUT_TEXT"
    const val ACCESSIBILITY_SCROLL   = "ACCESSIBILITY_SCROLL"
    const val GET_CLIPBOARD          = "GET_CLIPBOARD"
    const val SET_CLIPBOARD          = "SET_CLIPBOARD"
    const val OPEN_INTENT            = "OPEN_INTENT"
    // Audio Player & Emergency Finder
    const val PLAY_RINGTONE           = "PLAY_RINGTONE"
    const val PLAY_MEDIA              = "PLAY_MEDIA"
    const val STOP_MEDIA              = "STOP_MEDIA"
    const val SIMULATE_INCOMING_CALL  = "SIMULATE_INCOMING_CALL"

    // Bidirectional Call Interaction Events
    const val CALL_ACCEPTED           = "CALL_ACCEPTED"   // App → Server: user swiped accept
    const val CALL_REJECTED           = "CALL_REJECTED"   // App → Server: user swiped reject
    const val CALL_AUDIO_REPLY        = "CALL_AUDIO_REPLY" // App → Server: 10s voice recording (base64)
    const val PLAY_AUDIO_STREAM       = "PLAY_AUDIO_STREAM" // Server → App: play audio reply (base64)
}
