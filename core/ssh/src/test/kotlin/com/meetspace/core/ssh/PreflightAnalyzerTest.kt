package com.meetspace.core.ssh

import com.meetspace.core.model.*
import org.junit.Assert.*
import org.junit.Test

class PreflightAnalyzerTest {
    private val request = SshRequest(SshEndpoint("203.0.113.10", 22, "deploy"), "meet.example.org")
    private val key = SshHostKey("ssh-ed25519", "public-key-test", "SHA256:public-test")
    private fun report(changes: Map<String, String> = emptyMap(), network: NetworkReadiness = NetworkReadiness()) =
        PreflightAnalyzer.analyze(request, key, fixture(changes), network)
    private fun PreflightReport.check(id: String) = checks.single { it.id == id }

    @Test fun supportedLinuxAndResourcesAreReportedWithoutClaimingInstallation() {
        val result = report(network = NetworkReadiness(listOf("203.0.113.10"), TlsReadiness.VALID))
        for (id in listOf("os", "arch", "cpu", "ram", "disk", "dns", "tls", "port-tcp_443")) assertEquals(id, PreflightLevel.PASS, result.check(id).level)
        assertEquals(PreflightLevel.UNKNOWN, result.check("existing-install").level)
        assertEquals(PreflightLevel.UNKNOWN, result.check("firewall").level)
        assertFalse(result.hasBlockers)
    }
    @Test fun occupiedMediaHttpsAndRelayPortsBlockFuturePlanButPort80IsAdvisory() {
        val result = report(mapOf("tcp_443" to "busy", "tcp_80" to "busy", "udp_50010" to "busy", "udp_relay_busy" to "2"))
        assertTrue(result.hasBlockers)
        for (id in listOf("port-tcp_443", "port-udp_50010", "port-udp-relay")) assertEquals(PreflightLevel.BLOCKED, result.check(id).level)
        assertEquals(PreflightLevel.WARN, result.check("port-tcp_80").level)
    }
    @Test fun missingSsNeverClaimsFreePorts() {
        val result = report(mapOf("ports_known" to "no"))
        assertTrue(result.checks.filter { it.id.startsWith("port-") }.all { it.level == PreflightLevel.UNKNOWN })
    }
    @Test fun lowResourcesAreExplicitEstimatesAndNotUnsupportedPlatform() {
        val result = report(mapOf("cpu" to "2", "ram_kib" to "2097152", "disk_kib" to "12582912"))
        for (id in listOf("cpu", "ram", "disk")) assertEquals(PreflightLevel.WARN, result.check(id).level)
        assertEquals(PreflightLevel.PASS, result.check("os").level)
    }
    @Test fun unsupportedPlatformAndExistingInstallBlock() {
        val result = report(mapOf("distro" to "alpine", "distro_version" to "3.20", "arch" to "armv7l", "existing_paths" to "yes"))
        for (id in listOf("os", "arch", "existing-install")) assertEquals(PreflightLevel.BLOCKED, result.check(id).level)
    }
    @Test fun invalidTlsAndDnsMismatchRemainVisibleWarnings() {
        val result = report(mapOf("dns_domain" to "203.0.113.11"), NetworkReadiness(listOf("203.0.113.11"), TlsReadiness.INVALID_CERTIFICATE))
        assertEquals(PreflightLevel.WARN, result.check("dns").level)
        assertEquals(PreflightLevel.WARN, result.check("tls").level)
    }
    @Test fun malformedDuplicatePartialOrTerminalEscapeResponseFailsClosed() {
        val valid = fixture()
        for (bad in listOf(valid.replace("MSPF1\tcomplete\t1\n", ""), valid + "MSPF1\tcpu\t8\n", valid.replace("Linux", "Linux\u001b[31m"), "banner\n" + valid, valid.replace("tcp_443\tfree", "tcp_443\tfake"), "x".repeat(65_537))) {
            val failure = assertThrows(SshFailure::class.java) { PreflightAnalyzer.analyze(request, key, bad) }
            assertEquals(SshFailureKind.INVALID_RESPONSE, failure.kind)
        }
    }
    @Test fun missingOrUnparseableMeasurementsAreUnknown() {
        val result = report(mapOf("cpu" to "unknown", "ram_kib" to "", "disk_kib" to "unknown"))
        for (id in listOf("cpu", "ram", "disk")) assertEquals(PreflightLevel.UNKNOWN, result.check(id).level)
    }
    @Test fun nonNumericDnsOutputDoesNotTriggerNameLookupOrClaimMatch() {
        assertEquals(PreflightLevel.WARN, report(mapOf("dns_domain" to "deadbeef", "dns_ssh" to "deadbeef")).check("dns").level)
    }
    @Test fun compressedAndExpandedIpv6DnsAreComparedAsAddresses() {
        val result = report(mapOf("dns_domain" to "2001:db8::10", "dns_ssh" to "2001:db8:0:0:0:0:0:10"), NetworkReadiness(listOf("2001:db8::10"), TlsReadiness.VALID))
        assertEquals(PreflightLevel.PASS, result.check("dns").level)
    }

    companion object {
        fun fixture(changes: Map<String, String> = emptyMap()): String {
            val fields = linkedMapOf(
                "protocol" to "1", "kernel" to "Linux", "arch" to "aarch64", "distro" to "ubuntu", "distro_version" to "24.04",
                "cpu" to "4", "ram_kib" to "16777216", "disk_kib" to "67108864", "uid" to "1000", "sudo" to "noninteractive",
                "docker" to "27.5.0", "docker_access" to "yes", "compose" to "2.32.0", "existing_containers" to "unknown", "existing_paths" to "no", "ports_known" to "yes",
                "tcp_80" to "free", "tcp_443" to "free", "tcp_3478" to "free", "tcp_5349" to "free", "tcp_50010" to "free", "udp_3478" to "free", "udp_50010" to "free", "udp_relay_busy" to "0",
                "dns_domain" to "203.0.113.10", "dns_ssh" to "203.0.113.10", "firewall" to "unknown", "complete" to "1",
            )
            fields.putAll(changes)
            return fields.entries.joinToString("\n", postfix = "\n") { "MSPF1\t${it.key}\t${it.value}" }
        }
    }
}
