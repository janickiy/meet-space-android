package com.meetspace.core.ssh

import com.meetspace.core.model.*
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import kotlinx.serialization.json.*

internal val installerJson = Json { isLenient = false }
internal fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content
internal fun JsonObject.optional(key: String): String? = get(key)?.takeUnless { it is JsonNull }?.jsonPrimitive?.content
internal fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
internal fun canonicalJson(value: JsonElement): String = when (value) {
    is JsonObject -> value.keys.sorted().joinToString(",", "{", "}") { JsonPrimitive(it).toString() + ":" + canonicalJson(value.getValue(it)) }
    is JsonArray -> value.joinToString(",", "[", "]", transform = ::canonicalJson)
    else -> value.toString()
}
internal fun jsonObject(raw: String): JsonObject {
    if (raw.toByteArray().size > 256 * 1024) throw InstallFailure("RESPONSE_LIMIT")
    return try { installerJson.parseToJsonElement(raw).jsonObject } catch (_: Exception) { throw InstallFailure("INVALID_RESPONSE") }
}

internal class InstallerBundle(private val files: Map<String, ByteArray>, allowTestProfile: Boolean = false) {
    val manifest: JsonObject
    init {
        try {
            require(files.keys == names.toSet())
            require(files.values.all { it.size <= 256 * 1024 })
            val pem = files.getValue("trust-root.pem").toString(Charsets.US_ASCII)
                .replace("-----BEGIN PUBLIC KEY-----", "").replace("-----END PUBLIC KEY-----", "").replace(Regex("\\s"), "")
            val key = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(pem)))
            val signature = Signature.getInstance("SHA256withRSA").apply { initVerify(key); update(files.getValue("release.json")) }
            require(signature.verify(files.getValue("release.sig")))
            manifest = jsonObject(files.getValue("release.json").toString(Charsets.UTF_8))
            require(manifest.string("installerVersion") == "1.0.0")
            require(manifest.string("profile") == "source-v1" || allowTestProfile && manifest.string("profile") == "integration-v1")
            require(manifest.string("runnerSha256") == sha256(files.getValue("runner.py")))
            require(manifest.string("dockerKeySha256") == sha256(files.getValue("docker.asc")))
        } catch (_: Exception) { throw InstallFailure("RELEASE_INVALID") }
    }
    fun payload(action: String, request: JsonObject): ByteArray {
        require(action in setOf("plan", "apply", "status", "cancel", "resume"))
        val data = buildJsonObject {
            put("bundle", buildJsonObject { files.forEach { (name, bytes) -> put(name, Base64.getEncoder().encodeToString(bytes)) } })
            put("action", action); put("request", request)
        }
        val encoded = Base64.getEncoder().encodeToString(data.toString().toByteArray())
        return ("import base64,json\n_d=json.loads(base64.b64decode('$encoded'))\n" +
            "_b={k:base64.b64decode(v) for k,v in _d['bundle'].items()}\n" +
            "_g={'__name__':'meetspace_bootstrap','BOOTSTRAP_BUNDLE':_b}\n" +
            "exec(compile(_b['runner.py'],'meetspace-signed-installer','exec'),_g)\n" +
            "_g['entry'](_d['action'],_d['request'])\n").toByteArray()
    }
    fun parsePlan(value: JsonObject): InstallPlan {
        try {
            val hash = value.string("hash")
            require(hash.matches(Regex("[a-f0-9]{64}")))
            require(sha256(canonicalJson(JsonObject(value.filterKeys { it != "hash" })).toByteArray()) == hash)
            val release = effectiveManifest(value)
            require(value.string("releaseSha256") == sha256(canonicalJson(release).toByteArray()))
            require(value.string("commit") == release.string("commit"))
            require(value.string("releaseVersion") == release.string("version"))
            val options = value.getValue("options").jsonObject
            val mode = options.optional("imageMode")?.let { wire -> InstallImageMode.entries.single { it.wire == wire } } ?: InstallImageMode.SOURCE
            val requestedRelease = options.optional("imageRelease") ?: "latest"
            require(validImageRelease(requestedRelease))
            require(mode == InstallImageMode.IMAGES || "imageRelease" !in options)
            val imageLock = value["imageLock"]?.jsonObject
            if (mode == InstallImageMode.IMAGES) validateImageLock(requireNotNull(imageLock), value, release)
            else require(imageLock == null && value["sourceLock"]?.jsonObject?.optional("channel") != "prebuilt")
            val smtp = options["smtp"]?.takeUnless { it is JsonNull }?.jsonObject?.let {
                require(it.keys == setOf("host", "port", "from", "username", "tlsMode"))
                InstallSmtpOptions(it.string("host"), it.getValue("port").jsonPrimitive.int,
                    it.string("from"), it.string("username"), it.string("tlsMode")).also { smtp -> require(smtp.isValid()) }
            }
            val resourceProfile = value.optional("resourceProfile")
            require(resourceProfile == null || resourceProfile in setOf("standard", "compact-test"))
            val docker = value.getValue("docker").jsonObject
            val installCompose = docker["installCompose"]?.jsonPrimitive?.boolean ?: false
            val installBuildx = docker["installBuildx"]?.jsonPrimitive?.boolean ?: false
            val dockerInstalled = docker.getValue("installed").jsonPrimitive.boolean
            val missingDockerPlugins = buildList {
                if (installCompose) add("Compose")
                if (installBuildx) add("Buildx")
            }
            return InstallPlan(value.toString(), hash, value.string("releaseVersion"), value.string("commit"),
                InstallOptions(options.string("domain"), options.string("publicIpv4"), options.string("acmeEmail"), smtp, mode, requestedRelease),
                value.getValue("platform").jsonObject.string("arch"), !dockerInstalled || missingDockerPlugins.isNotEmpty(),
                value.getValue("expiresAt").jsonPrimitive.long, value.optional("existingJobId"), value.optional("existingPlanHash"),
                value["sourceLock"]?.jsonObject?.let { "${it.string("channel")}: ${it.string("ref")}" }, release.string("sourceSha256"), resourceProfile,
                dockerInstalled && missingDockerPlugins.isNotEmpty(), missingDockerPlugins,
                imageLock?.string("sha256"), imageLock?.getValue("sizeBytes")?.jsonPrimitive?.long)
        } catch (_: Exception) { throw InstallFailure("PLAN_INVALID") }
    }
    /** Only the selected upstream snapshot varies; executable policy and infrastructure stay signed. */
    private fun effectiveManifest(plan: JsonObject): JsonObject {
        val lock = plan["sourceLock"]?.jsonObject ?: return manifest
        require(if (lock.optional("channel") == "prebuilt") manifest.optional("prebuiltPolicy") == "github-prebuilt-v1"
            else manifest.optional("sourcePolicy") == "github-latest-stable-v1")
        require(plan.string("recipeSha256") == sha256(canonicalJson(manifest).toByteArray()))
        require(lock.keys == setOf("repository", "channel", "ref", "version", "commit", "builtAt", "sourceUrl", "sourceSha256", "sourceFiles"))
        require(lock.string("repository") == "janickiy/meet-space")
        val commit = lock.string("commit"); val version = lock.string("version"); val ref = lock.string("ref")
        require(commit.matches(Regex("[a-f0-9]{40}")))
        require(lock.string("sourceSha256").matches(Regex("[a-f0-9]{64}")))
        require(lock.string("sourceUrl") == "https://codeload.github.com/janickiy/meet-space/tar.gz/$commit")
        require(version.length <= 64 && version.matches(Regex("v?[0-9]+\\.[0-9]+\\.[0-9]+(?:[+-][A-Za-z0-9.-]+)?")))
        require(ref.matches(Regex("[A-Za-z0-9][A-Za-z0-9._/+-]{0,199}")))
        require(lock.string("builtAt").matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z")))
        val tag = when (lock.string("channel")) {
            "release", "prebuilt" -> { require(version == ref); JsonPrimitive(ref) }
            "default-branch" -> { require(version == "0.0.0-source.${commit.take(12)}"); JsonNull }
            else -> error("Invalid selection")
        }
        val hashes = lock.getValue("sourceFiles").jsonObject
        require(hashes.keys == manifest.getValue("sourceFiles").jsonObject.keys)
        require(hashes.values.all { it.jsonPrimitive.content.matches(Regex("[a-f0-9]{64}")) })
        return JsonObject(manifest + lock.filterKeys { it in setOf("version", "commit", "builtAt", "sourceUrl", "sourceSha256", "sourceFiles") } + mapOf(
            "backendTag" to tag,
            "dockerfiles" to JsonObject(manifest.getValue("dockerfiles").jsonObject.mapValues { (_, entry) ->
                val file = entry.jsonObject
                JsonObject(file + ("sha256" to hashes.getValue(file.string("path"))))
            })))
    }
    private fun validateImageLock(lock: JsonObject, plan: JsonObject, release: JsonObject) {
        require(manifest.optional("prebuiltPolicy") == "github-prebuilt-v1")
        require(lock.keys == setOf("repository", "version", "commit", "platform", "url", "sha256", "sizeBytes", "indexSha256"))
        require(plan.getValue("sourceLock").jsonObject.string("channel") == "prebuilt")
        val arch = plan.getValue("platform").jsonObject.string("arch")
        require(arch in setOf("amd64", "arm64"))
        val version = release.string("version")
        require(version != "latest" && validImageRelease(version))
        require((plan.getValue("options").jsonObject.optional("imageRelease") ?: "latest") in setOf("latest", version))
        require(lock.string("repository") == "janickiy/meet-space" && lock.string("version") == version && lock.string("commit") == release.string("commit"))
        require(lock.string("platform") == "linux/$arch")
        require(lock.string("url") == "https://github.com/janickiy/meet-space/releases/download/$version/meetspace-$version-linux-$arch.tar.gz")
        require(listOf("sha256", "indexSha256").all { lock.string(it).matches(Regex("[a-f0-9]{64}")) })
        require(lock.getValue("sizeBytes").jsonPrimitive.long in 1..2L * 1024 * 1024 * 1024)
    }
    companion object { val names = listOf("runner.py", "trust-root.pem", "release.json", "release.sig", "docker.asc") }
}

