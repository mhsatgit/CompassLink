package com.compasslink3d

import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.text.InputFilter
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import org.json.JSONException
import java.io.IOException
import kotlin.math.roundToInt

internal fun Context.px(dp: Int) = (dp * resources.displayMetrics.density + .5f).toInt()

internal fun Context.roundedSurface(color: Int, radius: Int = 12) = GradientDrawable().apply {
    setColor(color)
    cornerRadius = px(radius).toFloat()
}

private fun Context.actionBackground(highlight: Boolean = false) = RippleDrawable(
    ColorStateList.valueOf(if (highlight) 0x33FFFFFF else 0x33FF3B4E),
    android.graphics.drawable.StateListDrawable().apply {
        addState(intArrayOf(-android.R.attr.state_enabled), angularSurface(PANEL_COLOR))
        addState(intArrayOf(android.R.attr.state_pressed), angularSurface(ACTION_PRESSED, CANVAS_ACCENT))
        addState(intArrayOf(android.R.attr.state_focused), angularSurface(SELECTED_SURFACE, CANVAS_TEXT))
        addState(intArrayOf(android.R.attr.state_selected), angularSurface(SELECTED_SURFACE, CANVAS_ACCENT))
        addState(intArrayOf(), angularSurface(if (highlight) ACTION_FILL else BUTTON_COLOR,
            if (highlight) CANVAS_ACCENT else CONTROL_OUTLINE))
    },
    null,
)

internal fun Context.builderButton(label: String, highlight: Boolean = false, icon: Int? = null, click: () -> Unit): Button =
    Button(this).apply {
        text = label
        textSize = 13f
        isAllCaps = false
        minHeight = px(48)
        minimumHeight = px(48)
        minimumWidth = 0
        setPadding(px(10), px(4), px(10), px(4))
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
        val colors = ColorStateList(
            arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(CONTROL_OUTLINE, CANVAS_TEXT),
        )
        setTextColor(colors)
        background = actionBackground(highlight)
        if (icon != null) {
            val graphic = getDrawable(icon)?.mutate()?.apply { setBounds(0, 0, px(19), px(19)) }
            setCompoundDrawablesRelative(graphic, null, null, null)
            compoundDrawableTintList = colors
            compoundDrawablePadding = px(6)
        }
        setOnClickListener { click() }
    }

internal fun Context.iconButton(label: String, icon: Int, click: () -> Unit) = ImageButton(this).apply {
    contentDescription = label
    tooltipText = label
    setImageResource(icon)
    imageTintList = ColorStateList(
        arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
        intArrayOf(CONTROL_OUTLINE, CANVAS_TEXT),
    )
    background = actionBackground()
    setPadding(px(12), px(12), px(12), px(12))
    scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
    minimumWidth = px(48)
    minimumHeight = px(48)
    setOnClickListener { click() }
}

internal fun Context.actionRow(vararg actions: View) = LinearLayout(this).apply {
    gravity = Gravity.CENTER_VERTICAL
    setPadding(0, px(6), 0, px(6))
    actions.forEachIndexed { index, action ->
        addView(action, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply {
            if (index != actions.lastIndex) marginEnd = px(8)
        })
    }
}

internal fun Context.controllerHeader(title: TextView, backLabel: String, onBack: () -> Unit, vararg actions: View) =
    LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, 0, 0, px(6))
        addView(iconButton(backLabel, R.drawable.ic_controller_back, onBack), LinearLayout.LayoutParams(px(48), px(48)))
        title.maxLines = 1
        title.ellipsize = TextUtils.TruncateAt.END
        addView(title, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply {
            marginStart = px(10)
            marginEnd = px(10)
        })
        for (action in actions) {
            addView(action, LinearLayout.LayoutParams(px(48), px(48)).apply { marginStart = px(6) })
        }
    }

internal fun Context.controlPanel() = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    background = angularSurface(PANEL_COLOR)
    setPadding(px(16), px(12), px(16), px(12))
}

internal fun Context.heading(label: String) = TextView(this).apply {
    text = label
    textSize = 20f
    typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    letterSpacing = .02f
    setTextColor(CANVAS_TEXT)
    if (Build.VERSION.SDK_INT >= 28) setAccessibilityHeading(true)
}

