package jo.joide.ai.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Codec JSON interne du module ia (Lot 1) : l'analyse par {@code indexOf} a
 * coûté des pertes de caractères (guillemets échappés, sauts de ligne,
 * points de code Unicode) — tout passe désormais par ce parseur complet.
 * Java pur, aucune dépendance (la règle du dépôt interdit une bibliothèque
 * JSON dans le module), aucune API au-delà de Java 8 (le module tourne sur
 * l'appareil en API 28 sans désucrage).
 *
 * <p>Valeurs : {@code Map<String,Object>} (ordre préservé), {@code List},
 * {@code String}, {@code Long} (entiers), {@code Double} (décimaux),
 * {@code Boolean}, {@code null}. Les erreurs portent leur position.</p>
 */
public final class Json {

    private Json() {}

    // ---- Écriture ----------------------------------------------------------

    /** Sérialise une valeur (Map/List/String/Number/Boolean/null). */
    public static String write(Object value) {
        StringBuilder out = new StringBuilder(64);
        writeValue(value, out);
        return out.toString();
    }

    private static void writeValue(Object value, StringBuilder out) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof String text) {
            writeString(text, out);
        } else if (value instanceof Boolean || value instanceof Long
                || value instanceof Integer) {
            out.append(value);
        } else if (value instanceof Double || value instanceof Float) {
            double d = ((Number) value).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                throw new IllegalArgumentException("Nombre non sérialisable : " + d);
            }
            out.append(d == Math.floor(d) && !dIsHuge(d) ? String.valueOf((long) d) : String.valueOf(d));
        } else if (value instanceof Map<?, ?> map) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                writeString(String.valueOf(entry.getKey()), out);
                out.append(':');
                writeValue(entry.getValue(), out);
            }
            out.append('}');
        } else if (value instanceof Iterable<?> list) {
            out.append('[');
            boolean first = true;
            for (Object item : list) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                writeValue(item, out);
            }
            out.append(']');
        } else {
            throw new IllegalArgumentException("Type non sérialisable : " + value.getClass());
        }
    }

    /** Le double dépasse la précision exacte d'un long : garder la forme décimale. */
    private static boolean dIsHuge(double d) {
        return Math.abs(d) >= 9.007199254740992E15;
    }

    /** Échappement complet d'une chaîne (guillemet, antislash, contrôles). */
    static void writeString(String value, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
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

    // ---- Lecture -----------------------------------------------------------

    /** Analyse un document JSON complet ; erreur lisible avec position. */
    public static Object parse(String json) throws JsonException {
        Reader reader = new Reader(json);
        Object value = reader.readValue();
        reader.skipSpaces();
        if (!reader.atEnd()) {
            throw reader.error("caractère en trop après la valeur");
        }
        return value;
    }

    /** Analyse un objet JSON ; erreur claire si la racine n'est pas un objet. */
    public static Map<String, Object> parseObject(String json) throws JsonException {
        Object parsed = parse(json);
        if (!(parsed instanceof Map<?, ?> map)) {
            throw new JsonException("Racine JSON non objet", 0);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> object = (Map<String, Object>) map;
        return object;
    }

    /** Lecteur à curseur (position dans les messages d'erreur). */
    static final class Reader {

        private final String text;
        private int at;

        Reader(String text) {
            this.text = text;
        }

        boolean atEnd() {
            return at >= text.length();
        }

        Object readValue() throws JsonException {
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

        private Map<String, Object> readObject() throws JsonException {
            at++; // '{'
            Map<String, Object> object = new LinkedHashMap<>();
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

        private List<Object> readArray() throws JsonException {
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

        String readString() throws JsonException {
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
                            throw error("point de code Unicode incomplet");
                        }
                        try {
                            value.append((char) Integer.parseInt(
                                    text.substring(at, at + 4), 16));
                        } catch (NumberFormatException e) {
                            throw error("point de code Unicode invalide");
                        }
                        at += 4;
                    }
                    default -> throw error("échappement inconnu \\" + escape);
                }
            }
        }

        private Number readNumber() throws JsonException {
            int start = at;
            if (!atEnd() && (text.charAt(at) == '-' || text.charAt(at) == '+')) {
                at++;
            }
            boolean decimal = false;
            while (!atEnd()) {
                char c = text.charAt(at);
                if (c >= '0' && c <= '9') {
                    at++;
                } else if (c == '.' || c == 'e' || c == 'E' || c == '-' || c == '+') {
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

        private void expect(String literal) throws JsonException {
            if (!text.startsWith(literal, at)) {
                throw error(literal + " attendu");
            }
            at += literal.length();
        }

        void skipSpaces() {
            while (!atEnd()) {
                char c = text.charAt(at);
                if (c == ' ' || c == '\n' || c == '\r' || c == '\t') {
                    at++;
                } else {
                    break;
                }
            }
        }

        JsonException error(String message) {
            return new JsonException(message, at);
        }
    }
}
