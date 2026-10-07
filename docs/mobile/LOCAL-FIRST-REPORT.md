# FOSA Mobile 0.13.0 — LOCAL-FIRST PAIRING

État de livraison et résultats de vérification consignés en fin de ce document. Aucun test automatisé ne remplace les scénarios sur deux téléphones et un réseau réellement privé de WAN.

## Audit de départ

Base : `172b8c0b3c2dace71d3dbf519ed203111aa437f5` sur `main`, version réelle **0.12.7**. Le README affichait encore 0.12.6. L’APK `android-latest` annonçait 0.12.7 ; le workflow Android construisait un APK debug et exécutait des tests instrumentés sur API 35.

Les sources examinées : README, docs/mobile, Android MobileActivity/MobileService/LanSession/LanHttp/LanAddress/RtcMobile, anciens adaptateurs Nearby, Web app/core/QR/service worker, rendez-vous Supabase et SQL, contrats iOS, tests navigateur et Android, workflows Android/Pages/audio, bridge et Bodypack.

| Élément | Avant | Après |
|---|---|---|
| Android par code | Passerelle 48765 et mDNS ; réseau préexistant obligatoire | Coordinateur conservé ; Connection Manager recherche LAN puis P2P |
| Découverte | NSD avec session ID | Métadonnées DNS-SD, requête/réponse UDP et DNS-SD Wi-Fi Direct natif |
| Web par code | Offre/réponse et candidats via rendez-vous HTTPS Internet | HTML/JS et authentification fournis par l’Android ; signalisation HTTP locale puis RPC chiffré direct |
| QR Web hors ligne | SDP/ICE compressés ; aller-retour QR | Locator de session ou invitation appareil courte ; aucun SDP/ICE dans le nouveau parcours |
| Ajout depuis hôte | Import d’une grosse invitation, puis retour de réponse | Caméra intégrée, ADD DEVICE, invitation à nonce et durée limitée |
| Transport réseau | Code réparti dans MobileService/UI ; ancien Nearby non raccordé | Contrat LocalTransport ; LanTransport et AndroidWifiDirectTransport, hotspot local |
| Identité | clientKey, resume token, generation | Mécanismes conservés ; challenge local à usage unique pour le nouveau protocole |
| Audio | Opus, liste ICE servers vide, candidats locaux | Moteur conservé, contrôle du type de paire sélectionnée et diagnostics supplémentaires |
| Web local Android | Aucun serveur d’assets | Assets embarqués dans l’APK, HTTP local et HTTPS avec CA propre à l’installation |

Supabase transportait uniquement les offres/réponses du bootstrap Web, jamais les médias. Le parcours local 0.13.0 n'en dépend plus. Pour conserver le code-only existant sur GitHub Pages, l'hôte peut activer **ENABLE INTERNET DISCOVERY · 2 MIN** dans Advanced Diagnostics ; cette porte d'entrée conserve l'ancien rendez-vous, puis le contrôle et l'audio directs. Elle n'est jamais appelée par l'entrée Web locale. En cas d'indisponibilité Internet, Pages renvoie vers WEB ACCESS, sans afficher de SDP/QR de réponse. Les méthodes de compatibilité 0.12 restent dans le code ; seul le test de compatibilité active l'ancien parcours QR avec une variable réservée au test. Les pages livrées n'activent pas cette variable. Aucun schéma ni fonction Supabase n'est modifié.

## Architecture et parcours

Le gestionnaire choisit le réseau et le coordinateur. LanSession gère authentification, membres, permissions, queues de signalisation et générations. RtcMobile/WebLanClient gèrent l’audio. L’interface affiche les résultats de cette séparation.

États : DISCOVERING, FOUND, AUTHENTICATING, CONNECTING_NETWORK, SIGNALING, NEGOTIATING_AUDIO, CONNECTED, RECONNECTING, FAILED. L’état CONNECTED exige un lien audio ; une adhésion HTTP seule ne le garantit pas.

**LAN.** Un réseau déjà présent est essayé en premier. L’hôte annonce son service `_fosa-mobile._tcp.` ; un beacon UDP répond à des recherches courtes sur 48764. Le client essaie également la passerelle sur TCP 48765. Une réponse doit correspondre au code et, pour un QR, au session ID. NSD n’est pas une recherche DNS Internet. Le broadcast UDP offre une seconde possibilité lorsque le multicast est peu fiable.

