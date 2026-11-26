package com.compasslink3d

import java.util.concurrent.ArrayBlockingQueue
import kotlin.math.pow
import kotlin.math.round

/** Relative screen-space tracking with speed-based gain and fractional carry. */
internal class TouchPointer(
    private val control: LayoutControl,
    private val density: Double,
    start: LayoutPoint,
    startedAt: Long,
) {
    private var previous = start
    private var previousAt = startedAt
    private var fraction = LayoutPoint(0.0, 0.0)

    fun move(point: LayoutPoint, time: Long, enabled: Boolean = true): ControllerInput.TouchPointer? {
        val elapsed = time - previousAt
        if (elapsed <= 0) return null
        val delta = (point - previous) * (1 / density)
        previous = point
        previousAt = time
        if (!enabled || delta.length == 0.0) return null
        val speed = delta.length * 1000 / elapsed
        val gain = (1 + 1.2 * (speed / 300 - 1).coerceAtLeast(0.0).pow(1.25)).coerceAtMost(4.0)
        val scale = control.settings.sensitivity * gain
        val x = fraction.x + (delta.x * scale * if (control.settings.invertX) -1 else 1).coerceIn(-512.0, 512.0)
        val y = fraction.y + (delta.y * scale * if (control.settings.invertY) -1 else 1).coerceIn(-512.0, 512.0)
        val dx = round(x).toInt().coerceIn(-512, 512)
        val dy = round(y).toInt().coerceIn(-512, 512)
        fraction = LayoutPoint(x - dx, y - dy)
        return if (dx == 0 && dy == 0) null else ControllerInput.TouchPointer(control.id, dx, dy, time)
    }
}

/** Keeps a short ordered window of touch reports. Old motion is never replayed after a stall. */
internal class TouchPointerQueue {
    private val pending = ArrayBlockingQueue<ControllerInput.TouchPointer>(48)
    val hasPending get() = pending.isNotEmpty()

    fun offer(event: ControllerInput.TouchPointer) {
        if (!pending.offer(event)) {
            pending.poll()
            pending.offer(event)
        }
    }

    fun poll(now: Long): ControllerInput.TouchPointer? {
        while (true) {
            val event = pending.poll() ?: return null
            if (now - event.sampledAt <= 80) return event
        }
    }

    fun discard(id: String) { pending.removeIf { it.control == id } }

    fun clear() = pending.clear()
}


/** Quantize relative motion at sample cadence, carrying subpixel movement per mapping. */
internal class MotionPointerReports {
    private val carry = mutableMapOf<String, LayoutPoint>()

    @Synchronized
    fun quantize(event: ControllerInput.Pointer, uptime: Long): ControllerInput.TouchPointer? {
        val sum = event.delta + (carry[event.control] ?: LayoutPoint(0.0, 0.0))
        val dx = round(sum.x).toInt()
        val dy = round(sum.y).toInt()
        carry[event.control] = LayoutPoint(sum.x - dx, sum.y - dy)
        return if (dx == 0 && dy == 0) null else ControllerInput.TouchPointer(event.control, dx, dy, uptime)
    }

    @Synchronized fun discard(id: String) { carry.remove(id) }
    @Synchronized fun clear() { carry.clear() }
}
