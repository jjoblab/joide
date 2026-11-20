# LOT-05 — Atelier : éditeur (brique), tiroir fichiers, sections, build

> Lot L5 du plan (conception §11). **Jalon J2** : un projet s'édite et se
> construit en mode Forge ; Console et Problèmes alimentés.

## Ce qui est fait

| Élément | Emplacement | Vérification |
|---|---|---|
| **Brique céleris intégrée** (`jo.celeris:android-view/widgets/host + core-syntax-grammars:2.1.0`) : vue `EditorView` + `PopupLayer` + façade `CelerisEditor.attach(view, popups, kit)` + `open(nom, texte)` (montage exact du sample de la brique), 32 langages via `builtinPacks()` | `app-android/build.gradle`, `WorkbenchFragment.attachEditor` | CI verte `8cd4c33` : celeris publié vers mavenLocal par la CI (checkout cloné), APK construit avec l'éditeur |
| Consommation **comme bibliothèque versionnée épinglée** (2.1.0 du checkout cloné, ADR-001 bis) : le checkout n'est jamais modifié ; la CI exécute `./gradlew publishToMavenLocal` de celeris avant le build JoIDE | `.github/workflows/ci.yml`, `tools/celeris-mavenlocal.sh` | étape CI verte |
| Tiroir fichiers : liste plate du projet, ouverture dans l'éditeur (premier fichier ouvert par défaut) | `WorkbenchFragment.setupFilesList` | APK |
| **Quatre sections câblées** (bottom sheet à en-tête unique) : Console, Problèmes, Journal, Logcat — une seule en-tête, aucun doublon (règle §3) | `layout/workbench_fragment.xml` | APK |
| **Bouton Construire (J2)** : `ForgeBackend.build()` → Console (journal, tâches, progression, fin) et Problèmes (diagnostics avec sévérité/position) ; échecs affichés honnêtement (moteur non installé → message explicite, jamais muet) | `WorkbenchFragment.runForgeBuild/onBuildEvent` | APK (le build réel forge.json→APK sur appareil reste la vérification J2 complète : limites §4) |
| `compileSdk 36` (l'AAR celeris 2.1.0 l'exige) ; **targetSdk reste 28** (ADR-009) ; `check-api28` vert | `app-android/build.gradle` | contrôle + CI |

## Décisions du lot

1. **Celeris sans tag JitPack** (constat : `git tag` vide dans le dépôt) : la
   consommation passe par mavenLocal du checkout cloné — même mécanisme que
   Forge (ADR-001). **Demande B-01** : pousser un tag (p.ex. `v2.1.0`) pour
   activer la voie JitPack documentée (`jitpack.yml` existe déjà).
2. **Le clavier délégué / barre de symboles** (`KeyboardPopup`, `OutlineView`)
   existent dans `android-widgets` (constaté) : le câblage au-dessus du clavier
   (flick-up REF-021) est reporté au lot terminal — c'est une interaction
   matérielle à vérifier sur appareil.
3. **code-lsp (LSP)** : le moteur 1.15.0 exige le registre authentifié GitHub
   Packages (demande B-02) + le compagnon ecj (~3,2 Mo) : intégré au lot L8/terminal
   selon la réponse du propriétaire — consigné, pas contourné.
4. **LayoutLib (aperçu XML)** : prêt sur JitPack (tag v3.7.1, `jitpack.yml`
   écrit pour JoIDE) MAIS licence « interne, aucun droit sans autorisation » →
   **demande B-03** (accord écrit) avant embarquement. Aperçu non branché en L5.

## Ce qui exige un appareil réel

- Le rendu réel de l'éditeur (Canvas celeris) et les interactions clavier
  (flick-up, trackpad caret).
- Le build complet d'un projet sur appareil (moteur Forge `app_process`) : la
  partie JVM (traduction, sessions) est couverte par les tests L3.

## Bilan

CI verte avec celeris embarqué ; J2 tenu côté logique (le build lancé depuis
l'atelier alimente Console/Problèmes), la vérification end-to-end reste
matérielle.
