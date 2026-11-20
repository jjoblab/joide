package jo.joide.buildforge;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import jo.forge.api.BuildStarted;
import jo.forge.client.EngineClient;
import jo.forge.client.VmLauncher;
import jo.forge.client.VmSpec;
import jo.forge.setup.install.ForgeHome;
import jo.forge.setup.install.InstalledState;
import jo.joide.workbench.BuildRequest;
import jo.joide.workbench.BuildSession;
import jo.joide.workbench.InstallState;

/**
 * Gestion du moteur Forge : installation ({@link ForgeInstaller}) et lancement des
 * builds ({@code EngineClient.launch}). La commande du moteur (classe
 * {@code EngineMain}, {@code FORGE_ENGINE_DIR}, classpath {@code engine.jar} +
 * {@code lib/*.jar} triés) est reprise telle quelle du module {@code app} de Forge
 * ({@code EngineSession}) plutôt que réinventée.
 *
 * <p>Côté appareil, le lanceur est {@code DeviceVmLauncher} (app_process, ART) ;
 * en test JVM, le lanceur par défaut et le moteur factice {@code --fake}.</p>
 */
public final class ForgeEngineManager {

    /** Classe principale du moteur Forge (constat EngineSession, Forge v0.2.6). */
    static final String ENGINE_MAIN = "jo.forge.engine.bootstrap.EngineMain";

    /** Variable d'environnement attendue par le moteur (répertoire installé). */
    static final String ENV_ENGINE_DIR = "FORGE_ENGINE_DIR";

    /** Tas du moteur (Mo) : 512 par défaut, 256 en mémoire basse (constat Forge). */
    private static final int DEFAULT_HEAP_MB = 512;

    private final ForgeInstaller installer;
    private final VmLauncher launcher;
    private final AtomicBoolean installing = new AtomicBoolean();
    private volatile InstalledState installed;

    /**
     * Gestionnaire sur un ForgeHome (appareil : {@code filesDir/forge} ; JVM : dossier
     * de test). Le lanceur JVM pur suffit aux tests ; l'appareil fournit le sien.
     */
    public ForgeEngineManager(Path forgeRoot, VmLauncher launcher) {
        this.installer = new ForgeInstaller(forgeRoot);
        this.launcher = launcher;
        this.installed = installer.currentState().orElse(null);
    }

    /** Installe (ou répare) le moteur depuis le canal signé ; l'état est rafraîchi. */
    public CompletableFuture<Void> installFromChannel(String abi, Consumer<Long> progress) {
        if (!installing.compareAndSet(false, true)) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Installation déjà en cours"));
        }
        return CompletableFuture.runAsync(() -> {
            try {
                var manifest = installer.downloadManifest();
                installer.install(manifest, abi,
                        (bytesReceived, bytesTotal) -> progress.accept(bytesTotal));
                installed = installer.currentState().orElse(null);
            } catch (IOException e) {
                throw new RuntimeException("Installation Forge : " + e.getMessage(), e);
            } finally {
                installing.set(false);
            }
        }, Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "forge-install");
            t.setDaemon(true);
            return t;
        }));
    }

    /** État d'installation pour le contrat BuildBackend. */
    public InstallState installState() {
        if (installing.get()) {
            return InstallState.IN_PROGRESS;
        }
        return installer.installState();
    }

    /**
     * Lance un build : démarre le moteur (processus séparé), soumet la requête,
     * traduit et relaie les événements vers l'atelier jusqu'à la fin du build.
     *
     * @param request  requête JoIDE (racine = dossier contenant forge.json).
     * @param consumer consommateur des événements traduits (atelier).
     */
    public CompletableFuture<BuildSession> startBuild(BuildRequest request,
            Consumer<jo.joide.workbench.BuildEvent> consumer) {
        if (installed == null) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "Moteur Forge non installé : lancez l'installation d'abord"));
        }
        return CompletableFuture.supplyAsync(() -> {
            try {
                VmSpec spec = specFor(installer.home(), installed, DEFAULT_HEAP_MB);
                // Le bus Forge → JoIDE : traduction à la volée, mort → fin de session.
                EngineClient engine = EngineClient.launch(launcher, spec, "joide-0.1.0",
                        event -> consumer.accept(ForgeEventTranslator.translate(event)),
                        () -> consumer.accept(new jo.joide.workbench.BuildEvent.Finished(
                                false, null, 0, "Le moteur Forge s'est arrêté")));
                engine.startBuild(ForgeBuildSession.forgeRequest(
                        request.buildId(), request.projectRoot(), request.clean())).get();
                return (BuildSession) new ForgeBuildSession(request.buildId(), engine);
            } catch (Exception e) {
                throw new RuntimeException("Lancement du build Forge : " + e.getMessage(), e);
            }
        }, Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "forge-build-" + request.buildId());
            t.setDaemon(true);
            return t;
        }));
    }

    /** Fabrique la spécification du moteur (classpath du répertoire installé). */
    static VmSpec specFor(ForgeHome home, InstalledState state, int heapMb) {
        List<String> classpath = new ArrayList<>();
        Path engineDir = home.engineDir(state.engineVersion());
        Path engineJar = engineDir.resolve("engine.jar");
        if (Files.isRegularFile(engineJar)) {
            classpath.add(engineJar.toString());
        }
        Path lib = engineDir.resolve("lib");
        if (Files.isDirectory(lib)) {
            // lib/*.jar triés (reproductibilité) — disposition EngineSession de Forge.
            try (var stream = Files.list(lib)) {
                stream.map(Path::getFileName).map(Object::toString)
                        .filter(n -> n.endsWith(".jar")).sorted()
                        .forEach(n -> classpath.add(lib.resolve(n).toString()));
            } catch (IOException e) {
                throw new IllegalStateException("Lecture de lib/ du moteur : " + e.getMessage(), e);
            }
        }
        return new VmSpec(classpath, ENGINE_MAIN, heapMb, List.of(),
                Map.of(ENV_ENGINE_DIR, engineDir.toString()));
    }
}
