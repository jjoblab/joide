package jo.joide.ai;

/**
 * Port de build de l'agent (L8, conception §8) : l'agent exécute ses builds
 * **via le BuildBackend du mode actif** — jamais un moteur concurrent. L'hôte
 * adapte ce port au backend réel (Forge ou Gradle) ; les tests l'instancient
 * en mémoire.
 */
public interface BuildRunner {

    /** Résultat borné d'un build (la queue compte : les erreurs sont à la fin). */
    record BuildOutcome(boolean success, String tailLog, int eventCount) {}

    /**
     * Lance un build du projet sur le backend du mode actif.
     *
     * @param projectRoot racine du projet.
     * @return résultat (journal borné à la queue par l'implémentation).
     */
    BuildOutcome build(String projectRoot) throws Exception;
}
