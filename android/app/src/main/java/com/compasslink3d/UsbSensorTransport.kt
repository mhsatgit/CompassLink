package com.compasslink3d

import android.os.SystemClock
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** Streams the latest sensor sample and ordered controller events over one ADB reverse socket. */
internal class UsbSensorTransport(
    private val controllerToken: String?,
    descriptor: JSONObject?,
    private val onSensorStatus: (String) -> Unit,
    private val onControllerStatus: (Boolean, String) -> Unit,
    private val onAuthorized: () -> Unit,
    private val onAuthorizationRejected: () -> Unit,
) {
    private class ProfileRequest(val descriptor: JSONObject) {
        val serialized = descriptor.toString()
        val createdAt = SystemClock.elapsedRealtime()
    }
    private data class Pending(val request: ProfileRequest, val revision: Long)

    private class ControllerLink(val socket: Socket, val protocol: ControllerProtocol) {
        val digital = ArrayBlockingQueue<ControllerInput>(64)
        val touch = TouchPointerQueue()
        val motionReports = MotionPointerReports()
        val continuous = ContinuousInput()
        val wake = ArrayBlockingQueue<Unit>(1)
        @Volatile var authenticated = false
        @Volatile var pending: Pending? = null

        fun clearInput() {
            digital.clear()
            touch.clear()
            motionReports.clear()
            continuous.clear()
        }
    }

    private val latest = AtomicReference<SensorSample?>()
    private val requested = AtomicReference(descriptor?.let(::ProfileRequest))
    @Volatile var freshMotion = emptyList<String>()
    private val connection = AtomicReference<Socket?>()
    private val controllerLink = AtomicReference<ControllerLink?>()
    private val readyLink = AtomicReference<ControllerLink?>()
    private val profileLock = Any()
    val profileReady: Boolean get() = readyLink.get()?.pending?.request?.let { it === requested.get() } == true
    @Volatile private var running = true
    private var worker: Thread? = null

    fun start() {
        check(worker == null) { "Transport already started" }
        worker = thread(name = "CompassLink3D-ADB", isDaemon = true) {
            try {
                while (running) {
                    val socket = Socket()
                    connection.set(socket)
                    var replies: Thread? = null
                    try {
                        if (!running) return@thread
                        socket.connect(InetSocketAddress("127.0.0.1", 8765), 1500)
                        socket.tcpNoDelay = true
                        onSensorStatus("Connected through ADB tunnel (USB / wireless)")
                        val link = controllerToken?.let {
                            ControllerLink(socket, ControllerProtocol(it, OutputMode.HYBRID))
                        }
                        controllerLink.set(link)
                        if (link != null) replies = readReplies(link)
                        writePackets(socket.getOutputStream().bufferedWriter(Charsets.UTF_8), link)
                    } catch (_: IOException) {
                        if (running) onSensorStatus("Waiting for PC server and ADB tunnel (USB / wireless).")
                    } finally {
                        val wasReady = readyLink.getAndSet(null) != null
                        controllerLink.set(null)
                        connection.compareAndSet(socket, null)
                        closeSocket(socket)
                        replies?.join()
                        if (running && requested.get() != null && (wasReady || replies == null)) {
                            onControllerStatus(false, "Waiting for PC server and ADB tunnel.")
                        }
                    }
                    if (running) Thread.sleep(1000)
                }
            } catch (_: InterruptedException) {
                // Closing the transport interrupts the writer's queue or reconnect wait.
            }
        }
    }

    private fun writePackets(writer: BufferedWriter, link: ControllerLink?) {
        var nextControl = 0L
        var nextSample = 0L
        var nextContinuous = 0L
        while (running) {
            val now = SystemClock.elapsedRealtime()
            if (now >= nextSample) {
                latest.getAndSet(null)?.let { writer.sendLine(it.encode()) }
                nextSample = now + 20
            }
            if (link == null) {
                Thread.sleep(20)
                continue
            }
            if (link.socket.isClosed) return
            val request = requested.get()
            if (request == null && link.authenticated) {
                link.authenticated = false
                link.pending = null
                writer.sendLine(link.protocol.stop())
                nextControl = 0
            }
            if (request != null && link.authenticated && link.pending?.request !== request) {
                val (revision, message) = link.protocol.profile(request.descriptor)
                link.pending = Pending(request, revision)
                writer.sendLine(message)
            }
            if (request != null && now >= nextControl) {
                val authenticated = link.authenticated
                writer.sendLine(if (authenticated) link.protocol.heartbeat(freshMotion = freshMotion) else link.protocol.start())
                nextControl = now + if (authenticated) 500 else 1000
            }
            val pending = link.pending
            if (readyLink.get() === link && pending != null && pending.request === requested.get()) {
                val event = link.digital.poll()
                if (event != null) {
                    writer.sendLine(link.protocol.input(event, pending.revision))
                    continue
                }
                var touchWritten = false
                val uptime = SystemClock.uptimeMillis()
                // Keep each sample distinct; flush a group only when Android already delivered a batch.
                // Give button edges and neutral releases priority over any pending movement.
                for (index in 0 until 16) {
                    if (!link.digital.isEmpty() || pending.request !== requested.get()) break
                    val pointer = link.touch.poll(uptime) ?: break
                    writer.sendLine(link.protocol.input(pointer, pending.revision), flushNow = false)
                    touchWritten = true
                }
                if (touchWritten) writer.flush()
                if (now >= nextContinuous && link.digital.isEmpty()) {
                    for (update in link.continuous.drain()) writer.sendLine(link.protocol.input(update, pending.revision))
                    nextContinuous = now + 25
                }
                if (link.touch.hasPending) continue
            }
            link.wake.poll(15, TimeUnit.MILLISECONDS)
        }
    }

    private fun readReplies(link: ControllerLink): Thread {
        link.socket.soTimeout = 3000
        return thread(name = "CompassLink3D-controller-replies", isDaemon = true) {
            var lastError: String? = null
            try {
                val reader = link.socket.getInputStream().bufferedReader(Charsets.UTF_8)
                while (running) {
                    val line = try {
                        reader.readControllerReply()
                    } catch (error: SocketTimeoutException) {
                        val request = requested.get()
                        if (request == null || SystemClock.elapsedRealtime() - request.createdAt < 3000) continue
                        throw error
                    } ?: break
                    val reply = JSONObject(line)
                    if (!link.protocol.accepts(reply)) continue
                    when (reply.optString("type")) {
                        "controller.ready" -> {
                            if (link.authenticated) continue
                            link.authenticated = true
                            link.wake.offer(Unit)
                            onAuthorized()
                            if (requested.get() != null) onControllerStatus(false, "Synchronizing the controller profile…")
                        }
                        "controller.profile.ready" -> {
                            val pending = link.pending ?: continue
                            if (reply.optLong("revision") != pending.revision) continue
                            lastError = null
                            val ready = synchronized(profileLock) {
                                pending.request === requested.get() && readyLink.compareAndSet(null, link)
                            }
                            if (ready) {
                                link.wake.offer(Unit)
                                val status = when (pending.request.descriptor.getString("outputMode")) {
                                    "gamepad" -> "Xbox controller connected in Windows"
                                    "hybrid" -> "Keyboard, mouse and Xbox connected"
                                    else -> "Connected · Profile synchronized"
                                }
                                onControllerStatus(true, status)
                            }
                        }
                        "controller.profile.error" -> {
                            val pending = link.pending ?: continue
                            if (reply.optLong("revision") != pending.revision || pending.request !== requested.get()) continue
                            lastError = reply.optString("message", "Controller profile rejected by the PC")
                            onControllerStatus(false, lastError)
                        }
                        "controller.error" -> {
                            val wasReady = readyLink.compareAndSet(link, null)
                            link.clearInput()
                            lastError = reply.optString("message", "Windows controller unavailable")
                            onControllerStatus(false, lastError)
                            if (reply.optString("code") == "authorization") {
                                onAuthorizationRejected()
                                break
                            }
                            if (wasReady || link.authenticated) break
                        }
                    }
                }
            } catch (_: IOException) {
                // EOF, timeout and connection errors take the same neutral reconnect path.
            } catch (_: JSONException) {
                lastError = "Invalid controller reply from the PC."
                onControllerStatus(false, lastError)
            } finally {
                readyLink.compareAndSet(link, null)
                closeSocket(link.socket)
                if (running && requested.get() != null && lastError == null) {
                    onControllerStatus(false, "Waiting for PC server and ADB tunnel.")
                }
            }
        }
    }

    fun send(sample: SensorSample) {
        if (running) latest.set(sample)
    }

    fun clearSamples() { latest.set(null) }

    fun discardPointer(id: String) {
        readyLink.get()?.let { link ->
            link.touch.discard(id)
            link.motionReports.discard(id)
            link.continuous.discardPointer(id)
        }
    }

    fun switchProfile(descriptor: JSONObject?): Boolean = synchronized(profileLock) {
        val next = descriptor?.let(::ProfileRequest)
        if (requested.get()?.serialized == next?.serialized) return@synchronized false
        val old = readyLink.getAndSet(null)
        freshMotion = emptyList()
        old?.clearInput()
        requested.set(next)
        controllerLink.get()?.wake?.offer(Unit)
        true
    }

    fun sendInput(event: ControllerInput) {
        val link = synchronized(profileLock) {
            val current = readyLink.get() ?: return
            if (current.pending?.request !== requested.get()) return
            current
        }
        if (event == ControllerInput.Neutral) {
            link.clearInput()
        }
        val release = if (event is ControllerInput.Axis &&
            event.value.x == 0.0 && event.value.y == 0.0) {
            link.continuous.discardAxis(event.control)
            true
        } else false
        when {
            event is ControllerInput.Pointer && event.timestampNs > 0 -> {
                val age = (SystemClock.elapsedRealtimeNanos() - event.timestampNs) / 1_000_000
                if (age !in 0..80) return
                val report = link.motionReports.quantize(event, SystemClock.uptimeMillis() - age) ?: return
                link.touch.offer(report)
            }
            event is ControllerInput.TouchPointer -> link.touch.offer(event)
            release || event is ControllerInput.Digital || event == ControllerInput.Neutral -> if (!link.digital.offer(event)) {
                // Losing even one transition can leave a native output held.
                readyLink.compareAndSet(link, null)
                closeSocket(link.socket)
            }
            else -> link.continuous.offer(event)
        }
        if (release || event is ControllerInput.Digital || event == ControllerInput.Neutral || event is ControllerInput.TouchPointer || event is ControllerInput.Pointer)
            link.wake.offer(Unit)
    }

    fun close() {
        running = false
        readyLink.set(null)
        connection.getAndSet(null)?.let(::closeSocket)
        worker?.interrupt()
        latest.set(null)
    }

    private fun BufferedWriter.sendLine(line: String, flushNow: Boolean = true) {
        write(line)
        newLine()
        if (flushNow) flush()
    }

    private fun closeSocket(socket: Socket) {
        try {
            socket.close()
        } catch (_: IOException) {
            // A failed close cannot make this disposable socket reusable.
        }
    }
}
