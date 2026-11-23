package jo.joide.buildforge;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
 * en test JVM, le lanceur par défaut et le moteur factice {@code --fake}. Le tas
 * suit la décision de Forge v0.2.15+ : 1024 Mo pour le dexage d'une fermeture
 * Compose, 256 Mo en mode basse mémoire (appareils sous 2,5 Go).</p>
 */
public final class ForgeEngineManager {

    /** Classe principale du moteur Forge (constat EngineSession, Forge v0.2.6). */
    static final String ENGINE_MAIN = "jo.forge.engine.bootstrap.EngineMain";

    /** Variable d'environnement attendue par le moteur (répertoire installé). */
    static final String ENV_ENGINE_DIR = "FORGE_ENGINE_DIR";

    /** Tas du moteur (Mo) — décision Forge v0.2.15 : 1024 pour le dexage Compose. */
    static final int HEAP_MB = 1024;

    /** Tas du moteur en mode basse mémoire (Mo) — appareils sous 2,5 Go de RAM. */
    static final int HEAP_LOW_MEMORY_MB = 256;

    /** Seuil de RAM (Mo) sous lequel le mode basse mémoire s'applique (constat Forge). */
    static final long LOW_MEMORY_THRESHOLD_MB = 2500;

    private final ForgeInstaller installer;
    private final VmLauncher launcher;
    private final DeviceProfile device;
    private final AtomicBoolean installing = new AtomicBoolean();
    private volatile InstalledState installed;
    private volatile EngineSettings settings = EngineSettings.auto();

    /**
     * Gestionnaire sur un ForgeHome (appareil : {@code filesDir/forge} ; JVM : dossier
     * de test) avec le profil de l'appareil (ABI, RAM) — le moteur en a besoin pour
     * ses composants natifs et son budget mémoire.
     */
    public ForgeEngineManager(Path forgeRoot, VmLauncher launcher, DeviceProfile device) {
        this.installer = new ForgeInstaller(forgeRoot);
        this.launcher = launcher;
        this.device = device;
        // Réparation au démarrage (Forge v0.2.24) : une installation interrompue
        // (moteur mis à l'écart, jamais remplacé) est rétablie avant tout usage.
        this.installer.recoverInterruptedInstall();
        this.installed = installer.currentState().orElse(null);
    }

