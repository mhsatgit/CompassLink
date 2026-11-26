package com.compasslink3d

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal const val MAX_CUSTOM_PROFILES = 32
internal const val MAX_PROFILE_BUNDLE_BYTES = MAX_CUSTOM_PROFILES * MAX_PROFILE_BYTES + 4096
private const val BUNDLE_FORMAT = "compasslink.profile-bundle"

internal data class ProfileBundle(val selected: String, val members: List<ControllerProfile>) {
    fun encode(codec: ProfileCodec): JSONObject = JSONObject().put("format", BUNDLE_FORMAT)
        .put("bundleVersion", 1).put("selected", selected)
        .put("profiles", JSONArray(members.map(codec::encode)))

    fun remap(installed: List<ControllerProfile>): ProfileBundle {
        val existing = installed.mapTo(HashSet()) { it.id }
        val ids = members.mapTo(HashSet()) { it.id }
        require(ids.size == members.size && selected in ids) { "Invalid or duplicate bundle profile IDs" }
        require(members.isNotEmpty() && members.none { it.builtIn }) { "A bundle must contain only custom profiles" }
        require(installed.count { !it.builtIn } + members.size <= MAX_CUSTOM_PROFILES) {
            "Save up to $MAX_CUSTOM_PROFILES custom profiles"
        }
        val reserved = (existing + ids).toMutableSet()
        val assigned = members.associate { profile ->
            profile.id to if (profile.id !in existing) profile.id else generateSequence { UUID.randomUUID().toString() }
                .first(reserved::add)
        }
        val imported = members.map { profile ->
            profile.copy(id = assigned.getValue(profile.id), hybrid = profile.hybrid?.let { options ->
                options.copy(quickSwitch = options.quickSwitch.mapValues { (_, target) -> assigned[target] ?: target })
            }).also(ControllerProfile::validateHybrid)
        }
        validateSwitchTargets(installed + imported)
        return ProfileBundle(assigned.getValue(selected), imported)
    }

    companion object {
        fun export(profile: ControllerProfile, installed: List<ControllerProfile>): ProfileBundle {
            require(!profile.builtIn) { "Built-in controllers are already included on every device" }
            val lookup = installed.associateBy { it.id }
            val members = linkedMapOf<String, ControllerProfile>()
            fun visit(item: ControllerProfile) {
                if (item.builtIn || members.putIfAbsent(item.id, item) != null) return
                for (id in item.hybrid?.quickSwitch.orEmpty().values.distinct().sorted()) {
                    visit(requireNotNull(lookup[id]) { "Switch target unavailable in ${item.name}" })
                }
            }
            visit(profile)
            return ProfileBundle(profile.id, members.values.toList())
        }

        fun decode(data: JSONObject, codec: ProfileCodec): ProfileBundle {
            require(data.length() == 4 && data.get("format") == BUNDLE_FORMAT && data.get("bundleVersion") == 1) {
                "Unsupported profile bundle"
            }
            val values = data.getJSONArray("profiles")
            require(values.length() in 1..MAX_CUSTOM_PROFILES) { "Invalid number of profiles in bundle" }
            return ProfileBundle(data.getString("selected"), List(values.length()) {
                codec.decode(values.getJSONObject(it).toString().toByteArray(Charsets.UTF_8))
            })
        }
    }
}

internal fun validateSwitchTargets(profiles: List<ControllerProfile>) {
    val ids = profiles.mapTo(HashSet()) { it.id }
    for (profile in profiles) {
        require(profile.hybrid?.quickSwitch.orEmpty().values.all { it in ids }) {
            "A switch target in ${profile.name} is unavailable. Import it with its dependencies or install the target first."
        }
    }
}
