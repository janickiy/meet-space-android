package com.meetspace.core.media

import org.junit.Assert.*
import org.junit.Test

class RemoteSdpTracksTest {
    @Test fun `reconnect replaces original association on the same MID without native streams`() {
        val first = RemoteSdpTracks.prepare(sdp(section("video", "5", "old-peer", "old-track")))
        val next = RemoteSdpTracks.prepare(sdp(section("video", "5", "new-peer", "new-track")))
        assertEquals(RemoteSdpTrack("5", "video", "old-peer", "old-track"), first.tracksByMid["5"])
        assertEquals(RemoteSdpTrack("5", "video", "new-peer", "new-track"), next.tracksByMid["5"])
        assertTrue(first.description.contains("a=msid:- old-track\r\n"))
        assertTrue(next.description.contains("a=msid:- new-track\r\n"))
        assertFalse(next.description.contains("msid:new-peer"))
        assertTrue(next.description.contains("a=ssrc:42 msid:- new-track\r\n"))
        assertEquals(next.description, RemoteSdpTracks.prepare(next.description).description)
    }

    @Test fun `inactive rejected and removed tracks no longer bind retained receivers`() {
        val active = RemoteSdpTracks.prepare(sdp(section("video", "5", "peer", "track")))
        assertEquals(1, active.tracksByMid.size)
        val inactive = RemoteSdpTracks.prepare(sdp(section("video", "5", "peer", "track").replace("a=sendonly", "a=inactive")))
        val rejected = RemoteSdpTracks.prepare(sdp(section("video", "5", "peer", "track").replace("m=video 9", "m=video 0")))
        val removed = RemoteSdpTracks.prepare(sdp("m=video 9 UDP/TLS/RTP/SAVPF 96\r\na=mid:5\r\na=recvonly\r\n"))
        assertTrue(inactive.tracksByMid.isEmpty()); assertTrue(rejected.tracksByMid.isEmpty()); assertTrue(removed.tracksByMid.isEmpty())
        assertTrue(removed.description.contains("a=msid:-\r\n"))
        assertTrue(RemoteSdpTracks.catalogBindings(active.tracksByMid, emptyList()).isEmpty())
    }

    @Test fun `audio camera and screen bind only their original exact authoritative catalog identities`() {
        val prepared = RemoteSdpTracks.prepare(sdp(
            section("audio", "4", "peer", "microphone") + section("video", "5", "peer", "camera") +
                section("video", "6", "peer-screen", "screen") + section("audio", "7", "peer-screen", "screen-audio")))
        val catalog = listOf(
            CatalogTrack("microphone", "peer", "participant", "microphone", "audio"),
            CatalogTrack("camera", "peer", "participant", "camera", "video"),
            CatalogTrack("screen", "peer-screen", "participant", "video/screen", "video"),
            CatalogTrack("screen-audio", "peer-screen", "participant", "audio/screen", "audio"))
        val bindings = RemoteSdpTracks.catalogBindings(prepared.tracksByMid, catalog)
        assertEquals(4, bindings.size)
        assertEquals("camera", bindings["5"]?.source); assertEquals("video/screen", bindings["6"]?.source)
        assertEquals("audio/screen", bindings["7"]?.source)
        assertTrue(RemoteSdpTracks.catalogBindings(prepared.tracksByMid,
            catalog.map { it.copy(streamId = "substituted") }).isEmpty())
        assertTrue(RemoteSdpTracks.catalogBindings(prepared.tracksByMid,
            catalog.map { it.copy(kind = "substituted") }).isEmpty())
        assertTrue(RemoteSdpTracks.catalogBindings(prepared.tracksByMid,
            catalog.map { it.copy(id = "substituted") }).isEmpty())
    }

    @Test fun `new camera catalog cannot reuse the feed association for an old publication`() {
        val old = RemoteSdpTracks.prepare(sdp(section("video", "5", "peer", "old")))
        val fresh = RemoteSdpTracks.prepare(sdp(section("video", "5", "peer", "new")))
        val nextCatalog = listOf(CatalogTrack("new", "peer", "participant", "camera", "video"))
        assertTrue(RemoteSdpTracks.catalogBindings(old.tracksByMid, nextCatalog).isEmpty())
        assertEquals("new", RemoteSdpTracks.catalogBindings(fresh.tracksByMid, nextCatalog)["5"]?.id)
    }

    @Test fun `legacy SSRC only signaling gains media level streamless marker and retains original mapping`() {
        val raw = sdp(section("video", "5", "peer", "track").replace("a=msid:peer track\r\n", ""))
        val prepared = RemoteSdpTracks.prepare(raw)
        assertEquals("peer", prepared.tracksByMid["5"]?.streamId)
        assertTrue(prepared.description.contains("a=msid:- track\r\n"))
        assertTrue(prepared.description.contains("a=ssrc:42 msid:- track\r\n"))
    }

    @Test fun `transport codecs SSRC and line formatting remain byte for byte unchanged`() {
        val raw = sdp(section("video", "5", "peer", "track"))
        val prepared = RemoteSdpTracks.prepare(raw)
        assertEquals(raw.replace("msid:peer track", "msid:- track"), prepared.description)
        val lf = raw.replace("\r\n", "\n")
        assertEquals(lf.replace("msid:peer track", "msid:- track"), RemoteSdpTracks.prepare(lf).description)
    }

    @Test fun `ambiguous stream track and duplicate MID associations fail closed`() {
        rejects { RemoteSdpTracks.prepare(sdp(section("video", "5", "peer", "one") + section("audio", "5", "peer", "two"))) }
        rejects { RemoteSdpTracks.prepare(sdp(section("video", "5", "peer", "one") + "a=msid:other two\r\n")) }
        rejects { RemoteSdpTracks.prepare(sdp(section("video", "5", "peer", "one").replace("a=ssrc:42 msid:peer one", "a=ssrc:42 msid:other one"))) }
        rejects { RemoteSdpTracks.prepare(sdp(section("video", "5", "peer", "one").replace("a=msid:peer one", "a=msid:peer"))) }
    }

    @Test fun `bounded parser rejects malformed and oversized answers without interpreting transport secrets`() {
        rejects { RemoteSdpTracks.prepare(" ".repeat(49_153)) }
        rejects { RemoteSdpTracks.prepare(sdp(section("video", "5", "peer", "one")) + "a=" + "x".repeat(4097)) }
        rejects { RemoteSdpTracks.prepare(sdp(section("video", "5", "peer", "one").replace("a=mid:5", "a=mid:5\ra=inactive"))) }
        rejects { RemoteSdpTracks.prepare(sdp(section("video", "5", "peer", "one")) + "\r\n".repeat(2049)) }
    }

    private fun sdp(media: String) = "v=0\r\no=- 1 2 IN IP4 127.0.0.1\r\ns=-\r\nt=0 0\r\na=group:BUNDLE 4 5 6 7\r\n" + media
    private fun section(kind: String, mid: String, stream: String, track: String) =
        "m=$kind 9 UDP/TLS/RTP/SAVPF 96\r\nc=IN IP4 0.0.0.0\r\na=mid:$mid\r\na=sendonly\r\n" +
            "a=ice-ufrag:fixture\r\na=ice-pwd:fixture-transport-secret\r\na=fingerprint:sha-256 AA:BB\r\n" +
            "a=rtpmap:96 VP8/90000\r\na=msid:$stream $track\r\na=ssrc:42 cname:unchanged\r\na=ssrc:42 msid:$stream $track\r\n"
    private fun rejects(block: () -> Unit) { try { block(); fail("Expected protocol rejection") } catch (_: ProtocolFailure) {} }
}
