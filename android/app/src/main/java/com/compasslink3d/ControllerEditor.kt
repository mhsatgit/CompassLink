package com.compasslink3d

import android.app.AlertDialog
import android.content.Context
import android.view.View
import android.widget.LinearLayout
import java.util.UUID

internal class ControllerEditor(
    private val context: Context,
    initial: ControllerProfile,
    private val keys: List<String>,
    private val save: (ControllerProfile) -> Unit,
    private val play: (ControllerProfile) -> Unit,
    private val profiles: () -> Unit,
    private val changedOrientation: (ProfileOrientation) -> Unit,
    unsaved: Boolean = false,
) {
    private val history = EditorHistory(initial)
    private var saved = initial.takeUnless { unsaved }
    private var selected: String? = null
    private val canvas = ControllerEditorCanvas(context, ::select, ::drag)
    private val title = context.heading("")
    private val detail = context.hint("")
    private val undo = context.builderButton("Undo") { history.undo(); refresh() }
    private val redo = context.builderButton("Redo") { history.redo(); refresh() }
    private val properties = context.builderButton("Properties") { selectedControl()?.let(::properties) }
    private val arrange = context.builderButton("Arrange") { selectedControl()?.let(::arrange) }
    private val add = context.builderButton("Add") { add() }
    private var renderedOrientation = initial.orientation

    val view: View = context.builderRoot().apply {
        addView(context.toolbar(
            title, add,
            context.builderButton("Save") { saveDraft() },
            context.builderButton("Play", highlight = true) { leave { play(history.current) } },
            properties, arrange, undo, redo,
            context.builderButton("Layout") { layoutProperties() },
            context.builderButton("Profiles") { requestBack() },
        ))
        addView(detail)
        addView(canvas, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    init { refresh() }

    fun requestBack() = leave(profiles)

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
        AlertDialog.Builder(context).setTitle("Unsaved controller")
            .setMessage("Save the changes to this controller before leaving?")
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Discard") { _, _ ->
                val previous = saved
                if (previous != null) history.commit(previous)
                if (previous == null) profiles() else next()
            }
            .setPositiveButton("Save") { _, _ -> if (saveDraft()) next() }
            .show()
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
        arrange.isEnabled = selected != null
    }

    private fun refresh() {
        title.text = "${history.current.name}${if (saved == history.current) "" else " *"}"
        if (selectedControl() == null) selected = null
        canvas.render(history.current.controls)
        canvas.selectControl(selected)
        undo.isEnabled = history.canUndo
        redo.isEnabled = history.canRedo
        add.isEnabled = history.current.controls.size < MAX_CONTROLS
        updateDetail()
        if (renderedOrientation != history.current.orientation) {
            renderedOrientation = history.current.orientation
            changedOrientation(renderedOrientation)
        }
    }

    private fun commit(profile: ControllerProfile) {
        history.commit(profile)
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
        val choices = context.builderRoot().apply { setPadding(context.px(20), context.px(8), context.px(20), 0) }
        val dialog = AlertDialog.Builder(context).setTitle("Add control").setView(choices)
            .setNegativeButton("Cancel", null).create()
        for (row in ControlType.entries.chunked(2)) {
            choices.addView(LinearLayout(context).apply {
                for (type in row) {
                    val button = context.builderButton(type.title) {
                        insertControl(type)
                        dialog.dismiss()
                    }
                    addView(button, LinearLayout.LayoutParams(0, -2, 1f).apply {
                        marginEnd = context.px(6)
                        bottomMargin = context.px(6)
                    })
                }
            })
        }
        dialog.show()
    }

    private fun insertControl(type: ControlType) {
        val control = newControl(type)
        val positions = listOf(.48 to .52, .22 to .30, .78 to .31, .20 to .73, .80 to .74,
            .50 to .22, .50 to .79, .14 to .48, .86 to .49)
        val placed = positions.map { (x, y) -> control.copy(geometry = control.geometry.copy(x = x, y = y)) }
            .minBy(canvas::overlap)
        append(placed)
    }

    private fun arrange(control: LayoutControl) {
        val actions = listOf("Bring forward", "Send backward", "Duplicate control", "Delete control",
            if (canvas.uniformResize) "Allow independent resize" else "Use uniform resize",
            "Select a control")
        context.choose(control.displayName, actions) { choice ->
            val controls = history.current.controls.toMutableList()
            val index = controls.indexOfFirst { it.id == control.id }
            when (choice) {
                0, 1 -> {
                    val next = (index + if (choice == 0) 1 else -1).coerceIn(0, controls.lastIndex)
                    controls.add(next, controls.removeAt(index))
                    commit(history.current.copy(controls = controls))
                }
                2 -> append(control.copy(id = UUID.randomUUID().toString(), geometry = control.geometry.copy(
                    x = control.geometry.x + .035, y = control.geometry.y + .035,
                )))
                3 -> {
                    controls.removeAt(index)
                    commit(history.current.copy(controls = controls))
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
        context.choose("Layout", listOf("Profile name and orientation", "Select a control")) { choice ->
            if (choice == 1) {
                chooseControl()
                return@choose
            }
            val profile = history.current
            val form = ControllerForm(context)
            val name = form.text("Profile name", profile.name)
            val orientation = form.choice("Preferred orientation", ProfileOrientation.entries, profile.orientation) { it.name.lowercase().replaceFirstChar(Char::uppercase) }
            form.show("Profile layout") {
                val title = name.value().trim()
                require(title.isNotBlank() && title.none { it.isISOControl() }) { "Enter a profile name" }
                commit(profile.copy(name = title, orientation = orientation()))
            }
        }
    }

    private fun properties(control: LayoutControl) {
        val options = listOf("Position and size", "Appearance", "Input bindings") +
            if (control.type in listOf(ControlType.BUTTON, ControlType.DPAD)) emptyList() else listOf("Control behavior")
        context.choose(control.displayName, options) { choice ->
            when (choice) {
                0 -> geometry(control)
                1 -> appearance(control)
                2 -> bindings(control)
                3 -> behavior(control)
            }
        }
    }

    private fun geometry(control: LayoutControl) {
        val form = ControllerForm(context)
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
        val form = ControllerForm(context)
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
            val title = label.value()
            require(title.none { it.isISOControl() }) { "Use a single-line label" }
            val next = ControlStyle(background.readColor(), border.readColor(), text.readColor(), pressed.readColor(),
                opacity.readNumber(15.0, 100.0) / 100, shape(), radius.readNumber(0.0, .5), visible.isChecked)
            commit(replacing(canvas.clamp(control.copy(label = title, style = next))))
        }
    }

    private fun behavior(control: LayoutControl) {
        val form = ControllerForm(context)
        val previous = control.settings
        val read: () -> ControlSettings = when (control.type) {
            ControlType.JOYSTICK -> {
                val deadZone = form.number("Dead zone (0–0.85)", previous.deadZone)
                val radius = form.number("Travel radius (0.2–1)", previous.radius)
                val threshold = form.number("Direction threshold (0.05–0.98)", previous.threshold)
                val hysteresis = form.number("Release hysteresis (0–0.3)", previous.hysteresis)
                val update: () -> ControlSettings = {
                    previous.copy(deadZone = deadZone.readNumber(0.0, .85), radius = radius.readNumber(.2, 1.0),
                        threshold = threshold.readNumber(.05, .98), hysteresis = hysteresis.readNumber(0.0, .3))
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
            ControlType.TOUCHPAD -> {
                val sensitivity = form.number("Sensitivity (0.1–8)", previous.sensitivity)
                val invertX = form.flag("Invert horizontal movement", previous.invertX)
                val invertY = form.flag("Invert vertical movement", previous.invertY)
                val tap = form.flag("Tap to click", previous.tapToClick)
                val update: () -> ControlSettings = {
                    previous.copy(sensitivity = sensitivity.readNumber(.1, 8.0),
                        invertX = invertX.isChecked, invertY = invertY.isChecked, tapToClick = tap.isChecked)
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
            "${it.wire.replaceFirstChar(Char::uppercase)}  ·  ${control.bindings[it]?.description ?: "Unbound"}"
        }) { choice -> binding(control, slots[choice]) }
    }

    private fun binding(control: LayoutControl, slot: Slot) {
        fun set(binding: Binding?) {
            val next = if (binding == null) control.bindings - slot else control.bindings + (slot to binding)
            commit(replacing(control.copy(bindings = next)))
        }
        val options = when (slot) {
            Slot.POINTER -> listOf("Mouse movement")
            Slot.WHEEL -> listOf("Mouse wheel")
            Slot.TAP -> listOf("Mouse button", "Gamepad (Snapshot 3 · unavailable)")
            else -> listOf("Keyboard", "Mouse button", "Gamepad (Snapshot 3 · unavailable)")
        }
        val choices = listOf("Unbound") + options
        context.choose("Bind ${slot.wire}", choices) {
            when (choices[it]) {
                "Unbound" -> set(null)
                "Mouse movement" -> set(Binding(BindingType.POINTER))
                "Mouse wheel" -> set(Binding(BindingType.WHEEL))
                "Mouse button" -> context.choose("Mouse button", MOUSE_BUTTONS) { index ->
                    set(Binding(BindingType.MOUSE_BUTTON, MOUSE_BUTTONS[index]))
                }
                "Keyboard" -> context.choose("Keyboard key", keys) { index ->
                    val key = keys[index]
                    val choices = MODIFIERS.filterNot { it == key }
                    val old = control.bindings[slot]?.modifiers.orEmpty()
                    val checked = BooleanArray(choices.size) { choices[it] in old }
                    AlertDialog.Builder(context).setTitle("Modifiers for $key")
                        .setMultiChoiceItems(choices.toTypedArray(), checked) { _, index, value -> checked[index] = value }
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Apply") { _, _ ->
                            set(Binding(BindingType.KEYBOARD, key, choices.filterIndexed { index, _ -> checked[index] }))
                        }.show()
                }
                else -> context.choose("Reserved gamepad binding · Snapshot 3", GAMEPAD_TARGETS) { index ->
                    set(Binding(BindingType.GAMEPAD, GAMEPAD_TARGETS[index]))
                }
            }
        }
    }
}
