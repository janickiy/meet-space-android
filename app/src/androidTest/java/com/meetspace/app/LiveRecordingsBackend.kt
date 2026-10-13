package com.meetspace.app

import android.content.Context
import com.meetspace.core.data.FoundationRepository
import com.meetspace.core.data.MeetingsRepository
import com.meetspace.core.data.RecordingsRepository
import java.io.File
import java.lang.reflect.Proxy
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.util.Properties
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import okhttp3.OkHttpClient

/**
 * Only the instrumentation APK contains this constructor bridge. It instantiates the actual
 * internal data-module repository/API with strict fixture trust and RAM stores. No business
 * response, API method, authorization check or hostname verifier is replaced.
 */
class LiveRecordingsBackend(context: Context) {
    private val input = Properties().apply {
        File(context.filesDir, "live-recordings.properties").inputStream().use(::load)
    }
    private val origin = requireNotNull(input.getProperty("origin")).also {
        require(it == "https://10.0.2.2:18488" || it == "https://127.0.0.1:18488")
    }
    private val client: OkHttpClient
    private val actors = mutableListOf<FoundationRepository>()
    val primary: FoundationRepository by lazy { newRepository().also(actors::add) }

    init {
        val ca = File(context.filesDir, "live-recordings-ca.pem").inputStream().use {
            CertificateFactory.getInstance("X.509").generateCertificate(it)
        }
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null); setCertificateEntry("stage08", ca) }
        val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
            .trustManagers.single() as X509TrustManager
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
        client = OkHttpClient.Builder().sslSocketFactory(ssl.socketFactory, trust)
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .connectTimeout(5, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS).build()
    }

    fun resource(key: String): String {
        require(key in setOf("conferenceId", "otherConferenceId", "recordingId", "secondRecordingId", "audioRecordingId", "videoFileId", "previewFileId", "audioFileId", "debugFileId", "transcriptId", "summaryId", "mediaSha256", "mediaBytes", "segmentCount"))
        return requireNotNull(input.getProperty(key))
    }
    suspend fun loginPrimary(): FoundationRepository = primary.also { login(it, 0) }
    /** Reset only this bridge's in-memory profile store, never the user's persistent profiles. */
    suspend fun resetPrimaryForOnboarding() {
        if (primary.state.value.session != null) primary.logout()
        primary.state.value.profiles.toList().forEach { primary.removeServer(it.id) }
    }
    fun connectOrigin(): String = origin
    /** Keep private inputs inside the UI interaction block and out of assertion diagnostics. */
    fun withPrimaryLoginInput(action: (email: String, password: String) -> Unit) {
        action(requireNotNull(input.getProperty("email0")), requireNotNull(input.getProperty("password0")))
    }
    suspend fun actor(index: Int): FoundationRepository = newRepository().also { actors += it; login(it, index) }
    private suspend fun login(repository: FoundationRepository, index: Int) {
        if (repository.state.value.session != null) repository.logout()
        if (repository.state.value.selectedProfile == null) repository.addServer(origin)
        repository.login(requireNotNull(input.getProperty("email$index")), requireNotNull(input.getProperty("password$index")))
    }
    suspend fun finish() {
        actors.toList().forEach { if (it.state.value.session != null) runCatching { it.logout() } }
        actors.removeAll { it !== primary }
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
    }
    private fun newRepository(): FoundationRepository {
        val loader = FoundationRepository::class.java.classLoader
        fun type(name: String) = Class.forName("com.meetspace.core.data.$name", true, loader)
        val profilesType = type("ProfileStore")
        var settings: Any = type("PersistedSettings").getDeclaredConstructor().newInstance()
        val profiles = Proxy.newProxyInstance(loader, arrayOf(profilesType)) { proxy, method, arguments ->
            when (method.name) {
                "read" -> settings
                "write" -> { settings = arguments!![0]; Unit }
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === arguments?.get(0)
                "toString" -> "Stage08 private profile store"
                else -> error("Unexpected test profile operation")
            }
        }
        val credentialsType = type("CredentialStore")
        val credentials = mutableMapOf<Any, Any>()
        val credentialStore = Proxy.newProxyInstance(loader, arrayOf(credentialsType)) { proxy, method, arguments ->
            when (method.name) {
                "read" -> credentials[arguments!![0]]
                "write" -> { credentials[arguments!![0]] = arguments[1]; Unit }
                "delete" -> { credentials.remove(arguments!![0]); Unit }
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === arguments?.get(0)
                "toString" -> "Stage08 private credential store"
                else -> error("Unexpected test credential operation")
            }
        }
        val apiType = type("MeetSpaceApi")
        val api = apiType.getDeclaredConstructor(OkHttpClient::class.java).newInstance(client)
        return type("DefaultFoundationRepository").getDeclaredConstructor(profilesType, credentialsType, apiType)
            .newInstance(profiles, credentialStore, api) as FoundationRepository
    }
}

internal val FoundationRepository.liveRecordings get() = this as RecordingsRepository
