package jo.joide.buildgradle;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import jo.joide.workbench.Diagnostic;
import jo.joide.workbench.EvenementBuild;

/**
 * Analyse de la sortie Gradle (ADR-008) : parse des diagnostics de compilation
 * depuis {@code --console=plain} (locale fixe). Le script d'initialisation fournit,
 * lui, la progression et les états de tâches (données structurées) ; ce parseur ne
 * couvre que la partie que le listener ne donne pas (diagnostics fichier:ligne).
 *
 * <p>Format couvert (constaté stable) : {@code chemin:ligne:colonne: erreur: message}
 * (javac) et {@code chemin: (ligne, colonne): error: message} (plugin Android).
 * Tout le reste est du journal brut.</p>
 */
public final class AnalyseurSortieGradle {

    private static final Pattern P_JAVAC = Pattern.compile(
            "^(\\S+?):(\\d+):\\s*(?:(\\d+):\\s*)?(error|warning|note)\\s*:\\s*(.+)$");
    private static final Pattern P_AGD = Pattern.compile(
            "^(\\S+?):\\s*\\((\\d+),\\s*(\\d+)\\):\\s*(error|warning)\\s*:\\s*(.+)$");

    private static final Pattern P_TACHE = Pattern.compile("^> Task (\\S+)$");

    /**
     * Traduit une ligne de sortie en événement (diagnostic ou journal).
     *
     * @param ligne ligne brute de la sortie Gradle.
     * @return l'événement traduit, jamais nul (journal par défaut).
     */
    public static EvenementBuild analyser(String ligne) {
        Matcher m = P_JAVAC.matcher(ligne);
        if (m.matches()) {
            return new EvenementBuild.DiagnosticRecu(new Diagnostic(
                    severite(m.group(4)), relativiser(m.group(1)),
                    Integer.parseInt(m.group(2)),
                    m.group(3) == null ? 0 : Integer.parseInt(m.group(3)),
                    null, m.group(5), "gradle-javac"));
        }
        m = P_AGD.matcher(ligne);
        if (m.matches()) {
            return new EvenementBuild.DiagnosticRecu(new Diagnostic(
                    severite(m.group(4)), relativiser(m.group(1)),
                    Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)),
                    null, m.group(5), "gradle-android"));
        }
        return new EvenementBuild.Journal(0, EvenementBuild.Journal.Niveau.INFO, ligne);
    }

    /** Détection (simplifiée) de la fin en échec d'un build Gradle. */
    public static boolean echecConstater(List<String> lignes) {
        return lignes.stream().anyMatch(l -> l.startsWith("FAILURE: Build failed"));
    }

    private static Diagnostic.Severite severite(String mot) {
        return switch (mot) {
            case "error" -> Diagnostic.Severite.ERREUR;
            case "warning" -> Diagnostic.Severite.AVERTISSEMENT;
            default -> Diagnostic.Severite.INFO;
        };
    }

    /** Le chemin absolu du projet n'a pas de valeur pour l'atelier : relatif si possible. */
    private static String relativiser(String chemin) {
        int idx = chemin.lastIndexOf("app/");
        if (chemin.startsWith("/") && idx > 0) return chemin.substring(idx);
        return chemin;
    }

    private AnalyseurSortieGradle() {}
}
