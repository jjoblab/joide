package jo.joide.diag;

/**
 * Destination d'écriture du pipeline de journalisation (ADR-021) : chaque
 * destination filtre par son niveau minimal — le moteur a déjà filtré par la
 * configuration globale, la destination affine (logcat bavard en debug,
 * fichier à partir de DEBUG…).
 */
public interface LogSink {

    /** Niveau minimal que cette destination accepte. */
    LogLevel minLevel();

    /**
     * Écrit une entrée — retour immédiat garanti : les destinations lentes
     * tamponnent de leur côté (voir {@link FileSink}).
     *
     * @param entry entrée déjà expurgée et tronquée
     */
    void write(LogEntry entry);
}
