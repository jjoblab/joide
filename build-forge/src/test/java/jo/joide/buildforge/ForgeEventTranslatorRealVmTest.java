package jo.joide.buildforge;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import jo.forge.api.BuildEvent;
import jo.forge.api.TaskDescription;
import jo.forge.api.TasksResponse;
import jo.forge.client.EngineClient;
import jo.forge.client.JavaVmLauncher;
import jo.forge.client.VmSpec;
import jo.joide.workbench.BuildEvent.Log;
import jo.joide.workbench.BuildEvent.TaskState;
import jo.joide.workbench.Diagnostic;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Intégration du traducteur d'événements avec le MOTEUR FICTIF réel
 * ({@code --fake}) dans un vrai processus séparé ({@code java}) : la chaîne
 * complète Forge → traduction → JoIDE est validée de bout en bout, exactement
 * comme sur appareil (mêmes trames stdio, seule la commande diffère).
 */
class ForgeEventTranslatorRealVmTest {

    /** Classpath courant du test : contient api, client et engine (le factice). */
    private static String classpath() {
        return System.getProperty("java.class.path");
    }

    /** Spécification du moteur factice (test d'intégration client de Forge). */
    private static VmSpec fakeEngine() {
        return new VmSpec(List.of(classpath()),
                "jo.forge.engine.bootstrap.EngineMain", 256, List.of("--fake"));
    }

    @Test
    @Timeout(60)
    void translatedEventsFollowTheForgeSequence() throws Exception {
        List<jo.joide.workbench.BuildEvent> translated = new CopyOnWriteArrayList<>();
        CompletableFuture<jo.joide.workbench.BuildEvent.Finished> finished =
                new CompletableFuture<>();
        EngineClient engine = EngineClient.launch(new JavaVmLauncher(), fakeEngine(),
                "joide-0.1.0",
                event -> {
                    jo.joide.workbench.BuildEvent joideEvent =
                            ForgeEventTranslator.translate(event);
                    translated.add(joideEvent);
                    if (joideEvent instanceof jo.joide.workbench.BuildEvent.Finished f) {
                        finished.complete(f);
                    }
                },
                () -> { });

        engine.startBuild(new jo.forge.api.BuildRequest("build-ok", "/projets/demo",
                new jo.forge.api.BuildOptions(2, false, false, false, 4096, "x86_64")))
                .get(10, TimeUnit.SECONDS);

        jo.joide.workbench.BuildEvent.Finished fin = finished.get(30, TimeUnit.SECONDS);
        assertTrue(fin.success(), "le build factice réussit, la fin traduite le dit");
        // Séquence traduite : journal, tâches réussies, progression, fin — le contrat
        // exact que l'atelier consomme (conception §4 : un seul bus, deux modes).
        assertTrue(translated.stream().anyMatch(e -> e instanceof Log));
        assertTrue(translated.stream().anyMatch(e ->
                e instanceof TaskState t && t.status() == TaskState.Status.SUCCEEDED));
        assertTrue(translated.stream().anyMatch(
                e -> e instanceof jo.joide.workbench.BuildEvent.Progress));
        // La fin est bien le DERNIER événement (garantie du contrat JoIDE).
        assertEquals(jo.joide.workbench.BuildEvent.Finished.class,
                translated.get(translated.size() - 1).getClass());

        engine.shutdown().get(10, TimeUnit.SECONDS);
        assertEquals(0, engine.awaitExit());
    }

