package com.meetspace.core.data

import okhttp3.Cookie
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionCookieJarTest {
    private val origin = "https://example.com:8443"
    private val login = ServerOrigin.url(origin, "$AUTH_PATH/login")
    private val valid = "$SESSION_COOKIE=synthetic-cookie; Path=$AUTH_PATH; Secure; HttpOnly; Max-Age=3600"

    @Test fun `session cookie is limited to exact origin and auth path`() {
        val jar = SessionCookieJar(origin)
        jar.saveFromResponse(login, listOf(Cookie.parse(login, valid)!!))
        assertEquals("synthetic-cookie", jar.loadForRequest(ServerOrigin.url(origin, "$AUTH_PATH/refresh")).single().value)
        assertTrue(jar.loadForRequest(ServerOrigin.url("https://example.com:9443", "$AUTH_PATH/refresh")).isEmpty())
        assertTrue(jar.loadForRequest(ServerOrigin.url("https://sub.example.com:8443", "$AUTH_PATH/me")).isEmpty())
        assertTrue(jar.loadForRequest(ServerOrigin.url(origin, "/api/v1/capabilities")).isEmpty())
        assertTrue(jar.loadForRequest(ServerOrigin.url(origin, "/api/v1/author")).isEmpty())
    }

    @Test fun `insecure broader and unrelated cookies are never accepted`() {
        listOf(
            valid.replace("; Secure", ""), valid.replace("; HttpOnly", ""),
            "$valid; Domain=example.com", valid.replace("Path=$AUTH_PATH", "Path=/"),
            valid.replace(SESSION_COOKIE, "other_cookie"), valid.replace("; Max-Age=3600", ""),
        ).forEach { cookie ->
            val jar = SessionCookieJar(origin)
            jar.saveFromResponse(login, listOf(Cookie.parse(login, cookie)!!))
            assertNull(jar.stored())
        }
        val jar = SessionCookieJar(origin)
        val wrongOrigin = ServerOrigin.url("https://example.com:9443", "$AUTH_PATH/login")
        jar.saveFromResponse(wrongOrigin, listOf(Cookie.parse(wrongOrigin, valid)!!))
        assertNull(jar.stored())
    }

    @Test fun `cookie deletion and expiry remove the persistent credential`() {
        val jar = SessionCookieJar(origin)
        jar.saveFromResponse(login, listOf(Cookie.parse(login, valid)!!))
        jar.saveFromResponse(login, listOf(Cookie.parse(login, valid.replace("Max-Age=3600", "Max-Age=0"))!!))
        assertNull(jar.stored())
        assertNull(SessionCookieJar(origin, StoredCredential("expired", 1)).stored())
    }
}
