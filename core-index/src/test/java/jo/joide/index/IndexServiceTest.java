package jo.joide.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import jo.joide.index.IndexService.ClassHit;
import jo.joide.index.IndexService.Scope;
import jo.joide.index.IndexService.Stats;
import jo.joide.index.IndexService.SymbolHit;
import jo.joide.outline.Outline;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Banc du service d'index (L10) : pass à froid deux phases, réutilisation par
 * empreintes (fichier inchangé non relu, jar inchangé non rescanné), re-index
 * d'un seul fichier, annulation (index utilisable mais NON prêt — requêtes
 * vides), recherche de symboles et de classes, persistance relue.
 */
class IndexServiceTest {

    @TempDir
    Path projet;

    @Test
    void passÀFroidPuisRéutilisation() throws Exception {
        Path sources = écrireSource("Point", "class Point { int x; int y; }");
        Path jar = fabriquerJar("com.exemple", "Outil", "Aide");
        IndexService index = new IndexService(projet);
        CompletableFuture<IndexService.Status> pass = index.ensureUpToDate(
                new Scope(List.of(sources), List.of(jar)),
                Executors.newSingleThreadExecutor(), null);
        IndexService.Status status = pass.get(10, TimeUnit.SECONDS);
        assertTrue(status.ready());
        assertFalse(status.building());
        assertEquals(1.0, status.fraction());
        Stats stats = status.stats();
        assertEquals(1, stats.jarsScannés());
        assertEquals(0, stats.jarsRéutilisés());
        assertEquals(1, stats.fichiersIndexés());
        assertEquals(0, stats.fichiersRéutilisés());
        assertEquals(2, stats.classes()); // com.exemple.Outil + com.exemple.Aide

        // Re-pass : tout est réutilisé (empreintes identiques).
        CompletableFuture<IndexService.Status> second = new IndexService(projet)
                .ensureUpToDate(new Scope(List.of(sources), List.of(jar)),
                        Executors.newSingleThreadExecutor(), null);
        Stats relu = second.get(10, TimeUnit.SECONDS).stats();
        assertEquals(0, relu.jarsScannés());
        assertEquals(1, relu.jarsRéutilisés());
        assertEquals(0, relu.fichiersIndexés());
        assertEquals(1, relu.fichiersRéutilisés());
    }

    @Test
    void rechercheSymbolesEtClasses() throws Exception {
        Path sources = écrireSource("Point", """
                public class Point {
                    private int x;
                    public void deplace(int dx, int dy) { }
                }
                """);
        Path jar = fabriquerJar("com.exemple", "Outil", "Aide");
        IndexService index = new IndexService(projet);
        index.ensureUpToDate(new Scope(List.of(sources), List.of(jar)),
                Executors.newSingleThreadExecutor(), null).get(10, TimeUnit.SECONDS);
        List<SymbolHit> hits = index.searchSymbols("poi", 10);
        assertEquals(1, hits.size());
        assertEquals("Point", hits.get(0).name());
        assertEquals(Outline.Kind.CLASS, hits.get(0).kind());
        assertTrue(hits.get(0).file().endsWith("Point.java"));
        assertEquals(1, hits.get(0).line());
        // Le préfixe matche la méthode aussi :
        assertEquals(1, index.searchSymbols("depla", 10).size());
        List<ClassHit> classes = index.searchClasses("out", 10);
        assertEquals(1, classes.size());
        assertEquals("com.exemple.Outil", classes.get(0).fqn());
        assertEquals(jar, classes.get(0).jar());
        // Préfixe de FQN :
        assertEquals(2, index.searchClasses("com.exemple", 10).size());
    }

    @Test
    void requêtesVidesAvantPrêt() {
        IndexService index = new IndexService(projet);
        assertTrue(index.searchSymbols("qu", 10).isEmpty());
        assertTrue(index.searchClasses("qu", 10).isEmpty());
        assertFalse(index.status().ready());
    }

