package jo.joide.ai;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Historique de conversation de l'agent avec compactage à hystérésis
 * (conception §8, REF-113) : rien n'est éliminé sous le seuil de déclenchement
 * (le préfixe reste octet-identique — le cache du fournisseur survit) ; au
 * déclenchement, une passe élimine les résultats d'outils anciens (hors des
 * plus récents, jamais touchés) jusqu'à repasser sous la cible ; la décision
 * est **permanente** (un résultat éliminé le reste) pour que le préfixe ne
 * dérive jamais en arrière. Le texte utilisateur/assistant n'est jamais touché.
 *
 * <p>L'historique stocké reste complet : la vue compactée est recalculée à
 * chaque appel, sans muter l'original (un retry re-dérive la même vue).</p>
 */
public final class ConversationHistory {

    /** Message de l'historique (rôle, contenu, appels d'outils éventuels). */
    public record Entry(String role, String content, List<LlmTransport.ToolCall> toolCalls,
            String toolCallId) {

        /** Taille approximative du message dans le budget de contexte. */
        public int size() {
            int total = (content == null ? 0 : content.length())
                    + (toolCallId == null ? 0 : toolCallId.length());
            if (toolCalls != null) {
                for (LlmTransport.ToolCall call : toolCalls) {
                    total += call.name().length() + call.argumentsJson().length();
                }
            }
            return total;
        }

        /** Copie au contenu éliminé (même rôle et appels). */
        public Entry elided() {
            return new Entry(role, "(résultat éliminé par le compactage)", toolCalls,
                    toolCallId);
        }
    }

    private final List<Entry> entries = new ArrayList<>();
    private final int triggerChars;
    private final int targetChars;
    private final int keepRecentToolResults;
    private final Map<String, Boolean> elidedCallIds = new LinkedHashMap<>();

    /** Compactage par défaut (seuils issus de l'analyse REF-113). */
    public ConversationHistory() {
        this(120_000, 60_000, 4);
    }

    /** Compactage à seuils paramétrés (tests ; produit : valeurs par défaut). */
    public ConversationHistory(int triggerChars, int targetChars, int keepRecentToolResults) {
        this.triggerChars = triggerChars;
        this.targetChars = targetChars;
        this.keepRecentToolResults = keepRecentToolResults;
    }

    /** Ajoute un message (utilisateur, assistant, système ou résultat d'outil). */
    public synchronized void add(Entry entry) {
        entries.add(entry);
    }

    /** Historique complet stocké (le transcript utilisateur). */
    public synchronized List<Entry> full() {
        return List.copyOf(entries);
    }

    /**
     * Historique compacté : rien sous le seuil de déclenchement ; au-delà, une
     * passe élimine les résultats d'outils anciens (permanemment) jusqu'à
     * repasser sous la cible.
     */
    public synchronized List<Entry> compacted() {
        int size = totalSize();
        if (size <= triggerChars) {
            return List.copyOf(entries);
        }
        // Indices des résultats d'outils, du plus ancien éliminable au plus récent.
        List<Integer> toolIndexes = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            if ("tool".equals(entries.get(i).role())) {
                toolIndexes.add(i);
            }
        }
        List<Entry> view = new ArrayList<>(entries);
        int recentUntouchable = toolIndexes.size() - keepRecentToolResults;
        for (int r = 0; r < recentUntouchable && size > targetChars; r++) {
            int index = toolIndexes.get(r);
            Entry entry = entries.get(index);
            if (entry.toolCallId() == null || elidedCallIds.containsKey(entry.toolCallId())) {
                continue; // déjà éliminé : la décision est permanente
            }
            size -= entry.size();
            elidedCallIds.put(entry.toolCallId(), Boolean.TRUE);
            view.set(index, entry.elided());
        }
        return List.copyOf(view);
    }

    private int totalSize() {
        return entries.stream().mapToInt(Entry::size).sum();
    }

    /** Réinitialise (nouvelle conversation : les éliminations oubliées). */
    public synchronized void reset() {
        entries.clear();
        elidedCallIds.clear();
    }
}
