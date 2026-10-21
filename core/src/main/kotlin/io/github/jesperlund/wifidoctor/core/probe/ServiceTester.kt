package io.github.jesperlund.wifidoctor.core.probe

import io.github.jesperlund.wifidoctor.core.model.EndpointFailure
import io.github.jesperlund.wifidoctor.core.model.EndpointResult
import io.github.jesperlund.wifidoctor.core.model.ServiceCheck
import io.github.jesperlund.wifidoctor.core.model.ServiceEndpoint
import io.github.jesperlund.wifidoctor.core.model.ServiceResult
import io.github.jesperlund.wifidoctor.core.model.ServiceTarget
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** Checks that the servers specific apps depend on can be reached, and how quickly. */
fun interface ServiceTester {
    suspend fun check(targets: List<ServiceTarget>, baseline: ServiceEndpoint, timeoutMs: Int): ServiceCheck
}

/**
 * Times each phase of an HTTPS request on one connection: DNS lookup (system resolver, what the
 * apps use), TCP connect, TLS handshake (with certificate and host-name verification) and the
 * first response line of a HEAD request. Any HTTP status counts as reachable; the point is the
 * path, not the content.
 */
class HttpsServiceTester(
    private val binding: NetworkBinding = NetworkBinding.Default,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val nanoTime: () -> Long = System::nanoTime,
    /** Upgrades a connected socket to TLS; tests replace it to talk plain HTTP. */
    private val secure: (Socket, String, Int, Int) -> Socket = ::tls,
    /** DNS answers like 0.0.0.0 or 127.0.0.1 are how DNS filters block names; tests on loopback turn this off. */
    private val treatLocalAnswersAsBlocked: Boolean = true,
) : ServiceTester {

    override suspend fun check(targets: List<ServiceTarget>, baseline: ServiceEndpoint, timeoutMs: Int): ServiceCheck =
        coroutineScope {
            val ref = async { probe(baseline, timeoutMs) }
            val results = targets.map { target ->
                async { ServiceResult(target, target.endpoints.map { e -> async { probe(e, timeoutMs) } }.awaitAll()) }
            }.awaitAll()
            ServiceCheck(results, ref.await())
        }

    suspend fun probe(endpoint: ServiceEndpoint, timeoutMs: Int): EndpointResult = withContext(dispatcher) {
        fun ms(from: Long) = (nanoTime() - from) / 1_000_000.0

        // 1. DNS
        var t = nanoTime()
        val addresses = try {
            binding.lookup(endpoint.host).toList()
        } catch (e: Exception) {
            return@withContext EndpointResult(endpoint, failure = EndpointFailure.DNS_FAILED, error = e.message ?: "lookup failed")
        }
        val dnsMs = ms(t)
        val shown = addresses.mapNotNull { it.hostAddress }
        if (addresses.isEmpty() || (treatLocalAnswersAsBlocked && addresses.all { isFilterAnswer(it) })) {
            return@withContext EndpointResult(endpoint, shown, dnsMs, failure = EndpointFailure.DNS_BLOCKED, error = "Resolved to ${shown.joinToString()}")
        }
        val address = addresses.firstOrNull { it is Inet4Address } ?: addresses.first()

        // 2. TCP connect
        val raw = binding.socketFactory().createSocket()
        var socket: Socket = raw
        try {
            t = nanoTime()
            try {
                raw.connect(InetSocketAddress(address, endpoint.port), timeoutMs)
            } catch (e: Exception) {
                return@withContext EndpointResult(endpoint, shown, dnsMs, failure = EndpointFailure.CONNECT_FAILED, error = e.message ?: e.javaClass.simpleName)
            }
            val connectMs = ms(t)
            raw.soTimeout = timeoutMs

            // 3. TLS handshake
            t = nanoTime()
            try {
                socket = secure(raw, endpoint.host, endpoint.port, timeoutMs)
            } catch (e: Exception) {
                return@withContext EndpointResult(endpoint, shown, dnsMs, connectMs, failure = EndpointFailure.TLS_FAILED, error = e.message ?: e.javaClass.simpleName)
            }
            val tlsMs = ms(t)

            // 4. First response
            t = nanoTime()
            val status = try {
                val request = "HEAD ${endpoint.path} HTTP/1.1\r\nHost: ${endpoint.host}\r\nUser-Agent: WifiDoctor\r\nConnection: close\r\n\r\n"
                socket.getOutputStream().apply { write(request.toByteArray(Charsets.US_ASCII)); flush() }
                val line = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1).readLine()
                    ?: error("Connection closed without a response")
                line.split(' ').getOrNull(1)?.toIntOrNull() ?: error("Unexpected response: ${line.take(40)}")
            } catch (e: Exception) {
                return@withContext EndpointResult(
                    endpoint, shown, dnsMs, connectMs, tlsMs, failure = EndpointFailure.HTTP_FAILED, error = e.message ?: e.javaClass.simpleName,
                )
            }
            EndpointResult(endpoint, shown, dnsMs, connectMs, tlsMs, ms(t), status)
        } finally {
            runCatching { socket.close() }
            runCatching { raw.close() }
        }
    }

    companion object {
        /** Addresses DNS filters hand out instead of the real one. */
        fun isFilterAnswer(address: InetAddress): Boolean =
            address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress

        private fun tls(socket: Socket, host: String, port: Int, timeoutMs: Int): Socket {
            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            val ssl = factory.createSocket(socket, host, port, true) as SSLSocket
            ssl.sslParameters = ssl.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" } // verify the host name
            ssl.soTimeout = timeoutMs
            ssl.startHandshake()
            return ssl
        }
    }
}
