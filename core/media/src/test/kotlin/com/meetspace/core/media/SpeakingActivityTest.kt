package com.meetspace.core.media

import org.junit.Assert.*
import org.junit.Test
import org.webrtc.RTCStats
import org.webrtc.RTCStatsReport

class SpeakingActivityTest {
    private val detector = SpeakingActivity()
    private val binding = ReceivedMicrophone("remote", "remote-track")
    private val microphones = mapOf("4" to binding)
    private val allowed = setOf("self", "remote")

    @Test fun independentMicrophonesCanSpeakTogetherAndSilenceExpires() {
        assertEquals(emptySet<String>(), update(0, local(0, 0.0, 0.0), remote(0, 0.0, 0.0)))
        assertEquals(allowed, update(200, local(200, .002, .2), remote(200, .002, .2)))
        assertEquals(allowed, update(400, local(400, .002, .4), remote(400, .002, .4)))
        assertEquals(emptySet<String>(), update(650, local(650, .002, .65), remote(650, .002, .65)))
    }

    @Test fun quietBackgroundDoesNotLightUpAndReleaseThresholdAvoidsWordFlicker() {
        update(0, local(0, 0.0, 0.0))
        assertTrue(update(200, local(200, .0000008, .2)).isEmpty()) // RMS .002
        assertEquals(setOf("self"), update(400, local(400, .0001808, .4))) // RMS .03
        assertEquals(setOf("self"), update(600, local(600, .0002258, .6))) // RMS .015
        assertEquals(setOf("self"), detector.expire(1049, allowed))
        assertTrue(detector.expire(1050, allowed).isEmpty())
    }

    @Test fun quietSpeechUsesTheSameOnsetForLocalAndRemoteMicrophones() {
        update(0, local(0, 0.0, 0.0), remote(0, 0.0, 0.0))
        assertEquals(allowed, update(200, local(200, .00008, .2), remote(200, .00008, .2))) // RMS .02
        assertEquals(.02f, detector.audioLevels(200, allowed).getValue("self"), .00001f)
        assertEquals(allowed, update(400, local(400, .0001448, .4))) // RMS .018
        assertEquals(.018f, detector.audioLevels(400, allowed).getValue("self"), .00001f)
        assertEquals(allowed, update(600, local(600, .0001448, .6)))
        assertTrue(detector.audioLevels(600, allowed).isEmpty())
        assertTrue(detector.expire(850, allowed).isEmpty())
    }

    @Test fun frozenCountersAndLoudCachedAudioLevelCannotKeepAHighlightAlive() {
        update(0, remote(0, 0.0, 0.0))
        assertEquals(setOf("remote"), update(200, remote(200, .002, .2)))
        assertTrue(update(700, remote(700, .002, .2, mapOf("audioLevel" to .95))).isEmpty())
        // Concealment counters may advance without any newly received RTP.
        assertTrue(update(900, remote(900, .02, .9, mapOf("packetsReceived" to 70))).isEmpty())
    }

    @Test fun duplicateAndOutOfOrderReportsDoNotRefreshTheHold() {
        update(0, local(0, 0.0, 0.0))
        update(200, local(200, .002, .2))
        update(500, local(200, .02, 1.0))
        assertTrue(update(700, local(100, .04, 2.0)).isEmpty())
    }

    @Test fun fallbackNeedsFreshRtpAndRejectsInvalidLevels() {
        fun fallback(at: Long, packets: Int, level: Double) = remote(at, null, null,
            mapOf("audioLevel" to level, "packetsReceived" to packets))
        assertEquals(setOf("remote"), update(0, fallback(0, 1, .1)))
        assertTrue(update(500, fallback(500, 1, .8)).isEmpty())
        assertTrue(update(700, fallback(700, 2, Double.NaN)).isEmpty())
        assertTrue(update(900, fallback(900, 3, 50.0)).isEmpty())
        assertEquals(setOf("remote"), update(1100, fallback(1100, 4, .05)))
    }

    @Test fun missingBindingUnknownTracksAndConflictingMidNeverIdentifyASpeaker() {
        val samples = arrayOf(
            remote(0, null, null, mapOf("audioLevel" to .8, "trackIdentifier" to "another-track")),
            remote(0, null, null, mapOf("audioLevel" to .8, "mid" to "missing")),
            local(0, null, null, mapOf("audioLevel" to .8, "trackIdentifier" to "old-local")))
        assertTrue(update(0, *samples).isEmpty())
        assertTrue(detector.update(report(remote(200, null, null, mapOf("audioLevel" to .8))), 200,
            "self", "local-track", emptyMap(), allowed).isEmpty())
    }

