package com.meetspace.core.ssh

import com.meetspace.core.model.*
import org.junit.Assert.*
import org.junit.Test

class SshInputValidatorTest {
    @Test fun canonicalHostDomainAndIpv6ProduceStableEndpoint() {
        val first = SshInputValidator.validate(" EXAMPLE.ORG ", "2222", " deploy ", "ПРИМЕР.РФ")
        assertEquals("example.org", first.endpoint.host)
        assertEquals("xn--e1afmkfd.xn--p1ai", first.domain)
        assertEquals("[example.org]:2222", first.endpoint.pinId)
        assertEquals(SshInputValidator.validate("[2001:db8::1]", "22", "root", "meet.example.org"), SshInputValidator.validate("2001:db8:0:0:0:0:0:1", "22", "root", "meet.example.org"))
    }
    @Test fun commandInjectionAndUrlInputsRejectedBeforeNetwork() {
        for (bad in listOf("https://example.org", "example.org:22", "example.org/path", "example.org;id", "x\nwhoami", "$(id)", "a'", "a\\b", "host.", "127.1", "999.0.0.1", "01.0.0.1", "fe80::1%eth0")) {
            assertEquals(bad, SshFailureKind.INPUT, assertThrows(SshFailure::class.java) { SshInputValidator.validate(bad, "22", "root", "meet.example.org") }.kind)
        }
    }
    @Test fun invalidPortUserAndDomainRejected() {
        for (port in listOf("0", "65536", "22;id", "-1", "abc")) assertThrows(SshFailure::class.java) { SshInputValidator.validate("example.org", port, "root", "meet.example.org") }
        for (user in listOf("-root", "a b", "a;id", "a\nroot", "root@x")) assertThrows(SshFailure::class.java) { SshInputValidator.validate("example.org", "22", user, "meet.example.org") }
        for (domain in listOf("localhost", "203.0.113.1", "example.org:443", "example.org?x", "a'$(id).org")) assertThrows(SshFailure::class.java) { SshInputValidator.validate("example.org", "22", "root", domain) }
    }
    @Test fun pastedHttpsDomainMatchesBareDomainIncludingCaseSlashAndIdn() {
        for ((input, expected) in listOf(
            "https://chat.stage.org.ru" to "chat.stage.org.ru",
            " HTTPS://CHAT.STAGE.ORG.RU/ " to "chat.stage.org.ru",
            "https://ПРИМЕР.РФ/" to "xn--e1afmkfd.xn--p1ai",
        )) {
            val request = SshInputValidator.validate("78.17.198.175", "22", "root", input)
            assertEquals(SshInputValidator.validate("78.17.198.175", "22", "root", expected), request)
            assertEquals(expected, SshInputValidator.normalizeDomain(request.domain))
        }
    }
    @Test fun pastedHttpsAddressCannotHideUnsupportedUrlParts() {
        for (domain in listOf("http://meet.example.org", "https://", "https:///meet.example.org", "//meet.example.org",
            "https://meet.example.org:443/", "https://user:password@meet.example.org/", "https://meet.example.org/path",
            "https://meet.example.org//", "https://meet.example.org/?q=x", "https://meet.example.org/#fragment",
            "https://meet.example.org%2fpath", "https://meet.example.org\\path", "https://meet.example.org\nother.org",
            "https://meet.example.org;id", "https://203.0.113.1/", "https://localhost/")) {
            assertEquals(domain, SshFailureKind.INPUT, assertThrows(SshFailure::class.java) {
                SshInputValidator.normalizeDomain(domain)
            }.kind)
        }
    }
    @Test fun probeCommandOnlyHasValidatedArguments() {
        assertEquals("LC_ALL=C sh -s -- 'meet.example.org' '203.0.113.10'", PreflightProbe.command("meet.example.org", "203.0.113.10"))
        assertThrows(SshFailure::class.java) { PreflightProbe.command("meet.example.org;reboot", "203.0.113.10") }
    }
    @Test fun credentialDescriptionIsRedactedAndDestructionZeroesBuffers() {
        val password = "unit-secret".toByteArray()
        val credential = SshCredentials.Password(password)
        assertFalse(credential.toString().contains("unit-secret"))
        credential.destroy()
        assertTrue(password.all { it == 0.toByte() })
        val key = ByteArray(50) { 42 }; val phrase = ByteArray(10) { 42 }
        SshCredentials.PrivateKey(key, phrase).destroy()
        assertTrue((key + phrase).all { it == 0.toByte() })
    }
}
