package jo.joide.workbench;

/** Demande de build adressée au backend du mode actif. */
public record BuildRequest(String buildId, String racineProjet, boolean nettoyage) {}