    @Test fun reusedReceiverOnlyAcceptsSamplesFromItsNewAdvertisedSource() {
        val rebound = mapOf("4" to ReceivedMicrophone("remote", "native-receiver-id", setOf(73L)))
        fun sample(at: Long, source: Long) = detector.update(report(remote(at, null, null, mapOf(
            "trackIdentifier" to "native-receiver-id", "ssrc" to source, "audioLevel" to .12))),
            at, "self", "local-track", rebound, allowed)

        assertTrue(sample(200, 42).isEmpty())
        assertEquals(setOf("remote"), sample(400, 73))
        assertEquals(.12f, detector.audioLevels(400, allowed).getValue("remote"), .00001f)
    }

    @Test fun trackIdentifierFallbackRequiresAnUnambiguousCatalogBinding() {
        val stat = remote(0, null, null, mapOf("mid" to null, "audioLevel" to .1, "packetsReceived" to 1))
        assertEquals(setOf("remote"), update(0, stat))
        detector.clear()
        assertTrue(detector.update(report(stat), 0, "self", "local-track",
            microphones + ("5" to binding.copy(participantId = "other")), allowed + "other").isEmpty())
    }

    @Test fun invalidOrResetEnergyCountersNeedANewBaseline() {
        update(0, local(0, .01, 1.0))
        assertTrue(update(200, local(200, .0, .1)).isEmpty())
        assertEquals(setOf("self"), update(400, local(400, .002, .3)))
        assertTrue(update(900, local(900, Double.NaN, .9, mapOf("audioLevel" to .8))).isEmpty())
    }

    @Test fun muteDisconnectAndNewCallClearAllSpeakerHistory() {
        update(0, local(0, 0.0, 0.0), remote(0, 0.0, 0.0))
        update(200, local(200, .002, .2), remote(200, .002, .2))
        assertEquals(setOf("remote"), detector.expire(210, setOf("remote")))
        detector.forget("remote")
        assertTrue(detector.expire(220, allowed).isEmpty())
        detector.clear()
        assertTrue(update(300, local(300, .01, .4), remote(300, .01, .4)).isEmpty())
    }

    @Test fun statePresentationRejectsMutedOfflineAndReconnectingSpeakers() {
        val state = CallState(phase = CallPhase.CONNECTED, participantId = "self", microphone = true,
            participants = listOf(CallParticipant("remote", "Участник", true, true, false)), activeSpeakerIds = allowed)
        assertTrue(state.isSpeaking("self")); assertTrue(state.isSpeaking("remote"))
        assertFalse(state.copy(microphone = false).isSpeaking("self"))
        assertFalse(state.copy(microphoneBlocked = true).isSpeaking("self"))
        assertFalse(state.copy(participants = state.participants.map { it.copy(online = false) }).isSpeaking("remote"))
        assertFalse(state.copy(participants = state.participants.map { it.copy(microphone = false) }).isSpeaking("remote"))
        assertFalse(state.copy(phase = CallPhase.RECONNECTING).isSpeaking("self"))
        assertFalse(state.copy(phase = CallPhase.RECONNECTING).isSpeaking("remote"))
    }

    @Test fun measuredVolumeChangesDuringContinuousSpeechAndDropsBeforeSpeakerHoldExpires() {
        update(0, local(0, 0.0, 0.0), remote(0, 0.0, 0.0))
        update(200, local(200, .00032, .2), remote(200, .002, .2)) // RMS .04 / .10
        assertEquals(.04f, detector.audioLevels(200, allowed).getValue("self"), .00001f)
        assertEquals(.10f, detector.audioLevels(200, allowed).getValue("remote"), .00001f)
        update(400, local(400, .00832, .4)) // RMS .20, no remote track anymore.
        assertEquals(mapOf("self" to .20f), detector.audioLevels(400, allowed))
        assertEquals(setOf("self", "remote"), update(500, local(500, .00832, .5)))
        assertTrue(detector.audioLevels(500, allowed).isEmpty()) // Silence, despite held tile highlight.
    }

