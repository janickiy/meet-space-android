package com.meetspace.core.data

import com.meetspace.core.model.AppFailure
import com.meetspace.core.model.FailureKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ServerOriginTest {
    @Test fun `canonical origins include scheme host and effective port`() {
        assertEquals("https://example.com", ServerOrigin.normalize(" HTTPS://EXAMPLE.COM:443/ "))
        assertEquals("https://example.com:8443", ServerOrigin.normalize("https://example.com:8443"))
        assertEquals("https://xn--e1afmkfd.xn--p1ai", ServerOrigin.normalize("https://пример.рф/"))
        assertEquals("https://[::1]:8443", ServerOrigin.normalize("https://[::1]:8443"))
    }

    @Test fun `unsafe and ambiguous URL inputs fail locally`() {
        listOf(
            "", "example.com", "http://example.com", "https://user:password@example.com",
            "https://@example.com", "https://example.com/app", "https://example.com/..",
            "https://example.com/%2e", "https://example.com//", "https://example.com?",
            "https://example.com?token=secret", "https://example.com#", "https://example.com./",
            "https://example.com\\@evil.test", "https://exa mple.com", "https://example.com\n.evil.test",
            "https://-invalid.test", "https://invalid_.test", "https://example.com:99999",
        ).forEach { input ->
            val error = assertThrows("Expected rejection for $input", AppFailure::class.java) { ServerOrigin.normalize(input) }
            assertEquals(FailureKind.INVALID_URL, error.kind)
        }
    }
}
