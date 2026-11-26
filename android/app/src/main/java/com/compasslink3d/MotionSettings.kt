package com.compasslink3d

import org.json.JSONObject

internal enum class MotionMapping(val title: String, val mode: OutputMode) {
    STICK("Analog stick", OutputMode.GAMEPAD),
    MOUSE("Mouse aim", OutputMode.KEYBOARD),
    STEERING("Digital steering", OutputMode.KEYBOARD),
}
internal enum class MotionSource(val title: String) {
    ORIENTATION("Orientation / tilt"), GYROSCOPE("Raw gyroscope"),
}
internal enum class MotionAxis { NONE, PITCH, YAW, ROLL }
internal enum class GyroEnable { HOLD, TOGGLE, ALWAYS }

internal data class MotionSettings(
    val enabled: Boolean = false,
    val mapping: MotionMapping = MotionMapping.STICK,
    val source: MotionSource = MotionSource.ORIENTATION,
    val horizontal: MotionAxis = MotionAxis.YAW,
    val vertical: MotionAxis = MotionAxis.PITCH,
    val sensitivity: Double = 1.0,
    val deadZone: Double = .08,
    val smoothingMs: Double = 45.0,
    val invertX: Boolean = false,
    val invertY: Boolean = false,
    val maximum: Double = 1.0,
    val stick: String = "RIGHT_STICK",
    val leftKey: String = "A",
    val rightKey: String = "D",
    val engage: Double = .36,
    val release: Double = .22,
    val mouseScale: Double = 850.0,
    val gyroEnable: GyroEnable = GyroEnable.HOLD,
    val gyroLabel: String = "Gyro aim",
) {
    fun validate(keys: Set<String>) {
        require(horizontal != MotionAxis.NONE || vertical != MotionAxis.NONE) { "Select at least one motion axis" }
        require(mapping != MotionMapping.STEERING ||
            (source == MotionSource.ORIENTATION && horizontal != MotionAxis.NONE)) {
            "Steering requires orientation and a horizontal axis"
        }
        require(sensitivity in .1..8.0 && deadZone in 0.0.. .85 && smoothingMs in 0.0..500.0 &&
            maximum in .1..1.0 && mouseScale in 10.0..3000.0) { "Invalid motion sensitivity, filter, or output limit" }
        require(stick == "LEFT_STICK" || stick == "RIGHT_STICK") { "Invalid motion stick" }
        require(leftKey in keys && rightKey in keys && leftKey != rightKey) { "Choose two different steering keys" }
        require(release in 0.0.. .9 && engage in .05..1.0 && release < engage) {
            "Steering release must be below engage"
        }
        require(gyroLabel.length in 1..24 && gyroLabel.isNotBlank() && gyroLabel.none(Char::isISOControl)) {
            "Use a gyro button label of 1–24 characters"
        }
    }

    fun encode() = JSONObject()
        .put("enabled", enabled).put("mapping", mapping.name).put("source", source.name)
        .put("horizontal", horizontal.name).put("vertical", vertical.name)
        .put("sensitivity", sensitivity).put("deadZone", deadZone).put("smoothingMs", smoothingMs)
        .put("invertX", invertX).put("invertY", invertY).put("maximum", maximum).put("stick", stick)
        .put("leftKey", leftKey).put("rightKey", rightKey).put("engage", engage).put("release", release)
        .put("mouseScale", mouseScale).put("gyroEnable", gyroEnable.name).put("gyroLabel", gyroLabel)

    fun descriptor(id: String) = JSONObject().put("id", id).put("mapping", mapping.name).put("source", source.name).apply {
        when (mapping) {
            MotionMapping.STICK -> put("stick", stick)
            MotionMapping.MOUSE -> Unit
            MotionMapping.STEERING -> put("leftKey", leftKey).put("rightKey", rightKey)
        }
    }

    companion object {
        fun decode(data: JSONObject, keys: Set<String>): MotionSettings {
            fun text(key: String) = data.get(key) as? String ?: throw IllegalArgumentException("Invalid motion $key")
            fun flag(key: String) = data.get(key) as? Boolean ?: throw IllegalArgumentException("Invalid motion $key")
            fun number(key: String) = (data.get(key) as? Number)?.toDouble()
                ?: throw IllegalArgumentException("Invalid motion $key")
            return MotionSettings(
                flag("enabled"), MotionMapping.valueOf(text("mapping")), MotionSource.valueOf(text("source")),
                MotionAxis.valueOf(text("horizontal")), MotionAxis.valueOf(text("vertical")), number("sensitivity"),
                number("deadZone"), number("smoothingMs"), flag("invertX"), flag("invertY"), number("maximum"),
                text("stick"), text("leftKey"), text("rightKey"), number("engage"), number("release"),
                number("mouseScale"), GyroEnable.valueOf(text("gyroEnable")), text("gyroLabel"),
            ).also { it.validate(keys) }
        }
    }
}