internal fun Context.hint(label: String) = TextView(this).apply {
    text = label
    textSize = 13f
    setTextColor(MUTED_TEXT)
    setPadding(0, px(4), 0, px(4))
}

internal fun Context.builderRoot() = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setBackgroundColor(CANVAS_BACKGROUND)
}

internal fun Context.showError(message: String) {
    AlertDialog.Builder(this).setTitle("CompassLink").setMessage(message)
        .setPositiveButton("OK", null).create().apply { escapeDismisses(); show() }
}

internal fun Context.reportErrors(action: () -> Unit): Boolean {
    try {
        action()
        return true
    } catch (error: IllegalArgumentException) {
        showError(error.message ?: "Check the entered values")
    } catch (error: IllegalStateException) {
        showError(error.message ?: "The operation is unavailable")
    } catch (error: JSONException) {
        showError("Invalid controller profile. ${error.message.orEmpty()}")
    } catch (error: IOException) {
        showError(error.message ?: "Could not read or save the profile")
    } catch (error: SecurityException) {
        showError(error.message ?: "This file is inaccessible")
    }
    return false
}

internal fun Context.choose(title: String, choices: List<String>, onBack: (() -> Unit)? = null, selected: (Int) -> Unit) {
    selectionSheet(title, onBack) { rows, dialog ->
        choices.forEachIndexed { index, choice ->
            rows.addView(builderButton(choice) { dialog.dismiss(); selected(index) },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(4) })
        }
    }
}

internal class ControllerForm(private val context: Context, private val onBack: (() -> Unit)? = null) {
    private val visibilityRules = mutableListOf<() -> Unit>()
    private val fields = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(context.px(20), context.px(8), context.px(20), context.px(12))
    }

    fun text(label: String, value: String, max: Int = MAX_LABEL, flags: Int = InputType.TYPE_CLASS_TEXT): EditText {
        val caption = context.hint(label)
        fields.addView(caption)
        val input = EditText(context).apply {
            id = View.generateViewId()
            inputType = flags
            setSingleLine()
            filters = arrayOf(InputFilter.LengthFilter(max))
            setText(value)
        }
        caption.labelFor = input.id
        fields.addView(input, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        return input
    }

    fun number(label: String, value: Double) = text(label, String.format(java.util.Locale.US, "%.4f", value).trimEnd('0').trimEnd('.'),
        16, InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED)

    fun mouseSensitivity(initial: Double): () -> Double {
        var value = initial
        val sliderLabel = context.getString(R.string.mouse_sensitivity)
        val caption = context.hint(context.getString(R.string.mouse_sensitivity_value, value))
        fun show(next: Double) {
            value = next
            caption.text = context.getString(R.string.mouse_sensitivity_value, value)
        }
        // Use finer steps near normal speeds for precise adjustments.
        fun position(speed: Double) =
            if (speed <= 1.5) (speed * 20).roundToInt() - 1 else 29 + ((speed - 1.5) * 10).roundToInt()
        val slider = SeekBar(context).apply {
            id = View.generateViewId()
            max = 94
            progress = position(value)
            contentDescription = sliderLabel
            minimumHeight = context.px(48)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    show(if (progress <= 29) (progress + 1) / 20.0 else 1.5 + (progress - 29) / 10.0)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
            })
        }
        caption.labelFor = slider.id
        fields.addView(caption)
        fields.addView(slider, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        fields.addView(context.actionRow(*listOf(.2, .5, 1.0).map { preset ->
            context.builderButton(context.getString(R.string.mouse_sensitivity_preset, preset)) {
                slider.progress = position(preset)
                show(preset)
            }
        }.toTypedArray()))
        return { value }
    }

    fun flag(label: String, checked: Boolean) = CheckBox(context).apply {
        text = label
        isChecked = checked
        minHeight = context.px(48)
        setOnCheckedChangeListener { _, _ -> refreshFields() }
        fields.addView(this)
    }

    fun <T> choice(label: String, options: List<T>, initial: T, name: (T) -> String): () -> T {
        var selected = initial
        fields.addView(context.hint(label))
        lateinit var button: Button
        button = context.builderButton(name(selected)) {
            context.choose(label, options.map(name)) { index ->
                selected = options[index]
                button.text = name(selected)
                refreshFields()
            }
        }
        fields.addView(button)
        return { selected }
    }

    fun <T> whenVisible(visible: () -> Boolean, addFields: () -> T): T {
        val first = fields.childCount
        val result = addFields()
        val children = (first until fields.childCount).map(fields::getChildAt)
        visibilityRules.add {
            val visibility = if (visible()) View.VISIBLE else View.GONE
            children.forEach { it.visibility = visibility }
        }
        return result
    }

    fun note(text: String) { fields.addView(context.hint(text)) }

    private fun refreshFields() = visibilityRules.forEach { it() }

    fun color(label: String, value: String): EditText {
        val input = text("$label (#RRGGBB)", value, 7)
        val presets = listOf("Charcoal" to "#19191D", "Red" to "#FF3B4E", "Dark red" to "#A9162C",
            "Gray" to "#B5B5BE", "White" to "#F5F5F7", "Black" to "#050506")
        fields.addView(context.builderButton("Choose $label") {
            context.choose(label, presets.map { it.first }) { input.setText(presets[it].second) }
        })
        return input
    }

    fun show(title: String, confirm: String = "Apply", apply: () -> Unit) {
        refreshFields()
        val scroll = ScrollView(context).apply { addView(fields) }
        val dialog = AlertDialog.Builder(context).setTitle(title).setView(scroll)
            .setOnCancelListener { onBack?.invoke() }
            .setNegativeButton("Cancel") { _, _ -> onBack?.invoke() }.setPositiveButton(confirm, null).create()
        dialog.escapeDismisses()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (context.reportErrors(apply)) dialog.dismiss()
            }
        }
        dialog.show()
    }
}

