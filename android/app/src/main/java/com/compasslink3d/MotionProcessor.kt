package com.compasslink3d

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.expm1
import kotlin.math.sin
import kotlin.math.sqrt

internal const val MOTION_STALE_NS = 200_000_000L
private const val MAX_DELTA = .1
private val ZERO = LayoutPoint(0.0, 0.0)

internal data class MotionQuaternion(val x: Double, val y: Double, val z: Double, val w: Double) {
    fun normalized(): MotionQuaternion? {
        val norm = sqrt(dot(this))
        return if (norm.isFinite() && norm > 1e-6) MotionQuaternion(x / norm, y / norm, z / norm, w / norm) else null
    }

    fun continuous(previous: MotionQuaternion?) =
        if (previous != null && dot(previous) < 0) MotionQuaternion(-x, -y, -z, -w) else this

    private fun dot(q: MotionQuaternion) = x * q.x + y * q.y + z * q.z + w * q.w
    fun inverse() = MotionQuaternion(-x, -y, -z, w) // Unit quaternion.
    operator fun times(q: MotionQuaternion) = MotionQuaternion(
        w * q.x + x * q.w + y * q.z - z * q.y,
        w * q.y - x * q.z + y * q.w + z * q.x,
        w * q.z + x * q.y - y * q.x + z * q.w,
        w * q.w - x * q.x - y * q.y - z * q.z,
    )

    fun angles() = MotionVector(
        asin((-2 * (y * z + x * w)).coerceIn(-1.0, 1.0)),
        atan2(2 * (x * y - z * w), 1 - 2 * (x * x + z * z)),
        atan2(-2 * (x * z - y * w), 1 - 2 * (x * x + y * y)),
    )

    companion object {
        val IDENTITY = MotionQuaternion(0.0, 0.0, 0.0, 1.0)
        private val screenBasis = Array(4) { quarter ->
            // Display rotation is the rotation of the screen relative to its natural orientation.
            val halfAngle = -quarter * PI / 4
            MotionQuaternion(0.0, 0.0, sin(halfAngle), cos(halfAngle))
        }
        fun relative(neutral: MotionQuaternion, current: MotionQuaternion, rotation: Int): MotionQuaternion {
            val basis = screenBasis[rotation]
            return (basis.inverse() * (neutral.inverse() * current) * basis).normalized() ?: IDENTITY
        }
    }
}

internal data class MotionVector(val x: Double, val y: Double, val z: Double) {
    fun display(rotation: Int) = when (rotation) {
        1 -> MotionVector(-y, x, z)
        2 -> MotionVector(-x, -y, z)
        3 -> MotionVector(y, -x, z)
        else -> this
    }
    fun axis(axis: MotionAxis) = when (axis) {
        MotionAxis.PITCH -> x
        MotionAxis.YAW -> y
        MotionAxis.ROLL -> z
        MotionAxis.NONE -> 0.0
    }
}

internal fun wrappedAngle(angle: Double) = atan2(sin(angle), cos(angle))
internal fun freshMotion(timestamp: Long, now: Long) = timestamp > 0 && now - timestamp in 0..MOTION_STALE_NS

internal fun motionSignal(value: Double, deadZone: Double, sensitivity: Double, limit: Double): Double {
    val outside = ((abs(value) - deadZone) / (1 - deadZone)).coerceAtLeast(0.0)
    return Math.copySign((outside * sensitivity).coerceAtMost(limit), value)
}

