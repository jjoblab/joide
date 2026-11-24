package jo.joide.diag;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Détection de boucle de plantages (portage CodeIDE §5.8, ADR-021) :
 * <b>au moins {@link CrashLimits#LOOP_THRESHOLD} plantages en
 * {@link CrashLimits#LOOP_WINDOW_MILLIS} ms</b> et l'application est
 * déclarée en boucle — le gestionnaire ne propose plus de suite heureuse, il
 * délègue au système pour casser la spirale.
 *
 * <p>L'historique est <b>persistant et minimal</b> : un fichier texte
 * d'horodatages (un par ligne) à côté des rapports. Un fichier corrompu ou
 * absent repart de zéro — perdre l'historique vaut mieux que de rater un
 * plantage. L'écriture est atomique ({@code .tmp} puis renommage).</p>
 */
public final class CrashLoopDetector {

    private static final String HISTORY_FILE = "loop-history.txt";

    private final Path historyFile;
    private final long windowMillis;
    private final int threshold;
    private final int historyMax;

    /**
     * @param directory répertoire des rapports (l'historique y vit à côté)
     * @param windowMillis fenêtre glissante de comptage
     * @param threshold nombre de plantages déclenchant la boucle
     * @param historyMax taille maximale de l'historique conservé
     */
    public CrashLoopDetector(Path directory, long windowMillis, int threshold, int historyMax) {
        this.historyFile = directory.resolve(HISTORY_FILE);
        this.windowMillis = windowMillis;
        this.threshold = threshold;
        this.historyMax = historyMax;
    }

    /** Constructeur aux bornes nominales du dépôt. */
    public CrashLoopDetector(Path directory) {
        this(directory, CrashLimits.LOOP_WINDOW_MILLIS, CrashLimits.LOOP_THRESHOLD,
                CrashLimits.LOOP_HISTORY_MAX);
    }

    /**
     * Enregistre un plantage et indique s'il fait entrer l'application en
     * boucle : les horodatages plus vieux que la fenêtre sont élagués, le
     * courant est ajouté, puis le seuil est comparé à la taille restante.
     *
     * @param nowMillis horodatage courant (millisecondes epoch)
     * @return {@code true} si le seuil est atteint ou dépassé dans la fenêtre
     */
    public boolean recordAndDetect(long nowMillis) {
        List<Long> recent = new ArrayList<>();
        for (Long timestamp : readHistory()) {
            if (timestamp >= nowMillis - windowMillis) {
                recent.add(timestamp);
            }
        }
        recent.add(nowMillis);
        List<Long> kept = recent.size() > historyMax
                ? new ArrayList<>(recent.subList(recent.size() - historyMax, recent.size()))
                : recent;
        writeHistory(kept);
        return kept.size() >= threshold;
    }

    /** Lit l'historique ; toute erreur se traduit par un historique vide. */
    private List<Long> readHistory() {
        try {
            List<Long> timestamps = new ArrayList<>();
            for (String line : Files.readAllLines(historyFile, StandardCharsets.UTF_8)) {
                try {
                    timestamps.add(Long.parseLong(line.strip()));
                } catch (NumberFormatException malformed) {
                    // Ligne pourrie : ignorée, l'historique reste utile.
                }
            }
            return timestamps;
        } catch (IOException unreadable) {
            return List.of();
        }
    }

    /** Écrit l'historique de façon atomique ; l'échec n'est jamais fatal. */
    private void writeHistory(List<Long> timestamps) {
        try {
            Files.createDirectories(historyFile.getParent());
            StringBuilder body = new StringBuilder();
            for (Long timestamp : timestamps) {
                body.append(timestamp).append('\n');
            }
            Path temporary = historyFile.resolveSibling(historyFile.getFileName() + ".tmp");
            // Bytes, pas writeString : règle API 28 (writeString est API 33).
            Files.write(temporary, body.toString().getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(temporary, historyFile, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException exotic) {
                Files.move(temporary, historyFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException writeFailed) {
            // Dégradation assumée : la boucle sera détectée un plantage plus
            // tard — mieux vaut ne jamais faire échouer le gestionnaire.
        }
    }
}
