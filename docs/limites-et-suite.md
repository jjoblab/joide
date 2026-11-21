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

### Non fait (avec la raison) — à livrir dans une session suivante
- **Lot 6** (design chat) : composer extensible multiligne, bouton Stop
  visible (l'annulation existe côté moteur — `AiSessionViewModel.cancelTurn`
  — mais le bouton n'est pas posé), jauge de contexte, sélecteur de modèle
  en bottom sheet, RecyclerView + DiffUtil (le transcript reste LinearLayout),
  accessibilité TalkBack complète.
- **Lot 7** (réglages IA) : hub + sous-pages (Fournisseurs, Modèles,
  Comportement, Permissions, Outils, Utilisation, Données, activation IA),
  matrice de câblage dans docs/analyse-ia.md + test automatisé. La page
  actuelle a été recâblée sur le registre réel (chips 4 fournisseurs,
  modèle saisi, test de connexion pas encore posé).
- **Lot 8** (intégrations atelier) : ouvrir fichier:ligne depuis le chat,
  rafraîchissement éditeur/arbre après mutation (l'événementiel existe côté
  tiroir : `WorkspaceEvents`), « Demander à l'IA » sur la sélection,
  « Expliquer cette erreur » depuis les Problèmes.
- **Lot 9** (qualité/docs) : ADR des décisions structurantes (fournisseurs,
  codec JSON interne, stockage conversations, rendu Markdown maison, modèle
  de réglages), contrôles automatiques API 28 sur le module `ai` (grep en CI),
  perf 200 messages.
- Ouvert mineur : changement de fournisseur en cours de conversation → la
  session garde l'historique (`rebuildLoop`) mais le bouton n'existe pas
  encore côté UI ; l'effort est lu globalement, l'épinglage par conversation
  est archivé (champ `effort`) mais pas encore éditable.

## Tiroir gauche (mission prompt-agent-tiroir-gauche-refonte.md)

### Fait
- **Lot 0** (`ffb36a0`) : extraction sans régression — `LeftDrawerFragment` +
  onglets, `WorkspaceViewModel`, contrat `WorkbenchHost`, bus
  `WorkspaceEvents` (workbench-spi, testé), largeur min(320dp, écran−56dp)
  aux dimens, « Files » → « Fichiers ».

### Non fait (lots 1 à 8) — raisons
- Lots 1-8 non commencés (moteur de fichiers `core-files`, icônes/guides,
  UI Fichiers, recherche, structure, `core-git` robuste, onglet Git dans le
  tiroir, réglages/robustesse) : la session de travail a été consacrée à la
  refonte IA (lots 0-5) comme l'ordonnait la mission ; l'aperçu validé
  (`docs/mockups/tiroir-gauche.html`) décrit précisément chaque lot.

## Comment continuer
1. `cd /home/z/my-project/joide && git pull` — tout est poussé sur `main`.
2. Les aperçus validés (`docs/mockups/ia.html`, `tiroir-gauche.html`)
   restent la référence des écrans à livrer.
3. Prochaines étapes dans l'ordre : IA Lot 6 → 7 → 8 → 9, puis tiroir 1 → 8.
4. Outils en place : JDK 17 (`/home/z/my-project/tools/jdk17`), SDK Android
   (`/home/z/my-project/tools/android-sdk`), forge en mavenLocal
   (`tools/forge-mavenlocal.sh`), tests locaux
   `JOIDE_SKIP_ANDROID=1 ./gradlew test` + `:app-android:assembleDebug`.
