# FOSA Audio Network — v0.9.0 expérimentale

Cette version fournit un bridge local exécutable en Python, la capture PortAudio/ASIO, un moteur de mixage 18 → 2 par profil et une liaison WebRTC/Opus sur le réseau local. Elle s’intègre à FOSA sans remplacer son talkback existant.

**Ce n’est pas encore une version validée pour un concert.** La MR18, le pilote Windows, les téléphones et la latence physique doivent être testés sur place. Aucun vumètre ni délai n’est simulé dans l’application.

## Premier essai sur le PC Windows

1. Installer le pilote USB/ASIO officiel correspondant à la MR18 et à Windows, depuis le support Midas. Relier la console en **USB**, régler son routage USB et sa fréquence à **48 kHz**.
2. Installer **Python 3.11 ou 3.12, 64 bits**. Télécharger le dépôt complet FOSA : GitHub → Code → Download ZIP. Extraire le ZIP ; garder `audio-bridge` à côté de `network.html`, `network.js` et `network.css`.
3. Double-cliquer sur `audio-bridge/start-windows.cmd`. La première installation nécessite Internet. Le programme reste ensuite local ; laisser sa fenêtre ouverte. Si le navigateur arrive trop tôt, actualiser une fois le bridge démarré.
4. Ouvrir `http://127.0.0.1:8765/network.html`. La fenêtre du bridge affiche un **code musiciens** et une **clé régisseur privée**. Ne pas partager la clé régisseur.
5. Dans AUDIO → Console régisseur, saisir la clé privée. Actualiser les interfaces, sélectionner **MR18 / ASIO**, buffer **256**, puis OUVRIR 18 ENTRÉES. Une MR18 compatible est aussi sélectionnée automatiquement ; aucune autre interface n’est ouverte sans choix manuel.
6. Nommer les canaux. Dans un autre navigateur ou sur le téléphone, rejoindre avec le code musiciens. Choisir MON MIX → ÉCOUTER. Commencer avec le volume du casque bas, puis augmenter progressivement.

Le navigateur ne voit pas les 18 entrées ASIO. Il reçoit uniquement le mix stéréo calculé par le bridge. Le code active `SD_ENABLE_ASIO` avant d’importer sounddevice ; les wheels Windows récentes incluent la DLL PortAudio compatible ASIO. Si aucun pilote ASIO MR18 n’apparaît, vérifier le pilote officiel, l’architecture 64 bits et la disponibilité du périphérique dans un autre logiciel audio.

## Téléphones / tablettes sur le LAN

Le mode PC ci-dessus écoute seulement sur le PC. Pour ouvrir le service au réseau local :

1. Fermer le premier bridge. Relier de préférence le PC au routeur en RJ45. Connecter les téléphones au même réseau Wi-Fi ; désactiver l’isolation des clients sur le point d’accès si nécessaire.
2. Double-cliquer sur `start-lan-windows.cmd`. Saisir l’IPv4 privée du PC, visible avec `ipconfig`, par exemple `192.168.1.10`. Réserver cette adresse dans le routeur pour qu’elle reste stable.
3. Le script crée un certificat HTTPS local. Copier **uniquement** `certs/fosa-local-ca.crt` vers les appareils personnels qui rejoindront FOSA. Garder tous les `.key` privés sur le PC.
4. Installer cette autorité locale comme certificat de confiance sur ces appareils. Sur iPhone/iPad, l’installation du profil doit être suivie de l’activation de sa confiance dans les réglages de certificats. Sous Android, utiliser l’installation de certificat CA dans les réglages de sécurité ; l’intitulé varie selon le fabricant. Retirer cette autorité des appareils après les essais si elle n’est plus utilisée. L’application ne modifie jamais automatiquement la confiance des certificats.
5. Autoriser Python dans le pare-feu Windows **sur le réseau privé**. Ne pas rediriger ce port vers Internet. Autoriser le port TCP 8765 pour l’interface et les ports UDP utilisés par WebRTC via la règle d’application Python.
6. Sur le téléphone, ouvrir `https://ADRESSE_DU_PC:8765/network.html`. Le certificat doit être reconnu pour que les fonctions du navigateur, notamment le micro, soient disponibles.
7. Rejoindre avec le code musiciens puis toucher ÉCOUTER. Dans MATRIX, le régisseur peut autoriser le micro, verrouiller le mix et limiter les canaux.

Dans FOSA hébergé, AUDIO peut aussi se connecter à cette adresse HTTPS. Certains navigateurs demandent l’accès au réseau local ou bloquent l’accès depuis un site public. Dans ce cas, ouvrir directement l’adresse HTTPS locale du bridge. Ne pas désactiver les protections du navigateur.

**Sans Internet :** ouvrir l’adresse locale du bridge. Son interface, ses réglages, sa signalisation et son audio n’utilisent ni CDN ni Supabase. L’application FOSA publique et son ancien talkback conservent leurs dépendances antérieures. La session audio LAN fonctionne indépendamment.

## Commandes manuelles

```sh
python -m venv .venv
# Activer le virtualenv selon votre système
python -m pip install -r requirements.txt
python bridge.py
```

Mode réseau après génération et installation du certificat :

```sh
python make-certificate.py --ip 192.168.1.10
python bridge.py --host 0.0.0.0 --cert certs/server.crt --key certs/server.key --public-url https://192.168.1.10:8765
```

Sous Linux, installer PortAudio via le gestionnaire de paquets du système. Si la bibliothèque native manque, le bridge sert l’interface mais indique explicitement que la capture est indisponible. Le fichier `requirements-tested-linux.txt` consigne les dépendances du banc logiciel ; il n’établit pas une validation Windows.

## Commandes et sauvegardes

