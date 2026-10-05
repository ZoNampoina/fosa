# FOSA Mobile — Android 0.11.0 / Web 0.11.1

**Android**
1. Install the APK. Open FOSA → Create Session. Enable microphone.
2. Use the same Wi-Fi on every phone, or enable Android hotspot and join it.
3. Create BAND LIVE. On other phones: Join Session → select it → enter its private code. QR is under Members → Invite.
4. Select User, Group, Leader or All; hold Talk. Release to listen. Use wired headphones to avoid feedback.
5. Panic Mute cuts local listening and transmission. Receive continues in the foreground service while the screen is off. Notification Talk is limited to five seconds; touch Talk again to stop.

**iPhone / iPad / Web fallback**
1. Open https://zonampoina.github.io/fosa/mobile/ once with Internet. Check Offline Ready; install to Home Screen if desired.
2. On the Android host: Create Session → Members → Invite. Find the private **six-digit session code** there. On the Web page: Join Session, enter your name and this code, then Create Web Invitation. Allow microphone to talk (listening remains possible without microphone).
3. The Android host scans this QR with its camera and opens FOSA. It then opens Members → Web Pair to display the answer.
4. Scan that answer from the PWA's Scan Host Answer button, or paste the copied answer. Keep the original PWA page open during pairing and audio.
5. Subsequent audio/control use the LAN only. Keep Web visible; background/lock-screen playback is not guaranteed. After a reload, pair again.

**If Create Web Invitation does not proceed**
- A blank or incomplete session code cannot create an invitation. The page explains where to find it: Android host → Members → Invite. Members → Web Pair displays the **answer after** the host scans the Web invitation, not the initial session code.
- If QR preparation fails, the page keeps the name, instrument and code, displays the actual error, and lets you retry. Edit Code / Retry replaces an existing invitation and releases its previous microphone and connection.
- Hosting a session from the browser is not implemented. The Web client can join an Android-hosted session; it does not replace the host phone.

**MR18 / PC**
Open Bodypack from the new Android home. The existing PC server, personal mix, gain, limiter and native PCM path are retained. FOSA Mobile does not need the MR18 or a PC.

**First physical test**
Android host + Android client → Talk User → Talk All → wired headphones → unplug router WAN → verify speech continues → screen off/on → stop/rejoin Wi-Fi → Panic Mute. Try the same with PWA only after native path succeeds. A guest network or AP isolation may prevent direct connections. Router/hotspot hardware is not available in the development environment; these tests remain to be run physically.

**Not finished**
Native iOS audio/IPA, Web hosting/discovery, Web guaranteed background audio, host migration, priority Talk, custom native DSP, physical end-to-end latency measurement. No advertised 20 ms result. Eight-member load needs hardware validation. Android download is currently a test-signed APK, not Play Store distribution; uninstall an older incompatible-signature version before updating.
