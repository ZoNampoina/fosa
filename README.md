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

## v0.9.5 — FOSA Web / Control / Talkback

Le lanceur mobile affiche maintenant un QR HTTPS de confiance pour rejoindre le PC, écouter et activer le micro sans certificat à installer. Le régisseur autorise **Micro** dans MATRIX ; le musicien touche **ACTIVER MON MICRO**, choisit Tous, un profil ou une personne, puis maintient **TALK**. La parole est coupée au relâchement et lors d’une révocation de permission.

Internet sert à charger la page et établir la session chiffrée ; l’audio et les commandes en session passent directement sur le LAN. Un QR distinct conserve l’écoute LAN sans Internet. Le bridge téléchargé sur le PC doit être mis à jour, puis son nouveau QR scanné.

Cette livraison concerne le contrôle et le talkback. Le monitoring actuel utilise toujours le moteur Python / Opus à trames de 20 ms : **il n’est pas le futur moteur FOSA Low-Latency**, et sa latence physique reste à mesurer. L’évolution du moteur de monitoring sera traitée séparément, sans annoncer de délai non mesuré.

[Activer le talkback](audio-bridge/README.md#activer-le-talkback-mobile) · [Tests et limites](audio-bridge/AUDIT.md).


## FOSA Low-Latency · v0.9.6

Un second moteur de monitoring PCM stéréo 48 kHz, 16 bits, transmet des blocs de 5 ms directement sur le LAN. Choisir **FOSA Low-Latency · PCM** avant ÉCOUTER ou dans Paramètres ; le mode Stable Opus reste disponible par utilisateur. Le nouveau lanceur `audio-bridge/start-low-latency-windows.cmd` propose le PCM dans le QR sécurisé et demande un buffer de capture ASIO de 128 échantillons.

La restitution utilise AudioWorklet, avec un buffer ciblé de 5/10/20/40 ms et des compteurs de sous-alimentation, de paquets tardifs et de données abandonnées. L’audio PCM nécessite le QR HTTPS sur mobile et consomme environ 1,54 Mbit/s par auditeur, hors en-têtes réseau. Le talkback et les permissions MATRIX sont communs aux deux moteurs.

**5 ms est la durée d’un bloc, pas une latence audio mesurée.** Cette version utilise encore le navigateur et le transport WebRTC SCTP/DTLS ; elle ne constitue pas une application audio native UDP/AAudio/CoreAudio. Le système et la sortie audio ajoutent leurs propres buffers. Les essais logiciels ne valident pas une latence garantie avec la MR18 ou des téléphones physiques.

## v0.9.7 — Régisseur PC direct

`audio-bridge/start-low-latency-windows.cmd` ouvre directement **Régisseur · PC serveur** dans LIVE, sans code session à saisir. Le profil audio du PC possède sa permission micro dès sa création. **ACTIVER MON MICRO** établit sa liaison puis demande l’accès au microphone dans le navigateur ; maintenir **TALK** transmet aux destinataires choisis. **Source du micro** permet de choisir un casque, un micro USB ou le micro intégré si le périphérique par défaut est occupé par le pilote audio.

Le master du PC démarre à 0 % pour parler sans ouvrir son monitoring ; le remonter permet d’écouter avec des écouteurs. MATRIX reste accessible pour autoriser les micros des musiciens. Le profil PC et ses réglages sont conservés au redémarrage, y compris une permission révoquée dans MATRIX. Télécharger le bridge mis à jour et relancer ce lanceur est nécessaire ; mettre à jour seulement la page Web ne modifie pas le serveur installé.
