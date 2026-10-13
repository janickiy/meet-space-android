package com.meetspace.core.model

/** Public deployment metadata only. Never contains SSH or application secrets. */
data class InstallOptions(val domain: String, val publicIpv4: String, val acmeEmail: String)
data class InstallPlan(
    val json: String, val hash: String, val version: String, val commit: String,
    val options: InstallOptions, val architecture: String, val installDocker: Boolean,
    val expiresAt: Long, val existingJobId: String? = null, val existingPlanHash: String? = null,
)
data class InstallTicket(val endpoint: SshEndpoint, val plan: InstallPlan, val jobId: String) {
    val planHash: String get() = plan.existingPlanHash ?: plan.hash
}
data class InstallEvent(val sequence: Long, val phase: String, val state: String, val errorCode: String?)
data class InstallStatus(val jobId: String, val state: String, val phase: String, val completedPhases: List<String>, val events: List<InstallEvent>, val errorCode: String?) {
    val terminal: Boolean get() = state in setOf("completed", "cancelled", "needs_recovery", "failed")
}
class InstallFailure(val code: String) : Exception("Установщик: $code")
