package com.compasslink3d

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import org.json.JSONArray
import org.json.JSONObject

internal enum class MotionSensor(val type: Int, val wire: String) {
    GAME(Sensor.TYPE_GAME_ROTATION_VECTOR, "gameRotation"),
    ROTATION(Sensor.TYPE_ROTATION_VECTOR, "rotation"),
    GYRO(Sensor.TYPE_GYROSCOPE, "gyroscope"),
    ACCEL(Sensor.TYPE_ACCELEROMETER, "accelerometer"),
}

internal fun orientationSensor(game: Boolean, rotation: Boolean, relative: Boolean): MotionSensor? =
    if (game && (relative || !rotation)) MotionSensor.GAME else if (rotation) MotionSensor.ROTATION else null

/** Fixed storage for a two-second timestamp window. */
internal class MotionEventRate {
    private val timestamps = LongArray(256)
    private var head = 0
    private var size = 0

    fun add(timestamp: Long) {
        if (size == timestamps.size) {
            head = (head + 1) % timestamps.size
            size--
        }
        timestamps[(head + size) % timestamps.size] = timestamp
        size++
    }

    fun hz(now: Long): Double? {
        while (size > 0 && now - timestamps[head] > 2_000_000_000L) {
            head = (head + 1) % timestamps.size
            size--
        }
        if (size < 2) return null
        val interval = timestamps[(head + size - 1) % timestamps.size] - timestamps[head]
        return if (interval < 400_000_000L) null else ((size - 1) * 1e9 / interval).coerceAtMost(1000.0)
    }

    fun clear() { head = 0; size = 0 }
}

internal class MotionSensors(private val manager: SensorManager, private val onGyro: (MotionVector, Long) -> Unit = { _, _ -> }) : SensorEventListener {
    private class Reading(val sensor: Sensor?) {
        val values = FloatArray(4)
        val rate = MotionEventRate()
        var registered = false
        var accuracy = 0
        var timestamp = 0L
    }
    private val readings = MotionSensor.entries.map { Reading(manager.getDefaultSensor(it.type)) }
    private val quaternion = FloatArray(4)
    var invalid = 0
        private set
    var gameplay: MotionSensor? = null
        private set
    var absolute: MotionSensor? = null
        private set
    var active = false
        private set

    fun available(kind: MotionSensor) = readings[kind.ordinal].sensor != null
    fun rate(kind: MotionSensor?, now: Long) = kind?.let { readings[it.ordinal].rate.hz(now) }
    fun label(kind: MotionSensor?) = kind?.let { readings[it.ordinal].sensor?.name }
    fun timestamp(kind: MotionSensor?) = kind?.let { readings[it.ordinal].timestamp } ?: 0L

    fun configure(streaming: Boolean, advanced: Boolean, legacyTilt: Boolean) {
        val orientationNeeded = streaming || advanced || legacyTilt
        val preferGame = advanced
        val gameFirst = orientationSensor(available(MotionSensor.GAME), available(MotionSensor.ROTATION), preferGame) == MotionSensor.GAME
        register(MotionSensor.GAME, orientationNeeded && gameFirst)
        val needRotation = orientationNeeded && ((!gameFirst || !registered(MotionSensor.GAME)) ||
            (streaming && available(MotionSensor.ROTATION)))
        register(MotionSensor.ROTATION, needRotation)
        if (orientationNeeded && !registered(MotionSensor.ROTATION) && !registered(MotionSensor.GAME)) {
            register(MotionSensor.GAME, true)
        }
        gameplay = orientationSensor(registered(MotionSensor.GAME), registered(MotionSensor.ROTATION), preferGame)
        absolute = orientationSensor(registered(MotionSensor.GAME), registered(MotionSensor.ROTATION), false)
        register(MotionSensor.GYRO, advanced)
        register(MotionSensor.ACCEL, advanced)
        active = readings.any { it.registered }
    }

    private fun registered(kind: MotionSensor) = readings[kind.ordinal].registered

