package com.meetspace.core.model

/** Public deployment metadata only. Never contains SSH or application secrets. */
enum class InstallImageMode(val wire: String) { IMAGES("images"), SOURCE("source") }
data class InstallOptions(val domain: String, val publicIpv4: String, val acmeEmail: String = "", val smtp: InstallSmtpOptions? = null,
    val imageMode: InstallImageMode = InstallImageMode.SOURCE, val imageRelease: String = "latest", val admin: InstallAdminOptions? = null)
fun validImageRelease(value: String): Boolean = value == "latest" || value.length <= 64 &&
    value.matches(Regex("v?[0-9]+\\.[0-9]+\\.[0-9]+(?:-[A-Za-z0-9.-]+)?"))
/** Public administrator identity only; bootstrap credentials are apply-only. */
data class InstallAdminOptions(val username: String = "admin", val httpsPort: Int = 8081) {
    fun isValid(): Boolean = username.matches(Regex("[a-z0-9][a-z0-9.@+_-]{2,39}")) &&
        httpsPort in 1024..65535 && httpsPort !in setOf(8082, 3478, 5349, 50010) && httpsPort !in 49160..49259
    fun url(domain: String): String = "https://$domain:$httpsPort"
}
/** Match the backend's Unicode length limit, without accepting invalid UTF-16 or control delimiters. */
fun isValidInstallAdminPassword(password: String): Boolean {
    val bytes = password.toByteArray(Charsets.UTF_8)
    return try { password.codePointCount(0, password.length) in 8..128 && bytes.size <= 512 &&
        bytes.toString(Charsets.UTF_8) == password && password.none { it in "\r\n\u0000" } }
    finally { bytes.fill(0) }
}
/** SMTP routing and identity only; the password is supplied separately when applying a plan. */
data class InstallSmtpOptions(val host: String, val port: Int, val from: String, val username: String, val tlsMode: String) {
    fun isValid(): Boolean = host.length in 1..253 && host.matches(Regex("[A-Za-z0-9.-]+")) &&
        host.split('.').all { it.length in 1..63 && it.first().isLetterOrDigit() && it.last().isLetterOrDigit() } &&
        port in 1..65535 && from.length <= 254 && from.matches(Regex("[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,63}")) &&
        from.substringBefore('@').let { it.length <= 64 && !it.startsWith('.') && !it.endsWith('.') && !it.contains("..") } &&
        from.substringAfter('@').split('.').all { it.length in 1..63 && it.first().isLetterOrDigit() && it.last().isLetterOrDigit() } &&
        username.isNotBlank() && username.toByteArray().size <= 512 && username.none { it.code < 32 || it.code == 127 } && tlsMode in setOf("tls", "starttls")
}
/** Validate an apply-only password without retaining its UTF-8 byte buffer. */
fun isValidInstallSmtpPassword(password: String): Boolean {
    val bytes = password.toByteArray()
    return try { bytes.isNotEmpty() && bytes.size <= 4096 && password.none { it in "\r\n\u0000" } }
    finally { bytes.fill(0) }
}
data class InstallPlan(
    val json: String, val hash: String, val version: String, val commit: String,
    val options: InstallOptions, val architecture: String, val installDocker: Boolean,
    val expiresAt: Long, val existingJobId: String? = null, val existingPlanHash: String? = null,
    val sourceSelection: String? = null, val sourceSha256: String? = null,
    val resourceProfile: String? = null,
    val installDockerPluginsOnly: Boolean = false,
    val missingDockerPlugins: List<String> = emptyList(),
    val imageArchiveSha256: String? = null,
    val imageArchiveBytes: Long? = null,
)
data class InstallTicket(val endpoint: SshEndpoint, val plan: InstallPlan, val jobId: String) {
    val planHash: String get() = plan.existingPlanHash ?: plan.hash
}
data class InstallEvent(val sequence: Long, val phase: String, val state: String, val errorCode: String?)
data class InstallStatus(val jobId: String, val state: String, val phase: String, val completedPhases: List<String>, val events: List<InstallEvent>, val errorCode: String?) {
    val terminal: Boolean get() = state in setOf("completed", "cancelled", "needs_recovery", "failed")
}
class InstallFailure(val code: String) : Exception("Установщик: $code")
