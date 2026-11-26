package com.compasslink3d

private fun key(target: String, vararg modifiers: String) = Binding(BindingType.KEYBOARD, target, modifiers.toList())
private fun mouse(button: String) = Binding(BindingType.MOUSE_BUTTON, button)
private fun xbox(button: String) = Binding(BindingType.GAMEPAD, button)
private fun axis(target: String) = Binding(BindingType.GAMEPAD_AXIS, target)

private fun control(id: String, label: String, type: ControlType, geometry: ControlGeometry,
    vararg bindings: Pair<Slot, Binding>) = LayoutControl(id, type, label, geometry,
    ControlStyle(shape = if (type == ControlType.JOYSTICK) ControlShape.CIRCLE else ControlShape.ROUNDED_RECTANGLE),
    bindings.toMap())

private fun button(id: String, label: String, x: Double, y: Double, binding: Binding) =
    control(id, label, ControlType.BUTTON, ControlGeometry(x, y, .18, .14), Slot.PRESS to binding)

private fun stick(id: String, label: String, x: Double, y: Double, target: String) =
    control(id, label, ControlType.JOYSTICK, ControlGeometry(x, y, .31, .46), Slot.AXIS to axis(target))

private fun pointer() = control("pointer", "Pointer", ControlType.TOUCHPAD,
    ControlGeometry(.50, .38, .85, .41), Slot.POINTER to Binding(BindingType.POINTER), Slot.TAP to mouse("LEFT"))

private val aim = MotionSettings(enabled = true, source = MotionSource.GYROSCOPE, mapping = MotionMapping.MOUSE,
    horizontal = MotionAxis.YAW, vertical = MotionAxis.PITCH, gyroLabel = "Gyro aim", deadZone = .02)

private fun template(id: String, name: String, target: String, targetLabel: String, options: HybridSettings,
    motion: MotionSettings? = null, vararg controls: LayoutControl) =
    ControllerProfile(id, name, 0, 0, options.orientation.locked ?: ProfileOrientation.LANDSCAPE,
        controls.toList() + control("switch", targetLabel, ControlType.PROFILE_SWITCH, ControlGeometry(.50, .92, .30, .13)),
        motion, options.copy(quickSwitch = mapOf("switch" to target)))

internal val HYBRID_TEMPLATES = listOf(
    template("hybrid-driving", "Driving", "hybrid-fps", "Switch to FPS",
        HybridSettings(orientation = OrientationPolicy.LANDSCAPE, haptics = TouchHaptics.LIGHT),
        MotionSettings(enabled = true, horizontal = MotionAxis.ROLL, vertical = MotionAxis.NONE,
            invertX = true, stick = "LEFT_STICK"),
        control("brake", "Brake", ControlType.TRIGGER, ControlGeometry(.14, .62, .17, .48), Slot.AXIS to axis("LEFT_TRIGGER")),
        control("accelerate", "Accelerate", ControlType.TRIGGER, ControlGeometry(.86, .62, .17, .48), Slot.AXIS to axis("RIGHT_TRIGGER")),
        button("camera", "Camera", .34, .44, xbox("Y")),
        button("handbrake", "Handbrake", .66, .44, xbox("A")),
        button("gear", "Gear", .50, .69, xbox("RB")),
    ),
    template("hybrid-fps", "FPS", "hybrid-flight", "Switch to Flight",
        HybridSettings(orientation = OrientationPolicy.LANDSCAPE, gyroControl = ControlSlot("shoulder", Slot.PRESS)),
        aim,
        stick("move", "Move", .19, .66, "LEFT_STICK"),
        control("look", "Look", ControlType.TOUCHPAD, ControlGeometry(.53, .50, .29, .55),
            Slot.POINTER to Binding(BindingType.POINTER)),
        button("shoulder", "RB · Gyro", .86, .18, xbox("RB")),
        button("fire", "Fire", .86, .36, mouse("LEFT")),
        button("aim", "Aim", .86, .53, mouse("RIGHT")),
        button("reload", "Reload", .86, .70, key("R")),
        button("jump", "Jump", .86, .87, key("SPACE")),
        button("interact", "Interact", .18, .27, key("E")),
    ),
    template("hybrid-flight", "Flight", "hybrid-presentation", "Switch to Slides",
        HybridSettings(orientation = OrientationPolicy.LANDSCAPE),
        MotionSettings(enabled = true, horizontal = MotionAxis.ROLL, vertical = MotionAxis.PITCH,
            invertX = true, stick = "LEFT_STICK"),
        stick("yaw", "Yaw", .22, .66, "RIGHT_STICK"),
        control("throttle", "Throttle", ControlType.SLIDER, ControlGeometry(.80, .62, .22, .52),
            Slot.AXIS to axis("RIGHT_TRIGGER")).let { it.copy(settings = it.settings.copy(vertical = true)) },
        button("gear", "Gear", .28, .22, xbox("Y")),
        button("flaps", "Flaps", .52, .22, xbox("X")),
        button("camera", "Camera", .76, .22, xbox("RB")),
    ),
    template("hybrid-presentation", "Presentation", "hybrid-desktop", "Switch to Desktop", HybridSettings(hybrid = false),
        null, pointer(),
        button("previous", "Previous", .17, .71, key("PAGEUP")),
        button("click", "Click", .50, .71, mouse("LEFT")),
        button("next", "Next", .83, .71, key("PAGEDOWN")),
    ),
    template("hybrid-desktop", "Desktop", "hybrid-driving", "Switch to Driving", HybridSettings(hybrid = false),
        aim.copy(enabled = false), pointer(),
        button("left", "Left click", .16, .69, mouse("LEFT")),
        button("right", "Right click", .84, .69, mouse("RIGHT")),
        control("scroll", "Scroll", ControlType.SLIDER, ControlGeometry(.50, .69, .31, .13), Slot.WHEEL to Binding(BindingType.WHEEL)),
        button("copy", "Copy", .15, .88, key("C", "CTRL")),
        button("paste", "Paste", .85, .88, key("V", "CTRL")),
    ),
)
