# LOT-06 — Mode Gradle : environnement, terminal intégré, builds, diagnostics

> Lot L6 du plan (conception §11). **Jalon J3** : terminal réel multi-sessions ;
> build Gradle lancé depuis JoIDE avec sortie dans la Console.

## Constat préalable (consigne du propriétaire : consulter `jjoblab/codeide`)

CodeIDE v0.139.0 (dépôt consulté intégralement, clone épinglé) — constats
décisifs pour ce lot, tous vérifiés dans les fichiers :

1. **Terminal : dépendance, pas fork.** CodeIDE consomme
   `com.github.termux.termux-app:terminal-emulator` et `:terminal-view`
   **v0.118.3 via JitPack**. Le `LICENSE.md` de termux-app excepte
   explicitement ces deux modules (code dérivé jackpal, **Apache-2.0**) ;
   le reste du dépôt est GPLv3 → un fork exposerait JoIDE à la GPLv3.
   `termux-shared` (clavier extra-keys) est aussi GPLv3 → CodeIDE a
   **ré-implémenté le clavier étendu en interne** (ADR-0035/0036).
2. **Découpage ADR-0035** : registre de sessions (métadonnées pures sans types
   Termux, testable JVM) + service foreground + vue unique rebranchée (jamais
   une vue par onglet) ; création de session sur le **thread principal**
   (contrainte `MainThreadHandler`, crash réel documenté) ; sessions mortes
   visibles jusqu'à fermeture explicite ; aperçu borné (160 caractères).
3. **Installateur : « installé = vérifié »** (ADR-0085/0087) : phases
   séquentielles vérifiées par exécution réelle, état persisté
   (`install-state.json`), reprise après interruption, préfixe
   `filesDir/usr`, extraction en staging puis bascule atomique.
4. **Builds Gradle** : CodeIDE passe par un serveur Tooling API en sous-processus
   (dépôt frère `codeide-tooling`, registre authentifié GitHub Packages,
   **demande B-04 ouverte**) — JoIDE lance pour sa part le Gradle du préfixe
   en processus terminal, conformément à sa conception §4 (« Gradle lance les
   builds dans le terminal »).

## Ce qui est fait

| Élément | Emplacement | Vérification |
|---|---|---|
| `TerminalSessionRegistry` (`terminal-core`, JVM pur) : multi-sessions, mortes visibles jusqu'à fermeture, aperçu borné 160, coquilles scriptées en test | `terminal-core/…/TerminalSessionRegistry.java` | 3 tests JVM verts |
| `GradleEnvironmentInstaller` (`terminal-core`) : phases BOOTSTRAP→OUTILS→JAVA→GRADLE, **installé = vérifié (exécution réelle)**, état persisté, **reprise d'une installation interrompue** (repart de la dernière phase vérifiée, ne re-télécharge pas l'acquis), extraction protégée contre la traversée de chemin, `processEnvironment` (HOME/PREFIX/TMPDIR/GRADLE_USER_HOME, CLASSPATH hérité retiré) | `terminal-core/…/GradleEnvironmentInstaller.java` | 2 tests JVM verts (interruption→reprise ; phase non vérifiée refusée, tout-ou-rien) |
| `GradleRealBackend` (`terminal-core`) : build lancé par `<prefix>/bin/gradle -q build --project-dir <racine>`, sortie → **parseur ADR-008** → événements du contrat (diagnostics javac avec position, journal), annulation = destruction du processus, fin garantie | `terminal-core/…/GradleRealBackend.java` | 2 tests JVM verts (sortie→événements, fin en dernier ; annulation→kill) |
| **Terminal Android réel** : dépendances JitPack `terminal-emulator`/`terminal-view` v0.118.3 (Apache-2.0, filtre strict de dépôt) ; `TerminalFragment` (vue unique + tiroir sessions + avis honnête si environnement non installé) ; `ExtendedKeyboardRow` **ré-implémenté en interne** (Ctrl/Alt bascules, Échap/Tab/flèches séquences) — GPLv3 évité | `app-android/build.gradle`, `settings.gradle.kts`, `app-android/…/terminal/` | APK construit par la CI |
| Entrée terminal depuis l'atelier (bouton barre) | `WorkbenchFragment` | APK |

## Décisions du lot

1. **PAS de fork Termux** : dépendance JitPack des deux modules Apache-2.0
   uniquement ; `termux-shared` jamais importé (clavier interne). Motif :
   licence (GPLv3) — même arbitrage que CodeIDE, documenté ADR-005.
2. **Build en processus terminal** (pas de serveur Tooling API) : la conception
   JoIDE §4 impose les builds dans le terminal ; le serveur Tooling de
   CodeIDE reste disponible si le besoin s'en fait sentir (demande B-04).
3. **`install-state.json` minimaliste** : clé/valeur phase par phase ; le format
   v2 de CodeIDE (quadruplets id/version/revision/sha256) viendra avec le vrai
   canal de composants (L8 : paramètres d'installation).

## Ce qui exige un appareil réel (limites §4)

- Le rendu réel du terminal (vue Termux, pty), le clavier tactile, la
  persistance par service foreground (`specialUse`, notification).
- L'installation réelle de l'environnement (archive bootstrap aarch64,
  paquets APT, JDK, Gradle — les composants du canal `codeide-packages`).

## Critère d'acceptation J3 — état

- **Terminal réel multi-sessions** : logique complète et testée JVM ; le
  montage de la vue Termux est câblé dans l'APK ; la vérification
  interactive exige un appareil.
- **Build Gradle → Console** : câblé de bout en bout côté logique
  (commande, parseur, événements) ; le premier build réel exige
  l'environnement installé sur appareil.

**Bilan : 8 tests de lot verts dans la CI** + APK avec le terminal embarqué.
