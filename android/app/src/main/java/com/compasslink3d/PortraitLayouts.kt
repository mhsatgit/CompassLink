package com.compasslink3d

import android.content.res.Configuration

internal val ProfileOrientation.title: String
    get() = if (this == ProfileOrientation.PORTRAIT) "Portrait" else "Landscape"

internal val Configuration.layoutOrientation: ProfileOrientation
    get() = if (orientation == Configuration.ORIENTATION_LANDSCAPE) ProfileOrientation.LANDSCAPE
        else ProfileOrientation.PORTRAIT

internal val ControllerProfile.usesTilt: Boolean
    get() = controls.any { it.type == ControlType.TILT && it.style.visible } ||
        (motion?.enabled == true && motion.source == MotionSource.ORIENTATION)

internal fun ControllerProfile.inferredPolicy(preferred: ProfileOrientation = orientation) = when {
    !usesTilt -> OrientationPolicy.SYSTEM
    preferred == ProfileOrientation.PORTRAIT -> OrientationPolicy.PORTRAIT
    else -> OrientationPolicy.LANDSCAPE
}

internal val ControllerProfile.orientationPolicy get() = hybrid?.orientation ?: inferredPolicy()
internal val ControllerProfile.lockedOrientation get() = orientationPolicy.locked
internal val ControllerProfile.legacyOrientationChoice get() = hybrid == null && usesTilt

internal fun ControllerProfile.layoutFor(orientation: ProfileOrientation): ControllerProfile {
    if (this.orientation == orientation) return this
    val positions = if (orientation == ProfileOrientation.PORTRAIT) {
        when (id) {
            DEFAULT_PROFILE_ID -> keyboardPortrait
            XBOX_PROFILE.id -> xboxPortrait
            TILT_RACING_PROFILE.id -> tiltPortrait
            else -> emptyMap()
        }
    } else emptyMap()
    return copy(orientation = orientation, controls = controls.map { control ->
        positions[control.id]?.let { control.copy(geometry = it) } ?: control
    })
}

private val keyboardPortrait = mapOf(
    "DPAD_UP" to ControlGeometry(.28, .52, .21, .13),
    "DPAD_LEFT" to ControlGeometry(.15, .69, .20, .13),
    "DPAD_RIGHT" to ControlGeometry(.41, .69, .20, .13),
    "DPAD_DOWN" to ControlGeometry(.28, .86, .21, .13),
    "ACTION_B" to ControlGeometry(.72, .82, .22, .15),
    "ACTION_A" to ControlGeometry(.82, .60, .24, .16),
)

private val xboxPortrait = mapOf(
    "LEFT_TRIGGER" to ControlGeometry(.24, .10, .38, .12),
    "RIGHT_TRIGGER" to ControlGeometry(.76, .10, .38, .12),
    "LB" to ControlGeometry(.24, .225, .38, .09),
    "RB" to ControlGeometry(.76, .225, .38, .09),
    "BACK" to ControlGeometry(.32, .345, .28, .08),
    "START" to ControlGeometry(.68, .345, .28, .08),
    "LEFT_STICK" to ControlGeometry(.25, .52, .42, .21),
    "RIGHT_STICK" to ControlGeometry(.75, .52, .42, .23),
    "L3" to ControlGeometry(.25, .69, .27, .08),
    "R3" to ControlGeometry(.75, .69, .27, .08),
    "DPAD" to ControlGeometry(.25, .855, .42, .20),
    "Y" to ControlGeometry(.75, .79, .145, .085),
    "X" to ControlGeometry(.595, .866, .145, .085),
    "B" to ControlGeometry(.905, .866, .145, .085),
    "A" to ControlGeometry(.75, .94, .145, .085),
)

private val tiltPortrait = mapOf(
    "TILT" to ControlGeometry(.50, .44, .46, .14),
    "W" to ControlGeometry(.75, .77, .42, .21),
    "S" to ControlGeometry(.25, .77, .42, .21),
)
