package jo.joide.diag;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CrashLoopDetectorTest {

    @TempDir
    Path tmp;

    @Test
    void troisPlantagesEnSoixanteSecondesDetectentLaBoucle() {
        CrashLoopDetector detector = new CrashLoopDetector(tmp.resolve("crashes"));
        assertFalse(detector.recordAndDetect(1_000L));
        assertFalse(detector.recordAndDetect(20_000L));
        assertTrue(detector.recordAndDetect(59_000L), "3 dans la fenêtre = boucle");
    }

    @Test
    void fenetreEcouleeRepartDeZero() {
        CrashLoopDetector detector = new CrashLoopDetector(tmp.resolve("crashes"));
        detector.recordAndDetect(0L);
        detector.recordAndDetect(30_000L);
        // 90 s plus tard : les deux premiers sont hors fenêtre.
        assertFalse(detector.recordAndDetect(120_000L));
    }

    @Test
    void historiquePersistantEntreInstances() {
        Path dir = tmp.resolve("crashes");
        CrashLoopDetector first = new CrashLoopDetector(dir);
        first.recordAndDetect(0L);
        first.recordAndDetect(10_000L);

        CrashLoopDetector second = new CrashLoopDetector(dir);
        assertTrue(second.recordAndDetect(20_000L),
                "l'historique survit au redémarrage du processus");
    }

    @Test
    void fichierCorrompuRepartDeZeroSansEchouer() throws Exception {
        java.nio.file.Files.writeString(dirFile(), "pas\ndes\nnombres\n");
        CrashLoopDetector detector = new CrashLoopDetector(tmp.resolve("crashes"));
        assertFalse(detector.recordAndDetect(5_000L));
    }

    private Path dirFile() throws Exception {
        java.nio.file.Path dir = tmp.resolve("crashes");
        java.nio.file.Files.createDirectories(dir);
        return dir.resolve("loop-history.txt");
    }
}
