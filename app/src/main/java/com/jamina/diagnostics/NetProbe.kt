package com.jamina.diagnostics

import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.random.Random

data class LatencyStats(
    val target: String,
    val method: String,
    val sent: Int,
    val received: Int,
    val rtts: List<Float>,
) {
    val lossPct: Float get() = if (sent == 0) 0f else (sent - received) * 100f / sent
    val avg: Float? get() = rtts.takeIf { it.isNotEmpty() }?.average()?.toFloat()
    val min: Float? get() = rtts.minOrNull()
    val max: Float? get() = rtts.maxOrNull()
    /** Mean absolute difference between consecutive RTTs (RFC 3550-style jitter estimate). */
    val jitter: Float? get() = if (rtts.size < 2) null else rtts.zipWithNext { a, b -> abs(b - a) }.average().toFloat()
}

object PingParser {
    private val timeRe = Regex("time[=<]([0-9.]+) ?ms")
    private val lossRe = Regex("([0-9.]+)% packet loss")
    private val txRe = Regex("([0-9]+) packets transmitted, ([0-9]+) (packets )?received")

    fun rtts(output: String): List<Float> = timeRe.findAll(output).mapNotNull { it.groupValues[1].toFloatOrNull() }.toList()
    fun loss(output: String): Float? = lossRe.find(output)?.groupValues?.get(1)?.toFloatOrNull()
    fun counts(output: String): Pair<Int, Int>? = txRe.find(output)?.let { it.groupValues[1].toInt() to it.groupValues[2].toInt() }
}

/**
 * Network probes. ICMP via the system ping binary where available; otherwise TCP connect
 * timing (a refused connection still proves reachability and gives an RTT).
 */
object NetProbe {
    private val tcpPorts = listOf(53, 80, 443)

    suspend fun ping(host: String, count: Int, intervalS: Float = 0.2f, timeoutS: Int = 2): LatencyStats = withContext(Dispatchers.IO) {
        val icmp = runCatching { systemPing(host, count, intervalS, timeoutS) }.getOrNull()
        icmp ?: tcpSeries(host, count, (intervalS * 1000).toLong())
    }

    private fun systemPing(host: String, count: Int, intervalS: Float, timeoutS: Int): LatencyStats? {
        val interval = if (intervalS < 0.2f) 0.2f else intervalS
        val cmd = listOf("/system/bin/ping", "-c", count.toString(), "-i", "%.1f".format(interval), "-W", timeoutS.toString(), host)
        val proc = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val finished = proc.waitFor((count * (interval + 0.1) + timeoutS + 5).toLong() * 1000, TimeUnit.MILLISECONDS)
        if (!finished) { proc.destroy(); return null }
        val out = proc.inputStream.bufferedReader().readText()
        val counts = PingParser.counts(out) ?: return null
        return LatencyStats(host, "ICMP ping", counts.first, counts.second, PingParser.rtts(out))
    }

    /** One TCP connect RTT in ms; refused = reachable. Null if unreachable/timeout. */
    fun tcpRtt(host: String, port: Int, timeoutMs: Int = 1500): Float? {
        val start = SystemClock.elapsedRealtimeNanos()
        val s = Socket()
        return try {
            s.connect(InetSocketAddress(host, port), timeoutMs)
            (SystemClock.elapsedRealtimeNanos() - start) / 1e6f
        } catch (e: ConnectException) {
            val t = (SystemClock.elapsedRealtimeNanos() - start) / 1e6f
            if (e.message?.contains("refused", ignoreCase = true) == true && t < timeoutMs) t else null
        } catch (_: SocketTimeoutException) {
            null
        } catch (_: Exception) {
            null
        } finally {
            runCatching { s.close() }
        }
    }

    private suspend fun tcpSeries(host: String, count: Int, intervalMs: Long): LatencyStats {
        val port = tcpPorts.firstOrNull { tcpRtt(host, it) != null } ?: 80
        val rtts = mutableListOf<Float>()
        var received = 0
        repeat(count) {
            val r = tcpRtt(host, port)
            if (r != null) { rtts += r; received++ }
            delay(intervalMs)
        }
        return LatencyStats(host, "TCP connect :$port", count, received, rtts)
    }

    /** Quick reachability RTT for roaming/monitoring (single probe). */
    suspend fun quickProbe(host: String): Float? = withContext(Dispatchers.IO) {
        runCatching { systemPing(host, 1, 0.2f, 1) }.getOrNull()?.rtts?.firstOrNull()
            ?: tcpPorts.firstNotNullOfOrNull { tcpRtt(host, it, 800) }
    }

    data class DnsResult(val hostname: String, val ms: Float, val resolved: Boolean, val addresses: List<String>)

    /** Times a DNS lookup. Uses a unique random label so the answer cannot come from the local cache. */
    suspend fun dns(domain: String = "example.com", uncached: Boolean = true): DnsResult = withContext(Dispatchers.IO) {
        val name = if (uncached) "jm${Random.nextInt(100000, 999999)}.$domain" else domain
        val t0 = SystemClock.elapsedRealtimeNanos()
        try {
            val addrs = InetAddress.getAllByName(name)
            DnsResult(name, (SystemClock.elapsedRealtimeNanos() - t0) / 1e6f, true, addrs.mapNotNull { it.hostAddress })
        } catch (_: UnknownHostException) {
            // NXDOMAIN still required a full round trip to the resolver
            DnsResult(name, (SystemClock.elapsedRealtimeNanos() - t0) / 1e6f, false, emptyList())
        }
    }

    data class HttpResult(val url: String, val ms: Float?, val code: Int?, val error: String?)

    suspend fun http(url: String = "http://connectivitycheck.gstatic.com/generate_204"): HttpResult = withContext(Dispatchers.IO) {
        val t0 = SystemClock.elapsedRealtimeNanos()
        try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 5000; c.readTimeout = 5000; c.useCaches = false
            c.instanceFollowRedirects = false
            val code = c.responseCode
            runCatching { c.inputStream.use(InputStream::readBytes) }
            c.disconnect()
            HttpResult(url, (SystemClock.elapsedRealtimeNanos() - t0) / 1e6f, code, null)
        } catch (e: Exception) {
            HttpResult(url, null, null, e.message ?: e.javaClass.simpleName)
        }
    }
}
