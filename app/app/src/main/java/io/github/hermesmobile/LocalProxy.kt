package io.github.hermesmobile

import android.content.res.AssetManager
import android.util.Log
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import javax.net.ssl.SSLSocketFactory
import kotlin.concurrent.thread

/**
 * Loopback origin for the WebView: http://127.0.0.1:<port>.
 *
 * Loopback is a "secure context" in Chromium, so microphone (getUserMedia), clipboard and
 * other powerful web APIs work even when the Hermes backend itself is plain http:// on a
 * tailnet address. Renderer assets are served from the APK; everything else (REST, media,
 * WebSocket upgrades) is piped verbatim to the configured backend.
 */
class LocalProxy(private val assets: AssetManager, private val upstream: String, private val pageHtml: () -> ByteArray) {
    private val up = URI(upstream)
    private val upHost = up.host
    private val upTls = up.scheme == "https"
    private val upPort = if (up.port > 0) up.port else if (upTls) 443 else 80
    private val upHostHeader = if (up.port > 0) "$upHost:${up.port}" else upHost
    private var server: ServerSocket? = null
    var port = 0
        private set

    fun start(preferred: Int = 47219): Int {
        val ss = ServerSocket()
        ss.reuseAddress = true
        try {
            ss.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), preferred), 64)
        } catch (e: Exception) {
            ss.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 64)
        }
        server = ss
        port = ss.localPort
        thread(name = "hermes-proxy-accept", isDaemon = true) {
            while (!ss.isClosed) {
                val c = try { ss.accept() } catch (e: Exception) { break }
                thread(name = "hermes-proxy-conn", isDaemon = true) { handle(c) }
            }
        }
        return port
    }

    fun stop() { try { server?.close() } catch (_: Exception) {} }

    private fun handle(client: Socket) {
        client.use { c ->
            try {
                c.tcpNoDelay = true
                val input = BufferedInputStream(c.getInputStream())
                val head = readHead(input) ?: return
                val lines = head.split("\r\n")
                val parts = lines[0].split(" ")
                if (parts.size < 3) return
                val path = parts[1]
                if (path.startsWith(MainActivity.PREFIX)) {
                    serveAsset(c.getOutputStream(), path.substringBefore('?').removePrefix(MainActivity.PREFIX))
                    return
                }
                proxy(c, input, lines)
            } catch (e: Exception) {
                Log.d("HermesProxy", "conn error: ${e.message}")
            }
        }
    }

    private fun readHead(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        var state = 0
        while (true) {
            val b = input.read()
            if (b < 0) return null
            buf.write(b)
            state = when {
                state == 0 && b == '\r'.code -> 1
                state == 1 && b == '\n'.code -> 2
                state == 2 && b == '\r'.code -> 3
                state == 3 && b == '\n'.code -> return buf.toString(Charsets.ISO_8859_1.name()).removeSuffix("\r\n\r\n")
                b == '\r'.code -> 1
                else -> 0
            }
            if (buf.size() > 64 * 1024) return null
        }
    }

    private fun serveAsset(out: OutputStream, rel0: String) {
        val rel = rel0.ifEmpty { "index.html" }
        val body: ByteArray? = try {
            when {
                rel.contains("..") -> null
                rel == "index.html" -> pageHtml()
                else -> assets.open("renderer/$rel").use { it.readBytes() }
            }
        } catch (e: Exception) { null }
        if (body == null) {
            out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            out.flush(); return
        }
        val cache = if (rel == "index.html") "no-store" else "max-age=31536000, immutable"
        out.write(("HTTP/1.1 200 OK\r\nContent-Type: ${mimeFor(rel)}\r\nContent-Length: ${body.size}\r\n" +
            "Cache-Control: $cache\r\nConnection: close\r\n\r\n").toByteArray())
        out.write(body)
        out.flush()
    }

    private fun proxy(c: Socket, clientIn: InputStream, lines: List<String>) {
        val isUpgrade = lines.any { it.lowercase().startsWith("upgrade:") }
        val sb = StringBuilder(lines[0]).append("\r\n")
        for (l in lines.drop(1)) {
            val name = l.substringBefore(':').trim().lowercase()
            val value = l.substringAfter(':', "").trim()
            when (name) {
                "host" -> sb.append("Host: $upHostHeader\r\n")
                "origin" -> sb.append("Origin: ${up.scheme}://$upHostHeader\r\n")
                "referer" -> sb.append("Referer: ${value.replace(Regex("^https?://[^/]+"), "${up.scheme}://$upHostHeader")}\r\n")
                "connection" -> if (isUpgrade) sb.append(l).append("\r\n")
                "proxy-connection", "keep-alive" -> {}
                else -> sb.append(l).append("\r\n")
            }
        }
        // One request per upstream connection keeps routing simple: later requests on a
        // kept-alive client socket might be for local assets.
        if (!isUpgrade) sb.append("Connection: close\r\n")
        sb.append("\r\n")

        val upSock: Socket = if (upTls) {
            (SSLSocketFactory.getDefault().createSocket(upHost, upPort) as javax.net.ssl.SSLSocket).apply { startHandshake() }
        } else Socket().apply { connect(InetSocketAddress(upHost, upPort), 10000) }
        upSock.use { u ->
            u.tcpNoDelay = true
            u.getOutputStream().write(sb.toString().toByteArray(Charsets.ISO_8859_1))
            u.getOutputStream().flush()
            val t = thread(isDaemon = true) {
                try { clientIn.copyTo(u.getOutputStream()) } catch (_: Exception) {}
                try { if (!upTls) u.shutdownOutput() } catch (_: Exception) {}
            }
            try { u.getInputStream().copyTo(c.getOutputStream()) } catch (_: Exception) {}
            try { c.getOutputStream().flush() } catch (_: Exception) {}
            try { c.shutdownOutput() } catch (_: Exception) {}
            t.join(200)
        }
    }

    companion object {
        fun mimeFor(rel: String): String = when (rel.substringAfterLast('.', "").lowercase()) {
            "js", "mjs" -> "text/javascript"
            "css" -> "text/css"
            "html" -> "text/html; charset=utf-8"
            "json" -> "application/json"
            "svg" -> "image/svg+xml"
            "wasm" -> "application/wasm"
            "woff2" -> "font/woff2"
            "woff" -> "font/woff"
            "ttf" -> "font/ttf"
            "webp" -> "image/webp"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "jpg", "jpeg" -> "image/jpeg"
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "ico" -> "image/x-icon"
            else -> java.net.URLConnection.guessContentTypeFromName(rel) ?: "application/octet-stream"
        }
    }
}
