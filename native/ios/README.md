# iOS preparation — NOT A RELEASE
FosaMobile is a SwiftUI library containing the shared visual tokens, an honest preparation screen, and the LAN coordinator HTTP adapter. Add it as a local Swift package to an iOS 16+ Xcode app and merge the Info.plist fragment. No IPA has been built here.

Remaining before native iOS release: embed a maintained WebRTC iOS framework; connect RTCAudioSession with playAndRecord, voiceChat, wired/USB routes and background audio; implement LAN-only ICE filtering and individual sender PTT; Bonjour discovery; short session locators and the LocalTransport contract; lifecycle and physical device tests. The HTTP adapter forbids cellular transport. Background microphone permissions must be started from a visible app.

Current iPhone/iPad option: Android Host → WEB ACCESS; GitHub Pages remains https://zonampoina.github.io/fosa/mobile/ . Join the host’s local Wi-Fi/hotspot, open its embedded HTTPS WebApp, approve that host’s certificate once and join with the session code. Keep the page open. This is fallback, with no background guarantee or claimed 20 ms latency.
