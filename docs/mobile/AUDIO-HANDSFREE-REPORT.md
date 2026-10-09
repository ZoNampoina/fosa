# FOSA Mobile 0.14.0 — audio, mains libres et réseau local

Audit initial : 8 octobre 2026 ; reprise : 9 octobre 2026 (UTC). Android versionCode 21. Ce rapport sépare les preuves de transport/PCM des essais d’audibilité sur les appareils des musiciens.

## État de livraison

**FOSA Mobile 0.14.0 publiée et vérifiée le 9 octobre 2026 (UTC).** Android versionCode **21**, package `com.arizona.fosa`. Commit applicatif distribué : [`16cb0e684ecc08022f32f3903c456acb1f8683b2`](https://github.com/ZoNampoina/fosa/commit/16cb0e684ecc08022f32f3903c456acb1f8683b2), sur `main`, `v0.14.0` et `android-latest`. [PR #7](https://github.com/ZoNampoina/fosa/pull/7) ; [commits détaillés](https://github.com/ZoNampoina/fosa/pull/7/commits). Base de l’intervention : `922694571f5e36b635e8ea3a57f5f35b7453caf2`.

- [APK versionné 0.14.0](https://github.com/ZoNampoina/fosa/releases/download/v0.14.0/FOSA-Android.apk).
- [APK stable](https://github.com/ZoNampoina/fosa/releases/download/android-latest/FOSA-Android.apk).
- [WebApp Mobile](https://zonampoina.github.io/fosa/mobile/).
- [Notes de publication](https://github.com/ZoNampoina/fosa/releases/tag/v0.14.0).
- [Démarrage local](QUICKSTART.md) : sans Internet, ouvrir **WEB ACCESS** fourni par l’Android hôte ; configurer la confiance de son certificat local avant d’utiliser le microphone Web.

Les preuves ci-dessous portent sur le transport, le PCM décodé et les contrôles logiciels. Les microphones, casques et radios des appareils de l’utilisateur restent à vérifier physiquement.

## Défauts constatés et correction

**Défaut reproductible : activation simultanée des microphones.** Deux pairs initialement en écoute seule ajoutaient leur piste puis produisaient chacun une offre SDP. Le moteur Android ne traitait pas la collision : il pouvait rester connecté en ICE/data channel sans négocier les directions d’envoi audio. Les erreurs de création/application du SDP étaient ignorées. Le test `simultaneousMicrophonePermissionUpgradeKeepsAudioNegotiated` échoue sur la base 0.13.0 : « Simultaneous permission upgrades must settle both audio senders ».

Preuve avant correctif : [diagnostic Android de la base](https://github.com/ZoNampoina/fosa/actions/runs/37811664536), commit de laboratoire `bd4fbae` dérivé de `c1e040f` (uniquement le test ajouté à 0.13.0 et son workflow). Le test UI ancien échoue aussi sur une hypothèse de débit : sur un microphone silencieux, Opus peut produire peu de paquets. Ce deuxième résultat ne prouve pas une coupure sonore.

Le correctif conserve le PeerConnection lors du changement de permission. Le pair de plus petite identité ignore l’offre en collision ; l’autre annule son offre locale avant d’appliquer l’offre distante. Les opérations SDP Android et Web sont sérialisées jusqu’à leur callback de fin. Après un rollback, la renégociation des nouvelles pistes reste demandée. Les offres en attente sont suivies et dédupliquées, et les erreurs SDP apparaissent dans les diagnostics. Le moteur vérifie aussi qu’un sender audio est effectivement négocié avant de déclarer la parole active. Le SDP du SDK natif identifie parfois le sender réutilisé par un UUID distinct du nom de la piste microphone ; le contrôle suit cet identifiant réel. Une négociation peut valablement répartir l’audio sur deux sections SEND_ONLY/RECV_ONLY : le test exige la piste microphone correspondante et du PCM décodé bidirectionnel, plutôt qu’une direction SEND_RECV particulière.

**Défaut d’affichage indépendant :** le vu-mètre microphone était forcé à zéro hors transmission. Il suit désormais le PCM réellement capturé, même lorsque la porte de parole est fermée. Un micro autorisé, une piste créée et ICE connecté sont des états distincts de la capture et du son reçu.

Ces constats établissent des causes logicielles vérifiables. Ils ne démontrent pas que tous les silences observés sur les appareils de l’utilisateur proviennent uniquement de ces défauts. Une source matérielle muette, une sortie à zéro, un casque, une permission système ou un filtrage du réseau restent identifiables grâce aux nouveaux diagnostics et nécessitent une vérification sur les appareils concernés.

## Mesure de la chaîne audio

| Étape | Android natif | Web local / PC |
|---|---|---|
| Autorisation / source | RECORD_AUDIO, source VOICE_COMMUNICATION, état démarrage/arrêt AudioRecord, mute système | getUserMedia, état de la piste et erreurs détaillées |
| Capture réelle | compteurs PCM et PCM non nul, RMS dBFS du microphone | WAV de laboratoire via getUserMedia dans les tests ; AudioWorklet de mesure PCM/RMS en production |
| Intention / porte | mode, armed, talkRequested, talking, senderEnabled, negotiatedSender | mode, armed, porte, direction réellement négociée du transceiver |
| Encodage / émission | codec du flux via codecId, paquets/octets RTP TX et delta | mêmes stats réelles du flux audio |
| Réception | paquets/octets RX, perte/jitter, énergie/durée/échantillons lorsque exposés par WebRTC | mêmes stats et RMS du MediaStream décodé |
| Lecture / sortie | PCM mélangé réellement écrit vers AudioTrack, compteur non nul, route et volume Android | audio.play(), lecture bloquée/démarrée, mute/volume et niveau décodé |
| Latence physique | UNKNOWN | UNKNOWN |

Les diagnostics donnent aussi des messages lisibles pour le micro bloqué/silencieux, la piste non négociée, l’absence de paquets récents, la lecture bloquée, la permission de réception et les volumes à zéro. Ils ne prétendent pas distinguer avec certitude un silence envoyé d’un défaut de décodage.

Les niveaux sont en dBFS ; les octets et paquets sont des compteurs cumulés de la connexion, les deltas décrivent la dernière période de mesure. Une piste muette peut encore transporter du RTP de silence. Le RTT ne représente pas la latence microphone → casque. La lecture PCM ne prouve pas à elle seule qu’une personne entend du son.

`SETTINGS → AUDIO DIAGNOSTICS → TEST AUDIO` injecte explicitement deux secondes de 660 Hz dans la vraie chaîne d’envoi Opus, vers la destination choisie. Il faut un micro autorisé et la permission de parole. Il s’arrête automatiquement, et Panic/fermeture/révocation l’arrêtent. Le signal traverse le sender, RTP, le décodeur et le chemin de sortie mesuré. Sur Android, `testSignalFrames` est séparé de `captureFrames` / `captureNonZeroFrames` : le test synthétique ne gonfle pas la preuve de capture physique. La tonalité native remplace volontairement le PCM avant l’encodeur, tout en conservant l’horloge réelle d’AudioRecord. Désactiver AudioRecord dans ce SDK donnait des callbacks sans octets et sans cadence ; cette erreur du candidat a été détectée par le test natif → Web puis corrigée. Aucun son de test n’est lancé automatiquement.

## HOLD, TAP et AUTO

| Mode | Déclenchement | Arrêt / sécurité | État initial |
|---|---|---|---|
| HOLD | appui maintenu, tactile ou clavier ; geste conservé lors des mises à jour | relâchement/annulation ; garde-fou 30 s ; pause de l’activité | fermé |
| TAP | un toucher ouvre, le suivant ferme | limite facultative OFF / 5 / 15 min ; notification Android STOP TALK / MUTE | fermé |
| AUTO | activation manuelle puis seuil d’énergie PCM | délai de fermeture 150–3000 ms, limite facultative et arrêt manuel | désarmé |

Le réglage de sensibilité AUTO couvre −60 à −12 dBFS, avec suppression du bruit réglable. AUTO est un détecteur d’énergie, pas un classificateur de parole : un instrument ou un bruit suffisamment fort ouvre la porte. Il n’est pas recommandé de présumer une détection fiable de la voix dans un environnement musical bruyant.

TAP conserve la transmission pendant l’arrière-plan natif et ne réutilise pas le timer HOLD. Le Web ferme la parole lorsqu’il perd sa visibilité/son focus ; son fonctionnement audio en arrière-plan n’est pas garanti. Panic, Leave, nouvelle génération ou révocation ferment la parole sans réarmement automatique. La perte du transport ferme TAP/AUTO ; HOLD reste lié au geste réellement maintenu et à son garde-fou. Toute tonalité de diagnostic est annulée à la perte du transport, même en mode HOLD. Le changement All / Group / Leader / User s’applique immédiatement, tout en gardant les permissions. Le choix d’écoute et les mutes par membre restent indépendants.

## Réseau et QR

NETWORK SETUP expose USE CURRENT NETWORK (défaut), CREATE LOCAL FOSA NETWORK, WI-FI DIRECT et ADVANCED NETWORK SETTINGS. Le réseau actuel doit fournir une adresse privée Wi-Fi/Ethernet utilisable ; sa sélection ne modifie pas le routeur. Wi-Fi Direct reste un transport natif Android, avec son adresse de groupe réelle. Le Web rejoint un réseau existant et ne crée pas de groupe radio.

Le hotspot conserve la réservation Android. Quand Android fournit le SSID et le mot de passe réels, ils alimentent le QR standard `WIFI:T:WPA;S:...;P:...;;`. Les caractères `\`, `;`, `:`, `,` et `"` sont échappés. Le mot de passe est masqué avec SHOW/HIDE et COPY. Si les identifiants sont incomplets ou le hotspot arrêté, aucun QR valable n’est inventé ou conservé.

- SCAN TO CONNECT TO WI-FI : rejoint uniquement le Wi-Fi avec le scanner du système.
- SCAN TO JOIN FOSA SESSION : rejoint ensuite la session FOSA ; le code à six chiffres reste utilisable.

Les diagnostics affichent les adresses/interfaces, le coordinateur, le Web HTTPS et les erreurs du transport. Wi-Fi invité/isolation, pare-feu TCP/UDP, multicast/mDNS bloqué, routage et multiples interfaces sont des pistes de contrôle ; l’app ne prétend pas détecter avec certitude ni corriger l’isolation du routeur. Le port TCP préféré peut être occupé : le serveur HTTP annonce alors son port réel ; une erreur du serveur HTTPS reste affichée.

Les réparations audio automatiques sont bornées à trois par pair ; les reprises d’identité à six essais. Une action manuelle peut réinitialiser le budget. Le WAN perdu avec un LAN toujours utilisable n’entraîne pas de reprise d’identité. Un changement d’IP natif redémarre les serveurs/annonces et garde l’identité ; le navigateur doit rouvrir le nouveau WEB ACCESS si l’adresse de l’hôte change.

## Vérification logicielle

| Vérification | Preuve / environnement | Résultat |
|---|---|---|
| Collision SDP avant correctif | deux moteurs natifs réels, émulateur API 35 | échec reproduit sur la base |
| Collision SDP après correctif | transport conservé, sender réel SEND_RECV ou SEND_ONLY, PCM décodé dans les deux sens | réussi |
| Capture Web et deux sens Opus | WAV via getUserMedia → AudioWorklet → vrais encodeurs/décodeurs Chromium | réussi |
| TAP Web prolongé | PCM décodé distant suivi pendant 180 secondes réelles | réussi |
| Trois clients Web simultanés | six chemins WebRTC, énergie et PCM non nul dans chaque direction | réussi |
| Panic / permissions / reset / identité | porte fermée sans réarmement, reprise sans doublon | réussi |
| Activation audio retardée | Panic annule aussi une tonalité encore en attente d’AudioContext | réussi |
| AUTO avec vrai PCM | armement, seuil haut/bas, délai et arrêt | réussi |
| Natifs ↔ natifs | deux moteurs dans un émulateur, tonalités explicites, PCM réel écrit vers AudioTrack | réussi |
| Android ↔ Web HTTPS local | coordinateur Kotlin, encodeurs/décodeurs réels, PCM distant mesuré par le Worklet de production | réussi |
| TAP natif en arrière-plan | activité arrêtée pendant 180 s, callbacks AudioRecord et RTP suivis, sans tonalité de test | réussi ; continuité du moteur, aucune preuve de microphone physique |
| Wi-Fi QR / QR session / appairage | encodeur puis décodage ZXing, caractères SSID/password spéciaux, QR courts | réussi ; scan radio physique restant |
| Bodypack / bridge / Low Latency / PC | régressions Windows/Linux, Chromium/WebKit, PCM/Opus, routage et permissions | réussi |
| Gain Bodypack +6 dB | neuf fenêtres PCM de sinusoïde complète avant/après, rapport RMS attendu ×1,6 à ×2,4 | réussi ; assertion de gain conservée |
| iOS préparatoire | compilation SwiftUI sur macOS 15 Intel | réussi ; aucun client iOS natif livré |
| Écrans et paysage | assertions d’interface/captures 320/375/844/1024 px ; Android Pixel 6 émulé portrait/paysage ; HOLD tactile/clavier | réussi |

La livraison Android exige le succès des **15 tests fonctionnels instrumentés** et des régressions Web/bridge ; le test visuel de splash est collecté séparément. Les workflows du commit distribué ont tous réussi :

- [Build FOSA Android APK](https://github.com/ZoNampoina/fosa/actions/runs/37871010202) : SUCCESS.
- [Verify FOSA Audio](https://github.com/ZoNampoina/fosa/actions/runs/37871010029) : SUCCESS.
- [Deploy FOSA to GitHub Pages](https://github.com/ZoNampoina/fosa/actions/runs/37871010244) : SUCCESS.

Prévalidation : natif sur `a052bc2`, Web/PR sur `fad40b2`. Le dernier changement de test PC n’a modifié aucun fichier applicatif Android/Mobile ; les workflows de livraison ont ensuite répété les assertions sur `16cb0e684ecc08022f32f3903c456acb1f8683b2`.

Le rapport final est consigné après les contrôles de publication dans un commit de documentation distinct. Les empreintes ci-dessous décrivent les artefacts du commit applicatif distribué.

Le coordinateur Node est une fixture de protocole ; le moteur média Chromium est réel. Android utilise le coordinateur Kotlin réel. Deux moteurs dans le même émulateur peuvent sélectionner une route interne `127.0.0.1` / `prflx` : ce résultat ne valide pas deux radios Android sur un routeur. Les tests Web vérifient séparément une paire host/host, DTLS connecté, aucun serveur ICE et aucune requête externe. Les filtres ICE privés de production sont conservés. Le WebView de laboratoire accepte explicitement son certificat de test ; cela ne valide pas l’installation du certificat sur un appareil physique.

La statistique RTT apparaît de façon asynchrone après une reconstruction de transport : le test attend sa valeur réelle, avec une limite de 15 secondes. Le test natif → Web conserve les compteurs PCM et le pic RMS sur toute la tonalité de deux secondes, puis exige une augmentation d’énergie RTP et une lecture active. Il ne dépend plus d’une seule lecture instantanée qui peut manquer le signal lorsque le thread UI de l’émulateur est retardé. Les deux directions de ce test sont isolées successivement. La tonalité reste arrêtée après Panic, même si l’activation audio est retardée, et après une perte du transport en mode HOLD.

Pour le contrôle +6 dB, la fixture PC produit une sinusoïde constante. La mesure retient neuf fenêtres décodées complètes, dans un délai maximal de 12 secondes, afin d’exclure les fondus de rebuffering. Les bornes ×1,6 à ×2,4 restent appliquées au RMS réellement décodé. La continuité et les pertes PCM ont leurs assertions distinctes.

Mesure du commit livré : RMS numérique normalisé **0,017684 → 0,035250**, soit un rapport **×1,9934**.

### Exemple de mesures natives et hybride

Instantanés du test sur émulateur API 35 ; les tonalités explicites isolent la chaîne média. Les compteurs sont cumulés et les statistiques RTP sont asynchrones. Les niveaux/compteurs suivants ne constituent ni une preuve de microphone parlé, ni une mesure de latence physique.

| Sens / essai | Mesure réelle | Résultat |
|---|---|---|
| Natif A → B | 9 paquets / 791 octets TX ; 98 400 échantillons de sortie non nuls sur B | Opus et DTLS connectés |
| Natif B → A | 8 paquets / 636 octets TX ; 95 040 échantillons de sortie non nuls sur A | Opus et DTLS connectés |
| Web → natif | 97 920 échantillons PCM de sortie non nuls | vraie réception native |
| Natif → Web | 77 184 échantillons PCM distants non nuls ; pic mesuré -31.05 dBFS | Worklet branché sur le MediaStream distant ; énergie RTP en hausse et lecture active |
| TAP natif 180 s | 9 755 040 échantillons AudioRecord ; `testSignalFrames=0` ; armement conservé en arrière-plan | callbacks et émission RTP continus |

Les échantillons de microphone non nuls d’un émulateur peuvent provenir du loopback de son système audio. `testSignalFrames` reste séparé des compteurs de microphone.

| Récepteur natif | Énergie RTP reçue | RTT mesuré | Jitter | Pertes |
|---|---:|---:|---:|---:|
| A | 0,000955 | 0 ms | 2 ms | 0 % |
| B | 0,001467 | 0 ms | 3 ms | 0 % |

Ces instantanés RTP peuvent précéder la fin de la tonalité et les compteurs PCM de sortie. Le RTT local de l’émulateur ne représente pas la latence microphone → casque.

### Exemple de mesures Web réellement collectées

Essai de trois clients, après cinq secondes d’émission simultanée. Chaque ligne décrit une connexion distincte ; ces compteurs ne sont pas un débit moyen ni une latence.

| Sens | Octets TX / RX vus par le pair local | Énergie audio reçue | RMS décodé reçu |
|---|---:|---:|---:|
| Hôte ↔ invité 1 | 23 429 / 23 464 | 0.008073 | -34.11 dBFS |
| Hôte ↔ invité 2 | 20 211 / 20 040 | 0.019282 | -29.91 dBFS |
| Invité 1 ↔ hôte | 23 545 / 23 429 | 0.007201 | -34.98 dBFS |
| Invité 1 ↔ invité 2 | 20 439 / 20 405 | 0.018965 | -32.64 dBFS |
| Invité 2 ↔ hôte | 20 040 / 20 211 | 0.007207 | -35.95 dBFS |
| Invité 2 ↔ invité 1 | 20 486 / 20 439 | 0.008121 | -35.42 dBFS |

Les compteurs Web de capture non nulle sont 9 357 184, 368 000, 275 584 échantillons. L’entrée est simulée par Chromium via getUserMedia ; aucun résultat sur les microphones physiques ne découle de ces chiffres. Les snapshots TX/RX opposés peuvent différer légèrement.

## Fichiers modifiés

- Audio : `RtcMobile.kt`, `MobileService.kt`, nouveaux `AudioNegotiation.kt`, `TalkControl.kt`, `mobile/talk.js`, `mobile/meter-worklet.js`, `mobile/core.js`.
- Réseau / QR : `FosaConnectionManager.kt`, `LanAddress.kt`, `LanHttp.kt`, nouveau `WifiQr.kt`.
- Interface / cache : `MobileActivity.kt`, `FosaDesign.kt`, `mobile/app.js`, `mobile/style.css`, `mobile/sw.js`.
- Tests : `MobileEngineTest.kt`, `MobileUiTest.kt`, `LocalPairingTest.kt`, nouveau `HybridAudioTest.kt`, `BodypackUiTest.kt`, `mobile/tests/local-first.cjs`, nouveau `mobile/tests/talk-control.cjs`, `audio-bridge/tests/low_latency_browser_flow.cjs`.
- Version / publication : `native/android/app/build.gradle.kts`, workflows Android et vérification audio, script Android de collecte des mesures.
- Documentation : README, QUICKSTART, PROTOCOL, VALIDATION et ce rapport.

Le Bodypack/MR18, le bridge PC et le moteur Low Latency restent des chemins distincts. Ils ont leurs régressions propres ; aucun monitoring MR18 depuis une session Mobile sans PC n’est annoncé.

## Contrôles des artefacts publiés

| Contrôle | Résultat vérifié |
|---|---|
| APK versionné réellement téléchargé | HTTP 200 ; 63 144 179 octets ; manifeste `com.arizona.fosa` / `0.14.0` / versionCode `21` |
| APK stable réellement téléchargé | même SHA-256 que l’APK versionné ; tags rattachés au commit applicatif distribué |
| Signature APK | signature v2 et digest de contenu vérifiés cryptographiquement ; certificat identique à l’APK 0.13.0 |
| Mise à jour Android | identité de signature conservée ; APK de type debug, comme la distribution précédente |
| Web embarqué | les 11 fichiers `assets/fosa-web/` correspondent octet par octet au dépôt vérifié |
| GitHub Pages réel | HTTP 200 sur les 11 fichiers Mobile ; tous les octets correspondent au dépôt |
| PWA | cache `fosa-mobile-0.14.0`, modules Talk/PCM inclus, version affichée 0.14.0 |

APK SHA-256 : `103aabf14f519ebb25dec9679acfbebda4ad1f697248690eff65deaf759d18aa`.

Certificat SHA-256 : `16c73ab9e6bf8f04e4d6bd32beb2ab4635343fd537b9f97297f50b1d37b1f0d1`.

### Empreintes Web (publiées et embarquées)

| Fichier | SHA-256 |
|---|---|
| `app.js` | `5cc2ff7cd5661622f2ba1033240068e431c347f26ebd93d38d7e0eb9e6ebb73c` |
| `connection.js` | `30f4c1bef366360e50622beb90a872637042c40b97317db4dd9a59f7e298a1d4` |
| `core.js` | `b7d602af3c790cb49db879a5f23968debda89b46277f9a50caf5f68a3b79491c` |
| `icon.svg` | `ad4662a30faa9c4cd67c55d549cc531a9ed84928f78217690c2b307c496f32b6` |
| `index.html` | `fef3f8d92023533c7b37463a35ea65827b3eb624ffdc47a927dec44200a2cd58` |
| `manifest.json` | `d8428e3efb6fc847eef2f83bbb8ba8e8e7b6246094cfa3aa0d0b303f075f72b3` |
| `meter-worklet.js` | `c54ee3bb5f7c454ec651cdf23080cba72007d739c7f24b059e58d81b873fe4ac` |
| `qr.js` | `bec651a984953adf3df9798b2f14406105494363eef513851a4417f830392ec4` |
| `style.css` | `cf933884124de07dbcb02d0090c86520be8d388d84d03eb7f2d37cc1327d9703` |
| `sw.js` | `b00b37aa20d5c3d7491eeb1f5d095fc621ee051609fe7cd843ff86706b716e78` |
| `talk.js` | `007a7424da12a3181f11a99cb96ea2a78fe1fde60f7e9903ca622a02fa74c894` |

## Essais matériels restant nécessaires

| Scénario | À vérifier sur les appareils réels |
|---|---|
| Deux Android, LAN sans Internet | microphone parlé non nul, audio entendu dans les deux sens, destinations et écoute, TAP plusieurs minutes |
| Android + PC/iPhone/Web local HTTPS | confiance du certificat, getUserMedia, playback déverrouillé, son physique dans les deux sens |
| Hotspot local créé par Android | SSID/password réels, scan Wi-Fi système, puis code/QR de session, audio entre radios |
| Wi-Fi Direct sans routeur | prise en charge des appareils, permissions, découverte/connexion et média entre radios |
| Réseau invité / WAN coupé / routeur | isolation, multicast, pare-feu, routes multiples ; aucune modification involontaire de la configuration |
| Réseau perdu puis rétabli / IP changée | reprise sans doublon, nouveau lien Web, micro restant fermé, réparation bornée |
| Filaire / USB / Bluetooth / débranchement | sortie entendue, volume système, Panic au débranchement, latence physique |
| AUTO sur scène / plusieurs musiciens | faux déclenchements par les instruments, réglage du seuil et du délai, absence de réarmement |
| MR18 / Windows ASIO / Bodypack | capture réelle 18 entrées, mixes, cohabitation talkback/monitoring et latence microphone → casque |

Verdict matériel : **NON VALIDÉ sur les appareils de l’utilisateur**. Les liens/CI ne doivent pas être interprétés comme une validation d’audibilité ou de latence avant ces essais.
