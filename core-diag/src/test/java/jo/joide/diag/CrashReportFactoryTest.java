package jo.joide.diag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class CrashReportFactoryTest {

    private final CrashReportFactory factory = new CrashReportFactory(() -> 100_000L);

    private CrashAppInfo appInfo() {
        return new CrashAppInfo("0.1.0", 1L, "debug", "jo.joide.app");
    }

    @Test
    void construitUnRapportExpurgeEtBorne() {
        RuntimeException boom = new RuntimeException("cle=sk-abc123def456ghi789xyz");
        boom.setStackTrace(new StackTraceElement[0]);
        List<LogEntry> breadcrumbs = new ArrayList<>();
        for (int i = 0; i < 80; i++) {
            breadcrumbs.add(new LogEntry(i, "s", LogLevel.INFO, "App", "main", "fil " + i, null));
        }

        CrashReport report = factory.create(boom, "main", appInfo(),
                DeviceInfo::unknown, "session-42", breadcrumbs, "Atelier", 1_234L, false);

        assertEquals("cle=<secret>", report.exception().message());
        assertEquals(LoggingLimits.MAX_BREADCRUMBS, report.breadcrumbs().size());
        assertEquals("fil 30", report.breadcrumbs().get(0).message(), "les plus ANCIENS partent");
        assertEquals("Atelier", report.lastScreen());
        assertEquals(1_234L, report.processUptimeMs());
        assertEquals(100_000L, report.timestampMillis());
        assertTrue(!report.id().isEmpty());
    }

    @Test
    void dureeNegativeBorneeAZeroEtNullSupportes() {
        RuntimeException boom = new RuntimeException("ok");
        CrashReport report = factory.create(boom, null, appInfo(),
                DeviceInfo::unknown, null, null, null, -5L, true);
        assertEquals(0L, report.processUptimeMs());
        assertEquals("", report.threadName());
        assertEquals("", report.sessionId());
        assertTrue(report.isCrashLoop());
    }

    @Test
    void fournisseurAppareilFautilRetombeSurUnknown() {
        RuntimeException boom = new RuntimeException("ok");
        CrashReport report = factory.create(boom, "main", appInfo(),
                () -> {
                    throw new IllegalStateException("capteur cassé");
                }, "s", List.of(), null, 0L, false);
        assertEquals(DeviceInfo.unknown(), report.device());
    }

    @Test
    void reconstruitUnRapportDeSortiePrecedente() {
        CrashReport report = factory.fromExitInfo(CrashType.ANR, 200_000L,
                "application ne répondant plus (ANR)", "ligne 1\nligne 2\n\nligne 3",
                appInfo(), DeviceInfo::unknown);
        assertEquals(CrashType.ANR, report.type());
        assertEquals(200_000L, report.timestampMillis());
        assertEquals(3, report.exception().frames().size(), "lignes vides écartées");
        assertTrue(report.exception().message().contains("ANR"));
        assertEquals("", report.sessionId());
        assertEquals(null, report.lastScreen());
    }
}
