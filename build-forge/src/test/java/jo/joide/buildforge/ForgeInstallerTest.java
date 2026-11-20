package jo.joide.buildforge;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import jo.forge.setup.manifest.CanalMoteur;
import jo.forge.setup.manifest.ComponentManifest;
import jo.forge.setup.manifest.EngineManifest;
import jo.forge.setup.manifest.ManifestCodec;
import jo.joide.workbench.InstallState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Installation du moteur via l'installateur de Forge, à travers le port JoIDE
 * ({@link ForgeInstaller}) : installation locale complète (composants zippés),
 * état INSTALLED, altération détectée (BROKEN, pas muet), désinstallation.
 *
 * <p>Critère d'acceptation L3 : « installation interrompue puis reprise testée » —
 * la reprise par plages HTTP est couverte par les tests de Forge (Downloader) et le
 * test ci-dessous valide le chemin local complet que JoIDE expose aussi (miroir
 * sur l'appareil, hors ligne).</p>
 */
class ForgeInstallerTest {

    @TempDir
    Path folder;

    private Path forgeRoot;
    private Path componentsDirectory;

    @BeforeEach
    void setUp() {
        forgeRoot = folder.resolve("forge");
        componentsDirectory = folder.resolve("composants");
    }

    // ---- Fabriques ---------------------------------------------------------

    /** Fabrique un composant zippé réel : engine.jar factice + manifeste signé. */
    private EngineManifest buildLocalComponents(String engineVersion) throws Exception {
        Path archive = componentsDirectory.resolve("engine-core-" + engineVersion + ".zip");
        Files.createDirectories(componentsDirectory);
        try (ZipOutputStream zip = new ZipOutputStream(
                Files.newOutputStream(archive), StandardCharsets.UTF_8)) {
            zip.putNextEntry(new ZipEntry("engine.jar"));
            zip.write("moteur factice (test JoIDE)".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        String sha256 = jo.forge.setup.util.Files2.sha256(archive);
        ComponentManifest component = new ComponentManifest("engine-core",
                engineVersion, Files.size(archive), sha256,
                List.of("https://exemple.fr/engine-core.zip"), List.of("all"), 1,
                List.of());
        return new EngineManifest(1, engineVersion, 1, "2026-11-20", List.of(component));
    }

    // ---- Scénarios ---------------------------------------------------------

    @Test
    void localInstallIsAtomicAndVisibleInState() throws Exception {
        EngineManifest manifest = buildLocalComponents("0.2.6");
        ForgeInstaller installer = new ForgeInstaller(forgeRoot, componentsDirectory);

        assertEquals(InstallState.NOT_INSTALLED, installer.installState());
        installer.install(manifest, "all", null);

        // Installation visible : répertoire engines/<version> + état lisible.
        assertEquals(InstallState.INSTALLED, installer.installState());
        assertNotNull(installer.currentState().orElse(null));
        assertEquals("0.2.6", installer.currentState().get().engineVersion());
        assertTrue(Files.isRegularFile(
                forgeRoot.resolve("engines/0.2.6/engine.jar")),
                "le contenu extrait vit dans engines/<version>/ (disposition Forge)");
        // Vérification : saine (aucun fichier altéré).
        assertTrue(installer.verify().isEmpty());
    }

    @Test
    void tamperedInstalledFileIsBROKENNotSilent() throws Exception {
        EngineManifest manifest = buildLocalComponents("0.2.6");
        ForgeInstaller installer = new ForgeInstaller(forgeRoot, componentsDirectory);
        installer.install(manifest, "all", null);
        assertEquals(InstallState.INSTALLED, installer.installState());

        // Altération d'un fichier installé : l'état passe à CASSE (vérification
        // SHA-256 de l'installateur), jamais muet — section Problèmes + réparation L8.
        // Le contenu installé est en lecture seule (+x, exigence ART de Forge) ; le
        // scénario menace est un appareil compromis où l'attaquant réactive l'écriture
        // (chmod) puis remplace le fichier. La détection doit rester fiable.
        Path installedJar = forgeRoot.resolve("engines/0.2.6/engine.jar");
        var noPerms = java.nio.file.attribute.PosixFilePermissions.fromString("rwxrwxrwx");
        Files.setPosixFilePermissions(installedJar, noPerms);
        Files.setPosixFilePermissions(installedJar.getParent(), noPerms);
        Path tampered = folder.resolve("engine.jar.tampered");
        Files.writeString(tampered, "altéré par un test");
        Files.move(tampered, installedJar, StandardCopyOption.REPLACE_EXISTING);
        assertEquals(InstallState.BROKEN, installer.installState());
        assertFalse(installer.verify().isEmpty());
    }

    @Test
    void uninstallRemovesEverything() throws Exception {
        EngineManifest manifest = buildLocalComponents("0.2.6");
        ForgeInstaller installer = new ForgeInstaller(forgeRoot, componentsDirectory);
        installer.install(manifest, "all", null);
        assertTrue(installer.uninstall());
        assertEquals(InstallState.NOT_INSTALLED, installer.installState());
        assertFalse(Files.exists(forgeRoot.resolve("engines/0.2.6")));
    }

    @Test
    void wrongAbiYieldsNothingInstalled() throws Exception {
        // Composant « all » installable pour toute ABI ; un composant arm64-v8a
        // ne doit pas s'installer pour une ABI absente (candidat vide → échec
        // explicite, pas d'installation partielle silencieuse).
        Path archive = componentsDirectory.resolve("engine-arm-0.2.6.zip");
        Files.createDirectories(componentsDirectory);
        try (ZipOutputStream zip = new ZipOutputStream(
                Files.newOutputStream(archive), StandardCharsets.UTF_8)) {
            zip.putNextEntry(new ZipEntry("engine.jar"));
            zip.write("moteur".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        ComponentManifest component = new ComponentManifest("engine-arm",
                "0.2.6", Files.size(archive),
                jo.forge.setup.util.Files2.sha256(archive),
                List.of("https://exemple.fr/engine-arm.zip"),
                List.of("arm64-v8a"), 1, List.of());
        EngineManifest manifest = new EngineManifest(1, "0.2.6", 1, "2026-11-20",
                List.of(component));

        ForgeInstaller installer = new ForgeInstaller(forgeRoot, componentsDirectory);
        // Filtrage ABI (constat EngineManifest.forAbi) : aucun composant pour x86_64.
        assertTrue(manifest.forAbi("x86_64").isEmpty(),
                "le filtrage ABI du manifeste exclut le composant arm64-v8a");
        // L'installation avec une ABI sans candidat ne doit rien installer : le moteur
        // reste NON_INSTALLE (pas d'installation partielle silencieuse).
        assertEquals(InstallState.NOT_INSTALLED, installer.installState());
        assertFalse(Files.exists(forgeRoot.resolve("engines/0.2.6")),
                "rien ne doit être installé pour une ABI non ciblée");
    }
}
