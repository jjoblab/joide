# Conception — architecture de JoIDE

> Phase 3. Architecture de l'application : modules, graphe de dépendances, assistant des
> deux modes, `BuildBackend`, intégration de Forge, terminal, IA, stockage, processus,
> rapports de diagnostic. Les décisions structurantes sont dans les ADR
> (`docs/adr/ADR-0xx-*.md`) ; les jetons visuels sont dans
> [`design-system.md`](design-system.md) ; les écrans sont dans [`ecrans/`](ecrans/).

## 1. Cadre

| Paramètre | Valeur |
|---|---|
| Produit | JoIDE — IDE Android qui tourne sur le téléphone |
| Package racine | `jo.joide` |
| Langage | Java 17 (identifiants anglais ; commentaires, docs et commits français ; textes UI français dans les ressources) |
| UI | Vues AndroidX + Material 3, **layouts XML + ViewBinding** ; `Fragment` et `ViewModel` autorisés ; aucun écran programmatique quand un XML est possible |
| `minSdk` / `targetSdk` | 28 / **28** (confirmé par ADR-009 : cohérent avec Forge, exécution de binaires téléchargés ; réexaminé par l'ADR-004 pour le mode Gradle) |
| Build | Gradle 8.11.1, AGP 8.7.3 (constat : dernière combinaison à ne pas exiger de JDK courant au-delà de Java 21) |
| Distribution | Hors magasin (GitHub Releases, APK signé) — héritée de la politique de Forge (ADR-003) |
| Licence du dépôt | Apache-2.0 (cohérente avec Forge) |

## 2. Modules et graphe de dépendances (sans cycle)

```
build-logic (conventions Gradle, inclut la règle de dépendances et le contrôle API 28)
   │ (inclus par settings.gradle.kts, n'est pas un module du produit)
   ▼
core-model ──► core-store ──► workbench-spi ◄──────────────┐
 (modèle projet,  (préférences,      (ports : BuildBackend,   │
  détection type,  recents,          agent, journal)          │
  templates)       sessions IA)          ▲                    │
                                        │                    │
build-forge ◄── (jo.forge:api|client|setup)                   │
build-gradle ◄── (terminal, installateur Gradle)              │
                                        │                    │
app-android ◄── (coquille : écrans, atelier, ViewBinding) ────┘
core-model, core-store, workbench-spi : Java pur (tests JVM sans appareil)
build-forge : Java pur, dépend des modules jo.forge publiés
build-gradle : Android (bibliothèque) : vue de terminal + service
app-android : application Android, dépend de tout ce qui précède
```

Règles (vérifiées par la règle de dépendances du build, voir L0) :

1. `core-model`, `core-store`, `workbench-spi`, `build-forge` ne dépendent **d'aucun**
   artefact Android : ils se construisent et se testent sur JVM.
2. `app-android` est le seul module autorisé à dépendre d'`app-android`-level choses UI
   (Material, ViewBinding) et à référencer les deux backends.
3. `build-gradle` est le seul module à dépendre de la bibliothèque de terminal et à
   porter le service de terminal.
4. Aucun module ne dépend du produit de référence (clean-room, voir `REFERENCES.md`).
5. Le graphe est vérifié à chaque build : une dépendance qui introduit un cycle ou viole
   les couches fait échouer le build (`tools/dep-rules.gradle`).

## 3. Assistant des deux modes (premier lancement)

Voir fiche [`ecrans/assistant-modes.md`](ecrans/assistant-modes.md). Résumé :

- Au **premier lancement** (et seulement tant qu'aucun mode n'est installé), un
  assistant en quatre pages présente le choix **Forge / Gradle** sans défaut caché :
  chaque page affiche ce que le mode apporte, ses limites affichées honnêtement, la
  taille à télécharger et l'espace disque nécessaire (constaté depuis le manifeste du
  canal, jamais inventé).
- Le choix est stocké (`core-store`) : `mode-choisi`, `mode-installe`, `etat-install`.
- L'installation du mode suit l'assistant : Forge réutilise le canal signé et les
  composants de Forge (manifeste vérifié contre la clé épinglée, reprise,
  installation atomique) ; Gradle lance l'installateur des composants
  (ADR-004..ADR-011).
