# Architecture et audit FOSA Bodypack 0.10

## Réutilisation du dépôt

| Brique existante | Décision |
|---|---|
| `index.html`, `v080.js`, Nearby Android/iOS | Conservés. L’ancien mode ne remplace pas le chemin MR18. Le lanceur ouvre maintenant Bodypack / Engineer. |
| `bridge.py`, sounddevice / PortAudio | Capture USB/ASIO réelle conservée. Le gestionnaire décrit driver, périphérique, entrées/sorties, fréquence et priorité. |
| `mixer.py` | Modèle validé et matrice de pan conservés, nombre de canaux paramétrable. Ancienne fonction DSP gardée pour compatibilité ; le serveur utilise `MonitorEngine`. |
| `low_latency.py`, AudioWorklet | PCM WebRTC de 5 ms conservé, queues bornées, contrôle de congestion et correction légère de dérive. |
| `StereoTrack`, aiortc / Opus | Opus 20 ms conservé, mix stéréo par musicien. Aucun chiffre de latence codec inventé. |
| `network-relay.js`, `secure_relay.py` | Signalisation HTTPS existante conservée. P-256/HKDF/AES-GCM, autorisations, anti-rejeu et passage au contrôle WebRTC direct. Aucun audio ne transite par Supabase. |
| WebSocket | Reste celui du relais de signalisation. Pas de transport PCM sur WebSocket/TCP imposant des retransmissions. |
| Sessions / régisseur / Talkback | Jetons musicien persistants, clé privée locale, routage all/rôle/user, permissions et TTL réutilisés. |
| Nouvelle application Android | `BodypackActivity` fournit la même UI de contrôle LAN. `BodypackService` reçoit/lit l’audio indépendamment du WebView et de l’écran. L’ancien Activity Nearby reste dans le dépôt. |

## Control plane

API aiohttp locale, authentification bearer et permissions côté serveur. L’interface affiche uniquement les niveaux remontés du moteur ; une capture absente produit `null`, pas des VU de démonstration. Les mesures navigateur/Android sont déclarées par ces clients. Les exports n’exposent pas leurs clés privées.

Les écritures atomiques de session passent par un worker disque. L’ouverture, l’énumération et la reprise du périphérique utilisent un worker dédié, afin de conserver un même thread d’accès au pilote. Le callback de capture fait une copie bornée et réveille le traitement ; il ne fait ni requête réseau, ni log, ni écriture de fichier.

## Audio plane

```text
MR18 USB / ASIO / 18 entrées à 48 kHz
  → capture callback / queue bornée
  → worker FOSA-DSP, blocs 240 frames
  → matrice personnelle (permissions, niveau, pan, mute, solo, ducking)
  → Talkback destiné à cet auditeur
  → mono facultatif
  → Monitor Gain, lissé
  → limiteur sample-peak stéréo lié, plafond −1 dBFS, release 100 ms
  → Master et fondu connexion / mute
  → garde numérique finale
  → PCM WebRTC ou UDP Android ; Opus WebRTC en option
```

Le limiteur utilise l’anticipation disponible **dans le bloc déjà capturé**, sans ajouter de bloc. Il ne s’agit pas d’un limiteur true-peak suréchantillonné ou d’un système mesurant le SPL du casque. Les valeurs non finies et blocs manifestement corrompus sont neutralisés. Les changements de matrice/gain/master sont lissés sur 5 ms. Le Panic local ne dépend pas d’un aller-retour serveur.

Les backlogs de capture et de transport sont bornés. Les données trop anciennes sont abandonnées et comptées. Un watchdog relance le pump après exception en réinitialisant les fondus. Cette architecture Python/NumPy n’est pas une garantie de temps réel dur : la charge du PC, le driver et le réseau doivent être surveillés.

## Transport natif Android

`POST /api/native` exige un jeton musicien. Il remplace l’ancien transport de **ce profil seulement** et fournit port UDP, identifiant de flux et deux clés AES-256-GCM distinctes, une par sens. Chaque nouvelle allocation change les clés.

