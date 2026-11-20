# Limites et suite — JoIDE

> Ce qui est fait, ce qui reste, les hypothèses retenues, les vérifications qui
> exigent un appareil réel. Ce document est la règle anti-« TODO silencieux » du
> cahier des charges §7 : tout manque vit ici.

## 1. Ce qui est fait (à la date de mise à jour)

| Élément | État |
|---|---|
| Phases 0-2 : analyses, revue, registre de références, inventaire génération 1 (différé) | Fait |
| Phase 3 : conception, design system, licences, demandes Forge, ADR, fiches d'écrans | Fait |
| Maquettes HTML des écrans clés (jalon J1) | Fait (`docs/mockups/`, miroir dans le répertoire de téléchargement) |
| L0 : squelette multi-modules, contrôle des termes, règle de langue, contrôle API 28, règle de dépendances, hooks | Fait |
| L1 : design system XML, thèmes clair/sombre, composants (carte projet, segments, état vide, strip d'avis, popup ancrée, bottom sheet 4 sections), coquille de navigation | Fait |
| L2 : assistant des deux modes, stockage du choix, tests des états | Fait |
| L3 : `BuildBackend` + `ForgeBackend` + `GradleBackend` (double), événements, diagnostics, annulation, tests avec doubles | Fait (tests JVM ; intégration Forge réelle = appareil) |
| L4 : détection des projets (forge.json / Gradle), accueil, création depuis modèle | Fait |
| CI : build JVM + APK signé + contrôles | Fait (démarrée ; les jobs suivants seront suivis dans les rapports de lots) |

## 2. Ce qui reste (les lots et jalons restants)

| Lot | Contenu | Jalon |
|---|---|---|
| L5 | Atelier : intégration des briques (éditeur céleris, LSP code-lsp, aperçu LayoutLib), onglets, tiroirs, bottom sheet câblé Console/Problèmes/Journal/Logcat, barre de symboles | **J2** : un projet s'édite et se construit en mode Forge |
| L6 | Mode Gradle : installateur des composants (bootstrap, JDK, Gradle, SDK), terminal intégré (fork), builds, diagnostics | **J3** |

Consigne du propriétaire (2026-11-20) : à l'étape terminal (L6), consulter aussi
`jjoblab/codeide` (dépôt de l'écosystème maison) en complément du packaging
(`codeide-packages`) et des forks de la famille libre — la base de code terminal à
reprendre sera choisie à ce moment-là.
| L7 | IA : fournisseurs, conversation, contexte, outils, approbation, clés chiffrées | — |
| L8 | Paramètres, changement/réparation/désinstallation de mode, rapport de diagnostic, journal des écroulements | — |
| L9 | Qualité : tests étendus, performances, accessibilité, sécurité, publication | **J4** |

## 3. Hypothèses retenues (faute d'interrogation possible)

1. **`targetSdk 28` confirmé** pour l'application entière (ADR-009) : cohérent avec
   Forge (exécution de binaires téléchargés depuis le stockage privé), assume la
   distribution hors magasin. Le cahier des charges demandait une confirmation par
   ADR : faite, avec le plan de sortie (ADR-004).
2. **AGP 8.7.3 / Gradle 8.11.1** pour l'environnement Gradle **embarqué** (distribué à
   l'utilisateur du mode Gradle) : dernière combinaison stable ne exigeant pas un JDK
   plus récent que ce que l'on peut embarquer raisonnablement ; réexaminable à L6.
3. **Composite build** en attendant la publication Maven de Forge (F-01) : le clone
   épinglé `v0.2.6` est inclus par `includeBuild` ; la bascule vers une coordonnée
   Maven est préparée (ADR-001).
4. **Termux forké dans ce dépôt** plutôt que consommé comme bibliothèque : la GPL de
   l'application et de l'émulateur est assumée dans le module `build-gradle` isolé
   (licences.md §1) ; le packaging du propriétaire (`codeide-packages`) fournit le
   bootstrap à préfixe dédié.
5. **Les versions des briques** (céleris, code-lsp, LayoutLib) seront épinglées à L5
   après lecture des versions d'intégration de la génération 1 (autorisée alors, et
   conforme à la consigne du propriétaire du 2026-11-20).
6. **Jalons d'arrêt pour validation** : J1 (maquettes) atteignable sans appareil ;
   J2-J4 exigent un appareil réel — les livraisons associées s'arrêtent à la
   vérification maximale hors appareil (tests JVM/Robolectric, doubles) et le reste
   est consigné ci-dessous §4.

## 4. Vérifications qui exigent un appareil réel (règlement transparent)

| Domaine | Ce qui n'est pas vérifiable dans le bac à sable |
|---|---|
| Forge sur appareil | Lancement réel du moteur (`app_process`), poignée de main, build réel d'un exemple, installation de l'APK produit par PackageInstaller, comportement du tueur de processus Android 12+ |
| Terminal | Rendu de l'émulateur, clavier matériel/logiciel, sessions persistantes en arrière-plan, performances du bootstrap sur l'appareil |
| Gradle embarqué | Daemon en mémoire bornée, temps de premier build, comportement sous pression mémoire |
| Éditeur/LSP | Performances réelles des briques sur appareil, comportement du clavier avec la barre de symboles |
| IA | Aucun appel réseau réel effectué dans le bac à sable (les tests utilisent un double de transport) |

Chaque rapport de lot (`docs/lots/`) signale explicitement ce qui n'a pu être vérifié
que sur appareil (règle du cahier des charges §6).

## 5. Suite possible (au-delà de L9, non engagée)

- **Complétion inline IA** et **génération de commit** : deux absences du produit de
  référence (REF-125) = deux différenciateurs immédiats ; non planifiés pour ne pas
  lier la boucle agent à l'éditeur.
- **Clés de secours** par fournisseur (REF-103) : rejeu automatique sur clé suivante.
- **MCP** (client + serveur hôte) : extension bidirectionnelle (REF-122) — attendre
  la stabilisation de L7.
- **Jeu de couleurs de l'éditeur** éditable/importable (design-system §2) et thème
  personnalisé.
- **RTL complet** de l'UI hors éditeur.
- **Historique du presse-papiers** dans l'atelier (REF-021).
- **Import/export de projets** par SAF multi-format.

## 6. Dettes connues

- Les valeurs chiffrées du design system (palettes exactes) seront affinées aux
  retours du jalon J1.
- `codeide-packages` : le bootstrap minimal existe (aarch64) ; l'assemblage complet
  (JDK, Gradle, SDK) de L6 est à construire sur cette base.
- Étape terminal (L6) : consulter `jjoblab/codeide` (consigne du propriétaire) en
  complément de `codeide-packages`.
- Le contrôle CI des termes interdits exige le secret `FORBIDDEN_TERMS` (posé par
  l'API GitHub, jamais versionné) ; voir `docs/` du workflow L0.
