package jo.joide.diag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JsonlLogStoreTest {

    @TempDir
    Path tmp;

    private JsonlLogStore store() {
        return new JsonlLogStore(tmp.resolve("logs"));
    }

    @Test
    void ajouteEtRelitLesEntreesDansLOrdre() throws Exception {
        JsonlLogStore store = store();
        List<String> lines = List.of(
                entry(1L, "INFO", "App", "démarrage"),
                entry(2L, "ERROR", "Build", "échec du moteur"));
        store.appendLines(lines, LoggingLimits.MAX_FILE_BYTES, LoggingLimits.MAX_ARCHIVE_FILES);

        List<LogEntry> read = store.readAll();
        assertEquals(2, read.size());
        assertEquals("démarrage", read.get(0).message());
        assertEquals("échec du moteur", read.get(1).message());
        assertEquals(LogLevel.ERROR, read.get(1).level());
        assertEquals(0, store.skippedLines());
    }

    @Test
    void tourneQuandLaTailleMaximaleSeraitDepassee() throws Exception {
        JsonlLogStore store = store();
        String line = entry(1L, "INFO", "App", "x".repeat(100));
        long small = line.getBytes(StandardCharsets.UTF_8).length + 10;
        store.appendLines(List.of(line), small, 3);
        store.appendLines(List.of(line, line), small, 3);
        store.appendLines(List.of(line), small, 3);

        assertTrue(Files.isRegularFile(tmp.resolve("logs/archive-1.jsonl")),
                "le courant a dû passer en archive à la rotation");
        assertEquals(4, store.readAll().size(), "aucune ligne perdue à la rotation");
    }

    @Test
    void compteLesLignesPourriesSansEchouer() throws Exception {
        JsonlLogStore store = store();
        List<String> lines = new ArrayList<>();
        lines.add("{ ceci n'est pas du json");
        lines.add(entry(5L, "INFO", "App", "ligne saine"));
        store.appendLines(lines, LoggingLimits.MAX_FILE_BYTES, 3);

        List<LogEntry> read = store.readAll();
        assertEquals(1, read.size());
        assertEquals(1, store.skippedLines(), "la ligne pourrie est comptée, pas cachée");
    }

    @Test
    void balayeLaRetentionParAgeEtParNombre() throws Exception {
        JsonlLogStore store = store();
        store.appendLines(List.of(entry(1L, "INFO", "App", "a")), 1, 5);
        Path logs = tmp.resolve("logs");
        // COPIE du courant vers une archive vieillie : le courant reste en place.
        java.nio.file.StandardCopyOption[] options = {};
        Files.copy(logs.resolve("current.jsonl"), logs.resolve("archive-1.jsonl"), options);
        Files.setLastModifiedTime(logs.resolve("archive-1.jsonl"),
                FileTime.from(Instant.now().minusSeconds(10L * 24L * 3600L)));

        store.sweep(System.currentTimeMillis(), 7, 5);
        assertTrue(!Files.exists(logs.resolve("archive-1.jsonl")),
                "l'archive trop vieille part");
        assertTrue(Files.isRegularFile(logs.resolve("current.jsonl")),
                "le courant ne part jamais");
    }

    @Test
    void effaceToutEtMesureLOccupation() throws Exception {
        JsonlLogStore store = store();
        store.appendLines(List.of(entry(1L, "INFO", "App", "a")), 1, 5);
        store.appendLines(List.of(entry(2L, "INFO", "App", "b")), 1, 5);
        JsonlLogStore.DiskStats stats = store.diskStats();
        assertTrue(stats.fileCount() >= 1 && stats.bytes() > 0);
        store.clear();
        assertEquals(0, store.readAll().size());
        assertEquals(0, store.diskStats().fileCount());
    }

    @Test
    void entreeJsonRoundTrip() {
        LogEntry original = new LogEntry(42L, "session-1", LogLevel.WARN, "Moteur",
                "main", "attention é", null);
        String line = LogEntryJson.write(original);
        LogEntry read = LogEntryJson.parse(line);
        assertEquals(original, read);

        FlattenedException exception = FlattenedException
                .from(new IllegalStateException("boom"));
        LogEntry withException = new LogEntry(43L, "session-1", LogLevel.ERROR, "Moteur",
                "worker", "échec", exception);
        LogEntry readBack = LogEntryJson.parse(LogEntryJson.write(withException));
        assertEquals(withException, readBack);
        assertEquals("boom", readBack.exception().message());

        assertNull(LogEntryJson.parse("pas du json"));
        assertNull(LogEntryJson.parse("{\"timestampMillis\":\"texte\"}"));
    }

    private static String entry(long timestamp, String level, String tag, String message) {
        return LogEntryJson.write(new LogEntry(timestamp, "session-1",
                LogLevel.valueOf(level), tag, "main", message, null));
    }
}
