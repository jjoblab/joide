# Écarts preview ↔ application — dock (feuille du bas et console)

> Comparaison de `docs/mockups/bottom-sheet.html` (aperçu **validé**) avec
> l'implémentation réelle (`WorkbenchFragment` + `ConsoleBuffer` du module
> `workbench-spi`). Suite de `ecarts-contenu-principal.md`, même méthode :
> statuts **conforme** · **corrigé** (rapproché) · **consigné** (écart
> assumé, raison indiquée).

## 1 · Comportement de la feuille (BottomSheetBehavior)

| Élément du preview | Implémentation avant | Statut |
|---|---|---|
| Trois détents : réduit (60 dp) → mi-hauteur (60 %) → plein calé sous la toolbar (offset 126 dp) | idem | conforme |
| Cycle par appui : poignée **et** barre d'état parcourent les états | idem | conforme |
| Glissement : la feuille suit le doigt, aimantée à l'état le plus proche | `fitToContents=false` + `halfExpandedRatio` (défaut Material) | conforme |
| **Build échoué → la feuille s'ouvre sur Problèmes** | ⚠ **tout build fini (réussi ou non) forçait `STATE_HALF_EXPANDED`** — vol de focus à chaque succès | **corrigé** — échec : `showDockSection(1)` + mi-hauteur ; réussite : la barre seule se met à jour |
| Lancement impossible (moteur absent) | ligne console mais pas d'ouverture | **corrigé** — feuille ouverte en mi-hauteur, ligne rouge honnête |
| **Repli sous le clavier logiciel** | absent | **corrigé** — écoute des insets IME sur le contenu principal, repli en `STATE_COLLAPSED` |
| L'onglet Console est actif au départ | ⚠ `showDockSection(1)` sélectionnait **Problèmes** (faute d'indice) | **corrigé** — Console active |
| Onglets nommés par mode : « Forge Console » / « Gradle Console » | « Console » dans les deux modes | **corrigé** — chaînes par mode |
| Plein : onglets et fil d'Ariane atteignables (chrome 126 dp) | idem | conforme |

## 2 · Console (affichage des sorties de build)

| Élément du preview | Implémentation avant | Statut |
|---|---|---|
| Lignes colorées par nature (✔ ok, ✗ erreur, ◐ en cours, avertissements) | ⚠ **TextView monochrome** : tout le flux en `sur`, y compris les erreurs | **corrigé** — `ConsoleBuffer` (workbench-spi, 22 tests) + rendu `Spannable` coloré aux jetons (ok/erreur/alerte/info/sur_var), bilans et en-têtes en gras |
| Tâche en cours ◐ **remplacée en place** par son état terminal | une ligne par événement : `compileJava : SUCCEEDED` brut | **corrigé** — mise à jour en place (même id de ligne), la console ne défile pas à l'infini |
| Durée réelle par tâche (« · 2,4 s ») | ⚠ **« 2,4 » codé en dur** dans la chaîne d'étape | **corrigé** — `TaskState.durationMs` formaté par `Durations` (décimes sous 10 s, minutes, heures) |
| Progression sans spam | ⚠ une ligne « progression : n/m » **par événement** | **corrigé** — ligne collante unique, mise à jour en place |
| Bilan de fin (« build réussi · 18 s · 59 tâches ») | « Build réussi » seul | **corrigé** — durée + tâches réelles ; échec : tâche fautive + message moteur en ligne rouge ; la ligne de progression **devient** le bilan |
| En-tête de run (« &gt; build Forge · 14:32 ») | « — build — » | **corrigé** — mode réel + heure locale |
| Journal par niveau (DEBUG atténué, WARNING alerte, ERROR rouge) | tout au même niveau | **corrigé** — `Log.level` → nature de ligne |
| **Auto-suivi** de la sortie | aucune descente automatique | **corrigé** — suit le bas **sauf** si le développeur a remonté (plus jamais tiré vers le bas pendant une lecture) |
| Sortie bornée (très gros builds) | non bornée | **corrigé** — 2000 lignes, élagage des plus anciennes + note honnête « … N lignes antérieures masquées » (affichée et copiée) |
| Copier la console ⧉ | texte affiché brut | **corrigé** — texte réel + note d'élagage |
| Console vidée au départ d'un build | les runs s'empilaient | **corrigé** — nouveau build, nouvelle console (étapes et problèmes aussi) |
| Mono 10.5/1.75 sur `sc_lowest`, cadre contour, coins 10 | mono 11.5/1.55 sur `sc_low`, coins 12 | **corrigé** — jetons du preview, clair et sombre |
| Feuille : coins supérieurs 16, barre 40 dp | coins 26 | **corrigé** |

## 3 · Barre d'état du dock et sections branchées

