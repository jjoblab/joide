package jo.joide.ai;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Historique envoyé au modèle : appariement **obligatoire** appel/résultat
 * avant tout envoi (conception §8, REF-119) — un appel d'outil sans réponse
 * reçoit un résultat d'erreur « interrompu » placé après les résultats que le
 * tour a produits, et un résultat qui ne répond à aucun appel est éliminé.
 * Répare aussi les conversations sauvegardées dans cet état (reprise).
 */
public final class ToolCallPairing {

    private ToolCallPairing() {}

    /**
     * Historique apparié : chaque appel a un résultat ; chaque résultat répond
     * à un appel. Retourne la même liste si rien ne manquait.
     */
    public static List<ConversationHistory.Entry> pair(
            List<ConversationHistory.Entry> history) {
        boolean changed = false;
        List<ConversationHistory.Entry> out = new ArrayList<>(history.size());
        Map<String, Boolean> answered = new LinkedHashMap<>();
        int i = 0;
        while (i < history.size()) {
            ConversationHistory.Entry entry = history.get(i++);
            if ("tool".equals(entry.role())) {
                // Résultat sans appel juste avant : éliminé.
                changed = true;
                continue;
            }
            out.add(entry);
            if (!"assistant".equals(entry.role()) || entry.toolCalls() == null
                    || entry.toolCalls().isEmpty()) {
                continue;
            }
            List<String> pending = new ArrayList<>();
            entry.toolCalls().forEach(c -> pending.add(c.id()));
            while (i < history.size() && "tool".equals(history.get(i).role())) {
                ConversationHistory.Entry result = history.get(i++);
                if (result.toolCallId() != null && pending.contains(result.toolCallId())) {
                    pending.remove(result.toolCallId());
                    out.add(result);
                    answered.put(result.toolCallId(), Boolean.TRUE);
                } else {
                    changed = true; // résultat dupliqué ou orphelin
                }
            }
            if (!pending.isEmpty()) {
                changed = true;
                for (String id : pending) {
                    out.add(new ConversationHistory.Entry("tool",
                            SystemPrompt.INTERRUPTED_TOOL_RESULT, null, id));
                }
            }
        }
        return changed ? out : history;
    }
}
