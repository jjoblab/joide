package jo.joide.app.lsp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import jo.codeide.lsp.host.ConfigurationLsp;
import jo.codeide.lsp.host.EtatIndexation;
import jo.codeide.lsp.host.LecteurStatistiquesFichierDisque;
import jo.codeide.lsp.host.PhaseIndexation;
import jo.codeide.lsp.host.SessionLsp;
import jo.joide.lsp.ForgeClasspath;
import jo.joide.lsp.LanguageClientBridge;
import kotlinx.coroutines.Dispatchers;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.TextDocumentItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * L'intégration RÉELLE des moteurs code-lsp v1.15.0 sur JVM (LOT-11) — la
 * preuve mécanique que l'écran d'indexation vit : le classpath Forge nourrit
 * la session, le pass d'index CONSTRUIT (phase visible, artefacts comptés)
 * puis rend PRÊT, le serveur Java initialisé publie de vrais diagnostics
 * ecj par le pont client. Le même bytecode sert l'appareil.
 */
class SessionLspIntegrationTest {

    @TempDir
    Path dossier;

    /** Un projet Forge minimal : un module, une source fautive, une plateforme
     *  factice (l'index lit les NOMS des entrées du jar — un zip aux entrées
     *  nommées suffit, aucun bytecode réel). */
    private void projetMinimal() throws Exception {
        Files.writeString(dossier.resolve("forge.json"), """
                { "name": "demo", "modules": [
                    { "name": "app", "type": "application", "language": "java" }
                ] }
                """);
        Path sources = dossier.resolve("app").resolve("src").resolve("main")
                .resolve("java");
        Files.createDirectories(sources);
        Files.writeString(sources.resolve("A.java"), """
                package demo;

                public class A {
                    int appelInconnu() {
                        return methodeInexistante();
                    }
                }
                """);
        Path classpath = dossier.resolve("build").resolve("app")
                .resolve("resolved");
        Files.createDirectories(classpath);
        Files.writeString(classpath.resolve("classpath.json"),
                "{\"version\":2,\"compile\":[],\"runtime\":[]}");
    }

    /**
     * Un « android.jar » factice : QUATRE MILLE noms de classes plates —
     * assez pour que le pass d'index dure au-delà d'un sondage (le vrai
     * android.jar en porte des dizaines de milliers, la phase y est
     * visible des secondes durant).
     */
    private Path plateformeFactice() throws Exception {
        Path jar = dossier.resolve("platform-34").resolve("android.jar");
        Files.createDirectories(jar.getParent());
        try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(
                Files.newOutputStream(jar))) {
            for (int i = 0; i < 4000; i++) {
                zip.putNextEntry(new java.util.zip.ZipEntry(
                        "android/factice/P" + i + ".class"));
                zip.closeEntry();
            }
        }
        return jar;
    }

    @Test
    void lePassDIndexSeConstruitPuisRendPrêtEtLesDiagnosticsArrivent()
            throws Exception {
        projetMinimal();
        Path androidJar = plateformeFactice();
        Path index = Files.createTempDirectory("joide-lsp-test");
        try (SessionLsp session = new SessionLsp(
                dossier.toFile(),
                index.toFile(),
                Dispatchers.getIO(),
                new LecteurStatistiquesFichierDisque(),
                null,
                androidJar.toFile(),
                null,
                new ConfigurationLsp())) {

            // La sync Forge : LE déclencheur du pass d'index (l'écran).
            session.appliquerClasspath(ForgeClasspath.of(dossier, androidJar));

            // Le pass : CONSTRUCTION observée (faits/total réels), puis PRÊT.
            boolean constructionVue = false;
            EtatIndexation état = null;
            long départ = System.currentTimeMillis();
            while (System.currentTimeMillis() - départ < 60_000) {
                état = session.getEtatIndexation().getValue();
                if (état.getPhase() == PhaseIndexation.CONSTRUCTION
                        && état.getTotal() >= 1) {
                    constructionVue = true;
                }
                if (état.getPhase() == PhaseIndexation.PRET) {
                    break;
                }
                Thread.sleep(5);
            }
            assertTrue(constructionVue,
                    "le pass d'index doit être OBSERVÉ en construction");
            assertNotNull(état);
            assertEquals(PhaseIndexation.PRET, état.getPhase(),
                    "l'index rend prêt (état: " + état + ")");
            assertTrue(état.getBilanDernierPassage() != null
                    && état.getBilanDernierPassage().getArtefactsTotal() >= 1,
                    "la plateforme factice compte comme artefact");

            // Le serveur Java : branché au pont, initialisé, un document fautif
            // ouvert — les diagnostics ecj arrivent par le pont client.
            LanguageClientBridge pont = new LanguageClientBridge();
            ConcurrentLinkedQueue<List<Diagnostic>> reçus =
                    new ConcurrentLinkedQueue<>();
            pont.onDiagnostics((uri, diagnostics) -> reçus.add(diagnostics));
            org.eclipse.lsp4j.services.LanguageServer serveur = session.serveur();
            // Le client se pose par la convention du moteur (connect maison).
            LanguageClientBridge.brancher(serveur, pont);
            InitializeParams paramètres = new InitializeParams();
            paramètres.setRootUri(dossier.toUri().toString());
            paramètres.setCapabilities(
                    new org.eclipse.lsp4j.ClientCapabilities());
            serveur.initialize(paramètres).get(30, TimeUnit.SECONDS);
            serveur.initialized(new org.eclipse.lsp4j.InitializedParams());
            Path source = dossier.resolve("app").resolve("src").resolve("main")
                    .resolve("java").resolve("A.java");
            serveur.getTextDocumentService().didOpen(
                    new DidOpenTextDocumentParams(new TextDocumentItem(
                            source.toUri().toString(), "java", 1,
                            Files.readString(source))));

            départ = System.currentTimeMillis();
            while (reçus.isEmpty() && System.currentTimeMillis() - départ < 20_000) {
                Thread.sleep(100);
            }
            assertTrue(!reçus.isEmpty(),
                    "le moteur doit publier des diagnostics (debounce 50 ms)");
            List<Diagnostic> diagnostics = reçus.peek();
            assertTrue(diagnostics.stream().anyMatch(d -> d.getMessage() != null
                    && d.getMessage().toLowerCase().contains("inexistante")),
                    "l'erreur de méthode inconnue se dit : "
                            + diagnostics.stream().map(Diagnostic::getMessage)
                            .reduce("", (a, b) -> a + " | " + b));
        }
    }
}
