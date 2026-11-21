package com.compasslink3d

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

internal data class LayoutPoint(val x: Double, val y: Double) {
    operator fun plus(other: LayoutPoint) = LayoutPoint(x + other.x, y + other.y)
    operator fun minus(other: LayoutPoint) = LayoutPoint(x - other.x, y - other.y)
    operator fun times(scale: Double) = LayoutPoint(x * scale, y * scale)
    val length: Double get() = hypot(x, y)
    val angle: Double get() = atan2(y, x) * 180.0 / PI
}

internal data class ControlBox(val center: LayoutPoint, val width: Double, val height: Double, val rotation: Double) {
    private val cosine = cos(rotation * PI / 180)
    private val sine = sin(rotation * PI / 180)
    val halfX = abs(width * cosine) / 2 + abs(height * sine) / 2
    val halfY = abs(width * sine) / 2 + abs(height * cosine) / 2

    fun local(point: LayoutPoint): LayoutPoint {
        val offset = point - center
        return LayoutPoint(offset.x * cosine + offset.y * sine, -offset.x * sine + offset.y * cosine)
    }

    fun world(local: LayoutPoint) = center + LayoutPoint(local.x * cosine - local.y * sine, local.x * sine + local.y * cosine)

    fun contains(point: LayoutPoint, round: Boolean, radius: Double = 0.0): Boolean {
        val local = local(point)
        if (round) return local.length <= width / 2
        val x = abs(local.x)
        val y = abs(local.y)
        if (x > width / 2 || y > height / 2) return false
        return hypot(maxOf(x - width / 2 + radius, 0.0), maxOf(y - height / 2 + radius, 0.0)) <= radius
    }
}

internal class ControllerViewport(val width: Int, val height: Int, private val minimumTargetPx: Double) {
    fun box(control: LayoutControl): ControlBox {
        val geometry = control.geometry
        var w = (geometry.width * width).coerceIn(minOf(minimumTargetPx, width.toDouble()), width.toDouble())
        var h = (geometry.height * height).coerceIn(minOf(minimumTargetPx, height.toDouble()), height.toDouble())
        if (control.round) {
            w = minOf(w, h)
            h = w
        }
        val center = LayoutPoint(geometry.x * width, geometry.y * height)
        val unbounded = ControlBox(center, w, h, geometry.rotation)
        val extentX = if (control.round) w / 2 else unbounded.halfX
        val extentY = if (control.round) h / 2 else unbounded.halfY
        val fit = minOf(1.0, width / (2 * extentX), height / (2 * extentY))
        val padX = (extentX * fit).coerceAtMost(width / 2.0)
        val padY = (extentY * fit).coerceAtMost(height / 2.0)
        return ControlBox(LayoutPoint(center.x.coerceIn(padX, width - padX), center.y.coerceIn(padY, height - padY)),
            w * fit, h * fit, geometry.rotation)
    }

    /** Saves the visible, clamped placement in the same unit space as the profile. */
    fun clamp(control: LayoutControl): LayoutControl {
        val box = box(control)
        return control.copy(geometry = ControlGeometry(
            box.center.x / width, box.center.y / height, (box.width / width).coerceAtLeast(.01),
            (box.height / height).coerceAtLeast(.01), box.rotation,
        ))
    }
}

internal fun wrapDegrees(degrees: Double) = ((degrees + 180) % 360 + 360) % 360 - 180
