package jo.joide.corestore;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import jo.joide.diag.CrashReportSummary;
import jo.joide.diag.DeviceInfo;
import jo.joide.diag.LogEntry;

/**
 * Rapport de diagnostic copiable (conception §10, enrichi ADR-021) :
 * versions (app, Forge, Gradle, briques), état d'installation des modes,
 * journal borné du dernier build, <b>résumés des rapports de plantage</b>
 * (système complet de core-diag : trace, filons, appareil), <b>queue du
 * journal applicatif</b> (pipeline ADR-021) et photographie de l'appareil —
 * <b>secrets rédigés</b> : jetons de plus de 16 caractères proches de
 * « key/token/secret » remplacés par {@code [rédigé]} (heuristique de la
 * conception, appliquée à chaque ligne du rapport ; les entrées du journal
 * applicatif sont déjà expurgées à l'écriture par core-diag).
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
    private final List<CrashReportSummary> crashes;
    private final List<LogEntry> journalTail;
    private final DeviceInfo device;

    public DiagnosticReport(Versions versions, List<ModeState> modes, String lastBuildLog,
            List<CrashReportSummary> crashes, List<LogEntry> journalTail, DeviceInfo device) {
        this.versions = versions;
        this.modes = List.copyOf(modes);
        this.lastBuildLog = lastBuildLog == null ? "" : lastBuildLog;
        this.crashes = crashes == null ? List.of() : List.copyOf(crashes);
        this.journalTail = journalTail == null ? List.of() : List.copyOf(journalTail);
        this.device = device;
    }

    /** Le rapport complet, copiable — chaque ligne passe par la rédaction des secrets. */
    public String render() {
        StringBuilder report = new StringBuilder("Rapport de diagnostic JoIDE\n\n");
        report.append("Versions :\n");
        report.append("- App : ").append(versions.app()).append('\n');
        report.append("- Forge : ").append(versions.forge()).append('\n');
        report.append("- Gradle : ").append(versions.gradle()).append('\n');
        report.append("- Briques : ").append(versions.briques()).append('\n');
        if (device != null) {
            report.append('\n').append("Appareil :\n");
            report.append("- Modèle : ").append(device.manufacturer()).append(' ')
                    .append(device.model()).append('\n');
            report.append("- Android : ").append(device.androidVersion())
                    .append(" (API ").append(device.apiLevel()).append(")\n");
            report.append("- Mémoire : ").append(device.freeMemoryBytes() / (1024 * 1024))
                    .append(" / ").append(device.maxMemoryBytes() / (1024 * 1024))
                    .append(" Mo utilisables\n");
        }
        report.append('\n').append("État d'installation des modes :\n");
        for (ModeState mode : modes) {
            report.append("- ").append(mode.mode()).append(" : ")
                    .append(mode.installState()).append('\n');
        }
        report.append('\n').append("Journal borné du dernier build :\n");
        appendBounded(report, lastBuildLog, 2_000, true);
        report.append('\n').append("Rapports de plantage (les plus récents d'abord) :\n");
        if (crashes.isEmpty()) {
            report.append("(aucun)\n");
        } else {
            for (CrashReportSummary crash : crashes) {
                report.append("- ").append(crash.type()).append(" · ")
                        .append(crash.exceptionClassName()).append(" : ")
                        .append(redact(crash.shortMessage()))
                        .append(crash.reviewed() ? "" : " [non consulté]")
                        .append('\n');
            }
        }
        report.append('\n').append("Journal applicatif (queue bornée) :\n");
        if (journalTail.isEmpty()) {
            report.append("(vide)\n");
        } else {
            for (LogEntry entry : journalTail) {
                report.append(entry.level()).append('/').append(entry.tag()).append(": ")
                        .append(redact(entry.message())).append('\n');
            }
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
