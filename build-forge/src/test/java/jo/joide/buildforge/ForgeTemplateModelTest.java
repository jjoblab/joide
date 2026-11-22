package jo.joide.buildforge;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import jo.forge.engine.model.ForgeModel;
import jo.forge.engine.model.ModelCodec;
import jo.forge.engine.model.ModuleDependency;
import jo.forge.engine.model.ModuleType;
import jo.joide.corestore.ProjectStore;
import jo.joide.corestore.ProjectTemplates;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Les modèles de création JoIDE produisent des projets que le VRAI moteur Forge
 * sait lire : chaque {@code forge.json} instancié est décodé par le
 * {@link ModelCodec} de Forge (module engine, la référence du format) — un
 * modèle qui ne se parserait pas produirait un projet inconstructible (critère
 * L4), jamais accepté.
 */
class ForgeTemplateModelTest {

    @TempDir
    Path folder;

    private ForgeModel parseInstantiated(String templateId) throws Exception {
        ProjectStore store = new ProjectStore(folder.resolve("projets"));
        Path project = ProjectTemplates.standard()
                .instantiate(store, templateId, "Demo", "com.exemple.demo");
        Path forgeJson = project.resolve("forge.json");
        assertTrue(Files.isRegularFile(forgeJson),
                "le modèle " + templateId + " porte forge.json");
        return ModelCodec.parse(Files.readString(forgeJson));
    }

    @Test
    void javaLibraryTemplateIsAReadableForgeModel() throws Exception {
        ForgeModel model = parseInstantiated("forge-java");
        assertEquals(1, model.modules().size());
        assertEquals(ModuleType.JAVA_LIBRARY, model.modules().get(0).type());
    }

    @Test
    void kotlinLibraryTemplateDeclaresLanguageAndStdlib() throws Exception {
        ForgeModel model = parseInstantiated("forge-kotlin");
        assertEquals("kotlin", model.modules().get(0).language());
        assertTrue(model.modules().get(0).dependencies().stream()
                        .anyMatch(d -> d instanceof ModuleDependency.MavenDependency maven
                                && maven.coordinate().startsWith(
                                        "org.jetbrains.kotlin:kotlin-stdlib:")),
                "la stdlib Kotlin est déclarée");
    }

    @Test
    void androidXmlTemplateDeclaresAndroidFacetAndGenerations() throws Exception {
        ForgeModel model = parseInstantiated("forge-android-xml");
        var module = model.modules().get(0);
        assertEquals(ModuleType.APPLICATION, module.type());
        var android = module.android().orElseThrow();
        assertEquals("com.exemple.demo", android.namespace());
        assertTrue(android.buildFeatures().viewBinding(),
                "ViewBinding demandé (livré Forge v0.2.18, F-06)");
        assertTrue(android.buildFeatures().buildConfig(),
                "BuildConfig demandé");
        assertTrue(android.minSdk() > 0 && android.targetSdk() > 0,
                "niveaux d'API déclarés");
    }

    @Test
    void composeTemplateDeclaresComposeClasspath() throws Exception {
        ForgeModel model = parseInstantiated("forge-compose");
        List<String> coords = model.modules().get(0).dependencies().stream()
                .map(d -> d instanceof ModuleDependency.MavenDependency maven
                        ? maven.coordinate() : "").toList();
        assertTrue(coords.stream().anyMatch(c -> c.startsWith(
                        "androidx.compose.material3:material3:")),
                "Material 3 : " + coords);
        assertTrue(coords.stream().anyMatch(c -> c.startsWith(
                        "androidx.activity:activity-compose:")),
                "activity-compose : " + coords);
        assertEquals("com.exemple.demo",
                model.modules().get(0).android().orElseThrow().namespace());
    }

    @Test
    void multimoduleTemplateWiresProjectDependencyInModel() throws Exception {
        ForgeModel model = parseInstantiated("forge-multimodule");
        assertEquals(2, model.modules().size(), "core + app");
        var app = model.modules().stream()
                .filter(m -> m.name().equals("app")).findFirst().orElseThrow();
        assertEquals(ModuleType.APPLICATION, app.type());
        assertTrue(app.dependencies().stream().anyMatch(
                        d -> d instanceof ModuleDependency.ProjectDependency projet
                                && "core".equals(projet.module())),
                "dépendance de projet vers core");
    }
}
