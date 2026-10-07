# FOSA LAN protocol 1
This version is separate from the MR18/PC Bodypack PCM engine. A phone coordinates a Mobile session; Opus audio flows directly between peers, never through its HTTP coordinator or the Internet.

## Discovery and control
Native Android stays LAN-only by default. The host prefers local TCP port `48765`; a joining Android first probes the Wi-Fi/hotspot default gateway on that port, then also uses Bonjour/mDNS `_fosa-mobile._tcp.` discovery. This makes hotspot pairing less dependent on multicast while retaining mDNS on normal LANs. Private RFC1918 IPv4 only; no cellular/Internet dependency. Native Android joins by six-digit code only. Manual address and QR remain fallbacks.

`POST /lan/info`, `/lan/join`, `/lan/poll`, `/lan/signal`, `/lan/group`, `/lan/leave`; UTF-8 JSON, 32 KiB maximum, bounded queues/timeouts. Join requires a six-digit code; eight failures/minute per remote are refused. Each member receives an unguessable token, ID, session name, leader ID, protocol. Poll, signal, group and leave authenticate `Authorization: Bearer <token>`. Roster never contains tokens. Maximum eight members. A member is online for seven seconds and a Talk indicator expires after 1.5 seconds without refresh. Inactive memberships leave active slots after 60 seconds when a new client joins; up to 64 retired identities remain resumable until the host session ends. Pairing reservations last 90 seconds.

Poll fields: `after` (last acknowledged signal seq), `talk`, `target`, `level` (real dBFS or null). Response: session, sessionName, leader, members, signals. Signals: `seq/from/generation/type/data`; types offer, answer, ice, reset. Peers acknowledge only after applying each signal. The smaller member UUID creates offers to avoid glare. The host alone can assign groups. Targets: all, leader, user:UUID, group:NAME. A separate sender AudioTrack per peer prevents private PTT from being broadcast.

## Reconnection identity (0.12.1)
Join may include a private `resumeToken` from the previous ticket and/or an unguessable `clientKey` persisted before the initial request. A valid session code and matching credential restore the same member ID, token and group. Repeated joins never identify devices by name, instrument or IP. An explicit leave revokes that identity. The roster exposes neither credential.

Every accepted rejoin increments the public member `generation`, resets Talk and clears stale SDP/ICE queues. Native and Web peers replace links whose generation changed. Manual browser offers replace the host link before applying their SDP. Signals from older generations are ignored. Android saves the identity per session code; Web saves it per browser installation and code. Clearing app/site data deliberately loses that identity. Applying the change requires updating the Android host as well as clients.

## Audio
WebRTC host candidates only: private IPv4, private/link-local IPv6 or local mDNS; UDP is preferred by ICE and TCP host candidates remain available as LAN fallback. Relay and server-reflexive candidates are discarded. Empty ICE server list: no STUN, TURN or cloud signaling. Opus mono, native requested 48 kHz, hardware may resample. DTLS/SRTP authenticates and encrypts each stream. Full mesh is simple and direct for small groups; CPU/airtime grow with members. No host audio relay or host failover in this release.

RTT, inbound jitter and packet loss come from RTCStats. They do not measure mouth-to-ear latency. End-to-end latency remains null/UNKNOWN. No synthetic VU. Native mic RMS is derived from AudioRecord samples. Web mic RMS is from the actual Web Audio graph. APM supplies echo/noise processing and automatic voice gain; custom native gate/EQ/compressor controls are unavailable. Web adds a real 100 Hz HPF and compressor. There is no claimed IEM safety certification.

## Browser code rendezvous
A cached HTTPS PWA cannot directly enumerate Android mDNS services or freely call the private HTTP coordinator. FOSA therefore uses a short-lived HTTPS rendezvous only for browser pairing.

The Android host does not poll the cloud during an ordinary native session. Opening MEMBERS → CODE / QR temporarily enables Web code pairing. During that window the host registers a hash of its six-digit code with a random host secret; the browser submits its compressed LAN-only SDP offer and the host posts the answer plus newly gathered LAN ICE candidates until the direct link opens. Requests expire quickly and are rate-limited. The rendezvous carries no audio and no ongoing FOSA control traffic.

After the answer is applied, the browser's encrypted WebRTC data channel becomes the coordinator RPC and Opus/SRTP audio remains peer-to-peer on the LAN. Loss of Internet after pairing does not route audio through Supabase. A normal Web repair performs an ICE restart while that data channel still exists; after a complete link loss or network-address change, the Web fallback may require re-pairing because browsers cannot rediscover the private coordinator by code alone.

### Offline browser fallback
When Internet is unavailable, the QR offer/answer exchange provides a fully local Web pairing path. Because a browser cannot enumerate Android mDNS services or derive a private host from a six-digit code, zero-Internet + code-only pairing is guaranteed only for native clients.

## Security boundary
The coordinator is HTTP on the private LAN; join codes and native tokens are not protected against a hostile LAN observer. Use a trusted dedicated network. Audio and browser RPC are encrypted; this release is not suitable for untrusted public networks. TLS/pinning, per-device admission and robust host failover remain P2. No login cloud is used.
