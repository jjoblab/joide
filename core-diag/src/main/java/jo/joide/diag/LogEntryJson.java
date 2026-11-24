package jo.joide.diag;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Mapping JSON d'une {@link LogEntry} (ADR-021) : une entrée = une ligne
 * JSONL, écrite avec {@link Json#write} et relue avec {@link Json#parse} —
 * lecture <b>défensive</b> (toute ligne non conforme rend {@code null},
 * comptée par le stockage, jamais d'exception).
 */
public final class LogEntryJson {

    private static final String KEY_TIMESTAMP = "timestampMillis";
    private static final String KEY_SESSION = "sessionId";
    private static final String KEY_LEVEL = "level";
    private static final String KEY_TAG = "tag";
    private static final String KEY_THREAD = "threadName";
    private static final String KEY_MESSAGE = "message";
    private static final String KEY_EXCEPTION = "exception";
    private static final String KEY_CLASS = "className";
    private static final String KEY_FRAMES = "frames";
    private static final String KEY_CAUSE = "cause";
    private static final String KEY_SUPPRESSED = "suppressed";

    private LogEntryJson() {
        // Classe utilitaire — jamais instanciée.
    }

    /**
     * Écrit l'entrée en une ligne JSON (sans le retour à la ligne final).
     *
     * @param entry entrée déjà expurgée et tronquée par le pipeline
     * @return la ligne JSONL
     */
    public static String write(LogEntry entry) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(KEY_TIMESTAMP, entry.timestampMillis());
        map.put(KEY_SESSION, entry.sessionId());
        map.put(KEY_LEVEL, entry.level().name());
        map.put(KEY_TAG, entry.tag());
        map.put(KEY_THREAD, entry.threadName());
        map.put(KEY_MESSAGE, entry.message());
        if (entry.exception() != null) {
            map.put(KEY_EXCEPTION, writeException(entry.exception()));
        }
        return Json.write(map);
    }

    /**
     * Lit une entrée depuis une ligne JSONL.
     *
     * @param line la ligne lue sur disque
     * @return l'entrée, ou {@code null} si la ligne est non conforme
     */
    public static LogEntry parse(String line) {
        try {
            Object root = Json.parse(line);
            if (!(root instanceof Map<?, ?> map)) {
                return null;
            }
            long timestamp = asLong(map.get(KEY_TIMESTAMP), -1L);
            LogLevel level = asLevel(map.get(KEY_LEVEL));
            if (timestamp < 0 || level == null) {
                return null;
            }
            FlattenedException exception = map.get(KEY_EXCEPTION) == null
                    ? null : readException(map.get(KEY_EXCEPTION));
            return new LogEntry(timestamp,
                    asString(map.get(KEY_SESSION)),
                    level,
                    asString(map.get(KEY_TAG)),
                    asString(map.get(KEY_THREAD)),
                    asString(map.get(KEY_MESSAGE)),
                    exception);
        } catch (Json.JsonException malformed) {
            return null;
        }
    }

    // ---- Arbre d'exception ------------------------------------------------------

    private static Map<String, Object> writeException(FlattenedException exception) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(KEY_CLASS, exception.className());
        if (exception.message() != null) {
            map.put("message", exception.message());
        }
        if (!exception.frames().isEmpty()) {
            List<Object> frames = new ArrayList<>(exception.frames().size());
            frames.addAll(exception.frames());
            map.put(KEY_FRAMES, frames);
        }
        if (exception.cause() != null) {
            map.put(KEY_CAUSE, writeException(exception.cause()));
        }
        if (!exception.suppressed().isEmpty()) {
            List<Object> suppressed = new ArrayList<>(exception.suppressed().size());
            for (FlattenedException supressedException : exception.suppressed()) {
                suppressed.add(writeException(supressedException));
            }
            map.put(KEY_SUPPRESSED, suppressed);
        }
        return map;
    }

    private static FlattenedException readException(Object node) {
        if (!(node instanceof Map<?, ?> map)) {
            return null;
        }
        String className = asString(map.get(KEY_CLASS));
        if (className.isEmpty()) {
            return null;
        }
        List<String> frames = new ArrayList<>();
        if (map.get(KEY_FRAMES) instanceof List<?> list) {
            for (Object frame : list) {
                if (frame instanceof String text && !text.isEmpty()) {
                    frames.add(text);
                }
            }
        }
        FlattenedException cause = map.get(KEY_CAUSE) == null
                ? null : readException(map.get(KEY_CAUSE));
        List<FlattenedException> suppressed = new ArrayList<>();
        if (map.get(KEY_SUPPRESSED) instanceof List<?> list) {
            for (Object suppressedNode : list) {
                FlattenedException flat = readException(suppressedNode);
                if (flat != null) {
                    suppressed.add(flat);
                }
            }
        }
        String message = map.get("message") == null ? null : asString(map.get("message"));
        return new FlattenedException(className, message, frames, cause, suppressed);
    }

    // ---- Petits lecteurs tolérants -----------------------------------------------

    private static String asString(Object value) {
        return value instanceof String text ? text : "";
    }

    private static long asLong(Object value, long fallback) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return fallback;
    }

    private static LogLevel asLevel(Object value) {
        if (value instanceof String text) {
            try {
                return LogLevel.valueOf(text);
            } catch (IllegalArgumentException unknown) {
                return null;
            }
        }
        return null;
    }
}
