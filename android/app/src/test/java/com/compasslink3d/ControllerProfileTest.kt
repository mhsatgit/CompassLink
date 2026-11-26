package com.compasslink3d

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.io.StringReader

class ControllerProfileTest {
    @Test fun portraitPresetsKeepBindingsAndFitDifferentDisplays() {
        for (preset in BUILT_IN_PROFILES) {
            val portrait = preset.layoutFor(ProfileOrientation.PORTRAIT)
            assertEquals(ProfileOrientation.PORTRAIT, portrait.orientation)
            assertEquals(preset.descriptor().toString(), portrait.descriptor().toString())
            assertEquals(preset.descriptor(OutputMode.GAMEPAD).toString(), portrait.descriptor(OutputMode.GAMEPAD).toString())
            for ((width, height) in listOf(320 to 568, 360 to 800, 600 to 960)) {
                val area = ControllerViewport(width, height, 48.0)
                val boxes = portrait.controls.map { it to area.box(it) }
                for ((index, item) in boxes.withIndex()) {
                    val (control, box) = item
                    assertTrue(box.center.x - box.halfX >= 0 && box.center.x + box.halfX <= width)
                    assertTrue(box.center.y - box.halfY >= 0 && box.center.y + box.halfY <= height)
                    for ((other, next) in boxes.drop(index + 1)) {
                        val intersects = if (control.round && other.round) {
                            (box.center - next.center).length < (box.width + next.width) / 2
                        } else {
                            kotlin.math.abs(box.center.x - next.center.x) < box.halfX + next.halfX &&
                                kotlin.math.abs(box.center.y - next.center.y) < box.halfY + next.halfY
                        }
                        assertFalse("${preset.name} at ${width}x${height}: ${control.id} overlaps ${other.id}", intersects)
                    }
                }
            }
        }
    }

    @Test fun changingPlayOrientationLeavesCustomLayoutAndEscapeBindingIntact() {
        val custom = DEFAULT_PROFILE.duplicate("My custom layout")
        assertEquals(custom.controls, custom.layoutFor(ProfileOrientation.PORTRAIT).controls)
        assertEquals(ProfileOrientation.LANDSCAPE, custom.orientation)
        val escape = DEFAULT_PROFILE.layoutFor(ProfileOrientation.PORTRAIT).controls.single { it.id == "ACTION_B" }
        assertEquals("ESCAPE", escape.bindings[Slot.PRESS]?.target)
    }

    @Test fun onlyVisibleTiltAndActiveOrientationMotionLockTheProfile() {
        val portrait = DEFAULT_PROFILE.duplicate("Touch profile").copy(orientation = ProfileOrientation.PORTRAIT)
        val tilt = newControl(ControlType.TILT)
        assertEquals(null, DEFAULT_PROFILE.lockedOrientation)
        assertEquals(null, XBOX_PROFILE.lockedOrientation)
        assertEquals(null, portrait.lockedOrientation)
        assertEquals(ProfileOrientation.LANDSCAPE, TILT_RACING_PROFILE.lockedOrientation)
        assertEquals(ProfileOrientation.PORTRAIT, portrait.copy(controls = listOf(tilt)).lockedOrientation)
        assertEquals(null, portrait.copy(controls = listOf(tilt.copy(style = tilt.style.copy(visible = false)))).lockedOrientation)
        assertEquals(null, portrait.copy(motion = MotionSettings()).lockedOrientation)
        assertEquals(ProfileOrientation.PORTRAIT, portrait.copy(motion = MotionSettings(enabled = true)).lockedOrientation)
        assertEquals(null, portrait.copy(motion = MotionSettings(enabled = true, source = MotionSource.GYROSCOPE)).lockedOrientation)
    }

    @Test fun controllerGrantValidatesTransportAndRedactsItsToken() {
        val token = "a".repeat(64)
        val grant = requireNotNull(ControllerGrant.parse("wireless", token))
        assertEquals("wireless", grant.mode)
        assertEquals(token, grant.token)
        assertFalse(grant.toString().contains(token))
        assertEquals(null, ControllerGrant.parse("lan", token))
        assertEquals(null, ControllerGrant.parse("usb", token.dropLast(1)))
        assertEquals(null, ControllerGrant.parse("usb", "X".repeat(64)))
    }

    @Test fun controllerReplyRequiresACompleteBoundedFrame() {
        val reader = StringReader("first\r\nsecond\nunsent")
        assertEquals("first", reader.readControllerReply())
        assertEquals("second", reader.readControllerReply())
        assertEquals(null, reader.readControllerReply())
        assertEquals(4096, StringReader("x".repeat(4096) + "\n").readControllerReply()?.length)
        assertThrows(IOException::class.java) { StringReader("x".repeat(4097)).readControllerReply() }
    }

