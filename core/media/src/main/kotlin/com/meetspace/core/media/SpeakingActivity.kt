package com.meetspace.core.media

import org.webrtc.RTCStatsReport
import kotlin.math.sqrt

/** Audio activity only: no second recorder, PCM ownership, audio gain or server protocol changes. */
internal class SpeakingActivity {
    private data class Previous(val participant: String, val timestamp: Double, val energy: Double?, val duration: Double?, val packets: Double?)
    private val previous = mutableMapOf<String, Previous>()
    private val voicedAt = mutableMapOf<String, Long>()

    fun update(
        report: RTCStatsReport, nowMs: Long, localParticipant: String, localTrack: String?,
        remoteMicrophones: Map<String, CatalogTrack>, allowed: Set<String>,
    ): Set<String> {
        val seen = mutableSetOf<String>()
        for (stat in report.statsMap.values) {
            val m = stat.members
            if (m["kind"] != "audio") continue
            val local = stat.type == "media-source"
            val participant = if (local) {
                if (localTrack == null || m["trackIdentifier"] != localTrack) continue
                localParticipant
            } else if (stat.type == "inbound-rtp") {
                val mid = m["mid"] as? String
                val track = m["trackIdentifier"] as? String
                val binding = if (mid != null) remoteMicrophones[mid]
                    else remoteMicrophones.values.singleOrNull { it.id == track }
                if (binding == null || binding.source != "microphone" || binding.kind != "audio" ||
                    track != null && binding.id != track) continue
                binding.participantId
            } else continue
            if (participant !in allowed) continue
            fun number(key: String): Double? = (m[key] as? Number)?.toDouble()?.takeIf { it.isFinite() && it >= 0 }
            val key = "${stat.id}:${m["ssrc"]}:$participant:${m["trackIdentifier"]}"
            seen += key
            val old = previous[key]
            val timestamp = stat.timestampUs
            if (!timestamp.isFinite() || old != null && timestamp <= old.timestamp) continue
            val energy = number("totalAudioEnergy")
            val duration = number("totalSamplesDuration")
            val packets = number("packetsReceived")
            previous[key] = Previous(participant, timestamp, energy, duration, packets)
            // Interval RMS avoids flicker from a single 10ms audioLevel sample and never
            // reuses a stuck audioLevel after RTP stops. W3C webrtc-stats §8.5 / §8.12.
            val level = if (!local && old?.packets != null && packets != null && packets <= old.packets) 0.0
            else if ("totalAudioEnergy" in m || "totalSamplesDuration" in m) {
                if (energy != null && duration != null && old?.energy != null && old.duration != null && duration > old.duration && energy >= old.energy)
                    sqrt((energy - old.energy) / (duration - old.duration)).coerceAtMost(1.0)
                else 0.0 // Baseline, reset counters or no new samples: no evidence of speech.
            } else {
                val fresh = local || packets != null && packets > (old?.packets ?: 0.0)
                if (fresh) number("audioLevel")?.takeIf { it <= 1.0 } ?: 0.0 else 0.0
            }
            val threshold = if (participant in voicedAt && nowMs - voicedAt.getValue(participant) < HOLD_MS) .012 else .025
            if (level >= threshold) voicedAt[participant] = nowMs
        }
        previous.keys.retainAll(seen)
        return expire(nowMs, allowed)
    }

    fun expire(nowMs: Long, allowed: Set<String>): Set<String> {
        voicedAt.entries.removeAll { (id, time) -> id !in allowed || nowMs < time || nowMs - time >= HOLD_MS }
        previous.entries.removeAll { it.value.participant !in allowed }
        return voicedAt.keys.toSet()
    }

    fun forget(participant: String) {
        voicedAt.remove(participant)
        previous.entries.removeAll { it.value.participant == participant }
    }
    fun clear() { previous.clear(); voicedAt.clear() }

    companion object {
        const val POLL_MS = 200L
        private const val HOLD_MS = 450L
    }
}
