package com.compasslink3d

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal const val MAX_CONTROLS = 32
internal const val MAX_PROFILE_BYTES = 65536
internal const val DEFAULT_PROFILE_ID = "default"
internal const val MAX_LABEL = 48
internal val MODIFIERS = listOf("SHIFT", "CTRL", "ALT")
internal val MOUSE_BUTTONS = listOf("LEFT", "RIGHT", "MIDDLE")
internal val GAMEPAD_BUTTONS = listOf(
    "A", "B", "X", "Y", "DPAD_UP", "DPAD_DOWN", "DPAD_LEFT", "DPAD_RIGHT",
    "LB", "RB", "START", "BACK", "L3", "R3",
)
internal val GAMEPAD_STICKS = listOf("LEFT_STICK", "RIGHT_STICK")
internal val GAMEPAD_TRIGGERS = listOf("LEFT_TRIGGER", "RIGHT_TRIGGER")
internal val LEGACY_GAMEPAD_AXES = GAMEPAD_STICKS + GAMEPAD_TRIGGERS
private val idPattern = Regex("[a-zA-Z0-9_-]{1,48}")
internal fun validProfileId(value: String) = idPattern.matches(value)
private val colorPattern = Regex("#[0-9a-fA-F]{6}")

internal enum class Slot(val wire: String) {
    PRESS("press"), UP("up"), DOWN("down"), LEFT("left"), RIGHT("right"),
    POINTER("pointer"), TAP("tap"), WHEEL("wheel"), AXIS("axis");
}

private val DIRECTION_SLOTS = listOf(Slot.UP, Slot.DOWN, Slot.LEFT, Slot.RIGHT)

internal enum class ControlType(val title: String, val slots: List<Slot>) {
    BUTTON("Button", listOf(Slot.PRESS)),
    DPAD("D-pad", DIRECTION_SLOTS),
    JOYSTICK("Joystick", DIRECTION_SLOTS + Slot.AXIS),
    TRIGGER("Trigger", listOf(Slot.PRESS, Slot.AXIS)),
    SLIDER("Slider", listOf(Slot.PRESS, Slot.WHEEL, Slot.AXIS)),
    TOUCHPAD("Touchpad", listOf(Slot.POINTER, Slot.TAP, Slot.AXIS)),
    TOUCH_LOOK("Full-screen touch-look", listOf(Slot.POINTER)),
    TILT("Tilt", listOf(Slot.LEFT, Slot.RIGHT)),
    PROFILE_SWITCH("Switch profile", emptyList()),
    ;

    val axisTargets get() = when (this) {
        JOYSTICK -> GAMEPAD_STICKS
        TOUCHPAD -> listOf("RIGHT_STICK")
        TRIGGER, SLIDER -> GAMEPAD_TRIGGERS
        else -> emptyList()
    }
}

internal enum class ControlShape { CIRCLE, ROUNDED_RECTANGLE, RECTANGLE }
internal enum class ProfileOrientation { LANDSCAPE, PORTRAIT }
internal enum class BindingType { KEYBOARD, MOUSE_BUTTON, POINTER, WHEEL, GAMEPAD, GAMEPAD_AXIS }
internal enum class OutputMode(val wire: String, val title: String) {
    KEYBOARD("keyboard", "Keyboard / Mouse"),
    GAMEPAD("gamepad", "Virtual Gamepad"),
    HYBRID("hybrid", "Hybrid controller"),
}

