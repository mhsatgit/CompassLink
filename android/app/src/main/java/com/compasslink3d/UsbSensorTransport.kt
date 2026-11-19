package com.compasslink3d

import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** One disposable streaming session. Each restart receives its own worker and socket. */
class UsbSensorTransport(private val onStatus: (String) -> Unit) {
    private val latest = AtomicReference<String?>(null)
    private val connection = AtomicReference<Socket?>(null)
    @Volatile private var running = true
    private var worker: Thread? = null

    fun start() {
        check(worker == null) { "Transport already started" }
        worker = thread(name = "CompassLink3D-ADB", isDaemon = true) {
            while (running) {
                try {
                    Socket().use { socket ->
                        connection.set(socket)
                        if (!running) return@thread
                        socket.connect(InetSocketAddress("127.0.0.1", 8765), 1500)
                        socket.tcpNoDelay = true
                        onStatus("Connected through ADB tunnel (USB / wireless)")
                        val writer = socket.getOutputStream().bufferedWriter(Charsets.UTF_8)
                        while (running) {
                            latest.getAndSet(null)?.let { line ->
                                writer.write(line)
                                writer.newLine()
                                writer.flush()
                            }
                            Thread.sleep(20)
                        }
                    }
                } catch (_: InterruptedException) {
                    return@thread
                } catch (_: Exception) {
                    if (running) {
                        onStatus("Waiting for PC server and USB tunnel…")
                        try {
                            Thread.sleep(1000)
                        } catch (_: InterruptedException) {
                            return@thread
                        }
                    }
                } finally {
                    connection.getAndSet(null)?.let { runCatching { it.close() } }
                }
            }
        }
    }

    fun send(line: String) {
        if (running) latest.set(line)
    }

    fun close() {
        running = false
        connection.getAndSet(null)?.let { runCatching { it.close() } }
        worker?.interrupt()
        latest.set(null)
    }
}

