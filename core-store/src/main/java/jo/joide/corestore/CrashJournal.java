package jo.joide.corestore;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Journal des écroulements (conception §10) : capture des plantages des trois
 * zones (UI, moteur, terminal), **borné**, écrit sous `filesDir/journal/`.
 *
 * <p>Inspiré de la note de plantage de la référence (REF : le processus
 * mourant écrit la note lui-même, message borné, heap utilisé/max) —
 * réimplémentation clean-room : une entrée = zone, horodatage, classe du
 * Throwable, message borné ; la borne garantit un journal qui ne croît pas
 * sans limite ; l'écriture est atomique (temp + renommage) pour qu'un plantage
 * pendant l'écriture ne laisse jamais un fichier à moitié écrit.</p>
 */
public final class CrashJournal {

    /** Zone d'origine du plantage (les trois zones de la conception §10). */
    public enum Zone { UI, MOTEUR, TERMINAL }

    /** Une entrée du journal. */
    public record Entry(Zone zone, long epochMs, String errorClass, String message) {

        /** Rendu sur une ligne (borné à la construction). */
        public String line() {
            return Instant.ofEpochMilli(epochMs) + " [" + zone + "] "
                    + errorClass + ": " + (message == null ? "" : message);
        }
    }

    private final Path file;
    private final int maxEntries;

    public CrashJournal(Path file) {
        this(file, 20);
    }

    public CrashJournal(Path file, int maxEntries) {
        this.file = file;
        this.maxEntries = maxEntries;
    }

    /**
     * Enregistre un plantage (borne le message à 300 caractères comme la
     * référence le fait — jamais une trace entière non bornée au disque).
     */
    public synchronized void record(Zone zone, Throwable error) {
        String message = error.getMessage() == null ? "" : error.getMessage();
        if (message.length() > 300) {
            message = message.substring(0, 300) + "…";
        }
        Entry entry = new Entry(zone, System.currentTimeMillis(),
                error.getClass().getName(), message);
        List<Entry> entries = new ArrayList<>(read());
        entries.add(entry);
        while (entries.size() > maxEntries) {
            entries.remove(0); // les plus anciens partent : la borne est stricte
        }
        writeAtomically(entries);
    }

    /** Entrées persistées (les plus récentes en dernier). */
    public synchronized List<Entry> read() {
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        try {
            List<Entry> entries = new ArrayList<>();
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank()) {
                    continue;
                }
                entries.add(parse(line));
            }
            return entries;
        } catch (Exception e) {
            return List.of(); // journal illisible : vide, jamais d'exception
        }
    }

    /** Analyse d'une ligne du journal (bornée aux champs connus). */
    private static Entry parse(String line) {
        try {
            int close = line.indexOf(']');
            long epoch = Instant.parse(line.substring(0, line.indexOf('[') - 1))
                    .toEpochMilli();
            String zone = line.substring(close - zoneLength(line, close), close);
            String rest = line.substring(close + 2);
            int colon = rest.indexOf(':');
            return new Entry(Zone.valueOf(zone), epoch,
                    rest.substring(0, colon), rest.substring(colon + 2));
        } catch (Exception e) {
            return new Entry(Zone.UI, 0L, "LigneIllisible", line);
        }
    }

    private static int zoneLength(String line, int close) {
        for (Zone zone : Zone.values()) {
            if (close >= zone.name().length()
                    && line.regionMatches(close - zone.name().length(), zone.name(), 0,
                            zone.name().length())) {
                return zone.name().length();
            }
        }
        return 0;
    }

    private void writeAtomically(List<Entry> entries) {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            StringBuilder body = new StringBuilder();
            entries.forEach(e -> body.append(e.line()).append('\n'));
            Files.writeString(tmp, body.toString(), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            // Le journal des écroulements ne doit jamais faire planter l'app.
        }
    }
}
