package jo.joide.workbench;

/**
 * Diagnostic structuré exploitable par l'éditeur (saut à la ligne) et par la section
 * Problèmes de l'atelier, quel que soit le mode (Forge ou Gradle).
 *
 * @param severity sévérité (info, avertissement, erreur).
 * @param file     chemin relatif au projet, éventuellement nul.
 * @param line     ligne 1-based, 0 si inconnue.
 * @param column   colonne 1-based, 0 si inconnue.
 * @param code     code machine (ex. {@code javac:cannot-find-symbol}), éventuellement nul.
 * @param message  message humain, jamais nul.
 * @param tool     outil émetteur (ex. {@code java-compiler}), jamais nul.
 */
public record Diagnostic(Severity severity, String file, int line, int column,
                         String code, String message, String tool) {

    /** Sévérité d'un diagnostic. */
    public enum Severity { INFO, WARNING, ERROR }
}
