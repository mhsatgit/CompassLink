package com.compasslink3d

import org.json.JSONArray
import org.json.JSONObject

internal enum class OrientationPolicy(val title: String, val locked: ProfileOrientation?) {
    SYSTEM("Follow system rotation", null),
    PORTRAIT("Lock portrait", ProfileOrientation.PORTRAIT),
    LANDSCAPE("Lock landscape", ProfileOrientation.LANDSCAPE),
}

internal enum class TouchHaptics { OFF, LIGHT, MEDIUM, STRONG }

internal data class ControlSlot(val control: String, val slot: Slot)

internal data class HybridSettings(
    val hybrid: Boolean = true,
    val orientation: OrientationPolicy = OrientationPolicy.SYSTEM,
    val haptics: TouchHaptics = TouchHaptics.OFF,
    val gyroControl: ControlSlot? = null,
    val quickSwitch: Map<String, String> = emptyMap(),
    val secondaryMotion: MotionSettings? = null,
    val originalAppearance: Boolean = false,
) {
    fun encode() = JSONObject().put("hybrid", hybrid).put("orientationPolicy", orientation.name)
        .put("haptics", haptics.name).put("quickSwitch", switchReferences(quickSwitch))
        .apply {
            gyroControl?.let { put("gyroControl", JSONObject().put("control", it.control).put("slot", it.slot.wire)) }
            secondaryMotion?.let { put("secondaryMotion", it.encode()) }
            if (originalAppearance) put("originalAppearance", true)
        }

    companion object {
        fun decode(data: JSONObject, keys: Set<String>): HybridSettings {
            val allowed = setOf(
                "hybrid", "orientationPolicy", "haptics", "quickSwitch", "gyroControl", "secondaryMotion", "originalAppearance",
            )
            require(data.keys().asSequence().all { it in allowed }) { "Unknown hybrid setting" }
            val references = data.getJSONArray("quickSwitch")
            require(references.length() <= MAX_CONTROLS) { "Too many profile switch actions" }
            val quickSwitch = buildMap {
                for (index in 0 until references.length()) {
                    val reference = references.getJSONObject(index)
                    val control = reference.string("control")
                    require(reference.length() == 2 && control !in this) { "Invalid or duplicate profile switch action" }
                    put(control, reference.string("target"))
                }
            }
            val gyro = if (data.has("gyroControl")) data.getJSONObject("gyroControl").let {
                require(it.length() == 2) { "Invalid gyro activation reference" }
                val name = it.string("slot")
                val slot = Slot.entries.firstOrNull { slot -> slot.wire == name }
                    ?: throw IllegalArgumentException("Invalid gyro activation slot")
                ControlSlot(it.string("control"), slot)
            } else null
            return HybridSettings(
                data.get("hybrid") as? Boolean ?: throw IllegalArgumentException("Invalid hybrid output setting"),
                OrientationPolicy.valueOf(data.string("orientationPolicy")),
                TouchHaptics.valueOf(data.string("haptics")), gyro, quickSwitch,
                if (data.has("secondaryMotion")) MotionSettings.decode(data.getJSONObject("secondaryMotion"), keys) else null,
                if (data.has("originalAppearance")) data.get("originalAppearance") as? Boolean
                    ?: throw IllegalArgumentException("Invalid appearance setting") else false,
            )
        }

        private fun JSONObject.string(key: String) = get(key) as? String ?: throw IllegalArgumentException("Invalid $key")
    }
}

internal fun switchReferences(references: Map<String, String>) = JSONArray(references.toSortedMap().map { (control, target) ->
    JSONObject().put("control", control).put("target", target)
})

internal fun ControllerProfile.activationSlots(): List<ControlSlot> = controls.flatMap { control ->
    if (!control.style.visible || control.type == ControlType.TILT) emptyList()
    else control.type.slots.filter { slot ->
        slot != Slot.TAP && control.bindings[slot]?.type in setOf(BindingType.KEYBOARD, BindingType.MOUSE_BUTTON, BindingType.GAMEPAD) &&
            control.bindings[slot]?.supports(OutputMode.HYBRID) == true
    }.map { ControlSlot(control.id, it) }
}

internal fun ControllerProfile.validateHybrid() {
    val options = hybrid
    val switchControls = controls.filter { it.type == ControlType.PROFILE_SWITCH }.map { it.id }.toSet()
    val configured = options?.quickSwitch.orEmpty()
    require(configured.keys == switchControls && configured.values.all { validProfileId(it) && it != id }) {
        "Assign a different, valid profile to every switch button"
    }
    val secondary = options?.secondaryMotion
    require(secondary == null || options.hybrid && motion != null && secondary.source != motion.source) {
        "Secondary motion requires hybrid output and a different sensor source"
    }
    val gyro = listOfNotNull(motion, secondary).firstOrNull { it.source == MotionSource.GYROSCOPE }
    if (options?.gyroControl != null) {
        require(gyro != null && gyro.gyroEnable != GyroEnable.ALWAYS && options.gyroControl in activationSlots()) {
            "Choose a visible, bound control for gyro activation"
        }
        require(options.hybrid) { "Control-based gyro activation requires hybrid output" }
    }
    require(options != null || gyro?.gyroEnable != GyroEnable.ALWAYS) { "Always-enabled gyro requires schema 5" }
}
