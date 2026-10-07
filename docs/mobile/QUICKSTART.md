# FOSA Mobile — audited LAN-first flow

**Android**
1. Install the APK. Open FOSA → Create Session. Enable microphone.
2. Use the same Wi-Fi on every phone, or enable Android hotspot and join it.
3. Create BAND LIVE. On another Android device: Join Session → enter the private six-digit code. FOSA first probes the hotspot/Wi-Fi gateway on the fixed local FOSA port, then uses mDNS discovery. No Internet, device selection or QR is required.
4. Select User, Group, Leader or All; hold Talk. Release to listen. Use wired headphones to avoid feedback.
5. Panic Mute cuts local listening and transmission. Receive continues in the foreground service while the screen is off. Notification Talk is limited to five seconds; touch Talk again to stop.

**iPhone / iPad / Web fallback**
1. Open https://zonampoina.github.io/fosa/mobile/ once with Internet and cache the app.
2. On the Android host, open MEMBERS → CODE / QR. This temporarily opens Web code pairing; ordinary native sessions stay LAN-only and do not poll the cloud.
3. Web client: Join Session → name/role/code → JOIN WITH CODE. Internet is used only for this short offer/answer + ICE bootstrap; audio and control then stay direct on the LAN.
4. For zero-Internet Web pairing, choose 100% LOCAL · OFFLINE QR. Browsers cannot discover the Android host from six digits alone, so QR is the fully local Web fallback.
5. RECONNECT AUDIO performs a true ICE restart while the direct control channel survives. After a complete WebRTC/network-address loss, re-pair by code or local QR.

**MR18 / PC**
PC : MR18 USB → lancer FOSA SERVER → START LOW LATENCY → lire l’adresse Bodypack affichée.
Téléphone : même LAN → OPEN BODYPACK (accueil ou Settings) → serveur détecté, IP manuelle ou lien QR du PC → CONNECT.
Premier test : écouteurs filaires → monter CH1 → Monitor Gain → maintenir Talk → relâcher → Panic Mute.

Le Bodypack conserve le mix serveur, le gain, le limiteur et le PCM natif. Un timeout temporaire du contrôle ne ferme plus le flux UDP authentifié ; Talk se coupe jusqu’au rétablissement des autorisations. Une erreur de chargement reste visible avec l’adresse modifiable. FOSA Mobile intercom peut fonctionner sans MR18 ni PC.

**Audit LAN-first**
Les tests automatisés couvrent désormais les candidats ICE tardifs Android→Web, le vrai ICE restart, User/Group/Leader/All, mute d’écoute individuel, reprise d’identité sans doublon et réception native en arrière-plan. La version affichée dans Settings est lue depuis le BuildConfig réel au lieu d’être codée en dur.

**First physical test**
Android host + Android client → Talk User → Talk All → wired headphones → unplug router WAN → verify speech continues → screen off/on → stop/rejoin Wi-Fi → Panic Mute. Try the same with PWA only after native path succeeds. A guest network or AP isolation may prevent direct connections. Router/hotspot hardware is not available in the development environment; these tests remain to be run physically.

**Not finished**
Native iOS audio/IPA, Web hosting/discovery, Web guaranteed background audio, host migration, priority Talk, custom native DSP, physical end-to-end latency measurement. No advertised 20 ms result. Eight-member load needs hardware validation. Android download is currently a test-signed APK, not Play Store distribution; uninstall an older incompatible-signature version before updating.
