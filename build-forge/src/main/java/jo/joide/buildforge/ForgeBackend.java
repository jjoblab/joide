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
import jo.joide.workbench.TaskInfo;
import jo.joide.workbench.WorkMode;

/**
 * Backend Forge : adapte le client du moteur Forge ({@code jo.forge.client}) au port
 * {@link BuildBackend} (conception §4). L'installation du moteur réutilise le canal
 * signé et l'installateur de Forge (module {@code setup}).
 *
 * <p>Capacités honnêtes (Forge v0.2.24) : AAR et ViewBinding SONT livrés
 * (demandes F-05/F-06 closes), les composants aapt2 et plateformes sont publiés
 * (F-02 close). Restent des limites réelles : la signature n'utilise que la clé
 * de débogage par projet et la minimisation R8 n'est pas proposée.</p>
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
        this.engineManager = new ForgeEngineManager(forgeRoot, launcher,
                ForgeEngineManager.DeviceProfile.jvm());
    }

    /**
     * Backend Forge avec le profil de l'appareil (ABI, RAM — le moteur en a besoin
     * pour ses composants natifs et son budget mémoire).
     *
     * @param forgeRoot racine ForgeHome.
     * @param launcher  lanceur du moteur.
     * @param device    profil de l'appareil hôte.
     */
    public ForgeBackend(Path forgeRoot, VmLauncher launcher,
            ForgeEngineManager.DeviceProfile device) {
        this.engineManager = new ForgeEngineManager(forgeRoot, launcher, device);
    }

    @Override
    public WorkMode mode() {
        return WorkMode.FORGE;
    }

    @Override
    public Capabilities capabilities() {
        // Forge v0.2.24 : AAR et ViewBinding livrés (F-05/F-06 closes), composants
        // aapt2 + plateformes publiés (F-02 close). Limites restantes, affichées.
        return new Capabilities(true, false, true, true, false, List.of(
                "Signature : clé de débogage par projet (la clé de production reste à venir)",
                "Minimisation R8 non proposée (débogage uniquement)",
                "Les scripts Gradle ne sont pas exécutés (mode Gradle = terminal, à venir)"));
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

    /**
     * Le répertoire des tâches d'un projet (Forge v0.2.27+, protocole 5 — D-22) :
     * le MOTEUR énumère ce qui peut tourner, l'atelier affiche. Une simple
     * LECTURE — le moteur est jetable, la question s'arrête avec lui.
     */
    @Override
    public CompletableFuture<List<TaskInfo>> listTasks(String projectRoot) {
        return engineManager.listTasks(projectRoot);
    }

    /**
     * Le répertoire des tâches d'un SOUS-ENSEMBLE de modules (Forge v0.2.28+,
     * protocole 6 — D-23, l'équivalent de {@code gradlew :core:tasks}) : le moteur
     * filtre le graphe et y dérive les agrégats propres de chaque module. Une
     * simple LECTURE — même moteur jetable, même honnêteté.
     */
    @Override
    public CompletableFuture<List<TaskInfo>> listTasks(String projectRoot,
            List<String> modules) {
        return engineManager.listTasks(projectRoot, modules);
    }

    /** Gestionnaire du moteur (assistant d'installation : manifeste, cache, états). */
    public ForgeEngineManager engineManager() {
        return engineManager;
    }
}
