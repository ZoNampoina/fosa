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


## v0.8.0
- profils, rôles et groupes personnalisés
- console Chef et PTT direct par destination
- favoris, appel Chef, verrouillage Concert et modes Répétition/Concert
- test pré-live, QR de session, historique et qualité simplifiée
- commandes distantes IN/OUT/MIC protégées par verrouillage admin local
- restauration de session, état I/O mémorisé et délai de reconnexion participants
- notification de session active côté PWA, avec service Android persistant prévu pour l'arrière-plan natif

## v0.9.0 — FOSA Audio Network (expérimental)

Nouveau module intégré : AUDIO, MON MIX, LIVE AUDIO, MATRIX, APPAREILS et DIAGNOSTIC. Capture native 18 entrées, mixage par profil et WebRTC LAN via un bridge local. L’application signale explicitement les sources absentes et les mesures indisponibles.

**Installer le bridge sur le PC USB de la MR18 : [guide Windows / LAN](audio-bridge/README.md).** Essais MR18, téléphones et latence physique requis avant usage live critique. Le talkback FOSA existant est conservé. Cette version ne met pas à jour l’APK Android.


## v0.9.4 — connexion LAN pour musiciens

Lancement `audio-bridge/start-mobile-windows.cmd` : détection du réseau physique, vérification du port et du pare-feu Windows, puis console locale avec QR et code session. Le musicien scanne, saisit son nom et touche ÉCOUTER ; le monitoring HTTP ne demande ni micro ni certificat. La capture Windows exige ASIO et 18 entrées à 48 kHz. La réception des paquets et la lecture navigateur sont distinguées de la connexion du driver.

[Guide de démarrage](audio-bridge/README.md) · [Audit et validation](audio-bridge/AUDIT.md). Le test MR18/Windows et les essais sur téléphones physiques restent indispensables ; le talkback mobile sécurisé et un installateur Windows autonome sont des étapes suivantes.
