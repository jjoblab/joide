package jo.joide.workbench;

import java.util.List;

/**
 * Demande de build adressée au backend du mode actif.
 *
 * @param buildId      identifiant du build, non nul.
 * @param projectRoot  racine du projet, non nulle.
 * @param clean        vrai pour un build propre (répertoires de sortie vidés).
 * @param consoleLevel niveau de console demandé au MOTEUR (Forge v0.2.26,
 *                     protocole 4) : sévérité minimale des entrées de journal
 *                     émises sur le canal — l'équivalent des {@code -q/-i/-d}
 *                     de Gradle, un argument du build, pas un filtre d'affichage.
 *                     {@code null} → {@code LIFECYCLE} (la console de build
 *                     classique : lignes de tâches, verdict, avertissements,
 *                     erreurs). Le mode Gradle l'ignore aujourd'hui (terminal).
 * @param tasks        cible du build (Forge v0.2.27, protocole 5 — D-22) : les
 *                     tâches demandées, l'équivalent des arguments de
 *                     {@code gradlew} ({@code :app:compileJava assemble}). La
 *                     clôture des dépendances se calcule côté MOTEUR ; un nom
 *                     inconnu échoue proprement avec les candidats. {@code null}
 *                     ou vide : tout le graphe du projet, la forme historique.
 *                     Le mode Gradle l'ignore aujourd'hui (terminal).
 */
public record BuildRequest(String buildId, String projectRoot, boolean clean,
        BuildEvent.Log.Level consoleLevel, List<String> tasks) {

    /** Niveau par défaut : la console de build classique (ligne de vie Gradle). */
    public static final BuildEvent.Log.Level NIVEAU_PAR_DÉFAUT =
            BuildEvent.Log.Level.LIFECYCLE;

    /**
     * Construit la requête avec le niveau de console et la cible explicites.
     */
    public BuildRequest {
        if (consoleLevel == null) {
            consoleLevel = NIVEAU_PAR_DÉFAUT;
        }
        tasks = tasks == null ? List.of() : List.copyOf(tasks);
    }

    /**
     * Forme au niveau seul (protocole 4, la cible est le graphe complet) —
     * compatibilité des appelants et des tests d'avant le protocole 5.
     *
     * @param buildId     identifiant du build.
     * @param projectRoot racine du projet.
     * @param clean       build propre demandé.
     * @param consoleLevel niveau de console demandé (null → LIFECYCLE).
     */
    public BuildRequest(String buildId, String projectRoot, boolean clean,
            BuildEvent.Log.Level consoleLevel) {
        this(buildId, projectRoot, clean, consoleLevel, null);
    }

    /**
     * Forme historique à trois champs (niveau par défaut : {@code LIFECYCLE}) —
     * compatibilité des appelants et des tests d'avant le protocole 4.
     *
     * @param buildId    identifiant du build.
     * @param projectRoot racine du projet.
     * @param clean      build propre demandé.
     */
    public BuildRequest(String buildId, String projectRoot, boolean clean) {
        this(buildId, projectRoot, clean, NIVEAU_PAR_DÉFAUT, null);
    }
}
