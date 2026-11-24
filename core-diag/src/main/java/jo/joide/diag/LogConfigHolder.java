package jo.joide.diag;

/**
 * Détenteur de la configuration de journalisation, lisible à chaud par le
 * moteur et le sink fichier sans dépendance circulaire entre eux (portage
 * CodeIDE, ADR-021). La valeur initiale vient du type de build (debug →
 * DEBUG, release → INFO côté coquille) ; le réglage utilisateur mis à jour
 * à l'exécution remplace la valeur — un unique point de bascule, effet
 * immédiat sur les entrées suivantes, sans interruption du pipeline.
 */
public final class LogConfigHolder {

    private volatile LogConfig current;

    /**
     * @param initial configuration initiale (jamais null)
     */
    public LogConfigHolder(LogConfig initial) {
        if (initial == null) {
            throw new IllegalArgumentException("La configuration initiale ne peut pas être null.");
        }
        this.current = initial;
    }

    /** Lit la configuration courante (immuable). */
    public LogConfig read() {
        return current;
    }

    /**
     * Remplace la configuration active — effet immédiat sur les entrées
     * suivantes.
     *
     * @param config nouvelle configuration (null refusé)
     */
    public void update(LogConfig config) {
        if (config == null) {
            return;
        }
        this.current = config;
    }
}
