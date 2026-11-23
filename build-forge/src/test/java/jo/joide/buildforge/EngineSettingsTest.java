package jo.joide.buildforge;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Banc des réglages du runtime (L10) : résolution du tas selon le mode et la RAM,
 * bornes de la valeur explicite, défauts honnêtes.
 */
class EngineSettingsTest {

    @Test
    void autoSuitLeSeuilRéel() {
        EngineSettings réglages = EngineSettings.auto();
        assertEquals(1024, réglages.resolveHeapMb(8192)); // au-dessus de 2,5 Go
        assertEquals(256, réglages.resolveHeapMb(2048)); // sous le seuil Forge
        assertEquals(1024, réglages.resolveHeapMb(0)); // RAM inconnue : prudent
    }

    @Test
    void modeBasseMémoireForcéOuCoupé() {
        assertEquals(256, new EngineSettings(0, EngineSettings.LowMemory.FORCÉ)
                .resolveHeapMb(8192));
        assertEquals(1024, new EngineSettings(0, EngineSettings.LowMemory.DÉSACTIVÉ)
                .resolveHeapMb(2048));
    }

    @Test
    void tasExpliciteBorné() {
        assertEquals(512, new EngineSettings(512, EngineSettings.LowMemory.AUTO)
                .resolveHeapMb(2048));
        assertEquals(256, new EngineSettings(64, EngineSettings.LowMemory.AUTO)
                .resolveHeapMb(8192)); // sous la borne : ramené au minimum
        assertEquals(1024, new EngineSettings(4096, EngineSettings.LowMemory.AUTO)
                .resolveHeapMb(8192)); // au-dessus : ramené au maximum
        // L'explicite prime même en basse mémoire (l'utilisateur a choisi).
        assertEquals(512, new EngineSettings(512, EngineSettings.LowMemory.FORCÉ)
                .resolveHeapMb(2048));
    }
}
