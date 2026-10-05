# iOS preparation — NOT A RELEASE
FosaMobile is a SwiftUI library containing the shared visual tokens, an honest preparation screen, and the LAN coordinator HTTP adapter. Add it as a local Swift package to an iOS 16+ Xcode app and merge the Info.plist fragment. No IPA has been built here.

Remaining before native iOS release: embed a maintained WebRTC iOS framework; connect RTCAudioSession with playAndRecord, voiceChat, wired/USB routes and background audio; implement LAN-only ICE filtering and individual sender PTT; Bonjour discovery; QR compressed SDP pairing; lifecycle and physical device tests. The HTTP adapter forbids cellular transport. Background microphone permissions must be started from a visible app.

Current iPhone/iPad option: https://zonampoina.github.io/fosa/mobile/ . Cache the PWA, keep it open, join an Android host by offline QR pairing. This is fallback, with no background guarantee or claimed 20 ms latency.
