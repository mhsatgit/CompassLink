package com.compasslink3d

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.OutputStream

internal class ControllerProfileStore(context: Context, private val codec: ProfileCodec) {
    private val file = AtomicFile(File(context.filesDir, "controller-profiles.json"))
    private var custom = emptyList<ControllerProfile>()
    private var activeId = DEFAULT_PROFILE_ID
    private var readable = true
    var hasPlayed = false
        private set
    var loadError: String? = null
        private set

    init {
        try {
            val data = file.openRead().use { JSONObject(it.readBounded(MAX_PROFILE_BUNDLE_BYTES).toString(Charsets.UTF_8)) }
            require(data.get("schemaVersion") in listOf(2, 3, 4, 5)) { "Unsupported profile store" }
            val values = data.getJSONArray("profiles")
            require(values.length() <= MAX_CUSTOM_PROFILES)
            val loaded = List(values.length()) { codec.decode(values.getJSONObject(it)) }
            require(loaded.none { it.builtIn } && loaded.map { it.id }.toSet().size == loaded.size)
            validateSwitchTargets(BUILT_IN_PROFILES + loaded)
            custom = loaded
            activeId = data.getString("activeId").takeIf { id -> find(id) != null } ?: DEFAULT_PROFILE_ID
            hasPlayed = data.optBoolean("hasPlayed")
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
        val profile = requireNotNull(find(id)) { "Profile does not exist" }
        if (id == activeId && hasPlayed) return
        if (readable) commit(custom, id, played = true)
        else {
            check(profile.builtIn)
            activeId = id
            hasPlayed = true
        }
    }

    fun save(profile: ControllerProfile) {
        require(!profile.builtIn) { "Duplicate this built-in controller to edit it" }
        val revision = profile.copy(modifiedAt = maxOf(System.currentTimeMillis(), profile.createdAt))
        val checked = codec.decode(codec.encode(revision).toString().toByteArray(Charsets.UTF_8))
        replace(checked)
    }

    fun importProfile(profile: ControllerProfile): String {
        // Keep portable IDs when there is no collision so dependants can be imported afterwards.
        val imported = if (find(profile.id) == null) profile else profile.duplicate(profile.name)
        replace(imported)
        return imported.id
    }

    fun importDocument(bytes: ByteArray): String {
        require(bytes.size <= MAX_PROFILE_BUNDLE_BYTES) { "Profile bundle exceeds the supported size" }
        val data = JSONObject(bytes.toString(Charsets.UTF_8))
        if (!data.has("format")) return importProfile(codec.decode(bytes))
        val bundle = ProfileBundle.decode(data, codec).remap(profiles)
        commit(custom + bundle.members, activeId)
        return bundle.selected
    }

    fun exportDamaged(output: OutputStream) {
        check(!readable) { "The profile library is readable" }
        file.openRead().use { it.copyTo(output) }
    }

    fun resetDamaged() {
        check(!readable) { "The profile library is readable" }
        val recovery = AtomicFile(File(file.baseFile.parentFile,
            "controller-profiles-damaged-${java.util.UUID.randomUUID()}.json"))
        val backup = recovery.startWrite()
        try {
            exportDamaged(backup)
            recovery.finishWrite(backup)
        } catch (error: Exception) {
            recovery.failWrite(backup)
            throw error
        }
        readable = true
        try {
            commit(emptyList(), active.id)
            loadError = null
        } catch (error: Exception) {
            readable = false
            throw error
        }
    }

    private fun replace(checked: ControllerProfile) {
        val next = custom.toMutableList()
        val existing = next.indexOfFirst { it.id == checked.id }
        if (existing >= 0) next[existing] = checked else next.add(checked)
        commit(next, activeId)
    }

    fun delete(id: String) {
        require(find(id)?.builtIn != true) { "This controller is built in" }
        val dependent = custom.firstOrNull { it.id != id && id in it.hybrid?.quickSwitch.orEmpty().values }
        require(dependent == null) { "Remove or retarget the switch button in ${dependent?.name} before deleting this profile" }
        commit(custom.filterNot { it.id == id }, if (activeId == id) DEFAULT_PROFILE_ID else activeId,
            played = activeId != id && hasPlayed)
    }

    private fun commit(next: List<ControllerProfile>, selected: String, played: Boolean = hasPlayed) {
        check(readable) { "Saved profiles could not be read and have been left on this device" }
        require(next.size <= MAX_CUSTOM_PROFILES) { "Save up to $MAX_CUSTOM_PROFILES custom profiles" }
        validateSwitchTargets(BUILT_IN_PROFILES + next)
        val version = if (next.any { it.hybrid != null }) 5 else if (next.any { it.motion != null }) 4 else 3
        val bytes = JSONObject().put("schemaVersion", version).put("activeId", selected).put("hasPlayed", played)
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
        hasPlayed = played
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
