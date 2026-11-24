package jo.joide.diag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileSinkTest {

    @TempDir
    Path tmp;

    private LogConfig config() {
        return new LogConfig(LogLevel.DEBUG, true, 7, 5, 1_000_000L);
    }

    private LogEntry entry(String message, LogLevel level) {
        return new LogEntry(System.currentTimeMillis(), "s", level, "Tag", "main", message, null);
    }

    private static boolean waitFor(java.util.function.BooleanSupplier condition,
            long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(20L);
        }
        return condition.getAsBoolean();
    }

    @Test
    void ecritParGroupementDiffere() throws Exception {
        JsonlLogStore store = new JsonlLogStore(tmp.resolve("logs"));
        FileSink sink = new FileSink(store, System::currentTimeMillis,
                this::config, failure -> { });
        sink.start();
        sink.write(entry("ligne un", LogLevel.INFO));
        sink.write(entry("ligne deux", LogLevel.INFO));

        Path file = tmp.resolve("logs").resolve("current.jsonl");
        assertTrue(waitFor(() -> Files.isRegularFile(file), 3_000L),
                "la fenêtre de groupement (500 ms) finit par écrire");
        List<LogEntry> read = store.readAll();
        assertEquals(2, read.size());
        sink.stop();
    }

    @Test
    void erreurPartImmediate() throws Exception {
        JsonlLogStore store = new JsonlLogStore(tmp.resolve("logs"));
        FileSink sink = new FileSink(store, System::currentTimeMillis,
                this::config, failure -> { });
        sink.start();
        sink.write(entry("erreur fatale", LogLevel.ERROR));

        assertTrue(waitFor(() -> !store.readAll().isEmpty(), 2_000L),
                "une entrée ERROR ne patiente pas la fenêtre de groupement");
        assertEquals("erreur fatale", store.readAll().get(0).message());
        sink.stop();
    }

    @Test
    void flushBlockingVideLaFile() throws Exception {
        JsonlLogStore store = new JsonlLogStore(tmp.resolve("logs"));
        FileSink sink = new FileSink(store, System::currentTimeMillis,
                this::config, failure -> { });
        sink.start();
        sink.write(entry("différée", LogLevel.INFO));

        assertTrue(sink.flushBlocking(2_000L));
        assertEquals(1, store.readAll().size());
        sink.stop();
    }

    @Test
    void fileLoggingDesactiveNecritRien() throws Exception {
        JsonlLogStore store = new JsonlLogStore(tmp.resolve("logs"));
        FileSink sink = new FileSink(store, System::currentTimeMillis,
                () -> new LogConfig(LogLevel.DEBUG, false, 7, 5, 1_000_000L), failure -> { });
        sink.start();
        sink.write(entry("interdite", LogLevel.ERROR));
        assertTrue(sink.flushBlocking(2_000L));
        assertTrue(store.readAll().isEmpty());
        sink.stop();
    }

    @Test
    void compteLesEchecsDEcritureDisque() throws Exception {
        // Un FICHIER sur le chemin du répertoire : la création du répertoire échoue.
        Path blocker = tmp.resolve("logs");
        Files.writeString(blocker, "je ne suis pas un répertoire", StandardCharsets.UTF_8);
        JsonlLogStore store = new JsonlLogStore(blocker);
        AtomicInteger failures = new AtomicInteger();
        FileSink sink = new FileSink(store, System::currentTimeMillis,
                this::config, failure -> failures.incrementAndGet());
        sink.start();
        sink.write(entry("perdue", LogLevel.ERROR));
        assertTrue(waitFor(() -> sink.writeErrorCount() >= 1L, 3_000L),
                "l'échec d'I/O est compté");
        assertEquals(1, failures.get(), "le listener est notifié");
        sink.stop();
    }

    @Test
    void respecteLaRotationConfiguree() throws Exception {
        JsonlLogStore store = new JsonlLogStore(tmp.resolve("logs"));
        long oneLine = LogEntryJson.write(entry("x", LogLevel.INFO))
                .getBytes(StandardCharsets.UTF_8).length;
        FileSink sink = new FileSink(store, System::currentTimeMillis,
                () -> new LogConfig(LogLevel.DEBUG, true, 7, 2, oneLine + 10L), failure -> { });
        sink.start();
        for (int i = 0; i < 5; i++) {
            sink.write(entry("ligne " + i, LogLevel.ERROR));
            sink.flushBlocking(2_000L);
        }
        List<LogEntry> read = store.readAll();
        // maxArchiveFiles=2 : archive-2 + archive-1 + courant = 3 entrées
        // survivantes — la rotation est BORNÉE, c'est sa raison d'être.
        assertEquals(3, read.size(), "2 archives + le courant survivent à la rotation");
        assertTrue(Files.isRegularFile(tmp.resolve("logs/archive-1.jsonl")));
        assertTrue(Files.isRegularFile(tmp.resolve("logs/archive-2.jsonl")));
        sink.stop();
    }
}
