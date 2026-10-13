package com.meetspace.core.ssh

import com.meetspace.core.model.*
import java.net.IDN
import java.net.Inet6Address
import java.net.InetAddress

object SshInputValidator {
    fun validate(host: String, portText: String, user: String, domain: String): SshRequest {
        val port = portText.trim().takeIf { it.matches(Regex("[0-9]{1,5}")) }?.toIntOrNull()
            ?.takeIf { it in 1..65535 } ?: fail("Укажите SSH-порт от 1 до 65535.")
        return validateRequest(SshRequest(SshEndpoint(host, port, user), domain))
    }

    fun validateEndpoint(endpoint: SshEndpoint): SshEndpoint {
        if (endpoint.port !in 1..65535) fail("Укажите SSH-порт от 1 до 65535.")
        val user = endpoint.user.trim()
        if (!user.matches(Regex("[a-zA-Z_][a-zA-Z0-9_.-]{0,63}"))) fail("Укажите имя SSH-пользователя без пробелов и специальных символов.")
        return endpoint.copy(host = normalizeHost(endpoint.host), user = user)
    }

    fun validateRequest(request: SshRequest): SshRequest = request.copy(
        endpoint = validateEndpoint(request.endpoint), domain = normalizeDomain(request.domain),
    )

    fun normalizeDomain(raw: String): String {
        val result = dnsName(raw.trim(), "Укажите домен без HTTPS, пути и порта.")
        if (!result.contains('.') || result.matches(Regex("[0-9.]+"))) fail("Укажите полное доменное имя для HTTPS.")
        return result
    }

    private fun normalizeHost(raw: String): String {
        val host = raw.trim()
        if (host.isEmpty() || host.length > 253 || host.any { it.isWhitespace() || it.isISOControl() } || host.contains('%')) fail("Укажите hostname или IP без пути, порта и пробелов.")
        if (host.contains(':')) {
            val ipv6 = if (host.startsWith('[') && host.endsWith(']')) host.substring(1, host.length - 1) else host
            if (!ipv6.matches(Regex("[0-9a-fA-F:]+"))) fail("Некорректный IPv6-адрес.")
            val address = runCatching { InetAddress.getByName(ipv6) }.getOrNull()
            if (address !is Inet6Address) fail("Некорректный IPv6-адрес.")
            return address.hostAddress!!.lowercase()
        }
        if (host.matches(Regex("[0-9.]+"))) {
            val parts = host.split('.')
            if (parts.size != 4 || parts.any { it.isEmpty() || it.length > 3 || it.toIntOrNull() !in 0..255 || (it.length > 1 && it.startsWith('0')) }) fail("Некорректный IPv4-адрес.")
            return parts.joinToString(".") { it.toInt().toString() }
        }
        return dnsName(host, "Укажите hostname или IP без пути и порта.")
    }

    private fun dnsName(raw: String, message: String): String {
        if (raw.isEmpty() || raw.length > 253 || raw.endsWith('.') || raw.any { it.isWhitespace() || it.isISOControl() } || raw.any { it in "/\\:@?#[]$'\"`()" }) fail(message)
        val ascii = runCatching { IDN.toASCII(raw, IDN.USE_STD3_ASCII_RULES).lowercase() }.getOrNull() ?: fail(message)
        if (ascii.length > 253 || ascii.split('.').any { !it.matches(Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")) }) fail(message)
        return ascii
    }

    private fun fail(message: String): Nothing = throw SshFailure(SshFailureKind.INPUT, message)
}