    @Test
    void reIndexUnSeulFichier() throws Exception {
        Path sources = écrireSource("Avant", "class Avant { }");
        IndexService index = new IndexService(projet);
        index.ensureUpToDate(new Scope(List.of(sources), List.of()),
                Executors.newSingleThreadExecutor(), null).get(10, TimeUnit.SECONDS);
        assertTrue(index.status().ready());
        // Sauvegarde : le fichier change de contenu — seule SES entrées bougent.
        Path fichier = sources.resolve("Avant.java");
        Files.writeString(fichier, """
                class Avant {
                    void nouveau() { }
                }
                """, StandardCharsets.UTF_8);
        index.reindexSource(fichier, Files.readString(fichier));
        List<SymbolHit> hits = index.searchSymbols("nouveau", 10);
        assertEquals(1, hits.size());
        // Le pass suivant ne relit que ce fichier :
        Stats stats = new IndexService(projet).ensureUpToDate(
                new Scope(List.of(sources), List.of()),
                Executors.newSingleThreadExecutor(), null)
                .get(10, TimeUnit.SECONDS).stats();
        assertEquals(0, stats.fichiersIndexés());
        assertEquals(1, stats.fichiersRéutilisés());
    }

    @Test
    void annulationLaisseNonPrêt() throws Exception {
        Path sources = écrireSource("Gros", "class Gros { void a(){} void b(){} }");
        IndexService index = new IndexService(projet);
        CompletableFuture<IndexService.Status> pass = index.ensureUpToDate(
                new Scope(List.of(sources), List.of()),
                Executors.newSingleThreadExecutor(), null);
        index.cancel(); // coupe immédiatement
        pass.get(10, TimeUnit.SECONDS);
        // Un pass coupé n'est PAS prêt : les requêtes rendent vide (honnête).
        assertFalse(index.status().ready());
        assertTrue(index.searchSymbols("gros", 10).isEmpty());
    }

    @Test
    void jarAbsentEtCorrompuSautés() throws Exception {
        Path sources = écrireSource("Solo", "class Solo {}");
        Path corrompu = projet.resolve("faux.jar");
        Files.writeString(corrompu, "ceci n'est pas un zip");
        IndexService index = new IndexService(projet);
        IndexService.Status status = index.ensureUpToDate(
                new Scope(List.of(sources),
                        List.of(projet.resolve("absent.jar"), corrompu)),
                Executors.newSingleThreadExecutor(), null).get(10, TimeUnit.SECONDS);
        assertTrue(status.ready()); // pass complet malgré les artefacts morts
        assertEquals(0, status.stats().classes());
    }

    @Test
    void invalidationEtPassAprèsCoupure() throws Exception {
        Path sources = écrireSource("X", "class X {}");
        IndexService index = new IndexService(projet);
        index.ensureUpToDate(new Scope(List.of(sources), List.of()),
                Executors.newSingleThreadExecutor(), null).get(10, TimeUnit.SECONDS);
        assertTrue(index.status().ready());
        index.invalidate();
        assertFalse(index.status().ready());
        assertTrue(index.searchSymbols("x", 10).isEmpty());
        // Après invalidation + nouveau pass, l'index est reconstruit :
        index.ensureUpToDate(new Scope(List.of(sources), List.of()),
                Executors.newSingleThreadExecutor(), null).get(10, TimeUnit.SECONDS);
        assertEquals(1, index.searchSymbols("x", 10).size());
    }

    @Test
    void lePassCèdeAuBuild() throws Exception {
        Path sources = écrireSource("Patience", "class Patience {}");
        jo.joide.workbench.BuildActivity activity = new jo.joide.workbench.BuildActivity();
        activity.set(true); // un build tourne
        IndexService index = new IndexService(projet);
        CompletableFuture<IndexService.Status> pass = index.ensureUpToDate(
                new Scope(List.of(sources), List.of()),
                Executors.newSingleThreadExecutor(), activity);
        Thread.sleep(150);
        assertFalse(pass.isDone()); // le pass attend (borné 60 s)
        activity.set(false); // build fini → le pass part
        assertTrue(pass.get(10, TimeUnit.SECONDS).ready());
    }

    // ---- Montage -------------------------------------------------------------

    private Path écrireSource(String classe, String corps) throws IOException {
        Path sources = projet.resolve("src/main/java");
        Files.createDirectories(sources);
        Files.writeString(sources.resolve(classe + ".java"),
                corps + "\n", StandardCharsets.UTF_8);
        return sources;
    }

    private Path fabriquerJar(String paquet, String... classes) throws IOException {
        Path jar = projet.resolve("biblio.jar");
        try (JarOutputStream out = new JarOutputStream(
                Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry(paquet.replace('.', '/') + "/"));
            out.closeEntry();
            for (String classe : classes) {
                out.putNextEntry(new JarEntry(
                        paquet.replace('.', '/') + "/" + classe + ".class"));
                out.write(new byte[] {0, 0, 0, 0}); // contenu illisible mais entrée nominative
                out.closeEntry();
            }
        }
        return jar;
    }
}
