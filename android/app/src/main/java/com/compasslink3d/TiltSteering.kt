package com.compasslink3d

import kotlin.math.asin

internal val TILT_KEY_BINDINGS = mapOf(
    Slot.LEFT to Binding(BindingType.KEYBOARD, "A"),
    Slot.RIGHT to Binding(BindingType.KEYBOARD, "D"),
)

internal val TILT_RACING_PROFILE = ControllerProfile("builtin-tilt-racing", "Tilt Racing", 0, 0, controls = listOf(
    LayoutControl("TILT", ControlType.TILT, "Tilt", ControlGeometry(.15, .79, .21, .25), ControlStyle(),
        TILT_KEY_BINDINGS),
    racingPedal("W", "W · Accelerate", .86, .48),
    racingPedal("S", "S · Reverse", .86, .79),
))

private fun racingPedal(key: String, label: String, x: Double, y: Double) =
    LayoutControl(key, ControlType.BUTTON, label, ControlGeometry(x, y, .22, .24), ControlStyle(),
        mapOf(Slot.PRESS to Binding(BindingType.KEYBOARD, key)))

// Project world up onto the display's right axis. Positive means the right edge is lower.
internal fun rightTiltDegrees(w: Float, x: Float, y: Float, z: Float, rotation: Int): Double {
    val upX = 2.0 * (x.toDouble() * z - w.toDouble() * y)
    val upY = 2.0 * (y.toDouble() * z + w.toDouble() * x)
    val rightDown = when (rotation) {
        1 -> upY   // Surface.ROTATION_90
        2 -> upX   // Surface.ROTATION_180
        3 -> -upY  // Surface.ROTATION_270
        else -> -upX
    }
    return Math.toDegrees(asin(rightDown.coerceIn(-1.0, 1.0)))
}

internal class TiltSteering {
    private var neutral: Double? = null
    var direction: Slot? = null
        private set

    fun update(angle: Double): Slot? {
        if (!angle.isFinite()) return direction
        val baseline = neutral
        if (baseline == null) {
            neutral = angle
            return null
        }
        val delta = angle - baseline
        direction = when {
            delta >= 13.0 -> Slot.RIGHT
            delta <= -13.0 -> Slot.LEFT
            direction == Slot.RIGHT && delta > 7.0 -> Slot.RIGHT
            direction == Slot.LEFT && delta < -7.0 -> Slot.LEFT
            else -> null
        }
        return direction
    }

    fun reset() {
        neutral = null
        direction = null
    }
}