    @Test fun cachedReportsDoNotRefreshVolumeAndMuteOrNewTrackClearsIt() {
        val stat = local(0, null, null, mapOf("audioLevel" to .1))
        update(0, stat)
        update(400, stat) // Cached native sample: same timestamp.
        assertEquals(.1f, detector.audioLevels(449, allowed).getValue("self"), .00001f)
        assertTrue(detector.audioLevels(450, allowed).isEmpty())
        update(600, local(600, null, null, mapOf("audioLevel" to .2)))
        assertTrue(detector.audioLevels(610, setOf("remote")).isEmpty())
        update(800, local(800, null, null, mapOf("audioLevel" to .3)))
        detector.forget("self")
        assertTrue(detector.audioLevels(800, allowed).isEmpty())
        update(1000, local(1000, null, null, mapOf("audioLevel" to .4)))
        detector.clear()
        assertTrue(detector.audioLevels(1000, allowed).isEmpty())
        update(1200, local(1200, null, null, mapOf("audioLevel" to .5)))
        assertTrue(update(1300, local(1300, null, null, mapOf("trackIdentifier" to "old-track", "audioLevel" to .8))).isNotEmpty())
        assertTrue(detector.audioLevels(1300, allowed).isEmpty())
    }

    @Test fun stateMeterUsesCurrentLevelsIndependentlyOfSpeakerHoldAndRejectsInvalidMutedMedia() {
        val state = CallState(phase = CallPhase.CONNECTED, participantId = "self", microphone = true,
            participants = listOf(CallParticipant("remote", "Участник", true, true, false)), activeSpeakerIds = allowed,
            microphoneLevels = mapOf("self" to .2f, "remote" to .1f))
        assertEquals(.2f, state.microphoneLevel("self"), 0f)
        assertEquals(.1f, state.microphoneLevel("remote"), 0f)
        assertEquals(0f, state.copy(microphone = false).microphoneLevel("self"), 0f)
        assertEquals(0f, state.copy(microphoneBlocked = true).microphoneLevel("self"), 0f)
        assertEquals(0f, state.copy(phase = CallPhase.RECONNECTING).microphoneLevel("self"), 0f)
        assertEquals(.2f, state.copy(activeSpeakerIds = emptySet()).microphoneLevel("self"), 0f)
        assertEquals(0f, state.copy(participants = emptyList()).microphoneLevel("remote"), 0f)
        for (bad in listOf(Float.NaN, Float.POSITIVE_INFINITY, -.1f))
            assertEquals(0f, state.copy(microphoneLevels = mapOf("self" to bad)).microphoneLevel("self"), 0f)
    }

    @Test fun negotiatedMicrophoneRemainsVisibleWhenPresenceIsLateOrMissing() {
        val remote = CallParticipant("remote", "Участник", true, false, true)
        val state = CallState(phase = CallPhase.CONNECTED, participantId = "self", microphone = false,
            participants = listOf(remote), receivedMicrophoneIds = setOf("remote"),
            activeSpeakerIds = setOf("remote"), microphoneLevels = mapOf("remote" to .02f))
        assertTrue(state.microphoneEnabled("remote"))
        assertTrue(state.canSpeak("remote"))
        assertTrue(state.isSpeaking("remote"))
        assertEquals(.02f, state.microphoneLevel("remote"), 0f)
        // Local mute is never overridden by a remote publication or a stale measured level.
        assertFalse(state.copy(receivedMicrophoneIds = setOf("self", "remote")).canSpeak("self"))
        // Removing the negotiated microphone on mute removes its indicator immediately.
        val muted = state.copy(receivedMicrophoneIds = emptySet())
        assertFalse(muted.microphoneEnabled("remote"))
        assertFalse(muted.isSpeaking("remote"))
        assertEquals(0f, muted.microphoneLevel("remote"), 0f)
        assertFalse(state.copy(participants = listOf(remote.copy(online = false))).canSpeak("remote"))
        assertFalse(state.copy(participants = listOf(remote.copy(microphoneBlocked = true))).canSpeak("remote"))
        assertFalse(state.copy(phase = CallPhase.RECONNECTING).canSpeak("remote"))
        assertFalse(state.copy(participants = emptyList()).canSpeak("remote"))
    }

    @Test fun quietReceivedLevelDoesNotWaitForActiveSpeakerSelection() {
        val state = CallState(phase = CallPhase.CONNECTED, participantId = "self",
            participants = listOf(CallParticipant("remote", "Участник", true, false, true)),
            receivedMicrophoneIds = setOf("remote"), microphoneLevels = mapOf("remote" to .014f))
        assertFalse(state.isSpeaking("remote"))
        assertEquals(.014f, state.microphoneLevel("remote"), 0f)
    }

