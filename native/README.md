# FOSA Native Hybrid

Objectif : conserver l'UI FOSA tout en ajoutant un transport natif appareil-à-appareil.

## Priorité de transport

1. Nearby / Wi-Fi direct / Bluetooth local
2. WebRTC DIRECT (LAN)
3. WebRTC srflx
4. TURN Internet

La couche native doit maintenir au moins deux chemins disponibles quand c'est possible et basculer sans couper le moteur audio.

## Android

- Google Nearby Connections pour découverte + connexion hors Internet.
- AudioRecord / AudioTrack faible latence ou AAudio via couche native.
- Canal de contrôle : rôle, groupe, PTT, priorité Chef, métriques.
- Canal audio : Opus mono 48 kHz, trames 10 ms en mode ULTRA.
- Reconnexion : conserver la session logique pendant la migration de transport.
- v0.8.0 : service Android au premier plan de type `connectedDevice`.
- Notification permanente **FOSA · LIVE actif** pendant une session locale.
- Le service démarre pendant que l'application est visible, conformément aux restrictions Android modernes.
- Passer vers une autre application ne déclenche pas l'arrêt du transport Nearby.

## Arrière-plan et microphone

Le service v0.8.0 protège la continuité du processus et du transport local. Le futur moteur audio natif devra déplacer la capture micro dans ce service et déclarer aussi le type `microphone` pour garantir la capture audio native en arrière-plan. La PWA seule ne peut pas garantir ce comportement sur tous les navigateurs mobiles.

## iOS

- MultipeerConnectivity / Network.framework pour découverte et transport local.
- AVAudioEngine pour la chaîne audio faible latence.
- Même contrat de messages que la couche Android.

## Contrat avec la PWA

Le pont natif expose :
- transport.list()
- transport.connect(peer)
- transport.sendControl(payload)
- transport.sendAudio(frame)
- transport.onState(...)
- transport.onAudio(...)

La PWA actuelle reste le fallback Internet/WebRTC.

## Local group discovery

The Android local transport uses Nearby Connections with `P2P_CLUSTER`.

User flow:
- Host: **CRÉER UN GROUPE**
- Other devices: **REJOINDRE**
- Nearby FOSA groups are discovered automatically.
- The host keeps advertising after a peer joins so multiple musicians can connect to the same group.
- `broadcast()` sends control/audio payloads to all currently connected peers.

The browser PWA cannot expose the same automatic LAN/Bluetooth discovery reliably. `local.html` remains a compatibility/fallback implementation; the native Android shell is the preferred layer for persistent local operation.
