package com.compasslink3d

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream

internal class ControllerProfileStore(context: Context, private val codec: ProfileCodec) {
    private val file = AtomicFile(File(context.filesDir, "controller-profiles.json"))
    private var custom = emptyList<ControllerProfile>()
    private var activeId = DEFAULT_PROFILE_ID
    private var readable = true
    var loadError: String? = null
        private set

    init {
        try {
            val data = file.openRead().use { JSONObject(it.readBounded(MAX_PROFILE_BYTES * MAX_PROFILES + 1024).toString(Charsets.UTF_8)) }
            require(data.get("schemaVersion") in listOf(2, 3, 4, 5)) { "Unsupported profile store" }
            val values = data.getJSONArray("profiles")
            require(values.length() <= MAX_PROFILES)
            val loaded = List(values.length()) { codec.decode(values.getJSONObject(it)) }
            require(loaded.none { it.builtIn } && loaded.map { it.id }.toSet().size == loaded.size)
            validateTargets(loaded)
            custom = loaded
            activeId = data.getString("activeId").takeIf { id -> find(id) != null } ?: DEFAULT_PROFILE_ID
        } catch (_: FileNotFoundException) {
            // A first launch has no user profiles.
        } catch (error: Exception) {
            readable = false
            loadError = "Saved profiles could not be read. ${error.message.orEmpty()}"
        }
    }

    val profiles: List<ControllerProfile> get() = BUILT_IN_PROFILES + custom
    val active: ControllerProfile get() = find(activeId) ?: DEFAULT_PROFILE

    fun find(id: String) = profiles.firstOrNull { it.id == id }

    fun select(id: String) {
        require(find(id) != null) { "Profile does not exist" }
        if (id != activeId) commit(custom, id)
    }

    fun save(profile: ControllerProfile) {
        require(!profile.builtIn) { "Duplicate this built-in controller to edit it" }
        val revision = profile.copy(modifiedAt = maxOf(System.currentTimeMillis(), profile.createdAt))
        val checked = codec.decode(codec.encode(revision).toString().toByteArray(Charsets.UTF_8))
        replace(checked)
    }

    fun importProfile(profile: ControllerProfile) {
        // Keep portable IDs when there is no collision so dependants can be imported afterwards.
        replace(if (find(profile.id) == null) profile else profile.duplicate(profile.name))
    }

    private fun replace(checked: ControllerProfile) {
        val next = custom.toMutableList()
        val existing = next.indexOfFirst { it.id == checked.id }
        if (existing >= 0) next[existing] = checked else next.add(checked)
        commit(next, checked.id)
    }

    fun delete(id: String) {
        require(find(id)?.builtIn != true) { "This controller is built in" }
        val dependent = custom.firstOrNull { it.id != id && id in it.hybrid?.quickSwitch.orEmpty().values }
        require(dependent == null) { "Remove or retarget the switch button in ${dependent?.name} before deleting this profile" }
        commit(custom.filterNot { it.id == id }, if (activeId == id) DEFAULT_PROFILE_ID else activeId)
    }

    private fun validateTargets(next: List<ControllerProfile>) {
        val ids = (BUILT_IN_PROFILES + next).mapTo(HashSet()) { it.id }
        for (profile in next) {
            require(profile.hybrid?.quickSwitch.orEmpty().values.all { it in ids }) {
                "A switch target in ${profile.name} is unavailable. Install the target profile first or remove the reference."
            }
        }
    }

    private fun commit(next: List<ControllerProfile>, selected: String) {
        check(readable) { "Saved profiles could not be read and have been left on this device" }
        require(next.size <= MAX_PROFILES) { "Save up to $MAX_PROFILES custom profiles" }
        validateTargets(next)
        val version = if (next.any { it.hybrid != null }) 5 else if (next.any { it.motion != null }) 4 else 3
        val bytes = JSONObject().put("schemaVersion", version).put("activeId", selected)
            .put("profiles", JSONArray(next.map(codec::encode))).toString().toByteArray(Charsets.UTF_8)
        val writer = file.startWrite()
        try {
            writer.write(bytes)
            file.finishWrite(writer)
        } catch (error: Exception) {
            file.failWrite(writer)
            throw error
        }
        custom = next.toList()
        activeId = selected
    }

    private companion object {
        const val MAX_PROFILES = 32
    }
}

internal fun InputStream.readBounded(limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream(minOf(limit, 4096))
    val buffer = ByteArray(4096)
    while (true) {
        val count = read(buffer)
        if (count < 0) return output.toByteArray()
        require(output.size() + count <= limit) { "File exceeds the supported size" }
        output.write(buffer, 0, count)
    }
}
