package com.compasslink3d

import android.content.Context
import android.view.MotionEvent
import kotlin.math.abs
import kotlin.math.max

internal class ControllerEditorCanvas(
    context: Context,
    private val select: (String?) -> Unit,
    private val change: (LayoutControl, Boolean) -> Unit,
) : ControllerCanvas(context, editing = true) {
    private enum class Gesture { MOVE, RESIZE, ROTATE }
    private data class Drag(
        val pointer: Int,
        val gesture: Gesture,
        val start: LayoutPoint,
        val original: LayoutControl,
        val box: ControlBox,
        var current: LayoutControl,
    )

    var uniformResize = false
    private var drag: Drag? = null

    init {
        contentDescription = "Controller layout canvas"
    }

    fun selectControl(id: String?) { selectedId = id }
    fun clamp(control: LayoutControl) = viewport?.clamp(control) ?: control
    fun cancelDrag() {
        val active = drag ?: return
        drag = null
        change(active.original, true)
    }

    fun overlap(control: LayoutControl): Double {
        val box = viewport?.box(control) ?: return 0.0
        return physical.sumOf { (_, existing) ->
            val width = minOf(box.center.x + box.halfX, existing.center.x + existing.halfX) -
                maxOf(box.center.x - box.halfX, existing.center.x - existing.halfX)
            val height = minOf(box.center.y + box.halfY, existing.center.y + existing.halfY) -
                maxOf(box.center.y - box.halfY, existing.center.y - existing.halfY)
            width.coerceAtLeast(0.0) * height.coerceAtLeast(0.0)
        }
    }

    override fun onPhysicalLayout() {
        val active = drag ?: return
        if (viewport == null || physical.none { it.model.id == active.original.id }) drag = null
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val index = event.actionIndex
        fun point(i: Int) = LayoutPoint(event.getX(i).toDouble(), event.getY(i).toDouble())
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val position = point(index)
                val selected = physical.firstOrNull { it.model.id == selectedId }
                val handleRadius = dp(22)
                val gesture = when {
                    selected != null && (position - resizeHandle(selected.box)).length <= handleRadius -> Gesture.RESIZE
                    selected != null && (position - rotateHandle(selected.box)).length <= handleRadius -> Gesture.ROTATE
                    else -> Gesture.MOVE
                }
                val hit = if (gesture == Gesture.MOVE) {
                    physical.lastOrNull { it.contains(position) }
                } else selected
                select(hit?.model?.id ?: models.firstOrNull { it.type == ControlType.TOUCH_LOOK }?.id)
                if (hit != null) drag = Drag(event.getPointerId(index), gesture, position, hit.model, hit.box, hit.model)
            }
            MotionEvent.ACTION_MOVE -> {
                val active = drag ?: return true
                val owner = event.findPointerIndex(active.pointer)
                if (owner >= 0) update(active, point(owner))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val active = drag ?: return true
                if (event.getPointerId(index) == active.pointer) {
                    if (!event.isCanceledRelease()) {
                        update(active, point(index))
                        change(active.current, true)
                        if (active.current == active.original) performClick()
                    } else change(active.original, true)
                    drag = null
                }
            }
            MotionEvent.ACTION_CANCEL -> cancelDrag()
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    private fun update(drag: Drag, position: LayoutPoint) {
        val area = viewport ?: return
        if (position == drag.start && drag.current == drag.original) return
        val box = drag.box
        val model = drag.original
        val original = model.geometry
        val geometry = when (drag.gesture) {
            Gesture.MOVE -> {
                val center = box.center + (position - drag.start)
                original.copy(x = center.x / area.width, y = center.y / area.height)
            }
            Gesture.ROTATE -> {
                val degrees = (position - box.center).angle - (drag.start - box.center).angle
                original.copy(rotation = wrapDegrees(box.rotation + degrees))
            }
            Gesture.RESIZE -> {
                val delta = box.local(position) - box.local(drag.start)
                var w = (box.width + 2 * delta.x).coerceIn(dp(24), max(dp(24), area.width.toDouble()))
                var h = (box.height + 2 * delta.y).coerceIn(dp(24), max(dp(24), area.height.toDouble()))
                if (uniformResize || model.round) {
                    val wScale = w / box.width
                    val hScale = h / box.height
                    val scale = (if (abs(wScale - 1) > abs(hScale - 1)) wScale else hScale)
                        .coerceAtMost(minOf(area.width / box.width, area.height / box.height))
                    w = box.width * scale
                    h = box.height * scale
                }
                original.copy(width = w / area.width, height = h / area.height)
            }
        }
        drag.current = area.clamp(model.copy(geometry = geometry))
        change(drag.current, false)
    }
}

internal class EditorHistory<T>(initial: T) {
    private val past = ArrayDeque<T>()
    private val future = ArrayDeque<T>()
    var current: T = initial
        private set
    val canUndo get() = past.isNotEmpty()
    val canRedo get() = future.isNotEmpty()

    fun commit(next: T) {
        if (next == current) return
        if (past.size == 40) past.removeFirst()
        past.addLast(current)
        current = next
        future.clear()
    }

    fun undo() {
        if (!canUndo) return
        future.addLast(current)
        current = past.removeLast()
    }

    fun redo() {
        if (!canRedo) return
        past.addLast(current)
        current = future.removeLast()
    }
}
