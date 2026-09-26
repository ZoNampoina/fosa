# TURN credentials

Cette fonction évite de conserver des identifiants TURN longue durée dans le frontend public.

Secrets attendus :
- TURN_CREDENTIALS_URL : endpoint serveur du fournisseur TURN qui renvoie des credentials temporaires
- TURN_API_TOKEN : secret serveur correspondant

Le frontend doit préférer cette fonction et n'utiliser les credentials statiques actuels qu'en fallback de test.

Ne jamais placer TURN_API_TOKEN dans index.html.
