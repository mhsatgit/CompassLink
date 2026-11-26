package com.compasslink3d

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeProvider
import kotlin.math.roundToInt

internal const val CANVAS_BACKGROUND = 0xFF0A1020.toInt()
internal const val CANVAS_ACCENT = 0xFF52D9E9.toInt()
internal const val CANVAS_TEXT = 0xFFEDF4FF.toInt()

internal fun MotionEvent.isCanceledRelease() = Build.VERSION.SDK_INT >= 33 && (flags and MotionEvent.FLAG_CANCELED) != 0

internal open class ControllerCanvas(context: Context, private val editing: Boolean) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    protected val density: Double get() = resources.displayMetrics.density.toDouble()
    protected var physical = emptyList<PhysicalControl>()
        private set
    protected var models = emptyList<LayoutControl>()
        private set
    protected val viewport: ControllerViewport? get() =
        if (width > 0 && height > 0) ControllerViewport(width, height, dp(48)) else null
    protected var selectedId: String? = null
        set(value) { field = value; invalidate() }

    fun render(controls: List<LayoutControl>) {
        models = controls
        rebuild()
    }

    private fun rebuild() {
        physical = viewport?.let { area ->
            models.filter { it.type != ControlType.TOUCH_LOOK && (editing || it.style.visible) }
                .map { PhysicalControl(it, area.box(it)) }
        }.orEmpty()
        onPhysicalLayout()
        invalidate()
    }

    protected open fun onPhysicalLayout() = Unit
    protected open fun pressed(id: String, slot: Slot? = null): Boolean = false
    protected open fun axes(id: String) = LayoutPoint(0.0, 0.0)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuild()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(CANVAS_BACKGROUND)
        if (editing) {
            paint.color = 0xFF263653.toInt()
            val spacing = dp(20).toInt().coerceAtLeast(1)
            for (x in spacing until width step spacing) {
                for (y in spacing until height step spacing) canvas.drawCircle(x.toFloat(), y.toFloat(), dp(1).toFloat(), paint)
            }
            models.firstOrNull { it.type == ControlType.TOUCH_LOOK && it.id == selectedId }?.let { layer ->
                paint.color = CANVAS_ACCENT
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = dp(2).toFloat()
                val edge = dp(4).toFloat()
                canvas.drawRect(edge, edge, width - edge, height - edge, paint)
                paint.style = Paint.Style.FILL
                text(canvas, layer.displayName, width / 2f, dp(18).toFloat(), dp(14).toFloat(),
                    CANVAS_ACCENT, 255, width * .8f)
            }
        }
        for (control in physical) drawControl(canvas, control)
        physical.firstOrNull { it.model.id == selectedId }?.let { drawSelection(canvas, it.box) }
    }

    private fun drawControl(canvas: Canvas, item: PhysicalControl) {
        val (control, box) = item
        val halfW = (box.width / 2).toFloat()
        val halfH = (box.height / 2).toFloat()
        val rect = RectF(-halfW, -halfH, halfW, halfH)
        val style = control.style
        val active = pressed(control.id)
        val alpha = (255 * style.opacity * if (control.style.visible) 1.0 else .33).roundToInt()
        val radius = item.cornerRadius.toFloat()
        canvas.save()
        canvas.translate(box.center.x.toFloat(), box.center.y.toFloat())
        canvas.rotate(box.rotation.toFloat())
        paint.color = Color.parseColor(if (active) style.pressed else style.background)
        paint.alpha = alpha
        canvas.drawRoundRect(rect, radius, radius, paint)
        paint.color = Color.parseColor(style.border)
        paint.alpha = alpha
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1.5).toFloat()
        canvas.drawRoundRect(rect, radius, radius, paint)
        paint.style = Paint.Style.FILL
        val labelColor = Color.parseColor(style.text)
        val unit = minOf(halfW, halfH)
        val labelSize = minOf(dp(18).toFloat(), unit * .52f)
        when (control.type) {
            ControlType.DPAD -> {
                for ((slot, glyph, x, y) in arrows) {
                    val color = if (pressed(control.id, slot)) CANVAS_ACCENT else labelColor
                    text(canvas, glyph, x * halfW, y * halfH, unit * .52f, color, alpha, unit * .8f)
                }
                text(canvas, control.label, 0f, 0f, labelSize * .78f, labelColor, alpha, halfW * .85f)
            }
            ControlType.JOYSTICK -> {
                val value = axes(control.id)
                val base = (halfW * control.settings.radius * .74).toFloat()
                paint.color = Color.parseColor(style.border)
                paint.alpha = alpha
                paint.style = Paint.Style.STROKE
                canvas.drawCircle(0f, 0f, base, paint)
                paint.style = Paint.Style.FILL
                paint.color = if (active) CANVAS_ACCENT else labelColor
                paint.alpha = (alpha * .78).roundToInt()
                canvas.drawCircle((value.x * base).toFloat(), (value.y * base).toFloat(), unit * .24f, paint)
                text(canvas, control.label, 0f, halfH * .71f, labelSize * .85f, labelColor, alpha, halfW * 1.4f)
            }
            ControlType.TRIGGER, ControlType.SLIDER -> {
                val fraction = axes(control.id).x.toFloat()
                val vertical = control.type == ControlType.TRIGGER || control.settings.vertical
                val travel = (if (vertical) halfH else halfW) * .7f
                paint.color = Color.parseColor(style.border)
                paint.alpha = alpha
                paint.strokeWidth = dp(3).toFloat()
                val origin = if (vertical) halfW * .62f else halfH * .48f
                if (vertical) canvas.drawLine(-origin, -travel, -origin, travel, paint)
                else canvas.drawLine(-travel, origin, travel, origin, paint)
                paint.color = CANVAS_ACCENT
                paint.alpha = alpha
                val progress = if (control.type == ControlType.TRIGGER) fraction else if (vertical) 1 - fraction else fraction
                val offset = -travel + 2 * travel * progress
                if (vertical) canvas.drawCircle(-origin, offset, dp(5).toFloat(), paint)
                else canvas.drawCircle(offset, origin, dp(5).toFloat(), paint)
                val label = if (control.type == ControlType.SLIDER) {
                    val setting = control.settings
                    val level = setting.minimum + (setting.maximum - setting.minimum) * fraction
                    "${control.displayName} · ${level.roundToInt()}"
                } else control.label
                text(canvas, label, if (vertical) halfW * .17f else 0f, if (vertical) 0f else -halfH * .22f,
                    labelSize, labelColor, alpha, if (vertical) halfW * 1.35f else halfW * 1.75f)
            }
            ControlType.TOUCHPAD -> {
                paint.color = Color.parseColor(style.border)
                paint.alpha = alpha / 2
                for (row in -1..1) for (column in -2..2) {
                    canvas.drawCircle(column * halfW * .31f, row * halfH * .53f, dp(2).toFloat(), paint)
                }
                text(canvas, control.label, 0f, 0f, labelSize, labelColor, alpha, halfW * 1.7f)
            }
            ControlType.BUTTON, ControlType.TILT, ControlType.PROFILE_SWITCH, ControlType.TOUCH_LOOK ->
                text(canvas, control.label, 0f, 0f, labelSize, labelColor, alpha, halfW * 1.72f)
        }
        canvas.restore()
    }

    private fun text(canvas: Canvas, label: String, x: Float, y: Float, size: Float, color: Int, alpha: Int, max: Float) {
        if (label.isEmpty()) return
        paint.textSize = size
        paint.typeface = typeface
        paint.textAlign = Paint.Align.CENTER
        paint.color = color
        paint.alpha = alpha
        val measured = paint.measureText(label)
        if (measured > max) paint.textSize *= max / measured
        canvas.drawText(label, x, y - (paint.ascent() + paint.descent()) / 2, paint)
    }

    private fun drawSelection(canvas: Canvas, box: ControlBox) {
        canvas.save()
        canvas.translate(box.center.x.toFloat(), box.center.y.toFloat())
        canvas.rotate(box.rotation.toFloat())
        paint.color = CANVAS_ACCENT
        paint.strokeWidth = dp(2).toFloat()
        paint.style = Paint.Style.STROKE
        val w = (box.width / 2).toFloat()
        val h = (box.height / 2).toFloat()
        canvas.drawRect(-w, -h, w, h, paint)
        paint.style = Paint.Style.FILL
        canvas.restore()
        for ((point, icon) in listOf(resizeHandle(box) to "↔", rotateHandle(box) to "↻")) {
            paint.color = CANVAS_ACCENT
            canvas.drawCircle(point.x.toFloat(), point.y.toFloat(), dp(10).toFloat(), paint)
            text(canvas, icon, point.x.toFloat(), point.y.toFloat(), dp(13).toFloat(), CANVAS_BACKGROUND, 255, dp(16).toFloat())
        }
    }

    protected fun resizeHandle(box: ControlBox) = box.world(LayoutPoint(box.width / 2 - dp(10), box.height / 2 - dp(10)))
    protected fun rotateHandle(box: ControlBox) = box.world(LayoutPoint(0.0, -box.height / 2 + dp(10)))
    protected fun dp(amount: Number) = amount.toDouble() * density

    private data class Arrow(val slot: Slot, val glyph: String, val x: Float, val y: Float)
    private companion object {
        val arrows = listOf(
            Arrow(Slot.UP, "▲", 0f, -.62f), Arrow(Slot.DOWN, "▼", 0f, .62f),
            Arrow(Slot.LEFT, "◀", -.62f, 0f), Arrow(Slot.RIGHT, "▶", .62f, 0f),
        )
    }
}

