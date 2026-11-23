package jo.joide.coremodel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import jo.joide.coremodel.ForgeJson.AndroidFacet;
import jo.joide.coremodel.ForgeJson.Dependency;
import jo.joide.coremodel.ForgeJson.Model;
import jo.joide.coremodel.ForgeJson.ModuleView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Banc du codec {@code forge.json} (L10) : lecture des exemples réels du moteur,
 * conventions des champs absents, mutations ciblées, conservation des champs
 * inconnus (fidélité de l'aller-retour), écriture atomique relisible.
 */
class ForgeJsonTest {

    @TempDir
    Path temporaire;

    /** L'exemple Android complet du moteur se lit champ par champ. */
    @Test
    void litExempleAndroidComplet() throws Exception {
        Model model = lit("""
                {"modelVersion":1,
                 "repositories":["https://repo.maven.apache.org/maven2","https://dl.google.com/dl/android/maven2"],
                 "modules":[
                   {"name":"core","type":"java-library"},
                   {"name":"app","type":"application","dependencies":[
                      {"project":"core","scope":"api"},
                      {"maven":"androidx.compose.material3:material3:1.3.0"}],
                    "android":{"manifest":"src/main/AndroidManifest.xml","res":["src/main/res"],
                      "minSdk":28,"targetSdk":34,"namespace":"demo.app","versionCode":4,
                      "versionName":"1.3-forge","buildFeatures":{"buildConfig":true,"viewBinding":true}},
                    "champFutur":"préservé"}]}
                """);
        assertEquals(List.of("https://repo.maven.apache.org/maven2",
                "https://dl.google.com/dl/android/maven2"), model.repositories());
        List<ModuleView> modules = model.modules();
        assertEquals(2, modules.size());
        ModuleView app = modules.get(1);
        assertEquals("app", app.name());
        assertEquals("application", app.type());
        assertEquals("java", app.language());
        assertEquals(17, app.javaRelease());
        assertEquals(List.of("src/main/java"), app.sources());
        assertEquals(2, app.dependencies().size());
        assertEquals("project", app.dependencies().get(0).kind());
        assertEquals("core", app.dependencies().get(0).module());
        assertEquals("api", app.dependencies().get(0).scope());
        assertEquals("maven", app.dependencies().get(1).kind());
        assertEquals("androidx.compose.material3", app.dependencies().get(1).group());
        assertEquals("material3", app.dependencies().get(1).artifact());
        assertEquals("1.3.0", app.dependencies().get(1).version());
        assertEquals("implementation", app.dependencies().get(1).scope());
        Optional<AndroidFacet> android = app.android();
        assertTrue(android.isPresent());
        assertEquals(28, android.get().minSdk());
        assertEquals(34, android.get().targetSdk());
        assertEquals("demo.app", android.get().namespace());
        assertEquals(4, android.get().versionCode());
        assertEquals("1.3-forge", android.get().versionName());
        assertTrue(android.get().viewBinding());
        // Conventions du module nu :
        ModuleView core = modules.get(0);
        assertEquals(List.of("src/main/java"), core.sources());
        assertEquals(List.of("src/main/resources"), core.resources());
        assertTrue(core.dependencies().isEmpty());
        assertTrue(core.android().isEmpty());
    }

    /** Convention Kotlin : src/main/kotlin quand {@code sources} est absent. */
    @Test
    void conventionKotlin() throws Exception {
        Model model = lit("{\"modules\":[{\"name\":\"app\",\"language\":\"kotlin\"}]}");
        assertEquals(List.of("src/main/kotlin"), model.modules().get(0).sources());
    }

    /** Ajout de module + création des conventions puis relecture. */
    @Test
    void ajouteEtRetireModule() throws Exception {
        Model model = lit("{\"modelVersion\":1,\"modules\":[]}");
        assertTrue(model.addModule("nouveau", "java-library", 17));
        assertFalse(model.addModule("nouveau", "java-library", 17)); // doublon refusé
        assertNotNull(model.module("nouveau"));
        assertTrue(model.removeModule("nouveau"));
        assertNull(model.module("nouveau"));
        model.writeAtomically();
        assertTrue(Model.read(chemin()).modules().isEmpty());
    }

    /** Racines sources : matérialisation de la convention, ajout/retrait, clé
     * présente mais vide = vide (sémantique du moteur, pas de repli caché). */
    @Test
    void racinesSources() throws Exception {
        Model model = modelAvecModule();
        assertTrue(model.addSourceRoot("app", "src/main/java2"));
        // La convention s'est matérialisée : la racine par défaut est conservée.
        assertEquals(List.of("src/main/java", "src/main/java2"),
                model.module("app").sources());
        assertTrue(model.removeSourceRoot("app", "src/main/java"));
        assertEquals(List.of("src/main/java2"), model.module("app").sources());
        assertTrue(model.removeSourceRoot("app", "src/main/java2"));
        // Clé présente mais vide : VIDE (le moteur ne replie pas sur la convention).
        assertEquals(List.of(), model.module("app").sources());
        assertFalse(model.removeSourceRoot("app", "src/main/java2")); // déjà retirée
    }

    /** Dépendances : ajout projet/maven, doublon, retrait, changement de version. */
    @Test
    void dépendances() throws Exception {
        Model model = modelAvecModule();
        assertTrue(model.addDependency("app",
                new Dependency("maven", null, "com.exemple", "outils", "1.0", "api")));
        assertFalse(model.addDependency("app",
                new Dependency("maven", null, "com.exemple", "outils", "1.0", "api")));
        model.addModule("core", "java-library", 17);
        assertTrue(model.addDependency("app",
                new Dependency("project", "core", null, null, null, "implementation")));
        List<Dependency> deps = model.module("app").dependencies();
        assertEquals(2, deps.size());
        assertTrue(model.setDependencyVersion("app", "com.exemple", "outils", "2.0"));
        assertEquals("2.0", model.module("app").dependencies().get(0).version());
        // Retrait par la vue COURANTE (la version a changé depuis la première vue).
        assertTrue(model.removeDependency("app", model.module("app").dependencies().get(0)));
        assertEquals(1, model.module("app").dependencies().size());
        assertTrue(model.removeDependency("app",
                new Dependency("project", "core", null, null, null, "implementation")));
    }

    /** Renommage : le nom et les dépendances des frères suivent. */
    @Test
    void renommeModuleEtDépendances() throws Exception {
        Model model = lit("""
                {"modules":[
                  {"name":"core","type":"java-library"},
                  {"name":"app","type":"application","dependencies":[{"project":"core"}]}]}
                """);
        assertTrue(model.renameModule("core", "socle"));
        assertEquals("socle", model.modules().get(0).name());
        assertEquals("socle", model.module("app").dependencies().get(0).module());
    }

    /** Facette Android : création à la volée, champs typés. */
    @Test
    void facetteAndroidCrééeÀLaVolée() throws Exception {
        Model model = modelAvecModule();
        model.setModuleField("app", "type", "application");
        model.setAndroidField("app", "namespace", "demo.app");
        model.setAndroidField("app", "minSdk", 24);
        AndroidFacet facet = model.module("app").android().orElseThrow();
        assertEquals("demo.app", facet.namespace());
        assertEquals(24, facet.minSdk());
        assertEquals(-1, facet.targetSdk()); // absent = non renseigné
    }

    /** Fidélité : des champs inconnus survivent à une mutation + réécriture. */
    @Test
    void champInconnuPréservé() throws Exception {
        Model model = lit("""
                {"modelVersion":1,"signatures":{"unHash":"préserve-moi"},
                 "champsFuturs":[1,2,3],
                 "modules":[{"name":"app","type":"java-library","extensionÉditeur":"valeur"}]}
                """);
        model.addSourceRoot("app", "src/main/java2");
        model.writeAtomically();
        Model relu = Model.read(chemin());
        assertEquals("préserve-moi", relu.champRacine("signatures.unHash"));
        assertEquals("valeur", relu.champModule("app", "extensionÉditeur"));
        assertTrue(relu.champRacine("champsFuturs").startsWith("["));
        assertEquals(List.of("src/main/java", "src/main/java2"), relu.module("app").sources());
    }

    /** Écriture atomique : le fichier temporaire disparaît, le contenu se relit. */
    @Test
    void écritureAtomiqueRelisible() throws Exception {
        Model model = modelAvecModule();
        model.addDependency("app", new Dependency("maven", null, "g", "a", "1.0", null));
        model.writeAtomically();
        try (var stream = Files.list(temporaire)) {
            assertEquals(1, stream.count()); // aucun .tmp traînant
        }
        Model relu = Model.read(chemin());
        assertEquals("g:a:1.0", relu.module("app").dependencies().get(0).display());
    }

    /** JSON invalide : diagnostic explicite, jamais de moitié lue. */
    @Test
    void diagnosticDIllisibilité() throws Exception {
        Files.writeString(chemin(), "{\"modules\":[{\"name\":\"app\"");
        assertTrue(litÉchoue().contains("illisible"));
        Files.writeString(chemin(), "[]");
        assertTrue(litÉchoue().contains("racine"));
    }

    // ---- Montage ------------------------------------------------------------

    private Path chemin() throws IOException {
        return temporaire.resolve("forge.json");
    }

    private Model lit(String json) throws Exception {
        Files.writeString(chemin(), json, StandardCharsets.UTF_8);
        return Model.read(chemin());
    }

    private Model modelAvecModule() throws Exception {
        return lit("{\"modelVersion\":1,\"modules\":[{\"name\":\"app\",\"type\":\"java-library\"}]}");
    }

    private String litÉchoue() throws IOException {
        try {
            Model.read(chemin());
            return "";
        } catch (Exception expected) {
            return expected.getMessage();
        }
    }
}
