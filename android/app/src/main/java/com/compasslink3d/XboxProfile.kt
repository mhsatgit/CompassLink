package com.compasslink3d

private fun xboxButton(target: String, x: Double, y: Double, round: Boolean = false): LayoutControl =
    LayoutControl(target, ControlType.BUTTON, target,
        ControlGeometry(x, y, .11, if (round) .21 else .16),
        ControlStyle(shape = if (round) ControlShape.CIRCLE else ControlShape.ROUNDED_RECTANGLE),
        mapOf(Slot.PRESS to Binding(BindingType.GAMEPAD, target)))

private fun xboxAxis(target: String, type: ControlType, label: String, x: Double, y: Double): LayoutControl =
    LayoutControl(target, type, label,
        if (type == ControlType.JOYSTICK) ControlGeometry(x, y, .20, .49) else ControlGeometry(x, y, .15, .19),
        ControlStyle(), mapOf(Slot.AXIS to Binding(BindingType.GAMEPAD_AXIS, target)))

internal val XBOX_PROFILE = ControllerProfile("builtin-xbox", "Xbox Controller", 0, 0, controls = listOf(
    xboxAxis("LEFT_TRIGGER", ControlType.TRIGGER, "LT", .095, .12),
    xboxButton("LB", .25, .12),
    xboxButton("BACK", .42, .12),
    xboxButton("START", .58, .12),
    xboxButton("RB", .75, .12),
    xboxAxis("RIGHT_TRIGGER", ControlType.TRIGGER, "RT", .905, .12),
    xboxAxis("LEFT_STICK", ControlType.JOYSTICK, "Left stick", .18, .50),
    LayoutControl("DPAD", ControlType.DPAD, "D-pad", ControlGeometry(.37, .73, .16, .44),
        ControlStyle(), listOf(Slot.UP, Slot.DOWN, Slot.LEFT, Slot.RIGHT).associateWith {
            Binding(BindingType.GAMEPAD, "DPAD_" + it.name)
        }),
    LayoutControl("RIGHT_STICK", ControlType.TOUCHPAD, "Look", ControlGeometry(.60, .50, .20, .47),
        ControlStyle(), mapOf(Slot.AXIS to Binding(BindingType.GAMEPAD_AXIS, "RIGHT_STICK"))),
    xboxButton("L3", .18, .90),
    xboxButton("R3", .60, .93),
    xboxButton("Y", .84, .34, round = true),
    xboxButton("X", .74, .55, round = true),
    xboxButton("B", .94, .55, round = true),
    xboxButton("A", .84, .76, round = true),
))

internal val BUILT_IN_PROFILES = listOf(DEFAULT_PROFILE, XBOX_PROFILE, TILT_RACING_PROFILE) + HYBRID_TEMPLATES
internal val ControllerProfile.builtIn: Boolean get() = BUILT_IN_PROFILES.any { it.id == id }
