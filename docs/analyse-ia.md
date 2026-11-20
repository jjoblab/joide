# Analyse de l'intégration IA — produit de référence

> Phase 1 (analyse de l'intégration IA du produit de référence). Notes fonctionnelles
> clean-room : aucun code, aucune chaîne de ressource, aucune clé reproduits. Chaque constat
> renvoie à une entrée `REF-xxx` de [`REFERENCES.md`](REFERENCES.md) (fichier, plage de
> lignes, SHA épinglé `1b1a3052168a52f38a91ea03c87831a0d94c1fff`). Ce document ne nomme
> jamais le produit de référence.

L'agent IA du produit de référence est architecturalement **un plugin désactivable** : aucun
câblage privilégié de l'hôte, tout l'agent se greffe sur le système de plugins standard du
produit (REF-101). Notre équivalent : un module `ai` de JoIDE consommé par le tiroir IA de
l'atelier, activable/désactivable dans les paramètres.

## 1. Fournisseurs et modèles

- **Quatre fournisseurs natifs** (deux grands laboratoires, un moteur de recherche, un
  agrégateur) sur un transport commun, plus un **pseudo-fournisseur « passerelle
  compatible »** (base URL configurable) pour les serveurs locaux — ce dernier est le moyen
  natif de travailler hors ligne (REF-102). Un SPI permet à un plugin d'ajouter un
  fournisseur.
- **Clés API** : apportées par l'utilisateur, une par fournisseur, saisie dans les
  réglages ; **clés de secours** multiples par fournisseur orchestrées par un pool avec
  mise au repos et **rejeu automatique sur la clé suivante tant qu'aucune sortie n'a été
  streamée** (REF-103).
- **Bascule fournisseur** : persistée ; le changement **reconstruit la boucle en portant la
  conversation** (aucune perte) (REF-102).
- **Modèles** : liste statique par défaut par fournisseur mais **liste vivante** interrogée
  par l'endpoint du fournisseur ; choix **persisté par fournisseur** (pas un champ global —
  un commentaire documente l'échec du modèle du fournisseur précédent) ; récents/favoris
  par fournisseur ; le fournisseur peut choisir un défaut depuis les capacités réelles du
  compte (REF-104). Nous retenons : **persistance par fournisseur + liste vivante avec
  repli statique**.
- **Effort de raisonnement** : champ neutre à 7 niveaux, mappé par fournisseur ;
  **épinglé par conversation** (un changement d'effort invaliderait le cache de messages)
  (REF-105).
- Constats hors périmètre pour nous (consignés pour mémoire) : tier gratuit maison via
  proxy propriétaire, flux OAuth d'un agrégateur, recherche web côté serveur par requête
  (REF-102).

## 2. Interface de conversation

- **Flux en streaming** (SSE chez tous les fournisseurs), deltas traduits en événements
  neutres (texte, raisonnement, début/fragment/fin d'appel d'outil, usage, fin, échec) ;
  l'UI applique chaque delta au message en cours (REF-106).
- **« Pensée » du modèle** : blocs de raisonnement affichés comme étapes séparées du
  transcript ; une ligne d'état au-dessus du champ de saisie distingue « réflechit /
  écrit / exécute un outil » ; le raisonnement doit être rejoué à l'identique (signature)
  sur le même modèle, et **changer de modèle abandonne les blocs de raisonnement** à la
  restauration (REF-106).
- **Pièces jointes** : image (sélecteur, appareil photo, presse-papiers, mise à l'échelle
  avant envoi), fichier et sélection (blocs balisés chemin + plage de lignes devant la
  question, bornés) ; **mention `@chemin`** avec complétion sur l'arborescence qui attache
  le fichier résolu (REF-107).
- **Historique multi-conversations** : une session = un fichier JSON écrit **atomiquement**
  après chaque tour, contenant à la fois le transcript vu par l'utilisateur **et**
  l'historique exact vu par le modèle ; titre dérivé de la première phrase (pas de
  renommage manuel constaté) ; index inter-projets pour un onglet d'accueil IA
  (REF-108). Nous retenons : **double stockage transcript/historique**, écriture
  atomique ; nous ajoutons le **renommage manuel** (écart voulu, REF-108).
- **Questions à choix multiples** : outil « demander à l'utilisateur » (1-4 questions,
  options, multi-select) qui **suspend le tour comme une permission** ; même mécanique
  que l'approbation d'outil (REF-109). Retenu.
- **Commandes slash** : nouveau/compacter/init/annuler/reprendre/modèle… interceptées dans
  le champ de saisie (REF-110). Retenu (jeu minimal : /nouveau, /annuler, /modèle).
