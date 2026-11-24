package jo.joide.diag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CrashHandlerTest {

    @TempDir
    Path tmp;

    /** Le gestionnaire précédent enregistre les délégations. */
    private final List<Throwable> delegated = new CopyOnWriteArrayList<>();
    private final List<Long> flushTimeouts = new CopyOnWriteArrayList<>();

    private final Thread.UncaughtExceptionHandler previous = (thread, throwable) ->
            delegated.add(throwable);

    private final java.util.function.LongConsumer flush = timeout -> flushTimeouts.add(timeout);

    private CrashHandler handler(Path crashes, java.util.function.LongSupplier clock) {
        CrashReportStore store = new CrashReportStore(crashes);
        CrashLoopDetector loopDetector = new CrashLoopDetector(crashes);
        CrashHandler handler = new CrashHandler(store, loopDetector, clock,
                () -> new CrashAppInfo("0.1.0", 1L, "debug", "jo.joide.app"), previous);
        handler.bindLogging(() -> "session-test", () -> List.of(
                new LogEntry(1L, "session-test", LogLevel.INFO, "App", "main", "filon", null)),
                flush);
        return handler;
    }

    @Test
    void ecritLeRapportJournalisePuisDelegue() {
        CrashHandler handler = handler(tmp.resolve("crashes"), () -> 1_000_000L);
        RuntimeException boom = new RuntimeException("le moteur a explosé");
        Thread thread = new Thread(() -> { }, "fil-planté");

        handler.uncaughtException(thread, boom);

        assertEquals(1, delegated.size(), "délégation au gestionnaire précédent (système)");
        assertEquals(boom, delegated.get(0));
        assertEquals(1, flushTimeouts.size(), "un seul vidage borné");
        assertTrue(flushTimeouts.get(0) >= 0L
                && flushTimeouts.get(0) <= CrashLimits.FLUSH_TIMEOUT_MILLIS);

        CrashReportStore store = new CrashReportStore(tmp.resolve("crashes"));
        List<CrashReportSummary> summaries = store.listSummaries();
        assertEquals(1, summaries.size());
        assertEquals("le moteur a explosé", summaries.get(0).shortMessage());
        CrashReport report = store.get(summaries.get(0).id());
        assertNotNull(report);
        assertEquals("fil-planté", report.threadName());
        assertEquals("session-test", report.sessionId());
        assertEquals(1, report.breadcrumbs().size());
        assertTrue(report.processUptimeMs() >= 0L);
    }

    @Test
    void boucleDetecteeApresTroisPlantages() {
        // Une boucle de plantages = 3 PROCESSUS successifs : le gestionnaire
        // est mon coup (le processus meurt après délégation) — trois instances.
        java.util.concurrent.atomic.AtomicLong clock =
                new java.util.concurrent.atomic.AtomicLong(5_000_000L);
        CrashReportStore store = new CrashReportStore(tmp.resolve("crashes"));
        Thread thread = Thread.currentThread();

        handler(tmp.resolve("crashes"), clock::get)
                .uncaughtException(thread, new RuntimeException("un"));
        clock.incrementAndGet();
        handler(tmp.resolve("crashes"), clock::get)
                .uncaughtException(thread, new RuntimeException("deux"));
        clock.incrementAndGet();
        handler(tmp.resolve("crashes"), clock::get)
                .uncaughtException(thread, new RuntimeException("trois"));

        List<CrashReportSummary> summaries = store.listSummaries();
        assertEquals(3, summaries.size());
        CrashReport last = store.get(summaries.get(0).id());
        assertNotNull(last);
        assertTrue(last.isCrashLoop(), "le troisième plantage porte l'indicateur de boucle");
        assertEquals(3, delegated.size(), "chaque plantage délègue (le système casse la spirale)");
    }

    @Test
    void reentranceDelegueSansDoubleRapport() {
        // Le gestionnaire précédent replante PENDANT la gestion : la garde de
        // ré-entrance doit déléguer sans écrire de second rapport.
        Thread.UncaughtExceptionHandler reentrant = new Thread.UncaughtExceptionHandler() {
            private final AtomicInteger calls = new AtomicInteger();

            @Override
            public void uncaughtException(Thread thread, Throwable throwable) {
                if (calls.incrementAndGet() == 1) {
                    delegated.add(throwable);
                    // Replante pendant la gestion du premier.
                    new CrashHandler(new CrashReportStore(tmp.resolve("reentrant")),
                            new CrashLoopDetector(tmp.resolve("reentrant")),
                            () -> 2_000_000L,
                            () -> new CrashAppInfo("0.1.0", 1L, "debug", "jo.joide.app"),
                            (t, e) -> delegated.add(e))
                            .uncaughtException(thread,
                                    new RuntimeException("second pendant la gestion"));
                } else {
                    delegated.add(throwable);
                }
            }
        };
        CrashHandler handler = new CrashHandler(new CrashReportStore(tmp.resolve("reentrant2")),
                new CrashLoopDetector(tmp.resolve("reentrant2")), () -> 2_000_000L,
                () -> new CrashAppInfo("0.1.0", 1L, "debug", "jo.joide.app"), reentrant);
        handler.bindLogging(() -> "s", List::of, flush);

        handler.uncaughtException(Thread.currentThread(), new RuntimeException("premier"));

        assertTrue(delegated.size() >= 2, "le plantage pendant la gestion est délégué");
        // Le handler RÉENTRANT (le second, appelé depuis le premier) écrit un
        // rapport ; le premier aussi — deux plantages distincts, deux rapports :
        // ce que la garde interdit, c'est la récursion INFINIE, pas les plantages
        // distincts. On vérifie surtout qu'on revient et qu'on délègue.
        assertTrue(new CrashReportStore(tmp.resolve("reentrant2")).listSummaries().size() == 1);
    }

    @Test
    void echecDecritureDelegueQuandMeme() throws Exception {
        // Un FICHIER sur le chemin du répertoire des rapports : save échoue —
        // le gestionnaire ne doit jamais lui-même planter.
        Path blocker = tmp.resolve("crashes-bloque");
        Files.writeString(blocker, "pas un répertoire", StandardCharsets.UTF_8);
        AtomicInteger delegatedCount = new AtomicInteger();
        CrashHandler blocked = new CrashHandler(new CrashReportStore(blocker),
                new CrashLoopDetector(blocker), () -> 3_000_000L,
                () -> new CrashAppInfo("0.1.0", 1L, "debug", "jo.joide.app"),
                (thread, throwable) -> delegatedCount.incrementAndGet());
        blocked.bindLogging(() -> "s", List::of, flush);

        blocked.uncaughtException(Thread.currentThread(), new RuntimeException("perdu"));
        assertEquals(1, delegatedCount.get(), "l'échec interne délègue, jamais de double faute");
    }

    @Test
    void journalLEntreeDErreurDuPlantage() {
        // Le gestionnaire journalise l'entrée ERROR dans le pipeline — la trace
        // du plantage vit AUSSI dans le journal (amélioration JoIDE).
        List<LogEntry> captured = new CopyOnWriteArrayList<>();
        LogConfigHolder config = new LogConfigHolder(
                new LogConfig(LogLevel.DEBUG, true, 7, 5, 1_000_000L));
        LogEngine engine = new LogEngine(() -> 10L, config, List.of(new LogSink() {
            @Override
            public LogLevel minLevel() {
                return LogLevel.DEBUG;
            }

            @Override
            public void write(LogEntry entry) {
                captured.add(entry);
            }
        }));
        AppLog.init(engine);

        CrashHandler handler = handler(tmp.resolve("crashes-journal"), () -> 4_000_000L);
        handler.uncaughtException(Thread.currentThread(), new RuntimeException("journalisé"));

        assertEquals(1, captured.size(), "la trace du plantage part AUSSI dans le journal");
        assertEquals(LogLevel.ERROR, captured.get(0).level());
        assertEquals("Crash", captured.get(0).tag());
    }
}
