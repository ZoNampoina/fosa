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

Supabase transportait uniquement les offres/réponses du bootstrap Web, jamais les médias. La 0.13.0 retire ce bootstrap du parcours normal. Les méthodes de compatibilité 0.12 restent dans le code, sans bouton de gros QR dans Join/Members. Le vieux test de compatibilité active son interface avec une variable réservée au test ; les pages livrées n’activent pas cette variable. Les fonctions Supabase ne sont pas supprimées et aucun schéma cloud n’est modifié.

## Architecture et parcours

Le gestionnaire choisit le réseau et le coordinateur. LanSession gère authentification, membres, permissions, queues de signalisation et générations. RtcMobile/WebLanClient gèrent l’audio. L’interface affiche les résultats de cette séparation.

États : DISCOVERING, FOUND, AUTHENTICATING, CONNECTING_NETWORK, SIGNALING, NEGOTIATING_AUDIO, CONNECTED, RECONNECTING, FAILED. L’état CONNECTED exige un lien audio ; une adhésion HTTP seule ne le garantit pas.

**LAN.** Un réseau déjà présent est essayé en premier. L’hôte annonce son service `_fosa-mobile._tcp.` ; un beacon UDP répond à des recherches courtes sur 48764. Le client essaie également la passerelle sur TCP 48765. Une réponse doit correspondre au code et, pour un QR, au session ID. NSD n’est pas une recherche DNS Internet. Le broadcast UDP offre une seconde possibilité lorsque le multicast est peu fiable.

**Wi-Fi Direct.** Android utilise réellement WifiP2pManager : annonce DNS-SD `_fosa._tcp`, requête de services, recherche de pairs, connect/createGroup, Group Info et Connection Info, suivi des broadcasts et Group Owner. Sans LAN, l’hôte peut créer un groupe autonome. Le client trouve le code annoncé et demande sa connexion au groupe depuis l’application. L’adresse du Group Owner est vérifiée contre le vrai coordinateur de session ; si nécessaire, la découverte LAN est relancée dans le groupe. Les erreurs permission/Wi-Fi/P2P occupé/formation du groupe sont remontées. Un dialogue de consentement Android peut rester imposé par le système ou le constructeur.

**Hotspot.** CREATE LOCAL FOSA NETWORK utilise startLocalOnlyHotspot et conserve sa réservation dans le service. Cette option existe avant la création de session, ainsi que dans Members. SSID et mot de passe Wi-Fi sont affichés. Les invités rejoignent ce Wi-Fi, puis saisissent le code FOSA. Ce réseau n’a pas besoin d’Internet. Changer de réseau avec plusieurs membres actifs est refusé afin de ne pas couper leur session.

**Code.** Les six chiffres sélectionnent la session. Le nouveau client demande un nonce local, valide code/session/nonce, puis reçoit un token aléatoire privé. Un même clientKey/resumeToken réutilise l’ancien membre et augmente sa génération ; des noms identiques ne fusionnent pas les appareils. Le protocole 1 reste accepté pour les clients existants.

**QR session.** Exemple de format : `fosa://join?v=2&s=<UUID>&c=482731&h=192.168.49.1&p=48765`. Un scan Android déclenche la recherche/jonction ; aucun scan de réponse. Le QR comporte un locator, jamais SDP, ICE, certificat ou token de membre.

**Scan Device.** Android invité : DISPLAY PAIRING QR annonce un appareil temporaire et ouvre un petit endpoint local. Hôte : ADD DEVICE ouvre la caméra ; découverte de cet appareil, vérification du nonce et transmission du locator de session, puis l’invité rejoint automatiquement. Invitation valable 90 s et consommable une fois. Pour un Web déjà ouvert depuis l’hôte local, le coordinateur enregistre l’invitation, l’hôte la valide par scan et le Web termine l’entrée avec un join token à usage unique. Un navigateur ne crée pas de serveur appareil ni de réseau Wi-Fi Direct.

**Audio.** Opus est transmis directement entre pairs sur le LAN/P2P, chiffré par DTLS/SRTP. Aucune adresse STUN/TURN n’est ajoutée. Les candidats relay/srflx et les adresses publiques ne sont pas échangés. Une paire relay/srflx détectée dans les stats est refusée. Le coordinateur transporte les commandes et la signalisation, aucun paquet audio. Talk All/User/Group/Leader, écoute sélective, Panic Mute, volume et service natif restent disponibles. Les permissions de parole/écoute sont désormais aussi affichées dans Members.

## Web local et limite incontournable de HTTP

L’APK embarque index.html, app.js, core.js, connection.js, QR, CSS, icônes, manifest et service worker. Aucun CDN/GitHub n’est nécessaire pour charger ces fichiers depuis l’hôte.

Ports : HTTP 48765 préféré, HTTPS 48766. WEB ACCESS affiche l’URL HTTPS courte et son QR, COPY LINK, la page HTTP `/trust` et l’empreinte de la CA. Le certificat racine est propre à l’installation ; sa clé privée reste dans le répertoire privé de l’application. La CA demeure identique après changement d’adresse, et un certificat serveur avec les adresses locales est renouvelé.

**Une page `http://192.168.x.x` ne peut pas activer getUserMedia ni installer le service worker dans un navigateur standard.** Elle peut charger l’interface et les instructions. Pour un accès audio/PWA complet, il faut ouvrir le HTTPS local et faire confiance à la CA de cet hôte une fois, avec vérification de son empreinte. Un simple contournement de l’avertissement navigateur ne garantit pas toutes les APIs. Cela ne requiert pas Internet, mais c’est une préparation réelle supplémentaire pour le Web ; le parcours Android natif n’exige pas cette installation.