internal data class Binding(
    val type: BindingType,
    val target: String = "",
    val modifiers: List<String> = emptyList(),
) {
    val description: String get() = when (type) {
        BindingType.KEYBOARD -> (modifiers + target).joinToString(" + ")
        BindingType.MOUSE_BUTTON -> "Mouse ${target.lowercase()}"
        BindingType.POINTER -> "Mouse movement"
        BindingType.WHEEL -> "Mouse wheel"
        BindingType.GAMEPAD -> if (target in LEGACY_GAMEPAD_AXES) "$target · legacy slot" else canonicalTarget
        BindingType.GAMEPAD_AXIS -> target.replace('_', ' ')
    }

    private val canonicalTarget get() = if (target == "SELECT") "BACK" else target

    fun supports(mode: OutputMode): Boolean = when (type) {
        BindingType.GAMEPAD -> mode != OutputMode.KEYBOARD && canonicalTarget in GAMEPAD_BUTTONS
        BindingType.GAMEPAD_AXIS -> mode != OutputMode.KEYBOARD
        else -> mode != OutputMode.GAMEPAD
    }

    fun encode() = JSONObject().put("type", type.name).apply {
        when (type) {
            BindingType.KEYBOARD -> put("key", target).put("modifiers", JSONArray(modifiers))
            BindingType.MOUSE_BUTTON -> put("button", target)
            BindingType.GAMEPAD, BindingType.GAMEPAD_AXIS -> put("target", canonicalTarget)
            else -> Unit
        }
    }
}

internal data class ControlGeometry(
    val x: Double = .5,
    val y: Double = .5,
    val width: Double = .22,
    val height: Double = .4,
    val rotation: Double = 0.0,
)

internal data class ControlStyle(
    val background: String = "#19191D",
    val border: String = "#FF3B4E",
    val text: String = "#F5F5F7",
    val pressed: String = "#A9162C",
    val opacity: Double = 1.0,
    val shape: ControlShape = ControlShape.ROUNDED_RECTANGLE,
    val cornerRadius: Double = .18,
    val visible: Boolean = true,
)

internal data class ControlSettings(
    val deadZone: Double = .2,
    val radius: Double = .74,
    val threshold: Double = .48,
    val hysteresis: Double = .08,
    val minimum: Double = 0.0,
    val maximum: Double = 100.0,
    val rangeStart: Double = .7,
    val rangeEnd: Double = 1.0,
    val vertical: Boolean = false,
    val sensitivity: Double = 1.0,
    val invertX: Boolean = false,
    val invertY: Boolean = false,
    val tapToClick: Boolean = true,
)

internal data class LayoutControl(
    val id: String,
    val type: ControlType,
    val label: String,
    val geometry: ControlGeometry,
    val style: ControlStyle,
    val bindings: Map<Slot, Binding>,
    val settings: ControlSettings = ControlSettings(),
) {
    val round: Boolean get() = type == ControlType.JOYSTICK || style.shape == ControlShape.CIRCLE
    val displayName: String get() = label.ifBlank { type.title }

    // The layout-only background layer uses the existing pointer-only touchpad wire mapping.
    fun mapping(mode: OutputMode) = JSONObject().put("id", id)
        .put("type", if (type == ControlType.TOUCH_LOOK) ControlType.TOUCHPAD.name else type.name)
        .put("label", displayName).put("bindings", bindingsJson(mode))

    fun bindingsJson(mode: OutputMode? = null) = JSONObject().apply {
        for ((slot, binding) in bindings.toSortedMap()) {
            if (mode == null || binding.supports(mode)) put(slot.wire, binding.encode())
        }
    }
}

