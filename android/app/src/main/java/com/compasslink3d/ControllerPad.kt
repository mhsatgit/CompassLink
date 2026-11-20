package com.compasslink3d

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.Button
import kotlin.math.roundToInt

internal class ControllerPad(
    context: Context,
    private val send: (ControllerTransition) -> Unit,
) : ViewGroup(context) {
    private val controls = ControllerControl.entries.associateWith { control ->
        newButton(control).also { button -> addView(button) }
    }
    private val pointers = mutableMapOf<Int, ControllerControl?>()
    private val held = mutableSetOf<ControllerControl>()
    private var ready = false
    private var gap = 0
    private var directionSize = 0
    private var actionSize = 0

    fun setReady(value: Boolean) {
        if (ready == value) return
        ready = value
        // A newly authorized socket always begins with every control released.
        releaseAll(notify = false)
        controls.values.forEach { it.isEnabled = value }
    }

    fun releaseAll(notify: Boolean = true) {
        pointers.clear()
        updateHeld(notify)
    }

    override fun onInterceptTouchEvent(event: MotionEvent) = true

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!ready) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val index = event.actionIndex
                if (event.actionMasked == MotionEvent.ACTION_DOWN) releaseAll()
                val control = controlAt(event.getX(index), event.getY(index))
                if (control != null) pointers[event.getPointerId(index)] = control
            }
            MotionEvent.ACTION_MOVE -> {
                for (index in 0 until event.pointerCount) {
                    val id = event.getPointerId(index)
                    if (pointers.containsKey(id)) {
                        pointers[id] = controlAt(event.getX(index), event.getY(index))
                    }
                }
            }
            MotionEvent.ACTION_POINTER_UP -> pointers.remove(event.getPointerId(event.actionIndex))
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> pointers.clear()
        }
        updateHeld()
        return true
    }

    private fun controlAt(x: Float, y: Float) = controls.entries.firstOrNull { (_, button) ->
        x >= button.left && x < button.right && y >= button.top && y < button.bottom
    }?.key

    private fun updateHeld(notify: Boolean = true) {
        val next = pointers.values.filterNotNull().toSet()
        for (control in held - next) setHeld(control, false, notify)
        for (control in next - held) setHeld(control, true, notify)
        held.clear()
        held.addAll(next)
    }

    private fun setHeld(control: ControllerControl, down: Boolean, notify: Boolean) {
        controls.getValue(control).isActivated = down
        if (notify) send(ControllerTransition(control, down))
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(width, height)
        gap = minOf(dp(8), width / 36, height / 16)
        directionSize = minOf(dp(94), (width / 2 - gap * 3) / 3, (height - gap * 4) / 3)
            .coerceAtLeast(0)
        actionSize = minOf(dp(108), (width / 2 - gap * 3) / 2, (height - gap * 2) / 2)
            .coerceAtLeast(0)
        for ((control, button) in controls) {
            val side = if (control == ControllerControl.ACTION_A || control == ControllerControl.ACTION_B) {
                actionSize
            } else directionSize
            val spec = MeasureSpec.makeMeasureSpec(side, MeasureSpec.EXACTLY)
            button.measure(spec, spec)
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val x = width / 4
        val y = height / 2
        val step = directionSize + gap
        place(ControllerControl.DPAD_UP, x, y - step)
        place(ControllerControl.DPAD_DOWN, x, y + step)
        place(ControllerControl.DPAD_LEFT, x - step, y)
        place(ControllerControl.DPAD_RIGHT, x + step, y)
        val offset = (actionSize + gap) / 2
        place(ControllerControl.ACTION_B, x * 3 - offset, y + actionSize / 5)
        place(ControllerControl.ACTION_A, x * 3 + offset, y - actionSize / 5)
    }

    private fun place(control: ControllerControl, x: Int, y: Int) {
        val button = controls.getValue(control)
        val size = button.measuredWidth
        button.layout(x - size / 2, y - size / 2, x - size / 2 + size, y - size / 2 + size)
    }

    private fun newButton(control: ControllerControl) = Button(context).apply {
        text = control.label
        contentDescription = control.description
        textSize = if (control.name.startsWith("ACTION_")) 24f else 13f
        setPadding(0, 0, 0, 0)
        backgroundTintList = null
        stateListAnimator = null
        isEnabled = false
        val activated = intArrayOf(android.R.attr.state_activated)
        val disabled = intArrayOf(-android.R.attr.state_enabled)
        val focused = intArrayOf(android.R.attr.state_focused)
        background = StateListDrawable().apply {
            addState(activated, shape(0xFF52D9E9.toInt(), 0xFF52D9E9.toInt(), control))
            addState(disabled, shape(0xFF141F33.toInt(), 0xFF253653.toInt(), control))
            addState(focused, shape(0xFF233B56.toInt(), 0xFF52D9E9.toInt(), control))
            addState(intArrayOf(), shape(0xFF1C2C47.toInt(), 0xFF476487.toInt(), control))
        }
        setTextColor(ColorStateList(
            arrayOf(activated, disabled, intArrayOf()),
            intArrayOf(0xFF0A1020.toInt(), 0xFF8EA2C2.toInt(), Color.WHITE),
        ))
        setOnClickListener {
            // Accessibility and keyboard activation use the same ordered transport.
            if (ready && control !in held) {
                send(ControllerTransition(control, true))
                send(ControllerTransition(control, false))
            }
        }
    }

    private fun shape(fill: Int, stroke: Int, control: ControllerControl) = GradientDrawable().apply {
        setColor(fill)
        setStroke(dp(1), stroke)
        cornerRadius = dp(if (control.name.startsWith("ACTION_")) 60 else 15).toFloat()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
}
