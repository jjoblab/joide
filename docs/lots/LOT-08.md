# LOT-08 — Paramètres, changement de mode, réparation, désinstallation, rapport de diagnostic

> Lot L8 du plan (conception §11). Critères : **bascule de mode testée ; rapport
> copiable avec secrets rédigés**.

## Consultation de la référence (consigne du propriétaire : toujours)

Re-consult**ée avant l'implémentation** au même SHA épinglé `1b1a305`
(reconsignation dans `REFERENCES.md`). Fichiers relus : `BuiltInSettingsPages.kt`
(pages de paramètres **déclaratives** — une page = des contrôles typés rendus
génériquement, clés locales à la page ; page **Privacy & Data** honnête :
« jamais votre code ni vos noms de fichiers », actions « vider les caches
(jamais les sources) / voir les journaux / sauvegarder »), `AgentPlugin.kt`
(la page de réglages IA **vit dans le plugin** : fournisseur, une clé par
fournisseur, effort de raisonnement, **base URL de passerelle**,
maxTokens/maxIterations/rpm en avancé), `Settings.kt` (SPI :
`controls()` re-questions à l'affichage, `onChanged`/`onAction`),
`DaemonCrashNote.kt` (le processus mourant écrit sa note, **installé devant le
gestionnaire par défaut puis chaîné à lui**, message borné à 300, heap
utilisé/max), `AgentSessionStore.kt` (session = un JSON **écrit entier après
chaque tour via temp+move atomique**, contenant **transcript ET historique
modèle**). **Clean-room : aucune ligne copiée** — Java.

## Ce qui est fait

| Élément | Emplacement | Vérification |
|---|---|---|
| `CrashJournal` : journal des écroulements **borné** (20 entrées), message tronqué à 300 caractères, écriture **atomique** (temp + renommage), zones UI/MOTEUR/TERMINAL, ligne illisible → entrée marquée, jamais d'exception vers l'appelant | `core-store/…/CrashJournal.java` | 2 tests (borne, fichier illisible) |
| `DiagnosticReport` : rapport copiable — versions (app/Forge/Gradle/briques), états d'installation des modes, journal borné du dernier build (**queue gardée** : les erreurs sont à la fin), journal des écroulements ; **secrets rédigés** par heuristique conception §10 (jetons > 16 caractères proches de key/token/secret → `[rédigé]`, le champ reste, la valeur part) | `core-store/…/DiagnosticReport.java` | 2 tests (rédaction : clé, bearer, secret entre guillemets ; sections rendues, valeur jamais présente) |
| `ModeManager` (ADR-002 : le changement de mode vit dans les paramètres) : **bascule exigeant l'installation** (refus informatif sinon), **réparation** (dossier repart propre, état remis à installer — l'hôte relance l'installateur), **désinstallation** (dossier supprimé ; le mode actif retombe honnêtement sur l'autre **non installé** — l'assistant reprend), **tailles constatées** (`occupiedBytes`, jamais inventées), suppression récursive protégée | `core-store/…/ModeManager.java` | 6 tests (bascule installée/non, désinstallation active, réparation, taille, sans choix = assistant exigé) |
| `InteractivePermissionGate` (L8, REF-117) : décideur injecté par l'UI, **diff dans l'invite**, trois décisions (refuser / permettre une fois / permettre pour la session + reset), **fermeture du dialogue = refus**, décideur cassé = refus, interruption = refus (défaut sûr partout), modes persistés | `ai/…/InteractivePermissionGate.java` | 4 tests (une fois/session/reset, défauts sûrs, modes) |
| Outil `construire_module` sur port `BuildRunner` : **l'agent construit via le BuildBackend du mode actif** (conception §8 : jamais un moteur concurrent) — l'hôte adapte le port au backend réel ; journal borné rendu au modèle ; sans runner, aucun outil de build exposé | `ai/…/BuildRunner.java`, `ai/…/AgentLoop.java` | 2 tests |
| Écran **Paramètres** (`SettingsFragment`) : IA (fournisseur laboratoire/passerelle, **clé au coffre chiffré**, mode de permission trois modes, **notice honnête de ce qui part de l'appareil**), Modes (installer/basculer/réparer/désinstaller par mode, état + taille Mo constatés), Diagnostic (**copier** dans le presse-papiers + **partager**), entrée depuis le menu de l'accueil | `app-android/…/settings/SettingsFragment.java` | APK CI verte |
| Tiroir IA câblé sur la **porte interactive partagée** (porte unique : paramètres et tiroir utilisent la même instance) avec **dialogue d'approbation : aperçu avant dans l'invite**, refuser/une fois/session, annulation = refus | `app-android/…/ai/AiPanelFragment.java` | APK CI verte |
| Journal des écroulements **câblé à la coquille** : gestionnaire par défaut captant les plantages UI dans le journal borné **puis chaîné au gestionnaire précédent** (l'esprit de la note de plantage de la référence — la mort est enregistrée comme avant) | `app-android/…/MainActivity.java` | APK CI verte |

**Tests : 24 core-store + 18 ai verts** (cumul dépôt : 77 tests JVM).

## Décisions du lot

1. **La porte est unique et partagée** : `SettingsFragment.sharedGate()` — le
   tiroir IA et les paramètres branchent la même `InteractivePermissionGate`
   (le mode persisté vaut pour toute l'app, une seule invite à la fois).
2. **ModeManager orchestre le stockage** : l'installation réelle des composants
   (Forge vérifié SHA-256 / environnement Gradle par phases) reste portée par
   les backends des lots L3/L6 — L8 gère marqueurs, dossier, bascule, et
   l'**honnêteté des états** (jamais « installé » sans dossier).
3. **Rédaction** : l'heuristique §10 (jeton > 16 caractères près de
   key/token/secret) est appliquée à **chaque ligne** du rapport ; le test
   prouve que la valeur ne figure jamais.
4. **Diagnostic assemble des valeurs constatées** (versions réelles du build,
   états réels des modes, journal borné du dernier build s'il existe) — aucun
   champ inventé.

## Ce qui exige un appareil réel (limites §4)

- EncryptedSharedPreferences réel (Keystore matériel), dialogue d'approbation
  en vrai écran, presse-papiers/partage réels.
- Les installations de modes réelles (composants Forge téléchargés,
  environnement Gradle) via les installateurs des lots L3/L6 — les marqueurs
  et la bascule sont testés, le téléchargement reste sur appareil.

## Critères d'acceptation — état

- **Bascule de mode testée** : oui (6 tests ModeManager : bascule installée ✓,
  refus si non installé ✓, désinstallation du mode actif ✓, réparation ✓,
  tailles constatées ✓, aucun choix caché ✓).
- **Rapport copiable avec secrets rédigés** : oui (rendu complet testé ;
  boutons Copier + Partager dans l'écran Paramètres ; rédaction testée).
