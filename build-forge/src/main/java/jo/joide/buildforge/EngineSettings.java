package jo.joide.buildforge;

/**
 * Réglages du runtime de build (lot L10, d'après la page « Build Runtime » de la
 * référence — REF-130) : **vrais réglages, bornés par les décisions du moteur**.
 *
 * <ul>
 *   <li><b>Tas du moteur</b> ({@code heapMbOverride}) : 0 = automatique (la
 *       décision Forge v0.2.15+ : 1024 Mo, 256 Mo sous 2,5 Go de RAM) ; sinon la
 *       valeur choisie, bornée [256, 1024] — une glissière honnête ne propose que
 *       ce que le moteur accepte.</li>
 *   <li><b>Mode basse mémoire</b> : AUTO (le seuil réel décide), FORCÉ ou
 *       DÉSACTIVÉ — borne le tas automatique.</li>
 * </ul>
 *
 * <p>La page constate ce qui n'est pas réglable : le moteur Forge EST un
 * processus séparé (toujours), la médiation de conflits du moteur est NEWEST
 * (v0.2.28) — des cartes de constat, pas des réglages menteurs.</p>
 */
public record EngineSettings(int heapMbOverride, LowMemory mode) {

    /** Mode basse mémoire. */
    public enum LowMemory { AUTO, FORCÉ, DÉSACTIVÉ }

    /** Tas maximal accepté (Mo) — le dexage d'une fermeture Compose demande 1024. */
    public static final int TAS_MAX = 1024;

    /** Tas minimal accepté (Mo). */
    public static final int TAS_MIN = 256;

    /** Réglages par défaut : tout automatique. */
    public static EngineSettings auto() {
        return new EngineSettings(0, LowMemory.AUTO);
    }

    /**
     * Le tas réellement appliqué au moteur.
     *
     * @param totalRamMb RAM totale de l'appareil (0 : inconnue — le mode AUTO
     *                   prend alors le tas plein, prudent pour les tests).
     * @return le tas en Mo (le choix explicite est borné, jamais hors clous).
     */
    public int resolveHeapMb(long totalRamMb) {
        if (heapMbOverride > 0) {
            return Math.max(TAS_MIN, Math.min(TAS_MAX, heapMbOverride));
        }
        boolean basseMémoire = switch (mode) {
            case FORCÉ -> true;
            case DÉSACTIVÉ -> false;
            case AUTO -> totalRamMb > 0
                    && totalRamMb < ForgeEngineManager.LOW_MEMORY_THRESHOLD_MB;
        };
        return basseMémoire ? ForgeEngineManager.HEAP_LOW_MEMORY_MB
                : ForgeEngineManager.HEAP_MB;
    }
}