- **Annulation** : stoppe la génération et les outils ; un appel d'outil interrompu reçoit
  un résultat d'erreur « interrompu » pour que la conversation reste envoyable (REF-110).

## 3. Contexte envoyé

- **Prompt système en deux moitiés** : une moitié **stable** (identité, réalité de la
  plateforme, règles de travail, liste d'outils) en préfixe système ; une moitié
  **volatile** (mode de permission, contexte projet) en message système **de fin
  d'historique**, rafraîchie à chaque tour sans casser le cache (REF-111). Toute
  l'ingénierie de contexte de la référence est **pilotée par la préservation du cache de
  prompt** : chaque choix est pesé en « octets déjà cachés ».
- **Fichiers de règles projet** : `AGENTS.md` puis `CLAUDE.md` à la racine, premier trouvé,
  extrait borné, mis en cache par (chemin, date de modification) — dans la moitié stable
  (cache-friendly) ; commande `/init` qui le génère (REF-111). Retenu à l'identique.
- **Philosophie « l'agent va chercher »** : pas d'envoi automatique du fichier ouvert ;
  l'agent **lit** le projet via ses outils (lecture du tampon éditeur vivant sinon disque),
  et les diagnostics (erreurs de build, LSP) sont des outils demandables (REF-112).
  L'action « demander à l'IA » attache la sélection courante à la question. Retenu.
- **Bornage des résultats d'outils à l'arrivée** : listes bornées avec compte de ce qui
  manque (« affinez la requête »), blobs bornés tête ou queue selon la sémantique (les
  journaux gardent la fin) ; la règle explicite : borner à l'arrivée **plutôt que
  réécrire ensuite**, car réécrire casse le cache (REF-113).
- **Compactage à hystérésis** : rien d'élidé sous un seuil ; au déclenchement, élision
  des résultats d'outils anciens hors des plus récents, **décision permanente** pour ne
  jamais faire dériver le préfixe ; **auto-compact** (résumé par le modèle sans outils)
  au-delà d'un seuil estimé ; délégation possible au rognage serveur quand le
  fournisseur sait compter les tokens (REF-113). Retenu comme conception de notre
  gestion de contexte (L7).

## 4. Outils de l'agent

- **Port « espace de travail » étroit** : aucun outil ne touche le projet directement ;
  tout passe par une interface étroite (lecture/écriture/déplacement/suppression,
  recherche, diagnostics), implémentée par l'hôte — ou par un **fake en mémoire pour les
  tests** (REF-114). Retenu : notre `AgentWorkspace` Java pur, testable avec un fake.
- **Outils de lecture** (jamais gated) : lecture de fichier (fenêtre de lignes),
  listage de répertoire, recherche de texte, recherche de symbole, diagnostics du
  fichier, vue d'ensemble du projet. **Outils d'écriture** (gated) : création, écriture,
  édition par fragment exact avec vérification d'unicité, renommage, déplacement,
  suppression, ajout/retrait de dépendance (REF-115).
- **Exécution** : compiler-exécuter un module (sandboxé, limité en temps), tâches de
  build/run avec journal borné à la queue (REF-115). Pour nous : l'agent passe par le
  `BuildBackend` du mode actif — jamais par un moteur parallèle (cohérent avec la règle
  des deux modes).
- **Concurrence** : les appels en lecture seule d'un même tour s'exécutent **en
  parallèle** ; les mutants **séquentiellement ensuite** (invites de permission jamais en
  course, écritures ordonnées) (REF-116). Retenu.
- **Porte de permission unique** : trois modes persistés (demander à chaque fois /
  accepter automatiquement / plan seulement) ; la porte est le **seul** point de décision ;
  le refus renvoie au modèle un résultat d'erreur informatif ; la fermeture du dialogue =
  refus (défaut sûr) (REF-117). Retenu à l'identique.
- **Diff à l'approbation** : un outil mutant qui le peut fournit un aperçu avant/après
  affiché **dans** l'invite d'approbation ; après exécution, diff par hunks sur la ligne de
  l'outil (REF-117). Retenu.
- **Annulation par tour** : enveloppe de points de contrôle — instantané du premier état
  de chaque chemin par tour et par appel ; le contenu, créations, suppressions sont
  annulables exactement ; **les opérations non annulables sont signalées honnêtement**
  au lieu d'être prétendues inversibles ; après annulation, une note est envoyée au
  modèle pour qu'il relise les fichiers (REF-118). Retenu.
- **Boucle** : requête → flux → outils → réinjection → recommence, **plafond d'itérations**
  par tour ; au plafond, un **tour de clôture sans outils** fait le bilan au lieu
  d'échouer en pure perte ; **appariement obligatoire appel/résultat avant tout envoi**
  (répare les conversations interrompues) (REF-119). Retenu.

## 5. Coûts, quotas, erreurs

- **Usage de tokens** normalisé (entrée/sortie **plus lectures/écritures de cache
  séparées**), affiché en pied de message et agrégé par tour (un tour = plusieurs
  requêtes) (REF-120).
- **Pacing préventif** : un régulateur par clé+modèle **attend avant d'envoyer** plutôt
  que de ramasser des erreurs de quota ; limites configurables puis **apprises des
  erreurs** ; attente affichée en compte à rebours annulable (REF-121). Retenu.
- **Taxonomie fine des 429** : le même refus de quota est décomposé en **limite par minute
  (transitoire) / limite journalière / modèle hors plan (limite zéro)** — trois remèdes
  différents pour trois causes qui se ressemblent sur le fil ; suggestion d'un autre
  modèle en un geste (REF-121). Retenu.
- **Reprise réseau** : backoff exponentiel prélisible uniquement (jamais re-POST après
  début de flux) ; crédit insuffisant → réessai avec plafond d'output abaissé ; réponse
  tronquée → une re-tentative avec découpage ; retry rejoue la conversation **sans**
  ajouter un tour utilisateur (REF-121).

## 6. Extensions

- **Client MCP maison** (protocole HTTP streamable avec repli SSE, stdio sur desktop
  uniquement — pas d'exécutable Node/Python sous Android), format de configuration JSON
  **compatible avec celui des clients de code IA du marché**, serveurs déclarés par
  fichier de projet **considérés non fiables jusqu'à approbation explicite** (REF-122).
- **L'app sert aussi ses propres outils en MCP** (HTTP loopback opt-in avec
  avertissement, mode stdio autonome, relais hébergé) ; skills au format du marché
  (REF-122). Hors périmètre L7 (consigné dans limites) — retenu en conception comme
  extension future.

## 7. Confidentialité et sécurité

- **Clés API en clair** dans le magasin de préférences standard — la référence le
  documente elle-même comme une dette (un seam chiffré est prévu mais non livré)
  (REF-123). **Nous faisons l'inverse dès le premier jour** (écart voulu) : stockage
  chiffré par la clé de bibliothèque chiffrement (voir conception §8) — le cahier des
  charges l'impose.
- **Ce qui sort de l'appareil** : la conversation complète (messages, pièces jointes,
  résultats d'outils — donc contenus de fichiers lus et sorties de build) part chez le
  fournisseur (REF-123). Nous l'affichons honnêtement dans les paramètres IA.
- **Résistance à l'injection d'instructions** : (1) règle d'ancrage dans le prompt système
  (tout résultat d'outil est une **donnée**, pas une instruction — un texte qui demande
  d'ignorer les instructions ou de révéler la configuration est à signaler, jamais à
  suivre) ; (2) **marquage explicite** du contenu rapporté du réseau dans une balise
  « contenu non fiable » ; la référence précise honnêtement que c'est une atténuation et
  que **la porte de permission sur les outils mutants est le vrai conteneur**
  (REF-124). Retenu à l'identique (les deux, plus la porte).
- **Passerelle/CA** : l'autorité de certification supplémentaire s'ajoute aux CA système,
  chaîne toujours validée (pas de « tout faire confiance ») (REF-123).

## 8. Absences notables (différenciateurs pour JoIDE)

Deux fonctionnalités sont **absentes** du produit de référence au SHA épinglé (REF-125) :

1. **Complétion inline IA** (ghost text) — le pipeline de complétion de l'éditeur y est
   entièrement non-IA.
2. **Génération de messages de commit** — le panneau de versionnage n'y référence jamais
   l'agent.

Ces deux absences sont des différenciateurs immédiats à notre portée ; la complétion
inline IA est placée en « suite possible » (limites-et-suite.md) — pas en L7 — pour ne
pas lier la boucle agent à l'éditeur.

## 9. Bilan de l'analyse

L'intégration IA de la référence est **l'état de l'art constaté sur mobile** : ingénierie
de cache de prompt, taxonomie de quotas, porte de permission unique, annulation honnête,
extension bidirectionnelle. Ce que nous reprenons : les deux moitiés de prompt
stable/volatile, le bornage à l'arrivée, la porte de permission unique avec diff à
l'approbation, l'annulation par tour avec aveu des limites, le tour de clôture au plafond,
le port espace de travail étroit, la concurrence des lectures, la persistance du modèle
par fournisseur, le pacer préventif et les trois variétés de 429.

Ce que nous faisons différemment : clés chiffrées dès le premier jour, renommage manuel des
conversations, pas de tier gratuit ni de proxy maison, pas de relais hébergé, pas de
serveur FTP local, MCP en extension future, et l'agent construit sur le `BuildBackend`
des deux modes (jamais un moteur concurrent).
