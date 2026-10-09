# FOSA Mobile 0.14.0 — démarrage local

## Android sur le même Wi-Fi/Ethernet/hotspot

1. Installer l’APK de la 0.14.0 sur l’hôte et les invités. Autoriser le micro pour parler.
2. Hôte : CREATE SESSION → NETWORK SETUP → USE CURRENT NETWORK → nom du groupe → CREATE SESSION. Le LAN doit fournir une IP Wi-Fi/Ethernet privée ; aucun réglage du routeur n’est modifié.
3. Invité : JOIN SESSION → six chiffres → JOIN WITH CODE. NEARBY permet aussi de sélectionner l’hôte ; SCAN QR lit son QR court.
4. Attendre la connexion du transport, puis vérifier MICROPHONE CAPTURING, le vu-mètre, TRANSMISSION SENDING et RECEPTION RECEIVING dans les deux sens avec des écouteurs filaires. Une connexion seule ne prouve pas le son.
5. Members : User/Group, autorisations parole/écoute et ajout d’appareils. Internet peut être coupé dès avant la première étape.

Au démarrage, la permission Nearby prépare aussi le repli automatique Wi-Fi Direct ; son refus laisse le LAN disponible. Les APK sont signés avec une clé debug désormais conservée par la CI. Une ancienne installation 0.12.x peut avoir une autre signature : si Android refuse la mise à jour, une réinstallation effacera son identité. Préparer cette migration avant la répétition, jamais pendant une session.

## Sans routeur

Wi-Fi doit être activé. Dans NETWORK SETUP, choisir WI-FI DIRECT pour créer un groupe natif ; USE CURRENT NETWORK reste le choix par défaut. Accorder Nearby devices sur Android 13+ ou localisation sur Android 12 et antérieurs. Android peut afficher son propre consentement de connexion. FOSA pilote les APIs P2P ; aucun réglage Wi-Fi Direct manuel n’est demandé par l’app.

Si P2P échoue : sur l’hôte, CREATE LOCAL FOSA NETWORK. Le SSID réel et le mot de passe masqué fournis par Android sont affichés avec SHOW/COPY. Scanner SCAN TO CONNECT TO WI-FI avec le système ; ce premier QR rejoint seulement le Wi-Fi. Utiliser ensuite SCAN TO JOIN FOSA SESSION ou les six chiffres. Aucun identifiant Wi-Fi n’est inventé. Créer/rejoindre ensuite la session par code. Le hotspot conserve sa réservation dans le service ; fermer FOSA/LEAVE ferme le réseau qu’il a créé. Un hotspot système préexistant peut aussi être utilisé.

## Un seul scan depuis l’hôte

Invité : JOIN → DISPLAY PAIRING QR, laisser l’écran ouvert. Hôte : Members → ADD DEVICE. Scanner une fois. Invitation valable 90 secondes. Le client rejoint automatiquement ; aucun QR de réponse. L’Android invité doit rester visible pour que le service audio puisse démarrer selon les règles Android.

## Web PC/iPhone/tablette depuis l’Android

1. Rejoindre le Wi-Fi/hotspot/groupe exposé par l’hôte. Le Web ne crée pas de groupe P2P.
2. Hôte : Members → WEB ACCESS, copier l’URL HTTPS locale ou scanner son QR URL.
3. Avant le premier accès audio, ouvrir la page HTTP `/trust`, télécharger le certificat public, vérifier l’empreinte affichée sur l’hôte et lui accorder confiance. Windows : racines de confiance de l’utilisateur ; Android : certificat CA ; iOS : profil puis confiance explicite. Firefox peut utiliser un magasin séparé.
4. Ouvrir le HTTPS local, saisir les six chiffres et autoriser le micro. ENABLE AUDIO permet de débloquer une lecture que le navigateur a suspendue.
5. OFFLINE PACKAGE vérifie le cache PWA. Les assets sont fournis par l’Android même si GitHub/DNS/Internet sont inaccessibles. Un cache d’interface n’est pas un serveur : l’hôte doit rester disponible pour une nouvelle session.

Le HTTP d’une IP privée charge l’interface mais ne fournit pas getUserMedia/service worker dans les navigateurs standards. Ne pas attendre de talkback complet depuis ce lien non sécurisé. Un simple avertissement TLS ignoré peut ne pas suffire : installer la confiance, ou utiliser l’APK Android. Garder la page Web ouverte ; arrière-plan Web non garanti.

GitHub Pages reste facultatif. Pour conserver son entrée par six chiffres lorsqu'Internet existe, l'hôte peut activer ENABLE INTERNET DISCOVERY · 2 MIN dans Advanced Diagnostics. Le rendez-vous échange seulement la signalisation initiale ; audio et commandes deviennent directs. Sans Internet, utiliser WEB ACCESS sur l'hôte. Le Web local n'appelle jamais ce rendez-vous.

## Reconnexion et diagnostic

Ne pas effacer les données de l’app/site. Une reprise conserve clientKey et resumeToken, donc le même membre. RECONNECT AUDIO relance la négociation. Après changement d’IP d’un hôte, rouvrir son nouveau WEB ACCESS dans le navigateur ; une coupure temporaire sur la même adresse récupère la session.

HOLD : maintenir, relâcher pour fermer ; garde-fou de 30 secondes. TAP : toucher une fois, toucher de nouveau pour fermer ; pas de coupure HOLD. Le micro reste ouvert en arrière-plan natif, avec notification STOP TALK / MUTE. SETTINGS propose une limite OFF, 5 ou 15 minutes. AUTO est désarmé par défaut : l’armer manuellement, régler le seuil d’énergie et le délai de fermeture. La musique et le bruit peuvent déclencher AUTO ; ce mode ne reconnaît pas sémantiquement la parole. Une perte de réseau, une révocation, Panic et Leave ferment TAP/AUTO sans réarmement à la reprise. Changer de destination applique immédiatement All/Group/Leader/User.

SETTINGS → AUDIO DIAGNOSTICS → TEST AUDIO émet deux secondes de 660 Hz vers la destination choisie. Il faut la permission de microphone et de parole. Le test traverse l’encodeur, le RTP et le décodeur réels. Les compteurs de signal de test sont séparés de la capture physique : un test de tonalité réussi ne prouve pas que le micro fonctionne. Vérifier les PCM, RMS, octets/paquets et niveaux reçus puis l’écoute sur l’autre appareil.

Status affiche transport, signalisation LOCAL et liens SRTP. Advanced contient IP, candidats/paire sélectionnée, DTLS, codec, paquets, RTT, jitter, perte et reprises. Aucun chiffre de latence micro → casque n’est garanti.

[Résultats audio/mains libres et essais matériels](AUDIO-HANDSFREE-REPORT.md). Les essais physiques ne sont pas remplacés par le build ou l’émulateur.

## Bodypack MR18 / PC

MR18 USB → PC → FOSA SERVER → START LOW LATENCY. Android : OPEN BODYPACK, serveur détecté ou adresse locale/code, écouteurs, gain/master. Ce moteur PCM et le bridge ne sont pas refondus par l’appairage Mobile.
