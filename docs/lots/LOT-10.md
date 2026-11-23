# LOT-10 — Indexation à l'ouverture, écrans de configuration (modules, dépendances, runtime) et journal d'activité/crash

> Lot hors plan initial (demande du propriétaire 2026-11-23) : « je veux que tu consultes la
> référence… quand l'utilisateur ouvre un projet il y a un système qui fait l'indexation pour
> que les LSP puissent s'en servir, je veux la même chose (design écrans et fonctionnalités
> identiques). Ils ont des écrans pour configurer le moteur, configurer le projet, l'ajout de
> nouveaux modules, les sources, classpaths, l'ajout de dépendance. Ajoute aussi un activity
> crash. » Étude clean-room du dépôt de référence **au SHA épinglé `1b1a305`**
> (reconsultation consignée dans REFERENCES.md) ; aucune ligne de code copiée.
>
> Livré en sept commits poussés au fil de l'eau (consigne du propriétaire : « toujours
> pousser à chaque phase terminée » ; « pas de dialogue — seulement des popups et des
> bottom sheets, même design partout ») : 9473e9a (socle JVM), 6940cb6 (réglages runtime
> + recherche Maven), e3894a5 (**clonage git depuis l'accueil**, demande additionnelle),
> 4b9b64b (écran Modules + onglet Dépendances), 37c475f (écran Runtime de build),
> 5abd933 (journal d'activité + erreurs non fatales), 3985e07 (branchement de
> l'indexation à l'ouverture + consommateur).

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
   (Abandonner/Enregistrer) ; feuille Nouveau module (nom, type, niveau Java) qui crée
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
   d'erreur non fatale réutilisable (Message + Voir les détails/trace + Ignorer) — une
   FEUILLE, conformément à la consigne du propriétaire (aucun dialogue).

## Écarts assumés (honnêteté)

- **Pas de segments pré-construits distants ni embarqués** (la référence télécharge des
  index tout faits) : notre index est local, construit sur l'appareil ; la demande
  d'approvisionnement est consignée (F-08).
- **Fichier local** : `forge.json` n'a pas de dépendance de fichier local (F-07 ouvert) ;
  la feuille d'ajout affiche la limite au lieu de mentir (pas de copie `libs/` factice).
- **Recherche Google Maven absente** : seule Maven Central est interrogée (réel, testé) ;
  l'écran le dit.
- **Plugins/langages multiples** : l'index couvre Java (et les symboles de plan des XML/
  Markdown existants) ; Kotlin viendra avec la complétion (lots suivants).
- **Suppression de module** : retire le module de `forge.json` et son dossier (confirmation
  rouge), comme la référence ; pas d'annulation (honnête).
- **Symboles du tiroir en feuille** : la référence rend les symboles dans la recherche du
  tiroir ; notre chip « Symboles » ouvre une **feuille** dédiée (recherche par préfixe,
  saut à la ligne) — même fonction, même design, zéro dialogue ; le rendu inline
  arrivera avec la complétion (lots suivants).
- **Fusion de la visionneuse** : la référence a une visionneuse de logs dédiée ; notre
  écran Journal d'activité fusionne déjà ActivityLog + CrashJournal (les trois zones) —
  les filtres par sévérité/source arriveront avec les lots d'observabilité.
- **Renommage de module** : la référence renomme aussi le paquet de l'application
  (`AppRenameDialog`) ; notre renommage porte le nom dans `forge.json` et les dépendances
  des frères — le paquet applicatif se renomme à la création du projet (gabarits).

## Bancs

- `ForgeJsonTest` (lecture/écriture/aller-retour, exemple Android complet, cas absents =
  conventions, écriture atomique).
- `BuildActivityTest` (bascule, attente, timeout).
- `IndexServiceTest` (pass à froid, réutilisation par empreintes, re-index d'un fichier,
  statut fraction, annulation, requêtes préfixe, classe depuis un jar réel).
- `IndexScopeTest` (sources forge.json **relatives au module** + jars des classpath.json
  v2 + AAR explosé libs + tolérance jamais construit/pourri).
- `MavenCentralSearchTest` (serveur HTTP local à deux cœurs comme le vrai solr, fusion,
  échec réseau → diagnostic distinct d'« aucun résultat »).
- `EngineSettingsTest` (résolution du tas selon mode/RAM, bornes de l'explicite).
- `EngineRuntimePrefsTest` (aller-retour, défauts sur absent/illisible/pourri).
- `ActivityLogTest` (aller-retour avec détail, borne stricte, pourri sauté).
- `GitServiceCloneTest` (clone réel d'un dépôt local file://, progression, dossier
  occupé refusé, **nettoyage du demi-dépôt** en cas d'échec, compat cloneInto).
- L'atelier (javac hors SDK) vérifie la syntaxe ; l'APK reste validé par le CI.
