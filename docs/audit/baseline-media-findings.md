# Media lifecycle baseline — 2026-10-27

This inventory and finding were recorded before the audit media remediation. No live conference, microphone, camera, MediaProjection consent, or server mutation was used to establish it.

## Implemented ownership

- `core:media` is an Android library, min SDK 26 / compile SDK 36, Java/JVM 17, using `io.github.webrtc-sdk:android:144.7559.15`.
- One application-scoped `CallEngine` owns a bounded 256-command coroutine actor and its `MeetSpace-call` executor. The process-lifetime executor is intentional, not an Activity leak; dedicated instrumentation engines explicitly cancel/close it.
- `CallEngine` owns PeerConnectionFactory, EGL, JavaAudioDeviceModule, PeerConnection/transceiver wrappers, local audio/camera/screen tracks and sources, capturers, SurfaceTextureHelpers, signaling, timers and stats jobs.
- `VideoFeed` owns sink attachment only. `VideoContent` owns each SurfaceViewRenderer and detaches/releases it on disposal.
- `ScreenShareService` is a non-exported, non-sticky mediaProjection foreground service. Capture consent is consumed once; capture startup failure uses independent cleanup calls and invalidates projection callbacks.
- `CallAudio` owns audio focus, device callbacks and communication routing. MainActivity pauses camera/microphone on non-configuration `onStop` and leaves on finishing `onDestroy`.

## Confirmed control-flow defect: lifecycle cleanup waits behind network work (P1)

Baseline references:

- `CallEngine.kt:91–98`: one actor invokes each suspending command to completion.
- `CallEngine.kt:138`, `163–171`, `328–351`: authorization, REST conference/membership lookup and signaling startup run inside that actor.
- `CallEngine.kt:602–625`, `668–671`: SDP transactions and presence updates also suspend the same consumer.
- `CallEngine.kt:200–204`, `295–307`: camera/microphone pause and leave are ordinary queued commands; they cannot cancel the current operation.
- `MainActivity.kt:60–65`: Android background/finish events request those queued operations.
- `MeetSpaceApi.kt:39–41`: the normal HTTP client permits a 30-second call timeout; native SDP wrappers permit 10 seconds.

Consequently, an already-owned capturer remains owned until an earlier network/SDP command completes. The same ordering applies to an audio-focus-loss callback, which calls `pauseDevices`. A slow control-plane request can therefore delay privacy-sensitive cleanup and the visible effect of leave/mute. No heap-growth or real-hardware capture duration is inferred from this code inspection.

The same baseline defect affects screen-stop requests: `stopScreen` (`CallEngine.kt:260–263`) and the MediaProjection `onStop` callback (`225–228`) use the ordinary command queue. The foreground-service Stop action and service destruction route through `engine.stopScreen` (`ScreenShareService.kt:18`, `50`). Therefore a user-requested projection stop can wait behind slow presence/SDP work. Android already stops projection itself before its system `onStop` callback; in that case the delayed application cleanup retains the Java/native owners rather than proving continued capture. `screenStopPreemptsSlowPresenceAndReleasesProjectionOwner` supplies an offline logical projection owner and verifies cleanup before the gate is opened.

`CallLifecyclePriorityTest` supplies cancellable, gated conference lookup/presence operations and a fake owned capturer. It requires pause/leave to release ownership before the network gate is opened, then checks that the actor still accepts a subsequent call. The tests use no external backend and never start native capture. Baseline execution and fixed results are recorded by the parent audit runner.

The parent runner executed the original two gated regressions against the immutable baseline app APK: both failed at the expected pre-gate cleanup assertion. Evidence: `.audit/android-audit-20261027/baseline-media-priority.log`.

Required remediation constraints: cancel only the active child operation, retain the actor, serialize urgent cleanup ahead of pending callbacks, invalidate stale queued capture enables, and fail safely after an interrupted SDP transaction rather than continuing it. An idle, established screen share must retain its projection when only camera/microphone pause is requested. Non-cooperative native/vendor calls remain a device-validation limitation.

## Targeted remediation and behavioral limits

The remediation adds a bounded priority lifecycle queue and a cancellable child Job for each normal command, while retaining the application actor and native serialization. A command cancelled before it starts is deferred rather than dropped. Interrupted setup/SDP is fully cleaned up and exposed as an explicit retry state; it does not automatically restore microphone or camera. Direct-call request tokens are scoped to the target call, including requests still waiting in the queue. Stopping an old call does not invalidate a newer call's preview. Normal camera/microphone/retry actions also retain the target call identity.

The offline regressions cover the original slow setup/presence failures, stale queued enables, repeated lifecycle operations, queued direct-call cancellation, preservation of a newer queued call, and preservation of an established screen share when no operation is interrupted. Additional screen-stop regressions cover a blocked control plane, cancellation of queued old consent, and rejection of an old projection callback after the same call has started a newer screen session. Parent verification results determine pass/fail; no successful run is claimed here before it completes.

Fail-safe limitation: if Android backgrounds the app while control-plane setup or an SDP transaction is suspended, the interrupted connection closes and requires explicit retry. Existing personal-call failure UI may require ending/restarting that call. A stable connection with no suspended command keeps its screen projection during device-only pause. Membership leave can still await its bounded 10-second server acknowledgement after all local capture/network/native media resources have already been released; subsequent urgent work can wait for that acknowledgement, but not for active capture cleanup.

## Other observations and limits

- The pinned AAR bytecode confirms `PeerConnection.getTransceivers()` disposes previous wrappers; the application retains one final snapshot and does not call it repeatedly.
- Receiver tracks are owned by PeerConnection/transceiver wrappers; VideoFeed closes sinks before PeerConnection disposal. The app does not retain borrowed VideoFrame objects.
- Screen-share frame rate is adapted at VideoSource and checked at the RTP sender; screen capture dimensions preserve aspect ratio. Existing native tests cover rejected consent/start-stop and a loopback encoder path; this audit has not yet rerun them.
- `RingtoneVolume.close()` depends on the same `allowed()` predicate as boosting. Inserting an external headset during ringing can prevent restoration of an otherwise unchanged temporary ring-volume boost (`RingtoneVolume.kt:33`, `IncomingCallRingtone.kt:29–31`). This is a P2 global-setting restoration issue, not a confirmed memory leak.
- Real multiparty media, Bluetooth hardware, permission revocation during capture, high-refresh projection, network handoff and long-term heap/native memory stabilization require separate, explicitly reported runtime checks.
