# FOSA LAN protocol 1
This version is separate from the MR18/PC Bodypack PCM engine. A phone coordinates a Mobile session; Opus audio flows directly between peers, never through its HTTP coordinator or the Internet.

## Discovery and control
Bonjour/mDNS `_fosa-mobile._tcp.` announces the session name, LAN IPv4, port and public session UUID. Private RFC1918 IPv4 only; no cellular/Internet dependency. Clients may use Ethernet. A hotspot is usable if its firmware permits local client traffic. Multicast can be blocked; native QR includes address, session, private code. Manual address remains under advanced connection.

`POST /lan/info`, `/lan/join`, `/lan/poll`, `/lan/signal`, `/lan/group`, `/lan/leave`; UTF-8 JSON, 32 KiB maximum, bounded queues/timeouts. Join requires a six-digit code; eight failures/minute per remote are refused. Each member receives an unguessable token, ID, session name, leader ID, protocol. Poll, signal, group and leave authenticate `Authorization: Bearer <token>`. Roster never contains tokens. Maximum eight members. A member is online for seven seconds and a Talk indicator expires after 1.5 seconds without refresh. Inactive memberships expire after 60 seconds when a new client joins.

Poll fields: `after` (last acknowledged signal seq), `talk`, `target`, `level` (real dBFS or null). Response: session, sessionName, leader, members, signals. Signals: `seq/from/type/data`; types offer, answer, ice, reset. Peers acknowledge only after applying each signal. The smaller member UUID creates offers to avoid glare. The host alone can assign groups. Targets: all, leader, user:UUID, group:NAME. A separate sender AudioTrack per peer prevents private PTT from being broadcast.

## Audio
WebRTC host candidates only: private IPv4 or local mDNS; relay and server-reflexive candidates discarded. Empty ICE server list: no STUN, TURN or cloud signaling. Opus mono, native requested 48 kHz, hardware may resample. DTLS/SRTP authenticates and encrypts each stream. Full mesh is simple and direct for small groups; CPU/airtime grow with members. No host audio relay or host failover in this release.

RTT, inbound jitter and packet loss come from RTCStats. They do not measure mouth-to-ear latency. End-to-end latency remains null/UNKNOWN. No synthetic VU. Native mic RMS is derived from AudioRecord samples. Web mic RMS is from the actual Web Audio graph. APM supplies echo/noise processing and automatic voice gain; custom native gate/EQ/compressor controls are unavailable. Web adds a real 100 Hz HPF and compressor. There is no claimed IEM safety certification.

## Offline browser pairing
Cached HTTPS PWA cannot freely access a private HTTP phone coordinator. Pairing exchanges compressed SDP locally, without a signaling server: deflate (zlib), base64url, bounded JSON 32 KiB. Browser offer QR: `fosa://mobile-pair?data=...` containing type, sdp, name, role, code. Android host joins the authenticated member and answers with `https://zonampoina.github.io/fosa/mobile/#answer=...` containing type, sdp, profile, host UUID. Scan the answer from the same still-open PWA page, or paste it. The initial WebRTC data channel is the browser's encrypted coordinator RPC (`requestId,path,body` → `requestId,data/error`); the host binds RPC to this member's token, not a caller-supplied identity. Other audio links remain direct. QR holds a private credential: do not share screenshots publicly.

PWA auto-discovery and hosting are unavailable. Brief outages can recover ICE, but reload or changed IP requires QR pairing again. Native reconnect restores the existing token and groups while the host survives; restarting the host creates a new session. Explicit Leave removes stored credentials. PWA requires the visible browser to remain running.

## Security boundary
The coordinator is HTTP on the private LAN; join codes and native tokens are not protected against a hostile LAN observer. Use a trusted dedicated network. Audio and browser RPC are encrypted; this release is not suitable for untrusted public networks. TLS/pinning, per-device admission and robust host failover remain P2. No login cloud is used.
