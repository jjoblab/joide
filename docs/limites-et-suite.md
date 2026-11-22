# Limites et suite — refonte IA + tiroir gauche (état au 2026-11-21)

> Ce fichier dit exactement ce qui est fait, ce qui ne l'est pas et pourquoi.
> À mettre à jour à chaque lot livré.

## Refonte IA (mission prompt-agent-ia-refonte-complete-openrouter.md)

### Fait (poussé, CI verte à chaque lot)
- **Lot 0** (`3025f22`) : séparation Assistant/Agent — liste des conversations,
  suggestions, « Créer un projet depuis cette conversation », assistant SANS
  outil, puces de contexte réelles via AgentContext.
- **Lot 1** (`31cdc5d`) : `ai/json/Json` (codec complet), `SseParser` (commentaires,
  CRLF, UTF-8 coupé), `LlmError` typé + `Secrets` (rédaction), `HttpLlmTransport`
  réécrit (appels d'outils cumulés par index, raisonnement, usage, vraie
  finish_reason, erreur en flux, annulation CancellationToken, reprises avant
  la première sortie), schémas d'outils sérialisés, tests sur serveur HTTP JDK.
- **Lot 2** (`62f6e7b`) : `ProviderRegistry` — OpenRouter, OpenAI, Gemini
  (compat vérifiée doc officielle), passerelle personnalisable ; Endpoint
  (URLs par fournisseur, en-têtes), extras (reasoning au format du
  fournisseur, routage OpenRouter), `ModelCatalog` (prix/capacités),
  migration « passerelle »→« custom », suppression de « laboratoire ».
  **Anthropic différé** : doc officielle inaccessible depuis l'environnement
  (blocage région) et sources tierces contradictoires — règle « aucun champ
  non confirmé ». À ajouter après vérification dans sa documentation.
