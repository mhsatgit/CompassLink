package com.compasslink3d

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin

class MotionProcessorTest {
    private val start = 1_000_000_000L
    private val neutral = MotionQuaternion.IDENTITY
    private fun yaw(angle: Double) = MotionQuaternion(0.0, 0.0, -sin(angle / 2), cos(angle / 2))
    private fun pitch(angle: Double) = MotionQuaternion(-sin(angle / 2), 0.0, 0.0, cos(angle / 2))
    private fun roll(angle: Double) = MotionQuaternion(0.0, sin(angle / 2), 0.0, cos(angle / 2))
    private fun assertNear(expected: Double, observed: Double) = assertEquals(expected, observed, 1e-8)

    @Test fun absoluteHeadingAgreesWithDesktopForMixedPoseAndUndefinedVertical() {
        assertNear(270.0, yaw(-PI / 2).headingDegrees()!!)
        assertNear(296.565051177078, MotionQuaternion(.2, .3, .4, .5).headingDegrees()!!)
        assertNear(296.565051177078, MotionQuaternion(-2.0, -3.0, -4.0, -5.0).headingDegrees()!!)
        assertNull(pitch(PI / 2).headingDegrees())
        assertNull(MotionQuaternion(0.0, 0.0, 0.0, 0.0).headingDegrees())
    }

    @Test fun normalizesMultipliesInvertsAndAlignsEquivalentSigns() {
        assertEquals(neutral, MotionQuaternion(0.0, 0.0, 0.0, 2.0).normalized())
        assertNull(MotionQuaternion(0.0, 0.0, 0.0, 0.0).normalized())
        assertNull(MotionQuaternion(Double.POSITIVE_INFINITY, 0.0, 0.0, 1.0).normalized())
        val q = yaw(.7) * pitch(.3) * roll(.2)
        val inverse = q * q.inverse()
        assertNear(1.0, inverse.w)
        assertNear(0.0, inverse.x)
        assertNear(0.0, inverse.y)
        assertNear(0.0, inverse.z)
        assertEquals(q, MotionQuaternion(-q.x, -q.y, -q.z, -q.w).continuous(q))
    }

    @Test fun relativePoseAndScreenSpaceAxesHaveKnownRotations() {
        val base = yaw(.8) * roll(.23)
        assertNear(.22, MotionQuaternion.relative(base, base * yaw(.22), 0).angles().y)
        assertNear(.34, MotionQuaternion.relative(base, base * pitch(.34), 0).angles().x)
        assertNear(.19, MotionQuaternion.relative(base, base * roll(.19), 0).angles().z)
        val landscape = MotionQuaternion.relative(base, base * pitch(.34), 1).angles()
        assertNear(0.0, landscape.x)
        assertNear(-.34, landscape.z)
        assertEquals(MotionVector(-2.0, 1.0, 3.0), MotionVector(1.0, 2.0, 3.0).display(1))
        assertEquals(MotionVector(2.0, -1.0, 3.0), MotionVector(1.0, 2.0, 3.0).display(3))
    }

    @Test fun projectedYawIsContinuousAcrossTheWrap() {
        assertNear(-PI + .02, wrappedAngle(PI + .02))
        val events = mutableListOf<ControllerInput>()
        val motion = MotionProcessor(MotionSettings(deadZone = .03, smoothingMs = 0.0), "motion", events::add)
        motion.orientation(neutral, start, 0)
        motion.orientation(yaw(PI - .01), start + 20_000_000, 0)
        motion.orientation(yaw(PI + .01), start + 40_000_000, 0)
        motion.orientation(yaw(2 * PI), start + 60_000_000, 0)
        assertTrue(events.isEmpty())
    }

    @Test fun smoothingUsesElapsedTimeAndStickBoundsStayCircular() {
        fun step(intervalMs: Long): LayoutPoint {
            val motion = MotionProcessor(MotionSettings(deadZone = 0.0, maximum = .7, sensitivity = 2.5,
                invertX = true, smoothingMs = 60.0), "motion", {})
            motion.orientation(neutral, start, 0)
            motion.orientation(neutral, start + 1_000_000, 0)
            for (time in intervalMs..120L step intervalMs) {
                motion.orientation(yaw(.52) * pitch(.45), start + (time + 1) * 1_000_000, 0)
            }
            return motion.output
        }
        val fast = step(10)
        val slow = step(30)
        assertNear(fast.x, slow.x)
        assertNear(fast.y, slow.y)
        assertTrue(fast.x < 0 && fast.y > 0 && fast.length <= .700000001)
        assertNear(.5, motionSignal(.6, .2, 1.0, 1.0))
        assertNear(-.8, motionSignal(-.8, .2, 2.0, .8))
        assertNear(0.0, motionSignal(.2, .2, 1.0, 1.0))
    }

