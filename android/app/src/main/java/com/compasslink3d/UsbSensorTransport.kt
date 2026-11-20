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
    private val onSensorStatus: (String) -> Unit,
    private val onControllerStatus: (Boolean, String) -> Unit,
) {
    private class ControllerLink(val socket: Socket, val protocol: ControllerProtocol) {
        val keys = ArrayBlockingQueue<ControllerTransition>(64)
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
                        val link = controllerToken?.let { ControllerLink(socket, ControllerProtocol(it)) }
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
            if (now >= nextControl) {
                val ready = readyLink.get() === link
                writer.sendLine(if (ready) link.protocol.heartbeat() else link.protocol.start())
                nextControl = now + if (ready) 500 else 1000
            }
            val event = link.keys.poll(20, TimeUnit.MILLISECONDS)
            if (event != null && readyLink.get() === link) writer.sendLine(link.protocol.key(event))
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
                            lastError = null
                            if (readyLink.compareAndSet(null, link)) {
                                onControllerStatus(true, "Connected · Windows input engine ready")
                            }
                        }
                        "controller.error" -> {
                            val wasReady = readyLink.compareAndSet(link, null)
                            link.keys.clear()
                            lastError = reply.optString("message", "Windows controller unavailable")
                            onControllerStatus(false, lastError)
                            if (wasReady) break
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

    fun sendKey(event: ControllerTransition) {
        val link = readyLink.get() ?: return
        if (!link.keys.offer(event)) {
            // Losing even one transition can leave a key held. Close and start neutral instead.
            readyLink.compareAndSet(link, null)
            closeSocket(link.socket)
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
