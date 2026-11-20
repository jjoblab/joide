# REFERENCES — registre des références au produit de référence

Ce fichier est **le seul endroit du dépôt** où le produit de référence peut être nommé (nom,
auteur, URL, chemins de fichiers). Partout ailleurs — code, commentaires, commits, tests,
logs, interface, documents, CI — ces mentions sont interdites (voir `tools/check-terms.sh`
et les hooks `tools/hooks/`). Dans les documents d'analyse, de revue et de conception, on
parle du « produit de référence » et on renvoie aux identifiants `REF-xxx` ci-dessous.

## Paramètres d'épinglage

| Paramètre | Valeur |
|---|---|
| Dépôt de référence | https://github.com/tyron12233/CodeAssist |
| Branche | `main` |
| SHA épinglé | `1b1a3052168a52f38a91ea03c87831a0d94c1fff` (2026-11-19, « Release 3.47.0 ») |
| SHA court | `1b1a305` |
| Licences constatées | **GPL-3.0-or-later** (fichier `LICENSE`) ; exception de linkage (fichier `LICENSE-EXCEPTION`) de type Classpath-exception-2.0 restreinte aux modules `plugin-api` et `platform-core` (coordonnées `io.github.tyron12233`) ; `TRADEMARKS.md` et `PRIVACY.md` à la racine |

Tous les numéros de ligne du tableau ci-dessous sont valables pour ce SHA. Un changement de
SHA sera consigné ici avant toute nouvelle lecture. Le dépôt de référence est consulté
**hors du dépôt de travail** (jamais en sous-module, jamais copié dans le projet).

## Conclusion sur la licence (clean-room)