internal data class ControllerProfile(
    val id: String,
    val name: String,
    val createdAt: Long,
    val modifiedAt: Long,
    val orientation: ProfileOrientation = ProfileOrientation.LANDSCAPE,
    val controls: List<LayoutControl> = emptyList(),
    val motion: MotionSettings? = null,
    val hybrid: HybridSettings? = null,
) {
    val requiresHybridTouchLook: Boolean
        get() = controls.any { it.type == ControlType.TOUCH_LOOK && it.style.visible } &&
            (controls.any { control -> control.style.visible && control.bindings.values.any { it.supports(OutputMode.GAMEPAD) } } ||
                motionsFor(OutputMode.GAMEPAD).isNotEmpty())

    fun descriptor(mode: OutputMode = OutputMode.KEYBOARD) = JSONObject().put("id", id).put("name", name)
        .put("controls", JSONArray(controls.filter { it.style.visible }.map { it.mapping(mode) }))
        .apply { motionFor(mode)?.let { put("motion", it.descriptor(motionId())) } }

    fun motionFor(mode: OutputMode) = motion?.takeIf { it.enabled && it.mapping.mode == mode }

    fun motionsFor(mode: OutputMode) = listOfNotNull(motion, hybrid?.secondaryMotion)
        .filter { it.enabled && (mode == OutputMode.HYBRID || it.mapping.mode == mode) }

    fun hybridDescriptor(mode: OutputMode, layoutOrientation: ProfileOrientation = orientation): JSONObject {
        val visible = controls.filter { it.style.visible }
        val motions = motionsFor(mode)
        val gyro = motions.firstOrNull { it.source == MotionSource.GYROSCOPE }
        val source = hybrid?.gyroControl
        val switches = hybrid?.quickSwitch.orEmpty().filterKeys { id -> visible.any { it.id == id } }
        return JSONObject().put("id", id).put("name", name).put("outputMode", mode.wire)
            .put("controls", JSONArray(visible.map { it.mapping(mode) }))
            .put("orientationPolicy", (hybrid?.orientation ?: inferredPolicy(layoutOrientation)).name)
            .put("haptics", (hybrid?.haptics ?: TouchHaptics.OFF).name)
            .put("motions", JSONArray(motions.mapIndexed { index, settings -> settings.descriptor(motionId(index)) }))
            .put("quickSwitch", switchReferences(switches))
            .put("gyroActivation", if (gyro == null) JSONObject.NULL else JSONObject()
                .put("source", if (source == null) "DEDICATED" else "CONTROL")
                .put("behavior", gyro.gyroEnable.name).apply {
                    source?.let { put("control", it.control).put("slot", it.slot.wire) }
                })
    }

    fun motionId(index: Int = 0): String {
        val ids = controls.mapTo(HashSet()) { it.id }
        return (1..MAX_CONTROLS + 2).asSequence().map { "motion-input-$it" }.filter { it !in ids }.elementAt(index)
    }

    fun duplicate(newName: String, time: Long = System.currentTimeMillis()) =
        copy(id = UUID.randomUUID().toString(), name = newName, createdAt = time, modifiedAt = time)
}

internal fun newControl(type: ControlType): LayoutControl {
    val keys = when (type) {
        ControlType.DPAD, ControlType.JOYSTICK -> mapOf(
            Slot.UP to Binding(BindingType.KEYBOARD, "W"), Slot.DOWN to Binding(BindingType.KEYBOARD, "S"),
            Slot.LEFT to Binding(BindingType.KEYBOARD, "A"), Slot.RIGHT to Binding(BindingType.KEYBOARD, "D"),
        )
        ControlType.TOUCHPAD -> mapOf(Slot.POINTER to Binding(BindingType.POINTER), Slot.TAP to Binding(BindingType.MOUSE_BUTTON, "LEFT"))
        ControlType.TOUCH_LOOK -> mapOf(Slot.POINTER to Binding(BindingType.POINTER))
        ControlType.TILT -> TILT_KEY_BINDINGS
        ControlType.SLIDER -> mapOf(Slot.WHEEL to Binding(BindingType.WHEEL))
        ControlType.PROFILE_SWITCH -> emptyMap()
        else -> mapOf(Slot.PRESS to Binding(BindingType.KEYBOARD, "SPACE"))
    }
    val geometry = when (type) {
        ControlType.DPAD -> ControlGeometry(width = .20, height = .48)
        ControlType.JOYSTICK -> ControlGeometry(width = .24, height = .58)
        ControlType.SLIDER -> ControlGeometry(width = .24, height = .18)
        ControlType.TOUCHPAD -> ControlGeometry(width = .28, height = .47)
        ControlType.TOUCH_LOOK -> ControlGeometry(width = 1.0, height = 1.0)
        else -> ControlGeometry(width = .14, height = .30)
    }
    val style = if (type == ControlType.JOYSTICK) ControlStyle(shape = ControlShape.CIRCLE) else ControlStyle()
    val settings = if (type == ControlType.TOUCH_LOOK) ControlSettings(sensitivity = .5, tapToClick = false) else ControlSettings()
    return LayoutControl(UUID.randomUUID().toString(), type, type.title, geometry, style, keys, settings)
}

