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
 * Lot 3 du tiroir : opérations de fichiers — création, renommage,
 * déplacement, copie bornée, corbeille restaurable (index ligne à ligne),
 * dossiers protégés, confinement. Aucun écrasement silencieux.
 */
class FsOperationsTest {

    @TempDir
    Path temp;

    private Path project() throws IOException {
        Path root = temp.resolve("projet");
        Files.createDirectories(root.resolve("app/src/main/java"));
        Files.write(root.resolve("app/src/main/java/Main.java"),
                "class Main {}".getBytes());
        Files.write(root.resolve("notes.txt"), "n".getBytes());
        return root;
    }

    @Test
    void createsFileWithContentAndParents() throws IOException {
        Path root = project();
        FsOperations.createFile(root, "app/src/main/java/Util.java",
                "public class Util {}\n");
        assertEquals("public class Util {}\n", new String(
                Files.readAllBytes(root.resolve("app/src/main/java/Util.java"))));
        // Jamais d'écrasement.
        assertThrows(IOException.class, () -> FsOperations.createFile(
                root, "app/src/main/java/Util.java", "x"));
    }

    @Test
    void createsDirectoryRefusingExisting() throws IOException {
        Path root = project();
        FsOperations.createDirectory(root, "app/src/test/java");
        assertTrue(Files.isDirectory(root.resolve("app/src/test/java")));
        assertThrows(IOException.class, () ->
                FsOperations.createDirectory(root, "app/src/test/java"));
    }

    @Test
    void movesAtomicallyRefusingExistingTarget() throws IOException {
        Path root = project();
        FsOperations.move(root, "notes.txt", "app/notes.txt");
        assertFalse(Files.exists(root.resolve("notes.txt")));
        assertTrue(Files.exists(root.resolve("app/notes.txt")));
        Files.write(root.resolve("autre.txt"), "a".getBytes());
        assertThrows(IOException.class, () ->
                FsOperations.move(root, "autre.txt", "app/notes.txt"));
    }

    @Test
    void copiesFilesAndBoundedTrees() throws IOException {
        Path root = project();
        FsOperations.copy(root, "notes.txt", "copie.txt");
        assertTrue(Files.exists(root.resolve("copie.txt")));
        FsOperations.copy(root, "app", "app-copie");
        assertTrue(Files.exists(root.resolve("app-copie/src/main/java/Main.java")));
        assertThrows(IOException.class, () ->
                FsOperations.copy(root, "app", "app-copie"));
    }

    @Test
    void trashRoundTripRestoresOriginal() throws IOException {
        Path root = project();
        FsOperations.toTrash(root, "notes.txt");
        assertFalse(Files.exists(root.resolve("notes.txt")));
        List<FsOperations.TrashEntry> entries = FsOperations.listTrash(root);
        assertEquals(1, entries.size());
        assertEquals("notes.txt", entries.get(0).originalPath());
        FsOperations.restore(root, entries.get(0));
        assertTrue(Files.exists(root.resolve("notes.txt")));
        assertEquals(0, FsOperations.listTrash(root).size());
    }

    @Test
    void restoreRefusesWhenTargetRecreated() throws IOException {
        Path root = project();
        FsOperations.toTrash(root, "notes.txt");
        Files.write(root.resolve("notes.txt"), "recréé".getBytes());
        FsOperations.TrashEntry entry = FsOperations.listTrash(root).get(0);
        assertThrows(IOException.class, () -> FsOperations.restore(root, entry));
        // L'entrée reste restaurable après nettoyage du conflit.
        Files.delete(root.resolve("notes.txt"));
        FsOperations.restore(root, entry);
        assertTrue(Files.exists(root.resolve("notes.txt")));
    }

    @Test
    void trashOfAFolderAndDefinitiveDelete() throws IOException {
        Path root = project();
        FsOperations.toTrash(root, "app/src/main/java");
        assertFalse(Files.exists(root.resolve("app/src/main/java")));
        FsOperations.TrashEntry entry = FsOperations.listTrash(root).get(0);
        assertEquals("app/src/main/java", entry.originalPath());
        assertTrue(entry.size() > 0, "la taille du dossier corbeillé est comptée");
        FsOperations.deleteForever(root, entry);
        assertEquals(0, FsOperations.listTrash(root).size());
    }

    @Test
    void emptyTrashClearsEverything() throws IOException {
        Path root = project();
        FsOperations.toTrash(root, "notes.txt");
        FsOperations.toTrash(root, "app/src/main/java/Main.java");
        assertEquals(2, FsOperations.listTrash(root).size());
        FsOperations.emptyTrash(root);
        assertEquals(0, FsOperations.listTrash(root).size());
    }

    @Test
    void protectedPathsRefused() throws IOException {
        Path root = project();
        Files.createDirectories(root.resolve(".git/objects"));
        Files.write(root.resolve(".git/HEAD"), "ref".getBytes());
        assertThrows(IOException.class, () ->
                FsOperations.toTrash(root, ".git/HEAD"));
        assertThrows(IOException.class, () ->
                FsOperations.createFile(root, ".joide/x.txt", "x"));
        assertThrows(IOException.class, () ->
                FsOperations.move(root, "notes.txt", ".git/notes.txt"));
    }

    @Test
    void escapesRefused() throws IOException {
        Path root = project();
        assertThrows(IOException.class, () ->
                FsOperations.createFile(root, "../extérieur.txt", "x"));
        assertThrows(IOException.class, () ->
                FsOperations.move(root, "notes.txt", "../x.txt"));
    }
}