**Wi-Fi Direct.** Android utilise réellement WifiP2pManager : annonce DNS-SD `_fosa._tcp`, requête de services, recherche de pairs, connect/createGroup, Group Info et Connection Info, suivi des broadcasts et Group Owner. Sans LAN, l’hôte peut créer un groupe autonome. Le client trouve le code annoncé et demande sa connexion au groupe depuis l’application. L’adresse du Group Owner est vérifiée contre le vrai coordinateur de session ; si nécessaire, la découverte LAN est relancée dans le groupe. Les erreurs permission/Wi-Fi/P2P occupé/formation du groupe sont remontées. Un dialogue de consentement Android peut rester imposé par le système ou le constructeur.

Le routage HTTP utilise le réseau qui possède une route spécifique vers le pair, sans choisir la route Internet par défaut. Le groupe P2P peut utiliser son interface native lorsque ConnectivityManager n'expose pas un Network exploitable. Le moniteur réseau WebRTC active explicitement ses interfaces Wi-Fi Direct lorsque le matériel et la permission le permettent. Les adaptateurs cellulaires/VPN sont exclus du moteur audio et des locators locaux. Une perte du groupe côté hôte déclenche sa recréation avec la même session ; cette reprise P2P reste à éprouver matériellement.

**Hotspot.** CREATE LOCAL FOSA NETWORK utilise startLocalOnlyHotspot et conserve sa réservation dans le service. Cette option existe avant la création de session, ainsi que dans Members. SSID et mot de passe Wi-Fi sont affichés. Les invités rejoignent ce Wi-Fi, puis saisissent le code FOSA. Ce réseau n’a pas besoin d’Internet. Changer de réseau avec plusieurs membres actifs est refusé afin de ne pas couper leur session.

L'adresse du hotspot est identifiée par la nouvelle interface créée, afin de ne pas publier l'ancienne adresse Wi-Fi sur un appareil capable de STA/AP simultanés. Une seconde pression réutilise la réservation déjà ouverte.

**Code.** Les six chiffres sélectionnent la session. Le nouveau client demande un nonce local, valide code/session/nonce, puis reçoit un token aléatoire privé. Un même clientKey/resumeToken réutilise l’ancien membre et augmente sa génération ; des noms identiques ne fusionnent pas les appareils. Le protocole 1 reste accepté pour les clients existants.

**QR session.** Exemple de format : `fosa://join?v=2&s=<UUID>&c=482731&h=192.168.49.1&p=48765`. Un scan Android déclenche la recherche/jonction ; aucun scan de réponse. Le QR comporte un locator, jamais SDP, ICE, certificat ou token de membre.

**Scan Device.** Android invité : DISPLAY PAIRING QR annonce un appareil temporaire et ouvre un petit endpoint local. Hôte : ADD DEVICE ouvre la caméra ; découverte de cet appareil, vérification du nonce et transmission du locator de session, puis l’invité rejoint automatiquement. Invitation valable 90 s et consommable une fois. Pour un Web déjà ouvert depuis l’hôte local, le coordinateur enregistre l’invitation, l’hôte la valide par scan et le Web termine l’entrée avec un join token à usage unique. Un navigateur ne crée pas de serveur appareil ni de réseau Wi-Fi Direct.

**Audio.** Opus est transmis directement entre pairs sur le LAN/P2P, chiffré par DTLS/SRTP. Aucune adresse STUN/TURN n’est ajoutée. Les candidats relay/srflx et les adresses publiques ne sont pas échangés. Une paire relay/srflx détectée dans les stats est refusée. Le coordinateur transporte les commandes et la signalisation, aucun paquet audio. Talk All/User/Group/Leader, écoute sélective, Panic Mute, volume et service natif restent disponibles. Les permissions de parole/écoute sont désormais aussi affichées dans Members.

La présence de l'hôte est entretenue indépendamment des recherches d'appareils : un ajout infructueux ne doit pas expirer ses liens audio existants. Le test instrumenté maintient un Talk et vérifie l'arrivée de paquets Opus pendant une recherche de 14 secondes.

## Web local et limite incontournable de HTTP

L’APK embarque index.html, app.js, core.js, connection.js, QR, CSS, icônes, manifest et service worker. Aucun CDN/GitHub n’est nécessaire pour charger ces fichiers depuis l’hôte.

Ports : HTTP 48765 préféré, HTTPS 48766. WEB ACCESS affiche l’URL HTTPS courte et son QR, COPY LINK, la page HTTP `/trust` et l’empreinte de la CA. Le certificat racine est propre à l’installation ; sa clé privée reste dans le répertoire privé de l’application. La CA demeure identique après changement d’adresse, et un certificat serveur avec les adresses locales est renouvelé.

