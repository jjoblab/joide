package jo.joide.ai;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Point de contrôle de tour (Lot 3) : instantané des fichiers touchés par un
 * tour de l'agent ; {@link #undo()} restaure créations et modifications et
 * **avoue honnêtement** ce qui est irréversible (suppressions). La Javadoc
 * d'{@code AgentLoop} promettait cette annulation depuis le L7 — elle
 * existait enfin. Pur (chemins passés par le port, testable avec le fake).
 */
public final class TurnCheckpoint {

    private final AgentWorkspace workspace;
    private final Map<String, String> before = new LinkedHashMap<>();
    private final List<String> created = new ArrayList<>();
    private final List<String> deleted = new ArrayList<>();
    private boolean undone;

    public TurnCheckpoint(AgentWorkspace workspace) {
        this.workspace = workspace;
    }

    /** À appeler AVANT toute mutation : retient l'état avant (fichier existant ou non). */
    public void beforeChange(String relativePath) {
        if (undone || before.containsKey(relativePath)) {
            return;
        }
        try {
            before.put(relativePath, workspace.fileBefore(relativePath));
        } catch (Exception e) {
            // Lecture impossible : le fichier est considéré absent (création).
            before.put(relativePath, "");
        }
    }

    /** À appeler APRÈS la mutation : note la nature du changement. */
    public void afterChange(String relativePath, boolean deletedFile) {
        if (undone) {
            return;
        }
        if (deletedFile) {
            deleted.add(relativePath);
            return;
        }
        String was = before.get(relativePath);
        if (was == null || was.isEmpty()) {
            created.add(relativePath);
        }
    }

    /**
     * Annule le tour : restaure les fichiers modifiés à leur état avant,
     * supprime les fichiers créés, **avoue** les suppressions (irréversibles
     * — elles ne sont pas restaurables, la note le dit).
     *
     * @return la note honnête à envoyer au modèle (il doit relire les fichiers).
     */
    public String undo() {
        if (undone) {
            return "Le tour était déjà annulé.";
        }
        undone = true;
        List<String> restored = new ArrayList<>();
        List<String> removedCreations = new ArrayList<>();
        for (Map.Entry<String, String> entry : before.entrySet()) {
            String path = entry.getKey();
            try {
                if (entry.getValue().isEmpty()) {
                    if (workspace.deleteFile(path)) {
                        removedCreations.add(path);
                    }
                } else {
                    workspace.writeFile(path, entry.getValue());
                    restored.add(path);
                }
            } catch (IOException e) {
                // La restauration échouée se dit, jamais masquée.
                restored.add(path + " (échec de restauration : "
                        + (e.getMessage() != null ? e.getMessage() : e) + ")");
            }
        }
        StringBuilder note = new StringBuilder("Le tour a été annulé par l'utilisateur.");
        if (!restored.isEmpty() || !removedCreations.isEmpty()) {
            note.append(" Fichiers restaurés à leur état avant : ");
            note.append(String.join(", ", restored)).append(removedCreations.isEmpty()
                    ? "" : (restored.isEmpty() ? "" : " ; ") + "créations supprimées : "
                            + String.join(", ", removedCreations));
        }
        if (!deleted.isEmpty()) {
            note.append(" ATTENTION : les suppressions suivantes sont irréversibles "
                    + "et n'ont PAS pu être annulées : ").append(String.join(", ", deleted));
        }
        note.append(" Relis les fichiers avant de continuer.");
        return note.toString();
    }

    /** Vrai si le tour a touché au moins un fichier. */
    public boolean hasChanges() {
        return !before.isEmpty();
    }

    /** Chemins touchés (transparence, tests). */
    public List<String> touchedPaths() {
        return new ArrayList<>(before.keySet());
    }

    /** Écrit le contenu en UTF-8 sans API Java 11 (API 28 — ADR-009). */
    static void writeUtf8(Path file, String content) throws IOException {
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
    }

    /** Lit le contenu en UTF-8 sans API Java 11 (API 28 — ADR-009). */
    static String readUtf8(Path file) throws IOException {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }
}