    private val codec = ProfileCodec(
        JSONArray(File("src/main/assets/controller-keys.json").readText()).let { array ->
            List(array.length(), array::getString).toSet()
        },
    )

    @Test fun motionProfileRoundTripsAndPreservesEarlierLayoutAndBindings() {
        val legacy = XBOX_PROFILE.layoutFor(ProfileOrientation.PORTRAIT).duplicate("Motion test", 100)
        val profile = legacy.copy(motion = MotionSettings(enabled = true, source = MotionSource.GYROSCOPE,
            invertX = true, deadZone = .23, gyroEnable = GyroEnable.TOGGLE, gyroLabel = "Aim clutch"))
        val encoded = codec.encode(profile)
        assertEquals(4, encoded.getInt("schemaVersion"))
        assertEquals(profile, codec.decode(encoded))
        assertEquals(encoded.toString(), codec.encode(codec.decode(encoded)).toString())
        assertEquals(legacy, codec.decode(codec.encode(legacy)))
        assertFalse(legacy.descriptor(OutputMode.GAMEPAD).has("motion"))
        assertFalse(profile.descriptor(OutputMode.KEYBOARD).has("motion"))
        assertEquals("RIGHT_STICK", profile.descriptor(OutputMode.GAMEPAD).getJSONObject("motion").getString("stick"))
        val colliding = profile.copy(controls = listOf(profile.controls.first().copy(id = "motion-input-1")))
        assertEquals("motion-input-2", colliding.motionId())
        assertEquals("A", TILT_RACING_PROFILE.controls.first().bindings[Slot.LEFT]?.target)
        assertEquals("D", TILT_RACING_PROFILE.controls.first().bindings[Slot.RIGHT]?.target)
        assertNullMotionForLegacySchema()
    }

    private fun assertNullMotionForLegacySchema() {
        val known = DEFAULT_PROFILE.duplicate("Old keyboard")
        assertEquals(known, codec.decode(codec.encode(known).put("schemaVersion", 2)))
        assertEquals(null, codec.decode(codec.encode(TILT_RACING_PROFILE)).motion)
    }

    @Test fun malformedMotionSettingsAreRejected() {
        val template = DEFAULT_PROFILE.duplicate("Motion settings").copy(motion = MotionSettings(true))
        val invalid = listOf("source" to "NETWORK", "engage" to .17, "smoothingMs" to 900.0,
            "stick" to "RIGHT_TRIGGER", "leftKey" to "D", "horizontal" to "MAGNETIC",
            "gyroLabel" to "Line\nbreak", "sensitivity" to "NaN", "maximum" to 2.0)
        for ((key, value) in invalid) {
            val wire = codec.encode(template)
            wire.getJSONObject("motion").put(key, value)
            assertThrows(IllegalArgumentException::class.java) { codec.decode(wire) }
        }
        assertThrows(IllegalArgumentException::class.java) { codec.decode(codec.encode(template).put("schemaVersion", 3)) }
    }

    @Test fun roundTripAllControlTypes() {
        val controls = ControlType.entries.filter { it != ControlType.PROFILE_SWITCH && it != ControlType.TOUCH_LOOK }.map(::newControl)
        val profile = DEFAULT_PROFILE.duplicate("My controller", 42).copy(
            orientation = ProfileOrientation.PORTRAIT,
            controls = controls,
        )
        assertEquals(profile, codec.decode(codec.encode(profile).toString().toByteArray()))
        assertNotEquals(profile.id, profile.duplicate("My copy").id)
        assertEquals(3, codec.encode(profile).getInt("schemaVersion"))
        assertEquals(setOf("W", "A", "S", "D", "SPACE", "ESCAPE"), DEFAULT_PROFILE.controls
            .map { it.bindings.getValue(Slot.PRESS).target }.toSet())
    }