**Une page `http://192.168.x.x` ne peut pas activer getUserMedia ni installer le service worker dans un navigateur standard.** Elle peut charger l’interface et les instructions. Pour un accès audio/PWA complet, il faut ouvrir le HTTPS local et faire confiance à la CA de cet hôte une fois, avec vérification de son empreinte. Un simple contournement de l’avertissement navigateur ne garantit pas toutes les APIs. Cela ne requiert pas Internet, mais c’est une préparation réelle supplémentaire pour le Web ; le parcours Android natif n’exige pas cette installation.

GitHub Pages reste une porte d’entrée et un cache Web compatible. Pour rejoindre localement par code sans Internet, ouvrir l’URL fournie par l’hôte. Un navigateur ouvert sur Pages ne peut pas deviner une IP privée depuis six chiffres, annoncer un service mDNS, ni piloter WifiP2pManager. Un scan de session depuis le Web dirige vers la WebApp HTTPS de l’hôte ; son réseau doit déjà être rejoint.

Après redirection du QR vers cet hôte, code/session/identité sont repris automatiquement pour terminer l'entrée, sans second scan ni ressaisie. L'invité doit auparavant avoir rejoint le réseau et approuvé son HTTPS.

## Sécurité et limites matérielles

Le code est annoncé pour identifier la session : **il n’est ni un secret cryptographique ni une protection contre quelqu’un qui est déjà sur le réseau**. Les identités, tokens privés, nonces à usage unique, expirations et générations limitent les reprises involontaires et rejeux. Les tokens de membres restent absents de la liste publique et des QR.

La signalisation native compatible reste HTTP sur un LAN de confiance : elle n’est pas une défense contre un observateur ou attaquant actif sur un Wi-Fi hostile. La confiance TLS du Web exige une vérification initiale. Ne pas annoncer une sécurité d’admission cryptographique forte avec seulement six chiffres.

Wi-Fi Direct dépend des capacités radio et du firmware. LAN/P2P/hotspot simultanés ne sont pas garantis ; certains Android exigent les services de localisation sur les versions anciennes. Permission Nearby Wi-Fi Devices à partir d’Android 13, localisation fine jusqu’à API 32. Un Wi-Fi avec isolation des clients peut empêcher tous les flux locaux. Mesh limité à huit membres ; consommation CPU/radio croissante. Bluetooth casque peut ajouter de la latence.

iOS/Windows natifs ne sont pas livrés. Leur séparation réseau/session/auth/signal/audio est documentée dans le contrat LocalTransport ; iOS devra utiliser ses APIs propres et Windows son adaptateur natif. Le PC et l’iPhone peuvent utiliser le Web une fois sur le réseau et son HTTPS approuvé. L’audio Web en arrière-plan n’est pas garanti. Après changement d’IP de l’hôte, un Web ouvert sur une ancienne URL doit rouvrir WEB ACCESS ; une coupure temporaire sur la même adresse récupère son identité.

Le Bodypack MR18, le bridge, Low Latency et l’ancienne application sont conservés. Aucun pilote ASIO ni moteur PCM n’est modifié.

L'APK est toujours un build **debug**, comme la distribution précédente. La CI conserve désormais sa clé debug pour les prochaines mises à jour. La clé des anciennes distributions n'était pas conservée par ce workflow : la compatibilité de signature avec une installation 0.12.x n'est pas garantie. Si Android refuse la mise à jour, une réinstallation peut être nécessaire et efface l'identité locale. Ne pas la faire pendant une session ; aucune migration matérielle n'a été testée.

## Fichiers de cette refonte

Liste complète : **35 fichiers ajoutés ou modifiés** par rapport à la base auditée. Les noms ci-dessous sont les chemins exacts dans le dépôt.

