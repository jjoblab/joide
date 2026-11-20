package jo.joide.buildforge;

import java.util.concurrent.CompletableFuture;
import jo.joide.workbench.BuildRequest;
import jo.joide.workbench.BuildSession;
import jo.joide.workbench.InstallState;

/**
 * Gestion du moteur Forge : état d'installation (installateur du module
 * {@code jo.forge.setup} : manifeste signé, reprise, installation atomique) et
 * lancement des builds ({@code jo.forge.client.EngineClient}).
 *
 * <p>Classe volontairement découplée des classes Android : testable sur JVM avec un
 * double du moteur.</p>
 */
final class ForgeEngineManager {

    private final ForgeInstallState installState = new ForgeInstallState();

    InstallState installState() {
        return installState.current();
    }

    CompletableFuture<BuildSession> startBuild(BuildRequest request) {
        // Le lancement réel du moteur (EngineClient.launch + DeviceVmLauncher/app_process)
        // est câblé côté Android (L3-L5) : le bac à sable ne peut pas le vérifier.
        return CompletableFuture.failedFuture(new UnsupportedOperationException(
                "Lancement du moteur Forge : câblage Android (voir limites §4)"));
    }
}
