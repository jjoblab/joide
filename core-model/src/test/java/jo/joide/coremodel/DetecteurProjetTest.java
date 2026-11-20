package jo.joide.coremodel;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import jo.joide.workbench.ModeTravail;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class DetecteurProjetTest {

    @TempDir
    Path dossier;

    @Test
    void detecteProjetForge() throws Exception {
        Files.writeString(dossier.resolve("forge.json"), "{}");
        assertEquals(ModeTravail.FORGE, DetecteurProjet.detecter(dossier));
        assertEquals(List.of(ModeTravail.FORGE), DetecteurProjet.modesCompatibles(dossier));
    }

    @Test
    void detecteProjetGradleChaqueSaveur() throws Exception {
        for (String marqueur : List.of("build.gradle", "build.gradle.kts",
                "settings.gradle", "settings.gradle.kts")) {
            Path d = Files.createTempDirectory(dossier, "p");
            Files.writeString(d.resolve(marqueur), "");
            assertEquals(ModeTravail.GRADLE, DetecteurProjet.detecter(d), marqueur);
        }
    }

    @Test
    void projetHybrideCompatibleAvecLesDeuxModes() throws Exception {
        Files.writeString(dossier.resolve("forge.json"), "{}");
        Files.writeString(dossier.resolve("build.gradle"), "");
        assertEquals(List.of(ModeTravail.FORGE, ModeTravail.GRADLE),
                DetecteurProjet.modesCompatibles(dossier));
    }

    @Test
    void dossierSansMarqueurNonReconnu() {
        assertNull(DetecteurProjet.detecter(dossier));
        assertTrue(DetecteurProjet.modesCompatibles(dossier).isEmpty());
    }
}
