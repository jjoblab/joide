package jo.joide.workbench;

import java.util.concurrent.CompletableFuture;

/**
 * Port unique de build consommé par l'atelier (Console, Problèmes, actions Exécuter),
 * quel que soit le mode actif (conception §4). Deux implémentations : ForgeBackend
 * (module build-forge), GradleBackend (module build-gradle).
 */
public interface BuildBackend {

    ModeTravail mode();

    Capacites capacites();

    EtatInstallation etatInstallation();

    /** Lance un build ; les événements arrivent par la session. */
    CompletableFuture<BuildSession> build(BuildRequest requete);
}