    @Test fun fullScreenLookSavesSensitivityAndUsesTheExistingMouseMappingWithXboxBindings() {
        val layer = newControl(ControlType.TOUCH_LOOK).let { it.copy(settings = it.settings.copy(sensitivity = .05)) }
        val profile = XBOX_PROFILE.duplicate("Xbox with touch-look").copy(
            controls = listOf(layer) + XBOX_PROFILE.controls,
            hybrid = HybridSettings(hybrid = false),
        )
        val restored = codec.decode(codec.encode(profile))
        assertEquals(profile, restored)
        assertTrue(restored.requiresHybridTouchLook)
        val descriptor = restored.hybridDescriptor(OutputMode.HYBRID)
        val pointer = descriptor.getJSONArray("controls").getJSONObject(0)
        assertEquals("TOUCHPAD", pointer.getString("type"))
        assertEquals(1, pointer.getJSONObject("bindings").length())
        assertEquals("POINTER", pointer.getJSONObject("bindings").getJSONObject("pointer").getString("type"))
        assertFalse(restored.copy(controls = listOf(layer)).requiresHybridTouchLook)
        assertThrows(IllegalArgumentException::class.java) {
            codec.decode(codec.encode(profile.copy(controls = listOf(layer, layer.copy(id = "duplicate")))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            codec.decode(codec.encode(profile.copy(controls = listOf(layer.copy(settings = layer.settings.copy(tapToClick = true))))))
        }
    }

    @Test fun hybridTemplatesAndBothMotionSourcesRoundTripWithoutChangingLegacyProfiles() {
        for (template in HYBRID_TEMPLATES) {
            val profile = template.duplicate("Copy of ${template.name}", 120)
            val encoded = codec.encode(profile)
            assertEquals(5, encoded.getInt("schemaVersion"))
            val decoded = codec.decode(encoded)
            assertEquals(profile, decoded)
            assertEquals(encoded.toString(), codec.encode(decoded).toString())
            assertTrue(decoded.hybrid!!.quickSwitch.values.all { id -> HYBRID_TEMPLATES.any { it.id == id } })
        }
        val fps = HYBRID_TEMPLATES.first { it.id == "hybrid-fps" }
        val dual = fps.copy(hybrid = fps.hybrid!!.copy(secondaryMotion = MotionSettings(
            enabled = true, stick = "LEFT_STICK", sensitivity = .72, horizontal = MotionAxis.ROLL,
        )))
        val decoded = codec.decode(codec.encode(dual))
        assertEquals(dual, decoded)
        val descriptor = decoded.hybridDescriptor(OutputMode.HYBRID)
        assertEquals(2, descriptor.getJSONArray("motions").length())
        assertEquals("shoulder", descriptor.getJSONObject("gyroActivation").getString("control"))
        val controls = descriptor.getJSONArray("controls")
        val action = (0 until controls.length()).map(controls::getJSONObject).single { it.getString("id") == "reload" }
        assertEquals("R", action.getJSONObject("bindings").getJSONObject("press").getString("key"))
        val legacy = TILT_RACING_PROFILE.layoutFor(ProfileOrientation.PORTRAIT).duplicate("Earlier tilt", 7)
        assertEquals(OrientationPolicy.PORTRAIT, codec.decode(codec.encode(legacy)).orientationPolicy)
        val gyro = legacy.copy(controls = DEFAULT_PROFILE.controls, motion = fps.motion)
        assertEquals(OrientationPolicy.SYSTEM, codec.decode(codec.encode(gyro)).orientationPolicy)
        val explicit = legacy.copy(hybrid = HybridSettings(hybrid = false, orientation = OrientationPolicy.SYSTEM))
        assertEquals(OrientationPolicy.SYSTEM, codec.decode(codec.encode(explicit)).orientationPolicy)
    }

    @Test fun malformedHybridSettingsAndMissingActivationOrSwitchReferencesAreRejected() {
        val original = HYBRID_TEMPLATES.first { it.id == "hybrid-fps" }
        val options = original.hybrid!!
        val removed = original.copy(controls = original.controls.filter { it.id != "shoulder" })
        val hidden = original.copy(controls = original.controls.map {
            if (it.id == "shoulder") it.copy(style = it.style.copy(visible = false)) else it
        })
        for (profile in listOf(removed, hidden,
            original.copy(hybrid = options.copy(gyroControl = ControlSlot("shoulder", Slot.LEFT))),
            original.copy(hybrid = options.copy(quickSwitch = mapOf("switch" to original.id))),
            original.copy(hybrid = options.copy(quickSwitch = emptyMap())),
            original.copy(motion = original.motion!!.copy(gyroEnable = GyroEnable.ALWAYS)),
        )) assertThrows(IllegalArgumentException::class.java) { codec.decode(codec.encode(profile)) }
        val invalid = codec.encode(original)
        invalid.getJSONObject("hybrid").put("haptics", 5)
        assertThrows(Exception::class.java) { codec.decode(invalid) }
    }

    @Test fun malformedAndOversizedProfilesAreRejected() {
        assertThrows(Exception::class.java) { codec.decode("{".toByteArray()) }
        assertThrows(IllegalArgumentException::class.java) { codec.decode(ByteArray(MAX_PROFILE_BYTES + 1)) }
        assertThrows(IllegalArgumentException::class.java) { codec.decode(codec.encode(DEFAULT_PROFILE).put("schemaVersion", 1)) }
        val invalidSize = codec.encode(DEFAULT_PROFILE)
        invalidSize.getJSONArray("controls").getJSONObject(0).getJSONObject("geometry").put("width", 1.1)
        assertThrows(IllegalArgumentException::class.java) { codec.decode(invalidSize) }
        val duplicateIds = codec.encode(DEFAULT_PROFILE)
        val controls = duplicateIds.getJSONArray("controls")
        controls.put(controls.getJSONObject(0))
        assertThrows(IllegalArgumentException::class.java) { codec.decode(duplicateIds) }
        val unsupportedKey = codec.encode(DEFAULT_PROFILE)
        unsupportedKey.getJSONArray("controls").getJSONObject(0).getJSONObject("bindings").getJSONObject("press").put("key", "RUN")
        assertThrows(IllegalArgumentException::class.java) { codec.decode(unsupportedKey) }
    }

    @Test fun hiddenAndOppositeModeBindingsAreOmitted() {
        val action = newControl(ControlType.BUTTON)
        val profile = DEFAULT_PROFILE.duplicate("Saved", 1).copy(controls = listOf(
            action.copy(style = action.style.copy(visible = false)),
            newControl(ControlType.BUTTON).copy(bindings = mapOf(Slot.PRESS to Binding(BindingType.GAMEPAD, "A"))),
        ))
        val roundTripped = codec.decode(codec.encode(profile))
        assertEquals(profile, roundTripped)
        val controls = roundTripped.descriptor().getJSONArray("controls")
        assertEquals(1, controls.length())
        assertFalse(controls.getJSONObject(0).getJSONObject("bindings").has("press"))
        val gamepad = roundTripped.descriptor(OutputMode.GAMEPAD).getJSONArray("controls")
        assertEquals(1, gamepad.length())
        assertEquals("A", gamepad.getJSONObject(0).getJSONObject("bindings").getJSONObject("press").getString("target"))
    }

    @Test fun xboxProfileRoundTripsAndRejectsAxisInWrongControl() {
        assertEquals(XBOX_PROFILE, codec.decode(codec.encode(XBOX_PROFILE)))
        assertEquals(TILT_RACING_PROFILE, codec.decode(codec.encode(TILT_RACING_PROFILE)))
        for (tilt in listOf(TILT_RACING_PROFILE.controls.first(), newControl(ControlType.TILT))) {
            assertEquals(Binding(BindingType.KEYBOARD, "A"), tilt.bindings[Slot.LEFT])
            assertEquals(Binding(BindingType.KEYBOARD, "D"), tilt.bindings[Slot.RIGHT])
        }
        val malformed = codec.encode(XBOX_PROFILE)
        malformed.getJSONArray("controls").getJSONObject(0).getJSONObject("bindings")
            .getJSONObject("axis").put("target", "RIGHT_STICK")
        assertThrows(IllegalArgumentException::class.java) { codec.decode(malformed) }
    }

    @Test fun snapshotTwoLayoutsAndReservedMappingsMigrate() {
        val stick = newControl(ControlType.JOYSTICK)
        val button = newControl(ControlType.BUTTON).copy(bindings = mapOf(Slot.PRESS to Binding(BindingType.GAMEPAD, "SELECT")))
        val old = DEFAULT_PROFILE.duplicate("Version 2", 12).copy(controls = listOf(stick, button))
        val data = codec.encode(old).put("schemaVersion", 2)
        val controls = data.getJSONArray("controls")
        controls.getJSONObject(0).getJSONObject("bindings").put("left", Binding(BindingType.GAMEPAD, "LEFT_STICK").encode())
        controls.getJSONObject(1).getJSONObject("bindings").getJSONObject("press").put("target", "SELECT")
        val migrated = codec.decode(data)
        assertEquals(old.id, migrated.id)
        assertEquals(stick.geometry, migrated.controls[0].geometry)
        assertEquals(stick.bindings[Slot.UP], migrated.controls[0].bindings[Slot.UP])
        assertEquals(Binding(BindingType.GAMEPAD_AXIS, "LEFT_STICK"), migrated.controls[0].bindings[Slot.AXIS])
        assertEquals(Binding(BindingType.GAMEPAD, "BACK"), migrated.controls[1].bindings[Slot.PRESS])
        assertEquals(migrated, codec.decode(codec.encode(migrated)))
    }

    @Test fun editorHistoryIsBoundedAndUndoRedoRetainsCompleteState() {
        val history = EditorHistory(DEFAULT_PROFILE.controls)
        history.commit(emptyList())
        assertTrue(history.canUndo)
        history.undo()
        assertEquals(DEFAULT_PROFILE.controls, history.current)
        history.redo()
        assertTrue(history.current.isEmpty())
        for (n in 0..50) history.commit(listOf(newControl(ControlType.BUTTON)))
        repeat(40) { history.undo() }
        assertFalse(history.canUndo)
        assertTrue(history.canRedo)
    }
}
