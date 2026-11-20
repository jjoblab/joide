package jo.joide.buildgradle;

import jo.joide.workbench.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GradleBackendTest {

    @Test
    void leBackendGradleAssumeSonPoidsDansSesLimites() {
        GradleBackend backend = new GradleBackend();
        assertEquals(ModeTravail.GRADLE, backend.mode());
        Capacites c = backend.capacites();
        assertTrue(c.scriptsGradle());
        assertTrue(c.terminal());
        assertTrue(c.aar());
        assertTrue(c.limitesAffichees().stream().anyMatch(l -> l.contains("lourd")));
    }
}
