# FOSA LAN protocol 2 — Local first, Internet optional

Compatibilité : endpoints et identités du protocole 1 conservés. Le Bodypack PCM/MR18 est un moteur distinct.

## Réseau et découverte

Contrat LocalTransport indépendant de Session/Auth/Signaling/Audio. Implémentations livrées : LanTransport, AndroidWifiDirectTransport ; LocalHotspot crée le LAN de repli. La logique de sélection appartient à FosaConnectionManager, pas à la vue.

LAN existant prioritaire. NSD `_fosa-mobile._tcp.` : protocole, session ID/nom, code, nom/rôle hôte, port, version et nombre de membres. UDP 48764 : requête FOSA-DISCOVERY/2 avec identifiant de recherche aléatoire, réponse de métadonnées bornée à 1800 octets et correspondant à cet identifiant. NSD/UDP ne transportent pas SDP, ICE ou audio. Passerelle TCP 48765 essayée aussi pour les hotspots peu fiables en multicast.

P2P Android : service `_fosa._tcp`, discoverServices/discoverPeers, connect/createGroup, requestGroupInfo/requestConnectionInfo. Le Group Owner n’est pas aveuglément assimilé à l’hôte : le coordinateur doit répondre avec la session demandée. Bind réseau selon les routes ; à défaut de Network P2P fourni par Android, les sockets utilisent le routage local du système. Aucune API de création P2P n’est supposée dans le navigateur.

## Canal de signalisation et authentification

JSON UTF-8, requêtes/queues bornées, huit membres maximum. HTTP local 48765 préféré ; Web HTTPS 48766 avec CA propre à l’installation, à approuver explicitement. POST `/lan/info`, `/lan/challenge`, `/lan/join`, `/lan/auth`, `/lan/reconnect`, `/lan/poll`, `/lan/signal`, `/lan/group`, `/lan/permissions`, `/lan/leave`, `/lan/ping`, `/lan/members`, `/lan/talk`. GET `/lan/info` fournit les métadonnées du Web local.

JOIN v2 contient code/session/protocolVersion=2, clientKey et nonce de challenge. Challenge lié à la source, 30 s, usage unique, stockage borné. Le token membre aléatoire est privé ; Authorization: Bearer requis après join. Les v1 restent acceptés. Huit codes erronés par source/minute bloquent temporairement l’admission.

Six chiffres = identité de session et protection contre erreurs de sélection, pas secret fort. Le code annoncé ne protège pas d’un utilisateur du LAN. L’admission native HTTP suppose un LAN de confiance et n’est pas résistante à un attaquant actif sur ce réseau.

Poll : after, talk, target, level ; réponse session/sessionName/leader/members/signals. Signal : seq/from/generation/type/data, types offer/answer/ice/reset. La plus petite identité produit les offres pour éviter les collisions. Acquittement après application. Générations anciennes ignorées. Owner seul change groupes et canTalk/canListen. Les récepteurs conformes appliquent les permissions aux pistes.

## Identité/reprise

clientKey persisté avant join, resumeToken privé après join, session ID et generation. Un join répété avec preuve privée retrouve le membre, remet Talk à zéro, vide les signaux obsolètes et augmente la génération. Les noms/IP ne sont pas des identifiants. Les identités expirées quittent les huit emplacements actifs après 60 s ; jusqu’à 64 identités retirées sont reprenables pendant cette session. Leave explicite révoque l’identité. Une réponse HTTP join perdue ne doit pas créer un second membre grâce au clientKey conservé.

Web local : HTTP de même origine pour bootstrap ; quand le lien direct est prêt, RPC dans le data channel chiffré. Si ce lien échoue, HTTP local reste disponible pour réparer/rejoindre ; aucun rendez-vous Internet. Après changement d’IP de l’hôte, les navigateurs ne redécouvrent pas les services natifs et doivent rouvrir le lien local.

## Invitations courtes

Session : fosa://join?v=2&s=UUID&c=123456&h=IP&p=PORT. Device : fosa://device?v=2&id=12HEX&n=32HEX. Maximum 150 caractères. Web QR : URL HTTPS seule. Aucun SDP/ICE/answer/certificat/token membre.

Android DeviceInvitation : endpoint POST `/pair/accept`, nonce temporaire à usage unique, délai 90 s. L’hôte découvre le locator de l’appareil par NSD/UDP/P2P, puis lui transmet son locator de session.

Web local : device-register → QR court → owner device-admit → device-poll → join avec jeton aléatoire à usage unique. Invitation 90 s ; 32 en attente maximum. Le navigateur n’annonce pas de serveur local.

## Média

WebRTC Opus, DTLS/SRTP, ICE servers vide. Candidats host locaux IPv4 privés/IPv6 privés/link-local/mDNS, UDP privilégié et TCP host accepté. Candidats relay/srflx et publics filtrés ; paire externe relay/srflx refusée. Média pair à pair, pas de cloud/HTTP/Supabase/TURN. Full mesh jusqu’à huit membres ; aucune migration d’hôte automatique livrée.

Stats : paire sélectionnée, adresses/ports/types candidats, protocole, DTLS, codec, paquets RX/TX, RTT, jitter, perte. Mouth-to-ear UNKNOWN ; les statistiques RTC ne constituent pas une latence physique.

Ancien rendez-vous : le code-only de GitHub Pages reste disponible lorsque l'hôte active explicitement ENABLE INTERNET DISCOVERY · 2 MIN dans Advanced Diagnostics. Ce bootstrap est limité à l'origine HTTPS GitHub Pages ; le Web local ne l'appelle pas. Audio et contrôle sont directs après le pairing. Le SDP compressé/QR aller-retour n'est visible que dans le test de compatibilité 0.12, jamais dans le parcours normal 0.13. Aucun changement Supabase requis pour les sessions locales.
