package com.compasslink3d

import kotlin.math.abs

internal sealed interface ControllerInput {
    data class Digital(val control: String, val slot: Slot, val down: Boolean) : ControllerInput
    data class Axis(val control: String, val value: LayoutPoint) : ControllerInput
    data class Pointer(val control: String, val delta: LayoutPoint) : ControllerInput
    data class Wheel(val control: String, val ticks: Double) : ControllerInput
    data object Neutral : ControllerInput
}

internal data class PhysicalControl(val model: LayoutControl, val box: ControlBox) {
    val cornerRadius = when {
        model.round -> box.width / 2
        model.style.shape == ControlShape.RECTANGLE -> 0.0
        else -> minOf(box.width, box.height) * model.style.cornerRadius
    }

    fun contains(point: LayoutPoint) = box.contains(point, model.round, cornerRadius)
}

internal class ControllerTouchEngine(
    private val density: Double,
    private val emit: (ControllerInput) -> Unit,
) {
    private data class Contact(
        var target: PhysicalControl?,
        var previous: LayoutPoint,
        val started: Long,
        var traveled: Double = 0.0,
        var value: Double? = null,
        var slots: Set<Slot> = emptySet(),
    )

    var controls: List<PhysicalControl> = emptyList()
    private val contacts = mutableMapOf<Int, Contact>()
    private val owners = mutableMapOf<Pair<String, Slot>, Int>()
    private val vectors = mutableMapOf<String, LayoutPoint>()
    fun value(id: String) = vectors[id] ?: LayoutPoint(0.0, 0.0)
    fun active(id: String, slot: Slot? = null) = owners.keys.any { it.first == id && (slot == null || slot == it.second) }
    fun touched(id: String) = contacts.values.any { it.target?.model?.id == id }

    fun down(id: Int, point: LayoutPoint, time: Long) {
        if (contacts.containsKey(id)) return
        val target = hit(point) ?: return
        if (target.model.type.capturesDrag && touched(target.model.id)) return
        val contact = Contact(target, point, time)
        contacts[id] = contact
        update(contact, point)
    }

    fun move(id: Int, point: LayoutPoint) {
        val contact = contacts[id] ?: return
        if (contact.target?.model?.type?.capturesDrag != true) {
            val next = hit(point)?.takeUnless { it.model.type.capturesDrag }
            if (next != contact.target) {
                changeSlots(contact, emptySet())
                contact.target = next
            }
        }
        update(contact, point)
    }

    fun up(id: Int, point: LayoutPoint, time: Long, canceled: Boolean) {
        val contact = contacts.remove(id) ?: return
        val target = contact.target?.model ?: return
        if (!canceled && target.type == ControlType.TOUCHPAD && target.settings.tapToClick &&
            time - contact.started <= 250 && contact.traveled + (point - contact.previous).length <= 12 * density) {
            changeSlots(contact, setOf(Slot.TAP))
        }
        changeSlots(contact, emptySet())
        if (target.type == ControlType.JOYSTICK || target.type == ControlType.TRIGGER) {
            setAxis(target.id, LayoutPoint(0.0, 0.0))
        }
    }

    fun clear(notify: Boolean) {
        contacts.clear()
        owners.clear()
        vectors.clear()
        if (notify) emit(ControllerInput.Neutral)
    }

    fun click(control: LayoutControl) {
        val slot = if (control.type == ControlType.TOUCHPAD) Slot.TAP else Slot.PRESS
        val binding = control.bindings[slot] ?: return
        if (binding.type == BindingType.GAMEPAD || active(control.id, slot)) return
        emit(ControllerInput.Digital(control.id, slot, true))
        emit(ControllerInput.Digital(control.id, slot, false))
    }

    private fun hit(point: LayoutPoint) = controls.lastOrNull { it.contains(point) }

    private fun update(contact: Contact, point: LayoutPoint) {
        val target = contact.target ?: return
        val control = target.model
        val local = target.box.local(point)
        val last = target.box.local(contact.previous)
        val x = (2 * local.x / target.box.width).coerceIn(-1.0, 1.0)
        val y = (2 * local.y / target.box.height).coerceIn(-1.0, 1.0)
        val next = when (control.type) {
            ControlType.BUTTON -> setOf(Slot.PRESS)
            ControlType.DPAD -> dpadSlots(x, y)
            ControlType.JOYSTICK -> {
                val axes = joystickAxes(local, target.box.width / 2 * control.settings.radius, control.settings.deadZone)
                setAxis(control.id, axes)
                joystickSlots(axes, contact.slots, control.settings)
            }
            ControlType.TRIGGER -> {
                val level = (y + 1) / 2
                setAxis(control.id, LayoutPoint(level, 0.0))
                val threshold = control.settings.threshold -
                    if (Slot.PRESS in contact.slots) control.settings.hysteresis else 0.0
                if (level >= threshold.coerceAtLeast(.02)) setOf(Slot.PRESS) else emptySet()
            }
            ControlType.SLIDER -> {
                val options = control.settings
                val level = ((if (options.vertical) -y else x) + 1) / 2
                val previous = contact.value
                if (control.bindings[Slot.WHEEL]?.type == BindingType.WHEEL && previous != null && level != previous)
                    emit(ControllerInput.Wheel(control.id, (level - previous) * 24))
                contact.value = level
                setAxis(control.id, LayoutPoint(level, 0.0))
                val tolerance = if (Slot.PRESS in contact.slots) options.hysteresis else 0.0
                if (level in (options.rangeStart - tolerance)..(options.rangeEnd + tolerance)) setOf(Slot.PRESS) else emptySet()
            }
            ControlType.TOUCHPAD -> {
                val delta = local - last
                contact.traveled += delta.length
                if (control.bindings[Slot.POINTER]?.type == BindingType.POINTER && contact.traveled > 8 * density) {
                    val scale = control.settings.sensitivity / density
                    val dx = delta.x * scale * if (control.settings.invertX) -1 else 1
                    val dy = delta.y * scale * if (control.settings.invertY) -1 else 1
                    if (dx != 0.0 || dy != 0.0) emit(ControllerInput.Pointer(control.id, LayoutPoint(dx, dy)))
                }
                emptySet()
            }
        }
        contact.previous = point
        changeSlots(contact, next)
    }

    private fun changeSlots(contact: Contact, next: Set<Slot>) {
        val control = contact.target?.model ?: return
        for (slot in contact.slots - next) changeOwner(control, slot, false)
        for (slot in next - contact.slots) changeOwner(control, slot, true)
        contact.slots = next
    }

    private fun changeOwner(control: LayoutControl, slot: Slot, down: Boolean) {
        val key = control.id to slot
        val previous = owners[key] ?: 0
        val count = previous + if (down) 1 else -1
        if (count == 0) owners.remove(key) else owners[key] = count
        val binding = control.bindings[slot]
        if (binding != null && binding.type != BindingType.GAMEPAD && (if (down) previous == 0 else count == 0)) {
            emit(ControllerInput.Digital(control.id, slot, down))
        }
    }

    private fun setAxis(id: String, value: LayoutPoint) {
        if (value(id) != value) {
            vectors[id] = value
            emit(ControllerInput.Axis(id, value))
        }
    }

    private val ControlType.capturesDrag get() = this != ControlType.BUTTON && this != ControlType.DPAD
}

