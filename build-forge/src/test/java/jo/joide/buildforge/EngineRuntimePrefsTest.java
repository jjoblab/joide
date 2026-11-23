package jo.joide.buildforge;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Banc de la persistance du runtime (L10) : aller-retour fichier, défauts
 * honnêtes (absent, illisible, valeurs pourries), jamais d'exception vers
 * l'appelant en lecture.
 */
class EngineRuntimePrefsTest {

    @TempDir
    Path temp;

    @Test
    void allerRetourComplet() throws IOException {
        Path fichier = temp.resolve("runtime.properties");
        EngineRuntimePrefs prefs = new EngineRuntimePrefs(fichier);
        prefs.write(new EngineSettings(512, EngineSettings.LowMemory.FORCÉ));
        EngineSettings lus = prefs.read();
        assertEquals(512, lus.heapMbOverride());
        assertEquals(EngineSettings.LowMemory.FORCÉ, lus.mode());
    }

    @Test
    void absentOuIllisibleDonneLesDéfauts() throws IOException {
        EngineRuntimePrefs absent = new EngineRuntimePrefs(temp.resolve("rien.properties"));
        EngineSettings défauts = absent.read();
        assertEquals(EngineSettings.auto(), défauts);

        Path pourri = temp.resolve("pourri.properties");
        Files.writeString(pourri, "heapMb = douze\nlowMemory = ??\n");
        EngineSettings lus = new EngineRuntimePrefs(pourri).read();
        assertEquals(0, lus.heapMbOverride()); // auto
        assertEquals(EngineSettings.LowMemory.AUTO, lus.mode());
    }

    @Test
    void bornesToujoursSûresÀLaRelecture() throws IOException {
        Path fichier = temp.resolve("runtime.properties");
        Files.writeString(fichier, "heapMb = 4096\nlowMemory = OFF\n");
        EngineSettings lus = new EngineRuntimePrefs(fichier).read();
        // Le tas explicite est borné par EngineSettings.resolveHeapMb, pas par
        // la persistance (1024 plafond réel du moteur).
        assertEquals(1024, lus.resolveHeapMb(8192));
    }
}
