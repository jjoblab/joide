# Écarts preview ↔ application — tiroir gauche

> Comparaison ligne à ligne de `docs/mockups/tiroir-gauche.html` (aperçu
> validé) avec l'implémentation réelle, après les lots 0-8. Statuts :
> **conforme** (déjà fidèle) · **corrigé** (rapproché dans ce lot) ·
> **consigné** (écart assumé, raison indiquée). Ce fichier est la référence
> des vérifications de fidélité ; il se met à jour à chaque correction.

## 1 · En-tête et navigation du tiroir

| Élément du preview | Implémentation avant | Statut |
|---|---|---|
| En-tête : **nom du projet (gras) + branche (small)** au-dessus des segments | switcher seul, pas de nom de projet | **corrigé** — en-tête ajouté (nom réel du dossier + branche Git) |
| **Contrôle segmenté** pleine largeur (fond `sc_high` arrondi, actif `sc2` arrondi) | 4 chips détachées 32 dp | **corrigé** — segments soudés, actif sur `sc2` |
| Chip de fermeture ✕ | `ic_x` | conforme |

## 2 · Onglet Fichiers

| Élément du preview | Implémentation avant | Statut |
|---|---|---|
| Outils ⇤ ⇥ ⇲ ⟳ ⌕ ⋮ | ⇤ ⇥ (réticule) ⟳ ⌕ ⚙ | **corrigé** — icônes redessinées sur les silhouettes du preview : ⇲ (flèche vers le coin), ⋮ (trois points verticaux pour Options) |
| Chips Projet/Android | idem | conforme |
| Rangées 40 dp, guides d'indentation, guide actif | idem | conforme |
| Icônes par type (badges lettrés colorés, convention Android Studio) | 25 vecteurs originaux `ic_tree_*` | conforme |
| Décorations git (point + couleur, modifié #3B82C4) + nom coloré | idem, `git_modifie` = #3B82C4 | conforme |
| Badges d'éléments des dossiers repliés (`4`, `1 240`) | idem, plafond « N+ » | conforme |
| Filtre : surlignage des correspondances + compte | surlignage gras + compte | conforme |
| Sélection multiple : barre 🗑 ⧉ ✂ ＋ (Supprimer, Copier, Déplacer, **Indexer**) | 🗑 ⧉ (flèche) ✕ — pas d'indexation | **corrigé** — icône ✂ (ciseaux) pour Déplacer, bouton ＋ **Indexer** (stage Git réel des fichiers sélectionnés) |
| Menu contextuel : Renommer · Dupliquer · Copier/Couper · Supprimer · Copier le chemin · **∓ .gitignore** · **Git : Historique** | Renommer · Dupliquer · Copier chemin · Déplacer · Supprimer · Sélectionner | **corrigé** — ajout « Ajouter à .gitignore » (motif réel) et « Git : Historique » (ouvre l'onglet Git) ; « Couper » sans cible de collage serait factice : **consigné** |
| Pastilles erreur/avertissement héritées par les parents | absentes | **consigné** — aucun moteur de diagnostics dans l'app ; la décoration se branchera sur la brique diagnostics quand elle existera (le preview l'annonçait comme exemple d'illustration) |
| Superpositions 🔒 lecture seule · 🔗 lien | absentes | **consigné** — pas de lecture seule gérée à ce jour |
| Corbeille, états honnêtes (chargement, erreur, vide) | idem | conforme |

## 3 · Onglet Chercher

| Élément du preview | Implémentation | Statut |
|---|---|---|
| Champ + Aa / W / .* + portée en menu | idem | conforme |
| Résultats groupés par fichier, numéros de ligne, surlignage | idem | conforme |
| Compteur en direct + Stop | idem | conforme |
| Ligne Remplacer repliable, aperçu cochable, Tout remplacer, annulation | idem | conforme |
| « Aller au fichier » flou | idem | conforme |
| Regex invalide : message du moteur | idem | conforme |

## 4 · Onglet Structure

| Élément | Implémentation | Statut |
|---|---|---|
| Position / A→Z, suivi du curseur, symbole courant mis en évidence | idem | conforme |
| Filtre rapide des symboles (⚙) | présent (champ repliable) | conforme |
| Badges de visibilité + − # ~, signatures, numéros de ligne | idem | conforme |
| Imports repliés (« 4 imports ») | idem | conforme |
| États vides honnêtes (rien d'ouvert, langage non pris en charge) | idem | conforme |

## 5 · Onglet Git (écarts les plus importants)

| Élément du preview | Implémentation avant | Statut |
|---|---|---|
| **Sous-navigation Changements · Branches · Historique · Plus** (chips) | sections repliables empilées | **corrigé** — sous-navigation par chips, une vue à la fois |
| En-tête : **chip branche ⎇ main ▾** (sélecteur) | nom de branche en texte simple | **corrigé** — chip, appui = sélecteur de branches (bascule réelle) |
| Suivi « ↓2 ↑1 · **synchronisé il y a 14 min** » | ↓↑ sans horodatage | **corrigé** — instant de la dernière synchro mémorisé et affiché |
| Boutons **Fetch · Pull · Push** | Pull + Push | **corrigé** — Fetch ajouté (⤓) |
| Sections Conflits ⚠ / Indexés / Non indexés / Non suivis avec badges | idem | conforme |
| **« tout indexer » / « tout désindexer »** par section | actions par rangée seulement | **corrigé** — action de section (stage/unstage de masse) |
| Rangées : lettre M/A/D/U/! **(D barré, ! teal)**, nom, **chemin du dossier parent**, compteurs **+n −m** | lettre + chemin complet, pas de compteurs | **corrigé** — D barré, ! teal, chemin parent raccourci, compteurs +/− réels par diff |
| Suppression : « supprimé dans le dossier de travail » | libellé de chemin seul | **corrigé** |
| Zone de commit : sujet + **compteur 0/72** + **Amend** + **Signoff** + bouton plein | message + bouton chip | **corrigé** — compteur, cases Amend/Signoff (réelles : JGit), bouton plein primaire |
| Branches : **locales/distantes groupées**, pastilles refs, suivi par branche, **Fusionner**, ＋ Branche, Basculer | liste plate, créer/basculer/supprimer | **corrigé** — groupes Locales/Distantes, suivi par branche, Fusionner (merge réel), distantes listées |
| Historique : **graphe à voies colorées**, pastilles HEAD/branche/étiquette, pagination « Charger 50 de plus » | liste plate, badge « fusion » | **corrigé** — voies dessinées (algorithme de voies par parents), pastilles de refs réelles, pagination |
| Visionneuse de diff : en-tête +/−, hunks @@, **numéros de ligne** | dialogue de lignes colorées sans hunks | **corrigé** — feuille du bas avec hunks, numéros, en-tête compteurs |
| Diff : surlignage au niveau des mots, actions par hunk, côte à côte | absent | **consigné** — le hunk-level staging et le word-diff demandent un moteur de patch dédié ; la feuille unifiée couvre le besoin de lecture |
| Conflits : par **bloc** (Le nôtre / Le leur / Les deux), Abandonner la fusion | par fichier (Nôtre/Leur/Contenu) | **partiel** — « Les deux » et « Abandonner la fusion » ajoutés (réels) ; la résolution par bloc parsé reste **consignée** |
| États Git : **Qui êtes-vous ?** (identité), init avec **.gitignore de départ**, jeton par hôte, progression | init nue, jeton ✓ | **corrigé** — carte identité (nom + e-mail réels dans la config), init avec .gitignore de départ Android/Gradle ; la progression chiffrée de push reste **consignée** (JGit ne l'expose pas simplement) |
| Feuilles plein écran, diffs dans la zone éditeur | feuille du bas | **consigné** — la zone éditeur n'héberge pas encore d'onglets de diff |

## 6 · Ce que le preview annonçait comme illustration

Le preview affiche des exemples (arbre, branches, compteurs) : dans
l'app, tout est réel — listage du disque, état Git, compteurs calculés.
Les légendes « pastille erreur/avertissement » et « vignette image réelle »
restent des illustrations en attente de leur moteur (diagnostics, images).

## 7 · Icônes : règle de fidélité

Les icônes de l'app sont des **dessins originaux** (décision de licence,
ADR) dont les silhouettes suivent le preview : ⇤ ⇥ ⇲ ⟳ ⌕ ⋮ ✕ ⎇ ⤓ pour les
outils, ✂ ＋ dans la barre de sélection, M/A/D/U/! pour Git. Aucun glyphe
Unicode directement dans l'UI (rendu selon la police de l'appareil) :
chaque silhouette est un VectorDrawable teinté par les jetons.