    private fun register(kind: MotionSensor, wanted: Boolean) {
        val reading = readings[kind.ordinal]
        val sensor = reading.sensor ?: return
        if (wanted == reading.registered) return
        if (wanted) reading.registered = manager.registerListener(this, sensor, if (kind == MotionSensor.GYRO) 10_000 else 20_000)
        else {
            manager.unregisterListener(this, sensor)
            reading.registered = false
            reading.timestamp = 0
            reading.rate.clear()
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        val kind = MotionSensor.entries.firstOrNull { it.type == event.sensor.type } ?: return
        val reading = readings[kind.ordinal]
        if (!reading.registered) return
        val time = event.timestamp
        val values = event.values
        val fuse = kind == MotionSensor.GAME || kind == MotionSensor.ROTATION
        val count = if (fuse && values.size >= 4) 4 else 3
        if (time <= reading.timestamp || values.size < count || !valid(values, count, if (fuse) 2f else 1000f)) {
            invalid = (invalid + 1).coerceAtMost(1_000_000)
            return
        }
        if (fuse) {
            SensorManager.getQuaternionFromVector(quaternion, values)
            var norm = 0f
            for (value in quaternion) norm += value * value
            if (!norm.isFinite() || norm < .000001f) {
                invalid = (invalid + 1).coerceAtMost(1_000_000)
                return
            }
            quaternion.copyInto(reading.values)
        } else values.copyInto(reading.values, endIndex = 3)
        reading.accuracy = event.accuracy
        reading.timestamp = time
        reading.rate.add(time)
        if (kind == MotionSensor.GYRO) onGyro(vector(kind), time)
    }

    private fun valid(values: FloatArray, count: Int, limit: Float): Boolean {
        for (i in 0 until count) if (values[i] !in -limit..limit) return false
        return true
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    fun pose(kind: MotionSensor): MotionQuaternion? {
        val (w, x, y, z) = readings[kind.ordinal].values
        return MotionQuaternion(x.toDouble(), y.toDouble(), z.toDouble(), w.toDouble()).normalized()
    }

    fun vector(kind: MotionSensor): MotionVector {
        val (x, y, z) = readings[kind.ordinal].values
        return MotionVector(x.toDouble(), y.toDouble(), z.toDouble())
    }

    fun telemetry(now: Long, processors: List<MotionProcessor>, connection: String, includePose: Boolean): SensorSample {
        val used = absolute?.takeIf { includePose && freshMotion(timestamp(it), now) }
        val pose = used?.let(::pose)
        val diagnostics = processors.map {
            val source = gameplay?.wire
            val age = it.centeredAt.takeIf { time -> time > 0 }?.let { time -> (now - time).coerceAtLeast(0) / 1_000_000 }
            MotionDiagnostics(source, MotionSensor.entries.map { kind ->
                val reading = readings[kind.ordinal]
                SensorDiagnostic(kind.wire, reading.sensor != null, reading.registered, reading.rate.hz(now))
            }, it.relative, age,
                if (freshMotion(timestamp(MotionSensor.GYRO), now)) vector(MotionSensor.GYRO) else null,
                if (freshMotion(timestamp(MotionSensor.ACCEL), now)) vector(MotionSensor.ACCEL) else null,
                it.settings.mapping.name, it.settings.source.name,
                when (it.settings.mapping) {
                    MotionMapping.STICK -> it.settings.stick
                    MotionMapping.MOUSE -> "POINTER"
                    MotionMapping.STEERING -> "${it.settings.leftKey} / ${it.settings.rightKey}"
                }, it.gyroEnabled,
                !freshMotion(timestamp(if (it.settings.source == MotionSource.GYROSCOPE) MotionSensor.GYRO else gameplay), now),
                invalid, it.output)
        }
        return SensorSample(pose, used?.let { readings[it.ordinal].sensor?.stringType },
            used?.let { readings[it.ordinal].accuracy }, timestamp(used), System.currentTimeMillis(), connection, diagnostics)
    }
}

internal data class SensorDiagnostic(val sensor: String, val available: Boolean, val active: Boolean, val hz: Double?)
internal data class MotionDiagnostics(
    val orientation: String?,
    val sensors: List<SensorDiagnostic>,
    val relative: MotionQuaternion?,
    val centerAgeMs: Long?,
    val gyro: MotionVector?,
    val accel: MotionVector?,
    val mapping: String,
    val source: String,
    val target: String,
    val gyroEnabled: Boolean,
    val stale: Boolean,
    val invalid: Int,
    val output: LayoutPoint,
) {
    fun encode() = JSONObject().put("orientation", orientation ?: JSONObject.NULL)
        .put("sensors", JSONObject().apply {
            sensors.forEach { s -> put(s.sensor, JSONObject().put("available", s.available).put("active", s.active)
                .put("hz", s.hz ?: JSONObject.NULL)) }
        })
        .put("relative", relative?.json() ?: JSONObject.NULL).put("centerAgeMs", centerAgeMs ?: JSONObject.NULL)
        .put("gyro", gyro?.json() ?: JSONObject.NULL).put("accel", accel?.json() ?: JSONObject.NULL)
        .put("mapping", mapping).put("source", source).put("target", target)
        .put("gyroEnabled", gyroEnabled).put("stale", stale).put("invalid", invalid)
        .put("output", JSONArray().put(output.x).put(output.y))
}

internal data class SensorSample(
    val pose: MotionQuaternion?,
    val sensor: String?,
    val accuracy: Int?,
    val timestamp: Long,
    val wallTime: Long,
    val connection: String,
    val mappings: List<MotionDiagnostics>,
) {
    fun encode() = JSONObject().put("q", pose?.json() ?: JSONObject.NULL)
        .put("sensor", sensor ?: JSONObject.NULL).put("accuracy", accuracy ?: JSONObject.NULL)
        .put("timestampNs", timestamp.toString()).put("t", wallTime).put("connectionMode", connection)
        .put("motion", mappings.firstOrNull()?.encode()?.apply {
            put("mappings", JSONArray().apply { mappings.forEach { put(it.encode()) } })
        } ?: JSONObject.NULL).toString()
}

private fun MotionQuaternion.json() = JSONArray().put(x).put(y).put(z).put(w)
private fun MotionVector.json() = JSONArray().put(x).put(y).put(z)


internal fun MotionProcessor.summary(now: Long): String {
    val target = when (settings.mapping) {
        MotionMapping.MOUSE -> "Mouse"
        MotionMapping.STICK -> settings.stick.replace('_', ' ')
        MotionMapping.STEERING -> "${settings.leftKey}/${settings.rightKey}"
    }
    val state = when {
        !sensorFresh(now) -> "waiting for fresh samples"
        settings.source == MotionSource.GYROSCOPE && !gyroEnabled -> "off"
        fresh(now) -> "live"
        else -> "waiting for center"
    }
    return "${settings.source.title} → $target · $state · %.2f, %.2f".format(output.x, output.y)
}
