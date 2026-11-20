package jo.joide.coremodel;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import jo.joide.workbench.WorkMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectDetectorTest {

    @TempDir
    Path folder;

    @Test
    void detectsForgeProject() throws Exception {
        Files.writeString(folder.resolve("forge.json"), "{}");
        assertEquals(WorkMode.FORGE, ProjectDetector.detect(folder));
        assertEquals(List.of(WorkMode.FORGE), ProjectDetector.compatibleModes(folder));
    }

    @Test
    void detectsGradleProjectEachFlavor() throws Exception {
        for (String marker : List.of("build.gradle", "build.gradle.kts",
                "settings.gradle", "settings.gradle.kts")) {
            Path dir = Files.createTempDirectory(folder, "p");
            Files.writeString(dir.resolve(marker), "");
            assertEquals(WorkMode.GRADLE, ProjectDetector.detect(dir), marker);
        }
    }

    @Test
    void hybridProjectCompatibleWithBothModes() throws Exception {
        Files.writeString(folder.resolve("forge.json"), "{}");
        Files.writeString(folder.resolve("build.gradle"), "");
        assertEquals(List.of(WorkMode.FORGE, WorkMode.GRADLE),
                ProjectDetector.compatibleModes(folder));
    }

    @Test
    void folderWithoutMarkerIsUnrecognized() {
        assertNull(ProjectDetector.detect(folder));
        assertTrue(ProjectDetector.compatibleModes(folder).isEmpty());
    }
}