internal val DEFAULT_PROFILE = ControllerProfile(
    DEFAULT_PROFILE_ID, "Default Controller", 0, 0, controls = listOf(
        presetButton("DPAD_UP", "UP", "W", .25, .17),
        presetButton("DPAD_LEFT", "LEFT", "A", .14, .50),
        presetButton("DPAD_RIGHT", "RIGHT", "D", .36, .50),
        presetButton("DPAD_DOWN", "DOWN", "S", .25, .83),
        presetButton("ACTION_B", "B", "ESCAPE", .67, .60, true),
        presetButton("ACTION_A", "A", "SPACE", .84, .40, true),
    ),
)

private fun presetButton(id: String, label: String, key: String, x: Double, y: Double, round: Boolean = false) =
    LayoutControl(id, ControlType.BUTTON, label,
        ControlGeometry(x, y, if (round) .15 else .10, if (round) .46 else .29),
        ControlStyle(shape = if (round) ControlShape.CIRCLE else ControlShape.ROUNDED_RECTANGLE),
        mapOf(Slot.PRESS to Binding(BindingType.KEYBOARD, key)))

internal class ProfileCodec(private val keys: Set<String>) {
    fun encode(profile: ControllerProfile): JSONObject = JSONObject()
        .put("schemaVersion", if (profile.hybrid != null) 5 else if (profile.motion == null) 3 else 4)
        .put("profileId", profile.id).put("name", profile.name)
        .put("createdAt", profile.createdAt).put("modifiedAt", profile.modifiedAt)
        .put("orientation", profile.orientation.name.lowercase())
        .put("controls", JSONArray(profile.controls.map { control ->
            val geometry = control.geometry
            val style = control.style
            val options = control.settings
            JSONObject().put("id", control.id).put("type", control.type.name).put("label", control.label)
                .put("geometry", JSONObject().put("x", geometry.x).put("y", geometry.y)
                    .put("width", geometry.width).put("height", geometry.height).put("rotation", geometry.rotation))
                .put("style", JSONObject().put("background", style.background).put("border", style.border)
                    .put("text", style.text).put("pressed", style.pressed).put("opacity", style.opacity)
                    .put("shape", style.shape.name).put("cornerRadius", style.cornerRadius).put("visible", style.visible))
                .put("settings", JSONObject().put("deadZone", options.deadZone).put("radius", options.radius)
                    .put("threshold", options.threshold).put("hysteresis", options.hysteresis)
                    .put("minimum", options.minimum).put("maximum", options.maximum)
                    .put("rangeStart", options.rangeStart).put("rangeEnd", options.rangeEnd).put("vertical", options.vertical)
                    .put("sensitivity", options.sensitivity).put("invertX", options.invertX).put("invertY", options.invertY)
                    .put("tapToClick", options.tapToClick))
                .put("bindings", control.bindingsJson())
        }))
        .apply {
            profile.motion?.let { put("motion", it.encode()) }
            profile.hybrid?.let { put("hybrid", it.encode()) }
        }

    fun decode(bytes: ByteArray): ControllerProfile {
        require(bytes.size <= MAX_PROFILE_BYTES) { "Profile exceeds 64 KiB" }
        return decode(JSONObject(bytes.toString(Charsets.UTF_8)))
    }

