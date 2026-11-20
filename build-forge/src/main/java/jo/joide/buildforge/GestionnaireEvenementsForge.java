package jo.joide.buildforge;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import jo.joide.workbench.*;

/**
 * Traduction des événements du moteur Forge ({@code jo.forge.api.BuildEvent}) vers
 * les événements du port ({@code EvenementBuild}) et état d'installation local
 * (délégué à l'installateur de Forge — {@code jo.forge.setup}).
 *
 * <p>Classe volontairement découplée des classes Android : testable sur JVM avec un
 * double du moteur.</p>
 */
final class GestionnaireEvenementsForge {

    private final GestionnaireInstallationForge installation =
            new GestionnaireInstallationForge();

    EtatInstallation etatInstallation() { return installation.etat(); }

    CompletableFuture<BuildSession> lancerBuild(BuildRequest requete) {
        // Le lancement réel du moteur (EngineClient.launch + DeviceVmLauncher/app_process)
        // est câblé côté Android (L3-L5) : le bac à sable ne peut pas le vérifier.
        return CompletableFuture.failedFuture(new UnsupportedOperationException(
                "Lancement du moteur Forge : câblage Android (voir limites §4)"));
    }
}