```text
.github/workflows/android-apk.yml
.github/workflows/verify-audio.yml
.gitignore
README.md
docs/mobile/LOCAL-FIRST-REPORT.md
docs/mobile/PROTOCOL.md
docs/mobile/QUICKSTART.md
docs/mobile/VALIDATION.md
mobile/app.js
mobile/connection.js
mobile/core.js
mobile/sw.js
mobile/tests/browser.cjs
mobile/tests/local-first.cjs
native/android/app/build.gradle.kts
native/android/app/src/androidTest/java/com/arizona/fosa/BodypackUiTest.kt
native/android/app/src/androidTest/java/com/arizona/fosa/mobile/LocalPairingTest.kt
native/android/app/src/androidTest/java/com/arizona/fosa/mobile/MobileUiTest.kt
native/android/app/src/main/AndroidManifest.xml
native/android/app/src/main/java/com/arizona/fosa/mobile/AndroidWifiDirectTransport.kt
native/android/app/src/main/java/com/arizona/fosa/mobile/DeviceInvitation.kt
native/android/app/src/main/java/com/arizona/fosa/mobile/FosaConnectionManager.kt
native/android/app/src/main/java/com/arizona/fosa/mobile/LanAddress.kt
native/android/app/src/main/java/com/arizona/fosa/mobile/LanHttp.kt
native/android/app/src/main/java/com/arizona/fosa/mobile/LanSession.kt
native/android/app/src/main/java/com/arizona/fosa/mobile/LanTls.kt
native/android/app/src/main/java/com/arizona/fosa/mobile/LanTransport.kt
native/android/app/src/main/java/com/arizona/fosa/mobile/LocalHotspot.kt
native/android/app/src/main/java/com/arizona/fosa/mobile/LocalTransport.kt
native/android/app/src/main/java/com/arizona/fosa/mobile/MobileActivity.kt
native/android/app/src/main/java/com/arizona/fosa/mobile/MobileService.kt
native/android/app/src/main/java/com/arizona/fosa/mobile/PairingQr.kt
native/android/app/src/main/java/com/arizona/fosa/mobile/RtcMobile.kt
native/ios/README.md
native/transport-contract.md
```

Les nouveaux adaptateurs réseau et locators sont séparés du coordinateur et du moteur audio. MobileActivity/MobileService intègrent les parcours ; les tests UI existants ne changent que pour la permission Nearby, les contrôles devenus défilants et le maintien du Talk pendant une découverte. Les assets et la publication restent dans les workflows existants.

## Validation et publication