    fun decode(data: JSONObject): ControllerProfile {
        val schema = data.get("schemaVersion")
        require(schema in listOf(2, 3, 4, 5)) { "Unsupported profile schema" }
        require(schema == 4 || schema == 5 || !data.has("motion")) { "Motion settings require profile schema 4" }
        require((schema == 5) == data.has("hybrid") && (schema != 4 || data.has("motion"))) { "Invalid profile settings for this schema" }
        val items = data.getJSONArray("controls")
        require(items.length() <= MAX_CONTROLS) { "A profile can contain up to $MAX_CONTROLS controls" }
        val controls = List(items.length()) { decodeControl(items.getJSONObject(it), schema == 2) }
        require(controls.map { it.id }.toSet().size == controls.size) { "Duplicate control IDs" }
        val touchLook = controls.filter { it.type == ControlType.TOUCH_LOOK }
        require(touchLook.size <= 1 && (touchLook.isEmpty() || schema == 5)) { "Use one full-screen touch-look layer in a schema 5 profile" }
        require(touchLook.all { it.bindings == mapOf(Slot.POINTER to Binding(BindingType.POINTER)) && !it.settings.tapToClick }) {
            "Full-screen touch-look supports movement only"
        }
        val createdAt = data.timestamp("createdAt")
        val modifiedAt = data.timestamp("modifiedAt")
        require(modifiedAt >= createdAt) { "Invalid profile timestamps" }
        val orientation = when (data.get("orientation")) {
            "landscape" -> ProfileOrientation.LANDSCAPE
            "portrait" -> ProfileOrientation.PORTRAIT
            else -> throw IllegalArgumentException("Unsupported orientation")
        }
        return ControllerProfile(
            data.id("profileId"), data.label("name", allowEmpty = false), createdAt, modifiedAt,
            orientation, controls, if (data.has("motion")) MotionSettings.decode(data.getJSONObject("motion"), keys) else null,
            if (schema == 5) HybridSettings.decode(data.getJSONObject("hybrid"), keys) else null,
        ).also(ControllerProfile::validateHybrid)
    }

    private fun decodeControl(data: JSONObject, legacy: Boolean): LayoutControl {
        val type = ControlType.valueOf(data.get("type") as? String ?: "")
        require(!legacy || type != ControlType.TILT) { "Tilt requires profile schema 3" }
        val id = data.id("id")
        val geometry = data.getJSONObject("geometry")
        val style = data.getJSONObject("style")
        val settings = data.getJSONObject("settings")
        val bindings = data.getJSONObject("bindings")
        val parsedBindings = bindings.keys().asSequence().associate { name ->
            val slot = type.slots.firstOrNull { it.wire == name && (!legacy || it != Slot.AXIS) }
                ?: throw IllegalArgumentException("Unknown binding slot")
            slot to decodeBinding(bindings.getJSONObject(name), slot, type.axisTargets)
        }.toMutableMap()
        if (legacy) {
            val axes = parsedBindings.values.filter { it.type == BindingType.GAMEPAD && it.target in type.axisTargets }
                .map { it.target }.distinct()
            if (axes.size == 1) parsedBindings[Slot.AXIS] = Binding(BindingType.GAMEPAD_AXIS, axes.single())
        }
        val min = settings.number("minimum", -100000.0, 100000.0)
        val max = settings.number("maximum", -100000.0, 100000.0)
        val rangeStart = settings.number("rangeStart", 0.0, 1.0)
        val rangeEnd = settings.number("rangeEnd", 0.0, 1.0)
        require(min < max && rangeStart <= rangeEnd) { "Invalid slider range" }
        return LayoutControl(id, type, data.label("label"), ControlGeometry(
            geometry.number("x", 0.0, 1.0), geometry.number("y", 0.0, 1.0),
            geometry.number("width", .01, 1.0), geometry.number("height", .01, 1.0),
            geometry.number("rotation", -180.0, 180.0),
        ), ControlStyle(
            style.color("background"), style.color("border"), style.color("text"), style.color("pressed"),
            style.number("opacity", .15, 1.0), ControlShape.valueOf(style.get("shape") as? String ?: ""),
            style.number("cornerRadius", 0.0, .5), style.flag("visible"),
        ), parsedBindings, ControlSettings(
            settings.number("deadZone", 0.0, .85), settings.number("radius", .2, 1.0),
            settings.number("threshold", .05, .98), settings.number("hysteresis", 0.0, .3),
            min, max, rangeStart, rangeEnd, settings.flag("vertical"),
            settings.number("sensitivity", if (type == ControlType.TOUCH_LOOK || type == ControlType.TOUCHPAD) .05 else .1, 8.0), settings.flag("invertX"),
            settings.flag("invertY"), settings.flag("tapToClick"),
        ))
    }

