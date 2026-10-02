# Audit FOSA Audio Network — 1 octobre 2026

Base examinée : `f690ef4`, branche principale de ZoNampoina/fosa. Le bridge, le moteur DSP, les interfaces Network, les lanceurs Windows, la documentation, les tests et les workflows de publication ont été examinés. L’ancien talkback cloud et les squelettes natifs sont conservés.

## Blocages constatés dans le code

| Point | État à l’audit | Correction de cette étape |
|---|---|---|
| Démarrage mobile | Premier lancement séparé imposé ; navigateur ouvert avant que le port écoute | Installation commune, attente d’une réponse HTTP identifiée avant ouverture |
| Réseau | Première adresse privée choisie par nom ; absence de contrôle physique et de métrique | Adaptateur physique, passerelle et métrique ; exclusion VPN/virtuel ; choix entre plusieurs LAN |
| Pare-feu | Commandes non vérifiées, règles ouvertes à toute adresse distante, tout le bridge élevé | Helper seul élevé, règles limitées au sous-réseau et au programme, état vérifié et erreur affichée |
| QR | Création manuelle après accès à la console ; code encore à saisir | QR immédiat sur la console locale, code prérempli dans le lien musicien |
| iPhone / lecture | `audio.play()` appelé après la signalisation, sans activation de sortie dans le geste initial | AudioContext repris lors de ÉCOUTER, avant les attentes réseau ; sortie et paquets contrôlés |
| Pilote | Détection automatique fondée sur le mot MR18 dans le nom | Reconnaissance des interfaces Midas USB ASIO 18 entrées ; identité matérielle toujours à vérifier sur place |
| Connexion capture | État surtout fondé sur l’activité du stream | Pilote ASIO sous Windows, nombre et fréquence réellement ouverts, fraîcheur de la capture |
| Restauration | Le mix local pouvait écraser le mix courant du serveur à l’ouverture | Mix courant du serveur conservé pour le profil restauré |
| Publication | Déploiement Pages direct après un push | Déploiement subordonné aux tests bridge et navigateur |

Ces constats expliquent des points de fragilité. Ils ne permettent pas d’affirmer quelle règle Windows ou quel routeur a bloqué les essais précédents : le PC et les téléphones de l’utilisateur ne sont pas accessibles depuis ce banc.

## Audio et mesures

Le DSP traite une matrice 18 × 2 propre à chaque profil. L’ordre demandé à ASIO est explicitement 0…17. Les RMS/peaks sont calculés séparément à partir de chaque colonne capturée. Un test logiciel de CH3 et un test DSP sur les 18 entrées couvrent l’absence de mélange entre colonnes ; seule une source physique isolée permet de confirmer le routage USB réel de la MR18.

Les paquets Opus durent 20 ms. La latence driver, le buffer de capture, le jitter buffer et le RTT sont des éléments distincts. Le temps d’encodage, le délai réseau à sens unique, le convertisseur de sortie et la latence bout-en-bout ne sont pas mesurés automatiquement dans cette étape. Le test physique à deux pistes décrit dans l’interface reste la référence.

## Portée de la livraison

Cette étape vise le monitoring LAN sans certificat. Les permissions, mixes personnels, mute, solo, pan et talkback déjà présents sont préservés. Le micro sur une origine mobile HTTP non sécurisée est explicitement indisponible. Le choix d’un HTTPS de confiance ou d’un client natif, la mesure physique, un service/tray et un paquet Windows autonome restent à réaliser après le premier essai réussi.

Le serveur est un processus indépendant du navigateur et reste actif si celui-ci est fermé. Cette propriété n’équivaut pas à un service Windows ni à un démarrage automatique après redémarrage.

## Validation

Les tests logiciels et les résultats GitHub Actions doivent être lus avec leur contexte : les signaux générés existent exclusivement dans les fixtures de test, jamais dans le produit. Chromium/WebKit avec une vue mobile vérifient un navigateur, pas le Wi-Fi, les écouteurs, la politique iOS ou le driver ASIO d’un appareil réel.

Un test Windows crée une vraie `.venv` et vérifie que les règles pare-feu ciblent l’image du processus serveur. Le `python.exe` de la `.venv` est un lanceur qui peut rediriger vers le runtime de base ; son chemin seul ne suffit pas pour le filtre d’application Windows.

