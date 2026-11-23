package jo.joide.workbench;

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
 */
public record BuildRequest(String buildId, String projectRoot, boolean clean,
        BuildEvent.Log.Level consoleLevel) {

    /** Niveau par défaut : la console de build classique (ligne de vie Gradle). */
    public static final BuildEvent.Log.Level NIVEAU_PAR_DÉFAUT =
            BuildEvent.Log.Level.LIFECYCLE;

    /**
     * Construit la requête avec le niveau de console explicite.
     */
    public BuildRequest {
        if (consoleLevel == null) {
            consoleLevel = NIVEAU_PAR_DÉFAUT;
        }
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
        this(buildId, projectRoot, clean, NIVEAU_PAR_DÉFAUT);
    }
}