| Élément du preview | Implémentation avant | Statut |
|---|---|---|
| Barre : **« ◐ build en cours · 3/12 »** réel, animée par les Progress | ⚠ « Prêt » statique, jamais mis à jour | **corrigé** — compteur réel, mise à jour à chaque progression |
| Barre : « ▶ build ok · il y a 3 min » (âge relatif) | absent | **corrigé** — âge réel rafraîchi à l'ouverture de la feuille (`RelativeTimes` du module ai) |
| Barre : échec rouge | absent | **corrigé** |
| Pills ok/err/**warn** | ⚠ le compte d'« erreurs » comptait **toutes** les rangées Problèmes, avertissements compris | **corrigé** — compteurs par sévérité (ERROR/WARNING) |
| Pied Problèmes « 2 erreurs · 2 avertissements » | absent | **corrigé** — créé au bilan, « Aucun problème — build propre » sinon |
| Étapes : durées réelles, ◐→✔ en place | ⚠ « tâche terminée en 2,4 s » factice, ajout sans remplacement | **corrigé** — rangées vivantes par tâche, marqueurs ✔/✗/○/◐ |
| Problèmes : appui **ouvre le fichier à la ligne** | ⚠ **deux `setOnClickListener` se neutralisaient** : le dialogue « Expliquer avec l'agent IA » était écrasé, jamais atteignable | **corrigé** — appui : saut au fichier ; **appui long** : explication par l'agent |
| Marqueurs par sévérité (✗/⚠/▸) | ✗/⚠ (INFO traité comme erreur) | **corrigé** |

## 3bis · Niveau de console et console du moteur (Forge v0.2.26, protocole 4)

> Décision D-21 de Forge : **le moteur compose et trie la console** (lignes de
> tâches, verdict — niveau `LIFECYCLE`), exactement comme Gradle dicte ses
> sorties. L'atelier demande (`consoleLevel`, l'équivalent des `-q/-i/-d`) et
> **affiche** — il ne rédige plus.

| Élément | Implémentation avant | Statut |
|---|---|---|
| Le **moteur** rédige les lignes de console (tâches, verdict, diagnostics, téléchargements) | ⚠ **JoIDE composait tout lui-même** (client Forge 0.2.25 : protocole 3, le moteur ne composait rien — deux consoles possibles qui divergent) | **corrigé** — client 0.2.26 : négociation protocole 4, l'atelier affiche les lignes `LIFECYCLE` reçues et **ne compose plus** ses lignes de tâches ni son verdict (rien ne double) ; repli automatique en composition locale face à un moteur plus ancien |
| Chips de niveau **Essentiel · Détaillé · Tout** (au-dessus de la sortie) | ⚠ **aucun filtre** : tout s'affichait, quel que soit le niveau (et `DownloadEvent`/`HeartbeatEvent` tombaient en lignes `toString` brutes) | **corrigé** — chips persistées : le niveau part **avec la requête** (argument du moteur, le tri de référence se fait à l'émission) ; en cours de build, l'affichage ne peut que se **resserrer** — jamais s'élargir, ce que le moteur n'a pas émis n'existe plus (sémantique Gradle) |
| Téléchargements et pulsations | lignes `DownloadEvent[buildId=…]` en DEBUG (bruit du repli du traducteur) | **corrigé** — événements propres du contrat (`Download`, `Heartbeat`) : moteur ancien → ligne « ↓ fichier (Kio) » en INFO ; moteur récent → rien (le moteur a déjà composé), la pulsation nourrit la **barre vivante** « ◐ tâche · 45 s » |
| Niveau `LIFECYCLE` dans le contrat workbench | inexistant (`DEBUG/INFO/WARNING/ERROR`) | **corrigé** — `Level.LIFECYCLE` entre INFO et WARNING (sévérité ordonnée), nature de ligne dédiée, sévérité testée |

## 4 · Consigné

- **Badge IA (compteur non lus)** : le « 3 » de la maquette reste codé — il
  attend l'état « suggestions non lues » de l'agent du tiroir droit (lot IA
  en cours) ; le mécanisme est prêt (`dock_badge`).
- **Logcat** : état honnête « nécessite adb » (preview : décor) — inchangé,
  câblage au lot diagnostic appareil.
- **Onglets Étapes/Logcat par mode** : conformes (Gradle : Étapes + Terminal
  réel ; Forge : Journal + Logcat honnête).
- La démo interactive HTML (92 → 275 → 434 px) illustre ; l'application
  utilise les détents réels du `BottomSheetBehavior` — pas de hauteur magique.

## 5 · Vérifications

- `JOIDE_SKIP_ANDROID=1 ./gradlew test` : 305/305 verts, dont 22 nouveaux
  (`ConsoleBufferTest` 13, `DurationsTest` 9) sur `workbench-spi` et 7 sur le
  niveau de console (`ForgeEventTranslatorTest` : LIFECYCLE, sévérité ordonnée,
  Download/Heartbeat sans bruit, argument `consoleLevel` porté jusqu'aux
  `BuildOptions` du moteur — moteur factice v0.2.26 en vrai processus).
- Comptabilité des plages du rendu (mises à jour en place, élagage, note
  d'en-tête) vérifiée par 3000 opérations aléatoires hors Android
  (`scripts/verifie_plages_console.py` hors dépôt) : chaque plage vaut
  toujours exactement sa ligne, la note précède toutes, aucun chevauchement.
- `:app-android:assembleDebug` : APK construit.
