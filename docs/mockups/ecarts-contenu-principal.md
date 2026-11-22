# Écarts preview ↔ application — contenu principal (zone éditeur)

> Comparaison de `docs/mockups/contenu-principal.html` (aperçu **validé**)
> avec l'implémentation réelle (`WorkbenchFragment` + brique céleris v2.2.11).
> Statuts : **conforme** · **corrigé** (rapproché) · **consigné** (écart
> assumé, raison indiquée). Suite de `ecarts-tiroir.md`.

## 1 · Barre d'outils

| Élément du preview | Implémentation avant | Statut |
|---|---|---|
| Menu ☰ (tiroir), titre projet + sous-titre, chip de mode | idem | conforme |
| Sous-titre « main · MainActivity.java » | branche seule | **corrigé** — branche · fichier actif, mis à jour à chaque ouverture |
| 💾 Enregistrer **réel** | toast d'état factice | **corrigé** — écrit le tampon vivant sur disque, événement disque publié (arbre + Git se rafraîchissent) |
| ↶ ↷ Annuler/Rétablir **réels**, désactivés honnêtement | toasts factices | **corrigé** — `TextSession#undo/redo`, activation sur `undoDepth/redoDepth` rafraîchie à chaque édition |
| ▶ Construire, ⋮ options | idem (réels) | conforme |

## 2 · Onglets de fichiers

| Élément du preview | Avant | Statut |
|---|---|---|
| Actif souligné primaire + gras | idem | conforme |
| ✱ **modifié par tampon réel** | point d'alerte **codé en dur sur l'onglet actif** | **corrigé** — le ✱ suit l'état réel du tampon (écoute d'édition céleris) |
| **Changement d'onglet conserve les tampons modifiés** | ⚠ **les modifications non enregistrées étaient perdues** (relecture disque à chaque ouverture) | **corrigé** — cache de tampons `Map<Path,String>` : le ✱ a enfin un sens ; fermer avec Ignorer jette le tampon |
| ✕ fermeture avec **confirmation si modifié** (Enregistrer / Ignorer / Annuler) | fermeture directe, perte silencieuse | **corrigé** |
| Appui long → Fermer · Fermer les autres · Copier le chemin | absent | **corrigé** (menu contextuel ancré à l'onglet) |
| ＋ nouvel onglet (fichier) | absent | **corrigé** — dialogue de nom, création réelle à la racine du projet, ouverture + rafraîchissement de l'arbre |
| Onglet vide → état honnête « ouvrez-en un depuis l'arbre » | zone d'édition vide, muette | **corrigé** — panneau d'état |

## 3 · Fil d'Ariane et segments

| Élément | Avant | Statut |
|---|---|---|
| Fil d'Ariane du fichier actif | mis à jour **une seule fois** au montage (1er fichier) | **corrigé** — suit chaque ouverture |
| Segments **Code / Aperçu** | boutons présents, **sans écouteur** (Aperçu mort) | **corrigé** — voir §5 |

## 4 · Zone d'édition

| Élément du preview | Avant | Statut |
|---|---|---|
| Gouttière, ligne courante, curseur clignotant | brique céleris | conforme |
| Coloration **aux jetons du preview** (mots-clés primaire, chaînes ok, commentaires sur_var, nombres tertiaire, types info) | palette Material (violet/vert/bleu) sans lien avec les jetons | **corrigé** — `SyntaxPalette` réalignée sur `colors.xml` (clair + sombre) |
| Soulignement d'erreur (diagnostics) | diagnostics du build rendus à l'agent, pas à l'éditeur | **consigné** — le rendu dans la brique céleris demande l'API décorations ; les Problèmes (dock) ouvrent déjà le fichier à la ligne |
| États honnêtes : **binaire** (type + taille, Aperçu image réel) | tout fichier lu en UTF-8 (un PNG s'ouvrait en caractères illisibles) | **corrigé** — détection binaire (octet nul), images rendues par `ImageView`, les autres binaires disent type + taille |
| État honnête : **volumineux** (lecture bornée, compte annoncé) | lecture intégrale (risque de gel sur gros fichier) | **corrigé** — lecture bornée à 256 Kio, troncacité annoncée au journal |
| Sauvegarde de sûreté à la suspension | absente | **corrigé** — tampons modifiés écrits dans le cache à la suspension ; à la réouverture, une sauvegarde plus récente propose « Restaurer » |
| Clavier : la zone reste visible, la barre de symboles se replie | `adjustResize` ✓, barre reste ouverte | **corrigé** — repli automatique quand le clavier monte |

## 5 · Aperçu (segment droit)

| Élément | Avant | Statut |
|---|---|---|
| **XML layout en filaire** : éléments avec `android:id`, sélection en primaire | bouton sans effet | **corrigé** — filaire réel rendu depuis le **tampon vivant** (modifications comprises), appui sur un élément → retour au Code, curseur sur la ligne de l'élément |
| Autres types : « pas d'aperçu pour ce type » (jamais de panneau figé) | muet | **corrigé** — message honnête au journal, on reste sur Code |
| Images : Aperçu via l'état binaire | absent | **corrigé** |

## 6 · Barre de symboles

| Élément | Avant | Statut |
|---|---|---|
| **Chemin du symbole courant** (chips, appui → saut à la définition) | clavier de touches code (Tab { } ( ) ;) sans chemin | **corrigé** — rangée de chemin au-dessus des touches : plan céleris (même moteur que la structure), symboles englobants du curseur, appui → saut à la ligne |
| Touches code conservées | — | conforme (le clavier reste, sous le chemin — utile au téléphone) |

## 7 · Règles transverses

- Undo/redo « désactivés honnêtement » : l'activation suit `undoDepth`/`redoDepth`, rafraîchie à chaque édition (écoute céleris), pas un état figé.
- Fermeture d'onglet modifié : **jamais de perte silencieuse** (confirmation à trois choix).
- Tout est réel : états, tampons, compteurs, filaire — les exemples du preview (code, console, problèmes) restent des illustrations.
