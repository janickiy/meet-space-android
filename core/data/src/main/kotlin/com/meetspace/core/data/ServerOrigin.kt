package com.meetspace.core.data

import com.meetspace.core.model.AppFailure
import com.meetspace.core.model.FailureKind
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** One canonical origin is the boundary for profiles, cookies and encrypted credentials. */
object ServerOrigin {
    fun normalize(raw: String): String {
        val value = raw.trim()
        if (value.isEmpty() || value.any { it.isWhitespace() || it.isISOControl() || it == '\\' }) invalid()
        if (!value.startsWith("https://", ignoreCase = true)) invalid()
        val authority = value.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')
        if ('@' in authority) invalid()
        val suffix = value.substring(value.indexOf("://") + 3 + authority.length)
        if (suffix.isNotEmpty() && suffix != "/") invalid()
        val url = value.toHttpUrlOrNull() ?: invalid()
        if (url.scheme != "https" || url.username.isNotEmpty() || url.password.isNotEmpty() ||
            url.encodedPath != "/" || url.query != null || url.fragment != null) invalid()
        val host = url.host
        if (host.endsWith('.') || host.isEmpty() || host.length > 253) invalid()
        // HttpUrl performs IDNA canonicalization and IPv6 validation. Validate DNS labels too.
        if (':' !in host && host.split('.').any {
                it.isEmpty() || it.length > 63 || !it.matches(Regex("[a-z0-9](?:[a-z0-9-]*[a-z0-9])?"))
            }) invalid()
        return url.newBuilder().encodedPath("/").build().toString().removeSuffix("/")
    }

    internal fun url(origin: String, path: String): HttpUrl =
        (origin + path).toHttpUrlOrNull() ?: invalid()

    internal fun matches(origin: String, url: HttpUrl): Boolean =
        normalizeUrlOrigin(url) == origin

    private fun normalizeUrlOrigin(url: HttpUrl): String =
        url.newBuilder().encodedPath("/").query(null).fragment(null).build().toString().removeSuffix("/")

    private fun invalid(): Nothing = throw AppFailure(
        FailureKind.INVALID_URL,
        "Введите HTTPS-адрес сервера без пути, параметров и учётных данных.",
    )
}