    @Test fun recenterAndStaleReleaseStickImmediately() {
        val events = mutableListOf<ControllerInput>()
        val motion = MotionProcessor(MotionSettings(smoothingMs = 80.0), "motion", events::add)
        val comfortable = yaw(.8) * roll(.1)
        motion.orientation(comfortable, start, 0)
        motion.orientation(comfortable, start + 20_000_000, 0)
        val displaced = comfortable * yaw(.5)
        motion.orientation(displaced, start + 40_000_000, 0)
        assertTrue((events.last() as ControllerInput.Axis).value.x > 0)
        motion.recenter(start + 45_000_000)
        assertEquals(neutral, motion.relative)
        assertEquals(ControllerInput.Axis("motion", LayoutPoint(0.0, 0.0)), events.last())
        val count = events.size
        motion.orientation(displaced, start + 60_000_000, 0)
        assertEquals(count, events.size)
        motion.orientation(displaced * yaw(.45), start + 80_000_000, 0)
        motion.expire(start + 80_000_000 + MOTION_STALE_NS + 1)
        assertEquals(ControllerInput.Axis("motion", LayoutPoint(0.0, 0.0)), events.last())
        assertFalse(motion.fresh(start + 80_000_000 + MOTION_STALE_NS + 1))
        motion.stop()
        assertNull(motion.relative)
    }

    @Test fun steeringHasSeparateThresholdsAndReleasesBeforeChangingDirection() {
        val events = mutableListOf<ControllerInput>()
        val motion = MotionProcessor(MotionSettings(mapping = MotionMapping.STEERING, deadZone = 0.0,
            smoothingMs = 0.0, engage = .4, release = .2), "steer", events::add)
        listOf(0.0, 0.0, .41, .3, -.55, -.3, -.19).forEachIndexed { index, x ->
            motion.orientation(yaw(asin(x * sin(.65))), start + index * 20_000_000L, 0)
        }
        assertEquals(listOf(
            ControllerInput.Digital("steer", Slot.RIGHT, true), ControllerInput.Digital("steer", Slot.RIGHT, false),
            ControllerInput.Digital("steer", Slot.LEFT, true), ControllerInput.Digital("steer", Slot.LEFT, false),
        ), events)
    }

    @Test fun rightEdgeAndLeftEdgeMatchLegacySteeringInAllDisplayRotations() {
        // Android natural axes. The sign of the physical screen edge follows the display rotation.
        val rightEdge = listOf(roll(.36), pitch(-.36), roll(-.36), pitch(.36))
        for ((rotation, right) in rightEdge.withIndex()) {
            assertTrue(rightTiltDegrees(right.w.toFloat(), right.x.toFloat(), right.y.toFloat(),
                right.z.toFloat(), rotation) > 13.0)
            val events = mutableListOf<ControllerInput>()
            val settings = MotionSettings(mapping = MotionMapping.STEERING, horizontal = MotionAxis.ROLL,
                deadZone = 0.0, smoothingMs = 0.0)
            val motion = MotionProcessor(settings, "steer", events::add)
            motion.orientation(neutral, start, rotation)
            motion.orientation(neutral, start + 20_000_000, rotation)
            motion.orientation(right, start + 40_000_000, rotation)
            motion.orientation(right.inverse(), start + 60_000_000, rotation)
            motion.orientation(neutral, start + 80_000_000, rotation)
            val keys = events.map {
                val event = it as ControllerInput.Digital
                (if (event.slot == Slot.RIGHT) settings.rightKey else settings.leftKey) to event.down
            }
            assertEquals(listOf("D" to true, "D" to false, "A" to true, "A" to false), keys)
        }
    }

    @Test fun gyroUsesEventTimeAndHoldToggleDoNotSendAnActivationJump() {
        fun integrate(intervalMs: Long, behavior: GyroEnable): Double {
            val events = mutableListOf<ControllerInput>()
            val discards = mutableListOf<String>()
            val motion = MotionProcessor(MotionSettings(mapping = MotionMapping.MOUSE, source = MotionSource.GYROSCOPE,
                deadZone = 0.0, smoothingMs = 0.0, mouseScale = 100.0, gyroEnable = behavior), "aim", events::add, discards::add)
            val rate = MotionVector(0.0, 0.0, -4.0)
            motion.gyro(rate, start, 0)
            motion.activate(true, start + 1)
            motion.gyro(rate, start + 1_000_000, 0)
            assertTrue(events.isEmpty())
            for (time in intervalMs..200L step intervalMs) motion.gyro(rate, start + (time + 1) * 1_000_000, 0)
            val total = events.sumOf { (it as ControllerInput.Pointer).delta.x }
            motion.activate(false, start + 201_000_000)
            assertEquals(behavior == GyroEnable.TOGGLE, motion.gyroEnabled)
            if (behavior == GyroEnable.TOGGLE) motion.activate(true, start + 202_000_000)
            assertFalse(motion.gyroEnabled)
            assertEquals("aim", discards.last())
            val count = events.size
            motion.gyro(rate, start + 230_000_000, 0)
            assertEquals(count, events.size)
            motion.activate(true, start + 230_000_001)
            motion.gyro(rate, start + 240_000_000, 0)
            motion.gyro(rate, start + 400_000_000, 0) // Gap too large to integrate.
            assertEquals(count, events.size)
            motion.expire(start + 400_000_000 + MOTION_STALE_NS + 1)
            assertFalse(motion.gyroEnabled)
            return total
        }
        assertNear(20.0, integrate(10, GyroEnable.HOLD))
        assertNear(20.0, integrate(40, GyroEnable.TOGGLE))
    }

