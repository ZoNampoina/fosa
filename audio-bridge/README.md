# FOSA Audio Network — v0.9.4 expérimentale

Cette version fournit un bridge local exécutable en Python, la capture PortAudio/ASIO, un moteur de mixage 18 → 2 par profil et une liaison WebRTC/Opus sur le réseau local. Elle s’intègre à FOSA sans remplacer son talkback existant.

**Ce n’est pas encore une version validée pour un concert.** La MR18, le pilote Windows, les téléphones et la latence physique doivent être testés sur place. Aucun vumètre ni délai n’est simulé dans l’application.

## Démarrer FOSA pour les musiciens

1. Relier la MR18 au PC par USB. Le pilote Midas ASIO 64 bits doit être installé et la console réglée à 48 kHz. Le PC et les musiciens doivent utiliser le même réseau local ; le PC peut être relié au routeur en RJ45.
2. Télécharger le dépôt complet et l’extraire. Double-cliquer sur **audio-bridge/start-mobile-windows.cmd**. Python 3.11/3.12 64 bits reste un prérequis de cette version. Le lanceur installe ses dépendances au premier démarrage, sans commande à saisir.
3. Windows peut demander une autorisation pour créer les règles de pare-feu FOSA. Seul ce réglage est élevé ; le bridge audio reste lancé avec les droits ordinaires. Les règles sont limitées au programme Python concerné, aux adresses LAN détectées et au sous-réseau local. Un refus est affiché ; FOSA ne prétend pas avoir configuré le pare-feu.
4. La console locale s’ouvre **après** une réponse réelle du serveur HTTP. Elle affiche le QR, l’adresse exacte, le code session, l’interface réseau et les contrôles de démarrage. La MR18/Midas USB ASIO avec au moins 18 entrées est ouverte automatiquement lorsqu’elle est disponible.
5. Sur Android/iPhone/tablette : même Wi-Fi → scanner le QR → saisir son nom et choisir son profil → **ÉCOUTER**. Le code session est déjà rempli. Aucun accès au micro ni certificat n’est demandé pour cette écoute.
6. Ajuster le master et **MON MIX**. Le régisseur règle les permissions dans MATRIX. Chaque appareil garde son propre jeton et son propre mix.

Conserver la fenêtre FOSA ouverte sur le PC. **Fermer le navigateur du serveur n’arrête pas le bridge** ; fermer sa fenêtre de programme ou arrêter le PC l’arrête. Si le port est déjà occupé, le lanceur l’indique avant de modifier le pare-feu.

### Ce que vérifient les diagnostics

- L’adresse proposée vient d’un adaptateur physique Wi-Fi/Ethernet avec IPv4 LAN. Les interfaces VPN, WARP, Tailscale, virtuelles et Bluetooth sont exclues. Si plusieurs LAN sont présents, choisir le réseau à partager dans la console ; les QR sont régénérés pour cette adresse.
- Le contrôle HTTP depuis le PC confirme que **ce bridge** répond sur l’adresse affichée. Il ne prouve pas que le téléphone peut passer le pare-feu ou l’isolation du routeur. La première requête distante est affichée séparément.
- La vérification des règles Windows ne garantit pas l’absence d’un blocage par un antivirus, une politique d’entreprise ou le point d’accès. Si le téléphone n’envoie aucune requête, FOSA affiche les causes possibles sans en inventer une.
- Le client distingue liaison WebRTC, arrivée de paquets audio, source absente et sortie navigateur suspendue. Le diagnostic affiche aussi RTT, jitter, pertes, buffer de réception et XRUN. Ces valeurs ne sont pas la latence audio physique.
- Le sélecteur ASIO demande les canaux 0 à 17 dans l’ordre. Dans DIAGNOSTIC, **Tester le mapping des entrées** observe où arrive le signal d’une seule entrée physique. Le routage USB dans la MR18 peut modifier cette correspondance.

Si la page s’ouvre mais pas le son, vérifier la réception des paquets et l’état de la sortie dans DIAGNOSTIC. Un blocage UDP peut empêcher WebRTC alors que TCP permet d’ouvrir la page. Si la sortie est suspendue après un rechargement ou un retour d’arrière-plan, toucher ÉCOUTER à nouveau peut être nécessaire.

### Mode PC local et micro talkback

`start-windows.cmd` conserve le mode local, sur `http://127.0.0.1:8765`. La console automatique n’est accessible que depuis le PC serveur : adresse loopback, origine locale et en-tête spécifique sont vérifiés. Les appareils distants ne reçoivent jamais la clé régisseur par ce mécanisme.

Le mode mobile simple est **écoute seule**. Sur un HTTP LAN non sécurisé, le micro talkback et le verrouillage de veille peuvent être indisponibles ; les boutons l’indiquent clairement. L’accès au micro sur mobile exige un contexte sécurisé reconnu par le navigateur. L’ancien `start-lan-windows.cmd` reste un mode HTTPS avancé avec certificat local, réservé aux essais du micro : il ne fait pas partie du parcours QR → ÉCOUTER. L’intégration d’un HTTPS de confiance transparent ou d’un client natif reste à réaliser pour le talkback mobile.

Le QR contient le code musiciens dans le fragment du lien, jamais la clé régisseur. Partager le QR donne accès à la session d’écoute. L’interface locale et l’audio fonctionnent sans Internet après la première installation. Ne pas rediriger le port du PC vers Internet.

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
- Le QR et le lien NFC contiennent l’adresse LAN, la session, le rôle proposé et le code musiciens, **jamais la clé régisseur**. Les liens `fosa://` et la programmation NFC native ne sont pas enregistrés par cette version.
- TALKBACK LAN utilise un micro autorisé et une commande maintenue. Le serveur ferme cette commande après 1,5 seconde sans renouvellement. Les destinations LAN proposées sont « Tous » et les rôles musicaux ; le contrat serveur accepte aussi un identifiant de profil.
- Un limiteur de crête de sortie évite le dépassement numérique, sans remplacer le réglage physique du volume ni une protection auditive dédiée.

## Ce qui est réel, ce qui reste à valider

| Élément | État v0.9.4 |
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

Les tests vérifient l’isolation des mixes, les gains/pan, mute/solo/ducking, les bornes de saisie, les permissions et la persistance, puis le transport WebRTC stéréo en boucle locale. Le test WebRTC injecte un signal connu exclusivement dans son banc. Aucun mode démo audio n’est exposé par le produit.

## Références techniques

- [Midas MR18](https://midasconsoles.com/en/products/0605-aaf)
- [sounddevice : installation ASIO sous Windows](https://python-sounddevice.readthedocs.io/en/0.5.3/installation.html)
- [aiortc : API WebRTC](https://aiortc.readthedocs.io/en/latest/api.html)

## Vérification et publication

Le workflow Verify FOSA Audio lance les tests du bridge sous Linux et Windows, vérifie le helper PowerShell et exerce les navigateurs avec une source numérique connue uniquement dans le banc de test. Les vues PC, Android et tablette sont vérifiées avec Chromium ; iPhone/iPad avec WebKit et des vues mobiles. **Ces émulations ne remplacent pas des appareils physiques.** Les tests navigateur utilisent une vraie origine HTTP LAN non sécurisée, les endpoints du bridge et son flux Opus.

GitHub Pages dépend maintenant de ces vérifications. Les anciens workflows de migration ne sont pas déclenchés par les modifications de ce module. Le bridge Windows doit être actualisé sur le PC : publier la page web ne remplace pas les fichiers déjà téléchargés.

Voir [AUDIT.md](AUDIT.md) pour les constats et les limites de validation.
