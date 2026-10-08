# FOSA Mobile 0.14.0 — audio, mains libres et réseau local

Date : 8 octobre 2026. Android versionCode 21. Ce rapport sépare les preuves de transport/PCM des essais d’audibilité sur les appareils des musiciens.

## État de livraison

La version candidate est en validation Android/Web. Les liens de publication, le commit final et les empreintes seront renseignés après les contrôles de livraison. Aucune validation de microphone, de casque ou de radio physique n’est annoncée sur la base d’un build.

## Défauts constatés et correction

**Défaut reproductible : activation simultanée des microphones.** Deux pairs initialement en écoute seule ajoutaient leur piste puis produisaient chacun une offre SDP. Le moteur Android ne traitait pas la collision : il pouvait rester connecté en ICE/data channel sans négocier les directions d’envoi audio. Les erreurs de création/application du SDP étaient ignorées. Le test `simultaneousMicrophonePermissionUpgradeKeepsAudioNegotiated` échoue sur la base 0.13.0 : « Simultaneous permission upgrades must settle both audio senders ».

Preuve avant correctif : [diagnostic Android de la base](https://github.com/ZoNampoina/fosa/actions/runs/37811664536), commit de laboratoire `bd4fbae` dérivé de `c1e040f` (uniquement le test ajouté à 0.13.0 et son workflow). Le test UI ancien échoue aussi sur une hypothèse de débit : sur un microphone silencieux, Opus peut produire peu de paquets. Ce deuxième résultat ne prouve pas une coupure sonore.

Le correctif conserve le PeerConnection lors du changement de permission. Le pair de plus petite identité ignore l’offre en collision ; l’autre annule son offre locale avant d’appliquer l’offre distante. Les opérations Web sont sérialisées, les offres en attente sont suivies, les erreurs SDP apparaissent dans les diagnostics. Le moteur vérifie aussi qu’un sender audio est effectivement négocié avant de déclarer la parole active.

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

Les niveaux sont en dBFS ; les octets et paquets sont des compteurs cumulés de la connexion, les deltas décrivent la dernière période de mesure. Une piste muette peut encore transporter du RTP de silence. Le RTT ne représente pas la latence microphone → casque. La lecture PCM ne prouve pas à elle seule qu’une personne entend du son.

`SETTINGS → AUDIO DIAGNOSTICS → TEST AUDIO` injecte explicitement deux secondes de 660 Hz dans la vraie chaîne d’envoi Opus, vers la destination choisie. Il faut un micro autorisé et la permission de parole. Il s’arrête automatiquement, et Panic/fermeture/révocation l’arrêtent. Le signal traverse le sender, RTP, le décodeur et le chemin de sortie mesuré. Sur Android, `testSignalFrames` est séparé de `captureFrames` / `captureNonZeroFrames` : le test synthétique ne gonfle pas la preuve de capture physique. Aucun son de test n’est lancé automatiquement.

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
| Collision SDP après correctif | même test, même PeerConnection, directions SEND_RECV | en cours |
| Capture Web + deux sens Opus | WAV via getUserMedia → AudioWorklet → vraie chaîne, Chromium | réussi sur candidat 03f5822 |
| TAP prolongé | PCM décodé distant mesuré pendant 180 secondes réelles | réussi sur candidat 03f5822 |
| Trois locuteurs simultanés | six chemins WebRTC, énergie et PCM non nul dans chaque direction | réussi sur candidat 03f5822 |
| Panic / permissions / reset / reprise | porte fermée sans réarmement, même identité, pas de doublon | réussi sur candidat 03f5822 |
| AUTO avec vrai PCM | armement, seuil haut/bas, délai et arrêt | réussi sur candidat 62d8bb4 |
| Native ↔ native / native ↔ Web HTTPS | encodeurs/décodeurs réels, tonalités explicites, PCM de sortie | en cours |
| TAP natif en arrière-plan | activité arrêtée >30 s, session/service et capture actifs | en cours |
| Wi-Fi QR | encodeur QR puis décodage ZXing, SSID/password spéciaux | en cours |
| Bodypack / bridge / Low Latency / talkback PC | régressions Windows/Linux, Chromium/WebKit, PCM/Opus, routage/permissions | réussi sur candidat 03f5822 |
| iOS préparatoire | compilation SwiftUI sur macOS 15 Intel | réussi sur candidat 03f5822 ; aucun client iOS natif livré |
| Petits écrans et paysage | Talk/Panic au-dessus de la navigation, captures 320/375/844/1024 px | réussi Web ; Android en cours |

[CI Web et régressions du candidat 03f5822](https://github.com/ZoNampoina/fosa/actions/runs/37814193058). Le coordinateur Node est une fixture de protocole ; le moteur média Chromium est réel. Les tests Android utilisent le vrai coordinateur Kotlin. Le WebView de laboratoire accepte explicitement son certificat de test ; cela ne valide pas l’installation du certificat sur un iPhone/PC/téléphone physique. L’environnement de développement ne fournit que loopback : le test média local y échoue en l’absence de candidats privés admissibles, sans assouplir les filtres de production. Les tests de capture/UI/politique de parole peuvent néanmoins s’y exécuter.

### Exemple de mesures Web réellement collectées

Instantané lors de l’essai trois locuteurs du candidat 03f5822, après cinq secondes d’émission simultanée. Chaque cellule décrit une connexion distincte ; ces compteurs ne sont pas un débit moyen ni une mesure de latence.

| Sens | Octets TX / RX vus par le pair local | Énergie audio reçue | RMS décodé reçu |
|---|---:|---:|---:|
| Hôte ↔ invité 1 | 23 486 / 23 545 | 0,008108 | −32,89 dBFS |
| Hôte ↔ invité 2 | 19 982 / 20 073 | 0,020055 | −29,91 dBFS |
| Invité 1 ↔ hôte | 23 545 / 23 486 | 0,007270 | −34,82 dBFS |
| Invité 1 ↔ invité 2 | 20 439 / 20 484 | 0,020574 | −33,17 dBFS |
| Invité 2 ↔ hôte | 20 073 / 20 063 | 0,007287 | −35,95 dBFS |
| Invité 2 ↔ invité 1 | 20 484 / 20 439 | 0,008115 | −34,66 dBFS |

Les compteurs de capture Web non nulle sont respectivement 9 313 408, 367 872 et 275 456 échantillons. L’entrée est simulée par Chromium ; aucune conclusion sur les microphones physiques ne découle de ces chiffres. Les snapshots RTP étant asynchrones, TX et RX opposés peuvent différer légèrement.

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
