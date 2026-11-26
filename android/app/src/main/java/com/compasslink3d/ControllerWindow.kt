package com.compasslink3d

import android.graphics.Rect
import android.os.Build
import android.view.View
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager

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
