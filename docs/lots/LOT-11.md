# LOT-11 — Les LSP câblés et l'écran d'indexation de la référence

> Lot hors plan (demande du propriétaire 2026-11-24) : « Je n'ai pas vu l'écran
> indexation comme la référence. Assures-toi que les LSP sont câblés, vérifie que
> c'est la dernière version de celeris qui est utilisée. » Le constat qui fonde le
> lot : l'indexation L10 (core-index) est un pass local quasi instantané sur un
> projet sans build — **la puce n'apparaissait jamais**. La référence, elle,
> indexe les bibliothèques et le SDK à l'ouverture (le pass est LE travail lourd
> de l'ouverture, séquencé à froid dans IdeServices). Étude clean-room du dépôt
> de référence **au SHA épinglé `1b1a305`** (reconsultation consignée dans
> REFERENCES.md) ; aucune ligne de code copiée.

## Ce qui a été livré

### 1. La brique code-lsp v1.15.0 consommée (B-02 résolue)

- **Dépôt** `maven.pkg.github.com/jjoblab/code-lsp` dans `settings.gradle.kts`,
  lecture authentifiée par `JOIDE_GH_PACKAGES_TOKEN` (le PAT du propriétaire en
  local, le `GITHUB_TOKEN` du dépôt en CI avec `permissions: packages: read`) —
  filtrée à `jo.codeide.lsp.*` : rien d'autre ne vient de ce registre.
- **Dépendances** (`app-android`) : `jo.codeide.lsp.host:jo-codeide-lsp-host:1.15.0`
  (l'hôte officiel : orchestre les moteurs Java/Kotlin/XML, l'index CIDX, le modèle
  par module) + `jo-codeide-lsp-java-ecj-art:1.15.0` **explicite** (B-05 : la brique
  l'injecte dans son POM par withXml mais pas dans les métadonnées Gradle `.module`
  que Gradle consomme en priorité — mesuré : `NoClassDefFoundError` à la première
  diagnose sans la déclaration explicite) + `kotlinx-coroutines-core:1.11.0`
  (le dispatcheur du constructeur de SessionLsp, même version que la brique).
- **compileSdk 36 → 37** : l'AAR lsp-host porte `minCompileSdk=37.2` ; la
  plateforme 37 s'installe par sdkmanager en CI, `android.suppressUnsupportedCompileSdk=37`
  couvre le couple AGP 8.7.3/37 (AGP 8.7 ne lit que le majeur — 37 suffit).
- **lsp4j 0.22.0 unique** dans le graphe (0.21.2 du moteur → 0.22.0 de l'hôte,
  la combinaison testée par la brique elle-même) : JoIDE câble SON propre client
  (le plan B-02), `celeris:lsp-protocole` (lsp4j 1.0.0) n'entre PAS dans le graphe.
- **Empaquetage** : les métadonnées Kotlin multiplateforme dupliquées de
  syntax-api/lang-syntax partent à l'empaquetage (`packaging.resources.excludes`).
  APK 27,5 → 48,5 Mo (ecj patché 3,2 Mo + moteurs + lsp4j + kotlin : B-02 l'avait
  budgété).

### 2. Le module `core-lsp` (JVM pur, testé)

- **`ForgeClasspath`** — la traduction du périmètre RÉEL Forge (forge.json +
  `build/<module>/resolved/classpath.json` v2, packaging jar/aar) vers le
  `ClasspathResult` du protocole tooling v8 que l'hôte LSP consomme ; android.jar
  découvert côté atelier (composants du moteur Forge puis SDK local).
- **`LspTypes`** — la conversion lsp4j 0.22.0 → types immuables céleris
  (`jo.celeris.core.lsp.types.*`) : positions UTF-16 ↔ offsets (`Document`),
  diagnostics, complétion (l'ordre du serveur honoré, listes tronquées
  respectées), survol (les deux formes du protocole), signatures, symboles
  (hiérarchiques et plates), plis, éditions, renommage (Either3), correctifs
  (les deux bras Command/CodeAction), indices en ligne, jetons sémantiques
  (décodage du delta) — le vide du domaine reste le vide (convention I9).
- **`LanguageClientBridge`** — le client lsp4j de JoIDE : diagnostics publiés →
  auditeurs (un auditeur fautif n'interrompt jamais les autres), journal,
  réponses vides honnêtes aux requêtes serveur→client (jamais de blocage), et
  `brancher()` qui suit les TROIS conventions de client de la brique
  (`connect` maison des serveurs Java/Kotlin, `setClient` du XML,
  `LanguageClientAware` standard en dernier recours).

### 3. Le câblage atelier (`app-android`)

- **`LspSessions`** — cycle de vie des trois moteurs à l'ouverture (fil de fond) :
  montage des sessions Java/Kotlin/XML (stockage d'index explicite sous
  `filesDir/lsp/index` — le défaut de la brique vit sous `user.home`, inexistant
  sur appareil), modèle persisté d'abord (réouverture rapide) puis sync Forge
  fraîche, tick d'état, serveurs initialisés PAR LANGAGE (un seul par projet,
  mémorisés), arrêt exhaustif sur leur fil.
