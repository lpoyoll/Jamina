package com.jamina.diagnostics

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import kotlin.random.Random

data class SpeedResult(val url: String, val bytes: Long, val seconds: Float, val error: String? = null) {
    val mbps: Float get() = if (seconds <= 0f) 0f else bytes * 8f / seconds / 1_000_000f
}

object SpeedTest {
    suspend fun download(url: String, maxSeconds: Float = 8f, maxBytes: Long = 200_000_000, onProgress: (Float) -> Unit = {}): SpeedResult =
        withContext(Dispatchers.IO) {
            try {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 6000; c.readTimeout = 8000; c.useCaches = false
                c.setRequestProperty("Cache-Control", "no-cache")
                val input = c.inputStream
                val buf = ByteArray(64 * 1024)
                var total = 0L
                val t0 = SystemClock.elapsedRealtimeNanos()
                var lastReport = t0
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    val now = SystemClock.elapsedRealtimeNanos()
                    val el = (now - t0) / 1e9f
                    if (now - lastReport > 250_000_000) { onProgress(total * 8f / el / 1e6f); lastReport = now }
                    if (el >= maxSeconds || total >= maxBytes) break
                }
                val secs = (SystemClock.elapsedRealtimeNanos() - t0) / 1e9f
                runCatching { input.close() }
                c.disconnect()
                SpeedResult(url, total, secs)
            } catch (e: Exception) {
                SpeedResult(url, 0, 0f, e.message ?: e.javaClass.simpleName)
            }
        }

    suspend fun upload(url: String, maxSeconds: Float = 8f, maxBytes: Long = 30_000_000, onProgress: (Float) -> Unit = {}): SpeedResult =
        withContext(Dispatchers.IO) {
            try {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 6000; c.readTimeout = 15000
                c.doOutput = true
                c.requestMethod = "POST"
                c.setChunkedStreamingMode(64 * 1024)
                c.setRequestProperty("Content-Type", "application/octet-stream")
                val chunk = Random.nextBytes(64 * 1024)
                var total = 0L
                val t0 = SystemClock.elapsedRealtimeNanos()
                var lastReport = t0
                c.outputStream.use { out ->
                    while (true) {
                        out.write(chunk)
                        total += chunk.size
                        val now = SystemClock.elapsedRealtimeNanos()
                        val el = (now - t0) / 1e9f
                        if (now - lastReport > 250_000_000) { onProgress(total * 8f / el / 1e6f); lastReport = now }
                        if (el >= maxSeconds || total >= maxBytes) break
                    }
                    out.flush()
                }
                val code = c.responseCode
                val secs = (SystemClock.elapsedRealtimeNanos() - t0) / 1e9f
                c.disconnect()
                SpeedResult(url, total, secs, if (code in 200..299) null else "HTTP $code")
            } catch (e: Exception) {
                SpeedResult(url, 0, 0f, e.message ?: e.javaClass.simpleName)
            }
        }
}

/**
 * Minimal HTTP server so another LAN device (e.g. a wired PC) can measure throughput to/from
 * this phone:  curl -o /dev/null http://PHONE_IP:PORT/download
 */
class LanTestServer(private val scope: CoroutineScope) {
    private var server: ServerSocket? = null
    private var job: Job? = null
    var port: Int = 0
        private set

    val running: Boolean get() = job?.isActive == true

    fun start(preferredPort: Int = 8099): Int {
        if (running) return port
        val ss = ServerSocket(preferredPort).also { server = it }
        port = ss.localPort
        job = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val s = runCatching { ss.accept() }.getOrNull() ?: break
                launch { handle(s) }
            }
        }
        return port
    }

    fun stop() {
        runCatching { server?.close() }
        job?.cancel()
        server = null
    }

    private fun handle(s: Socket) {
        try {
            s.soTimeout = 10000
            val reader = s.getInputStream().bufferedReader()
            val requestLine = reader.readLine() ?: return
            var contentLength = 0L
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                if (line.startsWith("Content-Length:", true)) contentLength = line.substringAfter(":").trim().toLongOrNull() ?: 0
            }
            val out = s.getOutputStream()
            if (requestLine.startsWith("GET /download")) {
                val size = 200L * 1024 * 1024
                out.write("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: $size\r\nConnection: close\r\n\r\n".toByteArray())
                val chunk = Random.nextBytes(64 * 1024)
                var sent = 0L
                while (sent < size) { out.write(chunk); sent += chunk.size }
            } else {
                if (contentLength > 0) Log.d("LanTestServer", "ignoring request body of $contentLength bytes")
                val body = "Jamina LAN test server. GET /download for a 200 MB stream."
                out.write("HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body".toByteArray())
            }
            out.flush()
        } catch (e: Exception) {
            Log.d("LanTestServer", "client error: ${e.message}")
        } finally {
            runCatching { s.close() }
        }
    }

    companion object {
        fun localIpv4(): String? = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback && (it.name.startsWith("wlan") || it.name.startsWith("eth")) }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is Inet4Address }?.hostAddress
        }.getOrNull()
    }
}