Le produit de référence est sous **GPL-3.0-or-later**, avec une exception de linkage limitée
à deux modules SPI (`plugin-api`, `platform-core`). Notre travail est une
**réimplémentation indépendante** à partir de la compréhension fonctionnelle de son design
et de son intégration IA : nous n'importons ni code, ni layouts XML, ni drawables, ni icônes,
ni chaînes de texte, ni valeurs de ressources recopiées. Les idées fonctionnelles
(comportements, parcours, principes visuels, mécanismes d'agent) ne sont pas protégeables
par le droit d'auteur ; leur expression, si. Notre implémentation est en Java (la référence
est en Kotlin/Compose), avec nos propres identifiants. La licence de JoIDE est **Apache-2.0**
(même famille que Forge, la cohérence de l'écosystème étant la règle). Toute réutilisation de
code de la référence est interdite par ce registre ; si un doute surgit, la réécriture
documentation-uniquement est préférée.

Exception de linkage : elle ne nous concerne qu'à la marge — nous ne compilons pas contre les
modules SPI de la référence (aucune dépendance vers eux dans JoIDE).

## Table des références

Colonnes : **ID** ; **composant concerné chez nous** ; **fichier de référence** (chemin depuis
la racine de son dépôt) ; **plage de lignes** ; **SHA** (court) ; **constat** ; **comparaison
avec notre implémentation** ; **statut**.

### Design

| ID | Composant JoIDE | Fichier de référence | Lignes | SHA | Constat | Comparaison | Statut |
|---|---|---|---|---|---|---|---|
| REF-001 | `workbench` (port unique UI/moteur) | `app/ide-ui-api` (module) + `settings.gradle.kts` | 84-229 | 1b1a305 | UI Compose découplée du moteur par un port unique (IdeBackend) ; UI testable sur desktop | Nous adoptons le port unique en Java (interfaces `jo.joide.workbench.spi`) ; écart de technologie voulu : vues AndroidX/XML, pas de Compose | Adopté (principe) |
| REF-002 | coquille de navigation (breakpoints) | `app/ide-ui-screens/.../EditorScreen.kt` | 68, 174-182 | 1b1a305 | Breakpoint compact mesuré « comme si les barres étaient visibles » pour éviter le basculement au clavier en paysage | Reproduit à l'identique dans la coquille (mesure à la largeur stabilité, pas à la largeur instantanée) | Adopté |
| REF-003 | accueil / projets récents | `app/ide-ui-screens/.../ProjectsHomeScreen.kt` + `HomeScreen.kt` | 70, 90, 207, 261-265, 296-490, 639-661, 748-862, 912-932, 989-1035 | 1b1a305 | Cartes à une ligne de méta ; segments avec comptes même vides ; carte « Reprendre » seul élément saturé ; état vide différé jusqu'à chargement confirmé | Mêmes principes ; écart voulu : pas d'onglets store/leçons/défis, pas de publicité | Adopté (partiel) |
| REF-004 | création de projet | `app/ide-ui-screens/.../CreateProjectScreen.kt` + `app/ide-ui-api/.../IdeBackend.kt` | 90, 142-250, 395-415, 169-202 | 1b1a305 | Galerie de modèles + formulaire piloté par les paramètres déclarés du modèle (texte/choix/bascule) | Même parcours en 2 étapes ; descripteur de modèle Java (`ProjectTemplate`) | Adopté |
| REF-005 | import de projet | `app/ide-ui-screens/.../ProjectsHomeScreen.kt` (débordement) | — | 1b1a305 | Import et clonage Git depuis le débordement de l'accueil | Nous : import SAF + détection de type ; pas de clonage Git en L6 | Adapté |
| REF-006 | atelier (topologie téléphone) | `EditorLayouts.kt` + `BuildDock.kt` + `RightToolOverlay.kt` + `EditorChrome.kt` | 411, 784 ; 75-106 ; 79 ; 180-373, 294-311, 1166-1234 | 1b1a305 | Top bar 52 dp hiérarchisée, onglets 40 dp, tiroir gauche, panneau IA droit, dock bas à deux détentes sensible au scroll imbriqué | Même topologie ; écart voulu : dock = bottom sheet à 4 sections (Console/Problèmes/Journal/Logcat), une détente 50 % + plein | Adapté |
| REF-007 | console / run | `RunScreen.kt` + `BuildDock.kt` + `EditorSheets.kt` | 125-240, 367-372 ; 93 ; 129-166 | 1b1a305 | Erreurs de compilation cliquables (ouvre le fichier à la ligne) ; découpage des lignes hors du thread principal ; vue tests avec re-run des échecs | Console = section du bottom sheet consommant le `BuildBackend` ; découpage hors thread principal retenu | Adopté (partiel) |
| REF-008 | paramètres | `SettingsHubScreen.kt` + `SettingsScreen.kt` | 84, 72 ; 147, 160-216 | 1b1a305 | Hub à plat (une page, destinations groupées) ; rendu générique des pages par descripteur, scope app/projet | Hub à plat retenu ; écart voulu : pages réelles bornées (pas de rendu générique) | Adapté |
| REF-009 | assistant premier lancement | `Onboarding.kt` + `AppOverlays.kt` | 103-176, 188, 231-232, 317 ; 63-80 | 1b1a305 | Feuille plein écran, pager à progression, CTA final qui lance la création de projet ; héros dessinés (pas des captures) | Écart voulu (cahier des charges) : l'assistant **choisit le mode de travail** (Forge/Gradle) avec tailles et limites affichées, CTA « Installer » | Remplacé |
| REF-010 | écrans store/leçons/défis/pub | `AppState.kt` (destinations) + `AdHost.kt` | 61 ; 10-32 | 1b1a305 | 36 destinations dont hub social complet et 11 emplacements publicitaires « temps d'attente naturelle » | Hors périmètre JoIDE ; seule la grille « jamais au-dessus des actions » est retenue comme règle pour les notifications de mise à jour | Abandonné (sauf règle) |
| REF-011 | navigation | `ScreenTransition.kt` + fichier d'état d'app | 31, 27-30, 51-79 ; 584, 1192, 1224, 1438-1470 | 1b1a305 | Transitions directionnelles par enum ordonné + flag retour ; priorité du retour système (overlay → écran → app) | Retenu avec `Navigation` maison ; écart voulu : **table parent/enfant déclarative** (leur retour en cascade de 30 branches est l'écueil à éviter) | Adopté (principe) |
| REF-012 | retour de navigation de code | `NavigationHistory.kt` | 21-80 | 1b1a305 | Historique Back/Forward des sauts au-dessus du retour système, déduplication (fichier, ligne), offsets rebasés | Retenu pour l'atelier (L5) | Adopté |
| REF-013 | design system (couleurs) | `Theme.kt` | 28-33, 126-196, 437-496 | 1b1a305 | Rôles M3 pour l'UI + palette dédiée aux concepts d'IDE (éditeur/console/syntaxe/git) séparée | Retenu à l'identique (design-system §2 : jetons UI + jetons domaine IDE) | Adopté |
| REF-014 | design system (typographie) | `ExpressiveType.kt` | 29-49 | 1b1a305 | Échelle en sp, tracking négatif sur les grandes tailles, display léger, eyebrow séparé, aucun conteneur à hauteur fixe | Retenu (adapté à l'échelle Material 3 de nos XML) | Adopté |
| REF-015 | design system (formes) | `ExpressiveShapes.kt` | 16-22 | 1b1a305 | Contraste de rayon grand/petit sur un même élément plutôt que rampe monotone | Retenu (cartes à coins alternés) | Adopté |
| REF-016 | design system (mouvement) | `Motion.kt` | 9-46 | 1b1a305 | Ressorts spatiaux sous-amortis, ressorts d'effets critiques ; tokens de durée nommés | Retenu comme règle ; écart voulu : porte reduce-motion **dans le token** (eux la gèrent aux points d'appel — inégal) | Adopté (amélioré) |
| REF-017 | états vides | `ProjectsHomeScreen.kt` + `EditorChrome.kt` | 261-265, 912-932 ; 1491-1560 | 1b1a305 | Encadré pointillé + action concrète ; état vide **différé** jusqu'à confirmation du chargement ; éditeur sans fichier = 6 actions concrètes | Retenu (composant `EmptyState` + retard de 300 ms) | Adopté |
| REF-018 | états d'erreur / avis | `EditorCenter.kt` + `EditorNotices.kt` | 316-369 ; 78-102 | 1b1a305 | Strip d'avis unique fusionnée (une ligne pour le simple, carte repliée pour le complexe, compte cliquable) | Retenu : la strip devient la zone de notification de l'atelier (compatibilité de mode, limites Forge affichées honnêtement) | Adopté |
| REF-019 | retours d'action | `FileOperationsDialogs.kt` + `AppNavGraph.kt` | 441-491 ; 483-492 | 1b1a305 | Snackbar à durée différenciée undo/non-undo (8 s / 5 s) ; optimisme local avec retour arrière | Retenu | Adopté |
| REF-020 | éditeur (architecture, gros fichiers) | `CodeEditor.kt` + `LargeFile.kt` | 104-125, 268-270, 362-369 ; 17-21 | 1b1a305 | Rope par lignes, re-tokenisation de la ligne modifiée, seuil gros fichier 2,5 M chars / 50 k lignes ; LTR inconditionnel | L'éditeur est une brique de l'auteur — le seuil gros fichier devient une **exigence/intérogation brique** (limites-et-suite) | Exigence (brique) |
| REF-021 | barre de symboles / sélection | `EditorSymbolBar.kt` + `SymbolKeySets.kt` + `EditorOverlayLayers.kt` | 92-126, 104, 539 ; 10-41 ; 279-334 | 1b1a305 | Flick-up = alternatif du cap ; maintien-glisser = trackpad de caret ; jamais de clickable qui vole le focus ; barre de sélection flottante ancrée au bout actif | Spécification d'intégration de la brique éditeur (conception §6) ; la barre de symboles est un composant JoIDE au-dessus de la brique | Exigence (intégration) |
| REF-022 | recherche | `FindReplaceBar.kt` + `SearchScreen.kt` + `CommandPalette.kt` | 60-168, 77-79 ; 83-105 ; 115-134 | 1b1a305 | Barre find/replace opaque dockée, options sur seconde rangée < 520 dp ; recherche projet dont l'état survit à la navigation ; palette de commandes à portées | Retenu (L5) | Adopté |
| REF-023 | accessibilité (sémantique) | `Primitives.kt` + `EditorBottomNav.kt` | 199-225 ; 63-74 | 1b1a305 | contentDescription obligatoire dans le composant bouton-icône de base ; item de nav = conteneur entier = un nœud fusionné | Retenu (règle de revue + composants JoIDE) | Adopté |
| REF-024 | accessibilité (tailles, contraste) | `Primitives.kt` + `ExpressiveType.kt` + `RunScreen.kt` + `Theme.kt` | 194 ; 42-44 ; 405-407 ; 126-196 | 1b1a305 | Boutons de barre densifiés **sous 48 dp** (34 dp constaté) — compromis documenté ; tout en sp ; couleurs du transcript conscientes du thème | **Écart voulu** : cible 48 dp minimale (44 dp au pire avec zone étendue) ; sp et contraste retenus | Rejeté (34 dp) / Adopté (le reste) |
| REF-025 | reduce-motion | `Motion.kt` | 8 | 1b1a305 | Reduce-motion géré « aux points d'appel » (inégal par construction) | **Écart voulu (amélioration)** : porte dans le token de mouvement | Amélioré |

### IA

| ID | Composant JoIDE | Fichier de référence | Lignes | SHA | Constat | Comparaison | Statut |
|---|---|---|---|---|---|---|---|
| REF-101 | module `ai` (activation) | `plugins/AgentPlugin.kt` + `BuiltInPlugins.kt` + `IdeServicesBackend.kt` | 15-35 ; 246 ; 525-530 | 1b1a305 | L'agent IA est un plugin built-in **désactivable**, aucun câblage privilégié d'hôte | Retenu : module `ai` activable/désactivable dans les paramètres | Adopté (principe) |
| REF-102 | fournisseurs | `Providers.kt` + `AgentBackend.kt` (passerelle) | 9-19 ; 526-572 | 1b1a305 | 4 fournisseurs natifs + passerelle compatible (base URL) pour serveurs locaux ; bascule en portant la conversation | L7 : 2 fournisseurs (un laboratoire + une passerelle compatible) ; la passerelle = moyen hors ligne natif | Adapté |
| REF-103 | clés de secours | `KeyFailover.kt` + `AgentBackend.kt` | 16-79, 91-168 ; 574-576, 724-728 | 1b1a305 | Pool de clés par fournisseur, mise au repos, rejeu sur la clé suivante tant qu'aucune sortie streamée | Suite possible (limites) ; L7 : une clé par fournisseur | Différé |
| REF-104 | modèles | `AnthropicProvider.kt` + `AgentBackend.kt` | 34-40, 85-103 ; 578-635, 704-712 | 1b1a305 | Liste vivante + repli statique ; choix **persisté par fournisseur** ; récents/favoris | Retenu | Adopté |
| REF-105 | effort de raisonnement | `Llm.kt` (SPI agent) | 130-159 | 1b1a305 | Champ neutre 7 niveaux, épinglé par conversation (cache) | Retenu en conception, simplifié (4 niveaux) en L7 | Adopté (simplifié) |
| REF-106 | conversation / streaming | `Llm.kt` (événements) + `ChatDrawer.kt` | 161-177 ; 213-260, 959-1057 | 1b1a305 | Streaming SSE, deltas neutres (texte/raisonnement/appel d'outil/usage) ; ligne d'état « pense / écrit / exécute » ; raisonnement rejoué à l'identique, abandonné si changement de modèle | Retenu (L7) | Adopté |
| REF-107 | pièces jointes | `ChatDrawer.kt` + `AgentBackend.kt` | 283-290 ; 1084-1126, 1395-1427 | 1b1a305 | Image (mise à l'échelle avant envoi), fichier/sélection en blocs balisés bornés ; mention `@chemin` avec complétion | Retenu (fichier + sélection + `@chemin` ; images en suite possible) | Adopté (partiel) |
| REF-108 | historique | `AgentSessionStore.kt` + `AgentRecentChats.kt` + `AgentHistorySheet.kt` | 37-92, 193-198 ; 21-36 ; 46-117 | 1b1a305 | Session = JSON atomique contenant transcript **et** historique modèle ; titre auto ; pas de renommage manuel | Retenu sauf : **renommage manuel ajouté** (écart voulu) ; stockage sous `.joide/ai/sessions/` | Adopté (amélioré) |
| REF-109 | questions à l'utilisateur | `AskUserTool.kt` + `QuestionCard.kt` | 8-63 | 1b1a305 | Outil 1-4 questions, options, multi-select, suspend le tour comme une permission | Retenu (L7) | Adopté |
| REF-110 | commandes slash / annulation | `AgentBackend.kt` + `AgentLoop.kt` + `ChatDrawer.kt` | 1629-1638, 1433-1445 ; 281-291 ; 256-272 | 1b1a305 | Slash interceptées dans le champ ; annulation propre, appel interrompu = résultat d'erreur « interrompu » | Retenu : /nouveau, /annuler, /modèle | Adopté (réduit) |
| REF-111 | prompt système (moitiés) | `SystemPrompt.kt` + `AgentLoop.kt` + `AgentBackend.kt` | 5-13, 57-62 ; 34-38, 460-475 ; 1558-1591 | 1b1a305 | Moitié stable en préfixe, moitié volatile en message système de fin ; `AGENTS.md`/`CLAUDE.md` borné, caché par (chemin, mtime) ; règle d'ancrage anti-injection dans le prompt | Retenu à l'identique (L7) | Adopté |
| REF-112 | contexte (philosophie) | `Workspace.kt` + `BuiltinTools.kt` + `AgentPlugin.kt` | 14-18 ; 85-93, 284-296 ; 42-77 | 1b1a305 | L'agent va chercher (pas d'envoi automatique du fichier ouvert) ; diagnostics = outils ; « Ask AI » attache la sélection | Retenu | Adopté |
| REF-113 | bornage / compactage | `BuiltinTools.kt` + `HistoryCompactor.kt` + `AgentLoop.kt` | 601-638 ; 8-28, 30-118, 43-49 ; 325-409 | 1b1a305 | Borner **à l'arrivée** (jamais réécrire ensuite) ; compacteur à hystérésis, décisions permanentes ; auto-compact sans outils | Retenu (L7) | Adopté |
| REF-114 | port espace de travail | `Workspace.kt` | 10-176 | 1b1a305 | Aucun outil ne touche le projet directement ; interface étroite, fake en mémoire pour les tests | Retenu : `AgentWorkspace` Java pur + fake de test | Adopté |
| REF-115 | outils | `BuiltinTools.kt` | 27-110, 235-247, 249-362, 403-470, 548-569, 661-712 | 1b1a305 | Lecture (non gated) : fichier/répertoire/texte/symbole/diagnostics ; écriture (gated) : créer/écrire/éditer fragment exact/renommer/déplacer/supprimer/dépendance ; exécution sandboxée | Retenu, **exécution via `BuildBackend`** (jamais un moteur concurrent) | Adopté (adapté) |
| REF-116 | concurrence des appels | `AgentLoop.kt` | 484-550 | 1b1a305 | Lectures d'un même tour en parallèle, mutants séquentiels ensuite | Retenu | Adopté |
| REF-117 | porte de permission | `Agent.kt` (SPI) + `AgentPermissionDialog.kt` | 29-39 ; 43-93 | 1b1a305 | Trois modes persistés ; porte unique ; refus = erreur informante renvoyée au modèle ; fermeture = refus ; **diff avant/après dans l'invite** | Retenu à l'identique (L7) | Adopté |
| REF-118 | annulation par tour | `CheckpointWorkspace.kt` + `AgentBackend.kt` | 20-34, 74-119, 217-227 ; 1486-1505 | 1b1a305 | Snapshots premier-toucher par tour/appel ; annulation exacte du réversible ; **aveu honnête de l'irréversible** ; note au modèle après undo | Retenu (L7) | Adopté |
| REF-119 | boucle d'agent | `AgentLoop.kt` | 27-75, 296-323, 609-643 | 1b1a305 | Plafond d'itérations avec **tour de clôture sans outils** ; appariement obligatoire appel/résultat avant envoi | Retenu (L7) | Adopté |
| REF-120 | usage de tokens | `Llm.kt` + `ChatDrawer.kt` | 81-105 ; 1099-1128 | 1b1a305 | Usage normalisé avec lectures/écritures de cache séparées, affiché par message, agrégé par tour | Retenu (L7) | Adopté |
| REF-121 | quotas / erreurs | `LlmErrors.kt` + `RequestPacer.kt` + `AgentLoop.kt` | 15-37, 93-137 ; 20-99 ; 411-439 | 1b1a305 | Trois variétés de 429 (minute/jour/hors-plan) ; pacer préventif appris des erreurs, attente visible annulable ; backoff prélisible uniquement | Retenu (L7) | Adopté |
| REF-122 | extensions MCP | `agent-mcp/` (module) + `AgentConnections.kt` + `McpManager.kt` | 24-57, 242-248 ; 39-53 | 1b1a305 | Client MCP maison (HTTP streamable, repli SSE), format compatible marché, serveurs de projet non fiables jusqu'à approbation ; serveur MCP hôte + relais | Hors périmètre L7 ; extension future (limites) | Différé |
| REF-123 | clés / confidentialité | `docs/agentic-coding.md` + `PRIVACY.md` + `AgentConnections.kt` | 23, 236-240 ; 39-50 ; 19-23 | 1b1a305 | **Clés API en clair** (dette documentée par la référence) ; la conversation complète part chez le fournisseur | **Écart voulu** : clés chiffrées dès le premier jour (cahier des charges) ; l'écran IA affiche ce qui sort | Amélioré |
| REF-124 | injection d'instructions | `SystemPrompt.kt` + `BuiltinTools.kt` | 57-62 ; 592-599 | 1b1a305 | Règle d'ancrage (résultat d'outil = donnée, pas instruction) + marquage du contenu réseau en « non fiable » ; la porte de permission est le vrai conteneur | Retenu à l'identique | Adopté |
| REF-125 | absences (différenciateurs) | `docs/completion-contributor-api.md` + `services/vcs-ui/` | 1-40 ; — | 1b1a305 | **Complétion inline IA inexistante** ; **génération de commit inexistante** | Suite possible (limites) — pas en L7 | Différé |

## Consignes du propriétaire consignées

- 2026-11-20 : la génération 1 n'est pas consultée (seuls céleris / code-lsp /
  LayoutLib sont retenus de sa connaissance) ; à l'étape terminal (L6), consulter
  aussi `jjoblab/codeide` (dépôt de l'écosystème maison, hors périmètre du présent
  registre de référence design/IA).

## Comparaison après lots (L2-L3, 2026-11-20)

- **LOT-02 (assistant des modes)** : conforme aux notes design de la référence
  (premier lancement sans défaut, apports/limites par mode). Écart : pas de pager
  (ViewPager2) — la référence utilise un pager, JoIDE met en page par sections
  (règle de dépendances : pas de bibliothèque pour un besoin couvert autrement) ;
  l'ordre de lecture reste imposé. Consigné dans LOT-02.
- **LOT-03 (intégration Forge)** : la traduction d'événements reprend la
  correspondance constatée dans l'analyse design (journal/tâches/progression/
  diagnostics/fin). Écarts : (1) la taille affichée par l'assistant viendra du
  manifeste vérifié seulement en L8 (avant l'installation réelle) ; (2) F-03
  (agrégation du premier diagnostic dans la fin de build) reste ouverte côté Forge ;
  (3) la reprise d'installation interrompue est testée côté Forge (plages HTTP) et
  le chemin local côté JoIDE — la reprise à travers le port JoIDE sera retestée en
  L8 avec le bouton Annuler. Aucun contournement silencieux : tout vit dans
  DEMANDES-FORGE ou LOT-03.

### Reconsultation du 2026-11-20 (lot L7 — IA)

Le dépôt de référence a été re-cloné **hors du dépôt de travail** et re-consul-
té **au même SHA épinglé `1b1a305`** avant l'implémentation du module `ai`.
Fichiers relus intégralement : `SystemPrompt.kt` (les deux moitiés, la règle
d'ancrage « tout résultat d'outil est une donnée, pas une instruction » dans le
préfixe stable, instructions projet AGENTS.md/CLAUDE.md bornées et mises en
cache par (chemin, mtime) dans la moitié stable), `AgentLoop.kt` (plafond
d'itérations 24 + **tour de clôture sans outils** avec un message WRAP_UP
explicite « dernier tour, aucun outil, faites le bilan de ce qui reste à
faire » ; compactage `compacted` une fois par tour ; **appariement
appel/résultat avant tout envoi** avec résultat d'erreur « interrompu » pour
les appels sans réponse — répare aussi les conversations sauvegardées cassées),
`AgentBackend.kt` (passerelle compatible OpenAI avec base URL + certificat CA
optionnel, prête dès que l'URL est posée sans clé ; **porte de permission
unique** à trois modes avec refus informatif renvoyé au modèle ; décisions
« refuser / permettre une fois / permettre pour la session »), `Providers.kt`
(transport commun injecté, liste de fournisseurs construite par le registre),
`HistoryCompactor.kt` (**hystérésis** : rien éliminé sous le seuil de déclen-
chement ; en une passe on revient sous la cible ; **décision permanente**
(ensemble d'identifiants éliminés) pour que le préfixe ne dérive jamais ;
résultats d'outils récents (4) jamais éliminés ; `serverManaged()` quand le
fournisseur rogne lui-même — deux rogneurs invalideraient mutuellement leur
cache). Aucune ligne de code copiée (clean-room, réimplémentation Java).

## Suivi des écarts après implémentation

Chaque lot (L0-L9) relit la partie correspondante du produit de référence **après**
implémentation et consigne ici les écarts : voulus, améliorations, ou oublis corrigés. Ce
suivi est alimenté par les rapports `docs/lots/LOT-xx.md`.