Version : **0.13.0**, Android versionCode **20**. Code de référence soumis aux tests : `fe4bdc3032b868bfa80ef9cbccc747117b738241`. [PR de livraison #6](https://github.com/ZoNampoina/fosa/pull/6). Le SHA du commit réellement distribué est indiqué dans les notes de release et la fiche des workflows après fusion ; ne pas confondre ce SHA avec celui de la branche testée.

### TESTÉ AUTOMATIQUEMENT

[Verify FOSA Audio — révision de référence](https://github.com/ZoNampoina/fosa/actions/runs/37659606739) : **succès**.

- Bridge Python : 41 tests exécutés sur Linux et Windows ; 2 ignorés par la suite existante. Les tests PCM/worklet couvrent stéréo, réordonnancement, wrap, perte, backlog, purge et conversion de fréquence.
- Chromium/WebKit : régressions des parcours existants, véritable Opus décodé, routage Talk/écoute et révocation, PTT tenu, reprise d'identité, redémarrage ICE, cache PWA hors ligne. Le test historique utilise volontairement l'ancien protocole pour vérifier sa compatibilité ; il n'est pas le nouveau parcours normal.
- Scénario local-first Chromium : vrais HTTP challenge/join et WebRTC, audio Opus décodé dans les deux sens, entrée automatique depuis une URL QR courte, même membre après reprise, génération augmentée, redémarrage ICE, contrôle/audio continuant après blocage du coordinateur HTTP. **Zéro requête vers un domaine externe dans ce scénario.** Coordinateur en fixture Node ; ce test n'est pas une liaison Android hôte ↔ navigateur.
- Préparation iOS : package Swift compilé sur macOS ; aucune application iOS native ni IPA livrée.

Les tests média Chromium ne peuvent pas aboutir dans l'espace réseau local de développement limité à loopback. Les tests exécutés en CI disposent d'interfaces privées ; aucun filtre de production n'a été affaibli pour obtenir un succès.

### TESTÉ SUR ÉMULATEUR

[Build FOSA Android APK — révision de référence](https://github.com/ZoNampoina/fosa/actions/runs/37659607180) : **succès, 10 tests fonctionnels sur Android API 35 x86_64**, puis **1 test de capture visuelle réussi**. Les 10 tests fonctionnels bloquent la publication en cas d'échec ; la capture reste une vérification visuelle non bloquante.

Les essais instrumentés utilisent le vrai coordinateur Kotlin, des sockets HTTP/HTTPS réelles, le moteur WebRTC natif et AudioTrack. Ils couvrent challenge consommable une fois, rejet de rejeu, reprise sans doublon, permissions et confidentialité des tokens, QR court décodé par ZXing, les neuf assets embarqués accessibles par HTTPS avec validation de la CA, service/écrans Mobile et Bodypack. Le test Mobile ajoute la recherche de 14 secondes pendant le Talk. Un émulateur n'éprouve pas une formation de groupe entre deux radios Wi-Fi Direct.

### Taille et lecture des QR

Comparaison des QR historiques décodés depuis les captures de test, puis de locators courts générés/décodés automatiquement :

| QR | Caractères | Matrice du locator d'exemple | Décodage automatique |
|---|---:|---|---|
| Ancienne invitation SDP/ICE JOHN | 1 399 | Capture historique | OK |
| Ancienne invitation SDP/ICE NIA | 1 388 | Capture historique | OK |
| Nouvelle session | 86 | Version 5, 37 × 37 modules | OK |
| Nouvel appareil | 68 | Version 3, 29 × 29 modules | OK |
| URL Web seule | 27 | Version 2, 25 × 25 modules | OK |

Le locator session de cet exemple réduit le texte d'environ **94 %** ; la taille dépend de l'adresse/du UUID. Le parseur natif borne l'entrée à 150 caractères. Ces lectures automatiques ne remplacent pas une caméra Redmi/Samsung à luminosité moyenne.

### Statistiques audio observées en CI

La capture local-first de la révision de référence observe **Opus, DTLS connected, paire host/host en UDP**, RTT arrondi à 0 ms, jitter 2 ms, perte 0 %, 21 paquets RX et 22 TX au moment de la capture. Les adresses de candidats sont masquées par Chromium ; les types et ports sont vérifiés. Il s'agit de deux contextes navigateur sur un même runner et d'un bref échantillon. **Ce n'est ni la latence micro → casque, ni un benchmark radio, ni une preuve de non-régression sur scène.** Aucun TURN, serveur audio cloud ou serveur ICE public n'est configuré ; les tests rejettent les candidats média non locaux. La mesure physique avant/après sur le même matériel reste requise.

### Liens de distribution

- [APK versionné 0.13.0](https://github.com/ZoNampoina/fosa/releases/download/v0.13.0/FOSA-Android.apk)
- [APK stable](https://github.com/ZoNampoina/fosa/releases/download/android-latest/FOSA-Android.apk)
- [Notes de release](https://github.com/ZoNampoina/fosa/releases/tag/v0.13.0)
- [Web GitHub Pages](https://zonampoina.github.io/fosa/mobile/), facultatif ; le Web local est fourni par l'APK.

Les workflows de `main` vérifient de nouveau le code avant de publier l'APK et GitHub Pages. Le workflow Android configure explicitement le fichier de signature mis en cache, plutôt que de supposer l'emplacement de la clé générée par Gradle. La version ne doit pas être déclarée validée sur appareils physiques avant exécution de la checklist suivante.

## Checklist physique obligatoire — résultat actuellement NON EFFECTUÉ

| Scénario | Procédure | Preuve attendue |
|---|---|---|
| A | Deux Android, même routeur, WAN débranché avant création ; hôte CREATE SESSION, invité saisit six chiffres | CONNECTED + SRTP CONNECTED ; voix décodée dans les deux sens |
| B | Deux Android, aucun routeur/hotspot commun ; Wi-Fi activé ; créer et rejoindre par code | Groupe P2P formé depuis FOSA, GO/adresse vus dans Advanced, voix bidirectionnelle |
| C | Android hôte et PC sur hotspot sans WAN ; charger `/trust`, vérifier empreinte, approuver CA et ouvrir HTTPS local | Assets issus de l’Android, entrée par code, micro et audio dans les deux sens ; HTTP seul ne suffit pas |
| D | Invité affiche DISPLAY PAIRING QR, hôte ADD DEVICE et un seul scan | Un membre apparaît et audio connecté, aucun scan retour |
| E | Hôte SHOW QR, invité SCAN QR | Même résultat après un scan ; QR ancien SDP absent du parcours |
| F | Session native et Web déjà active ; débrancher WAN/éteindre données cellulaires | Audio, membres, permissions et Talk restent opérationnels |
| G | DNS, GitHub, Supabase bloqués dès avant création ; utiliser natif et Web local déjà approuvé | Session et audio complets sans tentative de cloud |
| H | Couper puis rétablir Wi-Fi sur invité ; même session | Même ID, generation augmentée si rejoin, un seul JOHN ; permissions/groupes conservés |
| I | Redmi, Samsung, tablette, écran PC ; QR à luminosité moyenne | Lecture pratique des trois QR courts, tailles relevées |
| J | Talk All/User/Group/Leader, écoute ciblée, Panic, permissions, écran Android éteint | Aucune parole non autorisée ; sortie/audio persistants et mute effectif |

Relever pour A/B/C : interface, IP locale/distante, paire ICE sélectionnée de type host/host, protocole, DTLS, codec, paquets RX/TX, RTT, jitter et perte avant/après coupure WAN. Ces statistiques ne sont pas une mesure microphone → casque. La latence physique doit être mesurée avec une impulsion et un enregistrement des deux chemins ; aucune valeur garantie n’est annoncée.
