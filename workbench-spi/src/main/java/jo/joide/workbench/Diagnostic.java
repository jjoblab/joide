package jo.joide.workbench;

/**
 * Diagnostic structuré exploitable par l'éditeur (saut à la ligne) et par la section
 * Problèmes de l'atelier, quel que soit le mode (Forge ou Gradle).
 *
 * @param severite sévérité (info, avertissement, erreur).
 * @param fichier  chemin relatif au projet, éventuellement nul.
 * @param ligne    ligne 1-based, 0 si inconnue.
 * @param colonne  colonne 1-based, 0 si inconnue.
 * @param code     code machine (ex. {@code javac:cannot-find-symbol}), éventuellement nul.
 * @param message  message humain, jamais nul.
 * @param outil    outil émetteur (ex. {@code java-compiler}), jamais nul.
 */
public record Diagnostic(Severite severite, String fichier, int ligne, int colonne,
                         String code, String message, String outil) {

    /** Sévérité d'un diagnostic. */
    public enum Severite { INFO, AVERTISSEMENT, ERREUR }
}