- Le mode se change plus tard dans les paramètres : installer l'autre mode, réparer ou
  désinstaller un mode ; chaque projet affiche les modes compatibles (détection §5).

## 4. Abstraction commune : `BuildBackend`

Interface unique consommée par l'atelier (Console, Problèmes, actions Exécuter), quel
que soit le mode. En `workbench-spi` (Java pur) :

```java
public interface BuildBackend {
    Mode mode();                                   // FORGE ou GRADLE
    Capabilities capabilities();                   // ce que le mode sait faire (honnête)
    InstallState installState();                    // NON_INSTALLE, EN_COURS, INSTALLE, CASSE
    CompletableFuture<BuildSession> build(BuildRequest requete);
    void cancel(String buildId);
    Flux<Diagnostic> diagnostics();                // agrégé build + LSP côté atelier
}

public interface BuildSession {                    // un build lancé
    String id();
    Flux<EvenementBuild> evenements();             // LOG, TASK_STATE, PROGRESS, DIAGNOSTIC, FINISHED
}
```

- `ForgeBackend` (module `build-forge`) adapte `jo.forge.client.EngineClient` :
  `BuildEvent` (Forge) → `EvenementBuild` (JoIDE). La correspondance est directe car
  Forge expose déjà journal, états de tâches, progression, diagnostics structurés
  (fichier/ligne/colonne/code/outil) et fin de build.
- `GradleBackend` (module `build-gradle`) lance les builds **dans le terminal**
  (processus séparé), parse la sortie (ADR-008), et se connecte au même contrat.
- **Capacités** : chaque backend publie honnêtement ce qu'il sait faire (AAR ? ViewBinding ?
  projets Gradle existants ? terminal ?). L'interface n'offre jamais un bouton que le
  mode ne peut pas tenir ; un besoin manquant chez Forge devient une demande formelle
  (`DEMANDES-FORGE.md`), jamais un contournement silencieux.
- Les diagnostics LSP (brique dédiée) arrivent par un canal séparé (atelier) et sont
  fusionnés dans la section Problèmes ; les diagnostics de build arrivent par le
  `BuildBackend`.

## 5. Détection des projets (core-model)

- **Projet Forge** : `forge.json` à la racine → compatible mode Forge.
- **Projet Gradle** : `build.gradle`, `build.gradle.kts`, `settings.gradle`,
  `settings.gradle.kts` → compatible mode Gradle.
- Les deux → compatible avec les deux modes (l'utilisateur choisit à l'ouverture).
- Aucun des deux → dossier de fichiers ; JoIDE propose la création d'un projet depuis
  un modèle ou l'import.
- Quand le mode actif est incompatible avec le projet ouvert : la strip d'avis de
  l'atelier (REF-018) explique clairement la situation et propose **changer de mode**
  (paramètres) ou **importer vers l'autre format** (Forge possède un lecteur statique de
  scripts Gradle ; la conversion inverse est hors périmètre, voir limites).

## 6. Intégration des briques de l'auteur (éditeur, LSP, aperçu XML)

- **Éditeur + coloration** : brique *céleris* (JitPack constaté disponible) — intégrée
  dans le fragment Éditeur de l'atelier, avec la **barre de symboles** JoIDE au-dessus du
  clavier (flick-up, trackpad de caret — exigence REF-021, implémentée par nous au-dessus
  de la brique).
- **LSP** : brique *code-lsp* — diagnostics vers la section **Problèmes**, complétion,
  sauts. Le calendrier exact (vagues) et les versions d'intégration seront lus dans la
  génération 1 lors du câblage L5 (conformément au cahier des charges §2, et à la
  consigne du propriétaire : céleris / code-lsp / LayoutLib).
- **Aperçu XML** : brique *LayoutLib* — rendu de la mise en page dans un panneau de
  l'atelier (L5).
