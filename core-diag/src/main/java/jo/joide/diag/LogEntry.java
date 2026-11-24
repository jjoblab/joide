package jo.joide.diag;

/**
 * Entrée immuable du journal applicatif (portage CodeIDE §5.7, ADR-021).
 *
 * <p>Une entrée est produite par le pipeline <b>après</b> filtrage par niveau,
 * expurgation et troncature : ce qui vit ici est déjà sûr à écrire sur disque,
 * à afficher et à exporter. Le format de persistance est JSON Lines (une
 * entrée par ligne, {@link LogEntryJson}).</p>
 *
 * @param timestampMillis horodatage de l'émission, en millisecondes epoch
 * @param sessionId identifiant du lancement (UUID) auquel appartient l'entrée
 * @param level sévérité de l'entrée
 * @param tag étiquette courte du producteur (identifiant, donc en anglais)
 * @param threadName nom du fil émetteur, utile au diagnostic
 * @param message texte expurgé et tronqué (4 Kio maximum)
 * @param exception exception aplanie éventuelle associée à l'entrée
 */
public record LogEntry(
        long timestampMillis,
        String sessionId,
        LogLevel level,
        String tag,
        String threadName,
        String message,
        FlattenedException exception) {

    public LogEntry {
        sessionId = sessionId == null ? "" : sessionId;
        tag = tag == null ? "" : tag;
        threadName = threadName == null ? "" : threadName;
        message = message == null ? "" : message;
    }
}
