package jo.joide.terminal;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.List;
import java.util.ArrayList;
import jo.joide.workbench.BuildBackend;
import jo.joide.workbench.BuildRequest;
import jo.joide.workbench.BuildSession;
import jo.joide.workbench.Capabilities;
import jo.joide.workbench.InstallState;
import jo.joide.workbench.WorkMode;

/**
 * Backend Gradle réel (lot L6, J3) : le build Gradle tourne dans l'environnement
 * installé ({@code bin/gradle} du préfixe bootstrap, conception §4 — « Gradle
 * lance les builds dans le terminal »), la sortie est analysée par le parseur
 * hérité du lot L0 ({@code GradleOutputParser}, ADR-008) et alimente le même
 * contrat que le mode Forge.
 *
 * <p>Sur JVM de test, le lanceur de commande est injecté : la commande réelle
 * {@code <prefix>/bin/gradle -q <tâches> --project-dir <racine>} devient une
 * commande scriptée — le câblage de sortie est identique.</p>
 */
public final class GradleRealBackend implements BuildBackend {

    /** Lanceur d'une commande de l'environnement (production : ProcessBuilder). */
    public interface CommandLauncher {
        /** Lance la commande ; le consommateur reçoit chaque ligne de sortie (stdout+stderr) ; retourne le code de sortie. */
        int launch(List<String> command, Path workingDirectory,
                java.util.function.Consumer<String> onLine) throws IOException;

        /** Tue la commande en cours (annulation du build : le processus Gradle est détruit). */
        void cancelCurrent();
    }

    private final GradleEnvironmentInstaller environment;
    private final CommandLauncher launcher;
    private final String projectRoot;

    /**
     * Backend sur un environnement installé.
     *
     * @param environment installateur de l'environnement Gradle (état vérifié).
     * @param launcher     lanceur de commandes (appareil : ProcessBuilder ; test : script).
     * @param projectRoot  racine du projet ouvert (null : accueil sans projet).
     */
    public GradleRealBackend(GradleEnvironmentInstaller environment,
            CommandLauncher launcher, String projectRoot) {
        this.environment = environment;
        this.launcher = launcher;
        this.projectRoot = projectRoot;
    }

    @Override
    public WorkMode mode() {
        return WorkMode.GRADLE;
    }

    @Override
    public Capabilities capabilities() {
        return new Capabilities(true, true, true, true, true, List.of(
                "Terminal intégré (sessions multiples)",
                "Projets Gradle existants supportés",
                "Environnement complet à télécharger (plus lourd que Forge)"));
    }

    @Override
    public InstallState installState() {
        try {
            return environment.installState();
        } catch (IOException e) {
            // État illisible = cassé (jamais muet : le diagnostic L8 le lit).
            return InstallState.BROKEN;
        }
    }

    @Override
    public CompletableFuture<BuildSession> build(BuildRequest request) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                GradleBuildSession session = new GradleBuildSession(request.buildId(), launcher);
                List<String> command = List.of(
                        environment.prefix().resolve("bin/gradle").toString(),
                        "-q", "build",
                        "--project-dir", request.projectRoot());
                int exitCode = launcher.launch(command,
                        Path.of(request.projectRoot()),
                        line -> session.consumeLine(line));
                session.finish(exitCode);
                return (BuildSession) session;
            } catch (IOException e) {
                throw new RuntimeException("Lancement du build Gradle : " + e.getMessage(), e);
            }
        }, Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "gradle-build");
            t.setDaemon(true);
            return t;
        }));
    }

    /** Session d'un build Gradle : sortie analysée → événements du contrat. */
    static final class GradleBuildSession implements BuildSession {

        private final String id;
        private final CommandLauncher launcher;
        private final List<jo.joide.workbench.BuildEvent> events = new ArrayList<>();
        private final List<java.util.function.Consumer<jo.joide.workbench.BuildEvent>> subscribers =
                new ArrayList<>();
        private volatile boolean finished;

        GradleBuildSession(String id, CommandLauncher launcher) {
            this.id = id;
            this.launcher = launcher;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public void subscribe(java.util.function.Consumer<jo.joide.workbench.BuildEvent> consumer) {
            synchronized (events) {
                events.forEach(consumer);
                subscribers.add(consumer);
            }
        }

        void consumeLine(String line) {
            // Parseur hérité (ADR-008) : journal/tâches/diagnostics depuis la sortie.
            jo.joide.workbench.BuildEvent event =
                    jo.joide.buildgradle.GradleOutputParser.parse(line);
            if (event != null) {
                emit(event);
            }
        }

        void finish(int exitCode) {
            emit(new jo.joide.workbench.BuildEvent.Finished(
                    exitCode == 0, null, 0,
                    exitCode == 0 ? "Build Gradle réussi"
                            : "Build Gradle échoué (code " + exitCode + ")"));
            finished = true;
        }

        private void emit(jo.joide.workbench.BuildEvent event) {
            synchronized (events) {
                events.add(event);
                subscribers.forEach(s -> s.accept(event));
            }
        }

        @Override
        public boolean isFinished() {
            return finished;
        }

        @Override
        public void cancel() {
            // Annulation = destruction du processus Gradle (la fin suivra avec le
            // code de sortie non nul ; jamais d'attente infinie côté atelier).
            launcher.cancelCurrent();
        }
    }
}
