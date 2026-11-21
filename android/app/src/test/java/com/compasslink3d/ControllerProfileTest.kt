package com.compasslink3d

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ControllerProfileTest {
    private val codec = ProfileCodec(
        JSONArray(File("src/main/assets/controller-keys.json").readText()).let { array ->
            List(array.length(), array::getString).toSet()
        },
    )

    @Test fun roundTripAllControlTypes() {
        val controls = ControlType.entries.map(::newControl)
        val profile = DEFAULT_PROFILE.duplicate("My controller", 42).copy(
            orientation = ProfileOrientation.PORTRAIT,
            controls = controls,
        )
        assertEquals(profile, codec.decode(codec.encode(profile).toString().toByteArray()))
        assertNotEquals(profile.id, profile.duplicate("My copy").id)
        assertEquals(2, codec.encode(profile).getInt("schemaVersion"))
        assertEquals(setOf("W", "A", "S", "D", "SPACE", "ESCAPE"), DEFAULT_PROFILE.controls
            .map { it.bindings.getValue(Slot.PRESS).target }.toSet())
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

    @Test fun hiddenAndFutureControlsDoNotPublishExecutableMappings() {
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
