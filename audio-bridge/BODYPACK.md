# FOSA Bodypack 0.10

## PC

1. Extraire la nouvelle archive FOSA. Installer Python 3.11/3.12 **64 bits** et le pilote USB ASIO Midas si nécessaire.
2. Brancher la MR18 en USB. Régler la console/pilote sur **48 kHz**. Relier le PC au routeur par Ethernet.
3. Lancer **FOSA-SERVER.cmd**. La première installation des dépendances nécessite Internet ; les sessions Android suivantes fonctionnent sur le LAN seul.
4. Dans **ENGINE**, vérifier MR18 / ASIO / 18 IN. **START LOW LATENCY** ouvre le périphérique sélectionné. Le buffer initial LIVE est 128 échantillons ; essayer 64 uniquement après un essai stable.
5. Dans **Connect Bodypack**, choisir **Android natif · audio + talkback hors ligne**. Garder le serveur ouvert.

## Android

1. Installer [FOSA-Android.apk](https://github.com/ZoNampoina/fosa/releases/download/android-latest/FOSA-Android.apk).
2. Même réseau local que le PC. Ouvrir FOSA → **DETECT LAN** → serveur trouvé. Si mDNS est filtré, saisir l’IP affichée sur le PC. Le QR natif ouvre l’app si le lecteur QR accepte les liens `fosa://` ; l’IP manuelle reste disponible.
3. Saisir nom, rôle et code session, puis **ÉCOUTER**. Brancher écouteurs filaires ou DAC USB-C. USB-C Ethernet utilise le réseau système, sans mode spécial.
4. Dans **MIX**, régler les canaux, le pan, MONITOR GAIN et MASTER. La notification **FOSA MONITORING ACTIVE** reste accessible écran verrouillé : MUTE / OPEN / STOP.
5. Pour parler : le PC autorise le micro dans **LIVE / TALK** ou **MATRIX**. Sur le téléphone : **ACTIVER MON MICRO**, accepter Android, maintenir TALK. La lecture reste indépendante du micro.

## Premier essai MR18

1. Baisser le volume physique du téléphone/casque. Brancher un micro sur CH1 ; vérifier le routage USB **Input 1 → CH1**, pas une copie du Main L/R.
2. Monter uniquement CH1, master personnel à un niveau modéré. Parler normalement ; le VU CH1 doit réagir seul.
3. Passer MONITOR GAIN de 0 à +3 puis +6 dB : le niveau numérique envoyé doit monter. MASTER reste indépendant.
4. Tester mute et pan gauche/droite, puis **PANIC MUTE**. **RÉTABLIR L’ÉCOUTE** est une action distincte.
5. Tester Talkback, écran éteint, débranchement écouteurs, coupure/reprise Wi-Fi, puis plusieurs Bodypacks. Vérifier STATUS, XRuns, pertes et sous-alimentations.

**Le limiteur borne les échantillons numériques à −1 dBFS. Il ne mesure pas le niveau acoustique dans les IEM.** Garder un volume physique prudent ; le casque et son amplificateur déterminent le niveau sonore final.

## Ce que fait cette version

- Capture ASIO 18 entrées, sélection MR18 prioritaire, reprise du périphérique après déconnexion.
- Mix individuel côté PC : niveau, mute, solo maintenu, pan égal-puissance ; noms/groupes et ordre visuel ; entrées désactivables.
- Monitor Gain 0…+12 dB, limiteur L/R lié, master final 0…1, mono/stéréo, fondus de 5 ms. Les permissions et plafonds du régisseur sont vérifiés côté serveur.
- RMS, peak, peak hold, niveau L/R et réduction du limiteur **mesurés dans le DSP**. OPTIMIZE LEVEL analyse 3–5 s de signal et propose un gain sans l’appliquer automatiquement.
- More Me +3 dB sur le canal principal, macros +3 dB par groupe, LOCK MIX local et verrouillage régisseur, presets de session sur le PC.
- PCM stéréo 48 kHz / 16 bits / 5 ms. Android : UDP avec AES-GCM, contrôle d’intégrité et anti-rejeu. Web : data channel WebRTC sans retransmissions. Opus WebRTC 20 ms conservé.
- Android : AudioTrack en mode faible latence demandé, buffer borné, service de premier plan, notification, wake lock, détection de route, mute sur perte de focus / débranchement, Talkback AudioRecord.
- Découverte mDNS `_fosa._tcp`, QR, saisie IP/code, reconnexion avec le même profil. Écoute et Talkback Android sans passage cloud.
- Talkback PC : microphone système ou entrée MR18 sélectionnée, destinations tous / rôle / utilisateur, TTL et révocation côté serveur.
- Export des diagnostics, compteur XRuns, charge du worker DSP (incluant son ordonnancement), temps du callback, pertes et buffers transmis par le client.

## Alternatives et limites explicites

- **48 kHz uniquement** pour la capture dans 0.10. Le pilote doit accepter cette fréquence ; 44,1 et 96 kHz ne sont pas annoncés comme supportés. Les tailles ASIO sont des demandes validées à l’ouverture : PortAudio n’énumère pas les tailles disponibles.
- Settings → Advanced Audio → **Use another audio device** : ASIO natif, ASIO4ALL, ou WASAPI Shared de diagnostic, 1 à 64 entrées. WASAPI Exclusive non implémenté. Aucun remplacement automatique de la MR18 par ASIO4ALL.
- Le client Android utilise AudioTrack Java, pas Oboe/AAudio natif C++. Le mode faible latence peut être refusé par l’appareil ; STATUS expose le mode et le buffer réellement ouverts. Pas de promesse de ≤20 ms sans mesure physique.
- **UNKNOWN / NON MESURÉE** désigne la latence entrée-sortie. Le ping, les pertes, la durée des paquets et les buffers ne constituent pas cette mesure. Le test réseau mesure les commandes et exploite les compteurs audio ; il ne mesure pas le débit maximal de l’AP.
- Android natif est PCM uniquement. Le mode Opus existe dans le navigateur. Une interruption prolongée relance le transport, remet le buffer en état et applique un fondu.
- La version navigateur continue de fonctionner. Son arrière-plan dépend d’Android/iOS. Le Talkback navigateur mobile nécessite HTTPS ; le QR sécurisé existant utilise Internet pour l’établissement/reconnexion, puis l’audio et les commandes passent directement sur le LAN.
- Le démarrage Android HTTP échange les clés de session sur le LAN : utiliser un réseau dédié de confiance ou configurer TLS. Les paquets UDP sont ensuite chiffrés/authentifiés. Le code du QR donne accès à la session ; il ne contient jamais la clé régisseur.
- Les presets rappellent les profils déjà enregistrés sur ce PC, leurs mixes/permissions et le routing logique. Ils ne reconfigurent pas le pilote ni le réseau au milieu d’une écoute.
- Pas de client iOS natif, d’EQ par canal, de mesure acoustique automatique ni de calibration matérielle automatique. Ces fonctions ne sont pas présentées comme disponibles.

## Validation avant scène

Les tests logiciels sont décrits dans [ARCHITECTURE-BODYPACK.md](ARCHITECTURE-BODYPACK.md). La MR18, le pilote Midas, le DAC/casque et le Wi-Fi réels doivent encore être essayés ensemble. Pour mesurer la latence physique, enregistrer simultanément une impulsion directe et la sortie du téléphone sur deux pistes : décalage en échantillons ÷ 48 = millisecondes à 48 kHz.

Réseau conseillé : **PC → Ethernet → point d’accès dédié 5/6 GHz → téléphone**, écouteurs filaires. Bluetooth ajoute généralement trop de délai pour ce rôle ; FOSA l’identifie sur Android, sans tenter de masquer ce délai.
