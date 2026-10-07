# Validation FOSA 0.13.0

Les résultats exacts et scénarios physiques A–J sont dans [LOCAL-FIRST-REPORT.md](LOCAL-FIRST-REPORT.md). Classifications obligatoires : TESTÉ AUTOMATIQUEMENT, TESTÉ SUR ÉMULATEUR, NÉCESSITE TEST SUR APPAREIL PHYSIQUE.

Commandes :

- `python -m unittest discover -s audio-bridge/tests -v`
- `node mobile/tests/browser.cjs` — compatibilité 0.12, vrai Opus décodé, écoute/routage, PTT tactile et cache Chromium/WebKit.
- `node mobile/tests/local-first.cjs` — protocole de coordinateur en fixture Node, vrais HTTP et Chromium WebRTC, authentification par code, reprise, paire host/host, DTLS, voix décodée dans les deux sens, aucun domaine externe.
- `gradle assembleDebug assembleDebugAndroidTest`
- `gradle connectedDebugAndroidTest` — vrai coordinateur Kotlin, vrai TLS/CA validé et assets embarqués, QR décodé, reprise/permissions ; moteur audio natif et UI/service Bodypack/Mobile existants.

Les workflows existants restent les portes de publication. Aucun média de test n’est une preuve d’une liaison entre deux radios physiques. Un espace réseau de développement limité à loopback ne possède pas de candidats LAN admissibles : le test média doit échouer, sans assouplir les filtres de production.

Mesures physiques requises : WAN débranché avant pairing, P2P sans routeur, hotspot + PC Web avec certificat approuvé, deux sens de scan à une opération, DNS/cloud bloqués, panne/reprise sans doublons, deux sens de voix réellement entendus, chemin ICE host/host et latence impulsion microphone → casque.
