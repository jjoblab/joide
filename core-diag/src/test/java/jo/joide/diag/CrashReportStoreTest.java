package jo.joide.diag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CrashReportStoreTest {

    @TempDir
    Path tmp;

    private final java.util.concurrent.atomic.AtomicLong clock =
            new java.util.concurrent.atomic.AtomicLong(300_000L);
    private final CrashReportFactory factory = new CrashReportFactory(clock::get);

    private CrashReport report(String message) {
        RuntimeException boom = new RuntimeException(message);
        return factory.create(boom, "main", new CrashAppInfo("0.1.0", 1L, "debug", "jo.joide.app"),
                DeviceInfo::unknown, "session", List.of(), "Atelier", 100L, false);
    }

    private Path crashes() {
        return tmp.resolve("crashes");
    }

    @Test
    void sauveEtRelitUnRapportComplet() throws Exception {
        CrashReportStore store = new CrashReportStore(crashes());
        CrashReport original = report("échec du moteur");
        store.save(original);

        CrashReport read = store.get(original.id());
        assertNotNull(read);
        assertEquals(original, read, "aller-retour intégral sans perte");
        assertEquals("java.lang.RuntimeException", read.exception().className());
        assertEquals("échec du moteur", read.exception().message());
    }

    @Test
    void reduceProgressivementJusquAUPlafond() throws Exception {
        CrashReportStore store = new CrashReportStore(crashes());
        List<LogEntry> breadcrumbs = new java.util.ArrayList<>();
        for (int i = 0; i < LoggingLimits.MAX_BREADCRUMBS; i++) {
            breadcrumbs.add(new LogEntry(i, "s", LogLevel.INFO, "Tag", "main",
                    "fil très long " + "x".repeat(200), null));
        }
        RuntimeException boom = new RuntimeException("message " + "y".repeat(5_000));
        CrashReport big = factory.create(boom, "main",
                new CrashAppInfo("0.1.0", 1L, "debug", "jo.joide.app"),
                DeviceInfo::unknown, "session", breadcrumbs, "Atelier", 100L, false);

        store.save(big);
        for (Path file : Files.list(crashes()).toList()) {
            assertTrue(Files.size(file) <= CrashLimits.MAX_REPORT_BYTES,
                    "le rapport réduit tient dans le plafond : " + file);
        }
        // Le rapport relu garde l'essentiel — la réduction ne casse pas la structure.
        CrashReport read = store.get(big.id());
        assertNotNull(read);
        assertEquals("java.lang.RuntimeException", read.exception().className());
    }

    @Test
    void resumeEtTemoinDeConsultation() throws Exception {
        CrashReportStore store = new CrashReportStore(crashes());
        CrashReport first = report("premier");
        clock.incrementAndGet();
        CrashReport second = report("second");
        store.save(first);
        store.save(second);

        List<CrashReportSummary> summaries = store.listSummaries();
        assertEquals(2, summaries.size());
        assertEquals("second", summaries.get(0).shortMessage(), "plus récent en tête");
        assertTrue(summaries.stream().noneMatch(CrashReportSummary::reviewed));

        assertTrue(store.hasUnreviewed());
        assertTrue(store.markReviewed(first.id()), "le témoin est posé à côté du rapport");
        assertTrue(store.hasUnreviewed(), "le second rapport attend toujours sa lecture");
        assertTrue(store.listSummaries().stream()
                .anyMatch(summary -> summary.id().equals(first.id()) && summary.reviewed()));
        assertTrue(store.markReviewed(second.id()));
        assertFalse(store.hasUnreviewed(), "les deux lus : plus rien en attente");
    }

    @Test
    void retentionAuPlusMaxRapports() throws Exception {
        CrashReportStore store = new CrashReportStore(crashes());
        for (int i = 0; i < CrashLimits.MAX_REPORTS + 3; i++) {
            store.save(report("plantage " + i));
        }
        assertEquals(CrashLimits.MAX_REPORTS, store.listSummaries().size(),
                "les plus anciens partent");
    }

    @Test
    void suppressionUnParUnEtToutEnsemble() throws Exception {
        CrashReportStore store = new CrashReportStore(crashes());
        CrashReport one = report("un");
        CrashReport two = report("deux");
        store.save(one);
        store.save(two);
        store.markReviewed(one.id());

        assertTrue(store.delete(one.id()));
        assertNull(store.get(one.id()));
        assertEquals(1, store.listSummaries().size());
        assertEquals(1, store.deleteAll());
        assertEquals(0, store.listSummaries().size());
        assertEquals(0, Files.list(crashes()).count(), "témoins et restes nettoyés");
    }

    @Test
    void fichierPourriIgnoreSansEchouer() throws Exception {
        CrashReportStore store = new CrashReportStore(crashes());
        store.save(report("sain"));
        Files.writeString(crashes().resolve("9999999999999-pourri.json"),
                "{ pas du json", StandardCharsets.UTF_8);
        List<CrashReportSummary> summaries = store.listSummaries();
        assertEquals(1, summaries.size(), "le fichier pourru est ignoré, le bon reste");
        assertNull(store.get("inconnu"));
    }

    @Test
    void ecritureAtomiqueSansFichierTemporaireRestant() throws Exception {
        CrashReportStore store = new CrashReportStore(crashes());
        store.save(report("atomique"));
        for (Path file : Files.list(crashes()).toList()) {
            assertTrue(!file.getFileName().toString().endsWith(".tmp"),
                    "aucun restant temporaire");
        }
    }
}
