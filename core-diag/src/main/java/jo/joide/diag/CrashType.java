package jo.joide.diag;

/**
 * Type de plantage enregistré dans un rapport (portage CodeIDE §5.8, ADR-021).
 *
 * <p>La distinction guide le diagnostic : une {@link #EXCEPTION} possède une
 * chaîne d'exceptions Java exploitable, un {@link #ANR} provient d'un fil
 * principal bloqué, un {@link #NATIVE} correspond à un signal bas niveau
 * détecté après coup via {@code ApplicationExitInfo} (API 30+, côté coquille).</p>
 */
public enum CrashType {
    /** Exception Java non interceptée, capturée par le gestionnaire. */
    EXCEPTION,
    /** « Application Not Responding » — détecté au démarrage suivant. */
    ANR,
    /** Plantage natif (signal, tombestone) — détecté au démarrage suivant. */
    NATIVE
}
