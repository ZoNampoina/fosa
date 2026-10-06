# FOSA Mobile — Android / Web 0.12.0

**Android**
1. Install the APK. Open FOSA → Create Session. Enable microphone.
2. Use the same Wi-Fi on every phone, or enable Android hotspot and join it.
3. Create BAND LIVE. On another Android device: Join Session → enter the private six-digit code. FOSA finds the host automatically on the LAN; no device selection or QR is required.
4. Select User, Group, Leader or All; hold Talk. Release to listen. Use wired headphones to avoid feedback.
5. Panic Mute cuts local listening and transmission. Receive continues in the foreground service while the screen is off. Notification Talk is limited to five seconds; touch Talk again to stop.

**iPhone / iPad / Web fallback**
1. Open https://zonampoina.github.io/fosa/mobile/ once with Internet and cache the app.
2. Join Session → enter name, role and the host's six-digit code → Join with Code.
3. A short Supabase rendezvous exchanges only the initial WebRTC offer/answer. The host must have Internet for this pairing step.
4. As soon as WebRTC is connected, audio and control remain direct on the local LAN. Internet can disappear without becoming an audio relay.
5. If Internet is unavailable during Web pairing, use the advanced Offline QR fallback. The old double-scan flow is no longer the normal path.

**MR18 / PC**
Open Bodypack from the new Android home. The existing PC server, personal mix, gain, limiter and native PCM path are retained. FOSA Mobile does not need the MR18 or a PC.

**First physical test**
Android host + Android client → Talk User → Talk All → wired headphones → unplug router WAN → verify speech continues → screen off/on → stop/rejoin Wi-Fi → Panic Mute. Try the same with PWA only after native path succeeds. A guest network or AP isolation may prevent direct connections. Router/hotspot hardware is not available in the development environment; these tests remain to be run physically.

**Not finished**
Native iOS audio/IPA, Web hosting/discovery, Web guaranteed background audio, host migration, priority Talk, custom native DSP, physical end-to-end latency measurement. No advertised 20 ms result. Eight-member load needs hardware validation. Android download is currently a test-signed APK, not Play Store distribution; uninstall an older incompatible-signature version before updating.
