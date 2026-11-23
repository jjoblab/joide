# LOT-10 — Indexation à l'ouverture, écrans de configuration (modules, dépendances, runtime) et journal d'activité/crash

> Lot hors plan initial (demande du propriétaire 2026-11-23) : « je veux que tu consultes la
> référence… quand l'utilisateur ouvre un projet il y a un système qui fait l'indexation pour
> que les LSP puissent s'en servir, je veux la même chose (design écrans et fonctionnalités
> identiques). Ils ont des écrans pour configurer le moteur, configurer le projet, l'ajout de
> nouveaux modules, les sources, classpaths, l'ajout de dépendance. Ajoute aussi un activity
> crash. » Étude clean-room du dépôt de référence **au SHA épinglé `1b1a305`**
> (reconsultation consignée dans REFERENCES.md) ; aucune ligne de code copiée.

## Réponses aux questions du propriétaire consignées

- **Cache de dépendances partagé ?** Oui, déjà livré côté Forge (décision D-18, v0.2.21) :
  quand le moteur est installé, la réplique Maven vit sous `<forgeHome>/cache/maven`
  (disposition de dépôt, écritures atomiques, marqueurs d'intégrité) et les résultats de
  résolution sous `<forgeHome>/cache/résolutions` — **partagés entre tous les projets, anciens
  et nouveaux, et entre versions du moteur** ; le test Forge
  `cachePartagéEntreProjetsSurvitAuRéseauCoupé` le prouve. En développement sans forgeHome,
  retombée au cache du projet (`.forge/deps`). Même philosophie que le cache Gradle :
  un artifact téléchargé une fois n'est plus téléchargé.

## Périmètre (adoption de la référence, écarts voulus honnêtes)

1. **`core-model` : codec `forge.json`** (`ForgeJson`) — lecture/écriture atomique du modèle
   réel de Forge (modules, types, javaRelease, sources/resources, dépendances
   projet/maven + portées, facette Android, langage, dépôts). C'est la base des écrans de
   configuration : on édite **le modèle réel**, jamais une copie parallèle.
2. **`workbench-spi` : `BuildActivity`** — le sémaphore « un build tourne » (+ `awaitIdle`)
   de la référence : les travaux de fond (indexation) attendent ; les builds ne sont
   jamais ralentis par l'arrière-plan.
3. **`core-index` : `IndexService`** — l'indexation à l'ouverture, pour que les
   fournisseurs de langue (structure, recherche de symboles, complétion à venir) s'en
   servent :
   - pass **Bibliothèques & SDK** : noms de classes des jars du classpath résolu
     (fichiers classpath de Forge) — lecture des entrées zip, sans parsing de bytecode ;
   - pass **Sources du projet** : symboles de déclaration (types, méthodes, champs) via
     l'analyseur tolérant existant (`core-outline`) sur les racines sources du modèle ;
   - incrémental : empreintes (taille + mtime), re-index **d'un seul fichier** à la
     sauvegarde (`WorkspaceEvents.FileSaved`), annulable ;
   - statut observable (phase, fraction, compteur, statistiques réutilisé/reconstruit) ;
   - stockage persistant borné sous `<projet>/.joide/index/` (écriture atomique) ;
   - consommé par la recherche de symboles du tiroir (repli honnête en texte plein tant
     que l'index n'est pas prêt — pas de faux résultats).
4. **Écran Modules** (`ModulesFragment`) — d'après ModuleConfigScreen (référence) :
   liste (tuile type, menu ⋮ renommer/supprimer, FAB « Nouveau module », état vide avec
   action), détail à onglets **Réglages / Dépendances** ; carte Général (sortie, niveau
   Java, SDK plateforme), carte Ensembles de sources (chips amovibles + dialogue d'ajout
   avec préréglages java/kotlin/res + rôle), carte Facette Android (namespace, minSdk,
   targetSdk, versionCode, versionName) ; barre de sauvegarde épinglée
   (Abandonner/Enregistrer) ; dialogue Nouveau module (nom, type, niveau Java) qui crée
   les répertoires et inscrit le module dans `forge.json`.
5. **Onglet Dépendances** — d'après DependenciesScreen (référence) : liste déclarée par
   module (badge portée, suppression avec confirmation, édition version), **Ajouter** :
   recherche en ligne Maven Central (debounce 320 ms), coordonnée directe, cible module,
   fichier local (copie dans `libs/`) ; dépôts éditables (ajout URL, dépôts connus) ;
   Re-résoudre (lance le build — la résolution vit dans le moteur).
6. **Écran Runtime de build** (`BuildRuntimeFragment`, paramètres) — d'après la page
   « Build Runtime » (référence) : **tas du moteur** (glissière, bornes réelles 256–1024 Mo,
   défaut 1024 / 256 en mode basse mémoire), **mode basse mémoire** (Auto/Activé/Désactivé
   — seuil réel 2,5 Go), carte honnête « processus séparé » (le moteur Forge EST un
   processus séparé — non négociable, donc constaté, pas réglable), politique de conflit
   (constatée : le moteur applique NEWEST, non réglable en v0.2.28).
7. **Journal d'activité / crash** (`ActivityFragment`) — d'après BuildActivity + DaemonCrashNote
   + dialogue d'erreur (référence) : la chronologie (builds récents de l'atelier, passes
   d'indexation, écroulements des trois zones du `CrashJournal` existant) ; le clic
   déplie la trace ; notification de l'écroulement du moteur dans la console de build +
   ligne honnête « L'IDE est intact — appuyez sur Construire pour réessayer » ; dialogue
   d'erreur non fatale réutilisable (Message + Voir les détails/trace + Ignorer).

## Écarts assumés (honnêteté)

- **Pas de segments pré-construits distants ni embarqués** (la référence télécharge des
  index tout faits) : notre index est local, construit sur l'appareil ; la demande
  d'approvisionnement est consignée (F-08).
- **Fichier local** : `forge.json` n'a pas de dépendance de fichier local (F-07 ouvert) ;
  le dialogue copie dans `libs/` et affiche la limite au lieu de mentir.
- **Recherche Google Maven absente** : seule Maven Central est interrogée (réel, testé) ;
  l'écran le dit.
- **Plugins/langages multiples** : l'index couvre Java (et les symboles de plan des XML/
  Markdown existants) ; Kotlin viendra avec la complétion (lots suivants).
- **Suppression de module** : retire le module de `forge.json` et son dossier (confirmation
  rouge), comme la référence ; pas d'annulation (honnête).

## Bancs

- `ForgeJsonTest` (lecture/écriture/aller-retour, exemple Android complet, cas absents =
  conventions, écriture atomique).
- `BuildActivityTest` (bascule, attente, timeout).
- `IndexServiceTest` (pass à froid, réutilisation par empreintes, re-index d'un fichier,
  statut fraction, annulation, requêtes préfixe, classe depuis un jar réel).
- `MavenCentralSearchTest` (serveur HTTP local de test, fusion/dédoublonnage, échec
  réseau → diagnostic).
- L'atelier (javac hors SDK) vérifie la syntaxe ; l'APK reste validé par le CI.
