package jo.joide.corestore;

import java.nio.file.Files;
import java.nio.file.Path;
import jo.joide.coremodel.ProjectDetector;
import jo.joide.workbench.WorkMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Critère d'acceptation L4 : création depuis un modèle produit un projet
 * buildable (marqueurs présents) ; la liste de l'accueil reflète le stockage réel
 * avec les modes compatibles détectés ; renommage et suppression marchent.
 */
class ProjectStoreTest {

    @TempDir
    Path folder;

    private ProjectStore newStore() {
        return new ProjectStore(folder.resolve("projects"));
    }

    @Test
    void emptyStoreListsNothing() {
        assertTrue(newStore().list().isEmpty(), "stockage vide : liste vide");
    }

    @Test
    void forgeTemplateInstantiatesABuildableForgeProject() throws Exception {
        ProjectStore store = newStore();
        Path project = ProjectTemplates.standard()
                .instantiate(store, "forge-java", "demo-forge");
        // Buildable : forge.json présent à la racine → mode Forge détecté.
        assertTrue(Files.isRegularFile(project.resolve("forge.json")));
        assertEquals(WorkMode.FORGE, ProjectDetector.detect(project));
        assertEquals(1, store.list().size());
        assertEquals("demo-forge", store.list().get(0).name());
        assertEquals(WorkMode.FORGE, store.list().get(0).compatibleModes().get(0));
    }

    @Test
    void gradleTemplateInstantiatesABuildableGradleProject() throws Exception {
        ProjectStore store = newStore();
        Path project = ProjectTemplates.standard()
                .instantiate(store, "gradle-app", "demo-gradle");
        assertTrue(Files.isRegularFile(project.resolve("settings.gradle")));
        assertEquals(WorkMode.GRADLE, ProjectDetector.detect(project));
    }

    @Test
    void unrecognizedFoldersAreNotProjects() throws Exception {
        ProjectStore store = newStore();
        // Dossier de fichiers bruts (aucun marqueur) : pas listé comme projet.
        Files.createDirectories(store.root().resolve("notes-brutes"));
        Files.writeString(store.root().resolve("notes-brutes/readme.txt"), "brut");
        assertTrue(store.list().isEmpty(),
                "un dossier sans marqueur n'est pas un projet");
    }

    @Test
    void mostRecentProjectFirst() throws Exception {
        ProjectStore store = newStore();
        ProjectTemplates.standard().instantiate(store, "forge-java", "ancien");
        ProjectTemplates.standard().instantiate(store, "gradle-app", "recent");
        assertEquals("recent", store.list().get(0).name(), "plus récent en tête");
        assertEquals("ancien", store.list().get(1).name());
    }

    @Test
    void renameKeepsContentAndSafeName() throws Exception {
        ProjectStore store = newStore();
        Path project = ProjectTemplates.standard().instantiate(store, "forge-java", "a");
        Path renamed = store.renameProject(project, "nom avec espaces");
        assertEquals("nom_avec_espaces", renamed.getFileName().toString(),
                "le nom est sécurisé pour le dossier");
        assertTrue(Files.isRegularFile(renamed.resolve("forge.json")),
                "le contenu suit le renommage");
        assertEquals("nom_avec_espaces", store.list().get(0).name());
    }

    @Test
    void deleteRemovesProjectOnly() throws Exception {
        ProjectStore store = newStore();
        Path project = ProjectTemplates.standard().instantiate(store, "forge-java", "p");
        Path sibling = ProjectTemplates.standard().instantiate(store, "gradle-app", "q");
        assertTrue(store.deleteProject(project));
        assertFalse(Files.exists(project));
        assertTrue(Files.exists(sibling), "la suppression reste dans le projet");
        assertEquals(1, store.list().size());
        // La racine du stockage n'est pas un projet : suppression refusée.
        assertFalse(store.deleteProject(store.root()));
    }

    @Test
    void invalidNameIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> ProjectStore.safeName(".."));
        assertThrows(IllegalArgumentException.class, () -> ProjectStore.safeName("   "));
        assertEquals("a_b", ProjectStore.safeName("a b"));
    }
}
