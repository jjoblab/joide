package jo.joide.files;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lot 3 du tiroir : compte récursif borné (badge d'éléments des dossiers
 * repliés) et chaîne compacte (« com.example.app » sur une ligne).
 */
class FileTreeLot3Test {

    @TempDir
    Path temp;

    private Path project() throws IOException {
        Path root = temp.resolve("projet");
        Files.createDirectories(root.resolve("app/src/main/java/com/example/app"));
        Files.createDirectories(root.resolve("app/src/main/java/com/example/other"));
        Files.createDirectories(root.resolve("app/src/test/java"));
        Files.createDirectories(root.resolve("app/build/tmp"));
        Files.write(root.resolve("app/src/main/java/com/example/app/Main.java"),
                "class Main {}".getBytes());
        Files.write(root.resolve("app/src/main/java/com/example/other/Util.java"),
                "class Util {}".getBytes());
        return root;
    }

    @Test
    void countsAllEntriesIgnoringGenerated() throws IOException {
        Path root = project();
        // app : 9 dossiers (src, src/main, src/main/java, com, com/example,
        // com/example/app, com/example/other, src/test, src/test/java)
        // + 2 fichiers ; build ignoré.
        FileTree.Count count = FileTree.countAll(root, "app", 100, null);
        assertEquals(11, count.entries());
        assertFalse(count.capped());
    }

    @Test
    void countIsCappedAndSaysSo() throws IOException {
        Path root = project();
        FileTree.Count count = FileTree.countAll(root, "app", 5, null);
        assertEquals(5, count.entries());
        assertTrue(count.capped(), "le plafond atteint se dit, jamais de compte menteur");
    }

    @Test
    void countIsCancelable() throws IOException {
        Path root = project();
        FileTree.Count count = FileTree.countAll(root, "app", 100, () -> true);
        assertTrue(count.capped());
    }

    @Test
    void compactChainJoinsSingleChildDirectories() throws IOException {
        Path root = project();
        // L'UI appelle sur l'enfant package (com) : com ne contient que
        // example, example contient app ET other → la chaîne s'arrête là.
        FileTree.Chain chain = FileTree.compactChain(root,
                "app/src/main/java/com", 10, null);
        assertEquals("com.example", chain.display());
        assertEquals("app/src/main/java/com/example", chain.lastRelative());
        assertEquals(2, chain.levels());
    }

    @Test
    void chainStopsAtDepthCap() throws IOException {
        Path root = project();
        FileTree.Chain chain = FileTree.compactChain(root,
                "app/src/main/java/com", 1, null);
        assertEquals(1, chain.levels());
        assertEquals("com", chain.display());
    }

    @Test
    void chainStopsWhenAFileSitsAlongside() throws IOException {
        Path root = project();
        Files.write(root.resolve("app/src/main/java/com/Premier.java"),
                "x".getBytes());
        FileTree.Chain chain = FileTree.compactChain(root,
                "app/src/main/java/com", 10, null);
        // com contient example ET Premier.java : pas de chaîne du tout.
        assertEquals(1, chain.levels());
        assertEquals("com", chain.display());
        assertEquals("app/src/main/java/com", chain.lastRelative());
    }

    @Test
    void chainIsCancelable() throws IOException {
        Path root = project();
        FileTree.Chain chain = FileTree.compactChain(root,
                "app/src/main/java/com", 10, () -> true);
        assertEquals(1, chain.levels());
    }
}
