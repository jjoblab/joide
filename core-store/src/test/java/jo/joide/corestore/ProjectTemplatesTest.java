package jo.joide.corestore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Portefeuille des modèles de création (refonte) : six modèles au format
 * {@code forge.json} réel, substitution de paquet (chemins + contenus) et
 * dérivation par défaut.
 */
class ProjectTemplatesTest {

    @TempDir
    Path folder;

    private ProjectStore newStore() {
        return new ProjectStore(folder.resolve("projects"));
    }

    @Test
    void sixTemplatesAcrossAllKinds() {
        List<ProjectTemplates.Template> all = ProjectTemplates.standard().all();
        assertEquals(6, all.size(), "portefeuille attendu : 6 modèles");
        assertEquals(5, all.stream().filter(ProjectTemplates.Template::forge).count(),
                "cinq modèles Forge (ADR-009 : mode actif)");
        assertEquals(1, all.stream()
                        .filter(t -> t.kind() == ProjectTemplates.Kind.GRADLE).count(),
                "un modèle Gradle conservé pour le mode à venir");
        assertEquals(2, all.stream()
                        .filter(t -> t.kind() == ProjectTemplates.Kind.LIBRARY).count(),
                "deux bibliothèques (Java, Kotlin)");
        assertEquals(3, all.stream()
                        .filter(t -> t.kind() == ProjectTemplates.Kind.APPLICATION).count(),
                "trois applications (XML, Compose, multi-modules)");
    }

    @Test
    void packageSubstitutionAlignsPathsContentsAndNamespace() throws Exception {
        Path project = ProjectTemplates.standard()
                .instantiate(newStore(), "forge-android-xml",
                        "MonAppli", "fr.exemple.monappli");
        // Chemin : le paquet devient une arborescence.
        Path main = project.resolve(
                "app/src/main/java/fr/exemple/monappli/MainActivity.java");
        assertTrue(Files.isRegularFile(main), "la source vit sous le paquet choisi");
        // Contenu : package, import databinding et namespace du manifeste alignés.
        String source = Files.readString(main);
        assertTrue(source.startsWith("package fr.exemple.monappli;"),
                "le package de la source est celui choisi : " + source.substring(0, 60));
        assertTrue(source.contains("import fr.exemple.monappli.databinding.ActivityMainBinding;"),
                "l'import ViewBinding suit le paquet");
        String manifest = Files.readString(
                project.resolve("app/src/main/AndroidManifest.xml"));
        assertTrue(manifest.contains("package=\"fr.exemple.monappli\""),
                "le manifeste porte le paquet choisi");
        String model = Files.readString(project.resolve("forge.json"));
        assertTrue(model.contains("\"namespace\": \"fr.exemple.monappli\""),
                "le namespace forge.json suit le paquet");
        // ${name} substitué dans les libellés.
        String strings = Files.readString(
                project.resolve("app/src/main/res/values/strings.xml"));
        assertTrue(strings.contains("MonAppli"), "le nom du projet apparaît");
        assertTrue(!strings.contains("${name}"), "aucun marqueur résiduel");
    }

    @Test
    void composeTemplateCarriesMaterial3Dependencies() throws Exception {
        Path project = ProjectTemplates.standard()
                .instantiate(newStore(), "forge-compose", "Demo", "com.exemple.demo");
        String model = Files.readString(project.resolve("forge.json"));
        assertTrue(model.contains("material3:material3"),
                "Material 3 au classpath");
        assertTrue(model.contains("\"language\": \"kotlin\""),
                "module Kotlin");
        assertTrue(model.contains("activity-compose"),
                "activity-compose (setContent)");
    }

    @Test
    void multimoduleTemplateWiresProjectDependency() throws Exception {
        Path project = ProjectTemplates.standard()
                .instantiate(newStore(), "forge-multimodule", "Multi", "com.exemple.multi");
        String model = Files.readString(project.resolve("forge.json"));
        assertTrue(model.contains("\"project\": \"core\""),
                "dépendance de projet app → core");
        assertTrue(Files.isRegularFile(project.resolve(
                "core/src/main/java/com/exemple/multi/core/Salutations.java")),
                "le module core vit sous le paquet");
    }

    @Test
    void defaultPackageDerivesFromName() {
        assertEquals("com.example.monapplication",
                ProjectTemplates.defaultPackage("Mon Application"));
        assertEquals("com.example.app42",
                ProjectTemplates.defaultPackage("42"),
                "un nom numérique reçoit un préfixe (paquet Java valide)");
    }

    @Test
    void invalidPackageIsRejectedBeforeAnyFileIsWritten() {
        assertThrows(IllegalArgumentException.class, () -> ProjectTemplates.standard()
                .instantiate(newStore(), "forge-java", "p", "Paquet.Invalide"));
        assertThrows(IllegalArgumentException.class, () -> ProjectTemplates.standard()
                .instantiate(newStore(), "forge-java", "p", "a..b"));
    }
}
