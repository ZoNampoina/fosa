# FOSA

FOSA est un intercom/talkback temps réel pour musiciens.

## v0.5.0

### Audio
- Push-to-Talk et parler en continu
- flux RTP maintenu chaud : TALK ouvre/ferme un gain, sans redémarrer la piste
- profils AUTO / ULTRA / LOW / STABLE
- jitter buffer ciblé par profil
- Opus mono 48 kHz avec ptime 10 ms en ULTRA/LOW
- noise gate AudioWorklet + AUTO GATE
- profil CASQUE / HAUT-PARLEUR
- anti-larsen
- priorité Chef
- estimation de latence audio

### Routage
- Tous
- par rôle
- groupes rapides : RYTHMIQUE / CHANT / SONO
- participant individuel

### Réseau
- WebRTC DIRECT/LAN prioritaire
- TURN en secours
- ICE restart sur changement Wi-Fi / données mobiles
- mode LOCAL hors Internet : `local.html`, pairage manuel offre/réponse
- architecture native Android/iOS préparée pour Nearby / Bluetooth / Wi-Fi local

### Sécurité TURN
- Edge Function Supabase `turn-credentials` déployée
- le frontend préfère les credentials éphémères si le fournisseur serveur est configuré
- fallback TURN statique conservé uniquement pour les tests actuels
- secrets serveur attendus : `TURN_CREDENTIALS_URL` et `TURN_API_TOKEN`

### Native
Voir `native/README.md` et `native/transport-contract.md`.

Le Bluetooth appareil-à-appareil n'est pas disponible comme transport audio direct depuis la PWA. Les squelettes Android/iOS sont présents dans `native/` pour cette couche.


## v0.7.0
- écran de préparation : choix Héberger/Rejoindre, nom, rôle et scan des hébergements
- interface LIVE séparée avec switches OUT / IN / MIC
- interface MENU séparée pour session et réglages avancés
- publication automatique des sessions hôtes via Supabase Presence
