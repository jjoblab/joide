package jo.joide.corestore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Banc du journal d'activité (L10) : aller-retour avec détail, borne stricte,
 * fichier absent = vide, ligne pourrie = sautée sans exception.
 */
class ActivityLogTest {

    @TempDir
    Path temp;

    @Test
    void allerRetourAvecDétail() {
        ActivityLog log = new ActivityLog(temp.resolve("activite.txt"));
        log.record(ActivityLog.Kind.BUILD, "Build lancé (Forge)", null);
        log.record(ActivityLog.Kind.VERDICT, "Build réussi en 4,2 s", "12 tâches · 3 s");
        List<ActivityLog.Entry> entries = log.read();
        assertEquals(2, entries.size());
        assertEquals(ActivityLog.Kind.BUILD, entries.get(0).kind());
        assertEquals("Build lancé (Forge)", entries.get(0).message());
        assertEquals(null, entries.get(0).detail());
        assertEquals(ActivityLog.Kind.VERDICT, entries.get(1).kind());
        assertEquals("12 tâches · 3 s", entries.get(1).detail());
        assertTrue(entries.get(1).epochMs() >= entries.get(0).epochMs());
    }

    @Test
    void borneStricte() {
        ActivityLog log = new ActivityLog(temp.resolve("activite.txt"), 5);
        for (int i = 0; i < 9; i++) {
            log.record(ActivityLog.Kind.INDEX, "pass " + i, null);
        }
        List<ActivityLog.Entry> entries = log.read();
        assertEquals(5, entries.size());
        assertEquals("pass 4", entries.get(0).message()); // les plus anciennes partent
        assertEquals("pass 8", entries.get(4).message());
    }

    @Test
    void absentOuIllisibleOuPourri() throws Exception {
        assertEquals(List.of(),
                new ActivityLog(temp.resolve("rien.txt")).read());

        Path pourri = temp.resolve("activite.txt");
        Files.writeString(pourri, "pas une entrée\n9999 CRASH aïe :: détail\n\n");
        List<ActivityLog.Entry> entries = new ActivityLog(pourri).read();
        assertEquals(1, entries.size()); // la ligne pourrie est sautée
        assertEquals(ActivityLog.Kind.CRASH, entries.get(0).kind());
        assertEquals("aïe", entries.get(0).message());
    }

    @Test
    void messagesBornés() {
        ActivityLog log = new ActivityLog(temp.resolve("activite.txt"));
        log.record(ActivityLog.Kind.BUILD, "x".repeat(500), null);
        assertEquals(201, log.read().get(0).message().length()); // 200 + « … »
    }
}
