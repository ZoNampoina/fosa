# FOSA Mobile LocalTransport — v2

LocalTransport owns discovery, announcement, network formation/connection and cleanup. It never owns Session/Auth/SDP/audio. Android contract: `native/android/app/src/main/java/com/arizona/fosa/mobile/LocalTransport.kt`.

Priority: existing LAN → Android Wi-Fi Direct → explicitly created local hotspot. Legacy Internet rendezvous is optional compatibility, never media relay and never required for local entry.

States: DISCOVERING / FOUND / AUTHENTICATING / CONNECTING_NETWORK / SIGNALING / NEGOTIATING_AUDIO / CONNECTED / RECONNECTING / FAILED.

LAN uses DNS-SD, bounded UDP metadata, local HTTP/HTTPS coordinator and Opus/SRTP peer links. AndroidWifiDirectTransport uses WifiP2pManager, service/peer discovery and group/connection info. Group owner and Session owner are distinct concepts; verify session ID before authentication.

Future adapters: IOSPeerTransport (Bonjour / platform-supported peer networking) and WindowsWifiDirectTransport. They implement their platform capabilities and supply a local coordinator locator. They cannot assume Android WifiP2pManager support. A Web adapter only joins an already reachable local origin; it does not form P2P networks.

Session/auth/signaling are [FOSA LAN/2](../docs/mobile/PROTOCOL.md). Code is a session selector; private clientKey/resumeToken/generation belong to session identity. Never carry SDP/ICE or private member tokens in QR.

No live radio handover or migration of a session owner is claimed in this version. Network changes repair the existing identity; browsers may need to reopen a new local host address. Preserve Bodypack/MR18 transport separately.
