# FOSA Mobile 0.13.0 — démarrage local

## Android sur le même Wi-Fi/Ethernet/hotspot

1. Installer l’APK de la 0.13.0 sur l’hôte et les invités. Autoriser le micro pour parler.
2. Hôte : CREATE SESSION → nom du groupe → CREATE SESSION.
3. Invité : JOIN SESSION → six chiffres → JOIN WITH CODE. NEARBY permet aussi de sélectionner l’hôte ; SCAN QR lit son QR court.
4. Attendre CONNECTED et SRTP CONNECTED, puis tester TALK dans les deux sens avec des écouteurs filaires.
5. Members : User/Group, autorisations parole/écoute et ajout d’appareils. Internet peut être coupé dès avant la première étape.

## Sans routeur

Wi-Fi doit être activé. Sans LAN, FOSA tente Wi-Fi Direct natif. Accorder Nearby devices sur Android 13+ ou localisation sur Android 12 et antérieurs. Android peut afficher son propre consentement de connexion. FOSA pilote les APIs P2P ; aucun réglage Wi-Fi Direct manuel n’est demandé par l’app.

Si P2P échoue : sur l’hôte, CREATE LOCAL FOSA NETWORK. Lire le SSID et le mot de passe ; connecter les invités à ce Wi-Fi depuis leur système. Créer/rejoindre ensuite la session par code. Le hotspot conserve sa réservation dans le service ; fermer FOSA/LEAVE ferme le réseau qu’il a créé. Un hotspot système préexistant peut aussi être utilisé.

## Un seul scan depuis l’hôte

Invité : JOIN → DISPLAY PAIRING QR, laisser l’écran ouvert. Hôte : Members → ADD DEVICE. Scanner une fois. Invitation valable 90 secondes. Le client rejoint automatiquement ; aucun QR de réponse. L’Android invité doit rester visible pour que le service audio puisse démarrer selon les règles Android.

## Web PC/iPhone/tablette depuis l’Android

1. Rejoindre le Wi-Fi/hotspot/groupe exposé par l’hôte. Le Web ne crée pas de groupe P2P.
2. Hôte : Members → WEB ACCESS, copier l’URL HTTPS locale ou scanner son QR URL.
3. Avant le premier accès audio, ouvrir la page HTTP `/trust`, télécharger le certificat public, vérifier l’empreinte affichée sur l’hôte et lui accorder confiance. Windows : racines de confiance de l’utilisateur ; Android : certificat CA ; iOS : profil puis confiance explicite. Firefox peut utiliser un magasin séparé.
4. Ouvrir le HTTPS local, saisir les six chiffres et autoriser le micro. ENABLE AUDIO permet de débloquer une lecture que le navigateur a suspendue.
5. OFFLINE PACKAGE vérifie le cache PWA. Les assets sont fournis par l’Android même si GitHub/DNS/Internet sont inaccessibles. Un cache d’interface n’est pas un serveur : l’hôte doit rester disponible pour une nouvelle session.

Le HTTP d’une IP privée charge l’interface mais ne fournit pas getUserMedia/service worker dans les navigateurs standards. Ne pas attendre de talkback complet depuis ce lien non sécurisé. Un simple avertissement TLS ignoré peut ne pas suffire : installer la confiance, ou utiliser l’APK Android. Garder la page Web ouverte ; arrière-plan Web non garanti.

## Reconnexion et diagnostic

Ne pas effacer les données de l’app/site. Une reprise conserve clientKey et resumeToken, donc le même membre. RECONNECT AUDIO relance la négociation. Après changement d’IP d’un hôte, rouvrir son nouveau WEB ACCESS dans le navigateur ; une coupure temporaire sur la même adresse récupère la session.

Status affiche transport, signalisation LOCAL et liens SRTP. Advanced contient IP, candidats/paire sélectionnée, DTLS, codec, paquets, RTT, jitter, perte et reprises. Aucun chiffre de latence micro → casque n’est garanti.

[Checklist physique A–J et résultats de livraison](LOCAL-FIRST-REPORT.md). Les essais physiques ne sont pas remplacés par le build ou l’émulateur.

## Bodypack MR18 / PC

MR18 USB → PC → FOSA SERVER → START LOW LATENCY. Android : OPEN BODYPACK, serveur détecté ou adresse locale/code, écouteurs, gain/master. Ce moteur PCM et le bridge ne sont pas refondus par l’appairage Mobile.
