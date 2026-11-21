package jo.joide.ai;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Conversation de l'assistant général (Lot 0 de la séparation Assistant /
 * Agent) : transcript simple des messages échangés (utilisateur / assistant),
 * titre dérivé du premier message, dates réelles en millisecondes. Le Lot 4
 * généralisera ce modèle (double contenu — transcript vu et historique exact
 * vu par le modèle — fournisseur/modèle utilisés, usage, schéma versionné) ;
 * Lot 0 : le transcript suffit à recharger une conversation.
 *
 * <p>Instance non modifiable : {@link #withMessage} rend une copie (les
 * mutations ne touchent jamais une conversation partagée).</p>
 */
public record Conversation(String id, String title, long createdAt, long updatedAt,
        List<Message> messages) {

    /** Un message du transcript (rôle « user » ou « assistant », contenu texte). */
    public record Message(String role, String content) {}

    public Conversation {
        messages = safeCopy(messages);
    }

    /** Copie non modifiable (aucune liste interne exposée mutable). */
    private static List<Message> safeCopy(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(new ArrayList<>(messages));
    }

    /** Conversation neuve (titre vide : posé au premier message). */
    public static Conversation fresh(String id, long now) {
        return new Conversation(id, "", now, now, null);
    }

    /** Ajoute un message et rafraîchit la date de mise à jour (nouvelle instance). */
    public Conversation withMessage(Message message, long now) {
        List<Message> next = new ArrayList<>(messages);
        next.add(message);
        return new Conversation(id, title, createdAt, now, next);
    }

    /** Change le titre (nouvelle instance). */
    public Conversation withTitle(String newTitle, long now) {
        return new Conversation(id, newTitle, createdAt, now, messages);
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
}
