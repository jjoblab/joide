# Matrice de câblage des réglages IA (Lot 7)

Chaque réglage a un **écrivain** (l'écran qui le pose) et un **lecteur** (le
code qui l'utilise). Le contrôle automatique `tools/check-cablage-ia.sh`
vérifie chaque clé des deux côtés et **échoue** si une clé n'a pas de lecteur
(les clés explicitement « à venir » sont listées dans le script, visibles
aussi dans l'écran).

| Clé (joide_reglages_ia) | Écrivain | Lecteur | État |
|---|---|---|---|
| `fournisseur` | SettingsIaPageFragment (chips registre) | providerId(), AiSessionViewModel.buildLoop, hub | câblé |
| `modele_<provider>` | IaModelsPageFragment (sélection / saisie manuelle) | AiSessionViewModel.modelOf, AiPanelFragment.persistedModel, hub | câblé |
| `base_url_custom` | SettingsIaPageFragment (URL passerelle) | AiSessionViewModel.buildLoop, IaModelsPageFragment | câblé |
| `effort` | IaBehaviorPageFragment (chips) | AiSessionViewModel.buildLoop (Efforts.toApi → raisonnement fournisseur) | câblé |
| `tokens_max` | IaBehaviorPageFragment (pill) | AiSessionViewModel.buildLoop (ChatRequest.maxTokens) | câblé |
| `iterations_max` | IaBehaviorPageFragment (pill) | AiSessionViewModel.buildLoop (plafond AgentLoop) | câblé |
| `mode_permission` | IaPermissionsPageFragment (chips mode) | MainActivity (restauration porte), SettingsIaHubFragment.modeOf | câblé |
| `or_attribution` | SettingsIaPageFragment (switch) | AiSessionViewModel.buildLoop (en-tête X-Title), SettingsIaPageFragment.attributionHeaders | câblé |
| `or_sort` | SettingsIaPageFragment (chips routage) | AiSessionViewModel.buildLoop (provider.sort) | câblé |
| `or_allow_fallbacks` | SettingsIaPageFragment (switch) | AiSessionViewModel.buildLoop (provider.allow_fallbacks) | câblé |
| `or_require_parameters` | SettingsIaPageFragment (switch) | AiSessionViewModel.buildLoop (provider.require_parameters) | câblé |
| `or_data_collection` | SettingsIaPageFragment (switch) | AiSessionViewModel.buildLoop (provider.data_collection=deny) | câblé |
| `recherche_web` | IaBehaviorPageFragment (switch) | — (l'outillage arrive avec l'outil de navigation) | **à venir** (dit dans l'écran) |
| `serveur_mcp` | IaBehaviorPageFragment (switch) | — (l'outillage arrive avec le support MCP) | **à venir** (dit dans l'écran) |
| `laboratoire_averti` | SettingsIaPageFragment (avertissement unique) | SettingsIaPageFragment | interne (migration) |
| `laboratoire_averti`¹ | — | — | ¹ drapeau interne, jamais un réglage utilisateur |
