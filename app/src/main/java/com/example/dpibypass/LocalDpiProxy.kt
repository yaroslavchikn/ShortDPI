package com.example.dpibypass

import android.net.VpnService
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.concurrent.Executors
import kotlin.concurrent.thread

class LocalDpiProxy(
    private val port: Int,
    private val vpn: VpnService
) {

    private val serverSocket = ServerSocket()
    private val executor = Executors.newCachedThreadPool()
    private val resolver = DohResolver()

    fun start() {
        // Bind to wildcard so the VPN address can reach it.
        serverSocket.bind(InetSocketAddress(port))
        executor.submit { acceptLoop() }
    }

    fun stop() {
        runCatching { serverSocket.close() }
        executor.shutdownNow()
    }

    private fun acceptLoop() {
        while (!serverSocket.isClosed) {
            try {
                val client = serverSocket.accept()
                client.soTimeout = 120_000
                executor.submit { handleClient(client) }
            } catch (_: Exception) {
                // ignore
            }
        }
    }

    private fun handleClient(client: Socket) {
        try {
            val input = client.getInputStream()
            val output = client.getOutputStream()

            val headerText = readHeaders(input)
            if (headerText.isBlank()) {
                closeQuiet(client)
                return
            }

            val lines = headerText.split("\r\n").filter { it.isNotEmpty() }
            if (lines.isEmpty()) {
                closeQuiet(client)
                return
            }

            val firstLineParts = lines[0].split(" ")
            if (firstLineParts.size < 3) {
                closeQuiet(client)
                return
            }

            val method = firstLineParts[0].uppercase()
            val target = firstLineParts[1]
            val headers = lines.drop(1)

            if (method == "CONNECT") {
                handleConnect(client, output, target)
            } else {
                handleHttp(client, output, method, target, headers)
            }

        } catch (_: Exception) {
            closeQuiet(client)
        }
    }

    private fun handleConnect(
        client: Socket,
        clientOutput: OutputStream,
        target: String
    ) {
        val (host, targetPort) = parseHostPort(target)

        val upstream = connectSmart(host, targetPort)
        if (upstream == null) {
            writeHttpResponse(clientOutput, "502 Bad Gateway")
            closeQuiet(client)
            return
        }

        writeHttpResponse(clientOutput, "200 Connection established")

        tunnel(
            client = client,
            upstream = upstream,
            fragment = shouldFragment(host, targetPort)
        )
    }

    private fun handleHttp(
        client: Socket,
        clientOutput: OutputStream,
        method: String,
        target: String,
        headers: List<String>
    ) {
        var host: String? = null
        var targetPort = 80
        var path = target

        val absolute = target.startsWith("http://", true)

        if (absolute) {
            try {
                val uri = URI(target)
                host = uri.host
                targetPort = if (uri.port == -1) 80 else uri.port

                val rawPath = uri.path
                path = if (rawPath.isNullOrEmpty()) "/" else rawPath

                val rawQuery = uri.rawQuery
                if (!rawQuery.isNullOrEmpty()) {
                    path += "?$rawQuery"
                }
            } catch (_: Exception) {
                writeHttpResponse(clientOutput, "400 Bad Request")
                closeQuiet(client)
                return
            }
        }

        if (host == null) {
            val hostHeader = headers.firstOrNull {
                it.startsWith("Host:", true)
            }

            val hostValue = hostHeader
                ?.substringAfter(":", "")
                ?.trim()

            if (!hostValue.isNullOrEmpty()) {
                host = hostValue.substringBefore(":")
                val parsedPort = hostValue.substringAfter(":", "").toIntOrNull()
                if (parsedPort != null) {
                    targetPort = parsedPort
                }
            }
        }

        if (host == null) {
            writeHttpResponse(clientOutput, "400 Bad Request")
            closeQuiet(client)
            return
        }

        val upstream = connectSmart(host, targetPort)
        if (upstream == null) {
            writeHttpResponse(clientOutput, "502 Bad Gateway")
            closeQuiet(client)
            return
        }

        val newHeaders = ArrayList<String>()
        newHeaders.add("$method $path HTTP/1.1")

        headers
            .filterNot {
                it.startsWith("Proxy-Connection", true) ||
                it.startsWith("Proxy-Authorization", true)
            }
            .forEach { newHeaders.add(it) }

        val headerBlock = newHeaders.joinToString("\r\n") + "\r\n\r\n"

        upstream.getOutputStream().write(headerBlock.toByteArray(Charsets.ISO_8859_1))
        upstream.getOutputStream().flush()

        tunnel(
            client = client,
            upstream = upstream,
            fragment = shouldFragment(host, targetPort)
        )
    }

    private fun parseHostPort(target: String): Pair<String, Int> {
        if (target.startsWith("[")) {
            val host = target.substringAfter("[").substringBefore("]")
            val portString = target.substringAfter("]:", "443")
            val port = portString.toIntOrNull() ?: 443
            return host to port
        }

        val host = target.substringBeforeLast(":")
        val portString = target.substringAfterLast(":", "443")
        val port = portString.toIntOrNull() ?: 443

        return host to port
    }

    private fun connectSmart(host: String, port: Int): Socket? {
        val ip = resolver.resolve(host)
        val firstTarget = ip ?: host

        val connected = tryConnect(firstTarget, port)
        if (connected != null) return connected

        if (ip != null) {
            return tryConnect(host, port)
        }

        return null
    }

    private fun tryConnect(host: String, port: Int): Socket? {
        return try {
            val socket = Socket()
            vpn.protect(socket)
            socket.tcpNoDelay = true
            socket.soTimeout = 120_000
            socket.connect(InetSocketAddress(host, port), 10_000)
            socket
        } catch (_: Exception) {
            null
        }
    }

    private fun tunnel(
        client: Socket,
        upstream: Socket,
        fragment: Boolean
    ) {
        val closeAll = {
            closeQuiet(client)
            closeQuiet(upstream)
        }

        thread {
            try {
                relay(
                    input = client.getInputStream(),
                    output = upstream.getOutputStream(),
                    fragmentFirst = fragment
                )
            } catch (_: Exception) {
                // ignore
            } finally {
                closeAll()
            }
        }

        thread {
            try {
                relay(
                    input = upstream.getInputStream(),
                    output = client.getOutputStream(),
                    fragmentFirst = false
                )
            } catch (_: Exception) {
                // ignore
            } finally {
                closeAll()
            }
        }
    }

    private fun relay(
        input: InputStream,
        output: OutputStream,
        fragmentFirst: Boolean
    ) {
        val buffer = ByteArray(65536)
        var first = fragmentFirst

        while (true) {
            val n = input.read(buffer)
            if (n < 0) break

            if (first) {
                writeFragmented(output, buffer, n)
                first = false
            } else {
                output.write(buffer, 0, n)
                output.flush()
            }
        }

        output.flush()
    }

    /**
     * Дробим первый прочитанный кусок.
     * Обычно туда попадает ClientHello или начало протокола.
     * Это и есть основная локальная атака на примитивный DPI.
     */
    private fun writeFragmented(
        output: OutputStream,
        data: ByteArray,
        length: Int
    ) {
        var offset = 0

        val pieces = intArrayOf(1, 2, 3, 5, 8, 13, 21, 34)

        for (size in pieces) {
            if (offset >= length) break

            val chunk = minOf(size, length - offset)
            output.write(data, offset, chunk)
            output.flush()

            try {
                Thread.sleep(2)
            } catch (_: InterruptedException) {
                // ignore
            }

            offset += chunk
        }

        if (offset < length) {
            output.write(data, offset, length - offset)
            output.flush()
        }
    }

    private fun readHeaders(input: InputStream): String {
        val out = ByteArrayOutputStream()
        val last = ByteArray(4)

        while (true) {
            val b = input.read()
            if (b == -1) break

            out.write(b)

            last[0] = last[1]
            last[1] = last[2]
            last[2] = last[3]
            last[3] = b.toByte()

            if (
                last[0] == 13.toByte() &&
                last[1] == 10.toByte() &&
                last[2] == 13.toByte() &&
                last[3] == 10.toByte()
            ) {
                break
            }

            if (out.size() > 65536) break
        }

        return out.toString("ISO-8859-1")
    }

    private fun writeHttpResponse(output: OutputStream, status: String) {
        val response = "HTTP/1.1 $status\r\n" +
            "Proxy-Agent: DpiBypass\r\n" +
            "Connection: keep-alive\r\n" +
            "\r\n"

        output.write(response.toByteArray(Charsets.ISO_8859_1))
        output.flush()
    }

    private fun shouldFragment(host: String, port: Int): Boolean {
        if (port == 443) return true

        val h = host.lowercase()

        return (port == 80 || port == 443) && (
            h.endsWith("youtube.com") ||
            h.endsWith("googlevideo.com") ||
            h.endsWith("ytimg.com") ||
            h.endsWith("ggpht.com") ||
            h.endsWith("google.com") ||
            h.endsWith("googleusercontent.com") ||
            h.endsWith("gvt1.com") ||
            h.endsWith("youtu.me")
        )
    }

    private fun closeQuiet(socket: Socket?) {
        runCatching { socket?.close() }
    }
}
