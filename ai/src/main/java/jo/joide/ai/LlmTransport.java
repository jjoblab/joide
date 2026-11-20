package jo.joide.ai;

import java.util.List;

/**
 * Transport LLM (lot L7, conception §8) : port unique pour l'envoi d'une requête
 * de complétion en flux (SSE) — l'implémentation de production est
 * `HttpURLConnection` (Android 28, pas de dépendance réseau ajoutée), les tests
 * injectent un transport mock. Le réseau n'est jamais appelé sur le fil UI
 * (conception §10 : l'IA tourne dans son service avec son propre pool).
 */
public interface LlmTransport {

    /** Message de la conversation (rôles du format passerelle). */
    record ChatMessage(String role, String content, List<ToolCall> toolCalls,
            String toolCallId) {

        /** Message système (moitié stable en préfixe, volatile en fin d'historique). */
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

    /** Outil exposé au modèle (roster de la moitié stable du prompt). */
    record ToolSpec(String name, String description) {}

    /** Requête de complétion. */
    record ChatRequest(String model, List<ChatMessage> messages, List<ToolSpec> tools,
            int maxTokens) {}

    /** Delta de flux : texte, appel d'outil (cumulatif à l'arrivée), fin ou échec. */
    sealed interface StreamEvent {

        /** Fragment de texte du modèle. */
        record Text(String delta) implements StreamEvent {}

        /** Appel d'outil complet (nom + arguments JSON cumulés). */
        record ToolCallEvent(ToolCall call) implements StreamEvent {}

        /** Fin normale de la génération. */
        record Finish(String stopReason) implements StreamEvent {}

        /** Échec du flux (réseau, quota, refus) : message lisible, jamais la clé. */
        record Failure(String message) implements StreamEvent {}
    }

    /**
     * Liste vivante des modèles du compte (endpoint /models) ; peut échouer —
     * l'appelant retombe sur sa liste statique.
     */
    List<String> listModels(String baseUrl, String apiKey) throws Exception;

    /**
     * Lance une requête de complétion en flux.
     *
     * @param baseUrl   base de l'endpoint (passerelle : URL utilisateur).
     * @param apiKey    clé du fournisseur (peut être nulle sur serveur local).
     * @param request   requête (modèle, messages, outils).
     * @param onEvent   consommateur de chaque delta (thread transport).
     */
    void chat(String baseUrl, String apiKey, ChatRequest request,
            java.util.function.Consumer<StreamEvent> onEvent) throws Exception;
}
