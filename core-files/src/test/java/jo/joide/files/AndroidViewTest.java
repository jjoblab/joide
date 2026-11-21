package jo.joide.files;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lot 3 du tiroir : squelette de la vue Android — modules détectés par
 * build.gradle, Gradle Scripts et Manifestes groupés, java/res par module,
 * un nœud n'apparaît que si son contenu existe.
 */
class AndroidViewTest {

    @TempDir
    Path temp;

    private Path project() throws IOException {
        Path root = temp.resolve("projet");
        Files.createDirectories(root.resolve("app/src/main/java"));
        Files.createDirectories(root.resolve("app/src/main/res/layout"));
        Files.createDirectories(root.resolve("app/src/test/java"));
        Files.write(root.resolve("settings.gradle"), "s".getBytes());
        Files.write(root.resolve("gradle.properties"), "g".getBytes());
        Files.write(root.resolve("app/build.gradle"), "b".getBytes());
        Files.write(root.resolve("app/src/main/AndroidManifest.xml"), "m".getBytes());
        Files.write(root.resolve("app/src/main/java/Main.java"),
                "class Main {}".getBytes());
        Files.write(root.resolve("app/src/main/res/layout/a.xml"), "a".getBytes());
        return root;
    }

    @Test
    void buildsGroupsOverRealFilesOnly() throws IOException {
        List<AndroidView.Node> nodes = AndroidView.build(project(), 10, null);
        // Gradle Scripts, Manifestes, puis le module app (la racine ne
        // construit pas : pas de build.gradle racine).
        assertEquals(3, nodes.size());
        AndroidView.Node scripts = nodes.get(0);
        assertTrue(scripts.group());
        assertEquals(AndroidView.LABEL_SCRIPTS, scripts.display());
        // settings.gradle, gradle.properties + build.gradle du module app.
        assertEquals(3, scripts.children().size());
        AndroidView.Node moduleScript = scripts.children().get(2);
        assertEquals("build.gradle", moduleScript.display());
        assertEquals("app", moduleScript.qualifier());
        assertEquals("app/build.gradle", moduleScript.relativePath());

        AndroidView.Node manifests = nodes.get(1);
        assertEquals(AndroidView.LABEL_MANIFESTS, manifests.display());
        assertEquals(1, manifests.children().size());
        assertEquals("AndroidManifest.xml", manifests.children().get(0).display());
        assertEquals("app", manifests.children().get(0).qualifier());

        AndroidView.Node module = nodes.get(2);
        assertEquals("app", module.display());
        assertFalseGroup(module);
        assertEquals("app", module.relativePath());
        // java (main), java (test), res — tous réels.
        assertEquals(3, module.children().size());
        assertEquals(AndroidView.LABEL_JAVA, module.children().get(0).display());
        assertEquals("app/src/main/java", module.children().get(0).relativePath());
        assertEquals(AndroidView.LABEL_JAVA_TEST, module.children().get(1).display());
        assertEquals(AndroidView.LABEL_RES, module.children().get(2).display());
    }

    private void assertFalseGroup(AndroidView.Node node) {
        assertTrue(!node.group() && node.directory());
    }

    @Test
    void rootModuleAppearsWhenItBuilds() throws IOException {
        Path root = project();
        Files.write(root.resolve("build.gradle"), "b".getBytes());
        Files.createDirectories(root.resolve("src/main/java"));
        List<AndroidView.Node> nodes = AndroidView.build(root, 10, null);
        // Le module racine porte le nom du projet et n'a pas de chemin relatif.
        AndroidView.Node rootModule = nodes.stream()
                .filter(n -> "projet".equals(n.display()))
                .findFirst().orElseThrow();
        assertNull(rootModule.relativePath());
        assertEquals(1, rootModule.children().size());
        assertEquals("src/main/java", rootModule.children().get(0).relativePath());
    }

    @Test
    void missingContentMeansNoNode() throws IOException {
        Path root = project();
        // Sans manifeste, le groupe Manifestes disparaît.
        Files.delete(root.resolve("app/src/main/AndroidManifest.xml"));
        List<AndroidView.Node> nodes = AndroidView.build(root, 10, null);
        assertEquals(2, nodes.size());
        assertEquals(AndroidView.LABEL_SCRIPTS, nodes.get(0).display());
    }

    @Test
    void moduleCountIsBounded() throws IOException {
        Path root = project();
        for (int i = 0; i < 5; i++) {
            Path module = root.resolve("module" + i);
            Files.createDirectories(module);
            Files.write(module.resolve("build.gradle"), "b".getBytes());
        }
        List<AndroidView.Node> nodes = AndroidView.build(root, 3, null);
        // Racine (settings.gradle) + app + module0 dans la liste des modules,
        // mais seuls ceux avec du contenu réel produisent un nœud : app seul.
        long moduleNodes = nodes.stream()
                .filter(n -> n.id().startsWith("module:")).count();
        assertEquals(1, moduleNodes);
        assertNotNull(nodes);
    }

    @Test
    void emptyProjectGivesEmptySkeleton() throws IOException {
        Path root = temp.resolve("vide");
        Files.createDirectories(root);
        assertEquals(0, AndroidView.build(root, 10, null).size());
    }
}
