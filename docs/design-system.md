# Design system — jetons, thèmes et composants JoIDE

> Phase 3. Jetons visuels, thèmes clair/sombre, composants Material 3 personnalisés,
> popups ancrées, états, règles d'accessibilité. Les principes repris du produit de
> référence sont cités en `REF-xxx` ([`REFERENCES.md`](REFERENCES.md)) ; les valeurs
> chiffrées ci-dessous sont **nôtres** (clean-room : aucune couleur hex, aucune
> dimension, aucun nom repris).

## 1. Principes directeurs

1. **Un seul élément saturé par écran** : la couleur principale vit dans **une** action
   (bouton primaire, carte « Reprendre ») ; les listes restent neutres, la couleur vit
   dans la tuile d'icône, jamais dans le fond des cartes (REF-003).
2. **Couleur = information** : hiérarchie par la typographie et le contraste de poids,
   pas par des fonds colorés (REF-014).
3. **Densité honnête** : densité IDE obtenue par la hiérarchie et le regroupement,
   jamais par des cibles tactiles rétrécies (48 dp minimaux — écart voulu REF-024).
4. **Retour avant décor** : chaque action a un retour visible (snackbar à durée
   différenciée : 8 s si annulable, 5 s sinon — REF-019).
5. **États vides et erreurs de première classe** : encadré pointillé + phrase + action
   concrète, différé jusqu'à confirmation du chargement (REF-017) ; strip d'avis unique
   pour tout l'avertissable (REF-018).

## 2. Jetons de couleur

Deux familles séparées (principe REF-013) :

**Famille UI (rôles Material 3)** — utilisée pour tous les contrôles :

| Jeton | Clair | Sombre | Usage |
|---|---|---|---|
| `primary` / `onPrimary` | bleu nuit profond / blanc cassé | bleu clair uplifting / bleu très sombre | boutons primaires, états actifs (le seul saturé) |
| `onPrimaryContainer` / `primaryContainer` | bleu très clair / bleu nuit | bleu nuit / bleu clair atténué | tuiles d'icônes, chips sélectionnées |
| `surface` / `onSurface` / `onSurfaceVariant` | blanc chaud / gris encre / gris moyen | gris nuit chaud / gris clair / gris atténué | fonds, texte principal, texte secondaire |
| `outline` / `outlineVariant` | gris moyen / gris clair | gris sombre / gris très sombre | bordures d'états vides, séparateurs |
| `error` / `onError` / `errorContainer` | rouge signal / blanc / rose pâle | rouge doux / noir / rouge sombre | erreurs, diagnostics de sévérité erreur |
| `tertiary` / `tertiaryContainer` | vert boutique / vert profond | vert doux / vert sombre | succès de build, « à jour » |

Les valeurs sont générées par l'outil de palettes Material 3 (source de teinte
« bleu nuit » ; le même outil, la même méthode que le produit de référence utilise —
pas ses valeurs). Thème clair et thème sombre suivent le réglage système ;
`uiMode` dans `configChanges` (pas de recréation à la rotation ni au basculement de
thème, REF-002).

**Famille domaine IDE** (fichiers `values/ide_colors.xml`, distincts des rôles M3) :

| Jeton | Usage |
|---|---|
| `ide_editor_bg` | fond de l'éditeur (plus sombre que `surface` en sombre) |
| `ide_console_bg` | fond des sections Console / Logcat (sombre **dans les deux thèmes**, choix assumé : une console reste une console) |
| `ide_console_fg_*` | couleurs du transcript (les **mêmes** dans les deux thèmes — exigence de lisibilité REF-024 : le transcript est conçu pour un fond sombre unique) |
| `ide_diag_error` / `ide_diag_warning` / `ide_diag_info` | rayures des squiggles et puces de Problèmes (déclinés clair/sombre) |
| `ide_success` / `ide_failure` / `ide_skipped` / `ide_up_to_date` | états de tâches (Console) |

**Syntaxe** : fournie par la brique d'éditeur (jeu de couleurs paramétrable plus tard ;
consigné dans limites).

## 3. Typographie

Échelle Material 3 adaptée, tout en `sp`, **aucun conteneur de texte à hauteur fixe**
(REF-014) :

