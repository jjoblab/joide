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

### Non fait (lots 3 à 8) — raisons
- **Lot 3** (interactions Fichiers) : menu contextuel (renommer, dupliquer,
  copier/couper/coller, copier le chemin, .gitignore), sélection multiple,
  glisser-déposer, corbeille réelle (FsOperations atomiques + restauration),
  vues Projet/Android, filtre en direct, badges d'éléments des dossiers
  repliés, RecyclerView + DiffUtil — non commencé, prochaine étape.
- Lots 4-8 : recherche (`core-search`), structure (`core-outline`),
  `core-git` robuste, onglet Git dans le tiroir, réglages/robustesse —
  dans l'ordre après le Lot 3 (aperçu validé : `docs/mockups/tiroir-gauche.html`).

## Comment continuer
1. `cd /home/z/my-project/joide && git pull` — tout est poussé sur `main`.
2. Les aperçus validés (`docs/mockups/ia.html`, `tiroir-gauche.html`)
   restent la référence des écrans à livrer.
3. Prochaines étapes dans l'ordre : IA Lot 6 → 7 → 8 → 9, puis tiroir 1 → 8.
4. Outils en place : JDK 17 (`/home/z/my-project/tools/jdk17`), SDK Android
   (`/home/z/my-project/tools/android-sdk`), forge en mavenLocal
   (`tools/forge-mavenlocal.sh`), tests locaux
   `JOIDE_SKIP_ANDROID=1 ./gradlew test` + `:app-android:assembleDebug`.
