package jo.joide.ai;

import java.util.List;

/**
 * Transport LLM (port du module ai) : envoi d'une complétion en flux (SSE).
 * L'implémentation de production est {@link HttpLlmTransport}
 * ({@code HttpURLConnection}, disponible dès l'API 24, aucune dépendance) ;
 * les tests injectent un transport mock. Le réseau n'est jamais appelé sur
 * le fil UI (conception §10).
 */
public interface LlmTransport {

    /**
     * Point de terminaison complet (Lot 2) : URLs du chat et des modèles (les
     * chemins varient selon le fournisseur — voir {@code ProviderSpec}), clé,
     * en-têtes additionnels (attribution OpenRouter). Construit via
     * {@code ProviderSpec.endpoint(...)} — jamais à la main.
     */
    record Endpoint(String chatUrl, String modelsUrl, String apiKey,
            java.util.Map<String, String> headers) {

        /** Endpoint simple (clé, pas d'en-têtes additionnels). */
        public Endpoint(String chatUrl, String modelsUrl, String apiKey) {
            this(chatUrl, modelsUrl, apiKey, java.util.Collections.emptyMap());
        }
    }

    /** Message de la conversation (rôles du format passerelle). */
    record ChatMessage(String role, String content, List<ToolCall> toolCalls,
            String toolCallId) {

        /** Message système (moitié stable en préfixe, volatile en fin). */
        public static ChatMessage system(String content) {
            return new ChatMessage("system", content, null, null);
        }

        /** Message utilisateur. */
        public static ChatMessage user(String content) {
            return new ChatMessage("user", content, null, null);
        }

        /** Message assistant avec appels d'outils. */
        public static ChatMessage assistant(String content, List<ToolCall> toolCalls) {
            return new ChatMessage("assistant", content, toolCalls, null);
        }

        /** Résultat d'un appel d'outil (id d'appariement obligatoire). */
        public static ChatMessage tool(String toolCallId, String content) {
            return new ChatMessage("tool", content, null, toolCallId);
        }
    }

    /** Appel d'outil demandé par le modèle (apparié par identifiant au résultat). */
    record ToolCall(String id, String name, String argumentsJson) {}

    /**
     * Outil exposé au modèle (Lot 1) : le **schéma JSON des paramètres**
     * accompagne la description — sans lui, un fournisseur conforme reçoit
     * des outils muets et le modèle ne sait pas quels arguments fournir.
     */
    record ToolSpec(String name, String description, String parametersSchemaJson) {

        /** Outil sans paramètres (schéma d'objet vide, valide). */
        public static ToolSpec of(String name, String description) {
            return new ToolSpec(name, description, "{\"type\":\"object\",\"properties\":{}}");
        }
    }

    /**
     * Requête de complétion (jeton d'annulation, effort et champs propres au
     * fournisseur optionnels — les extras sont fusionnés tels quels dans le
     * corps, valeurs déjà prêtes pour le JSON).
     */
    record ChatRequest(String model, List<ChatMessage> messages, List<ToolSpec> tools,
            int maxTokens, String reasoningEffort, CancellationToken cancellation,
            java.util.Map<String, Object> extras) {

        /** Requête simple (sans annulation ni effort ni extras). */
        public ChatRequest(String model, List<ChatMessage> messages,
                List<ToolSpec> tools, int maxTokens) {
            this(model, messages, tools, maxTokens, null, null,
                    java.util.Collections.emptyMap());
        }

        /** Requête avec annulation et effort (sans extras). */
        public ChatRequest(String model, List<ChatMessage> messages,
                List<ToolSpec> tools, int maxTokens, String reasoningEffort,
                CancellationToken cancellation) {
            this(model, messages, tools, maxTokens, reasoningEffort, cancellation,
                    java.util.Collections.emptyMap());
        }
    }

    /** Delta de flux : texte, raisonnement, appel d'outil, usage, fin ou échec. */
    sealed interface StreamEvent {

        /** Fragment de texte du modèle. */
        record Text(String delta) implements StreamEvent {}

        /** Fragment de raisonnement (jamais mélangé à la réponse). */
        record Reasoning(String delta) implements StreamEvent {}

        /** Appel d'outil complet (nom + arguments JSON cumulés par index). */
        record ToolCallEvent(ToolCall call) implements StreamEvent {}

        /** Usage signalé par le fournisseur (fin de flux). */
        record UsageEvent(Usage usage) implements StreamEvent {}

        /** Fin normale (vraie raison d'arrêt : stop, length, tool_calls…). */
        record Finish(String stopReason) implements StreamEvent {}

        /** Échec du flux : message rédigé, jamais la clé, cause typée. */
        record Failure(String message, LlmError error) implements StreamEvent {}
    }

    /**
     * Corps brut de la liste des modèles ({@code GET modelsUrl}) ; l'analyse
     * vit dans {@code ModelCatalog} (format enrichi ou simple). Peut échouer —
     * l'appelant retombe sur son cache.
     */
    String fetchModelsJson(Endpoint endpoint) throws Exception;

    /**
     * Lance une requête de complétion en flux.
     *
     * @param endpoint point de terminaison complet (URLs, clé, en-têtes).
     * @param request  requête (modèle, messages, outils, jeton d'annulation, extras).
     * @param onEvent  consommateur de chaque delta (thread transport).
     */
    void chat(Endpoint endpoint, ChatRequest request,
            java.util.function.Consumer<StreamEvent> onEvent) throws Exception;
}
