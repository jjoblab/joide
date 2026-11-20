package jo.joide.buildforge;

import jo.joide.workbench.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ForgeBackendTest {

    @Test
    void leBackendForgeAnnonceSesLimitesHonnêtement() {
        ForgeBackend backend = new ForgeBackend();
        assertEquals(ModeTravail.FORGE, backend.mode());
        Capacites c = backend.capacites();
        // Les limites connues de Forge (AAR, ViewBinding, composants non publiés) sont
        // affichées, jamais contournées en silence (cahier des charges §4.2).
        assertTrue(c.limitesAffichees().size() >= 3);
        assertTrue(c.limitesAffichees().stream().anyMatch(l -> l.contains("AAR")));
        assertFalse(c.aar());
        assertFalse(c.viewBinding());
        assertEquals(EtatInstallation.NON_INSTALLE, backend.etatInstallation());
    }
}
