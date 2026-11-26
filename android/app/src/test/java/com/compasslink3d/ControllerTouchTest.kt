package com.compasslink3d

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ControllerTouchTest {
    @Test fun accessibleDirectionSharesDigitalOwnershipAndAnalogPulseReleases() {
        val events = mutableListOf<ControllerInput>()
        val touch = ControllerTouchEngine(1.0, events::add, OutputMode.HYBRID)
        val dpad = newControl(ControlType.DPAD)
        val axes = newControl(ControlType.JOYSTICK).copy(
            bindings = mapOf(Slot.AXIS to Binding(BindingType.GAMEPAD_AXIS, "LEFT_STICK")))
        val area = ControllerViewport(600, 400, 48.0)
        val buttons = PhysicalControl(dpad, area.box(dpad))
        val stick = PhysicalControl(axes, area.box(axes))
        touch.controls = listOf(buttons)
        val up = buttons.box.world(LayoutPoint(0.0, -buttons.box.height * .4))
        touch.down(1, up, 0L)
        assertTrue(touch.accessiblePress(buttons, Slot.UP, null, 1L))
        touch.accessibleRelease(2L)
        assertTrue(touch.active(dpad.id, Slot.UP))
        touch.up(1, up, 3L, false)
        assertEquals(listOf(true, false), events.filterIsInstance<ControllerInput.Digital>().map { it.down })
        assertTrue(touch.accessiblePress(stick, Slot.RIGHT, null, 4L))
        assertEquals(1.0, touch.value(axes.id).x, 1e-8)
        touch.accessibleRelease(5L)
        assertEquals(LayoutPoint(0.0, 0.0), touch.value(axes.id))
    }
    @Test fun motionSubpixelsSurviveBetweenSamplesAndClearPerSource() {
        val reports = MotionPointerReports()
        val queue = TouchPointerQueue()
        val slow = ControllerInput.Pointer("gyro", LayoutPoint(.25, -.25))
        var total = 0
        repeat(100) { tick ->
            reports.quantize(slow, tick * 10L)?.let {
                total += it.dx
                assertEquals(-it.dx, it.dy)
                assertEquals(tick * 10L, it.sampledAt)
            }
        }
        assertEquals(25, total)
        reports.quantize(slow, 1010)
        reports.discard("gyro")
        assertEquals(null, reports.quantize(slow, 1020))
        queue.offer(ControllerInput.TouchPointer("gyro", 1, 1, 1030))
        queue.offer(ControllerInput.TouchPointer("touch", 1, 1, 1031))
        queue.discard("gyro")
        assertEquals("touch", queue.poll(1032)?.control)
        assertEquals(null, queue.poll(1032))
    }

    @Test fun shoulderKeepsNormalOutputAndGyroHoldUntilTheFinalTouchReleases() {
        val shoulder = newControl(ControlType.BUTTON).copy(bindings = mapOf(Slot.PRESS to Binding(BindingType.GAMEPAD, "RB")))
        val events = mutableListOf<ControllerInput>()
        val motion = MotionProcessor(MotionSettings(source = MotionSource.GYROSCOPE, mapping = MotionMapping.MOUSE),
            "gyro", events::add)
        val time = 1_000_000_000L
        val rate = MotionVector(0.0, 0.0, -3.0)
        motion.gyro(rate, time, 0)
        var presses = 0
        val touch = ControllerTouchEngine(1.0, { event ->
            events.add(event)
            if (event is ControllerInput.Digital) motion.activate(event.down, time + 30_000_000)
        }, OutputMode.HYBRID, onPress = { presses++ })
        val center = LayoutPoint(150.0, 150.0)
        touch.controls = listOf(PhysicalControl(shoulder, ControlBox(center, 100.0, 100.0, 0.0)))
        touch.down(1, center, 1)
        touch.down(2, center, 2)
        touch.move(2, center, 3)
        motion.gyro(rate, time + 20_000_000, 0)
        motion.gyro(rate, time + 30_000_000, 0)
        touch.up(1, center, 4, false)
        assertTrue(motion.gyroEnabled)
        assertTrue(events.any { it is ControllerInput.Pointer })
        touch.up(2, center, 5, false)
        assertFalse(motion.gyroEnabled)
        assertEquals(1, presses)
        assertEquals(listOf(true, false), events.filterIsInstance<ControllerInput.Digital>().map { it.down })
    }

    @Test fun switchActionsFireOnceAndClearingLayoutDiscardsOldGesturesAndPendingMovement() {
        val sw = newControl(ControlType.PROFILE_SWITCH)
        val right = LayoutPoint(250.0, 150.0)
        val left = LayoutPoint(100.0, 150.0)
        val pad = newControl(ControlType.TOUCHPAD)
        val events = mutableListOf<ControllerInput>()
        val queue = ContinuousInput()
        val pointerQueue = TouchPointerQueue()
        val switches = mutableListOf<String>()
        val touch = ControllerTouchEngine(1.0, { event ->
            when (event) {
                ControllerInput.Neutral -> {
                    queue.clear()
                    pointerQueue.clear()
                }
                is ControllerInput.TouchPointer -> pointerQueue.offer(event)
                else -> queue.offer(event)
            }
            events.add(event)
        }, OutputMode.HYBRID, onSwitch = switches::add)
        touch.controls = listOf(
            PhysicalControl(sw, ControlBox(right, 70.0, 100.0, 0.0)),
            PhysicalControl(pad, ControlBox(left, 100.0, 100.0, 0.0)),
        )
        touch.down(1, left, 1)
        touch.move(1, left + LayoutPoint(25.0, 0.0), 20)
        touch.down(2, right, 21)
        touch.move(2, right, 22)
        touch.down(3, right, 23)
        assertEquals(listOf(sw.id), switches)
        assertTrue(pointerQueue.hasPending)
        queue.offer(ControllerInput.Pointer("gyro", LayoutPoint(10.0, 0.0)))
        touch.clear(notify = true)
        touch.controls = emptyList()
        touch.move(1, left, 24)
        touch.up(2, right, 25, false)
        assertTrue(queue.drain().isEmpty())
        assertFalse(pointerQueue.hasPending)
        assertEquals(ControllerInput.Neutral, events.last())
    }
    @Test fun normalizedRotatedControlsFitAndHitTestAtMultipleSizes() {
        val control = newControl(ControlType.BUTTON).copy(geometry = ControlGeometry(.1, .9, .4, .6, 45.0))
        for ((width, height) in listOf(960 to 480, 480 to 880, 1600 to 530)) {
            val viewport = ControllerViewport(width, height, 48.0)
            val box = viewport.box(control)
            assertTrue(box.center.x >= box.halfX)
            assertTrue(box.center.y + box.halfY <= height + 1e-9)
            val interior = LayoutPoint(box.width / 3, 0.0)
            assertTrue(box.contains(box.world(interior), false))
            assertFalse(box.contains(box.world(LayoutPoint(box.width * .52, 0.0)), false))
            val corner = box.world(LayoutPoint(box.width / 2 - 1, box.height / 2 - 1))
            assertTrue(box.contains(corner, false))
            assertFalse(box.contains(corner, false, 20.0))
            assertTrue(box.width >= 48.0 && box.height >= 48.0)
            val circular = viewport.box(newControl(ControlType.JOYSTICK))
            assertEquals(circular.width, circular.height, 1e-9)
            val normalized = viewport.clamp(control)
            val projected = viewport.box(normalized)
            assertTrue(normalized.geometry.x in 0.0..1.0 && normalized.geometry.y in 0.0..1.0)
            assertEquals(box.center.x, projected.center.x, 1e-8)
            assertEquals(box.center.y, projected.center.y, 1e-8)
            assertEquals(box.width, projected.width, 1e-8)
            assertEquals(box.height, projected.height, 1e-8)
        }
    }

    @Test fun dpadDiagonalsJoystickDeadZoneAndHysteresis() {
        assertEquals(setOf(Slot.UP, Slot.RIGHT), dpadSlots(.8, -.9))
        assertTrue(dpadSlots(.12, -.2).isEmpty())
        val settings = ControlSettings(threshold = .5, hysteresis = .1)
        assertEquals(LayoutPoint(0.0, 0.0), joystickAxes(LayoutPoint(0.0, 4.0), 50.0, .20))
        assertEquals(setOf(Slot.UP, Slot.LEFT), joystickSlots(
            joystickAxes(LayoutPoint(-80.0, -80.0), 50.0, .20), emptySet(), settings,
        ))
        val fringe = LayoutPoint(0.0, -.45)
        assertEquals(setOf(Slot.UP), joystickSlots(fringe, setOf(Slot.UP), settings))
        assertTrue(joystickSlots(fringe, emptySet(), settings).isEmpty())
    }

    @Test fun pointerOwnershipReleasesOnlyAfterLastContactAndResolvesOverlapFromTop() {
        val bottom = newControl(ControlType.BUTTON)
        val top = newControl(ControlType.BUTTON)
        val events = mutableListOf<ControllerInput>()
        val engine = ControllerTouchEngine(1.0, events::add)
        val box = ControlBox(LayoutPoint(150.0, 150.0), 100.0, 100.0, 0.0)
        engine.controls = listOf(PhysicalControl(bottom, box), PhysicalControl(top, box))
        engine.down(0, box.center, 1)
        engine.down(1, box.center, 2)
        engine.up(1, box.center, 3, canceled = false)
        assertEquals(listOf(ControllerInput.Digital(top.id, Slot.PRESS, true)), events)
        engine.move(0, LayoutPoint(0.0, 0.0))
        assertEquals(ControllerInput.Digital(top.id, Slot.PRESS, false), events.last())
        engine.clear(notify = true)
        assertEquals(ControllerInput.Neutral, events.last())
    }

    @Test fun touchpadInvertsAxesAndSlidersReleaseOnLift() {
        val events = mutableListOf<ControllerInput>()
        val engine = ControllerTouchEngine(2.0, events::add)
        val box = ControlBox(LayoutPoint(150.0, 150.0), 200.0, 200.0, 0.0)
        val pad = newControl(ControlType.TOUCHPAD).copy(settings = ControlSettings(sensitivity = 2.5, invertX = true))
        engine.controls = listOf(PhysicalControl(pad, box))
        engine.down(0, box.center, 10)
        engine.move(0, LayoutPoint(190.0, 170.0), 110)
        engine.up(0, LayoutPoint(190.0, 170.0), 114, canceled = false)
        assertEquals(listOf(ControllerInput.TouchPointer(pad.id, -50, 25, 110)), events)
        engine.down(0, box.center, 120)
        engine.up(0, box.center, 130, canceled = false)
        assertEquals(listOf(ControllerInput.Digital(pad.id, Slot.TAP, true),
            ControllerInput.Digital(pad.id, Slot.TAP, false)), events.takeLast(2))
        events.clear()
        val slider = newControl(ControlType.SLIDER).copy(
            bindings = mapOf(Slot.PRESS to Binding(BindingType.KEYBOARD, "ENTER"), Slot.WHEEL to Binding(BindingType.WHEEL)),
        )
        engine.controls = listOf(PhysicalControl(slider, box))
        engine.down(1, box.center, 40)
        engine.move(1, LayoutPoint(220.0, 150.0))
        engine.up(1, LayoutPoint(220.0, 150.0), 60, canceled = false)
        assertTrue(events.any { it is ControllerInput.Wheel && it.ticks > 0 })
        assertEquals(listOf(ControllerInput.Digital(slider.id, Slot.PRESS, true),
            ControllerInput.Digital(slider.id, Slot.PRESS, false)), events.filterIsInstance<ControllerInput.Digital>())
    }

    @Test fun continuousInputCoalescesAndKeepsFractionalDeltas() {
        val buffer = ContinuousInput()
        buffer.offer(ControllerInput.Pointer("p", LayoutPoint(.7, -.3)))
        buffer.offer(ControllerInput.Pointer("p", LayoutPoint(.8, -.9)))
        buffer.offer(ControllerInput.Axis("a", LayoutPoint(.5, -.5)))
        buffer.offer(ControllerInput.Axis("a", LayoutPoint(0.0, 1.0)))
        buffer.offer(ControllerInput.Wheel("w", .8))
        assertEquals(listOf(ControllerInput.Axis("a", LayoutPoint(0.0, 1.0)),
            ControllerInput.Pointer("p", LayoutPoint(1.0, -1.0))), buffer.drain())
        buffer.offer(ControllerInput.Wheel("w", .4))
        assertEquals(listOf(ControllerInput.Wheel("w", 1.0)), buffer.drain())
        buffer.clear()
        assertTrue(buffer.drain().isEmpty())
    }

    @Test fun fullScreenLookStartsOnFreeSpaceAndSharesPlayWithButtonsWithoutCreatingGestures() {
        val layer = newControl(ControlType.TOUCH_LOOK)
        val button = newControl(ControlType.BUTTON)
        val events = mutableListOf<ControllerInput>()
        val engine = ControllerTouchEngine(1.0, events::add)
        val center = LayoutPoint(200.0, 200.0)
        engine.backgroundLook = PhysicalControl(layer, ControlBox(center, 400.0, 400.0, 0.0))
        engine.controls = listOf(PhysicalControl(button, ControlBox(center, 90.0, 90.0, 0.0)))

        engine.down(1, center, 10)
        engine.down(2, LayoutPoint(100.0, 200.0), 11)
        engine.down(3, LayoutPoint(120.0, 220.0), 12)
        engine.move(2, LayoutPoint(110.0, 190.0), 21)
        engine.move(3, LayoutPoint(90.0, 230.0), 22)
        engine.move(2, center, 31)
        engine.up(2, center, 33, false)
        engine.up(3, LayoutPoint(90.0, 230.0), 34, false)
        engine.up(1, center, 35, false)
        assertEquals(listOf(true, false), events.filterIsInstance<ControllerInput.Digital>().map {
            assertEquals(button.id, it.control)
            it.down
        })
        val moves = events.filterIsInstance<ControllerInput.TouchPointer>()
        assertEquals(2, moves.size)
        assertTrue(moves.all { it.control == layer.id && it.dx > 0 })
        assertTrue(moves.first().dy < 0)
        assertFalse(events.any { it is ControllerInput.Wheel })
    }

    @Test fun pointerUsesTimestampedSpeedAndCarriesSmallMovesWithinOneStroke() {
        val layer = newControl(ControlType.TOUCH_LOOK)
        val zero = LayoutPoint(0.0, 0.0)
        val tracker = TouchPointer(layer, 1.0, zero, 100)
        val small = (1..10).mapNotNull { step ->
            tracker.move(LayoutPoint(step * .28, step * -.28), 100 + step * 10L)
        }
        assertEquals(1, small.sumOf { it.dx })
        assertEquals(-1, small.sumOf { it.dy })
        val slow = TouchPointer(layer, 2.0, zero, 100)
        val fast = TouchPointer(layer, 2.0, zero, 100)
        assertEquals(10, slow.move(LayoutPoint(40.0, 0.0), 200)?.dx)
        assertEquals(null, fast.move(LayoutPoint(20.0, 0.0), 100))
        assertEquals(40, fast.move(LayoutPoint(40.0, 0.0), 105)?.dx)
        assertEquals(null, fast.move(LayoutPoint(40.0, 0.0), 110))
        assertEquals(null, TouchPointer(layer, 1.0, zero, 100).move(LayoutPoint(.28, -.28), 110))
    }

    @Test fun touchReportsStaySeparateAndDropOverflowAndStaleSamples() {
        val pending = TouchPointerQueue()
        for (sample in 1..60) pending.offer(ControllerInput.TouchPointer("look", sample, 0, sample.toLong()))
        assertEquals((13..60).toList(), generateSequence { pending.poll(60) }.map { it.dx }.toList())
        pending.offer(ControllerInput.TouchPointer("look", 1, 0, 61))
        pending.offer(ControllerInput.TouchPointer("look", 2, 0, 150))
        assertEquals(2, pending.poll(150)?.dx)
        assertEquals(null, pending.poll(150))
    }

    @Test fun xboxMultitouchProducesIndependentAxesAndDigitalTransitions() {
        val events = mutableListOf<ControllerInput>()
        val engine = ControllerTouchEngine(1.0, events::add, OutputMode.GAMEPAD)
        val viewport = ControllerViewport(920, 300, 48.0)
        engine.controls = XBOX_PROFILE.controls.map { PhysicalControl(it, viewport.box(it)) }
        fun box(id: String) = engine.controls.single { it.model.id == id }.box
        val left = box("LEFT_STICK")
        val right = box("RIGHT_STICK")
        val lt = box("LEFT_TRIGGER")
        val rt = box("RIGHT_TRIGGER")
        val dpad = box("DPAD")
        engine.down(1, left.center, 1)
        engine.down(2, right.center, 1)
        assertTrue(events.isEmpty())
        engine.move(1, left.world(LayoutPoint(0.0, -left.width * .45)))
        engine.move(2, right.world(LayoutPoint(right.width * .45, 0.0)))
        engine.down(3, lt.center, 2)
        engine.down(4, rt.center, 2)
        engine.move(3, lt.world(LayoutPoint(0.0, lt.height)))
        engine.move(4, rt.world(LayoutPoint(0.0, rt.height / 4)))
        engine.down(5, dpad.world(LayoutPoint(dpad.width / 4, -dpad.height / 4)), 3)
        val faces = listOf("A", "B", "X", "Y")
        faces.forEachIndexed { index, key -> engine.down(index + 6, box(key).center, 4) }
        assertEquals(-1.0, engine.value("LEFT_STICK").y, 1e-9)
        assertEquals(1.0, engine.value("RIGHT_STICK").x, 1e-9)
        assertEquals(1.0, engine.value("LEFT_TRIGGER").x, 1e-9)
        assertEquals(.75, engine.value("RIGHT_TRIGGER").x, 1e-9)
        assertEquals(setOf(Slot.UP, Slot.RIGHT), events.filterIsInstance<ControllerInput.Digital>()
            .filter { it.control == "DPAD" && it.down }.map { it.slot }.toSet())
        assertTrue(faces.all { ControllerInput.Digital(it, Slot.PRESS, true) in events })
        engine.up(1, left.center, 5, canceled = true)
        engine.up(4, rt.center, 5, canceled = false)
        assertEquals(ControllerInput.Axis("RIGHT_TRIGGER", LayoutPoint(0.0, 0.0)), events.last())
        assertEquals(LayoutPoint(0.0, 0.0), engine.value("LEFT_STICK"))
        assertEquals(1.0, engine.value("RIGHT_STICK").x, 1e-9)
        engine.clear(notify = true)
        assertEquals(ControllerInput.Neutral, events.last())
        assertFalse(engine.active("A"))
        assertEquals(LayoutPoint(0.0, 0.0), engine.value("LEFT_TRIGGER"))
    }

    @Test fun dragLookStartsInEmptySpaceAndStopsWithoutLifting() {
        val events = mutableListOf<ControllerInput>()
        val engine = ControllerTouchEngine(1.0, events::add, OutputMode.GAMEPAD)
        val viewport = ControllerViewport(920, 300, 48.0)
        engine.controls = XBOX_PROFILE.controls.map { PhysicalControl(it, viewport.box(it)) }
        val emptySpace = LayoutPoint(420.0, 110.0)
        val face = engine.controls.first { it.model.id == "A" }.box.center
        engine.down(0, emptySpace, 100)
        assertTrue(events.isEmpty())
        engine.down(1, face, 102)
        engine.move(0, emptySpace + LayoutPoint(8.0, -4.0), 116)
        val value = engine.value("RIGHT_STICK")
        assertTrue(value.x in 0.7..0.8 && value.y < 0)
        assertTrue(engine.active("A"))
        engine.expireMotion(195)
        assertEquals(value, engine.value("RIGHT_STICK"))
        engine.expireMotion(196)
        assertEquals(ControllerInput.Axis("RIGHT_STICK", LayoutPoint(0.0, 0.0)), events.last())
        engine.move(0, emptySpace, 210)
        assertTrue(engine.value("RIGHT_STICK").x < 0)
        engine.up(0, emptySpace, 220, canceled = false)
        assertEquals(ControllerInput.Axis("RIGHT_STICK", LayoutPoint(0.0, 0.0)), events.last())
        assertTrue(engine.active("A"))
    }

    @Test fun tiltArmsAtTheHeldAngleAndReleasesSteeringBeforeChangingDirection() {
        val events = mutableListOf<ControllerInput>()
        val engine = ControllerTouchEngine(1.0, events::add)
        val viewport = ControllerViewport(920, 300, 48.0)
        engine.controls = TILT_RACING_PROFILE.controls.map { PhysicalControl(it, viewport.box(it)) }
        fun center(id: String) = engine.controls.first { it.model.id == id }.box.center
        engine.updateTilt(45.0, 1)
        assertTrue(events.isEmpty())
        engine.down(0, center("TILT"), 10)
        engine.up(0, center("TILT"), 20, canceled = false)
        engine.updateTilt(10.0, 21)
        engine.down(1, center("W"), 23)
        engine.updateTilt(24.0, 30)
        engine.updateTilt(19.0, 31)
        engine.updateTilt(-5.0, 35)
        engine.expireMotion(385)
        engine.up(1, center("W"), 390, canceled = false)
        assertEquals(listOf(
            ControllerInput.Digital("W", Slot.PRESS, true),
            ControllerInput.Digital("TILT", Slot.RIGHT, true),
            ControllerInput.Digital("TILT", Slot.RIGHT, false),
            ControllerInput.Digital("TILT", Slot.LEFT, true),
            ControllerInput.Digital("TILT", Slot.LEFT, false),
            ControllerInput.Digital("W", Slot.PRESS, false),
        ), events)
        assertFalse(engine.tiltEnabled("TILT"))
    }

    @Test fun anExistingRightJoystickAlsoSupportsLookFromEmptySpace() {
        val events = mutableListOf<ControllerInput>()
        val engine = ControllerTouchEngine(1.0, events::add, OutputMode.GAMEPAD)
        val stick = newControl(ControlType.JOYSTICK).copy(
            bindings = mapOf(Slot.AXIS to Binding(BindingType.GAMEPAD_AXIS, "RIGHT_STICK")),
        )
        val box = ControlBox(LayoutPoint(200.0, 120.0), 100.0, 100.0, 0.0)
        engine.controls = listOf(PhysicalControl(stick, box))
        engine.down(0, LayoutPoint(80.0, 50.0), 100)
        assertTrue(events.isEmpty())
        engine.move(0, LayoutPoint(68.0, 50.0), 116)
        assertEquals(-1.0, engine.value(stick.id).x, 1e-9)
        engine.up(0, LayoutPoint(68.0, 50.0), 130, canceled = false)
        assertEquals(LayoutPoint(0.0, 0.0), engine.value(stick.id))
        engine.down(1, LayoutPoint(235.0, 120.0), 140)
        assertTrue(engine.value(stick.id).x > 0.9)
    }

    @Test fun screenRotationKeepsRightEdgeTiltPositiveInBothLandscapeDirections() {
        val half = Math.toRadians(10.0)
        val w = kotlin.math.cos(half).toFloat()
        val offset = kotlin.math.sin(half).toFloat()
        assertEquals(20.0, rightTiltDegrees(w, 0f, offset, 0f, 0), 1e-4)
        assertEquals(20.0, rightTiltDegrees(w, offset, 0f, 0f, 1), 1e-4)
        assertEquals(20.0, rightTiltDegrees(w, 0f, -offset, 0f, 2), 1e-4)
        assertEquals(20.0, rightTiltDegrees(w, -offset, 0f, 0f, 3), 1e-4)
    }
}
