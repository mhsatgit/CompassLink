package com.compasslink3d

import android.app.AlertDialog
import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ScrollView
import java.util.UUID

internal class ControllerEditor(
    private val context: Context,
    initial: ControllerProfile,
    private val keys: List<String>,
    private val save: (ControllerProfile) -> Unit,
    private val play: (ControllerProfile) -> Unit,
    private val profiles: () -> Unit,
    private val changedOrientation: (ProfileOrientation?) -> Unit,
    private val window: ControllerWindow,
    unsaved: Boolean = false,
    private val availableProfiles: () -> List<ControllerProfile> = { emptyList() },
    savedProfile: ControllerProfile? = null,
) {
    private val history = EditorHistory(initial)
    private var saved = savedProfile ?: initial.takeUnless { unsaved }
    val isClean get() = saved == history.current
    val draft get() = history.current
    val selection get() = selected
    private var selected: String? = null
    private val canvas = ControllerEditorCanvas(context, ::select, ::drag)
    private val title = context.heading("")
    private val detail = context.hint("")
    private val historyButton = context.iconButton("Undo and redo", R.drawable.ic_controller_history) { showHistory() }
    private val properties = context.iconButton("Control properties", R.drawable.ic_controller_settings) { selectedControl()?.let(::properties) }
    private val bind = context.builderButton("Bindings", icon = R.drawable.ic_controller_signal) {
        selectedControl()?.let { if (it.type == ControlType.PROFILE_SWITCH) switchTarget(it) else bindings(it) }
    }
    private val arrange = context.iconButton("Arrange control", R.drawable.ic_controller_layers) { selectedControl()?.let(::arrange) }
    private val rename = context.builderButton("Rename", icon = R.drawable.ic_controller_edit) { selectedControl()?.let(::rename) }
    private val add = context.builderButton("Add", highlight = true, icon = R.drawable.ic_controller_add) { add() }
    private val layout = context.iconButton("Layout settings", R.drawable.ic_controller_phone) { layoutProperties() }
    private var renderedOrientation = initial.lockedOrientation
    var canvasOnly = true
        private set

    private val tools = ScrollView(context).apply {
        isFillViewport = true
        isClickable = true
        window.insetContent(this, 10)
        addView(context.controlPanel().apply {
            addView(context.controllerHeader(
                title, "Done", { leave(profiles) },
                historyButton,
                context.iconButton("Save", R.drawable.ic_controller_save) { saveDraft() },
                context.iconButton("Play", R.drawable.ic_controller_play) { leave { play(history.current) } },
            ))
            addView(detail)
            addView(context.actionRow(add, properties, arrange, layout))
            addView(context.actionRow(
                bind,
                rename,
                context.builderButton("Resume editing", icon = R.drawable.ic_controller_back) { showCanvas(true) },
            ))
        })
        visibility = View.GONE
    }

    private val pencil = context.iconButton("Open editor tools", R.drawable.ic_controller_edit) { showCanvas(false) }

    val view: View = object : FrameLayout(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = MeasureSpec.getSize(widthMeasureSpec)
            val h = MeasureSpec.getSize(heightMeasureSpec)
            val wide = w >= context.px(620) && w > h
            val panelWidth = if (wide) context.px(340) else -1
            if (tools.layoutParams.width != panelWidth) {
                tools.layoutParams = LayoutParams(panelWidth, if (wide) -1 else -2,
                    if (wide) Gravity.END else Gravity.BOTTOM)
            }
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }.apply {
        setBackgroundColor(CANVAS_BACKGROUND)
        addView(FrameLayout(context).apply {
            window.insetContent(this, 0, protectSystemBars = false)
            addView(canvas, FrameLayout.LayoutParams(-1, -1))
        }, FrameLayout.LayoutParams(-1, -1))
        addView(FrameLayout(context).apply {
            window.insetContent(this, 8, protectBottom = false)
            addView(pencil, FrameLayout.LayoutParams(context.px(48), context.px(48), Gravity.TOP or Gravity.END))
        }, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        addView(tools, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
    }

    init {
        refresh()
        showCanvas(initial.controls.isNotEmpty())
    }

    fun restore(id: String?, canvas: Boolean, clean: Boolean) {
        if (clean) saved = history.current
        select(id)
        showCanvas(canvas)
    }

    fun requestBack() {
        if (canvasOnly) showCanvas(false) else leave(profiles)
    }

    fun cancelDrag() = canvas.cancelDrag()

    private fun showCanvas(value: Boolean) {
        cancelDrag()
        canvasOnly = value
        pencil.visibility = if (value) View.VISIBLE else View.GONE
        tools.visibility = if (value) View.GONE else View.VISIBLE
        window.immersive(value)
        view.requestApplyInsets()
    }

    private fun saveDraft() = context.reportErrors {
        save(history.current)
        saved = history.current
        refresh()
    }

    private fun leave(next: () -> Unit) {
        if (saved == history.current) {
            next()
            return
        }
        val dialog = AlertDialog.Builder(context).setTitle("Unsaved controller")
            .setMessage("Save the changes to this controller before leaving?")
            .setNegativeButton("Keep editing", null)
            .setNeutralButton("Discard") { _, _ ->
                val previous = saved
                if (previous != null) history.commit(previous)
                if (previous == null) profiles() else next()
            }
            .setPositiveButton("Save", null).create()
        dialog.escapeDismisses()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (saveDraft()) {
                    dialog.dismiss()
                    next()
                }
            }
        }
        dialog.show()
    }

    private fun selectedControl() = history.current.controls.firstOrNull { it.id == selected }

    private fun select(id: String?) {
        selected = id
        canvas.selectControl(id)
        updateDetail()
    }

    private fun updateDetail() {
        val selected = selectedControl()
        val name = selected?.let { "Selected  ·  ${it.displayName}${if (it.style.visible) "" else "  ·  Hidden in Play"}" }
            ?: "Tap a control to select it, or use Add."
        detail.text = name
        properties.isEnabled = selected != null
        bind.isEnabled = selected != null && selected.type != ControlType.TOUCH_LOOK
        arrange.isEnabled = selected != null
        rename.isEnabled = selected != null
    }

    private fun refresh() {
        val name = history.current.name
        title.text = if (saved == history.current) name else context.getString(R.string.unsaved_profile_title, name)
        if (selectedControl() == null) selected = null
        canvas.render(history.current.controls)
        canvas.selectControl(selected)
        historyButton.isEnabled = history.canUndo || history.canRedo
        add.isEnabled = history.current.controls.size < MAX_CONTROLS
        updateDetail()
        val orientation = history.current.lockedOrientation
        if (renderedOrientation != orientation) {
            renderedOrientation = orientation
            changedOrientation(orientation)
        }
    }

    private fun showHistory() {
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        if (history.canUndo) actions.add("Undo" to { history.undo() })
        if (history.canRedo) actions.add("Redo" to { history.redo() })
        context.choose("Edit history", actions.map { it.first }) {
            actions[it].second()
            refresh()
        }
    }

    private fun commit(profile: ControllerProfile) {
        val options = profile.hybrid
        val gyro = listOfNotNull(profile.motion, options?.secondaryMotion).firstOrNull { it.source == MotionSource.GYROSCOPE }
        val source = options?.gyroControl
        val normalized = if (options != null && source != null &&
            (source !in profile.activationSlots() || gyro == null || gyro.gyroEnable == GyroEnable.ALWAYS || !options.hybrid)) {
            android.widget.Toast.makeText(context, "Gyro now uses its dedicated activation control.", android.widget.Toast.LENGTH_LONG).show()
            profile.copy(hybrid = options.copy(gyroControl = null))
        } else profile
        history.commit(normalized)
        refresh()
    }

    private fun replacing(control: LayoutControl) = history.current.copy(
        controls = history.current.controls.map { if (it.id == control.id) control else it },
    )

    private fun drag(control: LayoutControl, finished: Boolean) {
        val next = replacing(control)
        if (finished) commit(next) else canvas.render(next.controls)
    }

    private fun append(control: LayoutControl) {
        if (history.current.controls.size == MAX_CONTROLS) return
        val placed = canvas.clamp(control)
        selected = placed.id
        commit(history.current.copy(controls = history.current.controls + placed))
    }

    private fun add() {
        val types = ControlType.entries.filter { type ->
            type != ControlType.TOUCH_LOOK || history.current.controls.none { it.type == type }
        }
        context.controlPicker(types) { type ->
            val control = newControl(type)
            if (control.type == ControlType.PROFILE_SWITCH) switchTarget(control, add = true)
            else {
                insertControl(control)
                showCanvas(true)
            }
        }
    }

    private fun insertControl(control: LayoutControl, options: HybridSettings? = history.current.hybrid) {
        if (history.current.controls.size >= MAX_CONTROLS) return
        if (control.type == ControlType.TOUCH_LOOK) {
            val profile = history.current
            val settings = options ?: HybridSettings(hybrid = false, orientation = profile.inferredPolicy())
            val next = profile.copy(controls = listOf(control) + profile.controls, hybrid = settings)
            selected = control.id
            commit(next)
            if (next.requiresHybridTouchLook && !settings.hybrid) {
                android.widget.Toast.makeText(context, "Touch-look and Xbox controls will play together.", android.widget.Toast.LENGTH_SHORT).show()
            }
            return
        }
        val positions = listOf(.48 to .52, .22 to .30, .78 to .31, .20 to .73, .80 to .74,
            .50 to .22, .50 to .79, .14 to .48, .86 to .49)
        val placed = positions.map { (x, y) -> control.copy(geometry = control.geometry.copy(x = x, y = y)) }
            .minBy(canvas::overlap)
        selected = placed.id
        commit(history.current.copy(controls = history.current.controls + canvas.clamp(placed), hybrid = options))
    }

    private fun arrange(control: LayoutControl) {
        if (control.type == ControlType.TOUCH_LOOK) {
            context.choose(control.displayName, listOf("Delete control", "Select a control")) {
                if (it == 0) commit(history.current.copy(controls = history.current.controls.filterNot { item -> item.id == control.id }))
                else chooseControl()
            }
            return
        }
        val actions = listOf("Bring forward", "Send backward", "Duplicate control", "Delete control",
            if (canvas.uniformResize) "Allow independent resize" else "Use uniform resize",
            "Select a control")
        context.choose(control.displayName, actions) { choice ->
            val controls = history.current.controls.toMutableList()
            val index = controls.indexOfFirst { it.id == control.id }
            when (choice) {
                0, 1 -> {
                    val first = if (controls.first().type == ControlType.TOUCH_LOOK) 1 else 0
                    val next = (index + if (choice == 0) 1 else -1).coerceIn(first, controls.lastIndex)
                    controls.add(next, controls.removeAt(index))
                    commit(history.current.copy(controls = controls))
                }
                2 -> {
                    val copy = control.copy(id = UUID.randomUUID().toString(), geometry = control.geometry.copy(
                        x = control.geometry.x + .035, y = control.geometry.y + .035,
                    ))
                    if (control.type == ControlType.PROFILE_SWITCH) switchTarget(copy, add = true)
                    else append(copy)
                }
                3 -> {
                    controls.removeAt(index)
                    val options = history.current.hybrid
                    commit(history.current.copy(controls = controls,
                        hybrid = options?.copy(quickSwitch = options.quickSwitch - control.id)))
                }
                4 -> canvas.uniformResize = !canvas.uniformResize
                5 -> chooseControl()
            }
        }
    }

    private fun chooseControl() {
        val controls = history.current.controls
        if (controls.isEmpty()) return
        context.choose("Select control", controls.map { it.displayName + if (it.style.visible) "" else " (hidden)" }) {
            select(controls[it].id)
        }
    }

    private fun layoutProperties() {
        val profile = history.current
        val options = profile.hybrid ?: HybridSettings(hybrid = false, orientation = profile.inferredPolicy())
        val items = mutableListOf("Profile options", "Select a control", "Motion settings")
        if (options.hybrid && profile.motion != null) items.add("Secondary motion")
        if (options.hybrid && listOfNotNull(profile.motion, options.secondaryMotion).any {
                it.source == MotionSource.GYROSCOPE && it.gyroEnable != GyroEnable.ALWAYS
            }) items.add("Gyro activation")
        context.choose("Layout", items) { choice ->
            when (items[choice]) {
                "Select a control" -> chooseControl()
                "Motion settings" -> context.configureMotion(profile.motion ?: MotionSettings(), keys, ::layoutProperties) {
                    require(it.source != options.secondaryMotion?.source) { "Use a different sensor from secondary motion" }
                    commit(profile.copy(motion = it, hybrid = if (it.gyroEnable == GyroEnable.ALWAYS) options else profile.hybrid))
                }
                "Secondary motion" -> {
                    val alternative = if (profile.motion?.source == MotionSource.GYROSCOPE) MotionSource.ORIENTATION else MotionSource.GYROSCOPE
                    context.configureMotion(options.secondaryMotion ?: MotionSettings(source = alternative), keys, ::layoutProperties) {
                        require(it.source != profile.motion?.source) { "Use a different sensor from primary motion" }
                        commit(profile.copy(hybrid = options.copy(secondaryMotion = it.takeIf { settings -> settings.enabled })))
                    }
                }
                "Gyro activation" -> configureGyroSource(profile, options)
                else -> profileOptions(profile, options)
            }
        }
    }

    private fun profileOptions(profile: ControllerProfile, options: HybridSettings) {
        val form = ControllerForm(context, ::layoutProperties)
        val name = form.text("Profile name", profile.name)
        val orientation = form.choice("Orientation", OrientationPolicy.entries, options.orientation) { it.title }
        val haptics = form.choice("Touch haptics", TouchHaptics.entries, options.haptics) { it.name.lowercase().replaceFirstChar(Char::titlecase) }
        val hybrid = form.flag("Use keyboard, mouse and virtual gamepad together", options.hybrid)
        form.show("Profile options") {
            val title = name.value().trim()
            require(title.isNotBlank() && title.none { it.isISOControl() }) { "Enter a profile name" }
            require(hybrid.isChecked || options.secondaryMotion == null) { "Disable secondary motion before turning off hybrid output" }
            commit(profile.copy(name = title, orientation = orientation().locked ?: profile.orientation,
                hybrid = options.copy(hybrid = hybrid.isChecked, orientation = orientation(), haptics = haptics())))
        }
    }

    private fun configureGyroSource(profile: ControllerProfile, options: HybridSettings) {
        val sources = listOf(null) + profile.activationSlots()
        val names = sources.map { source ->
            if (source == null) "Dedicated gyro control"
            else "${profile.controls.first { it.id == source.control }.displayName} · ${source.slot.wire}"
        }
        context.choose("Gyro activation", names, onBack = ::layoutProperties) { choice ->
            commit(profile.copy(hybrid = options.copy(gyroControl = sources[choice])))
        }
    }

    private fun switchTarget(control: LayoutControl, add: Boolean = false) {
        val profile = history.current
        val targets = availableProfiles().filter { it.id != profile.id }
        if (targets.isEmpty()) return context.showError("No other profile is available")
        val options = profile.hybrid ?: HybridSettings(hybrid = false, orientation = profile.inferredPolicy())
        val current = options.quickSwitch[control.id]?.let { id -> targets.firstOrNull { it.id == id } }
        val title = current?.let { "Switch target · ${it.name}" } ?: "Switch to profile"
        context.choose(title, targets.map { it.name }, onBack = { if (add) this.add() else properties(control) }) { choice ->
            val target = targets[choice]
            val next = options.copy(quickSwitch = options.quickSwitch + (control.id to target.id))
            if (add) {
                insertControl(control.copy(label = target.name), next)
                showCanvas(true)
            } else commit(profile.copy(hybrid = next))
        }
    }

    private fun properties(control: LayoutControl) {
        if (control.type == ControlType.TOUCH_LOOK) {
            behavior(control)
            return
        }
        val switch = control.type == ControlType.PROFILE_SWITCH
        val options = listOf("Position and size", "Appearance", if (switch) "Switch target" else "Input bindings") +
            if (switch || control.type in listOf(ControlType.BUTTON, ControlType.DPAD)) emptyList() else listOf("Control behavior")
        context.choose(control.displayName, options) { choice ->
            when (choice) {
                0 -> geometry(control)
                1 -> appearance(control)
                2 -> if (switch) switchTarget(control) else bindings(control)
                3 -> behavior(control)
            }
        }
    }

    private fun rename(control: LayoutControl) {
        val form = ControllerForm(context)
        val label = form.text("Label", control.label)
        form.show("Rename control", "Rename") {
            commit(replacing(control.copy(label = readLabel(label.value()))))
        }
    }

    private fun readLabel(label: String): String {
        require(label.none { it.isISOControl() }) { "Use a single-line label" }
        return label
    }

    private fun geometry(control: LayoutControl) {
        val form = ControllerForm(context) { properties(control) }
        val g = control.geometry
        val x = form.number("Center X (0–1)", g.x)
        val y = form.number("Center Y (0–1)", g.y)
        val width = form.number("Width (0.01–1)", g.width)
        val height = form.number("Height (0.01–1)", g.height)
        val rotation = form.number("Rotation (degrees)", g.rotation)
        form.show("Position and size") {
            val geometry = ControlGeometry(x.readNumber(0.0, 1.0), y.readNumber(0.0, 1.0),
                width.readNumber(.01, 1.0), height.readNumber(.01, 1.0), wrapDegrees(rotation.readNumber(-360.0, 360.0)))
            commit(replacing(canvas.clamp(control.copy(geometry = geometry))))
        }
    }

    private fun appearance(control: LayoutControl) {
        val form = ControllerForm(context) { properties(control) }
        val style = control.style
        val label = form.text("Label", control.label)
        val shapes = if (control.type == ControlType.JOYSTICK) listOf(ControlShape.CIRCLE) else ControlShape.entries
        val shape = form.choice("Shape", shapes, style.shape.takeIf { it in shapes } ?: shapes.first()) {
            it.name.lowercase().replace('_', ' ').replaceFirstChar(Char::uppercase)
        }
        val background = form.color("Background color", style.background)
        val border = form.color("Border color", style.border)
        val text = form.color("Text color", style.text)
        val pressed = form.color("Pressed color", style.pressed)
        val opacity = form.number("Opacity (15–100%)", style.opacity * 100)
        val radius = form.number("Corner radius (0–0.5)", style.cornerRadius)
        val visible = form.flag("Visible in Play Mode", style.visible)
        form.show("Appearance") {
            val title = readLabel(label.value())
            val next = ControlStyle(background.readColor(), border.readColor(), text.readColor(), pressed.readColor(),
                opacity.readNumber(15.0, 100.0) / 100, shape(), radius.readNumber(0.0, .5), visible.isChecked)
            commit(replacing(canvas.clamp(control.copy(label = title, style = next))))
        }
    }

    private fun behavior(control: LayoutControl) {
        val form = ControllerForm(context) { if (control.type != ControlType.TOUCH_LOOK) properties(control) }
        val previous = control.settings
        val read: () -> ControlSettings = when (control.type) {
            ControlType.JOYSTICK -> {
                val deadZone = form.number("Dead zone (0–0.85)", previous.deadZone)
                val radius = form.number("Travel radius (0.2–1)", previous.radius)
                val threshold = form.number("Direction threshold (0.05–0.98)", previous.threshold)
                val hysteresis = form.number("Release hysteresis (0–0.3)", previous.hysteresis)
                val sensitivity = form.number("Gamepad sensitivity (0.1–8)", previous.sensitivity)
                val update: () -> ControlSettings = {
                    previous.copy(deadZone = deadZone.readNumber(0.0, .85), radius = radius.readNumber(.2, 1.0),
                        threshold = threshold.readNumber(.05, .98), hysteresis = hysteresis.readNumber(0.0, .3),
                        sensitivity = sensitivity.readNumber(.1, 8.0))
                }
                update
            }
            ControlType.TRIGGER -> {
                val threshold = form.number("Press threshold (0.05–0.98)", previous.threshold)
                val hysteresis = form.number("Release hysteresis (0–0.3)", previous.hysteresis)
                val update: () -> ControlSettings = {
                    previous.copy(threshold = threshold.readNumber(.05, .98), hysteresis = hysteresis.readNumber(0.0, .3))
                }
                update
            }
            ControlType.SLIDER -> {
                val minimum = form.number("Minimum value", previous.minimum)
                val maximum = form.number("Maximum value", previous.maximum)
                val rangeStart = form.number("Press from (% of travel)", previous.rangeStart * 100)
                val rangeEnd = form.number("Press through (% of travel)", previous.rangeEnd * 100)
                val hysteresis = form.number("Release hysteresis (0–0.3)", previous.hysteresis)
                val vertical = form.flag("Vertical slider", previous.vertical)
                val update: () -> ControlSettings = {
                    val min = minimum.readNumber(-100000.0, 100000.0)
                    val max = maximum.readNumber(-100000.0, 100000.0)
                    val start = rangeStart.readNumber(0.0, 100.0) / 100
                    val end = rangeEnd.readNumber(0.0, 100.0) / 100
                    require(min < max && start <= end) { "Check the slider value and press ranges" }
                    previous.copy(minimum = min, maximum = max, rangeStart = start, rangeEnd = end,
                        hysteresis = hysteresis.readNumber(0.0, .3), vertical = vertical.isChecked)
                }
                update
            }
            ControlType.TOUCHPAD, ControlType.TOUCH_LOOK -> {
                val sensitivity = form.mouseSensitivity(previous.sensitivity)
                val invertX = form.flag("Invert horizontal movement", previous.invertX)
                val invertY = form.flag("Invert vertical movement", previous.invertY)
                val tap = if (control.type == ControlType.TOUCHPAD) form.flag("Tap to click", previous.tapToClick) else null
                val update: () -> ControlSettings = {
                    previous.copy(sensitivity = sensitivity(),
                        invertX = invertX.isChecked, invertY = invertY.isChecked, tapToClick = tap?.isChecked == true)
                }
                update
            }
            else -> return
        }
        form.show("${control.type.title} behavior") { commit(replacing(control.copy(settings = read()))) }
    }

    private fun bindings(control: LayoutControl) {
        val slots = control.type.slots
        context.choose("Input bindings", slots.map {
            val binding = control.bindings[it]
            val mode = OutputMode.entries.firstOrNull { mode -> binding?.supports(mode) == true }
            val detail = listOfNotNull(binding?.description ?: "Unbound", mode?.title).joinToString("  ·  ")
            "${it.wire.replaceFirstChar(Char::uppercase)}  ·  $detail"
        }, onBack = { properties(control) }) { choice -> binding(control, slots[choice]) }
    }

    private fun binding(control: LayoutControl, slot: Slot) {
        fun set(binding: Binding?) {
            val next = if (binding == null) control.bindings - slot else control.bindings + (slot to binding)
            commit(replacing(control.copy(bindings = next)))
        }
        val options = when (slot) {
            Slot.POINTER -> listOf("Mouse movement")
            Slot.WHEEL -> listOf("Mouse wheel")
            Slot.AXIS -> listOf("Gamepad axis")
            Slot.TAP -> listOf("Mouse button", "Gamepad button")
            else -> listOf("Keyboard", "Mouse button", "Gamepad button")
        }
        val choices = listOf("Unbound") + options
        context.choose("Bind ${slot.wire}", choices, onBack = { bindings(control) }) {
            when (choices[it]) {
                "Unbound" -> set(null)
                "Mouse movement" -> set(Binding(BindingType.POINTER))
                "Mouse wheel" -> set(Binding(BindingType.WHEEL))
                "Mouse button" -> context.choose("Mouse button", MOUSE_BUTTONS, onBack = { binding(control, slot) }) { index ->
                    set(Binding(BindingType.MOUSE_BUTTON, MOUSE_BUTTONS[index]))
                }
                "Keyboard" -> chooseKeyboardBinding(control, slot, ::set)
                "Gamepad axis" -> context.choose("Virtual Gamepad axis", control.type.axisTargets.map { it.replace('_', ' ') }, onBack = { binding(control, slot) }) { index ->
                    set(Binding(BindingType.GAMEPAD_AXIS, control.type.axisTargets[index]))
                }
                "Gamepad button" -> context.choose("Virtual Gamepad button", GAMEPAD_BUTTONS, onBack = { binding(control, slot) }) { index ->
                    set(Binding(BindingType.GAMEPAD, GAMEPAD_BUTTONS[index]))
                }
            }
        }
    }

    private fun chooseKeyboardBinding(control: LayoutControl, slot: Slot, apply: (Binding) -> Unit) {
        context.choose("Keyboard key", keys, onBack = { binding(control, slot) }) { index ->
            val key = keys[index]
            val form = ControllerForm(context) { chooseKeyboardBinding(control, slot, apply) }
            val previous = control.bindings[slot]?.modifiers.orEmpty()
            val modifiers = MODIFIERS.filterNot { it == key }.associateWith { form.flag(it, it in previous) }
            form.show("Modifiers for $key") {
                apply(Binding(BindingType.KEYBOARD, key, modifiers.filterValues { it.isChecked }.keys.toList()))
            }
        }
    }
}
