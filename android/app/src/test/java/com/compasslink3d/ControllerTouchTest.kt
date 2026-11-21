package com.compasslink3d

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ControllerTouchTest {
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
        engine.move(0, LayoutPoint(190.0, 170.0))
        engine.up(0, LayoutPoint(190.0, 170.0), 14, canceled = false)
        assertEquals(listOf(ControllerInput.Pointer(pad.id, LayoutPoint(-50.0, 25.0))), events)
        engine.down(0, box.center, 20)
        engine.up(0, box.center, 30, canceled = false)
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
}
