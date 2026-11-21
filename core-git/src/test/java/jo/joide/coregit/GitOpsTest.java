package jo.joide.coregit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.revwalk.RevWalk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lot 6 du tiroir : moteur git robuste — statut exact par zone (indexé /
 * non indexé / non suivi / conflits), zone de préparation, diff indexé et
 * non indexé, branches, fetch/pull/push sur un distant **local** (bare,
 * sans réseau), stash, historique avec parents (graphe), revert, reset,
 * résolution de conflits, .gitignore.
 */
class GitOpsTest {

    @TempDir
    Path temp;

    /** Dépôt de travail frais avec un premier commit. */
    private GitOps seedWork() throws Exception {
        Git.init().setDirectory(temp.resolve("work").toFile())
                .setInitialBranch("main").call().close();
        Path work = temp.resolve("work");
        GitOps ops = GitOps.open(work);
        Files.writeString(work.resolve("a.txt"), "un\n");
        ops.commitAll("initial");
        return ops;
    }

    private boolean has(List<GitOps.ChangeEntry> entries, GitOps.Area area,
            GitOps.Change change, String path) {
        return entries.stream().anyMatch(e -> e.area() == area
                && e.change() == change && e.path().equals(path));
    }

    @Test
    void exactStatusSeparatesStagedAndUnstaged() throws Exception {
        GitOps ops = seedWork();
        Path work = temp.resolve("work");
        assertTrue(ops.exactStatus().isEmpty(), "propre au départ");

        // Un nouveau fichier : non suivi, puis indexé (zone STAGED), puis
        // une modification au-dessus de l'index : zone UNSTAGED.
        Files.writeString(work.resolve("b.txt"), "deux\n");
        assertTrue(has(ops.exactStatus(), GitOps.Area.UNTRACKED,
                GitOps.Change.ADDED, "b.txt"));
        ops.stage(List.of("b.txt"));
        assertTrue(has(ops.exactStatus(), GitOps.Area.STAGED,
                GitOps.Change.ADDED, "b.txt"));
        assertFalse(has(ops.exactStatus(), GitOps.Area.UNTRACKED,
                GitOps.Change.ADDED, "b.txt"));
        Files.writeString(work.resolve("b.txt"), "deux\ntrois\n");
        // Deux zones pour le même fichier : ajouté (index) ET modifié (workdir).
        assertTrue(has(ops.exactStatus(), GitOps.Area.STAGED,
                GitOps.Change.ADDED, "b.txt"));
        assertTrue(has(ops.exactStatus(), GitOps.Area.UNSTAGED,
                GitOps.Change.MODIFIED, "b.txt"));
    }

    @Test
    void unstageReturnsToPreviousArea() throws Exception {
        GitOps ops = seedWork();
        Path work = temp.resolve("work");
        Files.writeString(work.resolve("b.txt"), "deux\n");
        ops.stage(List.of("b.txt"));
        ops.unstage(List.of("b.txt"));
        assertTrue(has(ops.exactStatus(), GitOps.Area.UNTRACKED,
                GitOps.Change.ADDED, "b.txt"), "retour non suivi");
        // Modification déjà committée : unstage → retour en zone non indexée.
        Files.writeString(work.resolve("a.txt"), "modifié\n");
        ops.stage(List.of("a.txt"));
        assertTrue(has(ops.exactStatus(), GitOps.Area.STAGED,
                GitOps.Change.MODIFIED, "a.txt"));
        ops.unstage(List.of("a.txt"));
        assertTrue(has(ops.exactStatus(), GitOps.Area.UNSTAGED,
                GitOps.Change.MODIFIED, "a.txt"), "retour non indexé");
    }

    @Test
    void stagedAndUnstagedDiffsDiffer() throws Exception {
        GitOps ops = seedWork();
        Path work = temp.resolve("work");
        Files.writeString(work.resolve("a.txt"), "un\najout-workdir\n");
        assertTrue(ops.diff("a.txt", false).stream()
                .anyMatch(l -> l.text().equals("ajout-workdir")));
        assertTrue(ops.diff("a.txt", true).isEmpty(),
                "rien d'indexé : le diff indexé est vide");
        ops.stage(List.of("a.txt"));
        assertTrue(ops.diff("a.txt", true).stream()
                .anyMatch(l -> l.text().equals("ajout-workdir")));
        assertTrue(ops.diff("a.txt", false).isEmpty(),
                "tout indexé : le diff non indexé est vide");
    }