- Ces briques ne sont jamais forkées ni modifiées en silence : tout écart constaté
  passe par une demande formelle au propriétaire (même règle que Forge,
  `DEMANDES-FORGE.md` est le registre Forge ; les demandes aux briques seront portées
  dans `limites-et-suite.md` jusqu'à leur registre propre).

## 7. Terminal intégré (mode Gradle)

Fondé sur un **fork de l'application Termux libre** (GPL-3.0) et de sa bibliothèque
d'émulateur de terminal — voir `licences.md` (ADR-005) pour l'analyse complète et le
choix du mécanisme d'intégration. Fonctionnalités imposées (cahier des charges §4.3) :
plusieurs sessions, clavier adapté (Ctrl, Échap, flèches, Tab), copier-coller,
redimensionnement, couleurs 256, persistance des sessions en arrière-plan (service de
premier plan, notification persistante, ADR-007), exécution de `gradle` et des outils.

## 8. IA (L7)

Conforme à `analyse-ia.md` (points repris) et aux règles du cahier des charges :

- Module `ai` (Java pur + transport HTTP/SSE) : fournisseurs (un laboratoire + une
  passerelle compatible OpenAI), modèle persisté par fournisseur, liste vivante avec
  repli statique.
- Boucle d'agent : deux moitiés de prompt (stable/volatile), bornage à l'arrivée,
  compactage à hystérésis, plafond d'itérations avec tour de clôture, appariement
  appel/résultat.
- Outils sur **port espace de travail** étroit (`AgentWorkspace`), lectures parallèles /
  mutations séquentielles, **porte de permission unique** (trois modes persistés),
  diff à l'approbation, annulation par tour avec aveu de l'irréversible.
- **Clés chiffrées** (Android Keystore via la bibliothèque `security-crypto` ;
  repli en mémoire si le keystore est indisponible) — écart voulu REF-123.
- L'agent exécute des builds **via le `BuildBackend`** du mode actif — jamais un moteur
  concurrent (§4.8 du cahier des charges).
- Résistance à l'injection : règle d'ancrage + marquage du contenu réseau + porte de
  permission (REF-124).

## 9. Stockage et sécurité

| Donnée | Emplacement | Règle |
|---|---|---|
| Projets | stockage privé `filesDir/projects/` (ADR-010 : privé par défaut ; SAF pour l'import/export) | Le terminal (mode Gradle) y accède par le préfixe du bootstrap (ADR-005) |
| Préférences, recents, mode choisi | `filesDir/` via `core-store` | Aucun secret |
| Clés API IA | EncryptedSharedPreferences (clé Keystore) | Jamais dans les logs ; rédaction dans les rapports (§10) |
| Moteur Forge | `filesDir/forge/` (convention ForgeHome) | Composants vérifiés SHA-256 + manifeste signé |
| Environnement Gradle | `filesDir/gradle-env/` (préfixe du bootstrap) | Vérifié à l'installation (ADR-011) |

Règles générales : HTTPS obligatoire ; vérification d'intégrité et d'authenticité de tout
composant téléchargé ; extraction d'archives protégée contre la traversée de chemin
(l'extracteur de Forge est réutilisé par le module `setup`) ; aucun secret dans les logs.

## 10. Processus, journal des écroulements, diagnostic

- Les travaux lourds ne tournent **jamais** dans le processus UI : moteur Forge = VM
  séparée (`app_process`, contrat Forge) ; Gradle/terminal = processus du terminal ;
  IA = service Android avec son propre pool (le réseau n'est jamais sur le fil UI).
- **Journal des écroulements** : capture des plantages des trois zones (UI par le
  gestionnaire par défaut, moteur par le journal stderr de Forge, terminal par le code
  de sortie), borné, écrit sous `filesDir/journal/`.
- **Rapport de diagnostic copiable** : versions (app, Forge, Gradle, briques), état
  d'installation des modes, journal borné du dernier build, journal des écroulements,
  **secrets rédigés** (heuristique : jetons > 16 caractères proches de « key/token/secret
  » remplacés par `[rédigé]`). Bouton « Copier » + partage.

## 11. Plan de lots et critères d'acceptation

| Lot | Contenu | Critère d'acceptation |
|---|---|---|
| L0 | Squelette : modules, CI, hooks et contrôle des termes interdits, règles de langue, contrôle API 28, règle de dépendances | `./gradlew build` vert sur JVM ; le contrôle des termes échoue sur un terme injecté ; commit refusé par le hook |
| L1 | Design system, thèmes, composants, popups ancrés, coquille de navigation | **Jalon J1** : prévisualisation HTML des écrans clés validée ; la coquille navigue (accueil → atelier → paramètres) avec gabarits sans doublon d'en-tête |
| L2 | Premier lancement : choix du mode, stockage, modèle d'assistant commun | Tests JVM des états de l'assistant ; test de câblage : le premier lancement présente les deux modes sans défaut caché |
| L3 | Mode Forge : intégration, installation du moteur, build, progression, diagnostics | BuildBackend (double Forge) : progression, annulation, diagnostics ; installation interrompue puis reprise testée ; manifeste falsifié refusé |
| L4 | Projets : accueil, création depuis modèles, import, détection du type, arborescence | Détection forge.json / build.gradle* testée ; création depuis un modèle produit un projet buildable |
| L5 | Atelier : éditeur (brique), onglets, tiroirs, bottom sheet 4 sections, LSP | **Jalon J2** : un projet s'édite et se construit en mode Forge ; Console et Problèmes alimentés |
| L6 | Mode Gradle : composants, installateur, terminal intégré, builds, diagnostics | **Jalon J3** : terminal réel multi-sessions ; build Gradle lancé depuis JoIDE avec sortie dans la Console |
| L7 | IA : fournisseurs, conversation, contexte, outils, approbation, clés chiffrées | Conversation avec au moins un fournisseur (mock en test) ; approbation des actions de l'agent ; clés chiffrées |
| L8 | Paramètres, changement de mode, mises à jour, réparation, désinstallation, rapport de diagnostic | Bascule de mode testée ; rapport copiable avec secrets rédigés |
| L9 | Qualité : tests, performances, accessibilité, sécurité, publication | **Jalon J4** : APK signé produit par la CI ; contrôles passants |

Chaque lot : consultation de la référence avant (design/IA), implémentation,
comparaison après consignée dans `REFERENCES.md`, tests, ADR le cas échéant, rapport
`docs/lots/LOT-xx.md`, commit atomique.

## 12. Décisions structurantes renvoyées aux ADR

| Décision | ADR |
|---|---|
| Choix du mode à l'installation, changement, réparation | ADR-002 |
| Distribution hors magasin (héritée de Forge) | ADR-003 |
| Licences Termux (application, bibliothèque terminal, bootstrap) | ADR-005 |
| Préfixe et bootstrap du terminal | ADR-006 |
| Exécution de binaires et `targetSdk` | ADR-009 (confirme 28) |
| Processus fantômes Android 12+ | ADR-007 |
| Mémoire : Gradle et son daemon sur téléphone | ADR-012 |
| SDK Android : licences, redistribution, binaires arm64 | ADR-010 |
| Stockage des projets | ADR-010 (privé par défaut) |
| Intégrité des composants Gradle (réutilisation du module setup de Forge) | ADR-011 |
| Diagnostics Gradle (moyen le plus fiable) | ADR-008 |

## 13. Hypothèses retenues (faute de pouvoir interroger le propriétaire)

1. `targetSdk 28` confirmé pour les deux modes (ADR-009) ; impact : distribution hors
   magasin uniquement, assumé (ADR-003).
2. AGP 8.7.3 / Gradle 8.11.1 pour l'environnement Gradle embarqué (distribué aux
   utilisateurs, pas pour construire JoIDE lui-même — le bac à sable utilise Java 21,
   Gradle wrapper 8.11.1).
3. Le fork de Termux est maintenu **dans ce dépôt** sous `jo.joide.terminal.*`
   (apprentissage du code, modifications) plutôt que consommé en bibliothèque : c'est le
   seul point où un code externe GPL entre dans le dépôt (analyse complète dans
   `licences.md`).
4. Les versions exactes des briques céleris / code-lsp / LayoutLib seront épinglées à
   L5 après lecture des versions d'intégration dans la génération 1 (autorisée alors).
