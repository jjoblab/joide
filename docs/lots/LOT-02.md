# LOT-02 — Assistant des modes (premier lancement)

> Lot L2 du plan (conception §11) : choix du mode au premier lancement, stockage du
> choix, modèle d'assistant commun. Jalon partiel de J1 ; l'installation des modes
> (L8) et l'atelier (L5) sont hors périmètre.

## Ce qui était demandé

- Assistant plein écran à la première installation : pages de présentation des deux
  modes (apports, limites honnêtes, tailles), choix explicite — **aucun défaut caché**
  (ADR-002).
- Tests JVM des états de l'assistant ; test de câblage : le premier lancement
  présente les deux modes.

## Ce qui est fait

| Élément | Emplacement | Vérification |
|---|---|---|
| Modèle d'états de l'assistant (pages, sélection provisoire, confirmation, réinitialisation) | `core-store/ModeSetupModel.java` | 4 tests JVM verts (`ModeSetupModelTest`) |
| Fragment 4 pages (bienvenue → Forge → Gradle → confirmation), sections à visibilité séquentielle — **aucune dépendance nouvelle** (pas de pager : la règle de dépendances des modules l'interdit sans besoin) | `app-android/…/ModeSetupFragment.java` + `layout/mode_setup_fragment.xml` | APK construit par la CI |
| Textes des pages (apports + limites honnêtes des deux modes) en ressources | `res/values/strings.xml` | `check-langue` : textes UI en `@string/` |
| Choix persisté : choisi ≠ installé (l'installation est un acte séparé, L8) | `ModeSetupModel.confirmChoice()` | test `confirmationPersistsChosenModeNotInstalled` |

## Décisions du lot

1. **Pas de ViewPager2** : une seule mise en page, quatre sections à visibilité
   séquentielle. Motif : la règle de dépendances interdit une nouvelle bibliothèque
   pour un besoin couvert par la mise en page seule ; l'ordre de lecture reste
   imposé (bienvenue → modes → confirmation).
2. **Modèle JVM pur** (`core-store`) : les états de l'assistant sont testables sans
   appareil, la coquille ne fait que refléter le modèle (fragment fin).
3. **Tailles des modes** : affichées dans les textes de présentation via les limites
   constatées (capacités) ; les tailles exactes depuis le manifeste vérifié
   arriveront avec l'installateur L3/L8 (le manifeste n'est pas encore consulté au
   premier lancement — consigné comme suite, pas masqué).

## Tests (critère d'acceptation)

`ModeSetupModelTest` (4) : premier lancement sans défaut caché, sélection
provisoire ne persiste rien, confirmation persiste le mode choisi non-installé,
réinitialisation ramène l'assistant. **Verts dans la CI.**

## Reste pour ce lot

- Brancher la taille de téléchargement Forge réelle (manifeste vérifié) dans la page
  Forge de l'assistant — dès que l'installateur L3 est consulté au premier
  lancement (L8 : paramètres d'installation).
