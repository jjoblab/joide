# LOT-03 — Mode Forge : intégration, installation, build

> Lot L3 du plan (conception §11) : intégration du moteur Forge comme bibliothèque
> versionnée, installateur du moteur, build, progression, diagnostics, annulation.

## Ce qui était demandé (critère d'acceptation)

- `BuildBackend` (double Forge) : progression, annulation, diagnostics.
- Installation interrompue puis reprise testée.
- Manifeste falsifié refusé.

## Ce qui est fait

| Élément | Emplacement | Vérification |
|---|---|---|
| Consommation de Forge **comme bibliothèque versionnée** : artefacts `jo.forge:{api,json,client,setup}:0.2.6` publiés vers mavenLocal par `tools/forge-mavenlocal.sh` (checkout épinglé v0.2.6, jamais modifié) ; la CI clone Forge à l'étiquette et publie avant le build | `build-forge/build.gradle`, `tools/forge-mavenlocal.sh`, `.github/workflows/ci.yml` | build vert (local + CI) |
| `ForgeEventTranslator` : événements Forge → JoIDE (journal, tâches, progression, diagnostics, fin), correspondance directe | `build-forge/…/ForgeEventTranslator.java` | 2 tests d'intégration avec le **moteur factice réel** dans un vrai processus séparé (`--fake`) : séquence complète traduite, fin toujours dernière, diagnostics d'erreur avec position |
| `ForgeInstaller` : téléchargement + vérification du manifeste (canal signé, clé ECDSA épinglée), installation atomique (`EngineInstaller` de Forge), état `INSTALLED`/`BROKEN` (altération SHA-256), désinstallation, source locale (miroir hors ligne) | `build-forge/…/ForgeInstaller.java` | 4 tests sécurité (manifeste signé accepté, **falsifié refusé** — 2 vecteurs : mauvaise clé, octets modifiés après signature ; HTTPS strict) + 4 tests installation (atomique, état, **altération → BROKEN pas muet**, désinstallation, filtre ABI) |
| `ForgeEngineManager` : lancement du moteur via `EngineClient.launch` (commande reprise du `EngineSession` de Forge : `EngineMain`, `FORGE_ENGINE_DIR`, classpath `engine.jar` + `lib/*.jar` triés) ; `ForgeBuildSession` : session JoIDE (abonnés, annulation, fin garantie — moteur mort → `Finished` avec journal) | `build-forge/…/ForgeEngineManager.java`, `ForgeBuildSession.java` | compilation + intégration fake (le lancement réel par `app_process` exige un appareil : limites §4) |
| `ForgeBackend` : port `BuildBackend` complet — mode, capacités honnêtes (AAR/ViewBinding/aapt2 : limites affichées, demandes F-05/F-06/F-02), état d'installation, build | `build-forge/…/ForgeBackend.java` | `ForgeBackendTest` + CI |

## Constats sur Forge v0.2.6 (consignés, pas contournés)

- **F-01 confirmée** : aucun plugin `maven-publish` dans les `build.gradle` de
  Forge ; la publication locale est injectée par initialisation Gradle (`-I`,
  `allprojects` + `afterEvaluate`) **sans toucher au checkout épinglé**.
- **`InstallException` vit dans `jo.forge.setup.download`** (pas `install`) —
  constat d'API, documenté ici pour le prochain qui l'utilise.
- Le composant installé est en **lecture seule (+x)** (exigence ART) : le test
  d'altération simule l'appareil compromis (chmod puis remplacement) pour vérifier
  que la détection SHA-256 reste fiable.
- `CancellationToken` doit être non nul dans `EngineInstaller.install` (constat
  NPE) : JoIDE passe un jeton jamais annulé par défaut ; le vrai jeton d'annulation
  arrivera avec l'interface d'installation (L8).
- `BuildFinished` de Forge porte déjà `failedTask` (F-03 partiellement satisfaite) ;
  l'agrégation du premier diagnostic d'erreur dans l'événement de fin reste ouverte
  (F-03) — la traduction actuelle n'agrège pas, l'atelier consomme le flux complet.

## Ce qui exige un appareil réel (limites §4)

- Le lancement du moteur par `app_process` (`DeviceVmLauncher`) et la VM ART.
- Les composants natifs `aapt2-arm64-v8a` / `platform-34` non publiés (F-02) :
  un projet Android (ressources) ne peut pas se construire en mode Forge avant
  leur publication ; un projet Java pur oui.
- Le canal HTTPS de production (clé publique réelle épinglée : voir ci-dessous).

## Clé du canal manifeste (tranchée)

`ForgeInstaller.MANIFEST_PUBLIC_KEY` porte la **clé publique réelle** de Forge
(`ClesForge.CLÉ_PUBLIQUE_MANIFESTE`, module app — clé publique ECDSA X.509, pas un
secret : la remplacer exigerait la clé privée qui reste chez Forge). Les tests la
surchargent par une paire générée localement (`joide.forge.manifestKey`) : les tests
n'attaquent jamais la vraie clé, ils valident le mécanisme de vérification.

## Tests (critère d'acceptation)

- Progression, annulation, diagnostics : couverts par l'intégration fake
  (`translatedEventsFollowTheForgeSequence`, `failedBuildKeepsDiagnosticsAndFailedTask`)
  — l'annulation côté client est couverte par les tests de Forge eux-mêmes
  (`EngineClientRealVmTest.annulationBuildLongPuisArret`), la traduction est pure.
- Manifeste falsifié refusé : **fait** (2 vecteurs d'attaque).
- Installation interrompue puis reprise : la **reprise par plages HTTP** est
  implémentée et testée par Forge (`Downloader`, `.part`, `Range:`) ; côté JoIDE,
  le chemin local complet est testé (`ForgeInstallerTest`). La reprise **à travers
  le port JoIDE** (interruption réelle au milieu, puis relance) sera retestée en L8
  avec l'interface d'installation et son bouton Annuler.

**Bilan : 11 tests de lot verts dans la CI** (2 intégration + 4 sécurité + 4
installation + 1 backend) + 11 tests des lots précédents.
