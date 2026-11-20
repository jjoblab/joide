package jo.joide.terminal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import jo.joide.workbench.BuildEvent;
import jo.joide.workbench.BuildRequest;
import jo.joide.workbench.Capabilities;
import jo.joide.workbench.InstallState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lot L6 (J3) : registre de sessions multi-sessions (vives et mortes visibles,
 * fermeture explicite, aperçu borné) ; installateur de l'environnement Gradle
 * (« installé = vérifié », reprise d'une installation interrompue) ; backend
 * Gradle réel (sortie → événements, annulation).
 */
class TerminalCoreTest {

    @TempDir
    Path folder;

    // ---- Registre de sessions (J3 : terminal réel multi-sessions) -----------

    /** Coquille scriptée : sortie contrôlée, fin explicite. */
    private static final class ScriptedShell implements TerminalSessionRegistry.Shell {
        private final java.util.function.Consumer<String> onOutput;
        private final Runnable onExit;
        private boolean killed;

        ScriptedShell(java.util.function.Consumer<String> onOutput, Runnable onExit) {
            this.onOutput = onOutput;
            this.onExit = onExit;
        }

        @Override public void write(String input) {
            onOutput.accept("$ " + input + "\n");
        }

        @Override public void kill() {
            killed = true;
            onExit.run();
        }

        boolean killed() {
            return killed;
        }
    }

    @Test
    void multipleSessionsLiveTogether() {
        List<String> created = new CopyOnWriteArrayList<>();
        TerminalSessionRegistry registry = new TerminalSessionRegistry(
                (id, cwd, onOutput, onExit) -> {
                    created.add(cwd);
                    return new ScriptedShell(onOutput, onExit);
                });
        String first = registry.newSession("/projets/a", "gradle a");
        String second = registry.newSession("/projets/b", "shell");
        assertEquals(2, registry.summaries().size(),
                "deux sessions simultanées (multi-sessions)");
        assertEquals("/projets/a", created.get(0));
        // Écriture routée vers la bonne session.
        assertTrue(registry.write(second, "ls"));
        assertFalse(registry.write("inconnu", "ls"));
    }

    @Test
    void deadSessionStaysVisibleUntilClosed() {
        List<ScriptedShell> shells = new CopyOnWriteArrayList<>();
        TerminalSessionRegistry registry = new TerminalSessionRegistry(
                (id, cwd, onOutput, onExit) -> {
                    ScriptedShell shell = new ScriptedShell(onOutput, onExit);
                    shells.add(shell);
                    return shell;
                });
        String id = registry.newSession("/home", "shell");
        shells.get(0).kill(); // la session meurt (exit naturel)
        // Morte mais VISIBLE jusqu'à fermeture explicite (règle du tiroir).
        assertFalse(registry.summaries().get(0).alive());
        assertEquals(1, registry.summaries().size());
        registry.close(id);
        assertEquals(0, registry.summaries().size());
    }

    @Test
    void outputPreviewIsBounded() {
        StringBuilder last = new StringBuilder();
        TerminalSessionRegistry registry = new TerminalSessionRegistry(
                (id, cwd, onOutput, onExit) -> new ScriptedShell(onOutput, onExit));
        String id = registry.newSession("/home", "shell bavard");
        registry.write(id, "x".repeat(500));
        // Aperçu borné à 160 caractères (règle du tiroir de CodeIDE reprise).
        assertTrue(registry.summaries().get(0).lastOutputPreview().length() <= 160 + 2);
    }

    // ---- Installateur de l'environnement (installé = vérifié) ---------------

    private Path fakeZip(String entryPrefix, String file) throws IOException {
        Path zip = folder.resolve(entryPrefix + ".zip");
        try (var out = new java.util.zip.ZipOutputStream(Files.newOutputStream(zip))) {
            out.putNextEntry(new java.util.zip.ZipEntry(file));
            out.write("factice".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return zip;
    }

    @Test
    void interruptedInstallationResumesFromVerifiedPhase() throws Exception {
        // Phases BOOTSTRAP et OUTILS vérifiées, puis interruption avant JAVA.
        List<String> verified = new CopyOnWriteArrayList<>();
        GradleEnvironmentInstaller installer = new GradleEnvironmentInstaller(
                folder.resolve("gradle-env"),
                command -> {
                    verified.add(command);
                    // BOOTSTRAP et OUTILS passent, JAVA échoue (interruption simulée).
                    return !command.contains("java");
                });
        assertEquals(null, installer.installedPhase(), "rien d'installé au départ");
        // Source locale : composants factices.
        GradleEnvironmentInstaller.ComponentSource source = name ->
                fakeZip(name, name + ".txt");
        // Interruption : la phase JAVA n'est pas vérifiée → IOException, état OUTILS.
        assertThrows(IOException.class, () -> installer.install(source));
        assertEquals(GradleEnvironmentInstaller.Phase.TOOLS, installer.installedPhase());

        // REPRISE : le vérificateur réussit maintenant (installation complétée),
        // les phases déjà vérifiées ne sont pas re-téléchargées.
        List<String> fetched = new CopyOnWriteArrayList<>();
        GradleEnvironmentInstaller resumed = new GradleEnvironmentInstaller(
                folder.resolve("gradle-env"), command -> true);
        resumed.install(name -> {
            fetched.add(name);
            return fakeZip(name + "-reprise", name + ".txt");
        });
        // BOOTSTRAP et OUTILS étaient déjà vérifiées : seuls JAVA et GRADLE sont
        // re-téléchargées.
        assertEquals(List.of("jdk", "gradle"), fetched,
                "la reprise repart de la dernière phase vérifiée");
        assertEquals(GradleEnvironmentInstaller.Phase.GRADLE, resumed.installedPhase());
        assertEquals(InstallState.INSTALLED, resumed.installState());
    }

    @Test
    void unverifiedPhaseIsRefused() {
        GradleEnvironmentInstaller installer = new GradleEnvironmentInstaller(
                folder.resolve("gradle-env"), command -> false); // rien ne passe
        assertThrows(IOException.class, () ->
                installer.install(name -> {
                    try {
                        return fakeZip(name, "x.txt");
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }));
        try {
            assertEquals(null, installer.installedPhase(),
                    "aucune phase persistée : tout ou rien");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    // ---- Backend Gradle réel (J3 : build → console) -------------------------

    @Test
    void gradleBuildFeedsConsoleWithParsedEvents() throws Exception {
        // Environnement complet (vérificateur trivial) :
        GradleEnvironmentInstaller env = new GradleEnvironmentInstaller(
                folder.resolve("gradle-env"), command -> true);
        env.install(name -> fakeZip(name, "c.txt"));

        List<String> commands = new CopyOnWriteArrayList<>();
        List<String> emittedLines = new CopyOnWriteArrayList<>();
        AtomicInteger cancellationCount = new AtomicInteger();
        GradleRealBackend backend = new GradleRealBackend(env,
                new GradleRealBackend.CommandLauncher() {
                    @Override public int launch(List<String> command, Path cwd,
                            java.util.function.Consumer<String> onLine) throws IOException {
                        commands.add(String.join(" ", command));
                        // Sortie Gradle réaliste : tâche (journal), diagnostic javac, fin.
                        onLine.accept("> Task :app:compileJava");
                        onLine.accept("Main.java:12: warning: dépréciation");
                        emittedLines.add("ligne envoyée");
                        return 0;
                    }
                    @Override public void cancelCurrent() {
                        cancellationCount.incrementAndGet();
                    }
                }, "/projets/demo");

        assertEquals(InstallState.INSTALLED, backend.installState());
        assertTrue(backend.capabilities().aar(),
                "le mode Gradle couvre les projets Android complets (AAR inclus)");

        var received = new CopyOnWriteArrayList<BuildEvent>();
        jo.joide.workbench.BuildSession session = backend.build(
                new BuildRequest("b1", "/projets/demo", false)).get();
        session.subscribe(received::add);
        assertTrue(session.isFinished());
        assertTrue(received.stream().anyMatch(e ->
                e instanceof BuildEvent.DiagnosticEvent d
                        && d.diagnostic().file().equals("Main.java")
                        && d.diagnostic().line() == 12),
                "les diagnostics javac deviennent des événements (parseur ADR-008)");
        assertTrue(received.stream().anyMatch(e ->
                e instanceof BuildEvent.Log l && l.message().contains(":app:compileJava")),
                "les lignes de tâches sont journalisées vers la Console");
        assertTrue(received.get(received.size() - 1) instanceof BuildEvent.Finished fin
                && fin.success(), "fin de build en dernier");
        // La commande réelle de l'environnement installé est bien celle du préfixe.
        assertTrue(commands.get(0).contains("bin/gradle"));
    }

    @Test
    void cancellingGradleBuildKillsTheProcess() throws Exception {
        GradleEnvironmentInstaller env = new GradleEnvironmentInstaller(
                folder.resolve("gradle-env"), command -> true);
        env.install(name -> fakeZip(name, "c.txt"));
        AtomicInteger cancels = new AtomicInteger();
        GradleRealBackend backend = new GradleRealBackend(env,
                new GradleRealBackend.CommandLauncher() {
                    @Override public int launch(List<String> command, Path cwd,
                            java.util.function.Consumer<String> onLine) {
                        return 1; // tué : code non nul
                    }
                    @Override public void cancelCurrent() {
                        cancels.incrementAndGet();
                    }
                }, "/projets/demo");
        var session = backend.build(new BuildRequest("b2", "/projets/demo", false)).get();
        session.cancel();
        assertEquals(1, cancels.get(), "l'annulation détruit le processus Gradle");
    }
}