Enveloppe : `FNA1 | stream ID (8 octets) | compteur big-endian (4) | ciphertext + tag GCM (16)`. Le nonce est ID + compteur ; l’en-tête est authentifié comme AAD. Les compteurs ne bouclent pas sous une même clé. Le sens téléphone→serveur rejette les séquences déjà vues ; le sens serveur→téléphone utilise une fenêtre anti-rejeu. Un HELLO authentifié établit l’adresse de destination et expire après deux secondes sans keepalive.

Payload audio : `A` + paquet `FLL1` existant, 240 frames stéréo s16 LE. Payload micro : `T` + 240 échantillons mono s16 LE. HELLO : `H`. Le plafond des paquets est inférieur au MTU usuel. Les paquets ne sont jamais retransmis. Charge PCM utile : 1,536 Mbit/s/client ; audio UDP chiffré avec IPv4/UDP : environ 1,67 Mbit/s/client, hors Wi-Fi/Ethernet et contrôle.

Android maintient une fenêtre bornée de 32 paquets, avec cible 5 / 10 / 20 / 40 ms. Un trou produit un fondu vers zéro ; un backlog excessif est abandonné avec fondu. La dérive native est actuellement contrôlée par cette borne et les compteurs de saut, sans resampler adaptatif fin. AudioTrack utilise WRITE_BLOCKING sur un thread prioritaire et demande PERFORMANCE_MODE_LOW_LATENCY ; l’état effectivement accordé reste observable.

Le service `mediaPlayback|microphone` est démarré depuis l’app visible. La permission micro est demandée séparément, seulement lorsque l’utilisateur active TALK. Perte de focus ou débranchement : mute local. La notification permet de muter même si le WebView est suspendu. Aucun redémarrage automatique du micro au boot Android.

## Tests exécutables

- `python -m unittest discover -s audio-bridge/tests -v` : capture simulée explicitement, mapping, authentification, permissions, Opus/PCM via véritables connexions WebRTC locales, séparation des mixes, gain/limiteur/master, mono, solo interdit, mute/fondu, NaN/Inf, auto-level, UDP chiffré sur sockets réelles, Talkback et anti-rejeu.
- `node audio-bridge/tests/pcm_worklet.cjs` : format PCM, séquences, réordonnancement, pertes, saturation du buffer, reprise et sorties 44,1/48/96 kHz. Ces fréquences de **sortie navigateur** ne signifient pas capture MR18 44,1/96 kHz.
- GitHub Actions Windows et Linux : mêmes tests Python, contrôles JS et pare-feu Windows.
- Playwright Chromium/WebKit : vrais flux décodés, deux mixes indépendants, Talkback autorisé/révoqué, destinations, reprise, PCM+Opus, vues PC/téléphone/tablette. Les sources et micros sont des fixtures de test, pas du matériel MR18.
- Android : compilation APK et test instrumenté sur émulateur API 35, avec serveur UDP chiffré de test, AudioTrack, AudioRecord, service en arrière-plan et Panic. Un émulateur ne valide ni le driver MR18 ni la latence matérielle du téléphone.
- Interface native : test navigateur des appels `FosaAndroid`, connexion authentifiée, modification du vrai mix serveur, Panic local, buffer initial et diagnostics. Le pont Android est simulé dans ce test d’interface ; la lecture audio native est couverte séparément par le test sur émulateur.

## Références techniques

- [Android : faible latence pour les applications](https://source.android.com/docs/core/audio/latency/app)
- [AudioTrack](https://developer.android.com/reference/android/media/AudioTrack)
- [Types de services Android de premier plan](https://developer.android.com/about/versions/14/changes/fgs-types-required)
- [python-sounddevice / PortAudio](https://python-sounddevice.readthedocs.io/)

Les résultats du matériel réel restent à consigner dans le premier essai MR18. Toute évolution vers 44,1/96 kHz, Oboe, Opus natif, iOS natif, true-peak, EQ ou mesure physique automatique nécessite une validation séparée.
