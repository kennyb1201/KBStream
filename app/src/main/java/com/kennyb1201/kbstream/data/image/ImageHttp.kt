package com.kennyb1201.kbstream.data.image

import java.net.Inet4Address
import java.net.InetAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory
import okhttp3.Dispatcher
import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response

/**
 * The art side's own HTTP client (F4, spec 2026-11-20).
 *
 * Coil shipped without one, which means every poster, backdrop and logo was
 * fetched through the shared default client: its dispatcher is sized for API
 * calls (a handful at a time), its timeouts are minutes, and a host that accepts
 * the connection and then stalls holds a request until it gives up long after
 * the rail the image belonged to has scrolled past. Pastes put 200-500 image
 * fetches in a session, so those defaults are the difference between a rail that
 * fills and a rail that waits.
 *
 * Four decisions, each chosen for what the pastes showed:
 *
 *  - **A wider dispatcher (32 / 16 per host).** A Home build asks for dozens of
 *    tiles at once - several per rail - and the default five-in-flight is what
 *    turned a warm cache into a queue.
 *  - **IPv4 first, IPv6 kept.** TV boxes sit on home networks whose IPv6 path
 *    is often slower or broken; this only reorders the answer, so a host with no
 *    A record (or a failed IPv4 connect) still reaches it over AAAA.
 *  - **TCP_NODELAY.** A 40-80 KB image response is small enough that Nagle's
 *    delay shows up as a whole extra round trip per tile.
 *  - **Short timeouts, one tighter retry.** Eight seconds for the whole call,
 *    then a single retry at 2.5s/3.5s - a second chance for a genuinely slow
 *    host without letting one host hold a queue slot for the rest of the walk.
 */
internal val imageHttpClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .dispatcher(
            Dispatcher().apply {
                maxRequests = IMAGE_MAX_REQUESTS
                maxRequestsPerHost = IMAGE_MAX_REQUESTS_PER_HOST
            }
        )
        .dns(Ipv4FirstDns)
        .socketFactory(NoDelaySocketFactory)
        .connectTimeout(IMAGE_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(IMAGE_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .callTimeout(IMAGE_CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .addInterceptor(ImageTimeoutRetry)
        .build()
}

private const val IMAGE_MAX_REQUESTS = 32
private const val IMAGE_MAX_REQUESTS_PER_HOST = 16
private const val IMAGE_CONNECT_TIMEOUT_MS = 4_000L
private const val IMAGE_READ_TIMEOUT_MS = 5_000L
private const val IMAGE_CALL_TIMEOUT_MS = 8_000L
private const val IMAGE_RETRY_CONNECT_TIMEOUT_MS = 2_500
private const val IMAGE_RETRY_READ_TIMEOUT_MS = 3_500

/**
 * Resolve, then prefer IPv4 without discarding IPv6.
 *
 * A stable sort, so the resolver's own preference order survives inside each
 * family and an IPv6-only host still answers.
 */
internal object Ipv4FirstDns : Dns {

    override fun lookup(hostname: String): List<InetAddress> {
        val addresses = Dns.SYSTEM.lookup(hostname)
        if (addresses.size < 2) return addresses
        return addresses.sortedBy { address -> if (address is Inet4Address) 0 else 1 }
    }
}

/**
 * The platform's own socket factory with TCP_NODELAY on every socket.
 *
 * OkHttp creates the raw socket through this for HTTPS too, then wraps it in
 * TLS, so the option applies to image hosts either way.
 */
internal object NoDelaySocketFactory : SocketFactory() {

    private val delegate: SocketFactory = SocketFactory.getDefault()

    override fun createSocket(): Socket = delegate.createSocket().noDelay()

    override fun createSocket(host: String, port: Int): Socket =
        delegate.createSocket(host, port).noDelay()

    override fun createSocket(
        host: String,
        port: Int,
        localHost: InetAddress,
        localPort: Int
    ): Socket = delegate.createSocket(host, port, localHost, localPort).noDelay()

    override fun createSocket(host: InetAddress, port: Int): Socket =
        delegate.createSocket(host, port).noDelay()

    override fun createSocket(
        address: InetAddress,
        port: Int,
        localAddress: InetAddress,
        localPort: Int
    ): Socket = delegate.createSocket(address, port, localAddress, localPort).noDelay()

    private fun Socket.noDelay(): Socket = apply {
        runCatching { tcpNoDelay = true }
    }
}

/**
 * One retry, at tighter timeouts, when a request times out.
 *
 * Only timeouts: a 404 or a refused connection is an answer, and retrying it
 * would cost the walk a slot for nothing.
 */
internal object ImageTimeoutRetry : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response =
        try {
            chain.proceed(chain.request())
        } catch (timeout: SocketTimeoutException) {
            chain
                .withConnectTimeout(IMAGE_RETRY_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .withReadTimeout(IMAGE_RETRY_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .proceed(chain.request())
        }
}