internal class ControllerPad(context: Context, profile: ControllerProfile, private val mode: OutputMode,
    onSwitch: (String) -> Unit = {}, send: (ControllerInput) -> Unit) :
    ControllerCanvas(context, editing = false) {
    private val haptics = profile.hybrid?.haptics ?: TouchHaptics.OFF
    var onPointerReleased: (String) -> Unit = {}
    private val touch = ControllerTouchEngine(density, send, mode, { feedback(haptics) }, onSwitch) { onPointerReleased(it) }
    private val hasMouseLook = profile.controls.any { it.style.visible && it.bindings[Slot.POINTER]?.supports(mode) == true }
    private var ready = false
    private var accessibleFocus = View.NO_ID
    private var scheduledExpiry: Long? = null
    private val expireMotion = Runnable {
        scheduledExpiry = null
        touch.expireMotion(SystemClock.uptimeMillis())
        scheduleMotionExpiry()
        invalidate()
    }
    private val acceptsInput get() = ready && isShown && hasWindowFocus()

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        render(profile.controls)
    }

    fun setReady(value: Boolean) {
        if (ready == value) return
        ready = value
        releaseAll(notify = false)
    }

    fun releaseAll(notify: Boolean = true) {
        touch.clear(notify && ready)
        scheduleMotionExpiry()
        invalidate()
    }

    fun updateTilt(angle: Double) {
        if (!acceptsInput) return
        if (touch.updateTilt(angle, SystemClock.uptimeMillis())) invalidate()
        scheduleMotionExpiry()
    }

    private fun scheduleMotionExpiry() {
        val expiry = touch.motionExpiry
        if (scheduledExpiry == expiry) return
        scheduledExpiry = expiry
        removeCallbacks(expireMotion)
        if (expiry != null) postDelayed(expireMotion, (expiry - SystemClock.uptimeMillis()).coerceAtLeast(0L))
    }

    override fun onPhysicalLayout() {
        releaseAll()
        touch.controls = physical
        touch.backgroundLook = models.firstOrNull { it.type == ControlType.TOUCH_LOOK && it.style.visible &&
            it.bindings[Slot.POINTER]?.supports(mode) == true }?.let { layer ->
            viewport?.let { area -> PhysicalControl(layer, area.box(layer)) }
        }
    }

    override fun pressed(id: String, slot: Slot?) =
        touch.active(id, slot) || (slot == null && (touch.touched(id) || touch.tiltEnabled(id)))
    override fun axes(id: String) = touch.value(id)

    private fun hasClickBinding(control: LayoutControl) = when (control.type) {
        ControlType.TILT -> control.bindings.values.any { it.supports(mode) }
        ControlType.PROFILE_SWITCH -> true
        ControlType.TOUCHPAD -> (mode == OutputMode.HYBRID || control.bindings[Slot.AXIS]?.supports(mode) != true) &&
            control.bindings[Slot.TAP]?.supports(mode) == true
        else -> control.bindings[Slot.PRESS]?.supports(mode) == true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!acceptsInput) return true
        fun position(index: Int) = LayoutPoint(event.getX(index).toDouble(), event.getY(index).toDouble())
        val index = event.actionIndex
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.actionMasked == MotionEvent.ACTION_DOWN && hasMouseLook) requestUnbufferedDispatch(event)
                touch.down(event.getPointerId(index), position(index), event.eventTime)
            }
            MotionEvent.ACTION_MOVE -> {
                for (history in 0 until event.historySize) {
                    val time = event.getHistoricalEventTime(history)
                    for (i in 0 until event.pointerCount) {
                        val id = event.getPointerId(i)
                        if (touch.tracksPointer(id)) {
                            val sample = LayoutPoint(event.getHistoricalX(i, history).toDouble(), event.getHistoricalY(i, history).toDouble())
                            touch.move(id, sample, time)
                        }
                    }
                }
                for (i in 0 until event.pointerCount)
                    touch.move(event.getPointerId(i), position(i), event.eventTime)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP ->
                touch.up(event.getPointerId(index), position(index), event.eventTime, event.isCanceledRelease())
            MotionEvent.ACTION_CANCEL -> releaseAll()
        }
        scheduleMotionExpiry()
        invalidate()
        return true
    }

    override fun getAccessibilityNodeProvider() = accessibility

    @Suppress("DEPRECATION")
    private val accessibility = object : AccessibilityNodeProvider() {
        override fun createAccessibilityNodeInfo(id: Int): AccessibilityNodeInfo? {
            if (id == HOST_VIEW_ID) return AccessibilityNodeInfo.obtain(this@ControllerPad).also { host ->
                onInitializeAccessibilityNodeInfo(host)
                for (index in physical.indices) host.addChild(this@ControllerPad, index)
            }
            val item = physical.getOrNull(id) ?: return null
            val (control, box) = item
            val local = Rect((box.center.x - box.halfX).toInt(), (box.center.y - box.halfY).toInt(),
                (box.center.x + box.halfX).toInt(), (box.center.y + box.halfY).toInt())
            val location = IntArray(2).also(::getLocationOnScreen)
            return AccessibilityNodeInfo.obtain().apply {
                setSource(this@ControllerPad, id)
                setParent(this@ControllerPad)
                packageName = context.packageName
                className = "android.widget.Button"
                contentDescription = control.displayName
                isVisibleToUser = isShown
                isEnabled = acceptsInput
                isCheckable = control.type == ControlType.TILT
                isChecked = isCheckable && touch.tiltEnabled(control.id)
                isFocusable = true
                isAccessibilityFocused = accessibleFocus == id
                isClickable = isEnabled && hasClickBinding(control)
                @Suppress("DEPRECATION")
                setBoundsInParent(local)
                local.offset(location[0], location[1])
                setBoundsInScreen(local)
                addAction(if (isAccessibilityFocused) AccessibilityNodeInfo.AccessibilityAction.ACTION_CLEAR_ACCESSIBILITY_FOCUS
                    else AccessibilityNodeInfo.AccessibilityAction.ACTION_ACCESSIBILITY_FOCUS)
                if (isClickable) addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK)
            }
        }

        override fun performAction(id: Int, action: Int, arguments: Bundle?): Boolean {
            if (id == HOST_VIEW_ID) return performAccessibilityAction(action, arguments)
            val item = physical.getOrNull(id) ?: return false
            val eventType = when (action) {
                AccessibilityNodeInfo.ACTION_CLICK -> {
                    if (!acceptsInput || !hasClickBinding(item.model)) return false
                    touch.click(item.model)
                    scheduleMotionExpiry()
                    invalidate()
                    AccessibilityEvent.TYPE_VIEW_CLICKED
                }
                AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS -> {
                    accessibleFocus = id
                    AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED
                }
                AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS -> {
                    accessibleFocus = View.NO_ID
                    AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED
                }
                else -> return false
            }
            val event = AccessibilityEvent.obtain(eventType)
            event.setSource(this@ControllerPad, id)
            event.packageName = context.packageName
            event.className = "android.widget.Button"
            event.contentDescription = item.model.displayName
            parent?.requestSendAccessibilityEvent(this@ControllerPad, event)
            return true
        }
    }
}

internal fun View.feedback(level: TouchHaptics) {
    if (level == TouchHaptics.OFF) return
    val effect = if (Build.VERSION.SDK_INT >= 30) when (level) {
        TouchHaptics.LIGHT -> android.view.HapticFeedbackConstants.CLOCK_TICK
        TouchHaptics.MEDIUM -> android.view.HapticFeedbackConstants.CONFIRM
        else -> android.view.HapticFeedbackConstants.LONG_PRESS
    } else android.view.HapticFeedbackConstants.VIRTUAL_KEY
    post { if (isAttachedToWindow) performHapticFeedback(effect) }
}
