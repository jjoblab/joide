package jo.joide.buildforge;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import jo.forge.client.VmLauncher;
import jo.joide.workbench.BuildBackend;
import jo.joide.workbench.BuildEvent;
import jo.joide.workbench.BuildRequest;
import jo.joide.workbench.BuildSession;
import jo.joide.workbench.Capabilities;
import jo.joide.workbench.InstallState;
import jo.joide.workbench.WorkMode;

/**
 * Backend Forge : adapte le client du moteur Forge ({@code jo.forge.client}) au port
 * {@link BuildBackend} (conception §4). L'installation du moteur réutilise le canal
 * signé et l'installateur de Forge (module {@code setup}).
 *
 * <p>Limites affichées honnêtement (capacités) : AAR et ViewBinding non supportés
 * tant que Forge ne les livre pas (docs/DEMANDES-FORGE.md F-05/F-06) ; les composants
 * aapt2 et plateformes ne sont pas publiés par Forge en v0.2.x (F-02).</p>
 *
 * <p>La racine ForgeHome (appareil : {@code filesDir/forge}) et le lanceur de VM
 * ({@code DeviceVmLauncher} sur appareil) sont fournis par la coquille Android ;
 * en test JVM, un lanceur {@code JavaVmLauncher} et le moteur factice suffisent.</p>
 */
public final class ForgeBackend implements BuildBackend {

    private final ForgeEngineManager engineManager;

    /**
     * Backend Forge sur un ForgeHome et un lanceur donnés.
     *
     * @param forgeRoot racine ForgeHome (appareil : {@code filesDir/forge}).
     * @param launcher  lanceur du moteur (appareil : {@code DeviceVmLauncher}).
     */
    public ForgeBackend(Path forgeRoot, VmLauncher launcher) {
        this.engineManager = new ForgeEngineManager(forgeRoot, launcher);
    }

    @Override
    public WorkMode mode() {
        return WorkMode.FORGE;
    }

    @Override
    public Capabilities capabilities() {
        return new Capabilities(true, false, false, false, false, List.of(
                "AAR non supportés (demande F-05)",
                "ViewBinding non supporté (demande F-06)",
                "Composants Android (aapt2, plateformes) non publiés en v0.2.x (F-02)"));
    }

    @Override
    public InstallState installState() {
        return engineManager.installState();
    }

    @Override
    public CompletableFuture<BuildSession> build(BuildRequest request) {
        // Le bus d'événements de l'atelier est injecté par la coquille (L5) ; ici un
        // journal brut par défaut évite de perdre les événements avant câblage.
        return engineManager.startBuild(request,
                event -> { /* atelier : sections Console/Problèmes (L5) */ });
    }

    /**
     * Lance un build avec le consommateur d'événements de l'atelier (câblage L5).
     *
     * @param request  requête de build (racine = dossier contenant forge.json).
     * @param consumer consommateur des événements traduits.
     */
    public CompletableFuture<BuildSession> build(BuildRequest request,
            Consumer<BuildEvent> consumer) {
        return engineManager.startBuild(request, consumer);
    }

    /** Installation du moteur depuis le canal signé (assistant des modes, L2/L3). */
    public CompletableFuture<Void> installEngine(String abi, Consumer<Long> progress) {
        return engineManager.installFromChannel(abi, progress);
    }
}
