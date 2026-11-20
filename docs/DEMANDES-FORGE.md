# Demandes à Forge

> Registre des demandes formelles adressées à Forge (`jjoblab/forge`, Apache-2.0). Le
> cahier des charges (§4.2) interdit tout contournement silencieux : un besoin manquant
> dans Forge fait l'objet d'une demande ici ; il est ensuite suivi jusqu'à livraison,
> contourné par le mode Gradle, ou abandonné. Statuts : **ouvert / accepté / livré /
> refusé / contourné / abandonné**.

| # | Date | Lot concerné | Demande | Contexte précis | Statut |
|---|---|---|---|---|---|
| F-01 | 2026-11-20 | L0-L3 | **Publication Maven des modules `api`, `json`, `client`, `setup`** | Forge déclare `group = 'jo.forge'`, `version = '0.2.6'` (constat dans `api/build.gradle` etc.) mais aucune publication Maven n'existe (pas de `maven-publish`, rien sur JitPack au 2026-11-20). Le cahier des charges §4.2 impose une consommation « comme bibliothèque versionnée ». Tant que la publication n'existe pas, JoIDE consomme Forge par **inclusion de build composite** (`includeBuild` du clone épinglé `v0.2.6`), documentée dans l'ADR-001. La publication (JitPack suffit : le dépôt est public et Gradle-capable) reste préférée. | **ouvert** (contourné par composite) |
| F-02 | 2026-11-20 | L3-L5 | **Composants `aapt2` (arm64) et `platform-*` publiés et vérifiables** | Forge v0.2.6 publie `engine-core`, `java-compiler`, `dexeur`, `signeur` (manifeste v0.2.6 constaté) mais **pas** `aapt2-arm64-v8a` ni `platform-34` (garde juridique, docs/limites-et-suite de Forge : « installez le composant… » tant que le prébuilt aapt2 ANDROID arm64 n'est pas produit). Conséquence pour JoIDE : en mode Forge, un projet **Android** (ressources) ne peut pas se construire avant leur publication ; un projet Java pur se construit. L'assistant et les capacités du `ForgeBackend` l'affichent honnêtement ; le mode Gradle couvre le besoin entre-temps. | **ouvert** |
| F-03 | 2026-11-20 | L3 | **Diagnostic structuré de fin de build** : conserver `failedTask` + premier diagnostic bloquant dans un événement de fin | Le `BuildFinished` actuel porte déjà `failedTask` (constat `BuildEvent.java`) : demande satisfaite pour le champ tâche ; la demande porte sur l'**agrégation du premier diagnostic d'erreur** dans l'événement de fin, pour l'affichage en tête de Console sans double consommation du flux. | **ouvert** (mineur) |
| F-04 | 2026-11-20 | L4 | **Reconnaissance des projets Gradle par l'import statique** (couverture des scripts Kotlin `*.kts`) | L'import Gradle statique de Forge (étape 13) lit les scripts **Groovy** ; les scripts Kotlin (`build.gradle.kts`, `settings.gradle.kts`) ne sont pas couverts (limite assumée de Forge). JoIDE détecte les projets Gradle des deux saveurs (§4.5) mais la conversion `kts` → `forge.json` ne sera pas fidèle avant couverture Forge. Entre-temps : le projet est signalé « compatible Gradle uniquement » avec explication honnête. | **ouvert** |
| F-05 | 2026-11-20 | L5 | **Prise en compte des AAR** | Les limites connues de Forge incluent « AAR non supportés, pas de ViewBinding ». Le mode Forge affichera ces limites dans les capacités du backend et à la création de projet (pas de template AndroidX en mode Forge tant que non couvert). Aucun contournement en silence. | **ouvert** |
| F-06 | 2026-11-20 | L5 | **ViewBinding (génération des classes de liaison)** | Même famille que F-05 : dépend de la chaîne de ressources et d'APT. C'est **le** point qui conditionne les modèles de projets Android modernes en mode Forge. | **ouvert** |

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