    @Test
    void commitStagedKeepsUntrackedOut() throws Exception {
        GitOps ops = seedWork();
        Path work = temp.resolve("work");
        Files.writeString(work.resolve("b.txt"), "deux\n");
        ops.commitStaged("ne contient pas b");
        assertEquals(2, ops.graph(10).size(), "un seul commit ajouté");
        assertTrue(has(ops.exactStatus(), GitOps.Area.UNTRACKED,
                GitOps.Change.ADDED, "b.txt"), "b.txt reste non suivi");
    }

    @Test
    void branchesCreateSwitchDelete() throws Exception {
        GitOps ops = seedWork();
        Path work = temp.resolve("work");
        ops.createBranch("feature");
        assertEquals(2, ops.branches().size());
        ops.switchBranch("feature");
        assertEquals("feature", ops.currentBranch());
        Files.writeString(work.resolve("c.txt"), "trois\n");
        ops.commitAll("sur feature");
        ops.switchBranch("main");
        assertFalse(Files.exists(work.resolve("c.txt")),
                "checkout : le fichier de feature disparaît de main");
        assertThrows(IOException.class, () -> ops.deleteBranch("feature", false),
                "non fusionnée sans force : refusé, le message dit pourquoi");
        ops.deleteBranch("feature", true);
        assertEquals(1, ops.branches().size());
        assertThrows(IOException.class, () -> ops.deleteBranch("main", true),
                "supprimer la branche courante : refusé");
    }

    @Test
    void pushPullFetchOnLocalRemote() throws Exception {
        GitOps ops = seedWork();
        Path bare = temp.resolve("distant.git");
        Git.init().setBare(true).setDirectory(bare.toFile())
                .setInitialBranch("main").call().close();
        ops.setRemote(bare.toUri().toString());
        assertNull(ops.tracking(), "pas encore d'amont");

        // Push initial avec amont, puis un clone tire, modifie, pousse,
        // et le dépôt d'origine tire : le réseau roule en local.
        try (Git pusher = Git.open(temp.resolve("work").toFile())) {
            pusher.push().setRemote("origin")
                    .setRefSpecs(new org.eclipse.jgit.transport.RefSpec(
                            "refs/heads/main:refs/heads/main"))
                    .call();
            pusher.branchCreate().setUpstreamMode(
                            org.eclipse.jgit.api.CreateBranchCommand
                                    .SetupUpstreamMode.TRACK)
                    .setStartPoint("origin/main").setForce(true)
                    .setName("main").call();
        }
        assertNotNull(ops.remoteUrl());
        GitOps.SyncResult pushed = ops.push(null);
        assertTrue(pushed.summary().contains("main"),
                "résumé du push parle de la branche : " + pushed.summary());

        Git.cloneRepository().setURI(bare.toUri().toString())
                .setDirectory(temp.resolve("clone").toFile()).call().close();
        GitOps clone = GitOps.open(temp.resolve("clone"));
        Files.writeString(temp.resolve("clone/d.txt"), "quatre\n");
        clone.commitAll("depuis le clone");
        clone.setRemote(bare.toUri().toString());
        clone.push(null);

        GitOps.SyncResult fetched = ops.fetch(null);
        assertFalse(fetched.summary().isEmpty());
        GitOps.SyncResult pulled = ops.pull(null);
        assertNotNull(pulled);
        assertTrue(has(ops.exactStatus(), GitOps.Area.STAGED,
                GitOps.Change.ADDED, "d.txt") || Files.exists(
                temp.resolve("work/d.txt")), "d.txt tiré du distant");
    }

    @Test
    void stashCreateListApplyDrop() throws Exception {
        GitOps ops = seedWork();
        Path work = temp.resolve("work");
        Files.writeString(work.resolve("a.txt"), "en cours\n");
        assertNotNull(ops.stashCreate("wip"));
        assertTrue(ops.exactStatus().isEmpty(),
                "le working dir est propre après le stash");
        assertEquals(1, ops.stashList().size());
        assertEquals("wip", ops.stashList().get(0).message());
        ops.stashApply(0, true);
        assertEquals(0, ops.stashList().size(), "appliqué avec effacement");
        assertEquals("en cours\n", Files.readString(work.resolve("a.txt")));
        assertThrows(IOException.class, () -> ops.stashApply(5, false),
                "stash inexistant : erreur claire");
    }

    @Test
    void graphCarriesParents() throws Exception {
        GitOps ops = seedWork();
        Files.writeString(temp.resolve("work/a.txt"), "suite\n");
        ops.commitAll("second");
        List<GitOps.CommitNode> graph = ops.graph(10);
        assertEquals(2, graph.size());
        assertEquals(1, graph.get(0).parents().size());
        assertEquals(graph.get(1).id(), graph.get(0).parents().get(0),
                "le parent du second est le premier (graphe)");
        assertTrue(graph.get(1).parents().isEmpty(), "racine sans parent");
    }