À confirmer sur place : MR18 ASIO 64 bits, fréquence réelle, Input 1/2/3 puis les autres entrées, première connexion Android et iPhone, deux mixes différents, stabilité prolongée, perte et retour du Wi-Fi, écouteurs filaires et mesure physique du délai.

## Références de conception

- WebKit, politique de capture et lecture des flux : https://webkit.org/blog/7763/a-closer-look-into-webrtc/
- API WebRTC W3C : https://w3c.github.io/webrtc-pc/
- Sélecteurs ASIO sounddevice : https://python-sounddevice.readthedocs.io/en/0.5.5/api/platform-specific-settings.html
- Cmdlets réseau Windows : https://learn.microsoft.com/en-us/powershell/module/netadapter/get-netadapter

## Complément du 2 octobre 2026 — Web / Control / Talkback v0.9.5

Base : `2d8cba5`, version mobile v0.9.4. Le blocage confirmé par le code est l’accès au microphone sur une origine HTTP LAN mobile. Autoriser un micro dans MATRIX ne peut pas lever cette restriction du navigateur. Installer un certificat auto-signé ne constitue pas un parcours simple et fiable sur Android/iOS.

La page musicien est désormais chargée depuis GitHub Pages en HTTPS de confiance. La signalisation initiale réutilise le Realtime Supabase existant avec chiffrement applicatif P-256 / HKDF-SHA256 / AES-GCM. Le QR contient l’identité publique du bridge et l’identifiant du démarrage, jamais sa clé privée ni la clé régisseur. Les échanges ont des clés distinctes pour les demandes et les réponses ; les requêtes anciennes sont rejetées et les retransmissions de join/offer restent idempotentes pendant toute leur fenêtre de validité.

Après négociation, le navigateur ferme son relais et utilise un canal de données WebRTC fiable, lié au profil authentifié, pour le mix, les permissions observées, les vumètres et TALK. Les routes d’administration ne sont pas disponibles par ces transports musiciens. Le micro utilise le WebRTC audio direct déjà présent, avec permission serveur, destinations Tous/profil/personne, baisse du mix et coupure au relâchement. Un numéro de commande empêche un ancien appui TALK d’annuler un relâchement plus récent.

Le premier banc sécurisé, commit `987b072`, a passé les tests Windows/Linux et les tests Chromium/WebKit. La signalisation utilise le véritable service Supabase ; les fichiers HTTPS proviennent du commit en test. La capture utilise l’API réelle getUserMedia avec le périphérique de test du navigateur. Le banc vérifie l’émission reçue, les trois modes de destination, l’absence de retour sur l’émetteur, le relâchement, la révocation de permission et le contrôle en session avec nouvelles connexions au relais bloquées. L’écoute HTTP, les mixes indépendants, la restauration et la reconnexion ont également passé leurs tests.

La publication est conditionnée aux vérifications du commit final. La page HTTPS possède un chemin versionné pour éviter de réutiliser les anciens fichiers du service worker. Le PC doit recevoir les nouveaux fichiers du bridge ; une simple mise à jour de la page publique ne remplace pas le serveur déjà téléchargé.

Limites inchangées : pas d’essai sur MR18 ou téléphones physiques depuis ce banc, pas de validation de concert ni de mesure physique bout-en-bout. Internet est nécessaire pour charger et rétablir une session HTTPS ; le QR LAN permet une écoute hors Internet. Le moteur de monitoring Python / Opus 20 ms est conservé pour les essais. **FOSA Low-Latency n’est pas livré dans cette étape.**


## v0.9.6 · moteur PCM

Ajout d’une voie PCM stéréo 5 ms, par utilisateur, distincte du monitoring Opus. Capture ASIO, authentification et MATRIX restent communes. Le callback réveille le traitement par bloc plutôt que par minuterie Windows. Les files serveur et client sont bornées, le stockage audio du lecteur est alloué à l’avance et la cadence de lecture corrige une dérive modérée des horloges. Les lectures HTTP Opus restent prises en charge.

Limites : Python, JavaScript, MessagePort, SCTP et l’OS ne sont pas des couches audio temps réel garanties ; une application native serait nécessaire pour contrôler plus précisément la restitution. Aucun test actuel ne prouve une latence physique ni une capacité de groupe sur matériel. Le talkback reste Opus 20 ms. Le débit PCM et les interruptions doivent être testés sur le réseau des musiciens avant un concert.
