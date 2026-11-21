package com.compasslink3d

import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONException
import java.io.IOException

internal fun Context.px(dp: Int) = (dp * resources.displayMetrics.density + .5f).toInt()

internal fun Context.builderButton(label: String, highlight: Boolean = false, click: () -> Unit): Button =
    Button(this).apply {
        text = label
        textSize = 13f
        isAllCaps = false
        minHeight = px(48)
        minimumHeight = px(48)
        setPadding(px(11), 0, px(11), 0)
        setTextColor(if (highlight) CANVAS_BACKGROUND else CANVAS_TEXT)
        background = GradientDrawable().apply {
            setColor(if (highlight) CANVAS_ACCENT else 0xFF1C2C47.toInt())
            cornerRadius = px(9).toFloat()
        }
        backgroundTintList = ColorStateList(
            arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf(android.R.attr.state_pressed), intArrayOf()),
            intArrayOf(0xFF303747.toInt(), 0xFF329DAC.toInt(), if (highlight) CANVAS_ACCENT else 0xFF1C2C47.toInt()),
        )
        setOnClickListener { click() }
        layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
            marginEnd = px(6)
        }
    }

internal fun Context.toolbar(vararg views: View): HorizontalScrollView = HorizontalScrollView(this).apply {
    isHorizontalScrollBarEnabled = false
    addView(LinearLayout(this@toolbar).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        views.forEach { addView(it) }
    })
}

internal fun Context.heading(label: String) = TextView(this).apply {
    text = label
    textSize = 20f
    setTypeface(typeface, Typeface.BOLD)
    setTextColor(CANVAS_TEXT)
    setPadding(0, 0, px(12), 0)
}

internal fun Context.hint(label: String) = TextView(this).apply {
    text = label
    textSize = 13f
    setTextColor(0xFFB5CCE8.toInt())
    setPadding(0, px(4), 0, px(4))
}

internal fun Context.builderRoot() = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setBackgroundColor(CANVAS_BACKGROUND)
}

internal fun Context.showError(message: String) {
    AlertDialog.Builder(this).setTitle("CompassLink").setMessage(message).setPositiveButton("OK", null).show()
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

internal fun Context.choose(title: String, choices: List<String>, selected: (Int) -> Unit) {
    AlertDialog.Builder(this).setTitle(title).setItems(choices.toTypedArray()) { _, index -> selected(index) }
        .setNegativeButton("Cancel", null).show()
}

internal class ControllerForm(private val context: Context) {
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

    fun flag(label: String, checked: Boolean) = CheckBox(context).apply {
        text = label
        isChecked = checked
        minHeight = context.px(48)
        fields.addView(this)
    }

    fun <T> choice(label: String, options: List<T>, initial: T, name: (T) -> String): () -> T {
        var selected = initial
        fields.addView(context.hint(label))
        val button = context.builderButton(name(selected)) {
            context.choose(label, options.map(name)) { index ->
                selected = options[index]
                fields.findViewWithTag<Button>(label).text = name(selected)
            }
        }
        button.tag = label
        fields.addView(button)
        return { selected }
    }

    fun color(label: String, value: String): EditText {
        val input = text("$label (#RRGGBB)", value, 7)
        val presets = listOf("Midnight" to "#1C2C47", "Blue" to "#3157A3", "Cyan" to "#52D9E9", "Mint" to "#51D69A",
            "Purple" to "#854FCE", "Coral" to "#CC526D", "White" to "#EDF4FF", "Black" to "#0A1020")
        fields.addView(context.builderButton("Choose $label") {
            context.choose(label, presets.map { it.first }) { input.setText(presets[it].second) }
        })
        return input
    }

    fun show(title: String, confirm: String = "Apply", apply: () -> Unit) {
        val scroll = ScrollView(context).apply { addView(fields) }
        val dialog = AlertDialog.Builder(context).setTitle(title).setView(scroll)
            .setNegativeButton("Cancel", null).setPositiveButton(confirm, null).create()
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