    @Test
    @Timeout(60)
    void failedBuildKeepsDiagnosticsAndFailedTask() throws Exception {
        List<jo.joide.workbench.BuildEvent> translated = new CopyOnWriteArrayList<>();
        CompletableFuture<jo.joide.workbench.BuildEvent.Finished> finished =
                new CompletableFuture<>();
        EngineClient engine = EngineClient.launch(new JavaVmLauncher(), fakeEngine(),
                "joide-0.1.0",
                event -> {
                    jo.joide.workbench.BuildEvent joideEvent =
                            ForgeEventTranslator.translate(event);
                    translated.add(joideEvent);
                    if (joideEvent instanceof jo.joide.workbench.BuildEvent.Finished f) {
                        finished.complete(f);
                    }
                },
                () -> { });

        // fail-9 : le factice échoue sur demo:compile avec un diagnostic structuré.
        engine.startBuild(new jo.forge.api.BuildRequest("fail-9", "/projets/demo",
                new jo.forge.api.BuildOptions(2, false, false, false, 4096, "x86_64")))
                .get(10, TimeUnit.SECONDS);

        jo.joide.workbench.BuildEvent.Finished fin = finished.get(30, TimeUnit.SECONDS);
        assertTrue(!fin.success());
        assertEquals("demo:compile", fin.failedTask());
        // Le diagnostic bloquant est traduit avec position et sévérité (saut éditeur).
        assertTrue(translated.stream().anyMatch(e ->
                e instanceof jo.joide.workbench.BuildEvent.DiagnosticEvent d
                        && d.diagnostic().severity() == Diagnostic.Severity.ERROR),
                "le diagnostic d'erreur du moteur doit être traduit");
        assertInstanceOf(jo.joide.workbench.BuildEvent.Finished.class,
                translated.get(translated.size() - 1));

        engine.shutdown().get(10, TimeUnit.SECONDS);
        assertEquals(0, engine.awaitExit());
    }

    @Test
    @Timeout(60)
    void leRépertoireParSousEnsembleSeDemandeAuMoteur() throws Exception {
        // Protocole 6 (Forge v0.2.28, D-23) : la vue d'un SOUS-ENSEMBLE de modules
        // (l'équivalent de gradlew :app:tasks) — le moteur filtre le graphe et y
        // dérive les agrégats PROPRES du module (app:assemble, app:clean), jamais
        // l'assemble global redéfini en silence. La chaîne est la même que sur
        // appareil : vrai processus, vraies trames, seul le moteur est factice.
        EngineClient engine = EngineClient.launch(new JavaVmLauncher(), fakeEngine(),
                "joide-0.1.0", event -> { }, () -> { });
        try {
            assertTrue(engine.protocolVersion() >= 6,
                    "le moteur factice parle le protocole 6 : " + engine.protocolVersion());

            // La vue COMPLÈTE garde ses agrégats globaux (assemble, build, clean).
            TasksResponse complet = engine.tasks("/projets/demo")
                    .get(10, TimeUnit.SECONDS);
            List<String> vueComplète = complet.tasks().stream()
                    .map(TaskDescription::name).toList();
            assertTrue(vueComplète.containsAll(List.of("assemble", "build", "clean")),
                    "la vue complète expose le cycle de vie global : " + vueComplète);

            // La vue du module app : SES concrets + SES agrégats — pas de cycle
            // de vie global redéfini.
            TasksResponse vue = engine.tasks("/projets/demo", List.of("app"))
                    .get(10, TimeUnit.SECONDS);
            List<String> noms = vue.tasks().stream()
                    .map(TaskDescription::name).toList();
            assertEquals(List.of("app:jar", "app:compileJava",
                    "app:resolveDependencies", "app:assemble", "app:clean"), noms,
                    "la vue sous-ensemble : concrets du module + SES agrégats");
            TaskDescription assembleApp = vue.tasks().stream()
                    .filter(t -> t.name().equals("app:assemble")).findFirst().orElseThrow();
            assertTrue(assembleApp.lifecycle(),
                    "l'agrégat de module est marqué cycle de vie, honnêtement");
            assertEquals(List.of("app:jar"), assembleApp.dependencies(),
                    "app:assemble agrège les sorties du module SEULEMENT");

            // Un module inconnu : refus honnête, le message porte le remède.
            try {
                engine.tasks("/projets/demo", List.of("zork")).get(10, TimeUnit.SECONDS);
                fail("un module inconnu doit être refusé");
            } catch (Exception échecAttendu) {
                String message = échecAttendu.getCause() != null
                        ? échecAttendu.getCause().getMessage()
                        : String.valueOf(échecAttendu);
                assertTrue(message.contains("zork"),
                        "le refus cite le module fautif : " + message);
            }
        } finally {
            // Moteur jetable : arrêt poli, tolerant — la question est déjà répondue.
            try {
                engine.shutdown().get(10, TimeUnit.SECONDS);
            } catch (Exception mortPolieIgnorée) {
                engine.kill();
            }
        }
    }
}
