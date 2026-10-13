package com.meetspace.core.media

import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import org.webrtc.*

internal suspend fun PeerConnection.offer(): SessionDescription = withTimeout(10_000) {
    suspendCancellableCoroutine { c -> createOffer(object : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription) { if (c.isActive) c.resume(sdp) }
        override fun onCreateFailure(error: String) { if (c.isActive) c.resumeWithException(ProtocolFailure()) }
        override fun onSetSuccess() = Unit
        override fun onSetFailure(error: String) = Unit
    }, MediaConstraints()) }
}
internal suspend fun PeerConnection.description(sdp: SessionDescription, local: Boolean) = withTimeout(10_000) {
    suspendCancellableCoroutine<Unit> { c ->
        val observer = object : SdpObserver {
            override fun onSetSuccess() { if (c.isActive) c.resume(Unit) }
            override fun onSetFailure(error: String) { if (c.isActive) c.resumeWithException(ProtocolFailure()) }
            override fun onCreateSuccess(sdp: SessionDescription) = Unit
            override fun onCreateFailure(error: String) = Unit
        }
        if (local) setLocalDescription(observer, sdp) else setRemoteDescription(observer, sdp)
    }
}