- Les 18 entrées conservent numéro, nom et type. Vumètres RMS/peak en dBFS, silence et clipping proviennent des échantillons capturés.
- Chaque musicien possède son profil, son mix, son master, ses mute et son panoramique. Le solo est temporaire ; il ne modifie pas les autres profils et n’est pas sauvegardé. Les presets de rôle s’appuient sur les noms et types des canaux. Ils doivent être ajustés à l’orchestre.
- Sauvegarder/restaurer conserve un instantané sur cet appareil. Les réglages courants sont aussi stockés par le bridge dans le dossier utilisateur `.fosa-audio`. Le jeton d’accès au profil reste dans le navigateur. Pas de synchronisation de comptes Supabase dans cette version.
- MATRIX applique les autorisations côté serveur : assignations, verrouillage du mix, émission et réception talkback. Un simple choix du rôle « Régisseur » ne donne aucun droit.
- Le QR et le lien NFC contiennent l’adresse HTTPS, la session et le rôle proposé, **pas les clés**. Le code musiciens reste nécessaire. Les liens `fosa://` et la programmation NFC native ne sont pas enregistrés par cette version.
- TALKBACK LAN utilise un micro autorisé et une commande maintenue. Le serveur ferme cette commande après 1,5 seconde sans renouvellement. Les destinations LAN proposées sont « Tous » et les rôles musicaux ; le contrat serveur accepte aussi un identifiant de profil.
- Un limiteur de crête de sortie évite le dépassement numérique, sans remplacer le réglage physique du volume ni une protection auditive dédiée.

## Ce qui est réel, ce qui reste à valider

| Élément | État v0.9.0 |
|---|---|
| Écrans, 18 canaux, réglages locaux, profils, permissions serveur | Implémentés et testés logiciellement |
| Capture 18 entrées à 48 kHz, détection ASIO, buffer, reconnexion | Code réel ; essai MR18/Windows nécessaire |
| Mix stéréo par utilisateur, mute/solo/pan, ducking LAN | Implémentés ; DSP testé |
| WebRTC LAN chiffré, Opus stéréo | Test de transport local réussi avec signal de test dans le banc uniquement |
| Interface web Android/iPhone/tablette | Responsive ; essais sur appareils physiques nécessaires |
| PCM réseau / Full 18CH sur un client technicien | Non implémenté ; transport actuel stéréo Opus uniquement |
| Auto-priorité Ethernet | Le routage appartient à l’OS ; pas de forçage depuis le navigateur |
| Détection Wi-Fi/Ethernet/Bluetooth | Affichée seulement quand l’API navigateur fournit l’information |
| Latence audio bout-en-bout | Non mesurée tant qu’un test physique n’a pas été réalisé |
| Arrière-plan Android/iOS | Selon les restrictions du navigateur ; aucune garantie PWA |
| Application native, service Windows installable, APK/iOS actualisés | Non livrés dans cette étape |
| Synchronisation cloud des presets/comptes | Non implémentée ; profils locaux au serveur |

Le moteur Python utilise des files bornées et abandonne les trames trop anciennes. Il ne remplace pas un moteur natif C++ temps réel déterministe. Le paquet Opus de cette implémentation dure **20 ms**, avant capture, réseau, buffers et restitution. Elle ne promet donc ni 5 ms bout-en-bout ni « zéro latence ». La montée en charge multi-utilisateur doit être mesurée sur le PC cible.

## Vérification avant un concert

1. Brancher 18 sources connues ou tester les entrées une par une ; vérifier les noms, l’absence d’inversion et les niveaux.
2. Connecter deux utilisateurs avec des mixes différents ; vérifier qu’un mute/solo/modification ne change que le destinataire voulu. Tester la matrice et le verrouillage.
3. Tester un micro autorisé puis interdit, son relâchement, sa destination et le ducking. Vérifier qu’aucun micro n’est ouvert après suspension de l’application.
4. Débrancher/rebrancher l’USB, perdre/rétablir le Wi-Fi, couper Internet tout en conservant le LAN. Vérifier les états et la restauration.
5. Mesurer physiquement le décalage entre une impulsion directe et la sortie du téléphone, enregistrées simultanément sur deux pistes. À 48 kHz, délai = nombre d’échantillons / 48 en ms.
6. Faire un essai prolongé avec tous les musiciens. Surveiller XRUN, pertes, jitter, charge CPU et température du PC. Augmenter les buffers en cas de craquements.

Le bouton TESTER LA LATENCE mesure le temps aller-retour HTTP et affiche le RTT WebRTC séparément. Il ne transforme pas ce ping en fausse mesure audio. OPTIMISER ajuste la cible de réception et propose un buffer de capture ; il ne coupe pas automatiquement le son pour changer le pilote.

Changer de page dans le panneau ne recrée pas la connexion. Fermer le panneau FOSA garde l’iframe audio active. Un vrai rechargement, une fermeture du navigateur ou une suspension OS peuvent couper le son ; la reconnexion est tentée ensuite. Les mises à jour du service worker attendent la fermeture des onglets au lieu de prendre la main pendant le live.

## Tests logiciels

Depuis la racine du dépôt :

```sh
python -m unittest discover -s audio-bridge/tests -v
```

Sept tests vérifient l’isolation des mixes, les gains/pan, mute/solo/ducking, les bornes de saisie, les permissions et la persistance, puis le transport WebRTC stéréo en boucle locale. Le test WebRTC injecte un signal connu exclusivement dans son banc. Aucun mode démo audio n’est exposé par le produit.

## Références techniques

- [Midas MR18](https://midasconsoles.com/en/products/0605-aaf)
- [sounddevice : installation ASIO sous Windows](https://python-sounddevice.readthedocs.io/en/0.5.3/installation.html)
- [aiortc : API WebRTC](https://aiortc.readthedocs.io/en/latest/api.html)