internal fun dpadSlots(x: Double, y: Double): Set<Slot> = buildSet {
    if (maxOf(abs(x), abs(y)) < .27) return@buildSet
    if (abs(x) >= abs(y) * .48 && x != 0.0) add(if (x < 0) Slot.LEFT else Slot.RIGHT)
    if (abs(y) >= abs(x) * .48 && y != 0.0) add(if (y < 0) Slot.UP else Slot.DOWN)
}

internal fun joystickAxes(point: LayoutPoint, radius: Double, deadZone: Double): LayoutPoint {
    val length = point.length
    if (radius <= 0 || length == 0.0) return LayoutPoint(0.0, 0.0)
    val fraction = (length / radius).coerceAtMost(1.0)
    val magnitude = ((fraction - deadZone) / (1 - deadZone)).coerceAtLeast(0.0)
    return point * (magnitude / length)
}

internal fun joystickSlots(axes: LayoutPoint, previous: Set<Slot>, options: ControlSettings): Set<Slot> = buildSet {
    fun consider(slot: Slot, value: Double) {
        val threshold = options.threshold - if (slot in previous) options.hysteresis else 0.0
        if (value >= threshold.coerceAtLeast(.02)) add(slot)
    }
    consider(Slot.LEFT, -axes.x)
    consider(Slot.RIGHT, axes.x)
    consider(Slot.UP, -axes.y)
    consider(Slot.DOWN, axes.y)
}
