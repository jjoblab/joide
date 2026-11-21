# Analyse du tiroir gauche — matrice de câblage (Lot 8)

> Chaque contrôle du tiroir gauche pointe vers son ancrage **réel** dans le
> code (Fichier#symbole, vérifié par `tools/check-cablage-tiroir.sh` en
> CI). Un contrôle non câblé se dit « à venir » **ici et à l'écran** —
> jamais un bouton qui ne fait rien (règle ADR-018, appliquée au tiroir).
> Référence visuelle : `docs/mockups/tiroir-gauche.html` (aperçu validé).

## Onglet Fichiers

| Contrôle | Ancrage réel | Statut |
|---|---|---|
| Chips Projet / Android | `FilesTabFragment#setAndroidView` + `AndroidView#build` | câblé |
| Tout replier | `FilesTabFragment#setupTools` (expandedDirs().clear) | câblé |
| Tout déplier | `FilesTabFragment#expandOneMoreLevel` | câblé |
| Suivre le fichier actif | `FilesTabFragment#revealActive` | câblé (préférence `explorateur_suivi_actif`) |
| Actualiser | `FilesTabFragment#setupTools` (resetTreeState) | câblé |
| Filtrer | `FilesTabFragment#applyFilter` (l'arbre étendu seulement) | câblé |
| Réglages de l'explorateur | `FilesTabFragment#showExplorerSettings` | câblé (4 préférences, table ci-dessous) |
| Chevron de dossier | `FilesTabFragment#onRowClick` (animation 150 ms) | câblé |
| Ouvrir un fichier | `WorkbenchHost#openFile` (l'arbre ne touche jamais l'éditeur) | câblé |
| Secret tapé | `FilesTabFragment#onRowClick` (jamais ouvert en contenu) | câblé |
| Menu contextuel | `FilesTabFragment#showContextMenu` (menu `file_tree_context`) | câblé |
| Renommer | `FilesTabFragment#showRenameDialog` + `FsOperations#move` | câblé |
| Dupliquer | `FilesTabFragment#duplicate` + `FsOperations#copy` | câblé |
| Copier le chemin | `FilesTabFragment#copyPaths` | câblé |
| Déplacer vers… | `FilesTabFragment#showMoveDialog` + `movePaths` | câblé |
| Supprimer (corbeille) | `FilesTabFragment#deletePaths` + `FsOperations#toTrash` | câblé |
| Annuler une suppression | `FilesTabFragment#undoTrash` (instantané mémoire) | câblé |
| Sélection multiple | `FilesTabFragment#onRowLongClick` + barre `tree_selection_bar` | câblé |
| Nouveau fichier/dossier | `FilesTabFragment#showNewDialog` + `FileTemplates#contentFor` | câblé |
| Corbeille (dialogue) | `FilesTabFragment#renderTrashDialog` (restaurer/effacer définitivement/vider) | câblé |
| Badge d'éléments | `FilesTabFragment#badgeFor` + `FileTree#countAll` | câblé (préférence `explorateur_badges`) |
| Packages compacts | `FileTree#compactChain` | câblé (préférence `explorateur_packages_compacts`) |
| Fichiers cachés | `FilesTabFragment#listAsync` (showHidden réel) | câblé (préférence `explorateur_fichiers_caches`) |
| Glisser-déposer natif | remplacé par « Déplacer vers… » | écart assumé (geste vs sélection : Lot 3, §écarts) |

## Onglet Chercher

| Contrôle | Ancrage réel | Statut |
|---|---|---|
| Modes Chercher / Aller au fichier | `SearchTabFragment#setGotoMode` | câblé |
| Bascules Aa / W / .* | `SearchTabFragment#setupToggles` (`ProjectSearch$Options`) | câblé |
| Portée (menu) | `SearchTabFragment#showScopeMenu` + `#scopeDir` (réelle) | câblé |
| Compteur en direct | `SearchTabFragment#searchText` (flux poussé, lots de 25) | câblé |
| Stop | `SearchTabFragment#cancelSearch` (génération par frappe) | câblé |
| Résultat → ouverture | `WorkbenchHost#openFileAt` (occurrence sélectionnée) | câblé |
| Ligne Remplacer (appui long .*) | `SearchTabFragment#toggleReplaceRow` | câblé |
| Tout remplacer | `SearchTabFragment#showReplacementPreview` + `Replacer` | câblé |
| Annuler un remplacement | `SearchTabFragment#undoReplacement` (instantané) | câblé |
| Bouton recherche du dock | `WorkbenchFragment#setupDock` (ouvre l'onglet) | câblé |

## Onglet Structure

| Contrôle | Ancrage réel | Statut |
|---|---|---|
| Plan du fichier actif | `StructureTabFragment#rebuildOutline` + `Outline#javaOutline` | câblé |
| Suivi du curseur | `StructureTabFragment#pollWorkbench` + `WorkbenchHost#cursorOffset` | câblé |
| Tri Position / A→Z | `StructureTabFragment#renderSort` | câblé |
| Filtre des symboles | `StructureTabFragment#onCreateView` (filtrage local) | câblé |
| Saut à la ligne | `WorkbenchHost#openFileAt` | câblé |
| Langages non pris en charge | état honnête (`tiroir_struct_langage`) | annoncé (un fournisseur LSP pourra s'y brancher) |

## Onglet Git

| Contrôle | Ancrage réel | Statut |
|---|---|---|
| En-tête branche + suivi | `GitOps#tracking` + `GitOps#currentBranch` | câblé |
| Tirer / Pousser | `GitOps#pull` / `GitOps#push` | câblé (jeton au coffre par hôte) |
| Jeton du distant | `GitCredentialsStore` (chiffré, repli mémoire) | câblé (interne) |
| Zones exactes (conflits/indexé/non indexé/non suivi) | `GitOps#exactStatus` | câblé |
| Indexer / désindexer | `GitOps#stage` / `GitOps#unstage` | câblé |
| Visionneuse de diff | `GitTabFragment#showDiffSheet` (hunks, numéros, bascule indexé) | câblé |
| Commit | `GitOps#commitStaged` (l'index décide) | câblé |
| Résolution de conflits | `GitOps#resolveConflict` (nôtre/leur/contenu) | câblé |
| Branches (basculer/créer/supprimer) | `GitOps#switchBranch` / `#createAndSwitch` / `#deleteBranch` | câblé |
| Historique + badge fusion | `GitOps#graph` (parents) | câblé |
| Diff d'un commit | `GitOps#commitDiffs` | câblé |
| Stash (remiser/appliquer/effacer) | `GitOps#stashCreate` / `#stashApply` / `#stashDrop` | câblé |
| .gitignore (motif) | `GitOps#ignore` | câblé |
| Revert du dernier commit | `GitOps#revert` (confirmation avec le message réel) | câblé |
| Reset soft HEAD~1 | `GitOps#reset` | câblé |
| Oublier le jeton | `GitCredentialsStore#remove` | câblé |
| Fusionner une branche | `GitOps#merge` (confirmation) | câblé |
| Abandonner la fusion | `GitOps#abortMerge` (reset + purge d'état) | câblé |
| Identité de commit | `GitOps#setIdentity` / `#identity` | câblé |
| Init avec .gitignore de départ | `GitOps#init` (motifs Android/Gradle) | câblé |
| Amend / Signoff | `GitOps#commitStaged` (trailer réel) | câblé |
| Écran Git complet | `WorkbenchHost#openGitScreen` (GitFragment conservé) | câblé |
| Graphe dessiné (lignes de fusion) | parents exposés (`GitOps#CommitNode`) ; le dessin des lignes reste à venir | annoncé |

## Préférences de l'explorateur (clés vérifiées en CI)

| Clé | Écrivain | Lecteur | Statut |
|---|---|---|---|
| `explorateur_fichiers_caches` | `FilesTabFragment#showExplorerSettings` | `FilesTabFragment#listAsync` | câblé |
| `explorateur_packages_compacts` | `FilesTabFragment#showExplorerSettings` | `FilesTabFragment#appendDir` | câblé |
| `explorateur_badges` | `FilesTabFragment#showExplorerSettings` | `FilesTabFragment#appendDir` | câblé |
| `explorateur_suivi_actif` | `FilesTabFragment#showExplorerSettings` | `FilesTabFragment#onCreateView` | câblé |

## Garde-fous (performance et robustesse)

| Mécanisme | Borne | Ancrage |
|---|---|---|
| Listing d'un dossier | 500 entrées, troncature dite | `FileTree#DEFAULT_MAX_ENTRIES` |
| Compte de badge | 2000 entrées, « N+ » au plafond | `FilesTabFragment#countAsync` |
| Parcours de recherche | 5000 fichiers, 2000 correspondances | `ProjectSearch#DEFAULT_MAX_*` |
| Fichiers lus (recherche/diff) | 2 Mio, binaires sautés (sonde 8 Kio) | `ProjectSearch#MAX_FILE_BYTES` |
| Diff de liste | au-delà de 800 rangées, rechargement brut | `FileTreeAdapter#DIFF_LIMIT` |
| Affinage Java (icônes) | en-tête de 8 Kio par fichier | `FileKinds#HEAD_LIMIT` |
| Recherche différée | 300 ms après la dernière frappe | `SearchTabFragment#scheduleSearch` |
| Suivi Structure | 700 ms local, hors fil UI | `StructureTabFragment#onResume` |
| Annulations | instantanés mémoire (corbeille indexée, remplacement) | `FsOperations` / `Replacer` |

## Accessibilité

Chaque bouton d'icône porte un `contentDescription` ressource (outils de
l'arbre, bascules Chercher, tri Structure, tirer/pousser Git) ; les icônes
décoratives des rangées portent `@null` (le nom textuel est le libellé).
Les actions destructrices (supprimer définitivement, reset, suppression de
branche, revert) passent par une confirmation ; les erreurs du moteur
(JGit, fichiers) s'affichent telles quelles — jamais muettes.
