# LOT-09 — Écrans du studio HTML en Android : Git, installation du mode, mode actif, états IA, accueil filtrable

> Lot hors plan L0-L9 initial (demande du propriétaire 2026-11-21 : « commence,
> chaque écran et fonctionnalité identique à 100 % »). Les écrans viennent du
> studio de previews validé (`download/joide-previews-ecrans.html`, 12 onglets)
> et du dépôt Forge consulté (`forge-src` : assistant d'installation du moteur,
> popup AlertDialog de désinstallation, manifeste signé, ~100 Mo, avertissement
> données mobiles).

## Ce qui est fait

1. **Module `core-git` (JVM pur, JGit 6.10.0 — Apache-2.0/EDL)** :
   `GitService` (statut avec compte de lignes, diff coloré index↔workdir,
   historique, commit, clonage), banc `GitServiceTest` 4/4 vert. La voie du diff
   tient en trois leçons : `DiffCommand` sur le workdir échoue sur les blobs
   (`MissingObjectException`) ; la voie juste est `DiffFormatter.scan(
   DirCacheIterator, FileTreeIterator)` ; la branche par défaut de JGit est
   désormais `main`. Aucune dépendance Android (check-deps vert).

2. **Écran Git (`GitFragment` + `git_fragment.xml`)** — studio onglet « Git » :
   statut (branche, synchronisation, pastilles de compte modifiés/nouveaux/
   supprimés), diff coloré (+vert/−rouge) du premier fichier modifié,
   historique (identifiant court, auteur, âge relatif, message), message de
   commit + Préparer/Commit, carte de clonage (URL + détection du type) ;
   état « pas un dépôt git » honnête (jamais muet). Bouton Git dans la barre de
   l'atelier.

3. **Écran Installation du mode (`InstallFragment` + `install_fragment.xml` +
   `ModeCatalog`)** — studio onglet « Installation mode », d'après forge-src :
   choix Forge/Gradle togglable (cartes à contour), canal manifeste signé
   (URL réelle du canal, pastille ✓ SIG), composants avec tailles (Forge :
   moteur/jre-21/java-compiler/ecj/d8-r8/apksig ; Gradle : bootstrap/outils/jdk/
   gradle — jamais inventées, `ModeCatalog` JVM testé), espace libre requis,
   avertissement données mobiles, progression annulable (octets conservés),
   phases vérifiées « installé = vérifié » avec leurs commandes réelles
   (BOOTSTRAP→OUTILS→JAVA→GRADLE), Réinstaller (réparation), popup AlertDialog
   de désinstallation (d'après MainActivity de Forge). Ouvert depuis les
   paramètres (bouton Installer) ET depuis la confirmation de l'assistant du
   premier lancement.

4. **Mode actif dans l'atelier (`WorkbenchFragment`)** — studio onglet
   « Éditeur (mode) » : chip de mode dans la barre, bandeau de notice (Gradle :
   lecture seule des projets Forge / Forge : terminal non fourni — capacités
   honnêtes du BuildBackend, conception §4), bouton terminal **visible
   seulement en mode Gradle**, onglet Terminal du dock togglé par le mode
   (5ᵉ section), build selon le mode (`ForgeBackend` ou `GradleBackend`).

5. **Tiroir IA à états (`AiPanelFragment`)** — studio onglet « Chat IA » :
   conversation à bulles typées — question de l'utilisateur (droite),
   réponse en cours « En réflexion… » (remplacée au fil des deltas), cartes
   d'outils (en cours ✓ fait/refusé), erreur de tour (échec). La boucle
   d'agent et la porte de permission restent réelles.

6. **Accueil (`HomeFragment`)** — studio : filtres par mode (chips
   Tous/Forge/Gradle, le filtre survit au rafraîchissement), carte de clonage
   git (URL, détection du type, projet cloné listé).

7. **Bancs JVM** : `ModeCatalogTest` (catalogues, totaux ~100 Mo Forge, 4
   phases Gradle) et `GitServiceTest` (4/4). Cœur JVM complet vert
   (`./gradlew test --rerun-tasks`).

## Vérifications locales (bac à sable sans appareil)

- `./gradlew test --rerun-tasks` : BUILD SUCCESSFUL (cœur JVM).
- `tools/check-langue.sh` : OK (identifiants anglais, textes UI en ressources).
- `tools/check-deps.sh` : OK (core-git sans Android).
- Chaînes : 44 références `R.string.*` toutes définies (values + values-fr).
- IDs de layouts vs bindings : appariés (git, install, home, workbench).
- Module app-android : constructible seulement avec un SDK Android — la
  sandbox n'en a pas au départ ; la CI le construit (workflow ci.yml).
  Un SDK est en cours d'installation local pour vérifier la compilation
  avant commit (sinon : commit revu par la CI).

## Limites honnêtes

- La progression de l'installation est simulée honnêtement (pas de réseau dans
  ce lot) : l'annulation arrête bien la progression, et l'état installé
  (`ModeManager.markInstalled`) reste réel. Le vrai téléchargement signé
  (canal Forge) est le chantier d'intégration moteur déjà prévu.
- Les tailles de composants viennent de `ModeCatalog` (constats de la
  documentation de Forge) : à terme elles doivent venir du manifeste signé du
  canal — le remplacement est un seul point (`ModeCatalog.forge()`).
- `GradleBackend.build` échoue proprement (câblage Android L6 documenté) :
  la Console affiche l'échec, jamais muet.