internal fun parseInstallerReply(raw: String): JsonObject {
    val response = jsonObject(raw)
    try {
        require(response.string("protocol") == "MSI1")
        if (!response.getValue("ok").jsonPrimitive.boolean) {
            val code = response.string("errorCode")
            throw InstallFailure(if (code.matches(Regex("[A-Z_]{1,64}"))) code else "INVALID_RESPONSE")
        }
        return response.getValue("result").jsonObject
    } catch (error: InstallFailure) { throw error }
    catch (_: Exception) { throw InstallFailure("INVALID_RESPONSE") }
}

internal fun parseInstallStatus(value: JsonObject, ticket: InstallTicket): InstallStatus {
    try {
        require(value.string("jobId") == ticket.jobId && value.string("planHash") == ticket.planHash)
        require(value.string("commit") == ticket.plan.commit && value.string("domain") == ticket.plan.options.domain)
        val states = setOf("running", "cancel_requested", "cancelled", "needs_recovery", "failed", "completed")
        val state = value.string("state"); require(state in states)
        fun safePhase(v: JsonObject) = (v.optional("phase") ?: "queued").also { require(it.matches(Regex("[a-z:-]{1,64}"))) }
        fun safeError(v: JsonObject) = v.optional("errorCode")?.also { require(it.matches(Regex("[A-Z_]{1,64}"))) }
        val completed = value.getValue("completedPhases").jsonArray.map { it.jsonPrimitive.content }
        require(completed.all { it in installPhases } && completed.distinct().size == completed.size)
        if (state == "completed") require(completed == installPhases)
        val events = value.getValue("events").jsonArray.also { require(it.size <= 64) }.map {
            val e = it.jsonObject; require(e.string("state") in states)
            InstallEvent(e.getValue("sequence").jsonPrimitive.long, safePhase(e), e.string("state"), safeError(e))
        }
        return InstallStatus(ticket.jobId, state, safePhase(value), completed, events, safeError(value))
    } catch (_: Exception) { throw InstallFailure("INVALID_RESPONSE") }
}
val installPhases = listOf("docker", "source", "images", "configuration", "dependencies", "backup", "migrations", "https", "services", "readiness")