    @Test
    void revertAndReset() throws Exception {
        GitOps ops = seedWork();
        Path work = temp.resolve("work");
        Files.writeString(work.resolve("a.txt"), "changé\n");
        ops.commitAll("à annuler");
        String revertId = ops.revert("HEAD");
        assertFalse(revertId.isBlank());
        assertEquals("un\n", Files.readString(work.resolve("a.txt")),
                "revert restaure le contenu d'avant");

        // Reset dur de deux crans : retour au premier commit (confirmation
        // côté UI ; après le revert, HEAD~2 = le commit initial).
        ops.reset(GitOps.ResetMode.HARD, "HEAD~2");
        assertEquals(1, ops.graph(10).size());
        assertEquals("un\n", Files.readString(work.resolve("a.txt")));
    }

    @Test
    void conflictResolutionOursAndTheirs() throws Exception {
        GitOps ops = seedWork();
        Path work = temp.resolve("work");
        ops.createAndSwitch("cote");
        Files.writeString(work.resolve("a.txt"), "conflit-cote\n");
        ops.commitAll("côté");
        ops.switchBranch("main");
        Files.writeString(work.resolve("a.txt"), "conflit-moi\n");
        ops.commitAll("moi");

        try (Git git = Git.open(work.toFile())) {
            git.merge().include(git.getRepository().resolve("cote")).call();
        }
        assertEquals(List.of("a.txt"), ops.conflicts(), "conflit détecté");
        ops.resolveConflict("a.txt", GitOps.Resolution.THEIRS, null);
        assertEquals("conflit-cote\n", Files.readString(work.resolve("a.txt")));
        assertTrue(ops.conflicts().isEmpty(), "résolu : plus de conflit");
        ops.commitStaged("fusion résolue");
    }

    @Test
    void cherryPickReplaysACommit() throws Exception {
        GitOps ops = seedWork();
        Path work = temp.resolve("work");
        ops.createAndSwitch("feature");
        Files.writeString(work.resolve("f.txt"), "feature\n");
        ops.commitAll("ajout feature");
        String featureId = ops.graph(1).get(0).id();
        ops.switchBranch("main");
        assertFalse(Files.exists(work.resolve("f.txt")));
        ops.cherryPick(featureId);
        assertEquals("feature\n", Files.readString(work.resolve("f.txt")));
    }

    @Test
    void ignoreHidesPatternsAndInitWorks() throws Exception {
        Path fresh = temp.resolve("neuf");
        Files.createDirectories(fresh);
        GitOps ops = GitOps.init(fresh);
        assertEquals("main", ops.currentBranch());

        Files.writeString(fresh.resolve("trace.log"), "bruit\n");
        assertTrue(has(ops.exactStatus(), GitOps.Area.UNTRACKED,
                GitOps.Change.ADDED, "trace.log"));
        ops.ignore("*.log");
        assertFalse(has(ops.exactStatus(), GitOps.Area.UNTRACKED,
                GitOps.Change.ADDED, "trace.log"),
                ".log ignoré via .gitignore");
        assertTrue(ops.isIgnored("*.log"));
        ops.ignore("*.log"); // doublon : aucune seconde ligne.
        assertEquals(1, Files.readAllLines(fresh.resolve(".gitignore")).stream()
                .filter(line -> line.equals("*.log")).count());
    }

    @Test
    void amendAndSignoffRewriteTheCommit() throws Exception {
        GitOps ops = seedWork();
        Path work = temp.resolve("work");
        Files.writeString(work.resolve("a.txt"), "amendé\n");
        ops.stage(List.of("a.txt"));
        ops.commitStaged("message initial");
        ops.commitStaged("message réécrit", true, true);
        assertEquals(2, ops.graph(10).size(), "amend réécrit, n'ajoute pas");
        GitOps.CommitNode head = ops.graph(1).get(0);
        assertEquals("message réécrit", head.message());
        // Signoff : le trailer Signed-off-by vit dans le message complet.
        try (Git git = Git.open(work.toFile());
             RevWalk walk = new RevWalk(git.getRepository())) {
            String full = walk.parseCommit(git.getRepository()
                    .resolve("HEAD")).getFullMessage();
            assertTrue(full.contains("Signed-off-by:"),
                    "trailer signoff présent : " + full);
        }
    }