GitHub Pages reste une porte d’entrée et un cache Web compatible. Pour rejoindre localement par code sans Internet, ouvrir l’URL fournie par l’hôte. Un navigateur ouvert sur Pages ne peut pas deviner une IP privée depuis six chiffres, annoncer un service mDNS, ni piloter WifiP2pManager. Un scan de session depuis le Web dirige vers la WebApp HTTPS de l’hôte ; son réseau doit déjà être rejoint.

## Sécurité et limites matérielles

Le code est annoncé pour identifier la session : **il n’est ni un secret cryptographique ni une protection contre quelqu’un qui est déjà sur le réseau**. Les identités, tokens privés, nonces à usage unique, expirations et générations limitent les reprises involontaires et rejeux. Les tokens de membres restent absents de la liste publique et des QR.

La signalisation native compatible reste HTTP sur un LAN de confiance : elle n’est pas une défense contre un observateur ou attaquant actif sur un Wi-Fi hostile. La confiance TLS du Web exige une vérification initiale. Ne pas annoncer une sécurité d’admission cryptographique forte avec seulement six chiffres.

Wi-Fi Direct dépend des capacités radio et du firmware. LAN/P2P/hotspot simultanés ne sont pas garantis ; certains Android exigent les services de localisation sur les versions anciennes. Permission Nearby Wi-Fi Devices à partir d’Android 13, localisation fine jusqu’à API 32. Un Wi-Fi avec isolation des clients peut empêcher tous les flux locaux. Mesh limité à huit membres ; consommation CPU/radio croissante. Bluetooth casque peut ajouter de la latence.

iOS/Windows natifs ne sont pas livrés. Leur séparation réseau/session/auth/signal/audio est documentée dans le contrat LocalTransport ; iOS devra utiliser ses APIs propres et Windows son adaptateur natif. Le PC et l’iPhone peuvent utiliser le Web une fois sur le réseau et son HTTPS approuvé. L’audio Web en arrière-plan n’est pas garanti. Après changement d’IP de l’hôte, un Web ouvert sur une ancienne URL doit rouvrir WEB ACCESS ; une coupure temporaire sur la même adresse récupère son identité.

Le Bodypack MR18, le bridge, Low Latency et l’ancienne application sont conservés. Aucun pilote ASIO ni moteur PCM n’est modifié.

## Fichiers de cette refonte

- `.gitignore` : exclure les produits de build locaux.
- `.github/workflows/android-apk.yml` : assets Web comme déclencheur, version, signature debug persistante et publication après tests.
- `.github/workflows/verify-audio.yml` : ajouter le scénario navigateur local-first.
- `native/android/app/build.gradle.kts` : versionCode 20, 0.13.0, scanner/TLS, copie des assets Web dans l’APK.
- `native/android/app/src/main/AndroidManifest.xml` : caméra optionnelle, Wi-Fi Direct optionnel, localisation API 32, liens courts, service connectedDevice.
- `native/android/app/src/main/java/com/arizona/fosa/mobile/LocalTransport.kt` : contrat, capacités de session et états.
- `LanTransport.kt` : DNS-SD et découverte UDP.
- `AndroidWifiDirectTransport.kt` : APIs P2P et groupe natifs.
- `LocalHotspot.kt` : réseau local sans WAN.
- `FosaConnectionManager.kt` : sélection/discovery/auth réseau.
- `PairingQr.kt`, `DeviceInvitation.kt` : invitations courtes, scan unique et expiration.
- `LanAddress.kt` : interfaces P2P et sélection réseau selon la route.
- `LanHttp.kt`, `LanTls.kt` : coordinateur borné, assets locaux, HTTPS et CA privée.
- `LanSession.kt` : protocole 2, challenge, admission Web par QR, permissions ; identité compatible.
- `MobileService.kt`, `MobileActivity.kt` : intégration réseau, Join/Members/Web Access, caméra et diagnostics.
- `RtcMobile.kt` : permissions et diagnostics de la paire média.
- `native/android/app/src/androidTest/java/com/arizona/fosa/mobile/LocalPairingTest.kt` : vraie signalisation HTTP, anti-rejeu, QR décodable, assets HTTPS avec validation TLS.
- `mobile/connection.js` : coordinateur Web local indépendant de l’audio.
- `mobile/core.js` : entrée locale, reprise, RPC HTTP/direct, permissions, stats.
- `mobile/app.js`, `mobile/sw.js` : Join simplifié, QR court, UI, cache local ; exclusion des routes de contrôle du cache.
- `mobile/tests/browser.cjs` : conserver les essais de compatibilité 0.12 et améliorer les diagnostics de test.
- `mobile/tests/local-first.cjs` : requêtes HTTP réelles et audio Chromium décodé, sans requête externe.
- `README.md`, `docs/mobile/{PROTOCOL,QUICKSTART,VALIDATION,LOCAL-FIRST-REPORT}.md`, `native/transport-contract.md`, `native/ios/README.md` : état de livraison et limites.

## Validation et publication

Résultats définitifs à compléter après les builds et workflows. Les tests Chromium locaux de média ne peuvent pas aboutir dans l’environnement de développement : son espace réseau ne possède qu’une interface loopback, volontairement exclue des candidats LAN de production. La politique média n’est pas assouplie pour faire passer ces tests. Les workflows GitHub disposant d’interfaces privées exécuteront ces scénarios.

Tests du bridge local : 41 tests, OK, dont 2 ignorés par la suite existante. Construction Android et tests instrumentés : en cours. Versionnement du QR visé : locator session <150 caractères, device <150, Web URL seule.

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
