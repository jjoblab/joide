package jo.joide.ai;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

/**
 * Stockage des conversations de l'assistant (Lot 0) : un fichier JSON par
 * conversation dans un dossier fourni par l'hôte (coquille Android :
 * {@code getFilesDir()/assistant/}). Écriture **atomique** (fichier
 * temporaire + renommage) pour qu'un arrêt brutal ne corrompe jamais une
 * conversation ; fichiers corrompus **ignorés avec avertissement** (jamais
 * un plantage, jamais une liste vide muette). Pur Java 8 (aucune API 9+ :
 * le module tourne sur l'appareil en API 28 sans désucrage).
 *
 * <p>Le Lot 4 généralisera ce port (double contenu transcript/historique
 * modèle, schéma versionné, migration) ; Lot 0 : le transcript seul, déjà
 * derrière l'interface pour que la coquille ne dépende pas du format.</p>
 */
public interface ConversationStore {

    /** Résumé d'une conversation pour la liste (titre, date, compte de messages). */
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

        // ---- Sérialisation (schéma stable, Lot 0 ; codec Json du Lot 1) -----

        /** Écrit le JSON d'une conversation (échappements complets par le codec). */
        static String writeJson(Conversation conversation) {
            java.util.Map<String, Object> root = new java.util.LinkedHashMap<>();
            root.put("id", conversation.id());
            root.put("title", conversation.title());
            root.put("created", conversation.createdAt());
            root.put("updated", conversation.updatedAt());
            List<Object> messages = new ArrayList<>();
            for (Conversation.Message message : conversation.messages()) {
                java.util.Map<String, Object> item = new java.util.LinkedHashMap<>();
                item.put("role", message.role());
                item.put("content", message.content());
                messages.add(item);
            }
            root.put("messages", messages);
            return jo.joide.ai.json.Json.write(root);
        }

        /** Lit une conversation depuis son JSON ; exception lisible si corrompu. */
        static Conversation readJson(String json) throws IOException {
            java.util.Map<String, Object> root;
            try {
                root = jo.joide.ai.json.Json.parseObject(json);
            } catch (jo.joide.ai.json.JsonException e) {
                throw new IOException("JSON de conversation invalide : " + e.getMessage());
            }
            Object id = root.get("id");
            if (!(id instanceof String)) {
                throw new IOException("JSON de conversation invalide (id manquant)");
            }
            List<Conversation.Message> parsedMessages = new ArrayList<>();
            Object messages = root.get("messages");
            if (messages instanceof List<?> items) {
                for (Object item : items) {
                    if (item instanceof java.util.Map<?, ?> message
                            && message.get("role") instanceof String role
                            && message.get("content") instanceof String content) {
                        parsedMessages.add(new Conversation.Message(role, content));
                    }
                }
            }
            return new Conversation((String) id,
                    root.get("title") instanceof String title ? title : "",
                    root.get("created") instanceof Number created ? created.longValue() : 0L,
                    root.get("updated") instanceof Number updated ? updated.longValue() : 0L,
                    parsedMessages);
        }
    }
}
