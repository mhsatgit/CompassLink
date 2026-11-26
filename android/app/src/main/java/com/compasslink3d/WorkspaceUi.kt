package com.compasslink3d

import android.app.Dialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.Paint
import android.graphics.Path
import android.view.Gravity
import android.view.View
import android.view.Window
import android.widget.LinearLayout
import android.widget.ScrollView
import kotlin.math.min

internal enum class Destination(val title: String, val icon: Int) {
    CONNECT("Connect", R.drawable.ic_controller_signal),
    CONTROLLERS("Controllers", R.drawable.ic_controller_gamepad),
    SENSORS("Sensors", R.drawable.ic_controller_phone)
}

internal fun Context.navigationShell(active: Destination, body: View, navigate: (Destination) -> Unit): View {
    fun navigation(rail: Boolean) = LinearLayout(this).apply {
        orientation = if (rail) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        for (next in Destination.entries) {
            val action = builderButton(next.title, active == next, next.icon) {
                if (next != active) navigate(next)
            }.apply { isSelected = active == next }
            val params = if (rail) LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = px(6) }
                else LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = px(3) }
            addView(action, params)
        }
    }

    val rail = navigation(true)
    val bottom = navigation(false)
    val column = builderRoot().apply {
        addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        addView(bottom, LinearLayout.LayoutParams(-1, -2))
    }
    return object : LinearLayout(this) {
        init {
            orientation = HORIZONTAL
            setBackgroundColor(CANVAS_BACKGROUND)
            addView(rail, LayoutParams(px(134), -1).apply { marginEnd = px(12) })
            addView(column, LayoutParams(0, -1, 1f))
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val wide = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight >= px(640)
            rail.visibility = if (wide) VISIBLE else GONE
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
        background = roundedSurface(0xFF182338.toInt(), 22)
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

internal class LayoutPreview(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    var profile: ControllerProfile? = null
        set(value) { field = value; invalidate() }

    override fun onDraw(canvas: Canvas) {
        val layout = profile ?: return
        val aspect = if (layout.orientation == ProfileOrientation.LANDSCAPE) 1.9f else .65f
        val w = min(width.toFloat(), height * aspect)
        val h = w / aspect
        canvas.translate((width - w) / 2, (height - h) / 2)
        paint.color = CANVAS_BACKGROUND
        canvas.drawRoundRect(0f, 0f, w, h, 8f, 8f, paint)
        for (control in layout.controls.filter { it.style.visible && it.type != ControlType.TOUCH_LOOK }) {
            val g = control.geometry
            val x = (g.x * w).toFloat()
            val y = (g.y * h).toFloat()
            val dx = (g.width * w / 2).toFloat()
            val dy = (g.height * h / 2).toFloat()
            paint.color = Color.parseColor(control.style.border)
            canvas.save()
            canvas.rotate(g.rotation.toFloat(), x, y)
            if (control.round) canvas.drawCircle(x, y, min(dx,dy), paint)
            else canvas.drawRoundRect(x-dx, y-dy, x+dx, y+dy, 3f, 3f, paint)
            canvas.restore()
        }
    }
}

internal class OrientationGauge(context: Context) : View(context) {
    private val pen = Paint(Paint.ANTI_ALIAS_FLAG)
    private val needle = Path()
    private var heading: Float? = null
    fun pose(q: MotionQuaternion?) {
        heading = q?.let {
            val east = 2 * (it.x * it.y - it.z * it.w)
            val north = 1 - 2 * (it.x * it.x + it.z * it.z)
            if (kotlin.math.hypot(east, north) <= 1e-5) null else Math.toDegrees(kotlin.math.atan2(east, north)).toFloat()
        }
        contentDescription = heading?.let { "Heading ${String.format(java.util.Locale.getDefault(), "%.0f", (it + 360) % 360)} degrees" }
            ?: "Heading unavailable"
        invalidate()
    }
    override fun onDraw(canvas: Canvas) {
        val radius = min(width, height) * .40f
        canvas.translate(width / 2f, height / 2f)
        pen.color = 0xFF182338.toInt()
        canvas.drawCircle(0f, 0f, radius, pen)
        pen.color = 0xFFADC0DD.toInt()
        repeat(24) { canvas.drawLine(0f, -radius, 0f, -radius * .92f, pen); canvas.rotate(15f) }
        canvas.rotate(heading ?: return)
        pen.color = CANVAS_ACCENT
        canvas.drawPath(needle.apply { rewind(); moveTo(0f, -radius*.83f); lineTo(radius*.19f, radius*.35f)
            lineTo(0f, radius*.16f); lineTo(-radius*.19f, radius*.35f); close() }, pen)
    }
}

/** A diagram of the active transport. Dashes indicate a link that has not been verified. */
internal class LinkDiagram(context: Context) : View(context) {
    private val pen = Paint(Paint.ANTI_ALIAS_FLAG)
    private var surface: LinearGradient? = null
    private val dashed = android.graphics.DashPathEffect(floatArrayOf(8f, 8f), 0f)
    var verified = false
        set(value) { field = value; invalidate() }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (w > 0) surface = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(),
            0xff152840.toInt(), 0xff30294c.toInt(), Shader.TileMode.CLAMP)
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
        pen.pathEffect = if (verified) null else dashed
        canvas.drawLine(-74f, 0f, 60f, 0f, pen)
        pen.pathEffect = null
        pen.color = 0xffbaa2ff.toInt()
        canvas.drawRoundRect(68f, -23f, 123f, 14f, 4f, 4f, pen)
        canvas.drawLine(95f, 14f, 95f, 24f, pen)
        canvas.drawLine(82f, 24f, 108f, 24f, pen)
        canvas.restore()
    }
}