internal fun EditText.value() = text.toString()
internal fun EditText.readNumber(min: Double, max: Double): Double {
    val raw = value().toDoubleOrNull()
    require(raw != null && raw.isFinite() && raw in min..max) { "Enter a number from $min to $max" }
    return raw
}

internal fun EditText.readColor(): String {
    val raw = value()
    require(raw.matches(Regex("#[0-9a-fA-F]{6}"))) { "Enter a color in #RRGGBB format" }
    Color.parseColor(raw)
    return raw.uppercase()
}

internal fun Context.configureMotion(previous: MotionSettings, keys: List<String>, onBack: (() -> Unit)? = null, apply: (MotionSettings) -> Unit) {
    val form = ControllerForm(this, onBack)
    val enabled = form.flag("Enable motion controls", previous.enabled)
    val mapping = form.choice("Mapping", MotionMapping.entries, previous.mapping) { it.title }
    val source = form.choice("Input source", MotionSource.entries, previous.source) { it.title }
    val horizontal = form.choice("Horizontal axis", MotionAxis.entries, previous.horizontal) { it.name }
    val vertical = form.choice("Vertical axis", MotionAxis.entries, previous.vertical) { it.name }
    val invertX = form.flag("Invert horizontal axis", previous.invertX)
    val invertY = form.flag("Invert vertical axis", previous.invertY)
    val sensitivity = form.number("Sensitivity (0.1–8)", previous.sensitivity)
    val deadZone = form.number("Dead zone (0–0.85)", previous.deadZone)
    val smoothing = form.number("Smoothing (0–500 ms)", previous.smoothingMs)
    val steer = { mapping() == MotionMapping.STEERING }
    val maximum = form.whenVisible({ !steer() }) { form.number("Maximum output (0.1–1)", previous.maximum) }
    val stick = form.whenVisible({ mapping() == MotionMapping.STICK }) {
        form.choice("Gamepad stick", listOf("LEFT_STICK", "RIGHT_STICK"), previous.stick) { it.replace('_', ' ') }
    }
    val (left, right) = form.whenVisible(steer) {
        form.choice("Steer left key", keys, previous.leftKey) { it } to
            form.choice("Steer right key", keys, previous.rightKey) { it }
    }
    val (engage, release) = form.whenVisible(steer) {
        form.number("Steering engage (0.05–1)", previous.engage) to
            form.number("Steering release (0–0.9)", previous.release)
    }
    val mouse = form.whenVisible({ mapping() == MotionMapping.MOUSE }) {
        form.number("Mouse speed at full input (pixels/sec)", previous.mouseScale)
    }
    val gyro = form.whenVisible({ source() == MotionSource.GYROSCOPE }) {
        form.note("Hold enables aim during the press. Toggle switches aim on or off. Always enables aim while ready.")
        form.choice("Gyro enable behavior", GyroEnable.entries, previous.gyroEnable) { it.name.lowercase().replaceFirstChar(Char::titlecase) }
    }
    val label = form.whenVisible({ source() == MotionSource.GYROSCOPE && gyro() != GyroEnable.ALWAYS }) {
        form.text("Gyro button label", previous.gyroLabel, 24)
    }
    form.note("Recenter during play to set a comfortable neutral position.")
    val allowedKeys = keys.toSet()
    form.show("Motion settings") {
        val settings = MotionSettings(enabled.isChecked, mapping(), source(), horizontal(), vertical(),
            sensitivity.readNumber(.1, 8.0), deadZone.readNumber(0.0, .85), smoothing.readNumber(0.0, 500.0),
            invertX.isChecked, invertY.isChecked, maximum.readNumber(.1, 1.0), stick(), left(), right(),
            engage.readNumber(.05, 1.0), release.readNumber(0.0, .9), mouse.readNumber(10.0, 3000.0),
            gyro(), label.value().trim())
        settings.validate(allowedKeys)
        apply(settings)
    }
}

