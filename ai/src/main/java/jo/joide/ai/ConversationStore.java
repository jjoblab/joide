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

        // ---- Sérialisation (schéma stable,_lot 0) ---------------------------

        /** Écrit le JSON d'une conversation (échappements complets). */
        static String writeJson(Conversation conversation) {
            StringBuilder json = new StringBuilder(128);
            json.append("{\"id\":").append(MiniJson.quote(conversation.id()));
            json.append(",\"title\":").append(MiniJson.quote(conversation.title()));
            json.append(",\"created\":").append(conversation.createdAt());
            json.append(",\"updated\":").append(conversation.updatedAt());
            json.append(",\"messages\":[");
            for (int i = 0; i < conversation.messages().size(); i++) {
                if (i > 0) {
                    json.append(',');
                }
                json.append("{\"role\":")
                        .append(MiniJson.quote(conversation.messages().get(i).role()))
                        .append(",\"content\":")
                        .append(MiniJson.quote(conversation.messages().get(i).content()))
                        .append('}');
            }
            json.append("]}");
            return json.toString();
        }

        /** Lit une conversation depuis son JSON ; exception lisible si corrompu. */
        static Conversation readJson(String json) throws IOException {
            Object parsed = MiniJson.parse(json);
            if (!(parsed instanceof java.util.Map<?, ?> root)) {
                throw new IOException("JSON de conversation invalide (racine non objet)");
            }
            Object id = root.get("id");
            Object title = root.get("title");
            Object created = root.get("created");
            Object updated = root.get("updated");
            Object messages = root.get("messages");
            if (!(id instanceof String) || !(messages instanceof List<?>)) {
                throw new IOException("JSON de conversation invalide (champs manquants)");
            }
            List<Conversation.Message> parsedMessages = new ArrayList<>();
            for (Object item : (List<?>) messages) {
                if (item instanceof java.util.Map<?, ?> message
                        && message.get("role") instanceof String role
                        && message.get("content") instanceof String content) {
                    parsedMessages.add(new Conversation.Message(role, content));
                }
            }
            return new Conversation((String) id, title instanceof String value ? value : "",
                    created instanceof Number number ? number.longValue() : 0L,
                    updated instanceof Number number ? number.longValue() : 0L,
                    parsedMessages);
        }
    }

    /**
     * Mini lecteur/écrivain JSON du Lot 0 (objets, tableaux, chaînes avec
     * échappements complets dont les points de code « backslash-u »,
     * nombres, booléens, null). Le Lot 1 livrera le codec complet du module
     * (erreurs typées, positions) ; celui-ci couvre exactement le schéma des
     * conversations, en Java 8.
     */
    final class MiniJson {

        private MiniJson() {}

        /** Échappement JSON d'une valeur de chaîne. */
        static String quote(String value) {
            StringBuilder json = new StringBuilder("\"");
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                switch (c) {
                    case '"' -> json.append("\\\"");
                    case '\\' -> json.append("\\\\");
                    case '\n' -> json.append("\\n");
                    case '\r' -> json.append("\\r");
                    case '\t' -> json.append("\\t");
                    default -> {
                        if (c < 0x20) {
                            json.append(String.format("\\u%04x", (int) c));
                        } else {
                            json.append(c);
                        }
                    }
                }
            }
            return json.append('"').toString();
        }

        /** Analyse un document JSON (Map/List/String/Number/Boolean/null). */
        static Object parse(String json) throws IOException {
            Reader reader = new Reader(json);
            Object value = reader.readValue();
            reader.skipSpaces();
            if (!reader.atEnd()) {
                throw reader.error("caractère en trop après la valeur");
            }
            return value;
        }

        /** Lecteur à curseur (messages d'erreur avec position). */
        private static final class Reader {

            private final String text;
            private int at;

            Reader(String text) {
                this.text = text;
            }

            boolean atEnd() {
                return at >= text.length();
            }

            Object readValue() throws IOException {
                skipSpaces();
                if (atEnd()) {
                    throw error("valeur attendue");
                }
                char c = text.charAt(at);
                switch (c) {
                    case '{' -> {
                        return readObject();
                    }
                    case '[' -> {
                        return readArray();
                    }
                    case '"' -> {
                        return readString();
                    }
                    case 't' -> {
                        expect("true");
                        return Boolean.TRUE;
                    }
                    case 'f' -> {
                        expect("false");
                        return Boolean.FALSE;
                    }
                    case 'n' -> {
                        expect("null");
                        return null;
                    }
                    default -> {
                        return readNumber();
                    }
                }
            }

            private java.util.Map<String, Object> readObject() throws IOException {
                at++; // '{'
                java.util.Map<String, Object> object = new java.util.LinkedHashMap<>();
                skipSpaces();
                if (!atEnd() && text.charAt(at) == '}') {
                    at++;
                    return object;
                }
                while (true) {
                    skipSpaces();
                    if (atEnd() || text.charAt(at) != '"') {
                        throw error("clé de chaîne attendue");
                    }
                    String key = readString();
                    skipSpaces();
                    if (atEnd() || text.charAt(at) != ':') {
                        throw error("deux-points attendu");
                    }
                    at++;
                    object.put(key, readValue());
                    skipSpaces();
                    if (atEnd()) {
                        throw error("objet non fermé");
                    }
                    char next = text.charAt(at);
                    if (next == ',') {
                        at++;
                    } else if (next == '}') {
                        at++;
                        return object;
                    } else {
                        throw error("virgule ou accolade fermante attendue");
                    }
                }
            }

            private List<Object> readArray() throws IOException {
                at++; // '['
                List<Object> array = new ArrayList<>();
                skipSpaces();
                if (!atEnd() && text.charAt(at) == ']') {
                    at++;
                    return array;
                }
                while (true) {
                    array.add(readValue());
                    skipSpaces();
                    if (atEnd()) {
                        throw error("tableau non fermé");
                    }
                    char next = text.charAt(at);
                    if (next == ',') {
                        at++;
                    } else if (next == ']') {
                        at++;
                        return array;
                    } else {
                        throw error("virgule ou crochet fermant attendu");
                    }
                }
            }

            private String readString() throws IOException {
                at++; // '"'
                StringBuilder value = new StringBuilder();
                while (true) {
                    if (atEnd()) {
                        throw error("chaîne non fermée");
                    }
                    char c = text.charAt(at++);
                    if (c == '"') {
                        return value.toString();
                    }
                    if (c != '\\') {
                        value.append(c);
                        continue;
                    }
                    if (atEnd()) {
                        throw error("échappement incomplet");
                    }
                    char escape = text.charAt(at++);
                    switch (escape) {
                        case '"' -> value.append('"');
                        case '\\' -> value.append('\\');
                        case '/' -> value.append('/');
                        case 'b' -> value.append('\b');
                        case 'f' -> value.append('\f');
                        case 'n' -> value.append('\n');
                        case 'r' -> value.append('\r');
                        case 't' -> value.append('\t');
                        case 'u' -> {
                            if (at + 4 > text.length()) {
                                throw error("\\uXXXX incomplet");
                            }
                            try {
                                value.append((char) Integer.parseInt(
                                        text.substring(at, at + 4), 16));
                            } catch (NumberFormatException e) {
                                throw error("\\uXXXX invalide");
                            }
                            at += 4;
                        }
                        default -> throw error("échappement inconnu \\" + escape);
                    }
                }
            }

            private Number readNumber() throws IOException {
                int start = at;
                if (!atEnd() && (text.charAt(at) == '-' || text.charAt(at) == '+')) {
                    at++;
                }
                boolean decimal = false;
                while (!atEnd()) {
                    char c = text.charAt(at);
                    if (c >= '0' && c <= '9') {
                        at++;
                    } else if (c == '.' || c == 'e' || c == 'E' || c == '-'
                            || c == '+') {
                        decimal = decimal || c == '.' || c == 'e' || c == 'E';
                        at++;
                    } else {
                        break;
                    }
                }
                if (at == start) {
                    throw error("valeur invalide");
                }
                String literal = text.substring(start, at);
                try {
                    return decimal ? (Number) Double.parseDouble(literal)
                            : (Number) Long.parseLong(literal);
                } catch (NumberFormatException e) {
                    throw error("nombre invalide : " + literal);
                }
            }

            private void expect(String literal) throws IOException {
                if (!text.startsWith(literal, at)) {
                    throw error(literal + " attendu");
                }
                at += literal.length();
            }

            private void skipSpaces() {
                while (!atEnd()) {
                    char c = text.charAt(at);
                    if (c == ' ' || c == '\n' || c == '\r' || c == '\t') {
                        at++;
                    } else {
                        break;
                    }
                }
            }

            private IOException error(String message) {
                return new IOException("JSON invalide à " + at + " : " + message);
            }
        }
    }
}
