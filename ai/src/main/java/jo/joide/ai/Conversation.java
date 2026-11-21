package jo.joide.ai;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Conversation (Lot 4) : **double contenu** — le transcript vu par
 * l'utilisateur (messages) ET l'historique exact vu par le modèle (avec
 * appels et résultats d'outils) — plus le fournisseur/modèle utilisés,
 * l'effort épinglé et l'usage cumulé. Changer de fournisseur ou de modèle
 * en cours de conversation la porte intacte ; le transcript se recharge,
 * l'historique se rejoue.
 *
 * <p>Instance non modifiable : les méthodes {@code with…} rendent des copies.</p>
 */
public record Conversation(String id, String title, long createdAt, long updatedAt,
        String providerId, String model, String effort, Usage usage,
        List<Message> messages, List<HistoryEntry> history) {

    /** Version de schéma courante (les fichiers v1 se migrèrent à la lecture). */
    public static final int SCHEMA_VERSION = 2;

    /** Un message du transcript vu (rôle user/assistant, contenu texte). */
    public record Message(String role, String content) {}

    /**
     * Une entrée de l'historique exact vu par le modèle (rôle, contenu,
     * appels d'outils éventuels, id d'appariement).
     */
    public record HistoryEntry(String role, String content,
            List<ToolCallData> toolCalls, String toolCallId) {

        /** Entrée sans appel d'outil (user/assistant/system). */
        public static HistoryEntry plain(String role, String content) {
            return new HistoryEntry(role, content, null, null);
        }
    }

    /** Appel d'outil tel qu'archivé (miroir neutre du format transport). */
    public record ToolCallData(String id, String name, String argumentsJson) {}

    public Conversation {
        messages = safeCopy(messages);
        history = safeCopy(history);
        usage = usage == null ? Usage.unknown() : usage;
    }

    private static <T> List<T> safeCopy(List<T> values) {
        if (values == null || values.isEmpty()) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(new ArrayList<>(values));
    }

    /** Conversation neuve (titre posé au premier message par le store). */
    public static Conversation fresh(String id, long now) {
        return new Conversation(id, "", now, now, null, null, null, null, null, null);
    }

    /** Ajoute un message au transcript (nouvelle instance). */
    public Conversation withMessage(Message message, long now) {
        List<Message> next = new ArrayList<>(messages);
        next.add(message);
        return new Conversation(id, title, createdAt, now, providerId, model, effort,
                usage, next, history);
    }

    /** Change le titre (nouvelle instance). */
    public Conversation withTitle(String newTitle, long now) {
        return new Conversation(id, newTitle, createdAt, now, providerId, model,
                effort, usage, messages, history);
    }

    /** Change le fournisseur/modèle utilisés (la conversation est portée). */
    public Conversation withModel(String providerId, String model, long now) {
        return new Conversation(id, title, createdAt, now, providerId, model, effort,
                usage, messages, history);
    }

    /** Épingle l'effort de raisonnement de la conversation. */
    public Conversation withEffort(String effort, long now) {
        return new Conversation(id, title, createdAt, now, providerId, model, effort,
                usage, messages, history);
    }

    /** Cumule l'usage d'un tour (nouvelle instance). */
    public Conversation withUsage(Usage turnUsage, long now) {
        if (turnUsage == null || turnUsage.isUnknown()) {
            return this;
        }
        Usage cumulative = new Usage(
                Math.max(0, usage.promptTokens()) + turnUsage.promptTokens(),
                Math.max(0, usage.completionTokens()) + turnUsage.completionTokens(),
                Math.max(0, usage.reasoningTokens()) + turnUsage.reasoningTokens(),
                Math.max(0, usage.cachedPromptTokens()) + turnUsage.cachedPromptTokens());
        return new Conversation(id, title, createdAt, now, providerId, model, effort,
                cumulative, messages, history);
    }

    /** Remplace tout l'historique modèle (rejoué par la session). */
    public Conversation withHistory(List<HistoryEntry> newHistory, long now) {
        return new Conversation(id, title, createdAt, now, providerId, model, effort,
                usage, messages, newHistory);
    }

    /** Premier message utilisateur ; null si aucun. */
    public Message firstUserMessage() {
        for (Message message : messages) {
            if ("user".equals(message.role())) {
                return message;
            }
        }
        return null;
    }

    /** Dernier message du transcript ; null si vide. */
    public Message lastMessage() {
        return messages.isEmpty() ? null : messages.get(messages.size() - 1);
    }

    /** Traduit une entrée ConversationHistory vers la forme archivée. */
    public static HistoryEntry fromHistory(ConversationHistory.Entry entry) {
        List<ToolCallData> calls = null;
        if (entry.toolCalls() != null && !entry.toolCalls().isEmpty()) {
            calls = new ArrayList<>();
            for (LlmTransport.ToolCall call : entry.toolCalls()) {
                calls.add(new ToolCallData(call.id(), call.name(), call.argumentsJson()));
            }
        }
        return new HistoryEntry(entry.role(), entry.content(), calls, entry.toolCallId());
    }

    /** Traduit l'entrée archivée vers ConversationHistory (rejeu au modèle). */
    public static ConversationHistory.Entry toHistory(HistoryEntry entry) {
        List<LlmTransport.ToolCall> calls = null;
        if (entry.toolCalls() != null && !entry.toolCalls().isEmpty()) {
            calls = new ArrayList<>();
            for (ToolCallData call : entry.toolCalls()) {
                calls.add(new LlmTransport.ToolCall(call.id(), call.name(),
                        call.argumentsJson()));
            }
        }
        return new ConversationHistory.Entry(entry.role(), entry.content(), calls,
                entry.toolCallId());
    }
}
