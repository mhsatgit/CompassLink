package com.compasslink3d

import android.app.Dialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.Paint
import android.graphics.Path
import android.view.Gravity
import android.view.View
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.view.Window
import android.widget.LinearLayout
import android.widget.FrameLayout
import android.widget.ScrollView
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

internal enum class Destination(val title: String, val icon: Int) {
    CONNECT("Connect", R.drawable.ic_controller_signal),
    CONTROLLERS("Controllers", R.drawable.ic_controller_gamepad),
    SENSORS("Sensors", R.drawable.ic_controller_phone)
}

internal fun Context.navigationShell(active: Destination, body: View, navigate: (Destination) -> Unit): View {
    val preferences = getSharedPreferences("workspace", Context.MODE_PRIVATE)
    var collapsed = preferences.getBoolean("compact_rail", false)
    fun navigation(rail: Boolean) = LinearLayout(this).apply {
        orientation = if (rail) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        gravity = if (rail) Gravity.TOP or Gravity.CENTER_HORIZONTAL else Gravity.CENTER
        for (next in Destination.entries) {
            val select = { if (next != active) navigate(next) }
            val action = if (rail && collapsed) iconButton(next.title, next.icon, select)
                else builderButton(next.title, icon = next.icon, click = select)
            action.isSelected = active == next
            val params = if (rail) LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = px(6) }
                else LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = px(3) }
            addView(action, params)
        }
    }

    val rail = builderRoot()
    fun renderRail() {
        rail.removeAllViews()
        rail.addView(iconButton(if (collapsed) "Expand navigation" else "Minimize navigation",
            R.drawable.ic_controller_menu) {
            collapsed = !collapsed
            preferences.edit().putBoolean("compact_rail", collapsed).apply()
            renderRail()
        }, LinearLayout.LayoutParams(px(48), px(48)).apply { bottomMargin = px(8) })
        rail.addView(navigation(true), LinearLayout.LayoutParams(-1, -2))
        rail.requestLayout()
    }
    renderRail()

    val bottom = navigation(false)
    val column = builderRoot().apply {
        addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        addView(bottom, LinearLayout.LayoutParams(-1, -2))
    }
    val railScroll = ScrollView(this).apply {
        isVerticalScrollBarEnabled = false
        addView(rail, LinearLayout.LayoutParams(-1, -2))
    }
    return object : LinearLayout(this) {
        init {
            orientation = HORIZONTAL
            setBackgroundColor(CANVAS_BACKGROUND)
            addView(railScroll, LayoutParams(px(if (collapsed) 52 else 144), -1).apply { marginEnd = px(10) })
            addView(column, LayoutParams(0, -1, 1f))
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
            val h = MeasureSpec.getSize(heightMeasureSpec) - paddingTop - paddingBottom
            val wide = w >= px(640) || w >= px(480) && w > h
            railScroll.visibility = if (wide) VISIBLE else GONE
            railScroll.layoutParams.width = px(if (collapsed) 52 else 144)
            bottom.visibility = if (wide) GONE else VISIBLE
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }
}

internal fun Context.information(title: String, message: String) {
    android.app.AlertDialog.Builder(this).setTitle(title).setMessage(message)
        .setPositiveButton("Done", null).create().apply { escapeDismisses(); show() }
}

internal fun Dialog.escapeDismisses() {
    setOnKeyListener { _, key, event ->
        if (key != android.view.KeyEvent.KEYCODE_ESCAPE) return@setOnKeyListener false
        if (event.action == android.view.KeyEvent.ACTION_UP && !event.isCanceled) cancel()
        true
    }
}

internal fun Context.selectionSheet(title: String, onBack: (() -> Unit)? = null, populate: (LinearLayout, Dialog) -> Unit) {
    val dialog = Dialog(this)
    dialog.setOnCancelListener { onBack?.invoke() }
    dialog.escapeDismisses()
    dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
    val rows = builderRoot().apply {
        background = angularSurface(PANEL_COLOR, CONTROL_OUTLINE)
        setPadding(px(18), px(16), px(18), px(16))
        addView(controllerHeader(heading(title), "Close", dialog::cancel))
    }
    populate(rows, dialog)
    dialog.setContentView(ScrollView(this).apply { addView(rows) })
    dialog.show()
    dialog.window?.apply {
        setBackgroundDrawableResource(android.R.color.transparent)
        setGravity(Gravity.BOTTOM)
        setLayout(min(resources.displayMetrics.widthPixels, px(600)), -2)
    }
}

