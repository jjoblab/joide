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

    /** Requête de complétion (jeton d'annulation et effort optionnels). */
    record ChatRequest(String model, List<ChatMessage> messages, List<ToolSpec> tools,
            int maxTokens, String reasoningEffort, CancellationToken cancellation) {

        /** Requête simple (sans annulation ni effort). */
        public ChatRequest(String model, List<ChatMessage> messages,
                List<ToolSpec> tools, int maxTokens) {
            this(model, messages, tools, maxTokens, null, null);
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
     * Liste vivante des modèles du compte (endpoint /models) ; peut échouer —
     * l'appelant retombe sur sa liste statique.
     */
    List<String> listModels(String baseUrl, String apiKey) throws Exception;

    /**
     * Lance une requête de complétion en flux.
     *
     * @param baseUrl   base de l'endpoint (fournisseur ou passerelle).
     * @param apiKey    clé du fournisseur (peut être nulle sur serveur local).
     * @param request   requête (modèle, messages, outils, jeton d'annulation).
     * @param onEvent   consommateur de chaque delta (thread transport).
     */
    void chat(String baseUrl, String apiKey, ChatRequest request,
            java.util.function.Consumer<StreamEvent> onEvent) throws Exception;
}
