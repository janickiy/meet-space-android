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
    @Test fun emailFreeAndLegacyPlansRetainTheirOriginalHashWhenReopened() {
        val bundle = bundle()
        for (email in listOf("", "owner@example.com")) {
            val original = validPlan(bundle, email)
            val parsed = bundle.parsePlan(original)
            assertEquals(email, parsed.options.acmeEmail)
            assertEquals(original.string("hash"), parsed.hash)
            assertEquals(parsed, bundle.parsePlan(jsonObject(parsed.json)))
        }
        assertEquals("", InstallOptions("example.com", "8.8.8.8").acmeEmail)
    }
    @Test fun resourceProfileIsValidatedAndPreservedInSavedPlan() {
        val bundle = bundle()
        fun profile(value: String): JsonObject {
            val data = JsonObject(validPlan(bundle).filterKeys { it != "hash" } + ("resourceProfile" to JsonPrimitive(value)))
            return JsonObject(data + ("hash" to JsonPrimitive(sha256(canonicalJson(data).toByteArray()))))
        }
        assertNull(bundle.parsePlan(validPlan(bundle)).resourceProfile)
        for (mode in listOf("standard", "compact-test")) {
            val parsed = bundle.parsePlan(profile(mode))
            assertEquals(mode, parsed.resourceProfile)
            assertEquals(parsed, bundle.parsePlan(jsonObject(parsed.json)))
        }
        failed("PLAN_INVALID") { bundle.parsePlan(profile("unbounded")) }
    }
    @Test fun missingDockerPluginsRequirePackageConsentWithoutClaimingDockerReplacement() {
        val bundle = bundle()
        for ((missingCompose, missingBuildx) in listOf(false to false, true to false, false to true, true to true)) {
            val docker = buildJsonObject {
                put("installed", true); put("version", "29.1.3")
                put("composeVersion", if (missingCompose) JsonNull else JsonPrimitive("2.40.3"))
                put("buildxVersion", if (missingBuildx) JsonNull else JsonPrimitive("0.30.1"))
                if (missingCompose) put("installCompose", true)
                if (missingBuildx) put("installBuildx", true)
            }
            val data = JsonObject(validPlan(bundle).filterKeys { it != "hash" } + ("docker" to docker))
            val signed = JsonObject(data + ("hash" to JsonPrimitive(sha256(canonicalJson(data).toByteArray()))))
            val parsed = bundle.parsePlan(signed)
            assertEquals(missingCompose || missingBuildx, parsed.installDocker)
            assertEquals(missingCompose || missingBuildx, parsed.installDockerPluginsOnly)
            assertEquals(listOfNotNull(if (missingCompose) "Compose" else null, if (missingBuildx) "Buildx" else null), parsed.missingDockerPlugins)
            assertEquals(parsed, bundle.parsePlan(jsonObject(parsed.json)))
        }
        val fresh = bundle.parsePlan(validPlan(bundle))
        assertTrue(fresh.installDocker)
        assertFalse(fresh.installDockerPluginsOnly)
        assertTrue(fresh.missingDockerPlugins.isEmpty())
    }
    @Test fun latestSourcePlanBindsRecipeArchiveVersionAndCommit() {
        val bundle = bundle()
        val commit = "a".repeat(40)
        val lock = buildJsonObject {
            put("repository", "janickiy/meet-space"); put("channel", "release"); put("ref", "0.3.2"); put("version", "0.3.2")
            put("commit", commit); put("builtAt", "2026-10-16T02:23:09Z")
            put("sourceUrl", "https://codeload.github.com/janickiy/meet-space/tar.gz/$commit"); put("sourceSha256", "b".repeat(64))
            put("sourceFiles", bundle.manifest.getValue("sourceFiles"))
        }
        val release = JsonObject(bundle.manifest + lock.filterKeys { it in setOf("version", "commit", "builtAt", "sourceUrl", "sourceSha256", "sourceFiles") } + ("backendTag" to JsonPrimitive("0.3.2")))
        fun hash(plan: JsonObject): JsonObject {
            val data = JsonObject(plan.filterKeys { it != "hash" })
            return JsonObject(data + ("hash" to JsonPrimitive(sha256(canonicalJson(data).toByteArray()))))
        }
        val plan = hash(JsonObject(validPlan(bundle) + mapOf("sourceLock" to lock,
            "recipeSha256" to JsonPrimitive(sha256(canonicalJson(bundle.manifest).toByteArray())),
            "releaseSha256" to JsonPrimitive(sha256(canonicalJson(release).toByteArray())), "commit" to JsonPrimitive(commit), "releaseVersion" to JsonPrimitive("0.3.2"))))
        val parsed = bundle.parsePlan(plan)
        assertEquals(commit, parsed.commit); assertEquals("0.3.2", parsed.version); assertEquals("b".repeat(64), parsed.sourceSha256)
        assertEquals(parsed, bundle.parsePlan(jsonObject(parsed.json))) // Saved ticket requires no fresh lookup.
        for ((key, value) in mapOf("sourceUrl" to "https://untrusted.invalid/a", "repository" to "other/repo", "version" to "0.3.3", "sourceSha256" to "c".repeat(64))) {
            val changed = hash(JsonObject(plan + ("sourceLock" to JsonObject(lock + (key to JsonPrimitive(value))))))
            failed("PLAN_INVALID") { bundle.parsePlan(changed) }
        }
        failed("PLAN_INVALID") { bundle.parsePlan(hash(JsonObject(plan + ("recipeSha256" to JsonPrimitive("d".repeat(64)))))) }
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
    private fun validPlan(bundle: InstallerBundle, email: String = "owner@example.com"): JsonObject {
        val data = buildJsonObject {
            put("releaseSha256",sha256(canonicalJson(bundle.manifest).toByteArray())); put("commit",bundle.manifest.string("commit")); put("releaseVersion",bundle.manifest.string("version"))
            put("options",buildJsonObject { put("domain","example.com"); put("publicIpv4","8.8.8.8"); put("acmeEmail",email) })
            put("platform",buildJsonObject { put("arch","arm64") });put("docker",buildJsonObject { put("installed",false) });put("expiresAt",2000000000)
        }
        return JsonObject(data + ("hash" to JsonPrimitive(sha256(canonicalJson(data).toByteArray()))))
    }
}