    private fun decodeBinding(data: JSONObject, slot: Slot, axisTargets: List<String>): Binding {
        val type = BindingType.valueOf(data.get("type") as? String ?: "")
        val allowed = when (slot) {
            Slot.POINTER -> setOf(BindingType.POINTER)
            Slot.WHEEL -> setOf(BindingType.WHEEL)
            Slot.TAP -> setOf(BindingType.MOUSE_BUTTON, BindingType.GAMEPAD)
            Slot.AXIS -> setOf(BindingType.GAMEPAD_AXIS)
            else -> setOf(BindingType.KEYBOARD, BindingType.MOUSE_BUTTON, BindingType.GAMEPAD)
        }
        require(type in allowed) { "Unsupported binding for ${slot.wire}" }
        return when (type) {
            BindingType.KEYBOARD -> {
                val key = data.get("key") as? String
                val list = if (data.has("modifiers")) data.getJSONArray("modifiers") else JSONArray()
                require(key in keys && list.length() <= 3) { "Unsupported keyboard binding" }
                val modifiers = List(list.length()) { list.get(it) }
                require(modifiers.toSet().size == modifiers.size && modifiers.all { it != key && it in MODIFIERS }) {
                    "Invalid keyboard modifiers"
                }
                Binding(type, requireNotNull(key), MODIFIERS.filter { it in modifiers })
            }
            BindingType.MOUSE_BUTTON -> Binding(type, data.choice("button", MOUSE_BUTTONS))
            BindingType.GAMEPAD -> Binding(type, data.choice("target", GAMEPAD_BUTTONS + LEGACY_GAMEPAD_AXES + "SELECT")
                .let { if (it == "SELECT") "BACK" else it })
            BindingType.GAMEPAD_AXIS -> Binding(type, data.choice("target", axisTargets))
            else -> Binding(type)
        }
    }

    private fun JSONObject.choice(key: String, allowed: List<String>): String {
        val value = get(key) as? String ?: ""
        require(value in allowed) { "Unsupported $key" }
        return value
    }

    private fun JSONObject.id(key: String): String {
        val value = get(key) as? String ?: ""
        require(idPattern.matches(value)) { "Invalid $key" }
        return value
    }

    private fun JSONObject.color(key: String): String {
        val value = get(key) as? String ?: ""
        require(colorPattern.matches(value)) { "Invalid $key color" }
        return value
    }

    private fun JSONObject.label(key: String, allowEmpty: Boolean = true): String {
        val value = get(key) as? String ?: throw IllegalArgumentException("Invalid $key")
        require(value.length <= MAX_LABEL && value.none { it.isISOControl() } && (allowEmpty || value.isNotBlank())) { "Invalid $key" }
        return value
    }

    private fun JSONObject.number(key: String, low: Double, high: Double): Double {
        val value = (get(key) as? Number)?.toDouble()
        require(value != null && value.isFinite() && value in low..high) { "Invalid $key" }
        return value
    }

    private fun JSONObject.timestamp(key: String): Long {
        val value = get(key)
        require((value is Int || value is Long) && (value as Number).toLong() >= 0) { "Invalid $key" }
        return (value as Number).toLong()
    }

    private fun JSONObject.flag(key: String) = get(key) as? Boolean ?: throw IllegalArgumentException("Invalid $key")
}
