package jo.joide.buildforge;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import jo.joide.workbench.BuildBackend;
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
 */
public final class ForgeBackend implements BuildBackend {

    /** Canal du manifeste du moteur Forge (release GitHub). */
    public static final String MANIFEST_CHANNEL =
            "https://github.com/jjoblab/forge/releases/latest/download/manifest.json";

    private final ForgeEngineManager engineManager = new ForgeEngineManager();

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
        return engineManager.startBuild(request);
    }
}
