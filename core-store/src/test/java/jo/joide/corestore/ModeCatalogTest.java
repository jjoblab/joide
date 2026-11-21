package jo.joide.corestore;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Banc du catalogue des composants (écran Installation) : tailles présentes,
 * totaux cohérents, phases vérifiées du mode Gradle uniquement (installé =
 * vérifié).
 */
final class ModeCatalogTest {

    @Test
    void leCatalogueForgeListeSesComposantsAvecTailles() {
        ModeCatalog catalog = ModeCatalog.forge();
        assertEquals(7, catalog.components().size());
        assertTrue(catalog.components().stream()
                .allMatch(c -> c.bytes() > 0));
        assertEquals("engine-core", catalog.components().get(0).id());
        // Total du manifeste 0.2.13 : ~52,9 Mo.
        assertTrue(catalog.totalBytes() > 50_000_000L,
                "total Forge ~53 Mo : " + catalog.humanTotal());
        assertTrue(catalog.humanTotal().endsWith("Mo"));
    }

    @Test
    void leCatalogueGradlePorteQuatrePhasesVerifiees() {
        ModeCatalog catalog = ModeCatalog.gradle();
        assertEquals(4, catalog.components().size());
        assertEquals(4, catalog.phases().size());
        assertEquals("BOOTSTRAP", catalog.phases().get(0).name());
        assertEquals("bin/gradle --version",
                catalog.phases().get(3).verificationCommand());
    }

    @Test
    void forgeNePorteAucunePhase() {
        assertFalse(ModeCatalog.forge().phases().isEmpty() == false
                && ModeCatalog.forge().phases().size() > 0);
        assertTrue(ModeCatalog.forge().phases().isEmpty());
    }
}
