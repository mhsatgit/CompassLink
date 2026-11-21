package com.compasslink3d

import org.json.JSONObject
import java.util.UUID

internal class ControllerProtocol(private val token: String) {
    private val sessionId = UUID.randomUUID().toString()
    private var sequence = 0L

    fun start() = packet("controller.start", 0).put("token", token).toString()

    fun heartbeat() = packet("controller.heartbeat", ++sequence).toString()

    fun profile(descriptor: JSONObject): Pair<Long, String> {
        val revision = ++sequence
        return revision to packet("controller.profile", revision).put("profile", descriptor).toString()
    }

    fun input(event: ControllerInput, revision: Long): String = when (event) {
        is ControllerInput.Digital -> control("controller.input", event.control, revision).put("slot", event.slot.wire)
            .put("action", if (event.down) "DOWN" else "UP")
        is ControllerInput.Axis -> control("controller.axis", event.control, revision)
            .put("x", event.value.x.coerceIn(-1.0, 1.0)).put("y", event.value.y.coerceIn(-1.0, 1.0))
        is ControllerInput.Pointer -> control("controller.pointer", event.control, revision)
            .put("dx", event.delta.x.toInt()).put("dy", event.delta.y.toInt())
        is ControllerInput.Wheel -> control("controller.wheel", event.control, revision).put("ticks", event.ticks.toInt())
        ControllerInput.Neutral -> packet("controller.neutral", ++sequence)
    }.toString()

    fun accepts(response: JSONObject) =
        response.optInt("version") == VERSION && response.optString("sessionId") == sessionId

    private fun control(type: String, id: String, revision: Long) =
        packet(type, ++sequence).put("control", id).put("revision", revision)

    private fun packet(type: String, sequence: Long) = JSONObject()
        .put("version", VERSION).put("type", type).put("sessionId", sessionId).put("sequence", sequence)

    private companion object {
        const val VERSION = 2
    }
}

/** Keeps analog updates bounded without discarding partial mouse pixels or wheel steps. */
internal class ContinuousInput {
    private val axes = mutableMapOf<String, LayoutPoint>()
    private val deltas = mutableMapOf<String, LayoutPoint>()
    private val wheels = mutableMapOf<String, Double>()

    @Synchronized
    fun offer(event: ControllerInput) {
        when (event) {
            is ControllerInput.Axis -> axes[event.control] = event.value
            is ControllerInput.Pointer -> {
                val pending = deltas[event.control]?.plus(event.delta) ?: event.delta
                deltas[event.control] = LayoutPoint(pending.x.coerceIn(-1024.0, 1024.0), pending.y.coerceIn(-1024.0, 1024.0))
            }
            is ControllerInput.Wheel -> wheels[event.control] = ((wheels[event.control] ?: 0.0) + event.ticks).coerceIn(-24.0, 24.0)
            else -> Unit
        }
    }

    @Synchronized
    fun drain(): List<ControllerInput> = buildList {
        for ((control, value) in axes) add(ControllerInput.Axis(control, value))
        axes.clear()
        for (entry in deltas.entries) {
            val x = entry.value.x.toInt().coerceIn(-512, 512)
            val y = entry.value.y.toInt().coerceIn(-512, 512)
            if (x != 0 || y != 0) {
                val sent = LayoutPoint(x.toDouble(), y.toDouble())
                add(ControllerInput.Pointer(entry.key, sent))
                entry.setValue(entry.value - sent)
            }
        }
        for (entry in wheels.entries) {
            val count = entry.value.toInt().coerceIn(-8, 8)
            if (count != 0) {
                add(ControllerInput.Wheel(entry.key, count.toDouble()))
                entry.setValue(entry.value - count)
            }
        }
    }

    @Synchronized
    fun clear() {
        axes.clear()
        deltas.clear()
        wheels.clear()
    }
}
