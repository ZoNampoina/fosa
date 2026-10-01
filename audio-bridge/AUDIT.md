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
