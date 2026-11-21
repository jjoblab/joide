package jo.joide.files;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lot 1 du tiroir : arborescence bornée, annulable, dossiers générés ignorés,
 * tri dossiers d'abord puis insensible à la casse, chemins confinés,
 * catégories d'icônes stables.
 */
class FileTreeTest {

    @TempDir
    Path temp;

    private Path project() throws IOException {
        Path root = temp.resolve("projet");
        Files.createDirectories(root.resolve("app/src/main/java"));
        Files.createDirectories(root.resolve("app/build"));
        Files.createDirectories(root.resolve(".git"));
        Files.write(root.resolve("app/src/main/java/Main.java"),
                "class Main {}".getBytes());
        Files.write(root.resolve("Zebra.txt"), "z".getBytes());
        Files.write(root.resolve("apple.xml"), "a".getBytes());
        Files.write(root.resolve(".hidden"), "h".getBytes());
        Files.write(root.resolve("release.jks"), "k".getBytes());
        return root;
    }

    @Test
    void listsDirectoriesFirstThenFilesCaseInsensitive() throws IOException {
        Path root = project();
        FileTree.Listing listing = FileTree.list(root, ".", false,
                FileTree.DEFAULT_MAX_ENTRIES, null);

        List<String> names = listing.entries().stream().map(FileTree.Entry::name).toList();
        // Dossiers d'abord (app), fichiers ensuite, insensible à la casse.
        assertEquals(0, names.indexOf("app"));
        assertTrue(names.indexOf("app") < names.indexOf("Zebra.txt"));
        assertTrue(names.indexOf("apple.xml") < names.indexOf("Zebra.txt"),
                "tri insensible à la casse");
        // .git et app/build ignorés, fichier caché filtré par défaut.
        assertFalse(names.contains(".git"));
        assertFalse(names.contains("build"));
        assertFalse(names.contains(".hidden"));
        assertFalse(listing.truncated());
    }

    @Test
    void hiddenFilesShownWhenAsked() throws IOException {
        Path root = project();
        FileTree.Listing listing = FileTree.list(root, ".", true,
                FileTree.DEFAULT_MAX_ENTRIES, null);
        assertTrue(listing.entries().stream()
                .anyMatch(e -> e.name().equals(".hidden")));
    }

    @Test
    void subdirectoryListingUsesRelativePaths() throws IOException {
        Path root = project();
        FileTree.Listing listing = FileTree.list(root, "app/src/main/java",
                false, FileTree.DEFAULT_MAX_ENTRIES, null);
        assertEquals(1, listing.entries().size());
        assertEquals("app/src/main/java/Main.java",
                listing.entries().get(0).relativePath(),
                "chemin relatif à la RACINE (ouvrir un fichier ne devine rien)");
        assertTrue(listing.entries().get(0).iconCategory().equals("java"));
    }

    @Test
    void largeFoldersTruncateHonestly() throws IOException {
        Path root = Files.createDirectories(temp.resolve("gros"));
        for (int i = 0; i < 30; i++) {
            Files.write(root.resolve("f" + String.format("%02d", i) + ".txt"),
                    "x".getBytes());
        }
        FileTree.Listing listing = FileTree.list(root, ".", false, 10, null);
        assertEquals(10, listing.entries().size());
        assertTrue(listing.truncated(), "la troncature se dit, jamais muette");
    }

    @Test
    void cancellationStopsImmediately() throws IOException {
        Path root = project();
        FileTree.Listing listing = FileTree.list(root, ".", false,
                FileTree.DEFAULT_MAX_ENTRIES, () -> true);
        assertTrue(listing.truncated(), "annulé d'entrée : résultat borné, vrai");
    }

    @Test
    void pathTraversalIsRefused() throws IOException {
        Path root = project();
        assertThrows(IOException.class, () -> FileTree.list(root, "..", false,
                10, null));
        assertThrows(IOException.class, () -> FileTree.list(root, "/etc", false,
                10, null));
        assertThrows(IOException.class, () ->
                FileTree.list(root, "app/../../..", false, 10, null));
    }

    @Test
    void iconCategoriesAreStable() {
        assertEquals("folder", FileIcons.categoryOf("src", true));
        assertEquals("java", FileIcons.categoryOf("Main.JAVA", false));
        assertEquals("xml", FileIcons.categoryOf("layout.xml", false));
        assertEquals("image", FileIcons.categoryOf("logo.png", false));
        assertEquals("secret", FileIcons.categoryOf("store.jks", false));
        assertEquals("vcs", FileIcons.categoryOf(".gitignore", false));
        assertEquals("file", FileIcons.categoryOf("Makefile", false));
        assertTrue(FileIcons.isSecretCategory("secret"));
        assertFalse(FileIcons.isSecretCategory("java"));
    }
}
