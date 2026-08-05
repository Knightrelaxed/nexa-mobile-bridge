package com.nexa.mobilebridge.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

@Serializable
data class CommandResult(
    val type: String = "COMMAND_RESULT",
    val command_id: String,
    val status: ResultStatus,
    val data: JsonObject? = null,
    val message: String,
    val device_name: String = "Samsung_A33_5G",
    val timestamp: Long = System.currentTimeMillis()
) {
    companion object {
        fun success(commandId: String, message: String, data: JsonObject? = null) = CommandResult(
            command_id = commandId,
            status = ResultStatus.SUCCESS,
            data = data,
            message = message
        )
        
        fun failure(commandId: String, message: String) = CommandResult(
            command_id = commandId,
            status = ResultStatus.FAILURE,
            message = message
        )
        
        fun unknown(commandId: String, action: String) = CommandResult(
            command_id = commandId,
            status = ResultStatus.FAILURE,
            message = "Unknown action: $action"
        )
    }
}

@Serializable
enum class ResultStatus { SUCCESS, FAILURE, PARTIAL }
