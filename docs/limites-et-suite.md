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

### Non fait (lots 6 à 8) — raisons
- **Lot 6** (`core-git` robuste : statut exact indexé/non indexé/conflits,
  diff indexé et non indexé, branches, fetch/pull/push authentifiés, stash,
  historique + graphe, cherry-pick/revert/reset, résolution de conflits,
  .gitignore, init) : non commencé — le budget de la session a servi les
  lots 2-5 (chacun livré complet, testé, CI verte). Point d'entrée :
  étendre `GitService` (JGit) en gardant pur JVM + tests, l'écran Git
  actuel et le bus GitStateChanged existent déjà.
- **Lot 7** (onglet Git **dans le tiroir** : Changements/Branches/
  Historique/Plus, zone de commit collée en bas, visionneuse de diff
  partagée) : dépend du Lot 6 ; l'écran Git plein écran reste en place
  (chip Git du tiroir, comportement Lot 0).
- **Lot 8** (réglages « Explorateur et Git » + matrice de câblage en CI,
  accessibilité, performance 20 000 fichiers, robustesse) : dépend des
  lots 6-7 ; le glisser-déposer natif (remplacé par « Déplacer vers… »)
  s'y ajoute.
- Comment continuer : `cd /home/z/my-project/joide && git pull` ; JDK 17
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
