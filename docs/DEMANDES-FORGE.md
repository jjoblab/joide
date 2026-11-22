# Demandes à Forge

> Registre des demandes formelles adressées à Forge (`jjoblab/forge`, Apache-2.0). Le
> cahier des charges (§4.2) interdit tout contournement silencieux : un besoin manquant
> dans Forge fait l'objet d'une demande ici ; il est ensuite suivi jusqu'à livraison,
> contourné par le mode Gradle, ou abandonné. Statuts : **ouvert / accepté / livré /
> refusé / contourné / abandonné**.

| # | Date | Lot concerné | Demande | Contexte précis | Statut |
|---|---|---|---|---|---|
| F-01 | 2026-11-20 | L0-L3 | **Publication Maven des modules `api`, `json`, `client`, `setup`** | Aucune publication Maven en v0.2.6 (pas de `maven-publish`) alors que le cahier des charges §4.2 impose une consommation « comme bibliothèque versionnée ». **Livré partie locale le 2026-11-22** : Forge v0.2.25 embarque `maven-publish` sur ses cinq modules bibliothèque — `./gradlew publishToMavenLocal` suffit, l'injection par script d'initialisation est retirée. La publication sur un dépôt public (JitPack/GitHub Packages) reste ouverte. | **livré (mavenLocal)** — dépôt distant ouvert |
| F-02 | 2026-11-20 | L3-L5 | **Composants `aapt2` (arm64) et `platform-*` publiés et vérifiables** | Non publiés en v0.2.6 (garde juridique de Forge tant que le prébuilt aapt2 ANDROID n'était pas produit). **Livré** : `platform-34` publié depuis v0.2.7, `aapt2-arm64-v8a` depuis v0.2.8 (constaté sur le manifeste signé de la release v0.2.24) — un projet Android complet (ressources, dex, APK signé) se construit en mode Forge. | **livré** (v0.2.8, vérifié v0.2.24) |
| F-03 | 2026-11-20 | L3 | **Diagnostic structuré de fin de build** : conserver `failedTask` + premier diagnostic bloquant dans un événement de fin | Le `BuildFinished` actuel porte déjà `failedTask` (constat `BuildEvent.java`) : demande satisfaite pour le champ tâche ; la demande porte sur l'**agrégation du premier diagnostic d'erreur** dans l'événement de fin, pour l'affichage en tête de Console sans double consommation du flux. | **ouvert** (mineur) |
| F-04 | 2026-11-20 | L4 | **Reconnaissance des projets Gradle par l'import statique** (couverture des scripts Kotlin `*.kts`) | L'import Gradle statique de Forge (étape 13) lit les scripts **Groovy** ; les scripts Kotlin (`build.gradle.kts`, `settings.gradle.kts`) ne sont pas couverts (limite assumée de Forge). JoIDE détecte les projets Gradle des deux saveurs (§4.5) mais la conversion `kts` → `forge.json` ne sera pas fidèle avant couverture Forge. Entre-temps : le projet est signalé « compatible Gradle uniquement » avec explication honnête. | **ouvert** |
| F-05 | 2026-11-20 | L5 | **Prise en compte des AAR** | Les limites connues de Forge v0.2.6 incluaient « AAR non supportés ». **Livré** : résolution et assemblage des AAR opérationnels — le rapport appareil Forge v0.2.23 construit `application-compose` avec 41 AAR au classpath. Le backend Forge de JoIDE annonce `aar = true` depuis le passage à v0.2.25 ; les templates AndroidX/Compose sont débloqués. | **livré** (v0.2.16+, vérifié v0.2.24) |
| F-06 | 2026-11-20 | L5 | **ViewBinding (génération des classes de liaison)** | Non livré en v0.2.6. **Livré en Forge v0.2.18** : tâche `generateViewBinding` (une classe `<Layout>Binding` par layout), opt-in par le bloc `buildFeatures` de `forge.json` — sémantique AGP 8. Le backend annonce `viewBinding = true`. | **livré** (v0.2.18) |

## Règles du registre

1. Toute limite de Forge rencontrée pendant un lot est ajoutée ici **avant** toute
   décision de contournement ; le contournement (souvent : « le mode Gradle couvre ») est
   une réponse enregistrée, pas un silence.
2. Les demandes acceptées par Forge sont suivies jusqu'à la version qui les livre ; à la
   livraison, l'entrée passe à **livré** et la dépendance de Forge de JoIDE est mise à
   jour (champ à jour dans l'ADR-001).
3. Les demandes refusées par Forge deviennent soit des **limites permanentes** du mode
   Forge (consignées dans `limites-et-suite.md`), soit des bascules vers le mode Gradle
   documentées dans les fiches d'écran.
4. Le registrecible est daté : chaque changement d'état porte sa date.
