package jo.joide.diag;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Mapping JSON des rapports de plantage (portage CodeIDE §5.8, ADR-021) via
 * le codec maison {@link Json} : aucune dépendance sur le chemin critique
 * d'un plantage.
 *
 * <p>L'écriture applique une {@link Reduction} décroissante jusqu'à
 * respecter la limite de taille ({@link CrashLimits#MAX_REPORT_BYTES}) — la
 * lecture, elle, accepte tout fichier valide : la réduction ne change jamais
 * la structure, seulement les longueurs.</p>
 */
public final class CrashReportJson {

    /**
     * Niveau de réduction appliqué à l'écriture d'un rapport : échelle
     * décroissante — chaque niveau coupe davantage (filons, puis tranches,
     * puis messages) jusqu'à ce que le rapport tienne dans la limite de
     * taille.
     */
    public enum Reduction {
        /** Rapport complet : bornes nominales. */
        FULL(LoggingLimits.MAX_BREADCRUMBS, CrashLimits.MAX_FRAMES, CrashLimits.MAX_MESSAGE_BYTES),
        /** Première réduction : filons et tranches allégés. */
        REDUCED(REDUCED_BREADCRUMBS, REDUCED_FRAMES, REDUCED_MESSAGE_BYTES),
        /** Seconde réduction : plus de filons, trace minimale. */
        LEAN(0, LEAN_FRAMES, LEAN_MESSAGE_BYTES),
        /** Chute finale : l'essentiel tient toujours dans la limite. */
        MINIMAL(0, 0, MINIMAL_MESSAGE_BYTES);

        final int breadcrumbs;
        final int frames;
        final int messageBytes;

        Reduction(int breadcrumbs, int frames, int messageBytes) {
            this.breadcrumbs = breadcrumbs;
            this.frames = frames;
            this.messageBytes = messageBytes;
        }

        /** Niveau de réduction suivant, ou soi-même au plancher. */
        Reduction next() {
            return ordinal() + 1 < values().length ? values()[ordinal() + 1] : this;
        }
    }

    // Échelons de l'échelle de réduction.
    private static final int REDUCED_BREADCRUMBS = 10;
    private static final int REDUCED_FRAMES = 50;
    private static final int REDUCED_MESSAGE_BYTES = 512;
    private static final int LEAN_FRAMES = 20;
    private static final int LEAN_MESSAGE_BYTES = 256;
    private static final int MINIMAL_MESSAGE_BYTES = 128;

    // Bornes de troncature par champ (octets) — complètent la réduction globale.
    private static final int SHORT_LIMIT = 64;
    private static final int BUILD_LIMIT = 32;
    private static final int NAME_LIMIT = 128;
    private static final int CLASS_LIMIT = 512;
    private static final int SUMMARY_LIMIT = 200;

    private CrashReportJson() {
        // Classe utilitaire — jamais instanciée.
    }

    /**
     * Sérialise un rapport au niveau de réduction demandé.
     *
     * @param report rapport à écrire
     * @param level  niveau de réduction
     * @return l'arbre JSON du rapport (prêt pour {@link Json#write})
     */
    public static Map<String, Object> write(CrashReport report, Reduction level) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(KEY_ID, report.id());
        map.put(KEY_TYPE, report.type().name());
        map.put(KEY_TIMESTAMP, report.timestampMillis());
        map.put(KEY_SESSION, report.sessionId());
        map.put(KEY_APPLICATION, writeApplication(report.application()));
        map.put(KEY_DEVICE, writeDevice(report.device()));
        map.put(KEY_THREAD, report.threadName());
        map.put(KEY_EXCEPTION, writeException(report.exception(), level));
        map.put(KEY_BREADCRUMBS, writeBreadcrumbs(report.breadcrumbs(), level));
        if (report.lastScreen() != null) {
            map.put(KEY_LAST_SCREEN, DiagText.truncateUtf8(report.lastScreen(), NAME_LIMIT));
        }
        map.put(KEY_UPTIME, report.processUptimeMs());
        map.put(KEY_LOOP, report.isCrashLoop());
        return map;
    }

    /**
     * Désérialise un rapport.
     *
     * @param text document JSON lu sur disque
     * @return le rapport, ou {@code null} si le contenu est invalide
     */
    public static CrashReport read(String text) {
        try {
            Object root = Json.parse(text);
            if (!(root instanceof Map<?, ?> map)) {
                return null;
            }
            if (!(map.get(KEY_APPLICATION) instanceof Map<?, ?> application)
                    || !(map.get(KEY_DEVICE) instanceof Map<?, ?> device)
                    || !(map.get(KEY_EXCEPTION) instanceof Map<?, ?> exception)) {
                return null;
            }
            String id = asString(map.get(KEY_ID));
            CrashType type = asCrashType(map.get(KEY_TYPE));
            if (id.isEmpty() || type == null) {
                return null;
            }
            FlattenedException flatException = readException(exception);
            if (flatException == null) {
                return null;
            }
            return new CrashReport(id,
                    type,
                    asLong(map.get(KEY_TIMESTAMP), 0L),
                    asString(map.get(KEY_SESSION)),
                    readApplication(application),
                    readDevice(device),
                    asString(map.get(KEY_THREAD)),
                    flatException,
                    readBreadcrumbs(map.get(KEY_BREADCRUMBS)),
                    map.get(KEY_LAST_SCREEN) == null ? null : asString(map.get(KEY_LAST_SCREEN)),
                    asLong(map.get(KEY_UPTIME), 0L),
                    Boolean.TRUE.equals(map.get(KEY_LOOP)));
        } catch (Json.JsonException malformed) {
            return null;
        }
    }

    /**
     * Extrait le résumé d'un rapport, sans le lire entièrement.
     *
     * @param text     document JSON lu sur disque
     * @param reviewed état « consulté » (fichier témoin, fourni par le dépôt)
     * @return le résumé, ou {@code null} si le contenu est invalide
     */
    public static CrashReportSummary readSummary(String text, boolean reviewed) {
        try {
            Object root = Json.parse(text);
            if (!(root instanceof Map<?, ?> map)) {
                return null;
            }
            String id = asString(map.get(KEY_ID));
            CrashType type = asCrashType(map.get(KEY_TYPE));
            if (id.isEmpty() || type == null) {
                return null;
            }
            String className = "";
            String message = "";
            if (map.get(KEY_EXCEPTION) instanceof Map<?, ?> exception) {
                className = asString(exception.get(KEY_CLASS));
                message = exception.get("message") == null
                        ? "" : asString(exception.get("message"));
            }
            return new CrashReportSummary(id,
                    type,
                    asLong(map.get(KEY_TIMESTAMP), 0L),
                    simpleClassName(className),
                    DiagText.firstLine(message, SUMMARY_LIMIT),
                    reviewed);
        } catch (Json.JsonException malformed) {
            return null;
        }
    }

    // ---- Sections ----------------------------------------------------------------

    private static Map<String, Object> writeApplication(CrashAppInfo application) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(KEY_VERSION_NAME, DiagText.truncateUtf8(application.versionName(), SHORT_LIMIT));
        map.put(KEY_VERSION_CODE, application.versionCode());
        map.put(KEY_BUILD, DiagText.truncateUtf8(application.buildType(), BUILD_LIMIT));
        map.put(KEY_PACKAGE, DiagText.truncateUtf8(application.applicationId(), NAME_LIMIT));
        return map;
    }

    private static CrashAppInfo readApplication(Map<?, ?> map) {
        return new CrashAppInfo(
                orDefault(asString(map.get(KEY_VERSION_NAME)), "unknown"),
                asLong(map.get(KEY_VERSION_CODE), 0L),
                orDefault(asString(map.get(KEY_BUILD)), "unknown"),
                orDefault(asString(map.get(KEY_PACKAGE)), "unknown"));
    }

    private static Map<String, Object> writeDevice(DeviceInfo device) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(KEY_MANUFACTURER, DiagText.truncateUtf8(device.manufacturer(), NAME_LIMIT));
        map.put(KEY_MODEL, DiagText.truncateUtf8(device.model(), NAME_LIMIT));
        map.put(KEY_ANDROID, DiagText.truncateUtf8(device.androidVersion(), SHORT_LIMIT));
        map.put(KEY_API, device.apiLevel());
        map.put(KEY_ABI, DiagText.truncateUtf8(device.abi(), SHORT_LIMIT));
        map.put(KEY_LOCALE, DiagText.truncateUtf8(device.locale(), SHORT_LIMIT));
        map.put(KEY_MAX_MEMORY, device.maxMemoryBytes());
        map.put(KEY_FREE_MEMORY, device.freeMemoryBytes());
        map.put(KEY_STORAGE_FREE, device.storageFreeBytes());
        return map;
    }

    private static DeviceInfo readDevice(Map<?, ?> map) {
        return new DeviceInfo(
                orDefault(asString(map.get(KEY_MANUFACTURER)), "unknown"),
                orDefault(asString(map.get(KEY_MODEL)), "unknown"),
                orDefault(asString(map.get(KEY_ANDROID)), "unknown"),
                (int) asLong(map.get(KEY_API), 0L),
                orDefault(asString(map.get(KEY_ABI)), "unknown"),
                orDefault(asString(map.get(KEY_LOCALE)), "unknown"),
                asLong(map.get(KEY_MAX_MEMORY), 0L),
                asLong(map.get(KEY_FREE_MEMORY), 0L),
                asLong(map.get(KEY_STORAGE_FREE), 0L));
    }

    private static Map<String, Object> writeException(FlattenedException exception, Reduction level) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(KEY_CLASS, DiagText.truncateUtf8(exception.className(), CLASS_LIMIT));
        if (exception.message() != null) {
            map.put("message", DiagText.truncateUtf8(exception.message(), level.messageBytes));
        }
        if (!exception.frames().isEmpty()) {
            List<Object> frames = new ArrayList<>();
            for (String frame : exception.frames()) {
                if (frames.size() >= level.frames) {
                    break;
                }
                frames.add(frame);
            }
            map.put(KEY_FRAMES, frames);
        }
        if (exception.cause() != null) {
            map.put(KEY_CAUSE, writeException(exception.cause(), level));
        }
        if (!exception.suppressed().isEmpty()) {
            List<Object> suppressed = new ArrayList<>();
            for (FlattenedException suppressedException : exception.suppressed()) {
                suppressed.add(writeException(suppressedException, level));
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

    private static List<Object> writeBreadcrumbs(List<LogEntry> breadcrumbs, Reduction level) {
        List<Object> list = new ArrayList<>();
        int from = Math.max(0, breadcrumbs.size() - level.breadcrumbs);
        for (int i = from; i < breadcrumbs.size(); i++) {
            LogEntry breadcrumb = breadcrumbs.get(i);
            Map<String, Object> map = new LinkedHashMap<>();
            map.put(KEY_TIMESTAMP, breadcrumb.timestampMillis());
            map.put(KEY_SESSION, breadcrumb.sessionId());
            map.put(KEY_LEVEL, breadcrumb.level().name());
            map.put(KEY_TAG, breadcrumb.tag());
            map.put(KEY_THREAD, breadcrumb.threadName());
            map.put("message", DiagText.truncateUtf8(breadcrumb.message(), level.messageBytes));
            if (breadcrumb.exception() != null) {
                map.put(KEY_EXCEPTION, writeException(breadcrumb.exception(), level));
            }
            list.add(map);
        }
        return list;
    }

    private static List<LogEntry> readBreadcrumbs(Object node) {
        List<LogEntry> breadcrumbs = new ArrayList<>();
        if (node instanceof List<?> list) {
            for (Object entryNode : list) {
                if (!(entryNode instanceof Map<?, ?> map)) {
                    continue;
                }
                LogLevel level = null;
                if (map.get(KEY_LEVEL) instanceof String levelName) {
                    try {
                        level = LogLevel.valueOf(levelName);
                    } catch (IllegalArgumentException unknown) {
                        level = null;
                    }
                }
                if (level == null) {
                    continue;
                }
                FlattenedException exception = map.get(KEY_EXCEPTION) == null
                        ? null : readException(map.get(KEY_EXCEPTION));
                breadcrumbs.add(new LogEntry(
                        asLong(map.get(KEY_TIMESTAMP), 0L),
                        asString(map.get(KEY_SESSION)),
                        level,
                        asString(map.get(KEY_TAG)),
                        asString(map.get(KEY_THREAD)),
                        asString(map.get("message")),
                        exception));
            }
        }
        return breadcrumbs;
    }

    // ---- Petits lecteurs tolérants -------------------------------------------------

    private static String asString(Object value) {
        return value instanceof String text ? text : "";
    }

    private static long asLong(Object value, long fallback) {
        return value instanceof Number number ? number.longValue() : fallback;
    }

    private static String orDefault(String value, String fallback) {
        return value.isEmpty() ? fallback : value;
    }

    private static CrashType asCrashType(Object value) {
        if (value instanceof String name) {
            try {
                return CrashType.valueOf(name);
            } catch (IllegalArgumentException unknown) {
                return null;
            }
        }
        return null;
    }

    /** Nom simple de la classe (après le dernier point). */
    private static String simpleClassName(String qualified) {
        int cut = qualified.lastIndexOf('.');
        return cut >= 0 ? qualified.substring(cut + 1) : qualified;
    }

    // Clés du document.
    private static final String KEY_ID = "id";
    private static final String KEY_TYPE = "type";
    private static final String KEY_TIMESTAMP = "timestampMillis";
    private static final String KEY_SESSION = "sessionId";
    private static final String KEY_APPLICATION = "application";
    private static final String KEY_DEVICE = "device";
    private static final String KEY_THREAD = "threadName";
    private static final String KEY_EXCEPTION = "exception";
    private static final String KEY_BREADCRUMBS = "breadcrumbs";
    private static final String KEY_LAST_SCREEN = "lastScreen";
    private static final String KEY_UPTIME = "processUptimeMs";
    private static final String KEY_LOOP = "isCrashLoop";
    private static final String KEY_VERSION_NAME = "versionName";
    private static final String KEY_VERSION_CODE = "versionCode";
    private static final String KEY_BUILD = "buildType";
    private static final String KEY_PACKAGE = "applicationId";
    private static final String KEY_MANUFACTURER = "manufacturer";
    private static final String KEY_MODEL = "model";
    private static final String KEY_ANDROID = "androidVersion";
    private static final String KEY_API = "apiLevel";
    private static final String KEY_ABI = "abi";
    private static final String KEY_LOCALE = "locale";
    private static final String KEY_MAX_MEMORY = "maxMemoryBytes";
    private static final String KEY_FREE_MEMORY = "freeMemoryBytes";
    private static final String KEY_STORAGE_FREE = "storageFreeBytes";
    private static final String KEY_CLASS = "className";
    private static final String KEY_FRAMES = "frames";
    private static final String KEY_CAUSE = "cause";
    private static final String KEY_SUPPRESSED = "suppressed";
    private static final String KEY_LEVEL = "level";
    private static final String KEY_TAG = "tag";
}
