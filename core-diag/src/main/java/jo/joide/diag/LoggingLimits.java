package jo.joide.diag;

/**
 * Bornes et constantes du pipeline de journalisation (portage CodeIDE §5.7,
 * ADR-021). Centralisées pour rester nommées, testées et documentées — jamais
 * de nombre magique dans le pipeline.
 */
public final class LoggingLimits {

    /** Taille maximale du message d'une entrée, en octets UTF-8. */
    public static final int MESSAGE_MAX_BYTES = 4 * 1024;

    /** Nombre maximal de tranches de pile par exception journalisée. */
    public static final int EXCEPTION_MAX_FRAMES = 50;

    /** Nombre maximal d'exceptions enchaînées conservées. */
    public static final int EXCEPTION_MAX_CAUSES = 10;

    /** Capacité du tampon circulaire de filons (entrées récentes). */
    public static final int BREADCRUMB_CAPACITY = 200;

    /** Capacité de la file d'attente d'écriture disque (DROP_OLDEST au-delà). */
    public static final int CHANNEL_CAPACITY = 512;

    /** Fenêtre d'écriture groupée, en millisecondes. */
    public static final long BATCH_WINDOW_MS = 500L;

    /** Nombre de millisecondes par jour (rétention des archives). */
    public static final long MILLIS_PER_DAY = 86_400_000L;

    /** Nom du fichier courant de journal (JSON Lines). */
    public static final String CURRENT_FILE_NAME = "current.jsonl";

    /** Préfixe des noms d'archives : {@code archive-1.jsonl} est la plus récente. */
    public static final String ARCHIVE_FILE_PREFIX = "archive-";

    /** Répertoire des journaux, sous {@code filesDir} (côté coquille). */
    public static final String LOGS_DIR_NAME = "logs";

    /** Taille maximale du fichier courant avant rotation (2 Mo). */
    public static final long MAX_FILE_BYTES = 2 * 1024 * 1024;

    /** Nombre maximal d'archives de journal conservées. */
    public static final int MAX_ARCHIVE_FILES = 5;

    /** Rétention des archives, en jours. */
    public static final int RETENTION_DAYS = 7;

    /** Filons de pain joints à un rapport de plantage (50 derniers). */
    public static final int MAX_BREADCRUMBS = 50;

    /** Nombre d'entrées du journal jointes au rapport de diagnostic. */
    public static final int DIAGNOSTIC_JOURNAL_TAIL = 100;

    private LoggingLimits() {
        // Constantes — jamais instanciée.
    }
}
