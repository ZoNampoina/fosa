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


## Local group discovery (v0.6.0)

The Android local transport now uses Nearby Connections with `P2P_CLUSTER`.

User flow:
- Host: **CRÉER UN GROUPE**
- Other devices: **REJOINDRE**
- Nearby FOSA groups are discovered automatically.
- No camera, QR, SDP copy/paste, or Internet signalling is part of the native local flow.
- The host keeps advertising after a peer joins so multiple musicians can connect to the same group.
- `broadcast()` sends control/audio payloads to all currently connected peers.

The browser PWA cannot expose the same automatic LAN/Bluetooth discovery reliably. `local.html` remains a compatibility/fallback implementation; the native Android shell must bridge the UI to `NearbyTransport`.

Android permissions and Google Play Services Nearby requirements must be declared by the native application before packaging.
