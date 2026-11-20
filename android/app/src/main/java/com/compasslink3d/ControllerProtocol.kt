package com.compasslink3d

import org.json.JSONObject
import java.util.UUID

internal enum class ControllerControl(val label: String, val description: String) {
    DPAD_UP("UP", "Up"),
    DPAD_DOWN("DOWN", "Down"),
    DPAD_LEFT("LEFT", "Left"),
    DPAD_RIGHT("RIGHT", "Right"),
    ACTION_A("A", "Action A"),
    ACTION_B("B", "Action B"),
}

internal data class ControllerTransition(val control: ControllerControl, val down: Boolean)

internal class ControllerProtocol(private val token: String) {
    private val sessionId = UUID.randomUUID().toString()
    private var sequence = 0L

    fun start() = packet("controller.start", 0).put("token", token).toString()

    fun heartbeat() = packet("controller.heartbeat", ++sequence).toString()

    fun key(event: ControllerTransition) = packet("controller.key", ++sequence)
        .put("control", event.control.name)
        .put("action", if (event.down) "DOWN" else "UP")
        .toString()

    fun accepts(response: JSONObject) =
        response.optInt("version") == VERSION && response.optString("sessionId") == sessionId

    private fun packet(type: String, sequence: Long) = JSONObject()
        .put("version", VERSION)
        .put("type", type)
        .put("sessionId", sessionId)
        .put("sequence", sequence)

    private companion object {
        const val VERSION = 1
    }
}
