package com.compasslink3d

import android.graphics.Rect
import android.os.Build
import android.view.View
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import kotlin.math.hypot

@Suppress("DEPRECATION")
internal class ControllerWindow(private val window: Window) {
    init {
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
        if (Build.VERSION.SDK_INT >= 29) {
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
        }
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= 30) {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                } else {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
        }
        if (Build.VERSION.SDK_INT < 30) immersive(false)
    }

    fun immersive(enabled: Boolean) {
        if (Build.VERSION.SDK_INT >= 30) {
            window.decorView.windowInsetsController?.let { controller ->
                controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                if (enabled) controller.hide(WindowInsets.Type.systemBars())
                else controller.show(WindowInsets.Type.systemBars())
            }
        } else {
            val layout = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            window.decorView.systemUiVisibility = if (enabled) {
                layout or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            } else layout
        }
    }

    fun insetContent(view: View, paddingDp: Int, protectSystemBars: Boolean = true, protectBottom: Boolean = true) {
        val padding = view.context.px(paddingDp)
        view.setPadding(padding, padding, padding, padding)
        view.setOnApplyWindowInsetsListener { current, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = if (protectSystemBars) WindowInsets.Type.systemBars() else 0
                val safe = insets.getInsetsIgnoringVisibility(WindowInsets.Type.displayCutout() or bars)
                val bottom = if (protectBottom) safe.bottom else 0
                current.setPadding(padding + safe.left, padding + safe.top, padding + safe.right, padding + bottom)
            } else {
                val cutout = if (Build.VERSION.SDK_INT >= 28) {
                    insets.displayCutout?.let { Rect(it.safeInsetLeft, it.safeInsetTop, it.safeInsetRight, it.safeInsetBottom) }
                } else null
                current.setPadding(
                    padding + maxOf(cutout?.left ?: 0, if (protectSystemBars) insets.systemWindowInsetLeft else 0),
                    padding + maxOf(cutout?.top ?: 0, if (protectSystemBars) insets.systemWindowInsetTop else 0),
                    padding + maxOf(cutout?.right ?: 0, if (protectSystemBars) insets.systemWindowInsetRight else 0),
                    padding + if (protectBottom) maxOf(cutout?.bottom ?: 0,
                        if (protectSystemBars) insets.systemWindowInsetBottom else 0) else 0,
                )
            }
            insets
        }
    }
}

/** Positions window chrome inside the same safe rectangle used by the controller canvas. */
internal fun FrameLayout.addMovableAction(action: View, id: String) {
    val size = context.px(48)
    addView(action, FrameLayout.LayoutParams(size, size, Gravity.TOP or Gravity.START))
    val preferences = context.getSharedPreferences("controller-chrome", android.content.Context.MODE_PRIVATE)
    val slop = ViewConfiguration.get(context).scaledTouchSlop
    fun key() = "$id.${if (width > height) "landscape" else "portrait"}"
    fun travelX() = (width - paddingLeft - paddingRight - size).coerceAtLeast(0)
    fun travelY() = (height - paddingTop - paddingBottom - size).coerceAtLeast(0)
    fun originX() = if (layoutDirection == View.LAYOUT_DIRECTION_RTL) travelX() else 0
    fun place(x: Float, y: Float) {
        action.translationX = x.coerceIn(0f, travelX().toFloat()) - originX()
        action.translationY = y.coerceIn(0f, travelY().toFloat())
    }
    fun restore() = place(
        preferences.getFloat("${key()}.x", 1f) * travelX(),
        preferences.getFloat("${key()}.y", 0f) * travelY(),
    )
    addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> restore() }
    var pointer = MotionEvent.INVALID_POINTER_ID
    var downX = 0f
    var downY = 0f
    var initialX = 0f
    var initialY = 0f
    var dragging = false
    action.setOnTouchListener { button, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pointer = event.getPointerId(0)
                downX = event.rawX
                downY = event.rawY
                initialX = action.translationX + originX()
                initialY = action.translationY
                dragging = false
                button.isPressed = true
            }
            MotionEvent.ACTION_MOVE -> if (event.getPointerId(0) == pointer) {
                val dx = event.rawX - downX
                val dy = event.rawY - downY
                if (!dragging && hypot(dx, dy) > slop) {
                    dragging = true
                    button.isPressed = false
                }
                if (dragging) place(initialX + dx, initialY + dy)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> if (event.getPointerId(event.actionIndex) == pointer) {
                pointer = MotionEvent.INVALID_POINTER_ID
                button.isPressed = false
                if (event.isCanceledRelease()) restore()
                else if (dragging) preferences.edit()
                    .putFloat("${key()}.x", (action.translationX + originX()) / travelX().coerceAtLeast(1))
                    .putFloat("${key()}.y", action.translationY / travelY().coerceAtLeast(1)).apply()
                else button.performClick()
            }
            MotionEvent.ACTION_CANCEL -> {
                pointer = MotionEvent.INVALID_POINTER_ID
                button.isPressed = false
                restore()
            }
        }
        true
    }
}
