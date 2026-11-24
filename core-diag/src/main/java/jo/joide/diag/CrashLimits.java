package jo.joide.diag;

/**
 * Bornes du système de gestion des plantages (portage CodeIDE §5.8, ADR-021)
 * — aucune valeur magique dispersée dans le code.
 */
public final class CrashLimits {

    /** Répertoire des rapports, sous {@code filesDir} (côté coquille). */
    public static final String DIRECTORY_NAME = "crashes";

    /** Extension des fichiers de rapport. */
    public static final String REPORT_SUFFIX = ".json";

    /** Extension du fichier témoin d'état « consulté ». */
    public static final String REVIEWED_SUFFIX = ".reviewed";

    /** Taille maximale d'un rapport sur disque : 256 Ko. */
    public static final int MAX_REPORT_BYTES = 256 * 1024;

    /** Nombre maximal de rapports conservés (les plus récents). */
    public static final int MAX_REPORTS = 20;

    /** Tranches de pile conservées dans un rapport. */
    public static final int MAX_FRAMES = 100;

    /** Sauts de cause conservés dans un rapport. */
    public static final int MAX_CAUSES = 10;

    /** Taille maximale d'un message d'exception dans un rapport. */
    public static final int MAX_MESSAGE_BYTES = 2_048;

    /** Fenêtre de détection de boucle : 3 plantages en 60 s. */
    public static final long LOOP_WINDOW_MILLIS = 60_000L;

    /** Seuil de détection de boucle. */
    public static final int LOOP_THRESHOLD = 3;

    /** Nombre d'horodatages de boucle conservés en historique. */
    public static final int LOOP_HISTORY_MAX = 10;

    /** Vidage du journal borné à 500 ms. */
    public static final long FLUSH_TIMEOUT_MILLIS = 500L;

    /** Durée totale maximale du gestionnaire : 2 s. */
    public static final long HANDLER_BUDGET_MILLIS = 2_000L;

    /** Nombre maximal de sorties de processus lues au démarrage (API 30+). */
    public static final int EXIT_INFO_MAX = 20;

    /** Taille maximale de la trace d'une sortie de processus. */
    public static final int EXIT_INFO_TRACE_MAX_BYTES = 8_192;

    private CrashLimits() {
        // Constantes — jamais instanciée.
    }
}
