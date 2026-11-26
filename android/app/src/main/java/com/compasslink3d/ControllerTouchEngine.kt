package com.compasslink3d

import kotlin.math.abs

internal sealed interface ControllerInput {
    data class Digital(val control: String, val slot: Slot, val down: Boolean) : ControllerInput
    data class Axis(val control: String, val value: LayoutPoint) : ControllerInput
    data class Pointer(val control: String, val delta: LayoutPoint, val timestampNs: Long = 0) : ControllerInput
    data class TouchPointer(val control: String, val dx: Int, val dy: Int, val sampledAt: Long) : ControllerInput
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
    private val mode: OutputMode = OutputMode.KEYBOARD,
    private val onPress: () -> Unit = {},
    private val onSwitch: (String) -> Unit = {},
    private val onPointerReleased: (String) -> Unit = {},
) {
    private data class Contact(
        var target: PhysicalControl?,
        var previous: LayoutPoint,
        val started: Long,
        val relativeLook: Boolean,
        val startedOnTarget: Boolean,
        val pointer: TouchPointer?,
        var previousAt: Long = started,
        var lookExpires: Long? = null,
        var traveled: Double = 0.0,
        var value: Double? = null,
        var slots: Set<Slot> = emptySet(),
    )

    var controls: List<PhysicalControl> = emptyList()
    var backgroundLook: PhysicalControl? = null
    private val contacts = mutableMapOf<Int, Contact>()
    private val owners = mutableMapOf<Pair<String, Slot>, Int>()
    private val vectors = mutableMapOf<String, LayoutPoint>()
    private val steering = TiltSteering()
    private var tiltControl: LayoutControl? = null
    private var tiltExpires: Long? = null
    val motionExpiry: Long?
        get() = (contacts.values.mapNotNull { it.lookExpires } + listOfNotNull(tiltExpires)).minOrNull()

    fun value(id: String) = vectors[id] ?: LayoutPoint(0.0, 0.0)
    fun active(id: String, slot: Slot? = null) = owners.keys.any { it.first == id && (slot == null || slot == it.second) }
    fun touched(id: String) = contacts.values.any { it.target?.model?.id == id }
    fun tiltEnabled(id: String) = tiltControl?.id == id
    fun tracksPointer(id: Int) = contacts[id]?.pointer != null

    fun down(id: Int, point: LayoutPoint, time: Long) {
        if (contacts.containsKey(id)) return
        val hit = hit(point)
        val target = hit ?: backgroundLook ?: controls.lastOrNull { isLookPad(it.model) }
            ?: controls.lastOrNull { hasRightStick(it.model) } ?: return
        if (target.model.type.capturesDrag && touched(target.model.id)) return
        val isBackground = target === backgroundLook
        val model = target.model
        val pointer = if ((hit != null || isBackground) && model.bindings[Slot.POINTER]?.supports(mode) == true)
            TouchPointer(model, density, point, time) else null
        val contact = Contact(target, point, time, !isBackground && (hit == null || isLookPad(model)), hit != null, pointer)
        contacts[id] = contact
        update(contact, point, time)
    }

    fun move(id: Int, point: LayoutPoint, time: Long? = null) {
        val contact = contacts[id] ?: return
        if (contact.target?.model?.type?.capturesDrag != true) {
            val next = hit(point)?.takeUnless { it.model.type.capturesDrag }
            if (next != contact.target) {
                changeSlots(contact, emptySet())
                contact.target = next
            }
        }
        update(contact, point, time ?: contact.previousAt + 16)
    }

    fun up(id: Int, point: LayoutPoint, time: Long, canceled: Boolean) {
        val contact = contacts.remove(id) ?: return
        val target = contact.target?.model ?: return
        if (target.type == ControlType.TILT) {
            if (!canceled && contact.target?.contains(point) == true) toggleTilt(target)
            return
        }
        if (!canceled && contact.pointer != null && point != contact.previous) updatePointer(contact, target, point, time)
        if (contact.pointer != null) onPointerReleased(target.id)
        if (!canceled && target.type == ControlType.TOUCHPAD && target.settings.tapToClick &&
            (!contact.relativeLook || mode == OutputMode.HYBRID && contact.startedOnTarget) &&
            time - contact.started <= 250 &&
            contact.traveled + (if (contact.pointer == null) (point - contact.previous).length else 0.0) <= 12 * density) {
            changeSlots(contact, setOf(Slot.TAP))
        }
        changeSlots(contact, emptySet())
        if (target.type == ControlType.JOYSTICK || target.type == ControlType.TRIGGER ||
            contact.relativeLook || (mode != OutputMode.KEYBOARD && target.type == ControlType.SLIDER)) {
            setAxis(target, LayoutPoint(0.0, 0.0))
        }
    }

    fun clear(notify: Boolean) {
        contacts.clear()
        owners.clear()
        vectors.clear()
        tiltControl = null
        tiltExpires = null
        steering.reset()
        if (notify) emit(ControllerInput.Neutral)
    }

    fun click(control: LayoutControl) {
        if (control.type == ControlType.TILT) {
            toggleTilt(control)
            return
        }
        if (control.type == ControlType.PROFILE_SWITCH) {
            if (!active(control.id, Slot.PRESS)) {
                onPress()
                onSwitch(control.id)
            }
            return
        }
        val slot = if (control.type == ControlType.TOUCHPAD) Slot.TAP else Slot.PRESS
        val binding = control.bindings[slot] ?: return
        if (!binding.supports(mode) || active(control.id, slot)) return
        emit(ControllerInput.Digital(control.id, slot, true))
        emit(ControllerInput.Digital(control.id, slot, false))
        onPress()
    }

    fun accessiblePress(target: PhysicalControl, direction: Slot?, progress: Double?, time: Long): Boolean {
        val model = target.model
        if (model.type in listOf(ControlType.TOUCHPAD, ControlType.TILT, ControlType.PROFILE_SWITCH)) {
            click(model)
            return true
        }
        if (model.type.capturesDrag && touched(model.id)) return false
        val box = target.box
        val level = progress?.coerceIn(0.0, 1.0) ?: 1.0
        val local = when (direction) {
            Slot.UP -> LayoutPoint(0.0, -box.height / 2)
            Slot.DOWN -> LayoutPoint(0.0, box.height / 2)
            Slot.LEFT -> LayoutPoint(-box.width / 2, 0.0)
            Slot.RIGHT -> LayoutPoint(box.width / 2, 0.0)
            else -> when {
                model.type == ControlType.TRIGGER -> LayoutPoint(0.0, (level - .5) * box.height)
                model.type == ControlType.SLIDER && model.settings.vertical -> LayoutPoint(0.0, (.5 - level) * box.height)
                model.type == ControlType.SLIDER -> LayoutPoint((level - .5) * box.width, 0.0)
                else -> LayoutPoint(0.0, 0.0)
            }
        }
        val point = box.world(local)
        val contact = Contact(target, box.center, time, false, true, null)
        contacts[ACCESSIBILITY_POINTER] = contact
        if (model.type == ControlType.SLIDER && model.bindings[Slot.WHEEL]?.supports(mode) == true)
            contact.value = value(model.id).x
        update(contact, point, time)
        return true
    }

    fun accessibleRelease(time: Long) {
        val contact = contacts[ACCESSIBILITY_POINTER] ?: return
        up(ACCESSIBILITY_POINTER, contact.previous, time, canceled = true)
    }

    fun updateTilt(angle: Double, time: Long): Boolean {
        val control = tiltControl ?: return false
        if (!angle.isFinite()) return false
        val previous = steering.direction
        val current = steering.update(angle)
        tiltExpires = time + 350
        if (previous == current) return false
        previous?.let { changeOwner(control, it, false) }
        current?.let { changeOwner(control, it, true) }
        return true
    }

    fun expireMotion(time: Long) {
        if (tiltExpires?.let { time >= it } == true) stopTilt()
        for (contact in contacts.values) {
            if (contact.lookExpires?.let { time >= it } != true) continue
            contact.lookExpires = null
            contact.target?.let { setAxis(it.model, LayoutPoint(0.0, 0.0)) }
        }
    }

    private fun stopTilt() {
        val control = tiltControl
        if (control != null) steering.direction?.let { changeOwner(control, it, false) }
        tiltControl = null
        tiltExpires = null
        steering.reset()
    }

    private fun toggleTilt(control: LayoutControl) {
        val enabled = tiltEnabled(control.id)
        stopTilt()
        if (!enabled && control.bindings.values.any { it.supports(mode) }) tiltControl = control
        onPress()
    }

    private fun hit(point: LayoutPoint) = controls.lastOrNull { it.contains(point) }

    private fun hasRightStick(control: LayoutControl) =
        mode != OutputMode.KEYBOARD && control.bindings[Slot.AXIS]?.target == "RIGHT_STICK"

    private fun isLookPad(control: LayoutControl) = control.type == ControlType.TOUCHPAD && hasRightStick(control)

    private fun update(contact: Contact, point: LayoutPoint, time: Long) {
        val target = contact.target ?: return
        val control = target.model
        val previousPoint = contact.previous
        val previousAt = contact.previousAt
        if (contact.pointer != null) updatePointer(contact, control, point, time)
        else if (control.type == ControlType.TOUCHPAD) contact.traveled += (point - previousPoint).length
        contact.previous = point
        contact.previousAt = time
        if (control.type == ControlType.TOUCH_LOOK) return
        if (contact.relativeLook) {
            val drag = point - previousPoint
            if (drag.length >= .25 * density) {
                val elapsed = (time - previousAt).coerceIn(8L, 40L)
                val gain = control.settings.sensitivity * 1000 / (650 * density * elapsed)
                val scale = minOf(gain, 1 / drag.length)
                val xDirection = if (control.settings.invertX) -scale else scale
                val yDirection = if (control.settings.invertY) -scale else scale
                setAxis(control, LayoutPoint(drag.x * xDirection, drag.y * yDirection))
                contact.lookExpires = time + 80
            }
            return
        }
        val local = target.box.local(point)
        val x = (2 * local.x / target.box.width).coerceIn(-1.0, 1.0)
        val y = (2 * local.y / target.box.height).coerceIn(-1.0, 1.0)
        val next = when (control.type) {
            ControlType.BUTTON, ControlType.PROFILE_SWITCH -> setOf(Slot.PRESS)
            ControlType.TILT -> emptySet()
            ControlType.DPAD -> dpadSlots(x, y)
            ControlType.JOYSTICK -> {
                val axes = joystickAxes(local, target.box.width / 2 * control.settings.radius, control.settings.deadZone)
                val output = if (mode != OutputMode.KEYBOARD && axes.length > 0)
                    axes * minOf(control.settings.sensitivity, 1 / axes.length) else axes
                setAxis(control, output)
                joystickSlots(axes, contact.slots, control.settings)
            }
            ControlType.TRIGGER -> {
                val level = (y + 1) / 2
                setAxis(control, LayoutPoint(level, 0.0))
                val threshold = control.settings.threshold -
                    if (Slot.PRESS in contact.slots) control.settings.hysteresis else 0.0
                if (level >= threshold.coerceAtLeast(.02)) setOf(Slot.PRESS) else emptySet()
            }
            ControlType.SLIDER -> {
                val options = control.settings
                val level = ((if (options.vertical) -y else x) + 1) / 2
                val previous = contact.value
                if (mode != OutputMode.GAMEPAD && control.bindings[Slot.WHEEL]?.type == BindingType.WHEEL &&
                    previous != null && level != previous)
                    emit(ControllerInput.Wheel(control.id, (level - previous) * 24))
                contact.value = level
                setAxis(control, LayoutPoint(level, 0.0))
                val tolerance = if (Slot.PRESS in contact.slots) options.hysteresis else 0.0
                if (level in (options.rangeStart - tolerance)..(options.rangeEnd + tolerance)) setOf(Slot.PRESS) else emptySet()
            }
            ControlType.TOUCHPAD, ControlType.TOUCH_LOOK -> emptySet()
        }
        changeSlots(contact, next)
    }

    private fun updatePointer(contact: Contact, control: LayoutControl, point: LayoutPoint, time: Long) {
        contact.traveled += (point - contact.previous).length
        val tapBinding = control.bindings[Slot.TAP]
        val hasTap = control.settings.tapToClick && tapBinding?.supports(mode) == true
        // Explicit pads with tap-to-click still suppress finger wobble on a tap.
        contact.pointer?.move(point, time, enabled = !hasTap || contact.traveled > 8 * density)?.let(emit)
    }

    private fun changeSlots(contact: Contact, next: Set<Slot>) {
        val control = contact.target?.model ?: return
        for (slot in contact.slots - next) changeOwner(control, slot, false)
        var activated = false
        for (slot in next - contact.slots) {
            if (changeOwner(control, slot, true)) activated = true
        }
        if (activated && control.type != ControlType.JOYSTICK) onPress()
        contact.slots = next
        if (activated && control.type == ControlType.PROFILE_SWITCH) onSwitch(control.id)
    }

    private fun changeOwner(control: LayoutControl, slot: Slot, down: Boolean): Boolean {
        val key = control.id to slot
        val previous = owners[key] ?: 0
        val count = previous + if (down) 1 else -1
        if (count == 0) owners.remove(key) else owners[key] = count
        val binding = control.bindings[slot]
        val transition = if (down) previous == 0 else count == 0
        if (transition && binding?.supports(mode) == true) {
            emit(ControllerInput.Digital(control.id, slot, down))
        }
        val analogTrigger = control.type == ControlType.TRIGGER && control.bindings[Slot.AXIS]?.supports(mode) == true
        return down && transition && (binding?.supports(mode) == true || analogTrigger || control.type == ControlType.PROFILE_SWITCH)
    }

    private fun setAxis(control: LayoutControl, value: LayoutPoint) {
        if (value(control.id) != value) {
            vectors[control.id] = value
            if (mode == OutputMode.KEYBOARD || control.bindings[Slot.AXIS]?.type == BindingType.GAMEPAD_AXIS)
                emit(ControllerInput.Axis(control.id, value))
        }
    }

    private val ControlType.capturesDrag get() = this != ControlType.BUTTON && this != ControlType.DPAD
}

private const val ACCESSIBILITY_POINTER = -2

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