    /** Installe (ou répare) le moteur depuis le canal signé ; l'état est rafraîchi. */
    public CompletableFuture<Void> installFromChannel(Consumer<InstallProgress> progress,
            jo.forge.setup.download.CancellationToken cancel) {
        if (!installing.compareAndSet(false, true)) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Installation déjà en cours"));
        }
        return CompletableFuture.runAsync(() -> {
            try {
                var manifest = installer.downloadManifest();
                installer.install(manifest, device.abi(),
                        (received, total) -> {
                            if (progress != null) {
                                progress.accept(new InstallProgress(manifest.engineVersion(),
                                        received, total));
                            }
                        }, cancel);
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

    /** Manifeste vérifié du canal (composants, tailles, version) pour l'assistant. */
    public ChannelManifest downloadManifestForAssistant() throws IOException {
        var manifest = installer.downloadManifest();
        return new ChannelManifest(manifest,
                installer.cachedComponentIds(manifest, device.abi()));
    }

    /** État d'installation pour le contrat BuildBackend. */
    public InstallState installState() {
        if (installing.get()) {
            return InstallState.IN_PROGRESS;
        }
        return installer.installState();
    }

    /** Installateur brut (écran d'installation : cache, vérification, désinstallation). */
    public ForgeInstaller installer() {
        return installer;
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
                VmSpec spec = specFor(installer.home(), installed, heapMb());
                // Le bus Forge → JoIDE : traduction à la volée, mort → fin de session.
                EngineClient engine = EngineClient.launch(launcher, spec, "joide-0.1.0",
                        event -> consumer.accept(ForgeEventTranslator.translate(event)),
                        () -> consumer.accept(new jo.joide.workbench.BuildEvent.Finished(
                                false, null, 0, "Le moteur Forge s'est arrêté")));
                engine.startBuild(ForgeBuildSession.forgeRequest(
                        request.buildId(), request.projectRoot(), request.clean(),
                        request.consoleLevel(), request.tasks(), device)).get();
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

    /**
     * Le répertoire des tâches d'un projet (Forge v0.2.27+, protocole 5 — D-22) :
     * lance un moteur JETABLE, pose la question, s'arrête proprement. Une LECTURE —
     * rien ne s'exécute, aucun événement de build : le moteur énumère ce qui peut
     * tourner (concrets + cycle de vie), l'atelier affiche.
     *
     * <p>Face à un moteur plus ancien (protocole < 5), le client refuse AVANT
     * d'émettre (une trame inconnue tuerait son lecteur) : l'échec porte le
     * remède (mettre le moteur à jour).</p>
     *
     * @param projectRoot racine du projet (dossier contenant forge.json).
     * @return le futur des descripteurs de tâches (traduits pour l'atelier).
     */
    public CompletableFuture<List<jo.joide.workbench.TaskInfo>> listTasks(
            String projectRoot) {
        return listTasks(projectRoot, null);
    }

    /**
     * Le répertoire des tâches d'un SOUS-ENSEMBLE de modules (Forge v0.2.28+,
     * protocole 6 — D-23, l'équivalent de {@code gradlew :core:tasks}) : la même
     * LECTURE par moteur jetable, la question porte en plus les modules — le
     * moteur filtre le graphe et y dérive les agrégats propres de chacun
     * ({@code core:assemble}, {@code core:clean}). Une liste vide ou nulle
     * demande tout le projet (la forme d'avant).
     *
     * <p>Face à un moteur qui parle encore le protocole 5, la liste part quand
     * même (fin de charge, trame bornée — il l'ignore) et la réponse est la vue
     * COMPLÈTE : dégradation sans échec. Un module inconnu est refusé par le
     * moteur avec les modules disponibles (l'échec porte le remède).</p>
     *
     * @param projectRoot racine du projet (dossier contenant forge.json).
     * @param modules     modules demandés (noms sans préfixe) ; vide ou nul :
     *                    tout le projet.
     * @return le futur des descripteurs des tâches du sous-ensemble.
     */
    public CompletableFuture<List<jo.joide.workbench.TaskInfo>> listTasks(
            String projectRoot, List<String> modules) {
        if (installed == null) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "Moteur Forge non installé : lancez l'installation d'abord"));
        }
        return CompletableFuture.supplyAsync(() -> {
            try {
                VmSpec spec = specFor(installer.home(), installed, heapMb());
                EngineClient engine = EngineClient.launch(launcher, spec, "joide-0.1.0",
                        event -> { }, () -> { });
                try {
                    return engine.tasks(projectRoot, modules).get(60, TimeUnit.SECONDS)
                            .tasks().stream()
                            .map(d -> new jo.joide.workbench.TaskInfo(d.name(), d.group(),
                                    d.description(), d.dependencies(), d.lifecycle()))
                            .toList();
                } finally {
                    // Moteur jetable : arrêt poli, tolerant (la réponse est déjà
                    // livrée — la mort propre du moteur n'est pas un échec de la
                    // question).
                    try {
                        engine.shutdown().get(10, TimeUnit.SECONDS);
                    } catch (Exception mortPolieIgnorée) {
                        engine.kill();
                    }
                }
            } catch (Exception e) {
                throw new RuntimeException("Répertoire de tâches Forge : "
                        + e.getMessage(), e);
            }
        }, Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "forge-tâches");
            t.setDaemon(true);
            return t;
        }));
    }

    /** Tas du moteur selon la RAM de l'appareil (décision Forge v0.2.15+). */
    int heapMb() {
        return settings.resolveHeapMb(device.totalRamMb());
    }

    /**
     * Applique les réglages du runtime (page Runtime de build — L10) : tas du
     * moteur borné et mode basse mémoire. Pris en compte au PROCHAIN lancement
     * du moteur (un moteur lancé garde son tas — honnête, comme la référence
     * pour son process séparé).
     */
    public void configure(EngineSettings settings) {
        this.settings = settings == null ? EngineSettings.auto() : settings;
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

    /** Profil de l'appareil hôte (le moteur pur Java ne le devine jamais). */
    public record DeviceProfile(String abi, long totalRamMb) {

        /** Profil de test JVM (aucun composant natif, RAM inconnue). */
        public static DeviceProfile jvm() {
            return new DeviceProfile("all", 0);
        }
    }

    /** Progression d'installation : version du moteur + octets reçus / total. */
    public record InstallProgress(String engineVersion, long bytesReceived, long bytesTotal) {
    }

    /** Manifeste vérifié + composants déjà en cache (assistant d'installation). */
    public record ChannelManifest(
            jo.forge.setup.manifest.EngineManifest manifest,
            java.util.Set<String> cachedComponentIds) {
    }
}
