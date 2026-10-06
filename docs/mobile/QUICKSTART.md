# FOSA Mobile — Android 0.12.3 / Web 0.12.3

**Android**
1. Install the APK. Open FOSA → Create Session. Enable microphone.
2. Use the same Wi-Fi on every phone, or enable Android hotspot and join it.
3. Create BAND LIVE. On another Android device: Join Session → enter the private six-digit code. FOSA finds the host automatically on the LAN; no device selection or QR is required.
4. Select User, Group, Leader or All; hold Talk. Release to listen. Use wired headphones to avoid feedback.
5. Panic Mute cuts local listening and transmission. Receive continues in the foreground service while the screen is off. Notification Talk is limited to five seconds; touch Talk again to stop.

**iPhone / iPad / Web fallback**
1. Open https://zonampoina.github.io/fosa/mobile/ once with Internet and cache the app. Web 0.12.3 corrige le PTT silencieux avant connexion ICE et le verrouillage autoplay : une interaction dans l’écran TALK déverrouille la sortie audio.
2. Join Session → enter name, role and the host's six-digit code → Join with Code.
3. A short Supabase rendezvous exchanges only the initial WebRTC offer/answer. The host must have Internet for this pairing step.
4. As soon as WebRTC is connected, audio and control remain direct on the local LAN. Internet can disappear without becoming an audio relay.
5. If Internet is unavailable during Web pairing, use the advanced Offline QR fallback. The old double-scan flow is no longer the normal path.

**MR18 / PC**
PC : MR18 USB → lancer FOSA SERVER → START LOW LATENCY → lire l’adresse Bodypack affichée.
Téléphone : même LAN → OPEN BODYPACK (accueil ou Settings) → serveur détecté, IP manuelle ou lien QR du PC → CONNECT.
Premier test : écouteurs filaires → monter CH1 → Monitor Gain → maintenir Talk → relâcher → Panic Mute.

Le Bodypack conserve le mix serveur, le gain, le limiteur et le PCM natif. Un timeout temporaire du contrôle ne ferme plus le flux UDP authentifié ; Talk se coupe jusqu’au rétablissement des autorisations. Une erreur de chargement reste visible avec l’adresse modifiable. FOSA Mobile intercom peut fonctionner sans MR18 ni PC.

**Mise à jour 0.12.3**
Installer l’APK 0.12.3 sur les Android concernés. HOLD TO TALK garde maintenant l’intention d’émettre pendant que le lien audio se négocie : le bouton affiche CONNECTING… et, si le lien reste bloqué, FOSA relance automatiquement la négociation sans demander de relâcher le doigt. Le PTT n’est plus désactivé par un simple retard du contrôle LAN. ICE accepte UDP en priorité et TCP comme secours sur les réseaux/hotspots qui filtrent l’UDP. STATUS affiche l’état ICE et le nombre de candidats locaux/distants pour diagnostiquer un vrai téléphone. Les correctifs Bodypack 0.12.2 restent inclus.

**First physical test**
Android host + Android client → Talk User → Talk All → wired headphones → unplug router WAN → verify speech continues → screen off/on → stop/rejoin Wi-Fi → Panic Mute. Try the same with PWA only after native path succeeds. A guest network or AP isolation may prevent direct connections. Router/hotspot hardware is not available in the development environment; these tests remain to be run physically.

**Not finished**
Native iOS audio/IPA, Web hosting/discovery, Web guaranteed background audio, host migration, priority Talk, custom native DSP, physical end-to-end latency measurement. No advertised 20 ms result. Eight-member load needs hardware validation. Android download is currently a test-signed APK, not Play Store distribution; uninstall an older incompatible-signature version before updating.
