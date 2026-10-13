package com.meetspace.app

import java.net.URI
import java.util.UUID

/** A push is only a resource hint, never a permission or navigation command. */
internal data class PushHint(val kind: String, val id: String) {
    companion object {
        fun parse(origin: String, raw: String): PushHint? = runCatching {
            if (raw.length > 1024 || raw.any(Char::isISOControl)) return null
            val expected = URI(origin); val url = URI(raw)
            if (url.scheme != "https" || url.rawUserInfo != null || url.rawFragment != null ||
                url.host != expected.host || effectivePort(url) != effectivePort(expected)) return null
            val parts = url.rawPath.split('/')
            if (parts.size != 3 || parts[0] != "" || parts[1] !in setOf("conferences", "conversations") || !uuid(parts[2])) return null
            val query = url.rawQuery
            if (query != null) {
                val values = query.split('&').map { it.split('=', limit = 2) }
                if (values.any { it.size != 2 } || values.map { it[0] }.distinct().size != values.size ||
                    values.any { (key, value) -> when (key) { "recording" -> !uuid(value); "tab" -> value !in setOf("transcript", "summary"); else -> true } }) return null
            }
            PushHint(parts[1], parts[2])
        }.getOrNull()
        private fun effectivePort(uri: URI) = if (uri.port == -1) 443 else uri.port
        private fun uuid(value: String) = value != "00000000-0000-0000-0000-000000000000" &&
            runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)
    }
}