    @Test fun decodedRemoteMeterIsIndependentOfPlaybackStatsAndExpiresOnSilence() {
        assertEquals(setOf("remote"), detector.updateRemoteLevels(mapOf("remote" to .02f), 32, allowed))
        // Listener output volume is zero: post-gain stats cannot erase pre-gain PCM activity.
        detector.update(report(remote(200, 0.0, .2)), 200, "self", "local-track", emptyMap(), allowed)
        assertEquals(.02f, detector.audioLevels(200, allowed).getValue("remote"), 0f)
        detector.updateRemoteLevels(mapOf("remote" to .2f), 224, allowed)
        assertEquals(.2f, detector.audioLevels(224, allowed).getValue("remote"), 0f)
        detector.updateRemoteLevels(mapOf("remote" to 0f), 256, allowed)
        assertTrue(detector.audioLevels(256, allowed).isEmpty())
        assertEquals(setOf("remote"), detector.expire(673, allowed))
        assertTrue(detector.expire(674, allowed).isEmpty())
        detector.updateRemoteLevels(mapOf("remote" to .2f), 700, allowed)
        detector.forget("remote")
        assertTrue(detector.audioLevels(700, allowed).isEmpty())
        assertTrue(detector.expire(700, allowed).isEmpty())
    }

    @Test fun decodedRemoteMetersCannotIdentifyUnknownMutedOrInvalidSources() {
        assertTrue(detector.updateRemoteLevels(mapOf("remote" to .2f), 0, setOf("self")).isEmpty())
        assertTrue(detector.updateRemoteLevels(mapOf("remote" to Float.NaN), 32, allowed).isEmpty())
        assertTrue(detector.updateRemoteLevels(mapOf("remote" to -.2f), 64, allowed).isEmpty())
        detector.updateRemoteLevels(mapOf("remote" to .2f), 100, allowed)
        detector.clear()
        assertTrue(detector.audioLevels(100, allowed).isEmpty())
    }

    @Test fun explicitMuteOverridesOldPublicationUntilUnmuteOrNewPublication() {
        val microphone = CatalogTrack("old-source", "peer", "remote", "microphone", "audio")
        val state = CallState(phase = CallPhase.CONNECTED, participantId = "self",
            participants = listOf(CallParticipant("remote", "Участник", true, false, true)),
            receivedMicrophoneIds = setOf("remote"), remoteMutedMicrophoneIds = setOf("remote"),
            activeSpeakerIds = setOf("remote"), microphoneLevels = mapOf("remote" to .5f))
        assertFalse(state.microphoneEnabled("remote"))
        assertEquals(0f, state.microphoneLevel("remote"), 0f)
        val repeatedCatalog = CallProtocol.newlyPublishedMicrophones(listOf(microphone), listOf(microphone))
        assertTrue(repeatedCatalog.isEmpty())
        assertFalse(state.copy(remoteMutedMicrophoneIds = state.remoteMutedMicrophoneIds - repeatedCatalog).canSpeak("remote"))
        val screen = microphone.copy(id = "screen", streamId = "peer-screen", source = "audio/screen")
        assertTrue(CallProtocol.newlyPublishedMicrophones(listOf(microphone), listOf(microphone, screen)).isEmpty())
        val replaced = CallProtocol.newlyPublishedMicrophones(listOf(microphone), listOf(microphone.copy(id = "new-source")))
        assertEquals(setOf("remote"), replaced)
        assertTrue(state.copy(remoteMutedMicrophoneIds = state.remoteMutedMicrophoneIds - replaced).canSpeak("remote"))
        assertTrue(state.copy(remoteMutedMicrophoneIds = emptySet()).canSpeak("remote"))
        assertTrue(CallProtocol.newlyPublishedMicrophones(listOf(microphone), emptyList()).isEmpty())
    }

    private fun update(now: Long, vararg stats: RTCStats): Set<String> =
        detector.update(report(*stats), now, "self", "local-track", microphones, allowed)
    private fun report(vararg stats: RTCStats) = RTCStatsReport(0, stats.associateBy { it.id })
    private fun local(at: Long, energy: Double?, duration: Double?, extra: Map<String, Any?> = emptyMap()) =
        sample("local", "media-source", at, energy, duration, mapOf("trackIdentifier" to "local-track") + extra)
    private fun remote(at: Long, energy: Double?, duration: Double?, extra: Map<String, Any?> = emptyMap()) =
        sample("remote", "inbound-rtp", at, energy, duration,
            mapOf("mid" to "4", "trackIdentifier" to "remote-track", "packetsReceived" to at / 10, "ssrc" to 42L) + extra)
    private fun sample(id: String, type: String, at: Long, energy: Double?, duration: Double?, extra: Map<String, Any?>): RTCStats {
        val members = mutableMapOf<String, Any>("kind" to "audio")
        energy?.let { members["totalAudioEnergy"] = it }; duration?.let { members["totalSamplesDuration"] = it }
        extra.forEach { (k, v) -> if (v != null) members[k] = v else members.remove(k) }
        return RTCStats(at * 1000, type, id, members)
    }
}