/** Processes only new monotonic samples. All state and callbacks belong to the activity thread. */
internal class MotionProcessor(
    val settings: MotionSettings,
    val id: String,
    private val send: (ControllerInput) -> Unit,
    private val discardPointer: (String) -> Unit = {},
) {
    private var orientation: MotionQuaternion? = null
    private var neutral: MotionQuaternion? = null
    private var orientationAt = 0L
    private var velocityAt = 0L
    private var processedAt = 0L
    private var awaitingCenter = true
    private var filtered = ZERO
    private var lastStick = ZERO
    private var steering: Slot? = null
    private var activationPressed = false
    var centeredAt = 0L
        private set
    var relative: MotionQuaternion? = null
        private set
    var gyroEnabled = false
        private set
    var output = ZERO
        private set

    fun orientation(q: MotionQuaternion, timestamp: Long, rotation: Int) {
        if (timestamp <= orientationAt) return
        orientationAt = timestamp
        val current = q.continuous(orientation)
        orientation = current
        if (awaitingCenter) {
            captureCenter(current, timestamp)
            return
        }
        val pose = MotionQuaternion.relative(neutral ?: return, current, rotation).continuous(relative)
        relative = pose
        if (settings.source == MotionSource.ORIENTATION) {
            val angles = pose.angles()
            // Projection is continuous at a full turn. Near neutral it behaves as angular deflection.
            process(angles, timestamp) { sin(wrappedAngle(it)) / sin(.65) }
        }
    }

    fun gyro(value: MotionVector, timestamp: Long, rotation: Int) {
        if (timestamp <= velocityAt) return
        if (velocityAt > 0 && !freshMotion(velocityAt, timestamp)) {
            neutralize()
            gyroEnabled = false
        }
        velocityAt = timestamp
        if (settings.source == MotionSource.GYROSCOPE && settings.gyroEnable == GyroEnable.ALWAYS) gyroEnabled = true
        if (!gyroEnabled || settings.source != MotionSource.GYROSCOPE) return
        val (x, y, z) = value.display(rotation)
        process(MotionVector(-x, -z, y), timestamp) { it / 4 }
    }

    fun activate(pressed: Boolean, now: Long) {
        if (settings.source != MotionSource.GYROSCOPE || activationPressed == pressed) return
        activationPressed = pressed
        val next = when (settings.gyroEnable) {
            GyroEnable.HOLD -> pressed
            GyroEnable.TOGGLE -> if (pressed) !gyroEnabled else gyroEnabled
            GyroEnable.ALWAYS -> return
        }
        val enabled = next && freshMotion(velocityAt, now)
        if (enabled != gyroEnabled) {
            neutralize()
            gyroEnabled = enabled
        }
    }

    fun recenter(now: Long) {
        neutralize()
        val current = orientation
        if (current != null && freshMotion(orientationAt, now)) captureCenter(current, orientationAt)
        else {
            awaitingCenter = true
            neutral = null
            relative = null
            centeredAt = 0
        }
    }

    private fun captureCenter(current: MotionQuaternion, timestamp: Long) {
        neutral = current
        relative = MotionQuaternion.IDENTITY
        centeredAt = timestamp
        awaitingCenter = false
    }

    fun fresh(now: Long): Boolean = when (settings.source) {
        MotionSource.ORIENTATION -> !awaitingCenter && freshMotion(orientationAt, now)
        MotionSource.GYROSCOPE -> gyroEnabled && freshMotion(velocityAt, now)
    }

    fun sensorFresh(now: Long) = freshMotion(
        if (settings.source == MotionSource.GYROSCOPE) velocityAt else orientationAt, now,
    )

    fun expire(now: Long) {
        if (fresh(now)) return
        if (processedAt != 0L || gyroEnabled && !freshMotion(velocityAt, now)) neutralize()
        if (gyroEnabled && !freshMotion(velocityAt, now)) gyroEnabled = false
    }

    fun stop() {
        neutralize()
        orientation = null
        orientationAt = 0L
        velocityAt = 0L
        neutral = null
        relative = null
        centeredAt = 0L
        awaitingCenter = true
        gyroEnabled = false
        activationPressed = false
    }

    private inline fun process(axes: MotionVector, timestamp: Long, normalize: (Double) -> Double) {
        val previous = processedAt
        processedAt = timestamp
        if (previous == 0L) return
        val dt = (timestamp - previous) * 1e-9
        if (dt <= 0 || dt > MAX_DELTA) {
            neutralize()
            processedAt = timestamp
            return
        }
        val limit = if (settings.mapping == MotionMapping.STEERING) 1.0 else settings.maximum
        val x = motionSignal(normalize(axes.axis(settings.horizontal)) * if (settings.invertX) -1 else 1,
            settings.deadZone, settings.sensitivity, limit)
        val y = if (settings.mapping == MotionMapping.STEERING) 0.0 else
            motionSignal(normalize(axes.axis(settings.vertical)) * if (settings.invertY) -1 else 1,
                settings.deadZone, settings.sensitivity, limit)
        val alpha = if (settings.smoothingMs == 0.0) 1.0 else -expm1(-dt * 1000 / settings.smoothingMs)
        filtered = LayoutPoint(smooth(filtered.x, x, alpha), smooth(filtered.y, y, alpha))
        output = filtered * (if (filtered.length > limit) limit / filtered.length else 1.0)
        when (settings.mapping) {
            MotionMapping.STICK -> emitStick(output)
            MotionMapping.MOUSE -> if (output != ZERO) {
                val delta = output * (settings.mouseScale * dt)
                send(ControllerInput.Pointer(id, delta, timestamp))
            }
            MotionMapping.STEERING -> steer(when {
                output.x >= settings.engage -> Slot.RIGHT
                output.x <= -settings.engage -> Slot.LEFT
                steering == Slot.RIGHT && output.x > settings.release -> Slot.RIGHT
                steering == Slot.LEFT && output.x < -settings.release -> Slot.LEFT
                else -> null
            })
        }
    }

    private fun smooth(previous: Double, target: Double, alpha: Double) =
        if (abs(target) < 1e-8) 0.0 else previous + (target - previous) * alpha

    private fun emitStick(value: LayoutPoint) {
        if (value == lastStick || (value != ZERO && (lastStick - value).length < .004)) return
        lastStick = value
        send(ControllerInput.Axis(id, value))
    }

    private fun steer(direction: Slot?) {
        if (direction == steering) return
        steering?.let { send(ControllerInput.Digital(id, it, false)) }
        steering = direction
        direction?.let { send(ControllerInput.Digital(id, it, true)) }
    }

    private fun neutralize() {
        processedAt = 0L
        filtered = ZERO
        output = ZERO
        emitStick(ZERO)
        steer(null)
        if (settings.mapping == MotionMapping.MOUSE) discardPointer(id)
    }
}
