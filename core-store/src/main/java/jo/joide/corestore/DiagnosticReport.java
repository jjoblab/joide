package jo.joide.corestore;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rapport de diagnostic copiable (conception §10) : versions (app, Forge,
 * Gradle, briques), état d'installation des modes, journal borné du dernier
 * build, journal des écroulements, **secrets rédigés** — jetons de plus de 16
 * caractères proches de « key/token/secret » remplacés par `[rédigé]`
 * (heuristique de la conception, appliquée à chaque ligne du rapport).
 *
 * <p>Pur JVM : la coquille assemble les valeurs réelles et appelle
 * {@link #render}. Bouton « Copier » + partage côté Android.</p>
 */
public final class DiagnosticReport {

    /** Versions et états fournis par la coquille (jamais inventés ici). */
    public record Versions(String app, String forge, String gradle, String briques) {}

    /** État d'un mode pour le rapport. */
    public record ModeState(String mode, String installState) {}

    private final Versions versions;
    private final List<ModeState> modes;
    private final String lastBuildLog;
    private final List<CrashJournal.Entry> crashes;

    public DiagnosticReport(Versions versions, List<ModeState> modes, String lastBuildLog,
            List<CrashJournal.Entry> crashes) {
        this.versions = versions;
        this.modes = List.copyOf(modes);
        this.lastBuildLog = lastBuildLog == null ? "" : lastBuildLog;
        this.crashes = List.copyOf(crashes);
    }

    /** Le rapport complet, copiable — chaque ligne passe par la rédaction des secrets. */
    public String render() {
        StringBuilder report = new StringBuilder("Rapport de diagnostic JoIDE\n\n");
        report.append("Versions :\n");
        report.append("- App : ").append(versions.app()).append('\n');
        report.append("- Forge : ").append(versions.forge()).append('\n');
        report.append("- Gradle : ").append(versions.gradle()).append('\n');
        report.append("- Briques : ").append(versions.briques()).append('\n');
        report.append('\n').append("État d'installation des modes :\n");
        for (ModeState mode : modes) {
            report.append("- ").append(mode.mode()).append(" : ")
                    .append(mode.installState()).append('\n');
        }
        report.append('\n').append("Journal borné du dernier build :\n");
        appendBounded(report, lastBuildLog, 2_000, true);
        report.append('\n').append("Journal des écroulements :\n");
        if (crashes.isEmpty()) {
            report.append("(aucun)\n");
        } else {
            crashes.forEach(c -> report.append(redact(c.line())).append('\n'));
        }
        return report.toString();
    }

    /** Ajoute un journal borné (la queue compte : les erreurs sont à la fin). */
    private static void appendBounded(StringBuilder report, String log, int maxChars,
            boolean keepTail) {
        if (log.isBlank()) {
            report.append("(vide)\n");
            return;
        }
        String bounded = log.length() <= maxChars ? log
                : keepTail ? "… " + log.substring(log.length() - maxChars)
                : log.substring(0, maxChars) + " …";
        bounded.lines().forEach(l -> report.append(redact(l)).append('\n'));
    }

    // ---- Rédaction des secrets (heuristique conception §10) ----------------

    private static final Pattern SECRET = Pattern.compile(
            "(?i)(key|token|secret|authorization|bearer)[\\s=:\"'-]{0,3}([A-Za-z0-9_\\-./+]{17,})\"?");

    /**
     * Rédige les secrets d'une ligne : tout jeton de plus de 16 caractères
     * proche d'un mot « key/token/secret » devient {@code [rédigé]}. Le nom du
     * champ reste (diagnostique) ; la valeur part (confidentialité).
     */
    public static String redact(String line) {
        if (line == null || line.isEmpty()) {
            return line;
        }
        Matcher matcher = SECRET.matcher(line);
        StringBuilder out = new StringBuilder();
        int last = 0;
        while (matcher.find()) {
            matcher.appendReplacement(out, Matcher.quoteReplacement(
                    matcher.group(1) + "=[rédigé]"));
        }
        matcher.appendTail(out);
        return out.toString();
    }
}
