package com.compasslink3d

import android.os.SystemClock
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** Streams the latest sensor sample and ordered controller events over one ADB reverse socket. */
internal class UsbSensorTransport(
    private val controllerToken: String?,
    private val profile: ControllerProfile?,
    private val onSensorStatus: (String) -> Unit,
    private val onControllerStatus: (Boolean, String) -> Unit,
) {
    private class ControllerLink(val socket: Socket, val protocol: ControllerProtocol) {
        val digital = ArrayBlockingQueue<ControllerInput>(64)
        val continuous = ContinuousInput()
        @Volatile var authenticated = false
        @Volatile var revision = 0L
    }

    private val latest = AtomicReference<String?>()
    private val connection = AtomicReference<Socket?>()
    private val readyLink = AtomicReference<ControllerLink?>()
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
                        val link = controllerToken?.takeIf { profile != null }?.let { ControllerLink(socket, ControllerProtocol(it)) }
                        if (link != null) replies = readReplies(link)
                        writePackets(socket.getOutputStream().bufferedWriter(Charsets.UTF_8), link)
                    } catch (_: IOException) {
                        if (running) onSensorStatus("Waiting for PC server and ADB tunnel (USB / wireless).")
                    } finally {
                        val wasReady = readyLink.getAndSet(null) != null
                        connection.compareAndSet(socket, null)
                        closeSocket(socket)
                        replies?.join()
                        if (running && controllerToken != null && (wasReady || replies == null)) {
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
                latest.getAndSet(null)?.let { writer.sendLine(it) }
                nextSample = now + 20
            }
            if (link == null) {
                Thread.sleep(20)
                continue
            }
            if (link.socket.isClosed) return
            if (link.authenticated && link.revision == 0L) {
                val (revision, message) = link.protocol.profile(requireNotNull(profile).descriptor())
                link.revision = revision
                writer.sendLine(message)
            }
            if (now >= nextControl) {
                val authenticated = link.authenticated
                writer.sendLine(if (authenticated) link.protocol.heartbeat() else link.protocol.start())
                nextControl = now + if (authenticated) 500 else 1000
            }
            val event = link.digital.poll(15, TimeUnit.MILLISECONDS)
            if (readyLink.get() === link) {
                if (event != null) writer.sendLine(link.protocol.input(event, link.revision))
                if (now >= nextContinuous && link.digital.isEmpty()) {
                    for (update in link.continuous.drain()) writer.sendLine(link.protocol.input(update, link.revision))
                    nextContinuous = now + 25
                }
            }
        }
    }

    private fun readReplies(link: ControllerLink): Thread {
        link.socket.soTimeout = 3000
        return thread(name = "CompassLink3D-controller-replies", isDaemon = true) {
            var lastError: String? = null
            try {
                val reader = link.socket.getInputStream().bufferedReader(Charsets.UTF_8)
                while (running) {
                    val line = reader.readLine() ?: break
                    val reply = JSONObject(line)
                    if (!link.protocol.accepts(reply)) continue
                    when (reply.optString("type")) {
                        "controller.ready" -> {
                            link.authenticated = true
                            onControllerStatus(false, "Synchronizing the controller profile…")
                        }
                        "controller.profile.ready" -> {
                            if (link.revision == 0L || reply.optLong("revision") != link.revision) continue
                            lastError = null
                            if (readyLink.compareAndSet(null, link)) {
                                onControllerStatus(true, "Connected · Profile synchronized")
                            }
                        }
                        "controller.profile.error" -> {
                            if (reply.optLong("revision") != link.revision) continue
                            lastError = reply.optString("message", "Controller profile rejected by the PC")
                            onControllerStatus(false, lastError)
                        }
                        "controller.error" -> {
                            val wasReady = readyLink.compareAndSet(link, null)
                            link.digital.clear()
                            link.continuous.clear()
                            lastError = reply.optString("message", "Windows controller unavailable")
                            onControllerStatus(false, lastError)
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
                if (running && lastError == null) {
                    onControllerStatus(false, "Waiting for PC server and ADB tunnel.")
                }
            }
        }
    }

    fun send(line: String) {
        if (running) latest.set(line)
    }

    fun sendInput(event: ControllerInput) {
        val link = readyLink.get() ?: return
        if (event == ControllerInput.Neutral) {
            link.digital.clear()
            link.continuous.clear()
        }
        when (event) {
            is ControllerInput.Digital, ControllerInput.Neutral -> if (!link.digital.offer(event)) {
                // Losing even one transition can leave a native output held.
                readyLink.compareAndSet(link, null)
                closeSocket(link.socket)
            }
            else -> link.continuous.offer(event)
        }
    }

    fun close() {
        running = false
        readyLink.set(null)
        connection.getAndSet(null)?.let(::closeSocket)
        worker?.interrupt()
        latest.set(null)
    }

    private fun BufferedWriter.sendLine(line: String) {
        write(line)
        newLine()
        flush()
    }

    private fun closeSocket(socket: Socket) {
        try {
            socket.close()
        } catch (_: IOException) {
            // A failed close cannot make this disposable socket reusable.
        }
    }
}