internal fun Context.controlPicker(types: List<ControlType>, selected: (ControlType) -> Unit) {
    selectionSheet("Add control") { rows, dialog ->
        val groups = listOf(
            Triple("Buttons", R.drawable.ic_controller_gamepad, listOf(ControlType.BUTTON, ControlType.DPAD)),
            Triple("Analog", R.drawable.ic_controller_settings, listOf(ControlType.JOYSTICK, ControlType.TRIGGER, ControlType.SLIDER)),
            Triple("Pointer and motion", R.drawable.ic_controller_phone, listOf(ControlType.TOUCHPAD, ControlType.TOUCH_LOOK, ControlType.TILT)),
            Triple("Profile actions", R.drawable.ic_controller_layers, listOf(ControlType.PROFILE_SWITCH)))
        for ((name, icon, members) in groups) {
            rows.addView(hint(name))
            for (pair in members.filter { it in types }.chunked(2)) {
                rows.addView(actionRow(*pair.map { type ->
                    builderButton(type.title, icon = icon) { dialog.dismiss(); selected(type) }
                }.toTypedArray()))
            }
        }
    }
}

internal class LayoutPreview(context: Context) : FrameLayout(context) {
    private val miniature = ControllerCanvas(context, editing = false, miniature = true)
    var profile: ControllerProfile? = null
        set(value) {
            field = value
            miniature.originalAppearance = value?.let { !it.builtIn && it.hybrid?.originalAppearance == true } == true
            miniature.render(value?.controls.orEmpty())
            contentDescription = value?.let { "${it.orientation.title} layout preview of ${it.name}" }
            requestLayout()
        }

    init {
        background = context.angularSurface(PANEL_COLOR, CANVAS_ACCENT)
        setPadding(context.px(3), context.px(3), context.px(3), context.px(3))
        addView(miniature.apply { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }, LayoutParams(-1, -1))
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        val display = resources.displayMetrics
        val longSide = maxOf(display.widthPixels, display.heightPixels).coerceAtLeast(1)
        val shortSide = minOf(display.widthPixels, display.heightPixels).coerceAtLeast(1)
        val aspect = if (profile?.orientation == ProfileOrientation.PORTRAIT) shortSide.toFloat() / longSide
            else longSide.toFloat() / shortSide
        val fittedWidth = min(w - paddingLeft - paddingRight, ((h - paddingTop - paddingBottom) * aspect).roundToInt()).coerceAtLeast(0)
        val fittedHeight = (fittedWidth / aspect).roundToInt()
        miniature.measure(MeasureSpec.makeMeasureSpec(fittedWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(fittedHeight, MeasureSpec.EXACTLY))
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val x = (width - miniature.measuredWidth) / 2
        val y = (height - miniature.measuredHeight) / 2
        miniature.layout(x, y, x + miniature.measuredWidth, y + miniature.measuredHeight)
    }
}

/** A preview with a persistent grab handle and a bounded, finger-driven height. */
internal class PreviewDrawer(context: Context, preview: LayoutPreview) : LinearLayout(context) {
    private data class Drag(val y: Float, val height: Int, val velocity: VelocityTracker, var moved: Boolean = false)
    private var drag: Drag? = null
    private var maximum = context.px(172)
    private var expanded = true
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val minVelocity = ViewConfiguration.get(context).scaledMinimumFlingVelocity * 4
    private val handle = object : FrameLayout(context) {
        override fun performClick() = super.performClick()
    }.apply {
        isFocusable = true
        setOnClickListener { settle(!expanded) }
        addView(View(context).apply {
            background = context.roundedSurface(CANVAS_TEXT, 2)
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }, FrameLayout.LayoutParams(context.px(36), context.px(3), Gravity.CENTER))
    }
    private val area = preview
    val contentHeight: Int get() = area.layoutParams.height

