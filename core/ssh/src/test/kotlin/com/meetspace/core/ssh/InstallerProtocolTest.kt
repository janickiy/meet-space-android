package com.meetspace.core.ssh

import com.meetspace.core.model.*
import java.io.File
import java.util.Base64
import kotlinx.serialization.json.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

class InstallerProtocolTest {
    // External files are declared as signedInstallerBundle inputs in core/ssh/build.gradle.kts.
    private fun files() = InstallerBundle.names.associateWith { File("../../installer/v1/$it").readBytes() }
    private fun bundle() = InstallerBundle(files())
    private fun failed(code: String, action: () -> Unit) {
        try { action(); fail("Expected rejection") } catch (failure: InstallFailure) { assertEquals(code, failure.code) }
    }
    @Test fun signedBundleValidatesOnJvm() { assertEquals("source-v1", bundle().manifest.string("profile")) }
    @Test fun changedCodeFailsBeforeRemoteExecution() {
        val files = files().toMutableMap(); files["runner.py"] = files.getValue("runner.py") + 10.toByte()
        failed("RELEASE_INVALID") { InstallerBundle(files) }
    }
    @Test fun changedManifestSignatureRejected() {
        val files = files().toMutableMap(); files["release.json"] = files.getValue("release.json") + 32.toByte()
        failed("RELEASE_INVALID") { InstallerBundle(files) }
    }
    @Test fun userInputIsOnlyBase64DataInFixedBootstrap() {
        val attack = "'; __import__('os').system('touch /tmp/evil') #"
        val payload = bundle().payload("plan", buildJsonObject { put("domain", attack) }).toString(Charsets.UTF_8)
        assertFalse(payload.contains(attack)); assertTrue(payload.contains("compile(_b['runner.py']"))
        val encoded = payload.substringAfter("b64decode('").substringBefore("')")
        assertTrue(Base64.getDecoder().decode(encoded).toString(Charsets.UTF_8).contains(attack))
    }
    @Test fun arbitraryActionCannotReachShell() {
        assertThrows(IllegalArgumentException::class.java) { bundle().payload("delete", buildJsonObject { }) }
    }
    @Test fun errorsCannotRenderArbitraryRemoteOutput() {
        failed("INVALID_RESPONSE") { parseInstallerReply("""{"protocol":"MSI1","ok":false,"errorCode":"password=secret"}""") }
        failed("DISK_SPACE") { parseInstallerReply("""{"protocol":"MSI1","ok":false,"errorCode":"DISK_SPACE"}""") }
    }
    @Test fun wrongProtocolAndOversizedResponsesRejected() {
        failed("INVALID_RESPONSE") { parseInstallerReply("""{"protocol":"other","ok":true,"result":{}}""") }
        failed("RESPONSE_LIMIT") { parseInstallerReply("x".repeat(262145)) }
    }
    @Test fun planHashAndReleaseBindingChecked() {
        val bundle = bundle(); val plan = validPlan(bundle)
        assertEquals("example.com", bundle.parsePlan(plan).options.domain)
        failed("PLAN_INVALID") { bundle.parsePlan(JsonObject(plan + ("commit" to JsonPrimitive("0".repeat(40))))) }
        failed("PLAN_INVALID") { bundle.parsePlan(JsonObject(plan + ("hash" to JsonPrimitive("0".repeat(64))))) }
    }
    @Test fun completedRequiresAllCheckpointsAndCorrectJobIdentity() {
        val plan = bundle().parsePlan(validPlan(bundle())); val ticket = InstallTicket(SshEndpoint("example.com",22,"root"), plan, "6f0f7f66-ea40-406e-a582-98bb9df557ca")
        val status = buildJsonObject {
            put("jobId",ticket.jobId); put("planHash",ticket.planHash); put("commit",plan.commit); put("domain",plan.options.domain)
            put("state","completed"); put("phase","readiness"); put("errorCode",JsonNull); put("completedPhases",JsonArray(installPhases.map(::JsonPrimitive))); put("events",JsonArray(emptyList()))
        }
        assertEquals("completed",parseInstallStatus(status,ticket).state)
        failed("INVALID_RESPONSE") { parseInstallStatus(JsonObject(status + ("completedPhases" to JsonArray(emptyList()))),ticket) }
        failed("INVALID_RESPONSE") { parseInstallStatus(JsonObject(status + ("jobId" to JsonPrimitive("other"))),ticket) }
    }
    @Test fun ownTimeoutBecomesRecoveryFailure() = runTest {
        try { boundedInstallerRequest { delay(60_001) }; fail("Expected timeout") }
        catch (failure: SshFailure) { assertEquals(SshFailureKind.TIMEOUT,failure.kind) }
    }
    @Test fun callerCancellationRemainsCancellation() = runTest {
        var converted=false;var entered=false
        val operation=launch(start=CoroutineStart.UNDISPATCHED) { try { boundedInstallerRequest { entered=true;delay(60_001) } } catch (_: SshFailure) { converted=true } }
        assertTrue(entered);operation.cancelAndJoin();assertFalse(converted)
    }
    @Test fun parentDeadlineIsNotConvertedIntoASshError() = runTest {
        var converted=false
        try { withTimeout(10) { try { boundedInstallerRequest { delay(1000) } } catch (_: SshFailure) { converted=true } } }
        catch (_: TimeoutCancellationException) { }
        assertFalse(converted)
    }
    private fun validPlan(bundle: InstallerBundle): JsonObject {
        val data = buildJsonObject {
            put("releaseSha256",sha256(canonicalJson(bundle.manifest).toByteArray())); put("commit",bundle.manifest.string("commit")); put("releaseVersion",bundle.manifest.string("version"))
            put("options",buildJsonObject { put("domain","example.com"); put("publicIpv4","8.8.8.8"); put("acmeEmail","owner@example.com") })
            put("platform",buildJsonObject { put("arch","arm64") });put("docker",buildJsonObject { put("installed",false) });put("expiresAt",2000000000)
        }
        return JsonObject(data + ("hash" to JsonPrimitive(sha256(canonicalJson(data).toByteArray()))))
    }
}
