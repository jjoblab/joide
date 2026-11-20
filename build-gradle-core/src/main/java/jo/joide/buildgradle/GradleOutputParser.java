package jo.joide.buildgradle;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import jo.joide.workbench.BuildEvent;
import jo.joide.workbench.Diagnostic;

/**
 * Analyse de la sortie Gradle (ADR-008) : parse des diagnostics de compilation
 * depuis {@code --console=plain} (locale fixe). Le script d'initialisation fournit,
 * lui, la progression et les états de tâches (données structurées) ; ce parseur ne
 * couvre que la partie que le listener ne donne pas (diagnostics fichier:ligne).
 *
 * <p>Formats couverts (constatés stables) : {@code chemin:ligne: colonne? erreur: message}
 * (javac) et {@code chemin: (ligne, colonne): erreur: message} (plugin Android).
 * Tout le reste est du journal brut.</p>
 */
public final class GradleOutputParser {

    private static final Pattern JAVAC_LINE = Pattern.compile(
            "^(\\S+?):(\\d+):\\s*(?:(\\d+):\\s*)?(error|warning|note)\\s*:\\s*(.+)$");
    private static final Pattern AGP_LINE = Pattern.compile(
            "^(\\S+?):\\s*\\((\\d+),\\s*(\\d+)\\):\\s*(error|warning)\\s*:\\s*(.+)$");

    private GradleOutputParser() {}

    /**
     * Traduit une ligne de sortie en événement (diagnostic ou journal).
     *
     * @param line ligne brute de la sortie Gradle.
     * @return l'événement traduit, jamais nul (journal par défaut).
     */
    public static BuildEvent parse(String line) {
        Matcher m = JAVAC_LINE.matcher(line);
        if (m.matches()) {
            return new BuildEvent.DiagnosticEvent(new Diagnostic(
                    severity(m.group(4)), relativize(m.group(1)),
                    Integer.parseInt(m.group(2)),
                    m.group(3) == null ? 0 : Integer.parseInt(m.group(3)),
                    null, m.group(5), "gradle-javac"));
        }
        m = AGP_LINE.matcher(line);
        if (m.matches()) {
            return new BuildEvent.DiagnosticEvent(new Diagnostic(
                    severity(m.group(4)), relativize(m.group(1)),
                    Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)),
                    null, m.group(5), "gradle-android"));
        }
        return new BuildEvent.Log(0, BuildEvent.Log.Level.INFO, line);
    }

    /** Détection de la fin en échec d'un build Gradle. */
    public static boolean isBuildFailure(List<String> lines) {
        return lines.stream().anyMatch(l -> l.startsWith("FAILURE: Build failed"));
    }

    private static Diagnostic.Severity severity(String word) {
        return switch (word) {
            case "error" -> Diagnostic.Severity.ERROR;
            case "warning" -> Diagnostic.Severity.WARNING;
            default -> Diagnostic.Severity.INFO;
        };
    }

    /** Le chemin absolu du projet n'a pas de valeur pour l'atelier : relatif si possible. */
    private static String relativize(String path) {
        int idx = path.lastIndexOf("app/");
        if (path.startsWith("/") && idx > 0) {
            return path.substring(idx);
        }
        return path;
    }
}
