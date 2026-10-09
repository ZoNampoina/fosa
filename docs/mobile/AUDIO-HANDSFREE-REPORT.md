# FOSA Mobile 0.14.0 — audio, mains libres et réseau local

Audit initial : 8 octobre 2026 ; reprise : 9 octobre 2026 (UTC). Android versionCode 21. Ce rapport sépare les preuves de transport/PCM des essais d’audibilité sur les appareils des musiciens.

## État de livraison

La version candidate est en validation Android/Web. Les liens de publication, le commit final et les empreintes seront renseignés après les contrôles de livraison. Aucune validation de microphone, de casque ou de radio physique n’est annoncée sur la base d’un build.

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

TAP conserve la transmission pendant l’arrière-plan natif et ne réutilise pas le timer HOLD. Le Web ferme la parole lorsqu’il perd sa visibilité/son focus ; son fonctionnement audio en arrière-plan n’est pas garanti. Panic, Leave, perte de transport, nouvelle génération ou révocation ferment la parole sans la rouvrir automatiquement à la reprise. Le changement All / Group / Leader / User s’applique immédiatement, tout en gardant les permissions. Le choix d’écoute et les mutes par membre restent indépendants.

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
| Collision SDP après correctif | même PeerConnection, sender réel actif (SEND_RECV ou SEND_ONLY), puis PCM décodé dans les deux sens | en cours |
| Capture Web + deux sens Opus | WAV via getUserMedia → AudioWorklet → vraie chaîne, Chromium | réussi sur candidat 0c21300 |
| TAP prolongé | PCM décodé distant mesuré pendant 180 secondes réelles | réussi sur candidat 0c21300 |
| Trois locuteurs simultanés | six chemins WebRTC, énergie et PCM non nul dans chaque direction | réussi sur candidat 0c21300 |
| Panic / permissions / reset / reprise | porte fermée sans réarmement, même identité, pas de doublon | réussi sur candidat 0c21300 |
| AUTO avec vrai PCM | armement, seuil haut/bas, délai et arrêt | réussi sur candidat 0c21300 |
| Native ↔ native / native ↔ Web HTTPS | encodeurs/décodeurs réels, tonalités explicites, PCM de sortie | réussi sur candidat 0c21300 ; aucune preuve de micro physique |
| TAP natif en arrière-plan | activité arrêtée pendant 180 s, callbacks AudioRecord et RTP suivis | réussi sur candidat 1763b74 ; micro d’émulateur silencieux, preuve de continuité des callbacks et du RTP |
| Wi-Fi QR | encodeur QR puis décodage ZXing, SSID/password spéciaux | réussi sur candidat 0c21300 ; scan radio physique restant |
| Bodypack / bridge / Low Latency / talkback PC | régressions Windows/Linux, Chromium/WebKit, PCM/Opus, routage/permissions | réussi sur candidat 0c21300 |
| iOS préparatoire | compilation SwiftUI sur macOS 15 Intel | réussi sur candidat 0c21300 ; aucun client iOS natif livré |
| Petits écrans et paysage | Talk/Panic au-dessus de la navigation, captures 320/375/844/1024 px | réussi Web ; Android Pixel 6 émulé portrait/paysage sur 0c21300 |

[CI Web et régressions du candidat 0c21300](https://github.com/ZoNampoina/fosa/actions/runs/37864612574). [Essais Android 0c21300](https://github.com/ZoNampoina/fosa/actions/runs/37864612967) : 14/15 tests réussis ; seule la collision de permission reste en échec sur ce candidat. Le coordinateur Node est une fixture de protocole ; le moteur média Chromium est réel. Les tests Android utilisent le vrai coordinateur Kotlin. Le WebView de laboratoire accepte explicitement son certificat de test ; cela ne valide pas l’installation du certificat sur un iPhone/PC/téléphone physique. L’environnement de développement ne fournit que loopback : le test média local y échoue en l’absence de candidats privés admissibles, sans assouplir les filtres de production. Les tests de capture/UI/politique de parole peuvent néanmoins s’y exécuter.

### Exemple de mesures Web réellement collectées

Instantané lors de l’essai trois locuteurs du candidat 0c21300, après cinq secondes d’émission simultanée. Chaque cellule décrit une connexion distincte ; ces compteurs ne sont pas un débit moyen ni une mesure de latence.

| Sens | Octets TX / RX vus par le pair local | Énergie audio reçue | RMS décodé reçu |
|---|---:|---:|---:|
| Hôte ↔ invité 1 | 19 594 / 20 218 | 0,008562 | −32,48 dBFS |
| Hôte ↔ invité 2 | 18 584 / 20 048 | 0,016986 | −32,70 dBFS |
| Invité 1 ↔ hôte | 20 218 / 19 594 | 0,007710 | −35,96 dBFS |
| Invité 1 ↔ invité 2 | 18 578 / 20 511 | 0,016955 | −33,07 dBFS |
| Invité 2 ↔ hôte | 20 048 / 18 584 | 0,007775 | −32,94 dBFS |
| Invité 2 ↔ invité 1 | 20 511 / 18 578 | 0,008432 | −36,00 dBFS |

Les compteurs de capture Web non nulle sont respectivement 9 356 800, 372 736 et 285 184 échantillons. L’entrée est simulée par Chromium ; aucune conclusion sur les microphones physiques ne découle de ces chiffres. Les snapshots RTP étant asynchrones, TX et RX opposés peuvent différer légèrement.

## Fichiers modifiés

- Audio : `RtcMobile.kt`, `MobileService.kt`, nouveaux `AudioNegotiation.kt`, `TalkControl.kt`, `mobile/talk.js`, `mobile/meter-worklet.js`, `mobile/core.js`.
- Réseau / QR : `FosaConnectionManager.kt`, `LanAddress.kt`, `LanHttp.kt`, nouveau `WifiQr.kt`.
- Interface / cache : `MobileActivity.kt`, `FosaDesign.kt`, `mobile/app.js`, `mobile/style.css`, `mobile/sw.js`.
- Tests : `MobileEngineTest.kt`, `MobileUiTest.kt`, `LocalPairingTest.kt`, nouveau `HybridAudioTest.kt`, `mobile/tests/local-first.cjs`, nouveau `mobile/tests/talk-control.cjs`.
- Version / publication : `native/android/app/build.gradle.kts`, workflows Android et vérification audio, script Android de collecte des mesures.
- Documentation : README, QUICKSTART, PROTOCOL, VALIDATION et ce rapport.

Le Bodypack/MR18, le bridge PC et le moteur Low Latency restent des chemins distincts. Ils ont leurs régressions propres ; aucun monitoring MR18 depuis une session Mobile sans PC n’est annoncé.

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