internal class MotionPanel(context: Context, private val settings: MotionSettings, gyroSettings: MotionSettings?,
    private val usesDedicatedControl: Boolean, recenter: () -> Unit, enable: (Boolean) -> Unit) {
    private val center = context.iconButton("Recenter motion", R.drawable.ic_controller_refresh, recenter)
    private var reportingTouchClick = false
    private val gyro = if (gyroSettings != null && usesDedicatedControl && gyroSettings.gyroEnable != GyroEnable.ALWAYS) {
        context.builderButton(gyroSettings.gyroLabel) {
            if (!reportingTouchClick) {
                enable(true)
                if (gyroSettings.gyroEnable == GyroEnable.HOLD) center.postDelayed({ enable(false) }, 180L)
                else enable(false)
            }
        }.apply {
            if (gyroSettings.gyroEnable == GyroEnable.HOLD) setOnTouchListener { button, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        button.isPressed = true
                        enable(true)
                    }
                    MotionEvent.ACTION_UP -> {
                        enable(false)
                        if (button.isPressed) {
                            reportingTouchClick = true
                            button.performClick()
                            reportingTouchClick = false
                        }
                        button.isPressed = false
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        button.isPressed = false
                        enable(false)
                    }
                    MotionEvent.ACTION_MOVE -> if (button.isPressed &&
                        (event.x !in 0f..width.toFloat() || event.y !in 0f..height.toFloat())) {
                        button.isPressed = false
                        enable(false)
                    }
                }
                true
            }
        }
    } else null
    private val gyroLabel = gyroSettings?.gyroLabel

    val view = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(center, LinearLayout.LayoutParams(context.px(48), context.px(48)))
        gyro?.let { addView(it, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)) }
    }

    fun update(calibrated: Boolean, fresh: Boolean, orientationAvailable: Boolean, gyroAvailable: Boolean, armed: Boolean, output: LayoutPoint, mappings: String) {
        center.isEnabled = orientationAvailable || settings.source == MotionSource.GYROSCOPE && gyroAvailable
        gyro?.isEnabled = gyroAvailable
        gyro?.let {
            val label = gyroLabel + if (armed) " · On" else " · Off"
            if (it.text != label) it.text = label
            it.isSelected = armed
        }
        val status = when {
            settings.source == MotionSource.GYROSCOPE && !gyroAvailable -> "Gyroscope unavailable"
            settings.source == MotionSource.ORIENTATION && !orientationAvailable -> "Orientation sensor unavailable"
            settings.source == MotionSource.GYROSCOPE && !armed -> if (calibrated) "Calibrated · Gyro off" else "Gyro off"
            !fresh -> "Waiting for motion samples"
            else -> String.format(java.util.Locale.US, "%s · X %+.2f  Y %+.2f", if (calibrated) "Calibrated" else "Gyro active", output.x, output.y)
        } + if (!usesDedicatedControl && gyroLabel != null && settings.source != MotionSource.GYROSCOPE)
            if (armed) " · Gyro on" else " · Gyro off" else ""
        val details = listOf(status, mappings).filter { it.isNotBlank() }.joinToString(" · ")
        center.tooltipText = details
        if (Build.VERSION.SDK_INT >= 30 && center.stateDescription != status) center.stateDescription = status
    }
}
