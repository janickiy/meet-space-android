package com.meetspace.core.data

import kotlinx.serialization.Serializable
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

internal const val SESSION_COOKIE = "meetrix_session"
internal const val AUTH_PATH = "/api/v1/auth"

internal data class SessionScope(val origin: String, val accountId: String)

@Serializable
internal data class StoredCredential(val value: String, val expiresAt: Long)

/** A jar belongs to exactly one origin; login always starts with an empty jar. */
internal class SessionCookieJar(
    private val origin: String,
    stored: StoredCredential? = null,
) : CookieJar {
    private var session: Cookie? = stored?.takeIf { it.expiresAt > System.currentTimeMillis() }?.let {
        Cookie.Builder().name(SESSION_COOKIE).value(it.value)
            .hostOnlyDomain(ServerOrigin.url(origin, "/").host)
            .path(AUTH_PATH).secure().httpOnly().expiresAt(it.expiresAt).build()
    }

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (!ServerOrigin.matches(origin, url) || !isAuthPath(url.encodedPath)) return
        cookies.filter {
            it.name == SESSION_COOKIE && it.hostOnly && it.domain == url.host &&
                it.path == AUTH_PATH && it.secure && it.httpOnly && it.persistent
        }.forEach {
            session = it.takeIf { cookie -> cookie.value.isNotBlank() && cookie.value.length <= 8192 &&
                cookie.expiresAt > System.currentTimeMillis() }
        }
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        if (!ServerOrigin.matches(origin, url) || !isAuthPath(url.encodedPath)) return emptyList()
        return session?.takeIf { it.expiresAt > System.currentTimeMillis() && it.matches(url) }
            ?.let(::listOf) ?: emptyList()
    }

    @Synchronized
    fun stored(): StoredCredential? = session?.takeIf { it.expiresAt > System.currentTimeMillis() }
        ?.let { StoredCredential(it.value, it.expiresAt) }

    @Synchronized
    fun clear() { session = null }

    private fun isAuthPath(path: String) = path == AUTH_PATH || path.startsWith("$AUTH_PATH/")
}
