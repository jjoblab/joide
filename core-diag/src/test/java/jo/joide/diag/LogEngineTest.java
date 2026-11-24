package jo.joide.diag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class LogEngineTest {

    /** Destination qui capture tout — pour observer ce que le moteur écrit. */
    private static final class CapturingSink implements LogSink {
        final List<LogEntry> written = new ArrayList<>();
        private final LogLevel minLevel;

        CapturingSink(LogLevel minLevel) {
            this.minLevel = minLevel;
        }

        @Override
        public LogLevel minLevel() {
            return minLevel;
        }

        @Override
        public void write(LogEntry entry) {
            written.add(entry);
        }
    }

    @Test
    void filtreParNiveauGlobalSansEvaluerLeMessage() {
        LogConfigHolder config = new LogConfigHolder(
                new LogConfig(LogLevel.INFO, true, 7, 5, 1_000_000L));
        CapturingSink sink = new CapturingSink(LogLevel.DEBUG);
        LogEngine engine = new LogEngine(() -> 1_000L, config, List.of(sink));

        AtomicBoolean evaluated = new AtomicBoolean(false);
        engine.d("Tag", () -> {
            evaluated.set(true);
            return "jamais vu";
        });

        assertTrue(!evaluated.get(), "le message filtré n'est jamais évalué");
        assertTrue(sink.written.isEmpty());
        assertTrue(engine.snapshot(10).isEmpty());
    }

    @Test
    void filtreParNiveauDeDestination() {
        LogConfigHolder config = new LogConfigHolder(
                new LogConfig(LogLevel.DEBUG, true, 7, 5, 1_000_000L));
        CapturingSink bavard = new CapturingSink(LogLevel.DEBUG);
        CapturingSink silencieux = new CapturingSink(LogLevel.ERROR);
        LogEngine engine = new LogEngine(() -> 2_000L, config, List.of(bavard, silencieux));

        engine.w("Tag", "avertissement");
        assertEquals(1, bavard.written.size());
        assertEquals(0, silencieux.written.size());
    }

    @Test
    void expurgeEtTronqueLeMessage() {
        LogConfigHolder config = new LogConfigHolder(
                new LogConfig(LogLevel.DEBUG, true, 7, 5, 1_000_000L));
        CapturingSink sink = new CapturingSink(LogLevel.DEBUG);
        LogEngine engine = new LogEngine(() -> 3_000L, config, List.of(sink));

        engine.e("Tag", " Authorization: Bearer abcdefghijklmnopqrstuvwxyz012345 ");
        assertTrue(sink.written.get(0).message().contains("<secret>"));
        assertTrue(!sink.written.get(0).message().contains("abcdefghijklmnopqrstuvwxyz"));

        engine.e("Tag", "x".repeat(10_000));
        byte[] bytes = sink.written.get(1).message()
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(bytes.length <= LoggingLimits.MESSAGE_MAX_BYTES);
    }

    @Test
    void aplatiEtExpurgeLException() {
        LogConfigHolder config = new LogConfigHolder(
                new LogConfig(LogLevel.DEBUG, true, 7, 5, 1_000_000L));
        CapturingSink sink = new CapturingSink(LogLevel.DEBUG);
        LogEngine engine = new LogEngine(() -> 4_000L, config, List.of(sink));

        engine.e("Tag", new IllegalStateException("cle=sk-abc123def456ghi789xyz"), "échec");
        LogEntry entry = sink.written.get(0);
        assertEquals("java.lang.IllegalStateException", entry.exception().className());
        assertEquals("cle=<secret>", entry.exception().message());
        assertTrue(!entry.exception().message().contains("sk-abc123"));
    }

    @Test
    void alimenteLesFilonsEtLaSession() {
        LogConfigHolder config = new LogConfigHolder(
                new LogConfig(LogLevel.DEBUG, true, 7, 5, 1_000_000L));
        LogEngine engine = new LogEngine(() -> 5_000L, config, List.of());

        engine.i("App", "un");
        engine.i("App", "deux");
        engine.i("App", "trois");

        List<LogEntry> snapshot = engine.snapshot(2);
        assertEquals(2, snapshot.size());
        assertEquals("deux", snapshot.get(0).message());
        assertEquals("trois", snapshot.get(1).message());
        assertEquals(engine.sessionId(), snapshot.get(0).sessionId());
        assertTrue(!engine.sessionId().isEmpty());
    }

    @Test
    void notifieLesAuditeursSansLesLaisserToutCasser() {
        LogConfigHolder config = new LogConfigHolder(
                new LogConfig(LogLevel.DEBUG, true, 7, 5, 1_000_000L));
        LogEngine engine = new LogEngine(() -> 6_000L, config, List.of());

        AtomicReference<LogEntry> seen = new AtomicReference<>();
        engine.addListener(seen::set);
        engine.addListener(entry -> {
            throw new RuntimeException("auditeur fautil");
        });

        engine.i("App", "message");
        assertEquals("message", seen.get().message());
    }

    @Test
    void changeDeNiveauAChaud() {
        LogConfigHolder config = new LogConfigHolder(
                new LogConfig(LogLevel.INFO, true, 7, 5, 1_000_000L));
        CapturingSink sink = new CapturingSink(LogLevel.DEBUG);
        LogEngine engine = new LogEngine(() -> 7_000L, config, List.of(sink));

        engine.d("Tag", "invisible");
        assertTrue(sink.written.isEmpty());
        config.update(new LogConfig(LogLevel.DEBUG, true, 7, 5, 1_000_000L));
        engine.d("Tag", "visible");
        assertEquals(1, sink.written.size());
    }

    @Test
    void niveauNullEtMessageNullSupportes() {
        LogConfigHolder config = new LogConfigHolder(
                new LogConfig(LogLevel.DEBUG, true, 7, 5, 1_000_000L));
        CapturingSink sink = new CapturingSink(LogLevel.DEBUG);
        LogEngine engine = new LogEngine(() -> 8_000L, config, List.of(sink));

        engine.log(null, "Tag", null, () -> "rien");
        engine.log(LogLevel.INFO, null, null, null);
        assertTrue(sink.written.isEmpty());
    }
}
