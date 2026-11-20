package jo.joide.workbench;

/**
 * Demande de build adressée au backend du mode actif.
 *
 * @param buildId     identifiant du build, non nul.
 * @param projectRoot racine du projet, non nulle.
 * @param clean       vrai pour un build propre (répertoires de sortie vidés).
 */
public record BuildRequest(String buildId, String projectRoot, boolean clean) {}
