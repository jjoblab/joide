package jo.joide.diag;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Propriétaire unique du stockage disque du journal (portage CodeIDE,
 * ADR-021) : répertoire {@code filesDir/logs/}, fichier courant
 * {@code current.jsonl} au format JSON Lines, archives
 * {@code archive-1.jsonl} (la plus récente) à {@code archive-N.jsonl}.
 *
 * <p>Toutes les opérations passent par un verrou unique : le sink fichier
 * est le seul consommateur en écriture, mais le diagnostic lit, efface et
 * balaie la rétention depuis d'autres fils — le verrou garantit la
 * cohérence des rotations.</p>
 *
 * <p>Tolérance aux écritures interrompues (plantage brutal d'un lancement
 * précédent) : les lignes illisibles sont <b>comptées puis ignorées</b> à la
 * lecture — {@link #skippedLines()} reste consultable pour le diagnostic ;
 * on n'avale pas l'information, on la mesure.</p>
 */
public final class JsonlLogStore {

    /** Occupation disque du journal (octets cumulés + nombre de fichiers). */
    public record DiskStats(long bytes, int fileCount) {}

    private final Path directory;

    /** Lignes illisibles ignorées depuis la dernière lecture. */
    private int skippedLines;

    /** Dernière erreur de lecture rencontrée (diagnostic, jamais affichée). */
    private String lastReadError;

    public JsonlLogStore(Path directory) {
        this.directory = directory;
    }

    /**
     * Ajoute des lignes JSONL, avec rotation : le fichier courant passe en
     * archive dès que la taille maximale serait dépassée. Le flux de sortie
     * reste ouvert sur l'ensemble du lot (rouvert aux rotations seulement).
     *
     * @param lines lignes sérialisées (sans le retour à la ligne final)
     * @param maxFileSizeBytes taille maximale du fichier courant
     * @param maxArchiveFiles nombre maximal d'archives conservées
     * @throws IOException si l'écriture échoue (remontée au sink appelant)
     */
    void appendLines(List<String> lines, long maxFileSizeBytes, int maxArchiveFiles)
            throws IOException {
        if (lines.isEmpty()) {
            return;
        }
        synchronized (this) {
            Files.createDirectories(directory);
            Path current = directory.resolve(LoggingLimits.CURRENT_FILE_NAME);
            long size = Files.isRegularFile(current) ? Files.size(current) : 0L;
            OutputStream out = null;
            try {
                for (String line : lines) {
                    byte[] bytes = (line + "\n").getBytes(StandardCharsets.UTF_8);
                    if (size > 0 && size + bytes.length > maxFileSizeBytes) {
                        if (out != null) {
                            out.close();
                            out = null;
                        }
                        rotate(maxArchiveFiles);
                        current = directory.resolve(LoggingLimits.CURRENT_FILE_NAME);
                        size = 0L;
                    }
                    if (out == null) {
                        out = Files.newOutputStream(current, StandardOpenOption.CREATE,
                                StandardOpenOption.APPEND);
                    }
                    out.write(bytes);
                    size += bytes.length;
                }
            } finally {
                if (out != null) {
                    out.close();
                }
            }
        }
    }

    /**
     * Lit toutes les entrées, de la plus ancienne à la plus récente (archives
     * puis fichier courant). Les lignes corrompues sont comptées, jamais
     * fatales.
     */
    public synchronized List<LogEntry> readAll() {
        List<LogEntry> entries = new ArrayList<>();
        if (!Files.isDirectory(directory)) {
            return entries;
        }
        for (Path file : filesInChronologicalOrder()) {
            readOneFile(file, entries);
        }
        return entries;
    }

    /**
     * Mesure l'occupation disque du journal.
     */
    public synchronized DiskStats diskStats() {
        List<Path> files = journalFiles();
        long bytes = 0L;
        for (Path file : files) {
            try {
                bytes += Files.size(file);
            } catch (IOException unreadable) {
                // Fichier disparu entre listage et mesure : ignoré.
            }
        }
        return new DiskStats(bytes, files.size());
    }

    /**
     * Supprime tous les fichiers de journal (le répertoire est conservé).
     */
    public synchronized void clear() {
        for (Path file : journalFiles()) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException undeletable) {
                // Effacement au mieux : le prochain balayage retentera.
            }
        }
        skippedLines = 0;
    }

    /**
     * Applique la rétention : supprime les archives plus vieilles que
     * {@code retentionDays} jours, puis celles au-delà du plafond. Le fichier
     * courant n'est jamais supprimé.
     *
     * @param nowMillis instant de référence (horloge injectée)
     * @param retentionDays rétention maximale, en jours
     * @param maxArchiveFiles plafond du nombre d'archives
     */
    public synchronized void sweep(long nowMillis, int retentionDays, int maxArchiveFiles) {
        if (!Files.isDirectory(directory)) {
            return;
        }
        long cutoff = nowMillis - retentionDays * LoggingLimits.MILLIS_PER_DAY;
        for (int index : archiveIndices()) {
            if (index > maxArchiveFiles) {
                deleteQuietly(archiveFile(index));
            } else {
                try {
                    if (Files.getLastModifiedTime(archiveFile(index)).toMillis() < cutoff) {
                        deleteQuietly(archiveFile(index));
                    }
                } catch (IOException unreadable) {
                    // Date illisible : conservée par prudence.
                }
            }
        }
    }

    /** Lignes illisibles ignorées depuis la dernière lecture complète. */
    public synchronized int skippedLines() {
        return skippedLines;
    }

    /** Dernière erreur de lecture (nom simple de l'exception), ou null. */
    public synchronized String lastReadError() {
        return lastReadError;
    }

    // ---- Internes ---------------------------------------------------------------

    private void readOneFile(Path file, List<LogEntry> entries) {
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            lastReadError = unreadable.getClass().getSimpleName();
            return;
        }
        for (String line : lines) {
            if (line.isBlank()) {
                continue;
            }
            LogEntry entry = LogEntryJson.parse(line);
            if (entry == null) {
                skippedLines++;
                lastReadError = "JsonException";
            } else {
                entries.add(entry);
            }
        }
    }

    /** Fait tourner les fichiers : current → archive-1, archives décalées. */
    private void rotate(int maxArchiveFiles) {
        deleteQuietly(archiveFile(maxArchiveFiles));
        for (int index = maxArchiveFiles - 1; index >= 1; index--) {
            Path source = archiveFile(index);
            if (Files.exists(source)) {
                try {
                    Files.move(source, archiveFile(index + 1));
                } catch (IOException unmovable) {
                    // Rotation partielle : la prochaine tentera encore.
                }
            }
        }
        Path current = directory.resolve(LoggingLimits.CURRENT_FILE_NAME);
        try {
            Files.move(current, archiveFile(1));
        } catch (IOException unmovable) {
            // Le courant repart de zéro à la prochaine écriture seulement
            // s'il a pu être renommé ; sinon il continue de croître.
        }
    }

    private Path archiveFile(int index) {
        return directory.resolve(LoggingLimits.ARCHIVE_FILE_PREFIX + index + ".jsonl");
    }

    private List<Path> journalFiles() {
        List<Path> files = new ArrayList<>();
        if (!Files.isDirectory(directory)) {
            return files;
        }
        try (Stream<Path> listing = Files.list(directory)) {
            listing.filter(Files::isRegularFile).forEach(files::add);
        } catch (IOException unreadable) {
            return files;
        }
        files.removeIf(file -> !isJournalFile(file.getFileName().toString()));
        return files;
    }

    /** Archives (indices décroissants) puis fichier courant, si présents. */
    private List<Path> filesInChronologicalOrder() {
        List<Path> ordered = new ArrayList<>();
        for (int index : archiveIndices()) {
            ordered.add(archiveFile(index));
        }
        Path current = directory.resolve(LoggingLimits.CURRENT_FILE_NAME);
        if (Files.isRegularFile(current)) {
            ordered.add(current);
        }
        return ordered;
    }

    /** Indices des archives existantes, de la plus récente à la plus vieille. */
    private List<Integer> archiveIndices() {
        List<Integer> indices = new ArrayList<>();
        if (!Files.isDirectory(directory)) {
            return indices;
        }
        try (Stream<Path> listing = Files.list(directory)) {
            listing.filter(Files::isRegularFile)
                    .map(file -> file.getFileName().toString())
                    .forEach(name -> {
                        Integer index = archiveIndex(name);
                        if (index != null) {
                            indices.add(index);
                        }
                    });
        } catch (IOException unreadable) {
            return indices;
        }
        indices.sort(Comparator.reverseOrder());
        return indices;
    }

    private static boolean isJournalFile(String name) {
        return name.equals(LoggingLimits.CURRENT_FILE_NAME) || archiveIndex(name) != null;
    }

    private static Integer archiveIndex(String name) {
        if (!name.startsWith(LoggingLimits.ARCHIVE_FILE_PREFIX) || !name.endsWith(".jsonl")) {
            return null;
        }
        String middle = name.substring(LoggingLimits.ARCHIVE_FILE_PREFIX.length(),
                name.length() - ".jsonl".length());
        try {
            return Integer.parseInt(middle);
        } catch (NumberFormatException malformed) {
            return null;
        }
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException undeletable) {
            // Suppression au mieux : le prochain balayage retentera.
        }
    }
}
