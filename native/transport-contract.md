# FOSA transport contract

## Etats
idle | discovering | connecting | connected | migrating | failed

## Transports
nearby-bluetooth
nearby-wifi
lan-webrtc
internet-direct
turn-relay

## Règle de bascule
- Ne pas couper le chemin actif tant qu'un nouveau chemin n'est pas connected.
- Une fois le nouveau chemin prêt, dupliquer le contrôle pendant 250 ms.
- Basculer l'audio sur le chemin ayant la plus faible latence mesurée.
- Conserver le chemin précédent 1 seconde comme secours si possible.

## Contrôle
{
  "type": "ptt|presence|target|priority|ping",
  "peerId": "...",
  "role": "Chef",
  "target": "all|role:Piano|group:rythmique|peer:...",
  "seq": 1,
  "ts": 0
}

## Audio
- Opus mono
- 48 kHz
- ULTRA : ptime 10 ms
- LOW : ptime 10 ms
- STABLE : ptime 20 ms + FEC
- numéro de séquence + timestamp obligatoire
