package com.nnexe.overlaybrowser

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Background TCP loopback client for communicating with the Frida-Gum agent on 127.0.0.1:39481.
 * Uses newline-delimited UTF-8 messages and automatically reconnects when disconnected.
 */
class AgentClient(
    private val host: String = DEFAULT_HOST,
    private val port: Int = DEFAULT_PORT,
    private val onLine: (String) -> Unit,
    private val onStatus: (Boolean, String) -> Unit,
) {
    private val readerExecutor = Executors.newSingleThreadExecutor()
    private val writerExecutor = Executors.newSingleThreadExecutor()
    private val running = AtomicBoolean(false)

    @Volatile private var socket: Socket? = null
    @Volatile private var writer: BufferedWriter? = null
    @Volatile private var connected = false

    val isConnected: Boolean
        get() = connected

    fun start() {
        if (running.getAndSet(true)) return
        readerExecutor.execute {
            while (running.get()) {
                try {
                    onStatus(false, "Connecting to $host:$port...")
                    Socket().use { sock ->
                        sock.tcpNoDelay = true
                        sock.connect(InetSocketAddress(host, port), 2000)
                        socket = sock
                        val reader = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.UTF_8))
                        val out = BufferedWriter(OutputStreamWriter(sock.getOutputStream(), Charsets.UTF_8))
                        writer = out
                        connected = true
                        onStatus(true, "Connected ($host:$port)")

                        while (running.get()) {
                            val line = reader.readLine() ?: break
                            onLine(line)
                        }
                    }
                } catch (e: Exception) {
                    if (running.get()) {
                        onStatus(false, "Disconnected: ${e.message ?: "connection closed"}")
                    }
                } finally {
                    connected = false
                    writer = null
                    socket = null
                }
                if (running.get()) {
                    try {
                        Thread.sleep(1500L)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            }
            onStatus(false, "Stopped")
        }
    }

    fun reconnect() {
        try {
            socket?.close()
        } catch (_: Exception) {}
    }

    fun send(command: String) {
        val out = writer ?: run {
            onLine("[err] Not connected to $host:$port")
            return
        }
        writerExecutor.execute {
            try {
                out.write(command.trimEnd('\r', '\n'))
                out.write("\n")
                out.flush()
            } catch (e: Exception) {
                onLine("[err] Send failed: ${e.message ?: "write error"}")
            }
        }
    }

    fun stop() {
        running.set(false)
        connected = false
        try {
            socket?.close()
        } catch (_: Exception) {}
        readerExecutor.shutdownNow()
        writerExecutor.shutdownNow()
    }

    companion object {
        const val DEFAULT_HOST = "127.0.0.1"
        const val DEFAULT_PORT = 39481
    }
}
