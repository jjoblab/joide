package jo.joide.diag;

/**
 * Configuration d'exécution du pipeline de journalisation (portage CodeIDE,
 * ADR-021), lisible à chaud par le moteur et le sink fichier — immuable.
 *
 * @param minLevel        niveau minimal global (filtrage à l'entrée du pipeline)
 * @param fileLoggingEnabled écriture disque active (processus de plantage :
 *                        interdite par convention — le rapport ne doit pas
 *                        dépendre du journal)
 * @param retentionDays   rétention des archives de journal, en jours
 * @param maxArchiveFiles plafond du nombre d'archives conservées
 * @param maxFileBytes    taille maximale du fichier courant avant rotation
 */
public record LogConfig(
        LogLevel minLevel,
        boolean fileLoggingEnabled,
        int retentionDays,
        int maxArchiveFiles,
        long maxFileBytes) {

    /** Configuration nominale du dépôt (bornes de {@link LoggingLimits}). */
    public static LogConfig defaults(LogLevel minLevel) {
        return new LogConfig(minLevel, true, LoggingLimits.RETENTION_DAYS,
                LoggingLimits.MAX_ARCHIVE_FILES, LoggingLimits.MAX_FILE_BYTES);
    }
}
