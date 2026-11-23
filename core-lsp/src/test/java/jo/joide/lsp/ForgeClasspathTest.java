package jo.joide.lsp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import jo.codeide.tooling.protocol.ClasspathKind;
import jo.codeide.tooling.protocol.ClasspathResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * La traduction du périmètre Forge vers le protocole tooling (LOT-11) :
 * forge.json + build/&lt;module&gt;/resolved/classpath.json deviennent le
 * ClasspathResult que l'hôte LSP consomme — modules, racines sources,
 * entrées JAR/AAR, plateforme.
 */
class ForgeClasspathTest {

    @TempDir
    Path dossier;

    /** Un projet Forge d'un module, avec classpath résolu v2. */
    private void projetMinimal() throws Exception {
        Files.writeString(dossier.resolve("forge.json"), """
                {
                  "name": "demo",
                  "modules": [
                    { "name": "app", "type": "application", "language": "java" }
                  ]
                }
                """);
        Path résolu = dossier.resolve("build").resolve("app")
                .resolve("resolved");
        Files.createDirectories(résolu);
        Files.writeString(résolu.resolve("classpath.json"), """
                {
                  "version": 2,
                  "compile": [
                    { "coordinate": "junit:junit:4.13.2", "jar": "%s" },
                    { "coordinate": "androidx.appcompat:appcompat:1.7.0",
                      "jar": "%s", "packaging": "aar", "exploded": "%s" }
                  ],
                  "runtime": []
                }
                """.formatted(
                dossier.resolve("junit-4.13.2.jar"),
                dossier.resolve("classes.jar"),
                dossier.resolve("exploded/appcompat")));
    }

    @Test
    void moduleRacinesEtEntrées() throws Exception {
        projetMinimal();
        ClasspathResult résultat = ForgeClasspath.of(dossier, null);
        assertEquals(1, résultat.getModules().size());
        var module = résultat.getModules().get(0);
        assertEquals("app", module.getName());
        // Les conventions du codec : src/main/java du module app.
        assertEquals(List.of(dossier.resolve("app").resolve("src/main/java")
                .toString()), module.getSourceDirs());
        assertEquals(2, module.getEntries().size());
        assertEquals(ClasspathKind.JAR, module.getEntries().get(0).getKind());
        assertEquals(ClasspathKind.AAR, module.getEntries().get(1).getKind());
        assertEquals("compile", module.getEntries().get(0).getScope());
        assertEquals(1, module.getNbJars());
        assertEquals(1, module.getNbAars());
    }

    @Test
    void plateformeAbsenteResteNulle() throws Exception {
        projetMinimal();
        ClasspathResult résultat = ForgeClasspath.of(dossier, null);
        assertNullSilencieux(résultat.getModules().get(0).getAndroidJar());
    }

    @Test
    void plateformePasséeAuModule() throws Exception {
        projetMinimal();
        Path androidJar = dossier.resolve("android.jar");
        Files.write(androidJar, new byte[] { 1 });
        ClasspathResult résultat = ForgeClasspath.of(dossier, androidJar);
        assertEquals(androidJar.toString(),
                résultat.getModules().get(0).getAndroidJar());
    }

    @Test
    void projetJamaisConstruitDonneEntréesVides() throws Exception {
        Files.writeString(dossier.resolve("forge.json"), """
                { "name": "vide", "modules": [
                    { "name": "app", "type": "application", "language": "java" }
                ] }
                """);
        ClasspathResult résultat = ForgeClasspath.of(dossier, null);
        assertEquals(1, résultat.getModules().size());
        assertTrue(résultat.getModules().get(0).getEntries().isEmpty());
        assertNotNull(résultat.getProjectDir());
    }

    @Test
    void forgeJsonIllisibleDonneRésultatVideJamaisDException() {
        ClasspathResult résultat = ForgeClasspath.of(dossier, null);
        assertTrue(résultat.getModules().isEmpty());
    }

    private static void assertNullSilencieux(String valeur) {
        assertTrue(valeur == null, "la plateforme absente reste absente");
    }
}
