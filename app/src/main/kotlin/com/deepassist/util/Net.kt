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
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import com.google.gson.JsonParser
import okhttp3.HttpUrl
import okhttp3.Request
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
 * cached answer is older than [REFRESH_AFTER_MS]). A host not in the cache is
 * asked from the system resolver and from Cloudflare's DNS over HTTPS (1.1.1.1,
 * reached by IP, already the phone's second DNS server) at the same time; the
 * first answer wins. Web pages the assistant reads are on hosts never seen
 * before, so this is what keeps them from waiting 30 s.
 *
 * Trace: one DiagLog line per call that opened a new connection or was slow —
 * host and phase durations only (DNS, TCP and family, TLS, response headers).
 */
object Net {

    private class Entry(val addresses: List<InetAddress>, val resolvedAt: Long)

    private val cache = ConcurrentHashMap<String, Entry>()
    private val refreshing = ConcurrentHashMap.newKeySet<String>()
    // A lookup on the broken resolver holds its thread for up to 30 s
    private val resolver = Executors.newFixedThreadPool(8) { r -> Thread(r, "dns").apply { isDaemon = true } }

    /** Talks to 1.1.1.1 by IP: no DNS needed to reach it. */
    private val dohClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .build()

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
        val (addrs, via) = race(host)
        val ms = SystemClock.elapsedRealtime() - start
        if (ms >= SLOW_DNS_MS || via != "system") DiagLog.event("Net", "dns $host $ms ms via $via")
        if (addrs.isNotEmpty()) {
            val now = System.currentTimeMillis()
            cache[host] = Entry(addrs, now)
            prefs?.edit()?.putString(host, addrs.joinToString(",") { it.hostAddress.orEmpty() } + "|" + now)?.apply()
        }
        return addrs
    }

    /** System resolver and DNS over HTTPS at once; the first non-empty answer, IPv4 first. */
    private fun race(host: String): Pair<List<InetAddress>, String> {
        val answers = LinkedBlockingQueue<Pair<Result<List<InetAddress>>, String>>()
        resolver.execute { answers.offer(runCatching { Dns.SYSTEM.lookup(host) } to "system") }
        resolver.execute { answers.offer(runCatching { dohLookup(host) } to "doh") }
        val deadline = SystemClock.elapsedRealtime() + RACE_TIMEOUT_MS
        var lastError: Throwable? = null
        repeat(2) {
            val left = deadline - SystemClock.elapsedRealtime()
            val (result, via) = answers.poll(left.coerceAtLeast(1L), TimeUnit.MILLISECONDS) ?: return@repeat
            val list = result.getOrNull()
            if (!list.isNullOrEmpty()) return list.sortedBy { if (it is Inet4Address) 0 else 1 } to via
            lastError = result.exceptionOrNull() ?: lastError
        }
        throw UnknownHostException("$host: no answer").apply { lastError?.let { initCause(it) } }
    }

    /** Cloudflare DNS over HTTPS, JSON form: A records, else AAAA. */
    private fun dohLookup(host: String): List<InetAddress> {
        for (type in listOf("A", "AAAA")) {
            val url = HttpUrl.Builder().scheme("https").host("1.1.1.1").addPathSegment("dns-query")
                .addQueryParameter("name", host).addQueryParameter("type", type).build()
            val request = Request.Builder().url(url).header("accept", "application/dns-json").build()
            val body = dohClient.newCall(request).execute().use { if (it.isSuccessful) it.body?.string() else null }
                ?: continue
            val wanted = if (type == "A") 1 else 28
            val addrs = JsonParser.parseString(body).asJsonObject.getAsJsonArray("Answer")
                ?.mapNotNull { a ->
                    val o = a.asJsonObject
                    if (o.get("type")?.asInt != wanted) return@mapNotNull null
                    // IP literal: no DNS query
                    runCatching { InetAddress.getByName(o.get("data").asString) }.getOrNull()
                }.orEmpty()
            if (addrs.isNotEmpty()) return addrs
        }
        throw UnknownHostException("$host: no DoH answer")
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
    private const val RACE_TIMEOUT_MS = 10_000L
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
