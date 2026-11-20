package jo.joide.corestore;

import java.nio.file.Files;
import java.nio.file.Path;
import jo.joide.workbench.ModeTravail;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class PreferencesModesTest {

    @TempDir
    Path dossier;

    @Test
    void ecriturePuisLectureRoundTrip() throws Exception {
        PreferencesModes prefs = new PreferencesModes(dossier.resolve("modes.properties"));
        assertNull(prefs.lire());
        prefs.ecrire(new ChoixMode(ModeTravail.FORGE, false));
        assertEquals(new ChoixMode(ModeTravail.FORGE, false), prefs.lire());
        prefs.ecrire(new ChoixMode(ModeTravail.GRADLE, true));
        assertEquals(new ChoixMode(ModeTravail.GRADLE, true), prefs.lire());
        // pas de fichier temporaire restant (écriture atomique)
        try (var flux = Files.list(dossier)) {
            assertEquals(1, flux.count());
        }
    }

    @Test
    void fichierIllisibleRenvoieNull() throws Exception {
        Files.writeString(dossier.resolve("modes.properties"), "pas=un mode");
        assertNull(new PreferencesModes(dossier.resolve("modes.properties")).lire());
    }
}
