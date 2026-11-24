package jo.joide.diag;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Codec JSON minimal du module (ADR-021, même règle que ForgeJson dans
 * core-model : <b>aucune bibliothèque JSON par module</b>).
 *
 * <p>Deux moitiés séparées :</p>
 * <ul>
 *   <li>{@link #write(Object)} : écrit un arbre de valeurs
 *       ({@code null}, {@link String}, {@link Boolean}, {@link Number},
 *       {@link Map} ordonnée, {@link List}) en JSON strict et compact —
 *       utilisé uniquement pour nos propres documents (rapports, entrées de
 *       journal), jamais pour un JSON externe ;</li>
 *   <li>{@link #parse(String)} : lecture <b>défensive</b> de n'importe quel
 *       JSON valide — profondeur bornée, nombres entiers ou décimaux, toutes
 *       les échappes de chaîne — toute malformation lève
 *       {@link JsonException} que l'appelant traduit en valeur absente,
 *       jamais en exception visible de l'application.</li>
 * </ul>
 *
 * <p>Les nombres s'écrivent et se relisent en {@link Long} quand la syntaxe
 * est entière, en {@link Double} sinon. Les caractères hors BMP et les
 * échappes {@code \\uXXXX} passent tels quels (paires de substitution
 * conservées).</p>
 */
public final class Json {

    /** Profondeur maximale d'imbrication lue (un rapport réel n'excède pas 6). */
    private static final int MAX_DEPTH = 64;

    /** Erreur du codec — toujours attrapée par les appelants du module. */
    public static final class JsonException extends RuntimeException {
        JsonException(String message) {
            super(message);
        }
    }

    private Json() {
        // Classe utilitaire — jamais instanciée.
    }

    // ---- Écriture -------------------------------------------------------------

    /**
     * Écrit un arbre de valeurs en JSON strict et compact.
     *
     * @param value arbre : {@code null}, {@link String}, {@link Boolean},
     *        {@link Number}, {@link Map} (clés String), {@link List}
     * @return le document JSON sur une seule ligne
     */
    public static String write(Object value) {
        StringBuilder out = new StringBuilder(128);
        writeValue(out, value);
        return out.toString();
    }

    private static void writeValue(StringBuilder out, Object value) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof String text) {
            writeString(out, text);
        } else if (value instanceof Boolean bool) {
            out.append(bool ? "true" : "false");
        } else if (value instanceof Double d && (d.isNaN() || d.isInfinite())) {
            out.append('0');
        } else if (value instanceof Number number) {
            out.append(number.toString());
        } else if (value instanceof Map<?, ?> map) {
            writeObject(out, map);
        } else if (value instanceof List<?> list) {
            writeArray(out, list);
        } else {
            // Inconnu : rendu en chaîne — jamais d'exception à l'écriture.
            writeString(out, String.valueOf(value));
        }
    }

    private static void writeObject(StringBuilder out, Map<?, ?> map) {
        out.append('{');
        boolean first = true;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!first) {
                out.append(',');
            }
            first = false;
            writeString(out, String.valueOf(entry.getKey()));
            out.append(':');
            writeValue(out, entry.getValue());
        }
        out.append('}');
    }

    private static void writeArray(StringBuilder out, List<?> list) {
        out.append('[');
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            writeValue(out, list.get(i));
        }
        out.append(']');
    }

    private static void writeString(StringBuilder out, String text) {
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    // ---- Lecture défensive ------------------------------------------------------

    /**
     * Lit un document JSON en arbre de valeurs.
     *
     * @param document le texte à analyser
     * @return {@code null}, {@link String}, {@link Boolean}, {@link Long},
     *         {@link Double}, {@link LinkedHashMap}, {@link ArrayList}
     * @throws JsonException document malformé (l'appelant traduit, jamais
     *         d'exception visible de l'application)
     */
    public static Object parse(String document) {
        if (document == null) {
            throw new JsonException("document null");
        }
        Reader reader = new Reader(document);
        reader.skipWhitespace();
        Object value = reader.readValue(0);
        reader.skipWhitespace();
        if (!reader.atEnd()) {
            throw new JsonException("contenu après la racine");
        }
        return value;
    }

    /** Curseur de lecture — exception en fin de texte, jamais de débordement. */
    private static final class Reader {
        private final String text;
        private int position;

        Reader(String text) {
            this.text = text;
        }

        boolean atEnd() {
            return position >= text.length();
        }

        void skipWhitespace() {
            while (position < text.length()) {
                char c = text.charAt(position);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    position++;
                } else {
                    break;
                }
            }
        }

        private char peek() {
            if (atEnd()) {
                throw new JsonException("fin de texte inattendue");
            }
            return text.charAt(position);
        }

        private char next() {
            char c = peek();
            position++;
            return c;
        }

        Object readValue(int depth) {
            if (depth > MAX_DEPTH) {
                throw new JsonException("profondeur excessive");
            }
            char c = peek();
            return switch (c) {
                case '{' -> readObject(depth);
                case '[' -> readArray(depth);
                case '"' -> readString();
                case 't' -> readKeyword("true", Boolean.TRUE);
                case 'f' -> readKeyword("false", Boolean.FALSE);
                case 'n' -> readKeyword("null", null);
                default -> readNumber();
            };
        }

        private Map<String, Object> readObject(int depth) {
            next(); // '{'
            Map<String, Object> map = new LinkedHashMap<>();
            skipWhitespace();
            if (peek() == '}') {
                position++;
                return map;
            }
            while (true) {
                skipWhitespace();
                if (peek() != '"') {
                    throw new JsonException("clé attendue");
                }
                String key = readString();
                skipWhitespace();
                if (next() != ':') {
                    throw new JsonException("deux-points attendu");
                }
                skipWhitespace();
                map.put(key, readValue(depth + 1));
                skipWhitespace();
                char c = next();
                if (c == '}') {
                    return map;
                }
                if (c != ',') {
                    throw new JsonException("virgule attendue");
                }
            }
        }

        private List<Object> readArray(int depth) {
            next(); // '['
            List<Object> list = new ArrayList<>();
            skipWhitespace();
            if (peek() == ']') {
                position++;
                return list;
            }
            while (true) {
                skipWhitespace();
                list.add(readValue(depth + 1));
                skipWhitespace();
                char c = next();
                if (c == ']') {
                    return list;
                }
                if (c != ',') {
                    throw new JsonException("virgule attendue");
                }
            }
        }

        private String readString() {
            next(); // '"'
            StringBuilder out = new StringBuilder(32);
            while (true) {
                char c = next();
                if (c == '"') {
                    return out.toString();
                }
                if (c == '\\') {
                    char escape = next();
                    switch (escape) {
                        case '"' -> out.append('"');
                        case '\\' -> out.append('\\');
                        case '/' -> out.append('/');
                        case 'n' -> out.append('\n');
                        case 'r' -> out.append('\r');
                        case 't' -> out.append('\t');
                        case 'b' -> out.append('\b');
                        case 'f' -> out.append('\f');
                        case 'u' -> out.append(readUnicodeEscape());
                        default -> throw new JsonException("échappe inconnue \\" + escape);
                    }
                } else if (c < 0x20) {
                    throw new JsonException("caractère de contrôle dans une chaîne");
                } else {
                    out.append(c);
                }
            }
        }

        private char readUnicodeEscape() {
            if (position + 4 > text.length()) {
                throw new JsonException("\\u tronqué");
            }
            int code = 0;
            for (int i = 0; i < 4; i++) {
                char c = next();
                int digit = Character.digit(c, 16);
                if (digit < 0) {
                    throw new JsonException("chiffre hexadécimal attendu");
                }
                code = code * 16 + digit;
            }
            return (char) code;
        }

        private Object readKeyword(String keyword, Object value) {
            if (position + keyword.length() > text.length()
                    || !text.startsWith(keyword, position)) {
                throw new JsonException("mot-clé attendu : " + keyword);
            }
            position += keyword.length();
            return value;
        }

        private Object readNumber() {
            int start = position;
            if (peek() == '-') {
                position++;
            }
            boolean decimal = false;
            while (!atEnd()) {
                char c = text.charAt(position);
                if (c >= '0' && c <= '9') {
                    position++;
                } else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                    decimal = true;
                    position++;
                } else {
                    break;
                }
            }
            String number = text.substring(start, position);
            if (number.isEmpty() || number.equals("-")) {
                throw new JsonException("nombre attendu");
            }
            try {
                return decimal ? (Object) Double.parseDouble(number) : (Object) Long.parseLong(number);
            } catch (NumberFormatException malformed) {
                throw new JsonException("nombre malformé : " + number);
            }
        }
    }
}
