package com.deepassist.util

import android.os.SystemClock
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
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
 * Trace: one DiagLog line per call that opened a new connection or was slow —
 * host and phase durations only (DNS, TCP and family, TLS, response headers).
 */
object Net {

    private val ipv4First = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> =
            Dns.SYSTEM.lookup(hostname).sortedBy { if (it is Inet4Address) 0 else 1 }
    }

    /** Applies IPv4-first DNS and the timing trace, labelled [name] in the log. */
    fun OkHttpClient.Builder.forApi(name: String): OkHttpClient.Builder =
        dns(ipv4First).eventListenerFactory { Trace(name) }

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
}
