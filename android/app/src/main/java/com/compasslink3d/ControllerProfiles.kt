package com.compasslink3d

import android.app.AlertDialog
import android.content.Context
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView

internal class ControllerProfiles(
    private val context: Context,
    private val store: ControllerProfileStore,
    private val onMotion: () -> Unit,
    private val onPlay: (ControllerProfile) -> Unit,
    private val onEdit: (ControllerProfile, Boolean) -> Unit,
    private val onImport: () -> Unit,
    private val onExport: (ControllerProfile) -> Unit,
) {
    private var selectedId = store.active.id
    private val list = context.builderRoot()
    private val details = context.hint("")
    private val rename = context.builderButton("Rename") { rename() }
    private val delete = context.builderButton("Delete") { delete() }
    private val edit = context.builderButton("Edit") { edit() }

    val view: View = context.builderRoot().apply {
        addView(context.toolbar(context.heading("Controller Profiles"),
            context.builderButton("New", highlight = true) { create() },
            context.builderButton("Import JSON", click = onImport),
            context.builderButton("Motion / Sensor", click = onMotion)))
        store.loadError?.let { addView(context.hint(it)) }
        addView(ScrollView(context).apply { addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f))
        addView(details)
        addView(context.toolbar(
            context.builderButton("Play", highlight = true) { onPlay(selected()) },
            edit, rename,
            context.builderButton("Duplicate") { duplicate() },
            context.builderButton("Export JSON") { onExport(selected()) },
            delete,
        ))
    }

    init { refresh() }

    private fun selected() = store.find(selectedId) ?: DEFAULT_PROFILE

    private fun refresh() {
        list.removeAllViews()
        val chosen = selected()
        for (profile in store.profiles) {
            val marker = if (profile.id == store.active.id) "  ·  Active" else ""
            val item = context.builderButton(profile.name + marker, profile.id == chosen.id) {
                selectedId = profile.id
                refresh()
            }
            val params = LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.px(6) }
            list.addView(item, params)
        }
        val builtIn = chosen.id == DEFAULT_PROFILE_ID
        details.text = "${chosen.name}  ·  ${chosen.controls.size} controls${if (builtIn) "  ·  Built-in" else ""}"
        rename.isEnabled = !builtIn
        delete.isEnabled = !builtIn
        edit.text = if (builtIn) "Edit a copy" else "Edit"
    }

    private fun readName(name: String): String {
        val trimmed = name.trim()
        require(trimmed.isNotBlank() && trimmed.none { it.isISOControl() }) { "Enter a profile name" }
        return trimmed
    }

    private fun create() {
        val form = ControllerForm(context)
        val name = form.text("Profile name", "My Controller")
        val template = form.choice("Start with", listOf("Blank layout", "Default Controller"), "Blank layout") { it }
        form.show("Create controller", "Create") {
            val profile = DEFAULT_PROFILE.duplicate(readName(name.value()))
            onEdit(if (template() == "Blank layout") profile.copy(controls = emptyList()) else profile, true)
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
            val copy = profile.duplicate(readName(name.value()))
            store.save(copy)
            selectedId = copy.id
            refresh()
        }
    }

    private fun edit() {
        val profile = selected()
        if (profile.id == DEFAULT_PROFILE_ID) {
            editDefaultController(context, onEdit)
        } else {
            onEdit(profile, false)
        }
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
                    refresh()
                }
            }.show()
    }
}

internal fun editDefaultController(context: Context, edit: (ControllerProfile, Boolean) -> Unit) {
    val form = ControllerForm(context)
    val input = form.text("Name for the copy", "Default Controller copy")
    form.show("Customize default controller", "Edit copy") {
        val name = input.value().trim()
        require(name.isNotBlank() && name.none { it.isISOControl() }) { "Enter a profile name" }
        edit(DEFAULT_PROFILE.duplicate(name), true)
    }
}