    @Test fun capabilitySelectionAndMeasuredTimestampWindow() {
        assertEquals(MotionSensor.GAME, orientationSensor(true, true, true))
        assertEquals(MotionSensor.ROTATION, orientationSensor(false, true, true))
        assertEquals(MotionSensor.ROTATION, orientationSensor(true, true, false))
        assertEquals(MotionSensor.GAME, orientationSensor(true, false, false))
        assertNull(orientationSensor(false, false, true))
        val rate = MotionEventRate()
        for (index in 0..79) rate.add(start + index * 25_000_000)
        assertNear(40.0, requireNotNull(rate.hz(start + 1_975_000_000)))
        assertNull(rate.hz(start + 4_000_000_000))
    }

    @Test fun alwaysGyroRestartsFromFreshSamplesAndToggleIgnoresRepeatPresses() {
        val rate = MotionVector(0.0, 0.0, -3.0)
        val outputs = mutableListOf<ControllerInput>()
        val settings = MotionSettings(source = MotionSource.GYROSCOPE, mapping = MotionMapping.MOUSE)
        val always = MotionProcessor(settings.copy(gyroEnable = GyroEnable.ALWAYS), "always", outputs::add)
        always.gyro(rate, start, 0)
        assertTrue(always.gyroEnabled)
        assertTrue(outputs.isEmpty())
        always.gyro(rate, start + 10_000_000, 0)
        assertTrue(outputs.isNotEmpty())
        always.expire(start + 10_000_001 + MOTION_STALE_NS)
        assertFalse(always.gyroEnabled)
        always.stop()
        val toggle = MotionProcessor(settings.copy(gyroEnable = GyroEnable.TOGGLE), "toggle", outputs::add)
        toggle.gyro(rate, start, 0)
        repeat(2) { toggle.activate(true, start + 1) }
        assertTrue(toggle.gyroEnabled)
        toggle.activate(false, start + 2)
        toggle.activate(true, start + 3)
        assertFalse(toggle.gyroEnabled)
    }

    @Test fun strongGyroMovementPreservesTheIntegralAcrossValidSampleIntervals() {
        val reports = mutableListOf<ControllerInput>()
        val processor = MotionProcessor(MotionSettings(mapping = MotionMapping.MOUSE, source = MotionSource.GYROSCOPE,
            gyroEnable = GyroEnable.ALWAYS, mouseScale = 3000.0, smoothingMs = 0.0), "gyro", reports::add)
        val speed = MotionVector(0.0, 0.0, -8.0)
        processor.gyro(speed, start, 0)
        processor.gyro(speed, start + 90_000_000L, 0)
        val report = reports.single() as ControllerInput.Pointer
        assertNear(270.0, report.delta.x)
        assertNear(0.0, report.delta.y)
        assertEquals(start + 90_000_000L, report.timestampNs)
    }

    @Test fun motionNeutralLeavesAnotherTouchOwnersPendingAxisIntact() {
        val queue = ContinuousInput()
        val immediate = mutableListOf<ControllerInput.Axis>()
        val forward: (ControllerInput) -> Unit = {
            if (it is ControllerInput.Axis && it.value.length == 0.0) {
                queue.discardAxis(it.control)
                immediate.add(it)
            } else queue.offer(it)
        }
        val touch = ControllerTouchEngine(1.0, forward, OutputMode.GAMEPAD)
        val joystick = newControl(ControlType.JOYSTICK).copy(id = "touch",
            bindings = mapOf(Slot.AXIS to Binding(BindingType.GAMEPAD_AXIS, "RIGHT_STICK")))
        val box = ControllerViewport(640, 480, 48.0).box(joystick)
        touch.controls = listOf(PhysicalControl(joystick, box))
        touch.down(1, box.center, 1)
        touch.move(1, box.center + LayoutPoint(box.width / 3, 0.0), 2)
        val motion = MotionProcessor(MotionSettings(smoothingMs = 0.0), "motion", forward)
        motion.orientation(neutral, start, 0)
        motion.orientation(neutral, start + 20_000_000, 0)
        motion.orientation(yaw(.5), start + 40_000_000, 0)
        motion.stop()
        assertEquals(ControllerInput.Axis("motion", LayoutPoint(0.0, 0.0)), immediate.last())
        val remaining = queue.drain().single() as ControllerInput.Axis
        assertEquals("touch", remaining.control)
        assertTrue(remaining.value.x > 0)
    }
}
