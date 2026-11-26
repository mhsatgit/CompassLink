package com.compasslink3d

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable

internal const val CANVAS_BACKGROUND = 0xFF050506.toInt()
internal const val CANVAS_ACCENT = 0xFFFF3B4E.toInt()
internal const val CANVAS_TEXT = 0xFFF5F5F7.toInt()
internal const val PANEL_COLOR = 0xFF101012.toInt()
internal const val BUTTON_COLOR = 0xFF19191D.toInt()
internal const val MUTED_TEXT = 0xFFB5B5BE.toInt()
internal const val QUIET_OUTLINE = 0xFF34343B.toInt()
internal const val CONTROL_OUTLINE = 0xFF797983.toInt()
internal const val ACTION_FILL = 0xFFC81E36.toInt()
internal const val ACTION_PRESSED = 0xFFA9162C.toInt()
internal const val SELECTED_SURFACE = 0xFF311318.toInt()

internal fun Context.angularSurface(fill: Int, outline: Int = QUIET_OUTLINE): Drawable =
    AngularSurface(fill, outline, px(9).toFloat(), px(1).toFloat())

private class AngularSurface(
    private val fill: Int, private val outline: Int, private val clip: Float, private val stroke: Float,
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shape = Path()

    override fun onBoundsChange(bounds: Rect) {
        val half = stroke / 2
        val left = bounds.left + half
        val top = bounds.top + half
        val right = bounds.right - half
        val bottom = bounds.bottom - half
        val corner = minOf(clip, bounds.width() / 2f, bounds.height() / 2f)
        shape.rewind()
        shape.moveTo(left + corner, top)
        shape.lineTo(right, top)
        shape.lineTo(right, bottom - corner)
        shape.lineTo(right - corner, bottom)
        shape.lineTo(left, bottom)
        shape.lineTo(left, top + corner)
        shape.close()
    }

    override fun draw(canvas: Canvas) {
        paint.style = Paint.Style.FILL
        paint.color = fill
        canvas.drawPath(shape, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = stroke
        paint.color = outline
        canvas.drawPath(shape, paint)
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit
    @Deprecated("Deprecated in Drawable")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
