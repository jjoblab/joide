package jo.joide.buildforge;

import java.nio.file.Path;
import jo.forge.client.JavaVmLauncher;
import jo.joide.workbench.Capabilities;
import jo.joide.workbench.InstallState;
import jo.joide.workbench.WorkMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ForgeBackendTest {

    @TempDir
    Path folder;

    @Test
    void forgeBackendAnnouncesItsCapabilitiesHonestly() {
        // ForgeHome jetable + lanceur JVM : aucun état n'est installé (bac à sable).
        ForgeBackend backend = new ForgeBackend(folder.resolve("forge"),
                new JavaVmLauncher());
        assertEquals(WorkMode.FORGE, backend.mode());
        Capabilities c = backend.capabilities();
        // Forge v0.2.24 : AAR et ViewBinding SONT livrés (demandes F-05/F-06 closes),
        // les composants aapt2 + plateformes sont publiés (F-02 close) — le backend
        // ne doit plus les annoncer manquants (cahier des charges §4.2 : honnête
        // dans les DEUX sens, jamais de limite périmée).
        assertTrue(c.aar(), "AAR supportés depuis Forge v0.2.16+ (F-05 livrée)");
        assertTrue(c.viewBinding(), "ViewBinding supporté depuis Forge v0.2.18 (F-06 livrée)");
        assertTrue(c.forgeProjects());
        assertFalse(c.gradleScripts(), "les scripts Gradle ne sont pas exécutés en mode Forge");
        assertFalse(c.terminal(), "le terminal intégré appartient au mode Gradle, à venir");
        // Les limites RESTANTES sont affichées : signature de débogage, pas de R8.
        assertTrue(c.limitations().size() >= 2);
        assertTrue(c.limitations().stream()
                        .anyMatch(l -> l.contains("débogage") || l.contains("débogage")),
                "la limite de signature est affichée : " + c.limitations());
        assertEquals(InstallState.NOT_INSTALLED, backend.installState());
    }

    @Test
    void deviceProfileDrivesHeapAndOptions() {
        // Appareil modeste (< 2,5 Go) : tas moteur réduit (256 Mo, décision Forge
        // v0.2.15+) ; appareil doté : 1024 Mo (le dexage d'une fermeture Compose
        // ne tient pas dans moins).
        var modeste = new ForgeEngineManager.DeviceProfile("arm64-v8a", 2048);
        var doté = new ForgeEngineManager.DeviceProfile("arm64-v8a", 4096);
        var managerModeste = new ForgeEngineManager(folder.resolve("m1"),
                new JavaVmLauncher(), modeste);
        var managerDoté = new ForgeEngineManager(folder.resolve("m2"),
                new JavaVmLauncher(), doté);
        assertEquals(ForgeEngineManager.HEAP_LOW_MEMORY_MB, managerModeste.heapMb());
        assertEquals(ForgeEngineManager.HEAP_MB, managerDoté.heapMb());

        // Les options du build suivent le profil : mode basse mémoire et ABI réelle.
        var requêteModeste = ForgeBuildSession.forgeRequest("b", "/p", false, modeste);
        assertTrue(requêteModeste.options().lowMemory());
        assertEquals("arm64-v8a", requêteModeste.options().abi());
        assertEquals(2048, requêteModeste.options().totalRamMb());
        var requêteDoté = ForgeBuildSession.forgeRequest("b", "/p", false, doté);
        assertFalse(requêteDoté.options().lowMemory());
    }
}
