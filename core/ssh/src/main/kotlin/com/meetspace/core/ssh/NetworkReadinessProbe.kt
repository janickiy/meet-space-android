package com.meetspace.core.ssh

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import java.io.IOException
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLException
import kotlin.coroutines.resume

enum class TlsReadiness { VALID, INVALID_CERTIFICATE, DNS_FAILURE, TIMEOUT, UNREACHABLE, UNKNOWN }
data class NetworkReadiness(val addresses: List<String> = emptyList(), val tls: TlsReadiness = TlsReadiness.UNKNOWN)

/** Checks the future HTTPS domain from Android with system CA + hostname validation. No redirects. */
object NetworkReadinessProbe {
    suspend fun inspect(domain: String): NetworkReadiness {
        val canonical = SshInputValidator.normalizeDomain(domain)
        val addresses = AtomicReference<List<String>>(emptyList())
        val client = OkHttpClient.Builder()
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .callTimeout(8, TimeUnit.SECONDS).connectTimeout(4, TimeUnit.SECONDS).readTimeout(4, TimeUnit.SECONDS)
            .eventListener(object : EventListener() {
                override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) {
                    addresses.set(inetAddressList.mapNotNull { it.hostAddress }.distinct().take(8))
                }
            }).build()
        val call = client.newCall(Request.Builder().url("https://$canonical/").head().build())
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    val status = when (e) {
                        is UnknownHostException -> TlsReadiness.DNS_FAILURE
                        is SSLException -> TlsReadiness.INVALID_CERTIFICATE
                        is SocketTimeoutException -> TlsReadiness.TIMEOUT
                        is java.io.InterruptedIOException -> TlsReadiness.TIMEOUT
                        else -> TlsReadiness.UNREACHABLE
                    }
                    if (continuation.isActive) continuation.resume(NetworkReadiness(addresses.get(), status))
                    client.connectionPool.evictAll()
                    client.dispatcher.executorService.shutdown()
                }
                override fun onResponse(call: Call, response: Response) {
                    val status = response.use { if (it.handshake != null) TlsReadiness.VALID else TlsReadiness.UNKNOWN }
                    if (continuation.isActive) continuation.resume(NetworkReadiness(addresses.get(), status))
                    client.connectionPool.evictAll()
                    client.dispatcher.executorService.shutdown()
                }
            })
        }
    }
}
