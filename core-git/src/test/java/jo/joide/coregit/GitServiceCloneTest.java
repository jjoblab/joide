package jo.joide.coregit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lot L10 du clonage (parcours d'accueil) : le clonage réel d'un dépôt
 * **local** (URL file, sans réseau), la progression notifiée, le refus d'un
 * dossier occupé, et le **nettoyage du demi-dépôt** laissé par un clonage
 * raté (comme la référence : la tentative suivante doit pouvoir repartir).
 */
class GitServiceCloneTest {

    @TempDir
    Path temp;

    /** Dépôt local jouant le distant : un commit avec forge.json (projet). */
    private Path seedDistant() throws Exception {
        Path distant = temp.resolve("distant");
        Git.init().setDirectory(distant.toFile())
                .setInitialBranch("main").call().close();
        Files.writeString(distant.resolve("forge.json"),
                "{\"modules\":[{\"name\":\"app\",\"type\":\"application\"}]}\n");
        try (Git git = Git.open(distant.toFile())) {
            git.add().addFilepattern(".").call();
            git.commit().setMessage("initial").setAuthor("T", "t@ex.io").call();
        }
        return distant;
    }

    @Test
    void cloneUnDépôtLocalAvecProgression() throws Exception {
        Path distant = seedDistant();
        List<String> notifications = new ArrayList<>();
        Path cible = temp.resolve("cible");
        Path cloné = GitService.clone(distant.toUri().toString(), cible, null,
                (fraction, tâche) -> {
                    if (fraction >= 0 || tâche != null) {
                        notifications.add(fraction + "/" + tâche);
                    }
                }, 30);
        assertEquals(cible, cloné);
        assertTrue(Files.isRegularFile(cible.resolve("forge.json")),
                "le fichier du distant est cloné");
        assertTrue(Files.isDirectory(cible.resolve(".git")), "c'est un vrai dépôt");
        assertEquals("main", new GitService(cloné).currentBranch());
        assertFalse(notifications.isEmpty(), "la progression a été notifiée");
    }

    @Test
    void dossierOccupéRefuséAvantToutClonage() throws Exception {
        Path distant = seedDistant();
        Path occupé = temp.resolve("occupé");
        Files.createDirectories(occupé.resolve(".git"));
        IOException erreur = assertInstanceOf(IOException.class,
                org.junit.jupiter.api.Assertions.assertThrows(Exception.class,
                        () -> GitService.clone(distant.toUri().toString(),
                                occupé, null, null, 30)));
        assertTrue(erreur.getMessage().contains("n'est pas vide"));
        // Le contenu existant est préservé (aucun nettoyage sauvage).
        assertTrue(Files.isDirectory(occupé.resolve(".git")));
    }

    @Test
    void clonageRatéNettoieLeDemiDépôt() {
        // URL inexistante : JGit échoue — le dossier cible ne doit rien garder.
        Path cible = temp.resolve("raté");
        org.junit.jupiter.api.Assertions.assertThrows(Exception.class,
                () -> GitService.clone(
                        temp.resolve("distant-inexistant").toUri().toString(),
                        cible, null, null, 5));
        assertFalse(Files.exists(cible.resolve(".git")),
                "pas de demi-dépôt après échec");
    }

    @Test
    void urlVideMessageClair() {
        IOException erreur = assertInstanceOf(IOException.class,
                org.junit.jupiter.api.Assertions.assertThrows(Exception.class,
                        () -> GitService.clone("  ", temp.resolve("x"),
                                null, null, 30)));
        assertTrue(erreur.getMessage().contains("vide"));
    }

    @Test
    void cloneIntoSansOptionsresteCompatible() throws Exception {
        Path distant = seedDistant();
        Path cible = temp.resolve("compat");
        GitService.cloneInto(distant.toUri().toString(), cible);
        assertTrue(Files.isRegularFile(cible.resolve("forge.json")));
    }
}
