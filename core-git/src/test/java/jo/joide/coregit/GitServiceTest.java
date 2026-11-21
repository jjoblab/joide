package jo.joide.coregit;

import java.io.IOException;
import org.eclipse.jgit.api.errors.GitAPIException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Banc de la couche git (écran Git du studio) : init implicite par JGit,
 * statut après écriture, compte de lignes du diff, historique, commit.
 */
final class GitServiceTest {

    @Test
    void unDepotNeufEstOuvertEtVide(@TempDir Path dir) throws IOException, GitAPIException {
        try (var git = org.eclipse.jgit.api.Git.init().setDirectory(dir.toFile()).call()) {
            GitService service = new GitService(dir);
            assertTrue(service.isOpen());
            assertTrue(service.status().isEmpty(), "aucun changement au départ");
            assertEquals("main", service.currentBranch());
        }
    }

    @Test
    void modificationVueAuStatutAvecComptes(@TempDir Path dir) throws IOException, GitAPIException {
        try (var git = org.eclipse.jgit.api.Git.init().setDirectory(dir.toFile()).call()) {
            Path file = dir.resolve("Main.java");
            Files.writeString(file, "class Main {}\n");
            git.add().addFilepattern(".").call();
            git.commit().setMessage("commit initial").call();

            Files.writeString(file, "class Main {\n    public static void main() {}\n}\n");
            GitService service = new GitService(dir);
            List<GitService.FileStatus> status = service.status();
            assertEquals(1, status.size());
            assertEquals("Main.java", status.get(0).path());
            assertEquals(GitService.ChangeKind.MODIFIED, status.get(0).kind());
            assertTrue(status.get(0).added() >= 2, "lignes ajoutées comptées");
            assertTrue(status.get(0).removed() >= 1, "lignes retirées comptées");
        }
    }

    @Test
    void leDiffColoreLesLignes(@TempDir Path dir) throws IOException, GitAPIException {
        try (var git = org.eclipse.jgit.api.Git.init().setDirectory(dir.toFile()).call()) {
            Path file = dir.resolve("Main.java");
            Files.writeString(file, "a\n");
            git.add().addFilepattern(".").call();
            git.commit().setMessage("initial").call();
            Files.writeString(file, "b\n");
            GitService service = new GitService(dir);
            List<GitService.DiffLine> lines = service.diff("Main.java");
            assertTrue(lines.stream().anyMatch(l -> l.kind() == GitService.LineKind.REMOVE));
            assertTrue(lines.stream().anyMatch(l -> l.kind() == GitService.LineKind.ADD));
        }
    }

    @Test
    void historiqueEtCommitRoulent(@TempDir Path dir) throws IOException, GitAPIException {
        try (var git = org.eclipse.jgit.api.Git.init().setDirectory(dir.toFile()).call()) {
            Files.writeString(dir.resolve("a.txt"), "a\n");
            GitService service = new GitService(dir);
            String id = service.commit(List.of(), "premier");
            assertFalse(id.isBlank());
            Files.writeString(dir.resolve("b.txt"), "b\n");
            service.commit(List.of("b.txt"), "second");
            List<GitService.CommitLog> log = service.log(10);
            assertEquals(2, log.size());
            assertEquals("second", log.get(0).message());
        }
    }
}
