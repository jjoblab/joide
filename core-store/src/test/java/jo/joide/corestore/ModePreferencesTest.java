package jo.joide.corestore;

import java.nio.file.Files;
import java.nio.file.Path;
import jo.joide.workbench.WorkMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ModePreferencesTest {

    @TempDir
    Path folder;

    @Test
    void writeThenReadRoundTrip() throws Exception {
        ModePreferences prefs = new ModePreferences(folder.resolve("modes.properties"));
        assertNull(prefs.read());
        prefs.write(new ModeChoice(WorkMode.FORGE, false));
        assertEquals(new ModeChoice(WorkMode.FORGE, false), prefs.read());
        prefs.write(new ModeChoice(WorkMode.GRADLE, true));
        assertEquals(new ModeChoice(WorkMode.GRADLE, true), prefs.read());
        // pas de fichier temporaire restant (écriture atomique)
        try (var stream = Files.list(folder)) {
            assertEquals(1, stream.count());
        }
    }

    @Test
    void unreadableFileYieldsNull() throws Exception {
        Files.writeString(folder.resolve("modes.properties"), "pas=un mode");
        assertNull(new ModePreferences(folder.resolve("modes.properties")).read());
    }
}