    init {
        orientation = VERTICAL
        addView(handle, LayoutParams(-1, context.px(48)))
        addView(area, LayoutParams(-1, maximum))
        settle(true)
        handle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    drag?.velocity?.recycle()
                    drag = Drag(event.rawY, area.layoutParams.height, VelocityTracker.obtain())
                    drag?.velocity?.trackScreenPosition(event)
                }
                MotionEvent.ACTION_MOVE -> drag?.let {
                    it.velocity.trackScreenPosition(event)
                    val dy = (event.rawY - it.y).roundToInt()
                    it.moved = it.moved || abs(dy) > slop
                    if (it.moved) resize((it.height - dy).coerceIn(0, maximum))
                }
                MotionEvent.ACTION_UP -> drag?.let {
                    it.velocity.trackScreenPosition(event)
                    it.velocity.computeCurrentVelocity(1000)
                    val velocity = it.velocity.yVelocity
                    if (!it.moved) handle.performClick()
                    else settle(if (abs(velocity) > minVelocity) velocity < 0 else area.layoutParams.height * 2 >= maximum)
                    endDrag()
                }
                MotionEvent.ACTION_CANCEL -> {
                    settle(expanded)
                    endDrag()
                }
            }
            true
        }
    }

    fun boundHeight(height: Int) {
        val next = height.coerceIn(context.px(110), context.px(210))
        if (next != maximum) {
            maximum = next
            if (drag == null) settle(expanded)
        }
    }

    private fun VelocityTracker.trackScreenPosition(event: MotionEvent) {
        val copy = MotionEvent.obtain(event)
        copy.offsetLocation(event.rawX - event.x, event.rawY - event.y)
        addMovement(copy)
        copy.recycle()
    }

    private fun endDrag() {
        drag?.velocity?.recycle()
        drag = null
        parent?.requestDisallowInterceptTouchEvent(false)
    }

    private fun resize(height: Int) {
        if (area.layoutParams.height == height) return
        area.layoutParams = LayoutParams(-1, height)
        area.visibility = if (height == 0) GONE else VISIBLE
    }

    private fun settle(open: Boolean) {
        expanded = open
        resize(if (open) maximum else 0)
        handle.contentDescription = if (open) "Hide controller preview" else "Show controller preview"
    }
}

internal class OrientationGauge(context: Context) : View(context) {
    private val pen = Paint(Paint.ANTI_ALIAS_FLAG)
    private val needle = Path()
    private var heading: Float? = null
    private var stale = false
    private var rotation = 0
    fun pose(q: MotionQuaternion?, lastKnown: Boolean = false) {
        val value = q?.headingDegrees()?.toFloat()
        val currentRotation = display?.rotation ?: 0
        if (value == heading && stale == lastKnown && rotation == currentRotation && contentDescription != null) return
        heading = value
        stale = lastKnown
        rotation = currentRotation
        contentDescription = value?.let {
            "${if (lastKnown) "Last known heading" else "Heading"} ${it.roundToInt() % 360} degrees"
        } ?: "Heading unavailable"
        invalidate()
    }
    override fun onDraw(canvas: Canvas) {
        val radius = min(width, height) * .40f
        canvas.translate(width / 2f, height / 2f)
        pen.color = PANEL_COLOR
        canvas.drawCircle(0f, 0f, radius, pen)
        pen.color = MUTED_TEXT
        repeat(24) { canvas.drawLine(0f, -radius, 0f, -radius * .92f, pen); canvas.rotate(15f) }
        canvas.rotate((heading ?: return) - rotation * 90f)
        pen.color = if (stale) MUTED_TEXT else CANVAS_ACCENT
        canvas.drawPath(needle.apply { rewind(); moveTo(0f, -radius*.83f); lineTo(radius*.19f, radius*.35f)
            lineTo(0f, radius*.16f); lineTo(-radius*.19f, radius*.35f); close() }, pen)
    }
}

internal enum class ReceiverState { UNCHECKED, CHECKING, REACHABLE, UNREACHABLE }

/** A diagram of receiver reachability, independent of the controller session. */
internal class LinkDiagram(context: Context) : View(context) {
    private val pen = Paint(Paint.ANTI_ALIAS_FLAG)
    private var surface: LinearGradient? = null
    private val dashed = android.graphics.DashPathEffect(floatArrayOf(8f, 8f), 0f)
    var state = ReceiverState.UNCHECKED
        set(value) { field = value; invalidate() }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (w > 0) surface = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(),
            PANEL_COLOR, SELECTED_SURFACE, Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        pen.style = Paint.Style.FILL
        pen.shader = surface
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), 22f, 22f, pen)
        pen.shader = null
        canvas.save()
        val scale = min(width/300f, height/100f)
        canvas.translate(width/2f, height/2f)
        canvas.scale(scale, scale)
        pen.style = Paint.Style.STROKE
        pen.strokeWidth = 2.4f
        pen.color = CANVAS_ACCENT
        canvas.drawRoundRect(-112f, -27f, -84f, 27f, 5f, 5f, pen)
        canvas.drawLine(-104f, 21f, -92f, 21f, pen)
        pen.pathEffect = if (state == ReceiverState.REACHABLE) null else dashed
        canvas.drawLine(-74f, 0f, 60f, 0f, pen)
        pen.pathEffect = null
        pen.color = CANVAS_TEXT
        canvas.drawRoundRect(68f, -23f, 123f, 14f, 4f, 4f, pen)
        canvas.drawLine(95f, 14f, 95f, 24f, pen)
        canvas.drawLine(82f, 24f, 108f, 24f, pen)
        canvas.restore()
    }
}
