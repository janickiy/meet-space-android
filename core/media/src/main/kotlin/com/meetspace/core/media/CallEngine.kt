package com.meetspace.core.media

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjection
import androidx.core.content.ContextCompat
import com.meetspace.core.data.*
import com.meetspace.core.model.AppFailure
import com.meetspace.core.model.Meeting
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.*
import org.webrtc.*
import org.webrtc.audio.JavaAudioDeviceModule

/** All PeerConnection mutations and SDP transactions run through one bounded actor, never the UI thread. */
class CallEngine(
    context: Context,
    private val repository: CallRepository,
    private val meetings: MeetingsRepository,
    private val onProjectionStopped: () -> Unit = {},
) {
    private val context = context.applicationContext
    private val dispatcher = Executors.newSingleThreadExecutor { r -> Thread(r, "MeetSpace-call") }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val queueOverflow = java.util.concurrent.atomic.AtomicBoolean(false)
    private val commands = Channel<suspend () -> Unit>(256)
    private val mutable = MutableStateFlow(CallState())
    val state = mutable.asStateFlow()
    private var generation = 0L
    private var wanted = false
    private var membershipEntered = false
    private var directClientId: String? = null
    private var attempts = 0
    private var signaling: CallSignaling? = null
    private var reader: Job? = null
    private var timer: Job? = null
    private var statsJob: Job? = null
    private var reconnectJob: Job? = null
    private var credentialTimer: Job? = null
    private var connectionId = ""
    private var peerId = ""
    private var joinRequest = ""
    private var presenceSequence = 0L
    private var policyVersion = -1L
    private var catalogRevision = -1L
    private var catalog = emptyList<CatalogTrack>()
    private var gate = NegotiationGate()
    private var hasRemote = false
    private var hasOffer = false
    private val remoteIce = mutableListOf<JsonElement>()
    private val localIce = mutableListOf<JsonElement>()
    private val seenIce = linkedSetOf<String>()
    private var factory: PeerConnectionFactory? = null
    private var egl: EglBase? = null
    private var audioModule: JavaAudioDeviceModule? = null
    private var pc: PeerConnection? = null
    private var micSource: AudioSource? = null
    private var micTrack: AudioTrack? = null
    private var cameraCapture: VideoCapturer? = null
    private var cameraHelper: SurfaceTextureHelper? = null
    private var cameraSource: VideoSource? = null
    private var cameraTrack: VideoTrack? = null
    private var screenCapture: ScreenCapturerAndroid? = null
    private var screenHelper: SurfaceTextureHelper? = null
    private var screenSource: VideoSource? = null
    private var screenTrack: VideoTrack? = null
    private var screenSession = 0L
    private var screenSessionActive = false
    private var screenSize = 1280 to 720
    private var screenSenderLimited = false
    private data class ScreenStatsSample(val stream: String, val timestampUs: Double, val frames: Long)
    private var screenStatsSample: ScreenStatsSample? = null
    private val slots = mutableMapOf<String, RtpTransceiver>()
    private var peerTransceivers = emptyList<RtpTransceiver>()
    private var remoteTracksByMid = emptyMap<String, RemoteSdpTrack>()
    private data class ReceivedVideo(val receiverId: String, val binding: RemoteSdpTrack, val feed: VideoFeed)
    private val received = mutableMapOf<String, ReceivedVideo>()
    private var captureWidth = 1280
    private var captureHeight = 720
    private var captureFps = 30
    private val audio = CallAudio(this.context, { routes -> enqueue { mutable.value = state.value.copy(routes = routes) } }, { pauseDevices() })

    init {
        scope.launch {
            for (command in commands) try { command() }
            catch (_: CancellationException) { fail("Операция звонка не завершилась вовремя.") }
            catch (e: CallDisconnected) { if (e.retryable) reconnect() else fail("Соединение звонка закрыто.") }
            catch (e: AppFailure) { fail(e.message ?: "Не удалось подключиться к встрече.") }
            catch (error: Exception) { android.util.Log.w("MeetSpaceCall", "operation_failed: " + error.javaClass.simpleName + " at " + error.stackTrace.firstOrNull()?.let { it.className + ":" + it.lineNumber }); fail("Не удалось установить медиасвязь. Проверьте сеть и повторите подключение.") }
        }
    }
    private fun enqueue(command: suspend () -> Unit) {
        if (!commands.trySend(command).isSuccess && queueOverflow.compareAndSet(false, true)) {
            // Backpressure is fatal: never silently lose SDP or ICE and pretend the call is live.
            scope.launch { commands.send { queueOverflow.set(false); fail("Слишком много событий звонка. Подключитесь снова.") } }
        }
    }
    private fun callback(epoch: Long, block: suspend () -> Unit) = enqueue { if (epoch == generation) block() }
    private fun permission(name: String) = ContextCompat.checkSelfPermission(context, name) == PackageManager.PERMISSION_GRANTED
    private fun id() = UUID.randomUUID().toString()

    fun prepare(meeting: Meeting) = enqueue {
        if (state.value.visible) return@enqueue
        if (meeting.status != "active") return@enqueue
        mutable.value = CallState(CallPhase.PREJOIN, meeting.id, meeting.title, status = "Перед входом")
    }
    /** Local, explicitly selected camera preview only. No meeting REST, ticket or SFU publication. */
    fun prepareDirectPreview(callId: String, title: String, microphone: Boolean = false, camera: Boolean = false) = enqueue {
        if (state.value.visible) return@enqueue
        validId(callId)
        directClientId = null
        mutable.value = CallState(phase = CallPhase.PREJOIN, title = title.take(200), status = "Личный звонок",
            microphone = microphone && permission(Manifest.permission.RECORD_AUDIO), directCallId = callId)
        if (camera && permission(Manifest.permission.CAMERA)) {
            ensureFactory()
            try { replaceCamera(true) }
            catch (_: Exception) { replaceCamera(false); notice("Камера недоступна. Можно ответить без видео.") }
        }
    }
    /** The controller owns durable call actions/heartbeat; this engine owns only the accepted media. */
    fun joinDirect(conferenceId: String, title: String, clientId: String, microphone: Boolean = true,
        camera: Boolean = false, callId: String = state.value.directCallId.orEmpty()) = enqueue {
        if (state.value.visible && (state.value.directCallId != callId ||
                state.value.phase !in setOf(CallPhase.PREJOIN, CallPhase.FAILED))) return@enqueue
        validId(callId); validId(conferenceId); validId(clientId)
        directClientId = clientId
        mutable.value = state.value.copy(phase = CallPhase.CONNECTING, conferenceId = conferenceId,
            title = title.take(200), directCallId = callId, microphone = microphone && permission(Manifest.permission.RECORD_AUDIO),
            status = "Подключаем личный звонок…", error = null)
        authorizeDirect()
        wanted = true; attempts = 0; membershipEntered = false
        ensureFactory()
        if (!camera || !permission(Manifest.permission.CAMERA)) replaceCamera(false)
        else if (cameraTrack == null && permission(Manifest.permission.CAMERA)) {
            try { replaceCamera(true) }
            catch (_: Exception) { replaceCamera(false); notice("Камера недоступна. Можно разговаривать без видео.") }
        }
        audio.start()
        open()
    }
    fun connect() = enqueue {
        if (state.value.phase !in setOf(CallPhase.PREJOIN, CallPhase.FAILED)) return@enqueue
        if (state.value.directCallId != null) {
            if (directClientId == null || state.value.conferenceId.isBlank()) {
                notice("Медиасвязь подключается после принятия личного звонка."); return@enqueue
            }
            authorizeDirect()
            wanted = true; attempts = 0
            mutable.value = state.value.copy(phase = CallPhase.CONNECTING, status = "Подключаем личный звонок…", error = null)
            ensureFactory(); audio.start(); open()
            return@enqueue
        }
        wanted = true; attempts = 0
        mutable.value = state.value.copy(phase = CallPhase.CONNECTING, status = "Проверяем допуск…", error = null)
        val conference = meetings.conference(state.value.conferenceId)
        if (conference.status != "active") { fail("Встреча ещё не началась или уже завершена."); return@enqueue }
        val member = meetings.membership(conference.id)
        val admitted = if (member?.canParticipate == true) member else meetings.joinMeeting(conference.id)
        membershipEntered = true
        if (!admitted.canParticipate) { fail("Ожидайте допуска организатора и повторите вход."); return@enqueue }
        ensureFactory()
        audio.start()
        open()
    }
    fun retry() = connect()
    fun microphone(enabled: Boolean) = enqueue {
        if (enabled && (state.value.microphoneBlocked || !permission(Manifest.permission.RECORD_AUDIO))) {
            notice("Разрешите доступ к микрофону."); return@enqueue
        }
        if (state.value.phase !in setOf(CallPhase.PREJOIN, CallPhase.CONNECTED)) return@enqueue
        mutable.value = state.value.copy(microphone = enabled, error = null)
        if (pc != null) { replaceMic(enabled); changed() }
    }
    fun camera(enabled: Boolean) = enqueue {
        if (enabled && (state.value.cameraBlocked || !permission(Manifest.permission.CAMERA))) {
            notice("Разрешите доступ к камере."); return@enqueue
        }
        if (state.value.phase !in setOf(CallPhase.PREJOIN, CallPhase.CONNECTED)) return@enqueue
        ensureFactory()
        try { replaceCamera(enabled) }
        catch (_: Exception) { replaceCamera(false); notice("Камера недоступна. Можно войти без видео или повторить включение.") }
        changed()
    }
    fun switchCamera() = enqueue {
        (cameraCapture as? CameraVideoCapturer)?.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
            override fun onCameraSwitchDone(front: Boolean) = Unit
            override fun onCameraSwitchError(error: String) { enqueue { notice("Не удалось переключить камеру.") } }
        })
    }
    fun audioRoute(id: Int) = enqueue { if (!audio.select(id)) notice("Аудиоустройство недоступно. Проверьте подключение гарнитуры.") }
    fun refreshAudioRoutes() = enqueue { audio.refresh() }
    fun pauseDevices() = enqueue {
        if (!state.value.visible) return@enqueue
        replaceMic(false); replaceCamera(false)
        mutable.value = state.value.copy(microphone = false, camera = false)
        changed()
    }
    fun message(text: String) = enqueue { notice(text) }
    private fun notice(text: String) { mutable.value = state.value.copy(error = text) }

    /** Called only after the service has entered foreground with a fresh Android consent Intent. */
    fun startScreen(consent: Intent, expectedConferenceId: String? = null, expectedDirectCallId: String? = null) = enqueue {
        if (!matchesScreenContext(expectedConferenceId, expectedDirectCallId) || state.value.phase != CallPhase.CONNECTED ||
                state.value.screenBlocked || state.value.cameraBlocked || screenCapture != null) {
            onProjectionStopped(); return@enqueue
        }
        val epoch = generation
        val session = ++screenSession
        screenSessionActive = true
        screenSize = 1280 to 720
        try {
            val source = factory!!.createVideoSource(true)
            screenSource = source
            ScreenShareVideoPolicy.apply(source)
            val helper = checkNotNull(SurfaceTextureHelper.create("MeetSpace-screen", egl!!.eglBaseContext))
            screenHelper = helper
            val capture = ScreenCapturerAndroid(consent, object : MediaProjection.Callback() {
                override fun onStop() { callback(epoch) {
                    if (session == screenSession && screenSessionActive) { stopScreenInternal(); changed() }
                } }
                override fun onCapturedContentResize(width: Int, height: Int) {
                    callback(epoch) {
                        if (session == screenSession && screenSessionActive && width > 0 && height > 0) {
                            val size = ScreenShareVideoPolicy.captureSize(width, height)
                            if (size != screenSize) {
                                screenSource?.let(ScreenShareVideoPolicy::apply)
                                screenCapture?.changeCaptureFormat(size.first, size.second, ScreenShareVideoPolicy.MAX_FPS)
                                screenSize = size
                            }
                        }
                    }
                }
            })
            screenCapture = capture
            capture.initialize(helper, context, source.capturerObserver)
            capture.startCapture(screenSize.first, screenSize.second, ScreenShareVideoPolicy.MAX_FPS)
            val track = factory!!.createVideoTrack(id(), source)
            screenTrack = track
            track.setEnabled(false)
            checkNotNull(slots["video/screen"]) { "Screen sender is unavailable" }
            setSource("video/screen", track)
            track.setEnabled(screenSenderLimited)
            mutable.value = state.value.copy(sharing = true, localScreen = VideoFeed(track, egl!!.eglBaseContext), error = null)
        } catch (error: Exception) {
            stopScreenInternal()
            android.util.Log.w("MeetSpaceCall", "screen_start_failed: ${error.javaClass.simpleName}")
            notice("Не удалось запустить демонстрацию. Повторите её с новым разрешением Android.")
            return@enqueue
        }
        changed()
    }
    fun stopScreen(expectedConferenceId: String? = null, expectedDirectCallId: String? = null) = enqueue {
        if (!matchesScreenContext(expectedConferenceId, expectedDirectCallId)) return@enqueue
        stopScreenInternal(); changed()
    }
    private fun matchesScreenContext(conferenceId: String?, directCallId: String?): Boolean =
        if (conferenceId != null) state.value.conferenceId == conferenceId && state.value.directCallId == directCallId
        else directCallId == null || state.value.directCallId == directCallId
    private fun stopScreenInternal() {
        val capture = screenCapture
        val track = screenTrack
        val source = screenSource
        val helper = screenHelper
        val feed = state.value.localScreen
        val active = screenSessionActive
        // Invalidate even callbacks queued by stopCapture before allowing another start.
        screenSession++
        screenSessionActive = false
        screenSenderLimited = false; screenStatsSample = null
        screenCapture = null
        screenTrack = null; screenSource = null; screenHelper = null
        runCatching { track?.setEnabled(false) }
        runCatching { setSource("video/screen", null) }
        runCatching { feed?.close() }
        mutable.value = state.value.copy(sharing = false, localScreen = null,
            diagnostics = state.value.diagnostics.copy(screenFramesEncoded = 0, screenEncodedFps = null))
        val projection = runCatching { capture?.mediaProjection }.getOrNull()
        runCatching { capture?.stopCapture() }
        // dispose() alone does not stop MediaProjection; also cover partial startup/stop failure.
        runCatching { projection?.stop() }
        runCatching { capture?.dispose() }
        runCatching { track?.dispose() }
        runCatching { source?.dispose() }
        runCatching { helper?.dispose() }
        if (active) runCatching { onProjectionStopped() }
    }
    fun leave(updateMembership: Boolean = true) = enqueue { leaveInternal(updateMembership) }
    /** A delayed direct-call cleanup cannot tear down a newer conference or another call. */
    fun leaveDirect(callId: String) = enqueue {
        if (state.value.directCallId != callId) return@enqueue
        leaveInternal(updateMembership = false)
    }
    private suspend fun leaveInternal(updateMembership: Boolean) {
        val conference = state.value.conferenceId
        val joined = membershipEntered && state.value.directCallId == null
        membershipEntered = false
        wanted = false
        mutable.value = state.value.copy(phase = CallPhase.LEAVING, status = "Выходим…")
        disposeAll()
        var error: String? = null
        if (updateMembership && joined && conference.isNotBlank()) {
            try { withTimeout(10_000) { meetings.leaveMeeting(conference) } }
            catch (_: Exception) { error = "Медиасвязь закрыта. Сервер не подтвердил выход из встречи; обновите её состояние." }
        }
        directClientId = null
        mutable.value = CallState(error = error)
    }

    private suspend fun authorizeDirect() {
        val callId = state.value.directCallId ?: return
        val calls = repository as? DirectCallsRepository ?: throw AppFailure(
            com.meetspace.core.model.FailureKind.UNAVAILABLE, "Личные звонки недоступны в этом подключении.")
        val accountId = (repository as? FoundationRepository)?.state?.value?.session?.user?.id
            ?: throw AppFailure(com.meetspace.core.model.FailureKind.SESSION_EXPIRED, "Войдите в учётную запись MeetSpace.")
        val call = calls.directCall(callId)
        if (call.id != callId || !CallProtocol.directEndpoint(call, accountId, directClientId.orEmpty(), state.value.conferenceId)) {
            throw AppFailure(com.meetspace.core.model.FailureKind.CONFLICT, "Личный звонок завершён или принят на другом устройстве.")
        }
    }
    private suspend fun open() {
        if (state.value.directCallId != null) authorizeDirect()
        val session = if (state.value.directCallId == null) repository.openCall(state.value.conferenceId)
            else repository.openDirectCall(state.value.conferenceId, checkNotNull(directClientId))
        // Take ownership before validation so fail()/closeNetwork() also closes rejected ICE sessions.
        signaling = session
        val config = CallProtocol.ice(session.iceConfiguration)
        val epoch = generation
        mutable.value = state.value.copy(status = "Подключаемся к комнате…")
        reader = scope.launch(Dispatchers.IO) {
            try {
                for (raw in session.messages) callback(epoch) { process(CallProtocol.event(raw, state.value.conferenceId), config) }
                callback(epoch) { reconnect() }
            } catch (e: CallDisconnected) { callback(epoch) { if (e.retryable) reconnect() else fail("Доступ к звонку закрыт. Проверьте учётную запись и допуск.") } }
            catch (_: CancellationException) { }
            catch (_: Exception) { callback(epoch) { reconnect() } }
        }
        deadline(20_000, "Сервер не подтвердил подключение к комнате.")
        config.expiresAt?.let { expiry ->
            credentialTimer = scope.launch {
                delay(((expiry - Instant.now().epochSecond - 30).coerceAtLeast(1)) * 1000)
                callback(epoch) { reconnect() }
            }
        }
    }
    private fun send(type: String, data: JsonObject = buildJsonObject {}): String {
        val request = id()
        if (signaling?.send(CallProtocol.envelope(request, type, state.value.conferenceId, data)) != true) throw CallDisconnected(true)
        return request
    }
    private fun peerData(extra: JsonObjectBuilder.() -> Unit = {}) = buildJsonObject { put("mediaPeerId", peerId); extra() }
    private suspend fun process(event: CallEvent, config: IceConfiguration) {
        val d = event.data
        when (event.type) {
            "chat.message.created", "chat.message.updated", "chat.message.deleted" ->
                mutable.value = state.value.copy(discussionRevision = state.value.discussionRevision + 1)
            "recording.started", "recording.stopping", "recording.processing", "recording.ready", "recording.failed" ->
                mutable.value = state.value.copy(recordingRevision = state.value.recordingRevision + 1)
            "conference.state", "participant.connected", "participant.disconnected" -> {
                val connection = validId(d.string("connectionId"))
                val participant = validId(d.string("participantId"))
                if (d.string("status") != "active") { fail("Встреча завершена."); return }
                if (connectionId.isNotEmpty() && connection != connectionId) throw ProtocolFailure()
                connectionId = connection
                val people = d.array("participants")
                if (people.size > 500) throw ProtocolFailure()
                val roster = people.map { entry ->
                    val p = entry as? JsonObject ?: throw ProtocolFailure()
                    CallParticipant(validId(p.string("id")), p.string("displayName").take(100), p.flag("online"), p.flag("microphoneEnabled"), p.flag("cameraEnabled"))
                }
                mutable.value = state.value.copy(participantId = participant, participants = roster)
                if (joinRequest.isEmpty()) { joinRequest = send("media.join"); deadline(20_000, "Медиасервис не подтвердил вход.") }
            }
            "participant.media.updated" -> {
                val person = d.obj("participant")
                val participantId = validId(person.string("id"))
                mutable.value = state.value.copy(participants = state.value.participants.map {
                    if (it.id == participantId) it.copy(microphone = person.flag("microphoneEnabled"), camera = person.flag("cameraEnabled")) else it
                })
            }
            "media.joined" -> {
                if (event.reply != joinRequest || pc != null) return
                peerId = validId(d.string("mediaPeerId"))
                val maxPeers = d.number("maxPeers").toInt().takeIf { it in 1..32 } ?: throw ProtocolFailure()
                catalog = CallProtocol.catalog(d)
                d["policy"]?.let { policy(it as? JsonObject ?: throw ProtocolFailure()) }
                if (!wanted) return
                (d["videoCapture"] as? JsonObject)?.let {
                    captureWidth = it.number("maxWidth").toInt().coerceIn(160, 1280)
                    captureHeight = it.number("maxHeight").toInt().coerceIn(120, 720)
                    captureFps = it.number("maxFrameRate").toInt().coerceIn(1, 30)
                    cameraCapture?.changeCaptureFormat(captureWidth, captureHeight, captureFps)
                }
                // REST retains short-lived TURN policy/expiry that this server omits in media.joined.
                createPeer(config, maxPeers)
                negotiate()
            }
            "ack" -> {
                if (d.string("type") == "media.ready" && event.reply?.let(gate::acknowledge) == true) {
                    timer?.cancel()
                    if (pc?.connectionState() == PeerConnection.PeerConnectionState.CONNECTED) connected()
                    else deadline(30_000, "ICE-соединение не установилось. Проверьте доступность STUN/TURN.")
                    negotiate()
                }
            }
            "error" -> {
                val code = d.string("code")
                if (event.reply == joinRequest && state.value.phase == CallPhase.RECONNECTING &&
                    code in setOf("media_unavailable", "media_limit_exceeded", "media_ownership_lost", "media_peer_not_found")) {
                    reconnect(); return
                }
                if (event.reply == gate.pending?.request && d.string("code") in setOf("screen_sharing_conflict", "media_policy_blocked")) {
                    pc?.description(SessionDescription(SessionDescription.Type.ROLLBACK, ""), true)
                    gate.rollback(); stopScreenInternal()
                    if (d.string("code") == "media_policy_blocked") { replaceMic(false); replaceCamera(false) }
                    notice(if (d.string("code") == "screen_sharing_conflict") "Экран уже показывает другой участник." else "Организатор ограничил передачу устройств.")
                    negotiate(); return
                }
                if (event.reply in listOfNotNull(joinRequest, gate.pending?.request, gate.pending?.ready)) fail("Медиасервис отклонил подключение. Проверьте допуск и повторите.")
            }
            else -> {
                if (!event.type.startsWith("media.") || pc == null || d.string("mediaPeerId") != peerId) return
                when (event.type) {
                    "media.answer" -> {
                        val pending = gate.pending ?: return
                        if (d.string("negotiationId") != pending.id || event.reply != pending.request || pending.ready != null) return
                        val answer = RemoteSdpTracks.prepare(d.string("sdp"))
                        pc!!.description(SessionDescription(SessionDescription.Type.ANSWER, answer.description), false)
                        remoteTracksByMid = answer.tracksByMid
                        syncVideos()
                        screenTrack?.let { track ->
                            screenSenderLimited = ScreenShareVideoPolicy.applySenderLimit(checkNotNull(slots["video/screen"]).sender)
                            check(screenSenderLimited) { "Negotiated screen sender has no encoding" }
                            track.setEnabled(true)
                        }
                        hasRemote = true
                        remoteIce.toList().forEach(::addIce); remoteIce.clear()
                        pending.ready = send("media.ready", peerData { put("negotiationId", pending.id) })
                        deadline(15_000, "Медиасервис не подтвердил готовность.")
                    }
                    "media.renegotiate" -> { gate.remote(d.number("revision")); negotiate() }
                    "media.ice" -> {
                        val candidate = d["candidate"] ?: throw ProtocolFailure()
                        if (hasRemote) addIce(candidate) else { if (remoteIce.size >= 128) throw ProtocolFailure(); remoteIce.add(candidate) }
                    }
                    "media.tracks" -> {
                        val revision = d.number("revision")
                        if (revision < catalogRevision) return
                        val nextCatalog = CallProtocol.catalog(d)
                        catalogRevision = revision; catalog = nextCatalog; syncVideos()
                    }
                    "media.policy" -> { if (policy(d.obj("policy"))) changed() }
                    "media.state" -> if ((d["state"] as? JsonPrimitive)?.content in setOf("failed", "closed")) reconnect()
                    "media.left" -> reconnect()
                }
            }
        }
    }
    private suspend fun policy(policy: JsonObject): Boolean {
        val version = (policy["version"] as? JsonPrimitive)?.longOrNull ?: 0
        if (version <= policyVersion) return false
        policyVersion = version
        if (policy.flag("kicked")) { fail("Организатор закрыл доступ к встрече."); return false }
        val mic = policy.flag("microphoneBlocked"); val cam = policy.flag("cameraBlocked"); val screen = policy.flag("screenBlocked")
        val changed = mic && state.value.microphone || cam && state.value.camera || (screen || cam) && state.value.sharing
        mutable.value = state.value.copy(microphoneBlocked = mic, cameraBlocked = cam, screenBlocked = screen)
        if (mic) replaceMic(false)
        if (cam) replaceCamera(false)
        if (screen || cam) stopScreenInternal()
        return changed
    }
    private fun ensureFactory() {
        if (factory != null) return
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(false).setInjectableLogger({ _, _, _ -> }, Logging.Severity.LS_NONE).createInitializationOptions())
        val base = EglBase.create(); egl = base
        val adm = JavaAudioDeviceModule.builder(context).setUseHardwareAcousticEchoCanceler(true).setUseHardwareNoiseSuppressor(true).createAudioDeviceModule()
        audioModule = adm; adm.setAudioRecordEnabled(false)
        factory = PeerConnectionFactory.builder().setAudioDeviceModule(adm)
            .setAudioProcessingFactory(MicrophoneAudioProcessing.factory)
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(base.eglBaseContext, false, false))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(base.eglBaseContext)).createPeerConnectionFactory()
    }
    private fun createPeer(ice: IceConfiguration, maxPeers: Int) {
        val config = PeerConnection.RTCConfiguration(ice.servers.map { PeerConnection.IceServer.builder(it.urls).setUsername(it.username).setPassword(it.credential).createIceServer() }).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            iceTransportsType = if (ice.relay) PeerConnection.IceTransportsType.RELAY else PeerConnection.IceTransportsType.ALL
        }
        val epoch = generation
        val peer = factory!!.createPeerConnection(config, object : PeerConnection.Observer {
            override fun onSignalingChange(value: PeerConnection.SignalingState) = Unit
            override fun onIceConnectionChange(value: PeerConnection.IceConnectionState) = Unit
            override fun onConnectionChange(value: PeerConnection.PeerConnectionState) { callback(epoch) {
                when (value) {
                    PeerConnection.PeerConnectionState.CONNECTED -> { if (gate.pending == null) connected() }
                    PeerConnection.PeerConnectionState.DISCONNECTED -> {
                        mutable.value = state.value.copy(status = "Связь прервана…")
                        deadline(8_000, null)
                    }
                    PeerConnection.PeerConnectionState.FAILED -> reconnect()
                    else -> Unit
                }
            } }
            override fun onIceConnectionReceivingChange(value: Boolean) = Unit
            override fun onIceGatheringChange(value: PeerConnection.IceGatheringState) {
                if (value == PeerConnection.IceGatheringState.COMPLETE) callback(epoch) { sendIce(JsonNull) }
            }
            override fun onIceCandidate(candidate: IceCandidate) { callback(epoch) {
                sendIce(buildJsonObject { put("candidate", candidate.sdp); put("sdpMid", candidate.sdpMid); put("sdpMLineIndex", candidate.sdpMLineIndex) })
            } }
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
            override fun onAddStream(stream: MediaStream) = Unit
            override fun onRemoveStream(stream: MediaStream) = Unit
            override fun onDataChannel(channel: DataChannel) { channel.close(); channel.dispose() }
            override fun onRenegotiationNeeded() = Unit
            // Unified Plan tracks are bound after the answer applies, using the retained
            // transceiver's MID and the original SDP identity, never legacy MediaStreams.
            override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) { callback(epoch) { syncVideos() } }
            override fun onRemoveTrack(receiver: RtpReceiver) { callback(epoch) { syncVideos() } }
        }) ?: throw ProtocolFailure()
        pc = peer
        for (source in listOf("microphone", "camera", "video/screen", "audio/screen")) {
            val kind = if (source in setOf("microphone", "audio/screen")) MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO else MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO
            slots[source] = peer.addTransceiver(kind, RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY))
        }
        repeat(2 * (maxPeers - 1)) {
            peer.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO, RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY))
            peer.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO, RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY))
        }
        // getTransceivers disposes the wrappers returned by addTransceiver in this SDK.
        // Take one final snapshot and retain only those wrappers for the entire connection.
        val transceivers = peer.transceivers
        peerTransceivers = transceivers
        slots.clear()
        listOf("microphone", "camera", "video/screen", "audio/screen").forEachIndexed { index, source -> slots[source] = transceivers[index] }
        transceivers.forEach { transceiver ->
            val preferred = if (transceiver.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO) "opus" else "VP8"
            val codecs = factory!!.getRtpReceiverCapabilities(transceiver.mediaType).codecs.filter { it.name.equals(preferred, true) }
            if (codecs.isEmpty()) throw ProtocolFailure()
            transceiver.setCodecPreferences(codecs)
        }
        if (state.value.microphone) replaceMic(true)
        if (state.value.directCallId != null && state.value.camera && cameraTrack == null) {
            if (!permission(Manifest.permission.CAMERA)) replaceCamera(false)
            else try { replaceCamera(true) }
            catch (_: Exception) { replaceCamera(false); notice("Камера недоступна. Можно разговаривать без видео.") }
        }
        cameraTrack?.let { setSource("camera", it) }
        mutable.value = state.value.copy(status = "Согласуем медиасвязь…")
    }
    private fun setSource(source: String, track: MediaStreamTrack?) {
        slots[source]?.let {
            if (!it.sender.setTrack(track, false)) throw ProtocolFailure()
            if (source == "video/screen" && track != null) screenSenderLimited = ScreenShareVideoPolicy.applySenderLimit(it.sender)
            it.direction = if (track == null) RtpTransceiver.RtpTransceiverDirection.RECV_ONLY else RtpTransceiver.RtpTransceiverDirection.SEND_RECV
            it.sender.setStreams(listOf(if (source.endsWith("/screen")) "$peerId-screen" else peerId))
        }
    }
    private fun replaceMic(enabled: Boolean) {
        val effective = enabled && permission(Manifest.permission.RECORD_AUDIO) && !state.value.microphoneBlocked
        setSource("microphone", null)
        micTrack?.dispose(); micTrack = null
        micSource?.dispose(); micSource = null
        audioModule?.setMicrophoneMute(!effective); audioModule?.setAudioRecordEnabled(effective)
        if (effective && pc != null) {
            micSource = factory!!.createAudioSource(MediaConstraints())
            micTrack = factory!!.createAudioTrack(id(), micSource).also { setSource("microphone", it) }
        }
        mutable.value = state.value.copy(microphone = effective)
    }
    private fun replaceCamera(enabled: Boolean) {
        setSource("camera", null)
        mutable.value.localVideo?.close()
        mutable.value = state.value.copy(camera = false, localVideo = null)
        runCatching { cameraCapture?.stopCapture() }; cameraCapture?.dispose(); cameraCapture = null
        cameraTrack?.dispose(); cameraTrack = null
        cameraSource?.dispose(); cameraSource = null
        cameraHelper?.dispose(); cameraHelper = null
        if (!enabled) return
        val enumerator: CameraEnumerator = if (Camera2Enumerator.isSupported(context)) Camera2Enumerator(context) else Camera1Enumerator(false)
        val device = enumerator.deviceNames.firstOrNull(enumerator::isFrontFacing) ?: enumerator.deviceNames.firstOrNull()
            ?: throw IllegalStateException("Камера недоступна")
        val capture = enumerator.createCapturer(device, null) ?: throw IllegalStateException("Камера недоступна")
        cameraCapture = capture
        val source = factory!!.createVideoSource(false); cameraSource = source
        val helper = SurfaceTextureHelper.create("MeetSpace-camera", egl!!.eglBaseContext); cameraHelper = helper
        capture.initialize(helper, context, source.capturerObserver)
        capture.startCapture(captureWidth, captureHeight, captureFps)
        val track = factory!!.createVideoTrack(id(), source); cameraTrack = track
        setSource("camera", track)
        mutable.value = state.value.copy(camera = true, localVideo = VideoFeed(track, egl!!.eglBaseContext), error = null)
    }
    private suspend fun changed() {
        gate.changed()
        if (pc != null) { negotiate(); updatePresence() }
    }
    private suspend fun negotiate() {
        val peer = pc ?: return
        val pending = gate.begin() ?: return
        peer.description(peer.offer(), true)
        val sdp = peer.localDescription.description
        if (sdp.toByteArray().size > 49_152) throw ProtocolFailure()
        pending.request = send("media.offer", peerData {
            put("negotiationId", pending.id); put("sdp", sdp)
            put("publications", buildJsonArray {
                slots.forEach { (source, transceiver) ->
                    val track = when (source) { "microphone" -> micTrack; "camera" -> cameraTrack; "video/screen" -> screenTrack; else -> null }
                    if (track != null) add(buildJsonObject {
                        put("mid", transceiver.mid ?: throw ProtocolFailure()); put("source", source); put("trackId", track.id())
                    })
                }
            })
        })
        hasOffer = true
        localIce.toList().forEach { send("media.ice", peerData { put("candidate", it) }) }; localIce.clear()
        deadline(20_000, "Медиасервис не ответил на предложение соединения.")
    }
    private fun sendIce(candidate: JsonElement) {
        if (candidate.toString().toByteArray().size > 4096) throw ProtocolFailure()
        if (!hasOffer) { if (localIce.size >= 128) throw ProtocolFailure(); localIce.add(candidate) }
        else send("media.ice", peerData { put("candidate", candidate) })
    }
    private fun addIce(candidate: JsonElement) {
        if (candidate == JsonNull) return // Native libwebrtc does not require an end-of-candidates marker.
        val c = candidate as? JsonObject ?: throw ProtocolFailure()
        if (c.toString().toByteArray().size > 4096) throw ProtocolFailure()
        if (!seenIce.add(c.toString())) return
        if (seenIce.size > 256) seenIce.remove(seenIce.first())
        val mid = (c["sdpMid"] as? JsonPrimitive)?.content
        val line = (c["sdpMLineIndex"] as? JsonPrimitive)?.intOrNull ?: 0
        if (line !in 0..127 || !pc!!.addIceCandidate(IceCandidate(mid, line, c.string("candidate")))) throw ProtocolFailure()
    }
    private fun syncVideos() {
        val live = RemoteSdpTracks.catalogBindings(remoteTracksByMid, catalog).filterValues { it.kind == "video" }
        (received.keys - live.keys).forEach { received.remove(it)?.feed?.close() }
        val videos = live.mapNotNull { (mid, metadata) ->
            val transceiver = peerTransceivers.singleOrNull { it.mid == mid } ?: return@mapNotNull null
            val receiver = transceiver.receiver
            val track = receiver.track() as? VideoTrack ?: return@mapNotNull null
            val binding = remoteTracksByMid.getValue(mid)
            val existing = received[mid]
            val video = if (existing != null && existing.receiverId == receiver.id() && existing.binding == binding) existing else {
                existing?.feed?.close()
                ReceivedVideo(receiver.id(), binding, VideoFeed(track, checkNotNull(egl).eglBaseContext)).also { received[mid] = it }
            }
            CallVideo(metadata.id, metadata.participantId, metadata.source == "video/screen", video.feed)
        }
        mutable.value = state.value.copy(videos = videos)
    }
    private suspend fun connected() {
        timer?.cancel(); attempts = 0
        mutable.value = state.value.copy(phase = CallPhase.CONNECTED, status = "На связи")
        updatePresence()
        if (statsJob == null) {
            val epoch = generation
            statsJob = scope.launch { while (isActive) { delay(3_000); callback(epoch) { stats(epoch) } } }
        }
    }
    private suspend fun updatePresence() {
        if (connectionId.isBlank()) return
        try { signaling?.presence(connectionId, ++presenceSequence, state.value.microphone, state.value.camera, state.value.sharing) }
        catch (_: Exception) { notice("Не удалось обновить состояние устройств на сервере.") }
    }
    private fun stats(epoch: Long) {
        pc?.getStats { report -> callback(epoch) {
            var receivedBytes = 0L; var sent = 0L; var frames = 0L; var relay = false; var audioBytes = 0L; var videoBytes = 0L
            report.statsMap.values.forEach { stat ->
                val m = stat.members
                fun number(key: String) = (m[key] as? Number)?.toLong() ?: 0
                if (stat.type == "inbound-rtp") {
                    receivedBytes += number("bytesReceived"); frames += number("framesDecoded")
                    if (m["kind"] == "audio") audioBytes += number("bytesReceived")
                    if (m["kind"] == "video") videoBytes += number("bytesReceived")
                }
                if (stat.type == "outbound-rtp") sent += number("bytesSent")
                if (stat.type == "transport") {
                    val pair = report.statsMap[m["selectedCandidatePairId"]]?.members
                    val local = report.statsMap[pair?.get("localCandidateId")]?.members
                    val remote = report.statsMap[pair?.get("remoteCandidateId")]?.members
                    if (local?.get("candidateType") == "relay" || remote?.get("candidateType") == "relay") relay = true
                }
            }
            mutable.value = state.value.copy(diagnostics = state.value.diagnostics.copy(relay = relay, receivedBytes = receivedBytes,
                decodedFrames = frames, sentBytes = sent, receivedAudioBytes = audioBytes, receivedVideoBytes = videoBytes))
        } }
        val sender = slots["video/screen"]?.sender ?: return
        if (screenTrack == null || !screenSessionActive) return
        val session = screenSession
        pc?.getStats(sender) { report -> callback(epoch) {
            if (session != screenSession || !screenSessionActive) return@callback
            val stat = report.statsMap.values.singleOrNull { it.type == "outbound-rtp" && it.members["kind"] == "video" } ?: return@callback
            val frames = (stat.members["framesEncoded"] as? Number)?.toLong() ?: return@callback
            val stream = "${stat.id}:${stat.members["ssrc"]}"
            val timestamp = report.timestampUs
            val previous = screenStatsSample
            val seconds = previous?.let { (timestamp - it.timestampUs) / 1_000_000.0 }
            val fps = if (previous != null && previous.stream == stream && frames >= previous.frames && seconds != null && seconds > 0)
                (frames - previous.frames) / seconds else null
            screenStatsSample = ScreenStatsSample(stream, timestamp, frames)
            mutable.value = state.value.copy(diagnostics = state.value.diagnostics.copy(screenFramesEncoded = frames, screenEncodedFps = fps))
            if (fps != null) android.util.Log.d("MeetSpaceCall", "screen_encoded_fps=" +
                String.format(java.util.Locale.ROOT, "%.2f", fps) + " framesEncoded=$frames")
        } }
    }
    private fun deadline(delayMs: Long, message: String?) {
        timer?.cancel(); val epoch = generation
        timer = scope.launch { delay(delayMs); callback(epoch) { if (message == null) reconnect() else fail(message) } }
    }
    private suspend fun reconnect() {
        if (!wanted) return
        val title = state.value.title; val conference = state.value.conferenceId
        val direct = state.value.directCallId != null
        val microphone = state.value.microphone; val camera = state.value.camera
        closeNetwork(); replaceMic(false); replaceCamera(false); stopScreenInternal()
        if (++attempts > 3) { fail("Связь не восстановилась. Подключитесь к встрече снова."); return }
        mutable.value = state.value.copy(phase = CallPhase.RECONNECTING, title = title, conferenceId = conference,
            microphone = direct && microphone && permission(Manifest.permission.RECORD_AUDIO),
            camera = direct && camera && permission(Manifest.permission.CAMERA),
            status = "Восстанавливаем связь… ($attempts/3)", error = if (direct) "Восстанавливаем личный звонок. Демонстрацию экрана можно включить снова после подключения."
                else "Камера и микрофон выключены. После восстановления их можно включить снова.")
        val epoch = generation
        reconnectJob = scope.launch {
            delay(2000L * attempts)
            callback(epoch) { try { open() } catch (_: Exception) { reconnect() } }
        }
    }
    private fun closeNetwork() {
        generation++
        timer?.cancel(); timer = null; reader?.cancel(); reader = null
        statsJob?.cancel(); statsJob = null; reconnectJob?.cancel(); reconnectJob = null
        credentialTimer?.cancel(); credentialTimer = null
        if (joinRequest.isNotEmpty()) runCatching { send("media.leave", if (peerId.isBlank()) buildJsonObject {} else peerData()) }
        signaling?.close(); signaling = null
        received.values.forEach { it.feed.close() }; received.clear(); remoteTracksByMid = emptyMap()
        peerTransceivers = emptyList(); slots.clear(); pc?.close(); pc?.dispose(); pc = null
        peerId = ""; connectionId = ""; joinRequest = ""; hasRemote = false; hasOffer = false
        remoteIce.clear(); localIce.clear(); seenIce.clear(); gate = NegotiationGate(); catalog = emptyList()
        catalogRevision = -1; policyVersion = -1; presenceSequence = 0
        mutable.value = state.value.copy(videos = emptyList(), participants = emptyList(), diagnostics = CallDiagnostics())
    }
    private fun disposeAll() {
        closeNetwork(); replaceMic(false); replaceCamera(false); stopScreenInternal()
        audio.close()
        factory?.dispose(); factory = null
        audioModule?.release(); audioModule = null
        egl?.release(); egl = null
    }
    private fun fail(message: String) {
        wanted = false
        disposeAll()
        mutable.value = state.value.copy(phase = CallPhase.FAILED, status = "Соединение закрыто", error = message)
    }
}
