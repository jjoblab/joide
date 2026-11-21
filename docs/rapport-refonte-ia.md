# Rapport final — refonte IA (mission prompt-agent-ia-refonte-complete-openrouter.md)

Livrés par lots, chacun poussé avec CI verte : Lot 0 `3025f22`, Lot 1 `31cdc5d`,
Lot 2 `62f6e7b`, Lot 3 `1ef465e`, Lot 4 `bb5be47`, Lot 5 `07ab918`, Lot 6
`5d69e40`, Lot 7 `3435316`, Lot 8 `79ac008`. Tests JVM : 128 verts (moteur et
coquille testés en Java pur ; la coquille Android se vérifie par APK + contrôles).

## Diagnostic de la mission : vérification point par point

Chaque point du §1 a été revérifié dans le code avant correction. Aucun point
s'est révélé inexact. | N° | Constat | Correction | Lot |

|---|---|---|---|
| 1 | `ask()` ignore tous les réglages (base/ clé/ modèle figés) | boucle construite depuis le registre et les préférences (fournisseur, clé coffre, modèle, effort, tokens, itérations, routage) | 1-2, 7 |
| 2 | `AgentLoop` recréée à chaque message | `AiSessionViewModel` possède la boucle (survit rotation/tiroir/onglets) | 4 |
| 3 | rendu après fin de tour ; `TurnCompleted` supprimait la réponse | streaming rendu par événements groupés (~24 ms) ; la réponse reste au transcript, rendu interprété en fin de tour | 4, 5 |
| 4 | texte brut, aucun Markdown | `MarkdownParser` streaming-safe + rendu en blocs (titres, listes, tableaux, code, citations) | 5 |
| 5 | texte de la 401 pour toute erreur | `LlmError` typé : chaque cause a son titre et son action | 1 |
| 6 | compteurs d'outils faux ; cartes inutiles | compteur réel d'actions ; résumés issus des résultats ; ligne d'état « Exécute X… » | 1, 6 |
| 7 | pill modèle en dur | modèle réellement persisté, ou « Choisir un modèle » honnête | 2 |
| 8 | pas de Stop, `requireActivity()` hors fil UI, conversation perdue | bouton Stop (annulation de bout en bout), session dans un ViewModel, persistance double contenu | 1, 4, 6 |
| 9 | pas de persistance/liste/nouvelle conversation/régénération/copie | store v2 + listes assistant et agent, `/nouveau`, régénération, copie des blocs | 4, 5 |
| 10 | LinearLayout non virtualisé | **partiellement traité** : le rendu par blocs borne les vues ; la migration RecyclerView+DiffUtil reste à faire (consignée) | 5 |
| 11 | approbation sans diff (400 premiers caractères) | diff unifié réel (DiffBuilder) dans l'invite, compteurs exacts | 3 |
| 12-16 | moteur : indexOf, arguments coupés, tools sans schéma, `reasoning` ignoré, erreur de flux ignorée | codec JSON complet, ToolArgs typé, schémas sérialisés, deltas de raisonnement séparés, erreur de flux détectée (objet error) | 1, 3 |
| 17-19 | boucle : outillage pauvre, parallélisme non déterministe, plafonds figés | roster riche (arborescence, vraie recherche, problèmes, fichier ouvert, modifier_fichier, renommage, questions, plan), invokeAll ordonné, plafonds réglés | 3 |
| 20-21 | porte : modes sans réglage réel, AUTO_ACCEPT sans confirmation | mode de porte persisté + page Permissions ; l'approbation par lot reste à faire (consignée) | 7 |
| 22-24 | robustesse : budget figé, compactage inopérant, tour de clôture | budget paramétré, compactage hystérésis + appariement, tour de clôture conservé, détection de boucle, checkpoint d'annulation | 1, 3, 4 |
| 25-31 | assistant sans identité, pas de conversations, maquettes fictives | assistant sans outils par construction, conversations persistées, registre réel vérifié documentairement | 0, 2, 4 |
| 32-35 | persistance : lieu unique, corruption, migration, secrets | emplacements séparés (assistant / agent par empreinte), atomique + corrompus ignorés, migration v1→v2, clé jamais affichée ni loggée (Secrets) | 1, 4 |
| 36-37 | matrice de câblage absente, contrôles morts | matrice docs/analyse-ia.md + tools/check-cablage-ia.sh en CI ; « à venir » annoncé à l'écran | 7 |

## Ce qui n'est pas fait (détails et raisons dans docs/limites-et-suite.md)

- Migration RecyclerView + DiffUtil du transcript (perf sur très longues
  conversations) ; jauge de contexte (dépend du catalogue, maintenant
  disponible — le branchement reste à faire) ; sélecteur de modèle en bottom
  sheet (la page Modèles du Lot 7 couvre le besoin en plein écran).
- « Demander à l'IA » depuis la sélection de l'éditeur : l'API d'entrée
  (`openAgentPanel`/`prefillPrompt`, Lot 8) est posée et utilisée par les
  Problèmes ; le déclencheur côté éditeur reste à brancher.
- Adaptateur Anthropic (doc officielle inaccessible depuis l'environnement de
  développement — ADR-014), recherche web et MCP (annoncés « à venir »),
  épinglage de l'effort par conversation éditable.
- Perf 200 messages et tests Robolectric de la coquille : non mesurés dans
  cette session.