| Style | Taille / poids | Particularité |
|---|---|---|
| `display` (titres d'accueil) | 32-36 sp, poids **léger** | tracking négatif léger : les titres se lisent comme des « pièces » (REF-014) |
| `headline` (titres d'écran) | 24-28 sp, normal | |
| `title` (titres de section/fiche) | 16-18 sp, demi-gras | |
| `body` | 14-16 sp, normal | interligne 1,4 |
| `label` (chips, boutons) | 12-14 sp, demi-gras | |
| `eyebrow` | 11-12 sp, demi-gras, majuscules, espacement large | petites majuscules au-dessus des titres (REF-014) — toujours en `sp`, jamais en `dp` |

## 4. Formes et élévations

- Échelle de coins (grands : conteneurs ; petits : contenus — **contraste de rayon par
  élément**, REF-015) : conteneurs 20 dp, cartes 16 dp, tuiles 12 dp, chips/boutons
  pleins 48 dp de hauteur, coins 24 dp ; **cartes à coins alternés** (grands coins
  extérieurs, petits intérieurs) comme signature visuelle des listes.
- Élévations M3 : 0 (surfaces), 1 (cartes sur surface), 3 (bottom sheet), 4 (popups
  ancrées) ; en thème sombre, l'élévation est rendue par l'éclaircissement de surface
  (pas d'ombre).

## 5. Mouvement

Tokens (dans `Motion` du projet, une **porte reduce-motion dans le token** — écart
voulu REF-025) :

| Jeton | Valeur | Usage |
|---|---|---|
| `duree_rapide` | 120 ms | apparitions discrètes, changements d'état |
| `duree_base` | 200 ms | transitions d'écrans, sheets |
| `duree_lente` | 350 ms | mouvements d'atelier (tiroirs, dock) |
| `ressort_spatial` | sous-amorti | position/taille (glissements de tiroirs) |
| `ressort_effet` | amorti critique | couleur/alpha — une couleur qui « overshoot » se lit comme un scintillement (REF-016) |
| `porte_reduce_motion` | bool | si activé : durées → 0, ressorts → coupés ; **vérifiée à chaque utilisation du token** |

## 6. Espacements et gabarits

| Jeton | Valeur |
|---|---|
| `gouttiere` (marge d'écran) | 16 dp (20 dp sur tablette) |
| `padding_carte` | 16 dp |
| `gouttiere_liste` | 12 dp entre éléments |
| `hauteur_barre_atelier` | 52 dp (référence REF-006, à nous) |
| `hauteur_barre_symboles` | 46 dp |
| `hauteur_barre_sections` (bottom sheet replié) | 56 dp |
| cible tactile minimale | **48 dp** (44 dp au pire avec zone invisible étendue) |
| largeur max contenu | 720 dp centré |

## 7. Composants personnalisés (répertoire `app-android`)

| Composant | Rôle | Référence |
|---|---|---|
| `VueCarteProjet` | carte de projet : tuile tonale (icône du projet), nom, une ligne de méta, menu ⋯ | REF-003 |
| `VueSegmentsComptes` | segments filtrants avec comptes, même vides | REF-003 |
| `VueEtatVide` | encadré pointillé + glyphe + phrase + action | REF-017 |
| `VueStripAvis` | strip unique d'avertissements de l'atelier (ligne simple / carte repliée / compte cliquable) | REF-018 |
| `BarreSymboles` | barre au-dessus du clavier : flick-up = alternatif, maintien-glisser = trackpad de caret | REF-021 |
| `PopupMenuAncree` | popup ancrée (générique) — **jamais de dialogue centré pour un menu/sélecteur/historique** (règle maison) | — |
| `BottomSheetSections` | bottom sheet de l'atelier : en-tête unique à onglets (Console / Problèmes / Journal / Logcat), **sans doublon** (règle maison : les 4 sections ont des responsabilités séparées) | — |
| `VueDiagnostic` | ligne de diagnostic cliquable (icône de sévérité, fichier:ligne, message) | REF-007 |

## 8. Popups ancrées (règle maison du cahier des charges)

- Tout menu, sélecteur ou historique est une **popup ancrée à l'élément déclencheur**
  (`PopupMenu` / `PopupWindow` avec ancrage et remplacement sur écran), jamais un
  `AlertDialog` centré — sauf les confirmations destructives (suppression de projet),
  qui restent des dialogues nommants.
- Les popups ancrées héritent de `surfaceContainer` + élévation 4, coins 12 dp,
  apparaissent par `duree_rapide` sous l'élément (au-dessus si pas de place), se
  referment au toucher extérieur ou à la touche retour.

## 9. États

- **Vide** : `VueEtatVide`, **différé de 300 ms** après confirmation du chargement
  (REF-017).
- **Chargement** : indicateur linéaire indéterminé en haut de liste (jamais de spinner
  centré sur un écran déjà structuré).
- **Erreur** : la strip d'avis ; erreur fatale d'écran : état vide décliné « quelque
  chose a échoué » + action réessayer + détails.
- **Désactivé** : atténué par teinte, jamais par alpha de texte seul (REF-024).

## 10. Règles d'accessibilité

1. **Cible tactile 48 dp** minimale — la densité ne rétrécit jamais la cible (écart
   voulu REF-024).
2. `contentDescription` **obligatoire** sur tout bouton-icône (revue + contrôle du
   composant de base, REF-023) ; les glyphes décoratifs la portent à `null`.
3. Item de navigation = **conteneur entier = un seul nœud d'accessibilité fusionné**
   (REF-023) ; rôles `Tab`/`Selected` posés sur les onglets de sections.
4. Tout en `sp`, aucun conteneur à hauteur fixe (2× de police wrappe, REF-024) ;
   l'éditeur garde son propre facteur de zoom.
5. Contraste : les couleurs du transcript (Console/Logcat) sont conçues pour **un** fond
   sombre unique dans les deux thèmes (exigence REF-024) ; les diagnostics déclinés
   clair/sombre.
6. Reduce-motion : porte dans le token (§5).
7. L'éditeur de code reste **LTR inconditionnel** (REF-020) ; le reste de l'UI suit la
   locale (française en L1-L9, RTL hors périmètre — limites).

## 11. Validation

Les gabarits des écrans clés sont prévisualisés en HTML
(`docs/mockups/`, miroir livré dans le répertoire de téléchargement) **avant** le code
(jalon J1) : accueil, assistant des modes, atelier téléphone (tiroirs + dock + bottom
sheet), écran d'installation, paramètres de mode. Chaque maquette porte les jetons de ce
document ; l'implémentation XML est comparée à la maquette au moment de L1 et l'écart
consigné dans le rapport de lot.
