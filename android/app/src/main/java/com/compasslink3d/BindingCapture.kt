package com.compasslink3d

import android.app.AlertDialog
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.TextView
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import kotlin.concurrent.thread

internal fun Context.captureBinding(
    keys: Set<String>,
    mouseOnly: Boolean,
    grant: ControllerGrant?,
    back: () -> Unit,
    accept: (Binding) -> Unit,
) {
    val code = SecureRandom().nextInt().toUInt().toString(16).uppercase().padStart(8, '0')
    val input = object : TextView(this) {
        override fun performClick() = super.performClick()
    }.apply {
        text = if (mouseOnly) "Click a mouse button on this card."
            else "Press a key on a connected keyboard or click a mouse button on this card."
        setTextColor(MUTED_TEXT)
        isFocusableInTouchMode = true
        setPadding(px(20), px(14), px(20), px(16))
    }
    val offer = hint(if (grant == null) "Connect to CompassLink on your PC to use dashboard capture."
        else "On your PC, open Capture a controller binding in the dashboard. Enter code $code.").apply {
        setPadding(px(20), 0, px(20), px(12))
    }
    val dialog = AlertDialog.Builder(this).setTitle("Capture keyboard or mouse")
        .setView(builderRoot().apply { addView(input); addView(offer) })
        .setNegativeButton("Cancel") { _, _ -> back() }.create()
    fun deliver(binding: Binding) {
        if (!dialog.isShowing) return
        if (mouseOnly && binding.type != BindingType.MOUSE_BUTTON) {
            input.setText(R.string.capture_invalid_mouse_slot)
        } else {
            dialog.dismiss()
            accept(binding)
        }
    }
    dialog.setOnCancelListener { back() }
    dialog.setOnKeyListener { _, keyCode, event ->
        if (keyCode == KeyEvent.KEYCODE_BACK) return@setOnKeyListener false
        if (mouseOnly) return@setOnKeyListener true
        val binding = event.capturedKeyboard(keys)
        if (binding != null) {
            val modifier = binding.target in MODIFIERS
            if (modifier && event.action == KeyEvent.ACTION_UP || !modifier &&
                event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) deliver(binding)
        } else if (event.action == KeyEvent.ACTION_DOWN) input.setText(R.string.capture_unsupported_key)
        true
    }
    fun mouse(event: MotionEvent): Boolean {
        if (!event.isFromSource(InputDevice.SOURCE_MOUSE)) return false
        val button = when {
            event.buttonState and MotionEvent.BUTTON_SECONDARY != 0 -> "RIGHT"
            event.buttonState and MotionEvent.BUTTON_TERTIARY != 0 -> "MIDDLE"
            event.buttonState and MotionEvent.BUTTON_PRIMARY != 0 -> "LEFT"
            else -> return false
        }
        input.performClick()
        deliver(Binding(BindingType.MOUSE_BUTTON, button))
        return true
    }
    input.setOnGenericMotionListener { _, event -> event.actionMasked == MotionEvent.ACTION_BUTTON_PRESS && mouse(event) }
    input.setOnTouchListener { _, event -> event.actionMasked == MotionEvent.ACTION_DOWN && mouse(event) }
    dialog.show()
    input.requestFocus()
    if (grant == null) return
    val connection = Socket()
    val main = Handler(Looper.getMainLooper())
    dialog.setOnDismissListener { runCatching { connection.close() } }
    thread(name = "compasslink-binding-capture", isDaemon = true) {
        val result = runCatching {
            connection.use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", 8765), 1500)
                socket.soTimeout = 46_000
                val request = JSONObject().put("type", "capture.listen").put("version", 1)
                    .put("code", code).put("mode", grant.mode).put("token", grant.token)
                socket.getOutputStream().bufferedWriter(Charsets.UTF_8).apply {
                    write(request.toString())
                    newLine()
                    flush()
                }
                val reader = socket.getInputStream().bufferedReader(Charsets.UTF_8)
                val ready = JSONObject(reader.readControllerReply() ?: return@use null)
                if (ready.optString("type") != "capture.ready") return@use null
                val response = JSONObject(reader.readControllerReply() ?: return@use null)
                if (response.optString("type") != "capture.binding") return@use null
                val data = response.getJSONObject("binding")
                when (data.getString("type")) {
                    "KEYBOARD" -> {
                        val key = data.getString("key")
                        val modifiers = data.getJSONArray("modifiers").let { list ->
                            require(list.length() <= MODIFIERS.size)
                            List(list.length(), list::getString)
                        }
                        require(key in keys && modifiers.distinct().size == modifiers.size &&
                            modifiers.all { it in MODIFIERS && it != key })
                        Binding(BindingType.KEYBOARD, key, modifiers)
                    }
                    "MOUSE_BUTTON" -> data.getString("button").takeIf { it in MOUSE_BUTTONS }
                        ?.let { Binding(BindingType.MOUSE_BUTTON, it) }
                    else -> null
                }
            }
        }.getOrNull()
        main.post {
            if (dialog.isShowing) {
                if (result != null) deliver(result)
                else offer.setText(R.string.capture_expired)
            }
        }
    }
}

private fun KeyEvent.capturedKeyboard(keys: Set<String>): Binding? {
    val name = KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_")
    val key = when (name) {
        "EQUALS" -> "EQUALS"
        "DEL" -> "BACKSPACE"
        "FORWARD_DEL" -> "DELETE"
        "GRAVE" -> "BACKTICK"
        "APOSTROPHE" -> "QUOTE"
        else -> name.removePrefix("DPAD_").removePrefix("MOVE_")
            .removeSuffix("_LEFT").removeSuffix("_RIGHT").replace("PAGE_", "PAGE")
    }
    if (key !in keys) return null
    val modifiers = listOfNotNull("SHIFT".takeIf { isShiftPressed }, "CTRL".takeIf { isCtrlPressed },
        "ALT".takeIf { isAltPressed }).filterNot { it == key }
    return Binding(BindingType.KEYBOARD, key, modifiers)
}
