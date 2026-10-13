package com.meetspace.core.media

internal data class RemoteSdpTrack(val mid: String, val kind: String, val streamId: String, val trackId: String)
internal data class PreparedRemoteAnswer(val description: String, val tracksByMid: Map<String, RemoteSdpTrack>)

/**
 * M144's legacy JNI MediaStream observer can abort when SFU stream identities change.
 * Unified Plan receives streamless tracks using RFC 8830's '-' association. Preserve the
 * original MID/track/catalog identity separately; never rewrite transport or RTP details.
 */
internal object RemoteSdpTracks {
    private val token = Regex("[!#$%&'*+\\-.0-9A-Z^_`a-z{|}~]{1,64}")
    private val midToken = Regex("[!#$%&'*+\\-.0-9A-Z^_`a-z{|}~]{1,16}")
    private val ssrcMsid = Regex("^(a=ssrc:[0-9]{1,10} msid:)([^ ]+)(?: ([^ ]+))?$")
    private val directions = setOf("a=sendrecv", "a=sendonly", "a=recvonly", "a=inactive")

    fun prepare(sdp: String): PreparedRemoteAnswer {
        if (sdp.toByteArray(Charsets.UTF_8).size > 49_152 || sdp.isEmpty()) throw ProtocolFailure()
        val newline = if (sdp.contains("\r\n")) "\r\n" else "\n"
        val lines = sdp.split(newline).toMutableList()
        if (lines.size > 2048 || lines.any { it.length > 4096 || it.any { c -> c < ' ' } }) throw ProtocolFailure()
        val starts = lines.indices.filter { lines[it].startsWith("m=") }
        if (starts.isEmpty() || starts.size > 128) throw ProtocolFailure()
        val sessionDirections = lines.take(starts.first()).filter { it in directions }
        if (sessionDirections.size > 1) throw ProtocolFailure()
        val inheritedDirection = sessionDirections.singleOrNull() ?: "a=sendrecv"
        val bindings = linkedMapOf<String, RemoteSdpTrack>()
        val mids = mutableSetOf<String>()
        // Work backwards so an SSRC-only media section can gain its explicit streamless msid.
        starts.indices.reversed().forEach { section ->
            val start = starts[section]
            val end = starts.getOrNull(section + 1) ?: lines.size
            val header = lines[start].removePrefix("m=").split(' ')
            if (header.size < 3) throw ProtocolFailure()
            val kind = header[0]
            val sectionLines = lines.subList(start + 1, end)
            val midLines = sectionLines.filter { it.startsWith("a=mid:") }
            val mid = midLines.singleOrNull()?.removePrefix("a=mid:") ?: throw ProtocolFailure()
            if (!midToken.matches(mid) || !mids.add(mid)) throw ProtocolFailure()
            if (kind !in setOf("audio", "video")) return@forEach
            val port = header[1].substringBefore('/').toIntOrNull() ?: throw ProtocolFailure()
            if (port !in 0..65535) throw ProtocolFailure()
            val sectionDirections = sectionLines.filter { it in directions }
            if (sectionDirections.size > 1) throw ProtocolFailure()
            val direction = sectionDirections.singleOrNull() ?: inheritedDirection
            var association: Pair<String, String?>? = null
            var mediaMsid = false
            fun association(stream: String, track: String?) {
                if (!token.matches(stream) || (track != null && !token.matches(track)) || (stream != "-" && track == null)) throw ProtocolFailure()
                val next = stream to track
                if (association != null && association != next) throw ProtocolFailure()
                association = next
            }
            for (index in start + 1 until end) {
                val line = lines[index]
                if (line.startsWith("a=msid:")) {
                    val fields = line.removePrefix("a=msid:").split(' ')
                    if (fields.size !in 1..2) throw ProtocolFailure()
                    association(fields[0], fields.getOrNull(1))
                    mediaMsid = true
                    lines[index] = "a=msid:-" + (fields.getOrNull(1)?.let { " $it" } ?: "")
                } else if (line.startsWith("a=ssrc:") && line.contains(" msid:")) {
                    val match = ssrcMsid.matchEntire(line) ?: throw ProtocolFailure()
                    val track = match.groupValues[3].takeIf { it.isNotEmpty() }
                    association(match.groupValues[2], track)
                    lines[index] = match.groupValues[1] + "-" + (track?.let { " $it" } ?: "")
                }
            }
            val original = association
            if (!mediaMsid) {
                // Without a media-level msid, native Unified Plan fabricates a default MediaStream.
                lines.add(start + 1, "a=msid:-" + (original?.second?.let { " $it" } ?: ""))
            }
            if (original != null && original.first != "-" && port != 0 && direction in setOf("a=sendrecv", "a=sendonly")) {
                bindings[mid] = RemoteSdpTrack(mid, kind, original.first, checkNotNull(original.second))
            }
        }
        val description = lines.joinToString(newline)
        if (description.toByteArray(Charsets.UTF_8).size > 49_152) throw ProtocolFailure()
        return PreparedRemoteAnswer(description, bindings.toMap())
    }

    /** Catalog remains authoritative, including changed identities on a reused native receiver. */
    fun catalogBindings(tracksByMid: Map<String, RemoteSdpTrack>, catalog: List<CatalogTrack>): Map<String, CatalogTrack> =
        tracksByMid.mapNotNull { (mid, track) ->
            catalog.singleOrNull { it.id == track.trackId && it.streamId == track.streamId && it.kind == track.kind }?.let { mid to it }
        }.toMap()
}
