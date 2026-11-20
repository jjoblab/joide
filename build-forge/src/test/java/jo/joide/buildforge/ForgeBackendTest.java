package jo.joide.buildforge;

import jo.joide.workbench.Capabilities;
import jo.joide.workbench.InstallState;
import jo.joide.workbench.WorkMode;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ForgeBackendTest {

    @Test
    void forgeBackendAnnouncesItsLimitationsHonestly() {
        ForgeBackend backend = new ForgeBackend();
        assertEquals(WorkMode.FORGE, backend.mode());
        Capabilities c = backend.capabilities();
        // Les limites connues de Forge (AAR, ViewBinding, composants non publiés) sont
        // affichées, jamais contournées en silence (cahier des charges §4.2).
        assertTrue(c.limitations().size() >= 3);
        assertTrue(c.limitations().stream().anyMatch(l -> l.contains("AAR")));
        assertFalse(c.aar());
        assertFalse(c.viewBinding());
        assertEquals(InstallState.NOT_INSTALLED, backend.installState());
    }
}