- **`EditorLanguageSession`** — la porte céleris `LanguageSession` portée par un
  serveur lsp4j : complétion typée, survol, signatures, plan, plis, formatage,
  surlignages, plages de sélection, renommage, correctifs, indices en ligne,
  jetons sémantiques, références — chaque appel sur le fil unique des serveurs
  (l'ordre LSP garanti), un échec rend le repli du domaine (l'éditeur ne
  plante jamais).
- **`WorkbenchFragment`** — à l'ouverture : `démarrerLsp()` monte les sessions,
  `syncForge()` déclenche **le pass d'index réel** (phase « Bibliothèques et
  SDK » : android.jar + jars résolus) ; la **puce** (référence IndexStatusChip,
  compact : visible seulement pendant le pass) et le **dialogue d'état**
  (`dialog_index_status.xml`, référence IndexStatusDialog 1:1 : barre 4dp,
  compte artefacts/fichiers, liste de travail avec états, répartition des
  indexeurs, durées, cache, bouton Réindexer) suivent `EtatIndexation` par un
  tick de 250 ms qui s'endort ~2 s après la fin du pass ; les transitions se
  disent (console + journal d'activité) ; à l'ouverture d'un fichier
  java/kotlin/xml : `connectServer` (garde de pose — le serveur du fichier A
  ne sert jamais le document B) ; les diagnostics publiés rejoignent l'éditeur
  céleris (`setDiagnostics` : squiggles, gouttière, bande d'erreurs).
- L'index local L10 (core-index) reste : il nourrit les symboles du tiroir —
  son verdict part toujours au journal/console, seule la puce a changé de
  source d'état.

### 4. Celeris v2.2.11 → v2.2.18

La dernière version **taguée** (les entrées 2.2.19+ du CHANGELOG ne sont pas
publiées) : la coloration ne paie plus la lexicographie intégrale à l'ouverture
(ADR-0032), le lexeur parle le TextMate réel (ADR-0030), le cache d'échecs de
règles (ADR-0029), la grammaire du premier fichier n'attend plus les 27
compilations (ADR-0028), la complétion survit au lexique périmé (2.2.14).

## Preuves

- **`SessionLspIntegrationTest`** (JVM, même bytecode que l'appareil) : le pass
  d'index est **OBSERVÉ en construction** (faits/total réels sur une plateforme
  de 4 000 entrées), rend **PRÊT** avec bilan d'artefacts, puis le serveur Java
  initialisé **publie de vrais diagnostics ecj** (« methodeInexistante ») par
  le pont client — le chemin complet de l'écran d'indexation et des squiggles.
- **`MoteurJavaSondeTest`** : le diagnose nu du moteur (ecj patché sur JVM).
- **`LspTypesTest`** (26 cas) : conversions, bornes, allers-retours
  position/offset, vides du domaine.
- **`ForgeClasspathTest`** (5 cas) : traduction du classpath Forge (modules,
  racines, JAR/AAR, plateforme absente/présente, forge.json illisible).
- **`LanguageClientBridgeTest`** (5 cas) : auditeurs, isolation des fautifs,
  poignées de retrait, réponses non bloquantes.
- `./gradlew test` vert (tous modules), `:app-android:assembleDebug` vert,
  contrôles check-langue/check-deps/check-cablage-* verts.

## Écarts honnêtes

- **Kotlin et XML montés mais non prouvés par test dédié** : les sessions se
  créent et se synchronisent (même hôte, même classpath), le serveur Kotlin
  sert les `.kt` et le XML les `.xml` par la même porte — seule la voie Java a
  son test d'intégration mécanique (la plus lourde, celle de l'écran).
- **Le dialogue affiche l'état JAVA** (l'index maître, miroir de la référence
  qui n'a qu'un index) : les fenêtres d'état Kotlin/XML (courtes, « sous-lues
  honnêtement » par la brique) ne s'y superposent pas.
- **La puce « Indexed » du repos n'existe pas en compact** (référence : clutter
  qui presse le bouton Run sur téléphone) — comportement conservé.
- **lint Debug reste rouge (831 erreurs préexistantes)** — aucune erreur
  nouvelle de ce lot (3 avertissements mineurs dans le dialogue) ; la CI ne
  lance pas lint (constat L10 inchangé).
- **Le token GitHub du propriétaire** (fourni pour la session) sert la lecture
  GitHub Packages en local ; en CI c'est le `GITHUB_TOKEN` du dépôt. Le PAT
  devrait être **tourné** (il a circulé dans les échanges).