- **Lot 3** (`1ef465e`) : outils dans `ai/tools/` (un fichier par outil,
  ToolArgs typé, schémas), `modifier_fichier` par remplacement ciblé,
  espace de travail durci (évasion, chemins protégés, binaire, taille,
  dossiers générés), `DiffBuilder` (diff réel à l'approbation),
  `TurnCheckpoint` (annulation de tour avec aveu des suppressions),
  détection de boucle, `demander_utilisateur`/`plan`, AGENTS.md borné et
  caché, contexte volatil réel (branche, fichier actif, problèmes).
- **Lot 4** (`bb5be47`) : conversation à **double contenu** (transcript +
  historique exact du modèle), migration v1→v2, empreinte de projet,
  `AiSessionViewModel` (survit rotation/tiroir/onglets, événements groupés
  ~24 ms, persistance après chaque tour), liste des conversations de
  l'agent, commandes /nouveau /annuler /modele /compacter /init.
- **Lot 5** (`07ab918`) : `MarkdownParser` streaming-safe (propriété de
  préfixes testée), `ResponseInterpreter` (raisonnement à part, bloc suivi
  → chips, nature des blocs, nettoyage), rendu en blocs (paragraphes,
  tableaux, cartes de code avec Copier), **bouton Appliquer** passant par
  la porte de permission.

### Fait (suite — lots 6 à 8 + qualité)
- **Lot 6** (`5d69e40`) : bouton **Stop** (annulation réelle), **ligne d'état**
  (Réfléchit… / Écrit… / Exécute X…), **complétion @** sur l'arborescence
  réelle (parcours borné hors fil UI), **état vide agent** avec 3 actions
  réelles, cible 48 dp, `adjustResize` (composer visible avec le clavier).
- **Lot 7** (`3435316`) : **hub Réglages IA** + sous-pages Fournisseurs
  (afficher/masquer la clé, **Tester la connexion**, URL passerelle +
  Réinitialiser, options OpenRouter : attribution X-Title, routage sort/
  fallbacks/require_parameters/data_collection câblés dans la requête),
  Modèles (**catalogue vivant** + cache 6 h + repli honnête, recherche,
  badges, prix/contexte si connus, sélection persistée, saisie manuelle),
  Comportement (effort, plafonds ; recherche web et MCP dits « à venir »),
  Permissions (**mode de la porte enfin persisté**, restauration au
  démarrage). **Matrice de câblage** docs/analyse-ia.md + contrôle
  tools/check-cablage-ia.sh **ajouté à la CI**.
- **Lot 8** (`79ac008`) : **bus d'espace de travail partagé** (atelier +
  tiroir gauche) ; chaque mutation de l'agent publie FileChangedOnDisk →
  l'onglet ouvert se relit depuis le disque ; bouton **Ouvrir** sur les
  cartes de code avec cible ; **« Expliquer cette erreur »** depuis les
  Problèmes (diagnostic exact prérempli à l'agent via openAgentPanel).
- **Lot 9** : ADR-014 à ADR-018 (registre documentaire, codecs internes,
  double contenu, rendu Markdown maison, matrice en CI) ; rapport final
  docs/rapport-refonte-ia.md (diagnostic de la mission vérifié point par
  point — aucun point inexact).

### Non fait (avec la raison) — à livrer dans une session suivante
- Migration **RecyclerView + DiffUtil** du transcript (perf très longues
  conversations) ; jauge de contexte (le catalogue la rend possible, le
  branchement reste) ; sélecteur de modèle en bottom sheet (la page Modèles
  couvre le besoin en plein écran).
- « Demander à l'IA » depuis la **sélection de l'éditeur** : l'API
  (openAgentPanel/prefillPrompt) est posée et utilisée par les Problèmes ;
  le déclencheur côté éditeur reste à brancher.
- **Adaptateur Anthropic** (ADR-014 : doc officielle inaccessible depuis
  l'environnement, sources tierces contradictoires) ; recherche web et MCP
  (annoncés « à venir ») ; effort épinglable par conversation (champ archivé,
  pas éditable) ; approbation « pour la session / tout le lot » (la porte
  demande à chaque mutation aujourd'hui).
- Perf 200 messages et tests Robolectric de la coquille : non mesurés.

## Tiroir gauche (mission prompt-agent-tiroir-gauche-refonte.md)

### Fait
- **Lot 0** (`ffb36a0`) : extraction sans régression — `LeftDrawerFragment` +
  onglets, `WorkspaceViewModel`, contrat `WorkbenchHost`, bus
  `WorkspaceEvents` (workbench-spi, testé), largeur min(320dp, écran−56dp)
  aux dimens, « Files » → « Fichiers ». Le bus est **partagé avec l'agent IA**
  depuis le Lot 8 de la refonte IA (les mutations de l'agent y publient).
- **Lot 1** (`ce8cd8b`) : module `core-files` (JVM pur, 7 tests) —
  `FileTree` (listing paresseux borné et annulable, dossiers générés/VCS
  ignorés, tri dossiers d'abord insensible à la casse, chemins confinés,
  troncature dite) et `FileIcons` (extension → catégorie stable, secrets
  marqués). Fondation des lots 2-8.
- **Lot 2** (`23c94a8`) : iconographie + guides + décorations, et l'onglet
  Fichiers passe sur le **vrai moteur**. core-files : `FileKind`/`FileKinds`
  — classification fine par chemin (racines src/test, packages, XML par
  parent res/, manifest, secrets, README/LICENSE), affinage Java par en-tête
  borné 8 Kio (13 tests). app-android : 24 icônes VectorDrawable
  **originales** (ADR-019, jetons clair+sombre), `IndentGuidesView` (un
  trait par niveau, guide du dossier actif en primaire), décorations **git
  réelles** (point + couleur du nom, GitService hors fil UI) et
  **diagnostics** (pastille héritée par les dossiers parents, via
  `WorkbenchHost.diagnostics()` ; `DiagnosticsChanged` publié au build).
  Arborescence paresseuse bornée annulable (génération par tâche), chevrons
  animés, fichier actif en gras + suivi (filiation ouverte, défilement),
  états honnêtes (chargement par dossier, erreur + Réessayer,
  dossier/projet vide, troncature dite), secrets jamais ouverts, outils
  replier/déplier/suivre/actualiser, état dans `WorkspaceViewModel` (survit
  rotation + écran Git), bus `WorkspaceEvents` branché (mutations agent,
  build, git). Fix au passage : `Path.of` → `Paths.get` (11 usages, API 28).

- **Lot 3** (`891ff2b` + `ed9e220` + `467e3f1`) : les interactions de
  l'onglet Fichiers, en trois morceaux. Noyau (`891ff2b`) : `FsOperations`
  (créer/renommer/déplacer atomiques, copie bornée, corbeille restaurable
  indexée, protégés .git/.joide, confinement — 10 tests), `FileTemplates`
  (contenus de départ), `FileTree.countAll` (compte récursif borné pour les
  badges) et `compactChain` (« com.example » sur une ligne — 12 tests).
  UI (`ed9e220`) : liste aplatie RecyclerView + DiffUtil, menu contextuel
  (sélectionner, renommer, dupliquer, copier le chemin, déplacer,
  supprimer), sélection multiple avec barre d'actions, corbeille
  (restaurer / supprimer définitivement / vider), nouveau fichier (modèle)
  ou dossier, filtre en direct (l'arbre étendu seulement, correspondances
  en gras), badges des dossiers repliés (« N+ » au plafond), Snackbar
  « Annuler » sur suppression, opérations hors fil UI publiées sur le bus
  (un seul chemin de mise à jour), renommage qui déplace le fichier actif
  de l'éditeur. Vues (`467e3f1`) : bascule **Projet / Android** —
  squelette par module (Gradle Scripts, Manifestes, java, res) calculé en
  JVM testé, adossé aux fichiers réels (un nœud n'existe que si son
  contenu existe) ; sous le squelette, le pipeline normal (packages
  compacts, décorations, badges).
  *Écart honnête* : le glisser-déposer est remplacé par « Déplacer vers… »
  (destination saisie, conflits suffixés) — l'appui long sert à la
  sélection multiple (maquette), le geste de glisser natif reste possible
  en suite (Lot 8).

- **Lot 4** (`2ba6c9a` + `2818a63`) : module `core-search` (11 tests) —
  `ProjectSearch` (littéral protégé ou regex, casse, mot entier, portée,
  globs, borné, annulable, binaires sautés, flux poussé au fur et à
  mesure), `Replacer` (aperçu cochable, groupes $0-$9, application atomique
  bas vers haut, instantané d'annulation), `FileFinder` (flou « mjav » →
  Main.java, récents d'abord). UI : modes Chercher · Aller au fichier,
  bascules Aa · W · .*, portée réelle en menu, compteur en direct + Stop
  (génération par frappe), résultats groupés repliables, appui →
  **openFileAt** (occurrence sélectionnée dans la brique éditeur) ;
  remplacement : aperçu cochable → confirmation aux nombres exacts →
  application + Snackbar « Annuler » ; le bouton de recherche du dock
  ouvre l'onglet (finis les toasts).
- **Lot 5** (`0a2b40d`) : module `core-outline` (4 tests) — plans Java
  (types, membres, visibilité, imports repliés, tolérant au code
  incomplet), XML (éléments + android:id), Markdown (titres), JSON
  (clés). UI : plan du fichier **actif** reconstruit au changement de
  tampon (suivi local 700 ms, hors fil UI), **suivi du curseur** (symbole
  courant en évidence via WorkbenchHost.cursorOffset), tri Position/A→Z,
  filtre rapide, saut à la ligne ; états honnêtes (rien d'ouvert,
  langage non pris en charge, plan indisponible).

- **Lot 6** (`2790bf0`) : module `GitOps` — moteur git robuste (17 tests
  au module) : statut **exact par zone** (indexé/non indexé/non
  suivi/conflits — un même fichier peut vivre dans deux zones), zone de
  préparation (stage/unstage), diffs **indexé et non indexé** + diff par
  commit, branches (basculer/créer/supprimer), réseau fetch/pull/push
  (jeton passé par l'app, distant local bare dans les tests), stash,
  historique **avec parents** (graphe), revert / cherry-pick / reset,
  résolution de conflits (nôtre/leur/contenu), .gitignore sans doublon,
  init.
- **Lot 7** (`5041ade`) : **le client git vit dans le tiroir** — la chip
  Git héberge GitTabFragment (4e onglet, l'écran plein écran reste en
  Plus). En-tête (branche, suivi ↓↑, tirer/pousser avec **jeton au coffre
  chiffré par hôte** — GitCredentialsStore, dialogue à la demande),
  Changements (zones exactes, indexer/désindexer par rangée, visionneuse
  de diff colorée, zone de commit, résolution de conflits), Branches,
  Historique (badge fusion, diff par commit), Plus (stash, .gitignore,
  revert, reset soft, oublier le jeton, écran complet). Tout hors fil UI,
  publié sur le bus — un seul chemin de mise à jour.
- **Lot 8** (`4c96e1f`) : **matrice de câblage du tiroir**
  (docs/analyse-tiroir.md — chaque contrôle pointe son ancrage réel,
  vérifié par tools/check-cablage-tiroir.sh **ajouté à la CI**),
  réglages réels de l'explorateur (fichiers cachés, packages compacts,
  badges, suivi — persistés et appliqués), garde-fous documentés (bornes
  de listing/compte/recherche/diff, délais), accessibilité (descriptions,
  confirmations destructrices, erreurs jamais muettes).

### Passe de fidélité au preview (2026-11-21, `c744bca`)
Analyse preview ↔ application (`docs/mockups/ecarts-tiroir.md`) puis
correction : en-tête projet/branche + segments soudés du tiroir, icônes
originales du preview (⇲ ⋮ ✂ ⤓ ⎇, silhouettes redessinées — jamais de
glyphe Unicode en UI), barre de sélection conforme (＋ Indexer = stage
réel), menu contextuel enrichi (.gitignore, Git : Historique), onglet Git
réécrit sur la structure du preview (sous-navigation Changements·Branches·
Historique·Plus, chip branche sélecteur, Fetch, « synchronisé il y a »,
compteurs +/− réels, D barré / ! teal, tout indexer/désindexer, zone de
commit 0/72 + Amend + Signoff, branches locales/distantes + Fusionner,
**graphe à voies colorées** + pastilles de refs + pagination, feuille de
diff à hunks et numéros de ligne, conflits Les deux + Abandonner la
fusion, carte « Qui êtes-vous ? », init avec .gitignore de départ).
Deux nouveaux aperçus : `docs/mockups/contenu-principal.html` (zone
éditeur) et `docs/mockups/bottom-sheet.html` (comportement du dock,
démo interactive des trois états du BottomSheetBehavior).

### Correctifs du premier lancement (2026-11-23)
Deux bugs réels constatés sur appareil après l'écran d'installation du moteur :
- **Gel de l'interface après l'installation** : `render()` appelait
  `installState()` sur le fil UI, qui vérifie les sommes SHA-256 de TOUT le
  moteur installé (des centaines de Mo) — juste après « Installation
  terminée », l'écran gelait et même le bouton retour ne répondait plus. La
  vérification vit désormais hors fil UI (cache `volatile` + vol unique), le
  rendu reste instantané et raffine les chips quand le résultat arrive.
- **Retour mort au premier lancement** : l'assistant pousse l'écran
  d'installation SANS l'empiler — `popBackStack()` ne faisait rien et aucune
  sortie n'existait. Le retour (bouton de barre ET bouton système, via
  `OnBackPressedDispatcher`) dépile désormais s'il y a une entrée, ferme la
  popup de désinstallation si ouverte, et mène à l'accueil sinon ; une
  installation réussie au premier lancement poursuit automatiquement vers
  l'accueil.
- **Galerie de modèles vide** : le champ de recherche de l'écran de création
  était un `TextView` non éditable dont le libellé (« Chercher un modèle… »)
  était pris pour la requête de filtre — aucun titre de modèle ne le
  contient, la galerie restait vide. Le champ est un vrai `EditText` (le
  libellé vit dans le hint, la recherche devient réellement saisissable).
Vérifié : tests JVM (`JOIDE_SKIP_ANDROID=1 ./gradlew test`, verts), APK debug
assemblé, contrôles langue/dépendances/API 28 OK.

### Contenu principal conforme au preview validé (2026-11-22)
`docs/mockups/contenu-principal.html` **validé** → analyse
(`docs/mockups/ecarts-contenu-principal.md`) puis rapprochement : boutons
enregistrer/annuler/rétablir **réels** (brique céleris, activation honnête
sur les profondeurs), **cache de tampons** (changer d'onglet ne perd plus
les modifications — bug réel trouvé par l'analyse), ✱ par tampon réel,
fermeture confirmée (Enregistrer/Ignorer/Annuler), menu long d'onglet
(Fermer · Fermer les autres · Copier le chemin), ＋ nouveau fichier, fil
d'Ariane + sous-titre suivis à chaque ouverture, coloration **aux jetons**
de colors.xml, barre de symboles avec **chemin du symbole courant**
(plan céleris, appui → saut) au-dessus des touches, segment **Aperçu**
vivant (filière XML rendue depuis le tampon, saut à la ligne de l'élément ;
images réelles pour les binaires), états honnêtes (binaire détecté,
volumineux borné à 256 Kio annoncé, aucun fichier ouvert), sauvegarde de
sûreté au cache à la suspension avec proposition de restauration, repli
de la barre de symboles sous le clavier. Consigné : soulignement
d'erreurs dans l'éditeur (demande l'API décorations de céleris ; les
Problèmes du dock ouvrent déjà le fichier à la ligne).

### Écarts assumés (consignés) et suite possible
- **Glisser-déposer natif** (Lot 3) : remplacé par « Déplacer vers… » —
  l'appui long sert à la sélection multiple et au menu contextuel (choix
  de la maquette) ; le geste natif reste possible en suite.
- **Graphe dessiné** (Lot 7) : les parents sont exposés
  (`GitOps.CommitNode.parents`, badge « fusion » à l'écran) ; le dessin
  des lignes de graphe reste à venir.
- **Plans de langages** (Lot 5) : Java/Kotlin, XML, JSON, Markdown — un
  fournisseur LSP (brique code-lsp, demande B-02 ouverte) pourra remplir
  les langages manquants en se branchant sur l'état honnête prévu.
- Suite éventuelle : glisser natif, dessin du graphe, vue des diffs
  dans la zone éditeur (onglets de diff), corbeille dans la vue Android.
  (`/home/z/my-project/tools/jdk17`), SDK Android
  (`/home/z/my-project/tools/android-sdk`), forge en mavenLocal
  (`tools/forge-mavenlocal.sh /home/z/my-project/forge 0.2.13`), tests
  locaux `JOIDE_SKIP_ANDROID=1 ./gradlew test` + `:app-android:assembleDebug`.
  L'aperçu validé reste `docs/mockups/tiroir-gauche.html`.

## Comment continuer
1. `cd /home/z/my-project/joide && git pull` — tout est poussé sur `main`.
2. Les aperçus validés (`docs/mockups/ia.html`, `tiroir-gauche.html`)
   restent la référence des écrans à livrer.
3. Prochaines étapes dans l'ordre : IA Lot 6 → 7 → 8 → 9, puis tiroir 1 → 8.
4. Outils en place : JDK 17 (`/home/z/my-project/tools/jdk17`), SDK Android
   (`/home/z/my-project/tools/android-sdk`), forge en mavenLocal
   (`tools/forge-mavenlocal.sh`), tests locaux
   `JOIDE_SKIP_ANDROID=1 ./gradlew test` + `:app-android:assembleDebug`.
