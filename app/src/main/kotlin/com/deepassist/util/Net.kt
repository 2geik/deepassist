package com.deepassist.util

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import okhttp3.Call
import okhttp3.Dns
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.OkHttpClient
import okhttp3.Protocol

/**
 * Network setup shared by the API clients.
 *
 * IPv4 first: OkHttp 4 tries a host's addresses one after another and waits the
 * full connect timeout on each. On 21 November 2026 the first request of every
 * session took 20-30 s on the phone's network while later ones took 1 s — the
 * pattern of an IPv6 route that stalls. Trying IPv4 addresses first avoids that;
 * IPv6 stays as the fallback.
 *
 * DNS cache: the trace showed the real cause — on the phone's Wi-Fi a DNS lookup
 * took 30 s (the router's resolver, fe80::1, doesn't answer), TCP 19 ms, TLS
 * 44 ms. Answers are kept (also across restarts) and used at once; lookups run
 * in the background ([prefetch] at service and session start, and whenever a
 * cached answer is older than [REFRESH_AFTER_MS]). Only a host never resolved
 * before waits for the system resolver.
 *
 * Trace: one DiagLog line per call that opened a new connection or was slow —
 * host and phase durations only (DNS, TCP and family, TLS, response headers).
 */
object Net {

    private class Entry(val addresses: List<InetAddress>, val resolvedAt: Long)

    private val cache = ConcurrentHashMap<String, Entry>()
    private val refreshing = ConcurrentHashMap.newKeySet<String>()
    private val resolver = Executors.newFixedThreadPool(4) { r -> Thread(r, "dns-refresh").apply { isDaemon = true } }

    @Volatile
    private var prefs: SharedPreferences? = null

    /** Loads the persisted answers; call once from Application.onCreate. */
    fun init(context: Context) {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        for ((host, value) in p.all) {
            val parts = (value as? String)?.split('|') ?: continue
            val at = parts.getOrNull(1)?.toLongOrNull() ?: continue
            // IP literals only: InetAddress.getByName doesn't query DNS for them
            val addrs = parts[0].split(',').filter { it.isNotBlank() }
                .mapNotNull { runCatching { InetAddress.getByName(it) }.getOrNull() }
            if (addrs.isNotEmpty()) cache[host] = Entry(addrs, at)
        }
    }

    /** Resolves [hosts] in the background so the next request doesn't wait for DNS. */
    fun prefetch(vararg hosts: String) {
        hosts.forEach { host ->
            val e = cache[host]
            if (e == null || age(e) > REFRESH_AFTER_MS) refreshAsync(host)
        }
    }

    private fun age(e: Entry) = System.currentTimeMillis() - e.resolvedAt

    private fun refreshAsync(host: String) {
        if (!refreshing.add(host)) return
        runCatching {
            resolver.execute {
                try {
                    resolveNow(host)
                } catch (e: Exception) {
                    DiagLog.event("Net", "dns refresh $host failed ${e.javaClass.simpleName}")
                } finally {
                    refreshing.remove(host)
                }
            }
        }.onFailure { refreshing.remove(host) }
    }

    private fun resolveNow(host: String): List<InetAddress> {
        val start = SystemClock.elapsedRealtime()
        val addrs = Dns.SYSTEM.lookup(host).sortedBy { if (it is Inet4Address) 0 else 1 }
        val ms = SystemClock.elapsedRealtime() - start
        if (ms >= SLOW_DNS_MS) DiagLog.event("Net", "dns $host $ms ms")
        if (addrs.isNotEmpty()) {
            val now = System.currentTimeMillis()
            cache[host] = Entry(addrs, now)
            prefs?.edit()?.putString(host, addrs.joinToString(",") { it.hostAddress.orEmpty() } + "|" + now)?.apply()
        }
        return addrs
    }

    /** Cached answers at once (refreshed in the background), IPv4 first. */
    private val cachedDns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val e = cache[hostname]
            if (e != null && age(e) < MAX_AGE_MS) {
                if (age(e) > REFRESH_AFTER_MS) refreshAsync(hostname)
                return e.addresses
            }
            return try {
                resolveNow(hostname)
            } catch (ex: UnknownHostException) {
                // The resolver failed: an old answer is better than none
                e?.addresses ?: throw ex
            }
        }
    }

    /** Applies IPv4-first DNS and the timing trace, labelled [name] in the log. */
    fun OkHttpClient.Builder.forApi(name: String): OkHttpClient.Builder =
        dns(cachedDns).eventListenerFactory { Trace(name) }

    private class Trace(private val name: String) : EventListener() {
        private var start = 0L
        private var dnsStart = 0L
        private var dnsMs = -1L
        private var connectStart = 0L
        private var connectMs = -1L
        private var family = ""
        private var tlsStart = 0L
        private var tlsMs = -1L
        private var failures = 0
        private var logged = false

        private fun now() = SystemClock.elapsedRealtime()

        override fun callStart(call: Call) {
            start = now()
        }

        override fun dnsStart(call: Call, domainName: String) {
            dnsStart = now()
        }

        override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) {
            dnsMs = now() - dnsStart
        }

        override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
            connectStart = now()
            family = if (inetSocketAddress.address is Inet4Address) "v4" else "v6"
        }

        override fun secureConnectStart(call: Call) {
            tlsStart = now()
        }

        override fun secureConnectEnd(call: Call, handshake: Handshake?) {
            tlsMs = now() - tlsStart
        }

        override fun connectEnd(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?) {
            connectMs = now() - connectStart - maxOf(tlsMs, 0L)
        }

        override fun connectFailed(
            call: Call,
            inetSocketAddress: InetSocketAddress,
            proxy: Proxy,
            protocol: Protocol?,
            ioe: IOException
        ) {
            failures++
            DiagLog.event(
                "Net",
                "$name connect $family failed after ${now() - connectStart} ms ${ioe.javaClass.simpleName}"
            )
        }

        override fun responseHeadersStart(call: Call) {
            report("headers ${now() - start} ms")
        }

        override fun callFailed(call: Call, ioe: IOException) {
            report("failed after ${now() - start} ms ${ioe.javaClass.simpleName}")
        }

        private fun report(outcome: String) {
            if (logged) return
            logged = true
            val newConnection = dnsMs >= 0 || connectMs >= 0
            if (!newConnection && now() - start < SLOW_MS && failures == 0 && !outcome.startsWith("failed")) return
            val parts = buildList {
                if (dnsMs >= 0) add("dns $dnsMs")
                if (connectMs >= 0) add("tcp $family $connectMs")
                if (tlsMs >= 0) add("tls $tlsMs")
                add(outcome)
            }
            DiagLog.event("Net", "$name ${parts.joinToString(", ")}")
        }
    }

    private const val SLOW_MS = 2_000L
    private const val SLOW_DNS_MS = 1_000L
    private const val PREFS = "dns_cache"

    /** A cached answer older than this is refreshed in the background (still used meanwhile). */
    private const val REFRESH_AFTER_MS = 5 * 60 * 1000L

    /** Answers older than this are not used without a fresh lookup. */
    private const val MAX_AGE_MS = 24 * 60 * 60 * 1000L

    /** Hosts resolved ahead of time: every request, then the lookup tools. */
    val API_HOSTS = arrayOf(
        "api.deepseek.com", "api.meta.ai", "api.openai.com",
        "mcp.exa.ai", "html.duckduckgo.com", "api.duckduckgo.com", "api.tavily.com",
        "api.open-meteo.com", "geocoding-api.open-meteo.com", "open.er-api.com",
        "www.youtube.com", "music.youtube.com"
    )
}
