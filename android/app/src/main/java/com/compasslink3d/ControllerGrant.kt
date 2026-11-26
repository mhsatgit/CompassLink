package com.compasslink3d

import android.util.AtomicFile
import java.io.File
import java.io.IOException

@ConsistentCopyVisibility
internal data class ControllerGrant private constructor(val mode: String, val token: String) {
    override fun toString() = "ControllerGrant(mode=$mode)"

    companion object {
        private val TOKEN = Regex("[0-9a-f]{64}")

        fun parse(mode: String?, token: String?): ControllerGrant? =
            if ((mode == "usb" || mode == "wireless") && token != null && TOKEN.matches(token))
                ControllerGrant(mode, token)
            else null
    }
}

/** Stores only grants accepted by the desktop. The caller uses the app's no-backup directory. */
internal class ControllerGrantStore(directory: File) {
    private val file = AtomicFile(File(directory, "controller-grant"))
    var saved = load()
        private set

    private fun load(): ControllerGrant? = try {
        file.openRead().use {
            val fields = it.readBounded(80).toString(Charsets.UTF_8).split('\n')
            ControllerGrant.parse(fields.getOrNull(0), fields.getOrNull(1))
        }
    } catch (_: IOException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    fun remember(grant: ControllerGrant) {
        if (saved == grant) return
        try {
            val stream = file.startWrite()
            try {
                stream.write("${grant.mode}\n${grant.token}".toByteArray(Charsets.UTF_8))
                file.finishWrite(stream)
                saved = grant
            } catch (error: IOException) {
                file.failWrite(stream)
            }
        } catch (_: IOException) {
            // The active connection still works if persistent storage is unavailable.
        }
    }

    fun forget(grant: ControllerGrant) {
        if (saved != grant) return
        file.delete()
        saved = null
    }
}
