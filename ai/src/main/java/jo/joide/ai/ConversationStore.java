package jo.joide.ai;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Stockage des conversations (Lot 4) : un fichier JSON par conversation —
 * **double contenu** (transcript vu + historique exact vu par le modèle),
 * fournisseur/modèle/effort épinglés, usage cumulé, version de schéma.
 * Écriture **atomique** (temporaire + renommage), corrompus **ignorés avec
 * avertissement** ; les fichiers du schéma v1 (Lot 0, transcript seul) se
 * migrent à la lecture. Pur Java 8 (API 28 sans désucrage).
 *
 * <p>Emplacements côté hôte : assistant {@code filesDir/assistant/}, agent
 * {@code filesDir/agent/<empreinte-du-projet>/} (empreinte stable du chemin,
 * voir {@link #fingerprint(String)}).</p>
 */
public interface ConversationStore {

    /** Résumé d'une conversation pour la liste (titre, date, compte). */
    record Summary(String id, String title, long updatedAt, int messageCount) {}

    /** Conversations connues, de la plus récemment modifiée à la plus ancienne. */
    List<Summary> list() throws IOException;

    /** Conversation complète ; null si absente ou illisible. */
    Conversation load(String id) throws IOException;

    /** Écrit une conversation (atomique : temporaire + renommage). */
    void save(Conversation conversation) throws IOException;

    /** Supprime le fichier d'une conversation ; vrai s'il existait. */
    boolean delete(String id) throws IOException;

    /** Identifiant stable d'une nouvelle conversation (horodatage + aléa, hexa). */
    static String newId() {
        return Long.toHexString(System.currentTimeMillis()) + "-"
                + Integer.toHexString(RANDOM.nextInt());
    }

    /** Aléa des identifiants (jamais seedé : les identifiants ne se devinent pas). */
    SecureRandom RANDOM = new SecureRandom();

    /**
     * Empreinte stable d'un chemin de projet (noms de dossier sûrs) : hachage
     * SHA-256 du chemin absolu, en hexadécimal borné.
     */
    static String fingerprint(String projectPath) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(
                    (projectPath == null ? "" : projectPath)
                            .getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < Math.min(12, hash.length); i++) {
                hex.append(String.format("%02x", hash[i]));
            }
            return hex.toString();
        } catch (Exception e) {
            // SHA-256 existe partout ; repli déterministe honnête.
            return Integer.toHexString(String.valueOf(projectPath).hashCode());
        }
    }

    /**
     * Implémentation fichiers JSON du port : un fichier {@code <id>.json}
     * par conversation. L'identifiant est validé (hexa et tiret seulement)
     * avant toute résolution de chemin — la traversée de dossier est
     * impossible par construction.
     */
    final class JsonFiles implements ConversationStore {

        private static final String ID_PATTERN = "[0-9a-fA-F-]{4,80}";

        private final Path directory;
        private final Consumer<String> onCorrupt;

        public JsonFiles(Path directory) {
            this(directory, null);
        }

        /**
         * @param directory dossier des conversations (créé à la première écriture).
         * @param onCorrupt averti de chaque fichier ignoré (journal honnête) ;
         *                   peut être null.
         */
        public JsonFiles(Path directory, Consumer<String> onCorrupt) {
            this.directory = directory;
            this.onCorrupt = onCorrupt;
        }

        @Override
        public List<Summary> list() throws IOException {
            List<Summary> summaries = new ArrayList<>();
            if (!Files.isDirectory(directory)) {
                return summaries;
            }
            try (DirectoryStream<Path> stream =
                    Files.newDirectoryStream(directory, "*.json")) {
                for (Path file : stream) {
                    Conversation conversation = readOrNull(file);
                    if (conversation != null) {
                        summaries.add(new Summary(conversation.id(),
                                conversation.title(), conversation.updatedAt(),
                                conversation.messages().size()));
                    }
                }
            }
            summaries.sort(Comparator.comparingLong(Summary::updatedAt).reversed());
            return Collections.unmodifiableList(summaries);
        }

        @Override
        public Conversation load(String id) throws IOException {
            return readOrNull(fileOf(id));
        }

        @Override
        public void save(Conversation conversation) throws IOException {
            Files.createDirectories(directory);
            Path target = fileOf(conversation.id());
            Path temp = directory.resolve(conversation.id() + ".json.tmp");
            Files.write(temp, writeJson(conversation).getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                // Système de fichiers sans renommage atomique : le remplacement
                // reste sûr (le temporaire est déjà intégralement écrit).
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }

        @Override
        public boolean delete(String id) throws IOException {
            return Files.deleteIfExists(fileOf(id));
        }

        /** Chemin du fichier d'une conversation (identifiant validé). */
        private Path fileOf(String id) throws IOException {
            if (id == null || !id.matches(ID_PATTERN)) {
                throw new IOException("Identifiant de conversation invalide : " + id);
            }
            return directory.resolve(id + ".json");
        }

        /** Lecture tolérante : fichier corrompu → null + avertissement. */
        private Conversation readOrNull(Path file) {
            String name = file.getFileName().toString();
            if (!Files.isRegularFile(file)) {
                return null;
            }
            try {
                String json = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
                return readJson(json);
            } catch (Exception e) {
                if (onCorrupt != null) {
                    onCorrupt.accept(name + " : " + (e.getMessage() != null
                            ? e.getMessage() : String.valueOf(e)));
                }
                return null;
            }
        }

        // ---- Sérialisation (schéma v2, Lot 4) ---------------------------------

        /** Écrit le JSON d'une conversation (échappements complets par le codec). */
        static String writeJson(Conversation conversation) {
            Map<String, Object> root = new java.util.LinkedHashMap<>();
            root.put("schema", Conversation.SCHEMA_VERSION);
            root.put("id", conversation.id());
            root.put("title", conversation.title());
            root.put("created", conversation.createdAt());
            root.put("updated", conversation.updatedAt());
            root.put("provider", conversation.providerId());
            root.put("model", conversation.model());
            root.put("effort", conversation.effort());
            Usage usage = conversation.usage();
            Map<String, Object> usageJson = new java.util.LinkedHashMap<>();
            usageJson.put("prompt", usage.promptTokens());
            usageJson.put("completion", usage.completionTokens());
            usageJson.put("reasoning", usage.reasoningTokens());
            usageJson.put("cached", usage.cachedPromptTokens());
            root.put("usage", usageJson);
            List<Object> messages = new ArrayList<>();
            for (Conversation.Message message : conversation.messages()) {
                Map<String, Object> item = new java.util.LinkedHashMap<>();
                item.put("role", message.role());
                item.put("content", message.content());
                messages.add(item);
            }
            root.put("messages", messages);
            List<Object> history = new ArrayList<>();
            for (Conversation.HistoryEntry entry : conversation.history()) {
                Map<String, Object> item = new java.util.LinkedHashMap<>();
                item.put("role", entry.role());
                item.put("content", entry.content());
                item.put("toolCallId", entry.toolCallId());
                if (entry.toolCalls() != null && !entry.toolCalls().isEmpty()) {
                    List<Object> calls = new ArrayList<>();
                    for (Conversation.ToolCallData call : entry.toolCalls()) {
                        Map<String, Object> callJson = new java.util.LinkedHashMap<>();
                        callJson.put("id", call.id());
                        callJson.put("name", call.name());
                        callJson.put("arguments", call.argumentsJson());
                        calls.add(callJson);
                    }
                    item.put("toolCalls", calls);
                }
                history.add(item);
            }
            root.put("history", history);
            return jo.joide.ai.json.Json.write(root);
        }

        /**
         * Lit une conversation depuis son JSON. Les fichiers v1 (Lot 0 :
         * transcript seul, sans champ schema) migrent : le transcript devient
         * aussi l'historique de départ (roles user/assistant rejouables).
         */
        static Conversation readJson(String json) throws IOException {
            Map<String, Object> root;
            try {
                root = jo.joide.ai.json.Json.parseObject(json);
            } catch (jo.joide.ai.json.JsonException e) {
                throw new IOException("JSON de conversation invalide : " + e.getMessage());
            }
            Object id = root.get("id");
            if (!(id instanceof String)) {
                throw new IOException("JSON de conversation invalide (id manquant)");
            }
            boolean v1 = !(root.get("schema") instanceof Number n)
                    || n.intValue() < 2;
            List<Conversation.Message> messages = new ArrayList<>();
            if (root.get("messages") instanceof List<?> items) {
                for (Object item : items) {
                    if (item instanceof Map<?, ?> message
                            && message.get("role") instanceof String role
                            && message.get("content") instanceof String content) {
                        messages.add(new Conversation.Message(role, content));
                    }
                }
            }
            List<Conversation.HistoryEntry> history = new ArrayList<>();
            if (root.get("history") instanceof List<?> entries) {
                for (Object item : entries) {
                    Conversation.HistoryEntry entry = readHistoryEntry(item);
                    if (entry != null) {
                        history.add(entry);
                    }
                }
            } else if (v1) {
                // Migration v1 → v2 : le transcript devient l'historique.
                for (Conversation.Message message : messages) {
                    history.add(Conversation.HistoryEntry.plain(
                            message.role(), message.content()));
                }
            }
            Usage usage = Usage.unknown();
            if (root.get("usage") instanceof Map<?, ?> usageJson) {
                usage = new Usage(longOf(usageJson.get("prompt")),
                        longOf(usageJson.get("completion")),
                        longOf(usageJson.get("reasoning")),
                        longOf(usageJson.get("cached")));
            }
            return new Conversation((String) id,
                    root.get("title") instanceof String title ? title : "",
                    longOf(root.get("created")),
                    longOf(root.get("updated")),
                    root.get("provider") instanceof String provider ? provider : null,
                    root.get("model") instanceof String model ? model : null,
                    root.get("effort") instanceof String effort ? effort : null,
                    usage, messages, history);
        }

        /** Entrée d'historique depuis sa forme JSON. */
        private static Conversation.HistoryEntry readHistoryEntry(Object item) {
            if (!(item instanceof Map<?, ?> entry)
                    || !(entry.get("role") instanceof String role)) {
                return null;
            }
            String content = entry.get("content") instanceof String content1 ? content1 : "";
            String toolCallId = entry.get("toolCallId") instanceof String callId ? callId : null;
            List<Conversation.ToolCallData> calls = null;
            if (entry.get("toolCalls") instanceof List<?> rawCalls) {
                calls = new ArrayList<>();
                for (Object rawCall : rawCalls) {
                    if (rawCall instanceof Map<?, ?> call
                            && call.get("id") instanceof String callId2
                            && call.get("name") instanceof String name
                            && call.get("arguments") instanceof String arguments) {
                        calls.add(new Conversation.ToolCallData(callId2, name, arguments));
                    }
                }
            }
            return new Conversation.HistoryEntry(role, content, calls, toolCallId);
        }

        private static long longOf(Object value) {
            return value instanceof Number n ? n.longValue() : 0L;
        }
    }
}
