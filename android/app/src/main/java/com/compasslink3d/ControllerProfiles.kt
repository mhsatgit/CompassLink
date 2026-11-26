package com.compasslink3d

import android.app.AlertDialog
import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView

internal class ControllerProfiles(
    private val context: Context,
    private val store: ControllerProfileStore,
    private val onPlay: (ControllerProfile, ProfileOrientation) -> Unit,
    private val onEdit: (ControllerProfile, Boolean) -> Unit,
    private val onImport: () -> Unit,
    private val onExport: (ControllerProfile) -> Unit,
    initialSelection: String? = null, initialScroll: Int = 0,
) {
    var selectedId = initialSelection?.let(store::find)?.id ?: store.active.id
        private set
    private val scroll = ScrollView(context)
    val scrollY get() = scroll.scrollY
    private var playOrientation = selected().orientation
    private val list = context.builderRoot()
    private val preview = LayoutPreview(context)
    private val details = context.hint("")
    private val edit = context.builderButton("Edit", icon = R.drawable.ic_controller_edit) { edit() }
    private val orientationButton = context.builderButton(playOrientation.title, icon = R.drawable.ic_controller_phone) { chooseOrientation() }

    val view: View = context.builderRoot().apply {
        addView(context.heading("Controllers"))
        val categories = context.builderRoot().apply {
            addView(context.actionRow(
                context.builderButton("New profile", icon = R.drawable.ic_controller_add) { create() },
                context.builderButton("Import JSON", icon = R.drawable.ic_controller_import, click = onImport),
            ))
            store.loadError?.let { addView(context.hint(it)) }
            addView(scroll.apply { addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        val selection = ScrollView(context).apply {
            isFillViewport = true
            addView(context.controlPanel().apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(preview, LinearLayout.LayoutParams(-1, context.px(76)))
                addView(details)
                addView(context.actionRow(
                    orientationButton,
                    context.builderButton("More", icon = R.drawable.ic_controller_more) { more() },
                ))
                addView(context.actionRow(
                    edit,
                    context.builderButton("Play", highlight = true, icon = R.drawable.ic_controller_play) {
                        onPlay(selected(), playOrientation)
                    },
                ))
            })
        }
        val body = object : LinearLayout(context) {
            init {
                orientation = VERTICAL
                addView(categories, LayoutParams(-1, 0, 1f))
                addView(selection, LayoutParams(-1, 0, 1f))
            }

            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val w = MeasureSpec.getSize(widthMeasureSpec)
                val h = MeasureSpec.getSize(heightMeasureSpec)
                val wide = w >= context.px(500) && w >= h * 1.25
                val direction = if (wide) HORIZONTAL else VERTICAL
                if (orientation != direction) {
                    orientation = direction
                    categories.layoutParams = if (wide) LayoutParams(0, -1, 1.15f).apply { marginEnd = context.px(12) }
                        else LayoutParams(-1, 0, 1f)
                    selection.layoutParams = if (wide) LayoutParams(0, -1, 1f) else LayoutParams(-1, 0, 1f)
                }
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            }
        }
        addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    init { refresh(); scroll.post { scroll.scrollTo(0, initialScroll) } }

    private fun selected() = store.find(selectedId) ?: DEFAULT_PROFILE

    private fun refresh() {
        list.removeAllViews()
        val chosen = selected()
        for ((builtInGroup, group) in store.profiles.groupBy { it.builtIn }) {
            list.addView(context.hint(if (builtInGroup) "Built-in layouts" else "My layouts"))
            for (profile in group) {
                val marker = if (profile.id == store.active.id) "  ·  Active" else ""
                val item = context.builderButton(profile.name + marker, profile.id == chosen.id, R.drawable.ic_controller_gamepad) {
                    if (selectedId != profile.id) {
                        selectedId = profile.id
                        playOrientation = profile.orientation
                        refresh()
                    }
                }
                item.gravity = Gravity.START or Gravity.CENTER_VERTICAL
                item.isSelected = profile.id == chosen.id
                val params = LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.px(6) }
                list.addView(item, params)
            }
        }
        val builtIn = chosen.builtIn
        refreshOrientation()
        edit.text = if (builtIn) "Edit a copy" else "Edit"
        orientationButton.text = playOrientation.title
        orientationButton.visibility = if (chosen.legacyOrientationChoice) View.VISIBLE else View.GONE
    }

    fun refreshOrientation() {
        val profile = selected()
        val orientation = if (profile.legacyOrientationChoice) playOrientation else
            profile.lockedOrientation ?: context.resources.configuration.layoutOrientation
        preview.profile = profile.layoutFor(orientation)
        val orientationLabel = if (profile.legacyOrientationChoice || profile.lockedOrientation != null)
            orientation.title else "Follows device"
        val supported = listOf(OutputMode.KEYBOARD, OutputMode.GAMEPAD).filter { mode ->
            profile.motionsFor(mode).isNotEmpty() || profile.controls.any { control ->
                control.style.visible && control.bindings.values.any { it.supports(mode) }
            }
        }
        val outputLabel = when {
            profile.hybrid?.hybrid == true || profile.requiresHybridTouchLook -> OutputMode.HYBRID.title
            supported.isEmpty() -> "No output bindings yet"
            else -> supported.joinToString(" / ") { it.title }
        }
        val count = profile.controls.size
        val countLabel = context.resources.getQuantityString(R.plurals.profile_control_count, count, count)
        val detailLabel = if (profile.builtIn) R.string.built_in_profile_details else R.string.profile_details
        details.text = listOf(context.getString(detailLabel, profile.name, countLabel), orientationLabel, outputLabel).joinToString(" · ")
    }

    private fun chooseOrientation() {
        context.choose("Play orientation", ProfileOrientation.entries.map { it.title }) { index ->
            playOrientation = ProfileOrientation.entries[index]
            refresh()
        }
    }

    private fun more() {
        val profile = selected()
        val actions = mutableListOf<Pair<String, () -> Unit>>(
            "Duplicate" to ::duplicate,
            "Export JSON" to { onExport(profile) },
        )
        if (!profile.builtIn) {
            actions.add("Rename" to ::rename)
            actions.add("Delete" to ::delete)
        }
        context.choose(profile.name, actions.map { it.first }) { actions[it].second() }
    }

    private fun readName(name: String): String {
        val trimmed = name.trim()
        require(trimmed.isNotBlank() && trimmed.none { it.isISOControl() }) { "Enter a profile name" }
        return trimmed
    }

    private fun create() {
        val form = ControllerForm(context)
        val name = form.text("Profile name", "My Controller")
        val template = form.choice("Start with", listOf("Blank layout") + BUILT_IN_PROFILES.map { it.name }, "Blank layout") { it }
        form.show("Create controller", "Create") {
            val base = BUILT_IN_PROFILES.find { it.name == template() } ?: DEFAULT_PROFILE.copy(
                controls = emptyList(), hybrid = HybridSettings(hybrid = false))
            val orientation = base.lockedOrientation ?: context.resources.configuration.layoutOrientation
            val layout = base.layoutFor(orientation)
            val settings = layout.hybrid ?: HybridSettings(hybrid = false, orientation = layout.inferredPolicy())
            onEdit(layout.copy(hybrid = settings).duplicate(readName(name.value())), true)
        }
    }

    private fun rename() {
        val profile = selected()
        val form = ControllerForm(context)
        val name = form.text("Profile name", profile.name)
        form.show("Rename controller", "Rename") {
            store.save(profile.copy(name = readName(name.value())))
            refresh()
        }
    }

    private fun duplicate() {
        val profile = selected()
        val form = ControllerForm(context)
        val name = form.text("Name for the copy", (profile.name.take(MAX_LABEL - 5) + " copy"))
        form.show("Duplicate controller", "Duplicate") {
            val copy = copyLayout(profile).duplicate(readName(name.value()))
            store.save(copy)
            selectedId = copy.id
            playOrientation = copy.orientation
            refresh()
        }
    }

    private fun edit() {
        val profile = selected()
        if (profile.builtIn) {
            editBuiltInController(context, copyLayout(profile), onEdit)
        } else {
            onEdit(profile, false)
        }
    }

    private fun copyLayout(profile: ControllerProfile): ControllerProfile {
        val orientation = when {
            profile.legacyOrientationChoice -> playOrientation
            profile.lockedOrientation != null -> requireNotNull(profile.lockedOrientation)
            profile.builtIn -> context.resources.configuration.layoutOrientation
            else -> profile.orientation
        }
        return profile.layoutFor(orientation)
    }

    private fun delete() {
        val profile = selected()
        AlertDialog.Builder(context).setTitle("Delete controller?")
            .setMessage(profile.name)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                context.reportErrors {
                    store.delete(profile.id)
                    selectedId = store.active.id
                    playOrientation = store.active.orientation
                    refresh()
                }
            }.show()
    }
}

internal fun editBuiltInController(context: Context, profile: ControllerProfile, edit: (ControllerProfile, Boolean) -> Unit) {
    val form = ControllerForm(context)
    val input = form.text("Name for the copy", "${profile.name} copy")
    form.show("Customize ${profile.name}", "Edit copy") {
        val name = input.value().trim()
        require(name.isNotBlank() && name.none { it.isISOControl() }) { "Enter a profile name" }
        edit(profile.duplicate(name), true)
    }
}
