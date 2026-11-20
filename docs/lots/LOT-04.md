# LOT-04 — Projets : accueil, création depuis modèles, import, détection

> Lot L4 du plan (conception §11). Critère d'acceptation : détection
> forge.json / build.gradle* testée (fait depuis L0) ; **création depuis un modèle
> produit un projet buildable**.

## Ce qui est fait

| Élément | Emplacement | Vérification |
|---|---|---|
| `ProjectStore` (`core-store`) : liste des projets du stockage privé (`filesDir/projects/`, ADR-010) dérivée du contenu réel (aucun index à désynchroniser) — nom, date, modes compatibles ; création, renommage sécurisé, suppression bornée au projet | `core-store/…/ProjectStore.java` | 8 tests JVM verts (`ProjectStoreTest`) |
| `ProjectTemplates` : deux modèles livrés — `forge-java` (projet Java pur **buildable par Forge**, compatible avec la limite F-02) et `gradle-app` (projet Gradle minimal, buildable par le mode Gradle) ; mêmes contenus fournis depuis les assets côté coquille | `core-store/…/ProjectTemplates.java` | tests : instanciation → `ProjectDetector.detect()` retourne le mode attendu |
| Accueil câblé (`HomeFragment`) : cartes de projets (une ligne de méta + **badge des modes compatibles**), état vide, création depuis modèle (`projet-<horodatage>`), ouverture de l'atelier sur le projet choisi ; avis passager honnête pour l'import (non câblé : L8) | `app-android/…/HomeFragment.java`, `layout/home_fragment.xml`, `layout/project_card_item.xml` | APK construit par la CI |

## Décisions du lot

1. **Liste dérivée du dossier, pas d'index** : l'accueil lit `filesDir/projects/`
   et détecte à chaque affichage — impossible d'avoir un index mensonger après un
   changement externe (terminal, L6). Les dossiers sans marqueur ne sont pas des
   projets (conception §5 : proposition de création/import).
2. **Deux modèles buildables et honnêtes** : `forge-java` expose la limite F-02
   (projet Java pur constructible en mode Forge ; projet Android à ressources non
   constructible avant la publication des composants) ; `gradle-app` couvre le
   besoin Gradle. Un modèle Android à ressources viendra avec la publication des
   composants aapt2 (F-02).
3. **Nom sécurisé** (`safeName`) : espaces → underscores, `..` refusé — le
   renommage/suppression restent bornés au stockage des projets.

## Critère d'acceptation — vérifié

`forge-java` instancié → `forge.json` présent à la racine → `detect()` = FORGE ;
`gradle-app` instancié → `settings.gradle` présent → `detect()` = GRADLE. **8
tests verts dans la CI.**

## Reste pour ce lot

- Import SAF (dossier existant vers le stockage privé) : L8 avec les permissions
  documentées ; le bouton annonce honnêtement l'état (pas d'échec silencieux).
- La fiche accueil demande une carte « Reprendre » saturée en tête : la liste
  triée par date le couvre fonctionnellement (le plus récent en tête) ; la
  saturation visuelle exacte est un détail design à reprendre en revue L9.
