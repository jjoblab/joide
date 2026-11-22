# JoIDE

**JoIDE** est un IDE Android qui tourne sur le téléphone : éditeur de code, diagnostics,
**deux modes de build** — **Forge** (moteur de build maison, léger) ou **Gradle +
terminal intégré** (environnement Linux complet) — et un assistant IA (conversations,
outils, approbation des actions). Java 17, vues AndroidX + Material 3 (layouts XML +
ViewBinding), `minSdk 28`, `targetSdk 28` (ADR-009).

## Structure du dépôt

| Répertoire | Rôle |
|---|---|
| `core-model/` | Modèle de projet : détection du type (`forge.json` / scripts Gradle), modèles de création |
| `core-store/` | Stockage local : choix de mode, préférences (JSON, écriture atomique) |
| `workbench-spi/` | Ports de l'atelier : `BuildBackend`, événements de build, diagnostics |
| `build-forge/` | Backend Forge : adaptation du client Forge au port `BuildBackend` |
| `build-gradle-core/` | Backend Gradle : lancement via terminal, analyse des diagnostics (testable sans appareil) |
| `app-android/` | L'application : écrans, atelier, assistant des modes, design system |
| `docs/` | Analyses, revue, conception, design system, licences, ADR, fiches d'écrans, registre de références |
| `tools/` | Contrôles locaux : termes interdits, langue, dépendances, API 28, publication locale de Forge |
| `.github/workflows/` | CI : contrôles + build APK |

## Construire

```sh
./tools/setup-local.sh                        # hooks git + liste locale des termes interdits
git clone https://github.com/jjoblab/forge.git ../forge
cd ../forge && ./gradlew publishToMavenLocal  # Forge v0.2.25+ publie nativement (ADR-001)
cd ../joide
./gradlew build                              # complet (SDK Android requis pour app-android)
JOIDE_SKIP_ANDROID=1 ./gradlew test          # cœur JVM uniquement, sans SDK
```

Le mode de build actif est **Forge seul** (ADR-009) : le mode Gradle + terminal
intégré est conçu et conservé (`build-gradle-core`, `terminal-core`) mais pas
encore activé dans l’interface.

## Règles du dépôt

- Identifiants en anglais ; commentaires, documents et commits en français ; textes UI
  en français **dans les ressources** (contrôles `tools/check-langue.sh`).
- Mentions du produit de design/IA de référence interdites partout sauf
  `docs/REFERENCES.md` (contrôles `tools/check-terms.sh`, hooks `tools/hooks/`).
- Dépendances et niveaux d'API contrôlés (`tools/check-deps.sh`, `tools/check-api28.sh`).

## Licence

Apache-2.0 — à l'exception du module terminal (fork de l'émulateur libre, GPL-3.0, isolé
et documenté : voir `docs/licences.md`).
