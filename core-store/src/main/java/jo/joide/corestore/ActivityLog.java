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
 * Journal d'activité (lot L10, d'après BuildActivity + la visionneuse de la
 * référence) : la chronologie de ce que l'IDE a fait — **builds** (départ,
 * verdict, écroulement du moteur), **passes d'indexation** (phase, verdict) —
 * bornée, persistante, écrite atomiquement. Les écroulements des trois zones
 * vivent dans le {@link CrashJournal} ; l'écran d'activité FUSIONNE les deux.
 *
 * <p>Une entrée : nature, horodatage, message court, détail optionnel
 * (déplié au tap — trace, statistiques). Format par ligne :
 * {@code <epochMs> <NATURE> <message> :: <détail>}.</p>
 */
public final class ActivityLog {

    /** Nature d'une entrée (préfixe stable du format de ligne). */
    public enum Kind { BUILD, VERDICT, INDEX, CRASH }

    /** Une entrée du journal. */
    public record Entry(Kind kind, long epochMs, String message, String detail) {}

    private final Path file;
    private final int maxEntries;

    public ActivityLog(Path file) {
        this(file, 100);
    }

    public ActivityLog(Path file, int maxEntries) {
        this.file = file;
        this.maxEntries = maxEntries;
    }

    /** Enregistre une entrée (détail peut être null) — bornée, atomique. */
    public synchronized void record(Kind kind, String message, String detail) {
        List<Entry> entries = new ArrayList<>(read());
        entries.add(new Entry(kind, System.currentTimeMillis(),
                borné(message, 200), borné(detail, 600)));
        while (entries.size() > maxEntries) {
            entries.remove(0); // les plus anciennes partent : la borne est stricte.
        }
        writeAtomically(entries);
    }

    /** Entrées persistées (les plus anciennes en premier) ; vide si illisible. */
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
                Entry entry = parse(line);
                if (entry != null) {
                    entries.add(entry);
                }
            }
            return entries;
        } catch (IOException e) {
            return List.of(); // journal illisible : vide, jamais d'exception.
        }
    }

    private static Entry parse(String line) {
        try {
            int first = line.indexOf(' ');
            long epoch = Long.parseLong(line.substring(0, first));
            int second = line.indexOf(' ', first + 1);
            Kind kind = Kind.valueOf(line.substring(first + 1, second));
            String rest = line.substring(second + 1);
            int séparateur = rest.indexOf(" :: ");
            if (séparateur >= 0) {
                return new Entry(kind, epoch, rest.substring(0, séparateur),
                        rest.substring(séparateur + 4));
            }
            return new Entry(kind, epoch, rest, null);
        } catch (Exception e) {
            return null; // ligne illisible : sautée, le journal reste utile.
        }
    }

    private void writeAtomically(List<Entry> entries) {
        try {
            Files.createDirectories(file.getParent());
            StringBuilder body = new StringBuilder();
            for (Entry entry : entries) {
                body.append(entry.epochMs()).append(' ').append(entry.kind())
                        .append(' ').append(entry.message());
                if (entry.detail() != null) {
                    body.append(" :: ").append(entry.detail());
                }
                body.append('\n');
            }
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temp, body.toString(), StandardCharsets.UTF_8);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            // Le journal d'activité ne doit jamais faire planter l'app.
        }
    }

    private static String borné(String texte, int max) {
        if (texte == null) {
            return null;
        }
        return texte.length() > max ? texte.substring(0, max) + "…" : texte;
    }
}