    @Test
    void mergeBranchAndAbortConflictedMerge() throws Exception {
        GitOps ops = seedWork();
        Path work = temp.resolve("work");
        ops.createAndSwitch("feature");
        Files.writeString(work.resolve("f.txt"), "feature\n");
        ops.commitAll("sur feature");
        ops.switchBranch("main");
        ops.merge("feature");
        assertEquals("feature\n", Files.readString(work.resolve("f.txt")),
                "fusion rapide intègre feature");

        // Fusion en conflit puis abandon : retour à l'état d'avant.
        ops.createAndSwitch("conflit");
        Files.writeString(work.resolve("a.txt"), "côté\n");
        ops.commitAll("côté");
        ops.switchBranch("main");
        Files.writeString(work.resolve("a.txt"), "moi\n");
        ops.commitAll("moi");
        try (Git git = Git.open(work.toFile())) {
            git.merge().include(git.getRepository().resolve("conflit")).call();
        }
        assertFalse(ops.conflicts().isEmpty(), "conflit posé");
        ops.abortMerge();
        assertTrue(ops.conflicts().isEmpty(), "état de fusion purgé");
        assertEquals("moi\n", Files.readString(work.resolve("a.txt")),
                "reset dur : le workdir revient à HEAD");
    }

    @Test
    void identityIsStoredAndReadBack() throws Exception {
        GitOps ops = seedWork();
        ops.setIdentity("Olson", "olson@exemple.fr");
        GitOps.Identity identity = ops.identity();
        assertEquals("Olson", identity.name());
        assertEquals("olson@exemple.fr", identity.email());
    }

    @Test
    void refsForPointsHeadBranchAndTag() throws Exception {
        GitOps ops = seedWork();
        String head = ops.graph(1).get(0).id();
        List<GitOps.RefBadge> refs = ops.refsFor(head);
        assertTrue(refs.stream().anyMatch(r -> r.name().equals("HEAD")));
        assertTrue(refs.stream().anyMatch(r -> r.name().equals("main")
                && r.kind().equals("br")));
        try (Git git = Git.open(temp.resolve("work").toFile())) {
            git.tag().setName("v0.1.0").call();
        }
        assertTrue(ops.refsFor(head).stream()
                .anyMatch(r -> r.name().equals("v0.1.0") && r.kind().equals("tag")),
                "étiquette détectée");
    }

    @Test
    void remoteBranchesListedAfterFetch() throws Exception {
        GitOps ops = seedWork();
        Path bare = temp.resolve("distant.git");
        Git.init().setBare(true).setDirectory(bare.toFile())
                .setInitialBranch("main").call().close();
        ops.setRemote(bare.toUri().toString());
        try (Git pusher = Git.open(temp.resolve("work").toFile())) {
            pusher.push().setRemote("origin").setRefSpecs(
                    new org.eclipse.jgit.transport.RefSpec(
                            "refs/heads/main:refs/heads/main")).call();
        }
        ops.fetch(null);
        List<GitOps.BranchInfo> remotes = ops.remoteBranches();
        assertEquals(List.of("origin/main"), remotes.stream()
                .map(GitOps.BranchInfo::name).toList());
    }

    @Test
    void diffCarriesHunkHeaders() throws Exception {
        GitOps ops = seedWork();
        Path work = temp.resolve("work");
        Files.writeString(work.resolve("a.txt"), "un\ndeux\n");
        List<GitOps.DiffLine> lines = ops.diff("a.txt", false);
        assertTrue(lines.stream().anyMatch(l -> l.kind() == GitOps.LineKind.HUNK
                && l.text().startsWith("@@")), "hunk @@ présent");
    }

    @Test
    void conflictResolutionBothConcatenates() throws Exception {
        GitOps ops = seedWork();
        Path work = temp.resolve("work");
        ops.createAndSwitch("cote");
        Files.writeString(work.resolve("a.txt"), "leur\n");
        ops.commitAll("côté");
        ops.switchBranch("main");
        Files.writeString(work.resolve("a.txt"), "nôtre\n");
        ops.commitAll("moi");
        try (Git git = Git.open(work.toFile())) {
            git.merge().include(git.getRepository().resolve("cote")).call();
        }
        ops.resolveConflict("a.txt", GitOps.Resolution.BOTH, null);
        assertEquals("nôtre\nleur\n", Files.readString(work.resolve("a.txt")),
                "les deux : nôtre puis leur, sans marqueurs");
    }

    @Test
    void starterGitignoreExcludesBuildArtifacts() throws Exception {
        Path fresh = temp.resolve("avec-ignore");
        Files.createDirectories(fresh);
        GitOps ops = GitOps.init(fresh, true);
        assertTrue(ops.isIgnored("build/"));
        assertTrue(ops.isIgnored(".gradle/"));
        assertTrue(ops.isIgnored("local.properties"));
    }

    @Test
    void openOnMissingRepositoryThrowsHonestly() {
        assertThrows(IOException.class, () ->
                GitOps.open(temp.resolve("inexistant")));
    }
}
