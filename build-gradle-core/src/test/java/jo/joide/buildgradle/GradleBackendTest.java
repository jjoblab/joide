package jo.joide.buildgradle;

import jo.joide.workbench.Capabilities;
import jo.joide.workbench.WorkMode;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GradleBackendTest {

    @Test
    void gradleBackendOwnsItsWeightInLimitations() {
        GradleBackend backend = new GradleBackend();
        assertEquals(WorkMode.GRADLE, backend.mode());
        Capabilities c = backend.capabilities();
        assertTrue(c.gradleScripts());
        assertTrue(c.terminal());
        assertTrue(c.aar());
        assertTrue(c.limitations().stream().anyMatch(l -> l.contains("lourd")));
    }
}
