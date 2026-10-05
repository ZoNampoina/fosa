# Validation scope

The tests exercise actual software transports and rendering. They do not certify physical audio latency or a particular phone, MR18, headset, hotspot firmware or access point.

- Android instrumentation: two native WebRTC engines establish direct LAN ICE/Opus; actual sender tracks enforce User/Group PTT, release and Panic Mute; metrics are obtained from RTCStats; peer reset establishes a new connection.
- Native coordinator: incorrect code/token rejection, leader-only groups, UTF-8 HTTP bodies, public roster privacy and stale Talk expiry.
- Android UI: actual host foreground service, Create/Join/Talk/Members/Status/Settings/Offline, hold release, continued native SRTP reception and actual AudioTrack activity while the activity is stopped and landscape screenshots; the actual Android platform splash is retained only by an instrumentation callback for its capture. No fabricated VU or physical latency.
- Browser: three actual Chromium WebRTC peers, test coordinator implementing the same message schema, real injected reference oscillator decoded on receiving streams. User target leaves the other receiver silent; All reaches both. HTTP is blocked after connection to verify audio and coordinator RPC remain peer-to-peer. Panic, responsive layouts and offline reload are checked.
- WebKit: cached app shell and UI after the local HTTP server is stopped. The Web Inspector offline/routing switches intercept navigation before its worker, so the test removes the actual server instead. This is not an iPhone hardware audio test.
- SwiftUI preparation: library compiles; no iOS audio engine/IPA.
- Existing Bodypack checks retained: Python Windows/Linux capture/mix/device/limiter/control tests; PCM/Opus browser decoding and talkback; native UDP AudioTrack/background/panic test.

The browser test coordinator is a fixture, not a production Web host. Cross-stack Android-to-iPhone interoperability still requires physical devices. The protocol and wire negotiation are implemented, but that hardware test is not reported as successful.

Before Live: dedicated AP or hotspot with client isolation disabled, two Android phones with wired earphones; User/All speech, router WAN removed, screen locked, Wi-Fi reconnect, Panic Mute. Then pair a cached iPhone PWA. Measure mouth-to-ear latency physically. Keep physical phone/headset volume low for initial calibration.
