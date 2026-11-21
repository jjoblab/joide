package jo.joide.coregit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Couche git du dépôt de l'atelier (conception §5 : import/clonage, écran
 * « Git » du studio de previews). Pur JVM (JGit) : statut, diff, historique,
 * commit, clonage — les types sont consommés par le fragment git et par
 * l'accueil (clonage). Aucune dépendance Android (règle check-deps).
 *
 * <p>Champs : {@link FileStatus} (état + compte de lignes), {@link DiffLine}
 * (type + texte), {@link CommitLog} (identifiant, auteur, date, message).</p>
 */
public final class GitService {

    private final Path repositoryRoot;

    public GitService(Path repositoryRoot) {
        this.repositoryRoot = repositoryRoot;
    }

    /** Ouvert et sain ? (un dépôt absent ou cassé se dit, jamais muet.) */
    public boolean isOpen() {
        try {
            return org.eclipse.jgit.api.Git.open(repositoryRoot.toFile()) != null;
        } catch (IOException e) {
            return false;
        }
    }

    /** Nom de la branche courante (HEAD détaché : l'identifiant court). */
    public String currentBranch() {
        try (org.eclipse.jgit.api.Git git = org.eclipse.jgit.api.Git.open(
                repositoryRoot.toFile())) {
            return git.getRepository().getBranch();
        } catch (IOException e) {
            return "?";
        }
    }

    /**
     * Suivi distant de la branche courante : branche distante, commits en avance
     * (à pousser) et en retard (à tirer). {@code null} sans branche suivie.
     */
    public Tracking tracking() {
        try (org.eclipse.jgit.api.Git git = org.eclipse.jgit.api.Git.open(
                repositoryRoot.toFile())) {
            org.eclipse.jgit.lib.Repository repo = git.getRepository();
            org.eclipse.jgit.lib.BranchTrackingStatus status =
                    org.eclipse.jgit.lib.BranchTrackingStatus.of(repo, repo.getBranch());
            if (status == null) {
                return null;
            }
            return new Tracking(
                    org.eclipse.jgit.lib.Repository.shortenRefName(
                            status.getRemoteTrackingBranch()),
                    status.getAheadCount(), status.getBehindCount());
        } catch (IOException e) {
            return null;
        }
    }

    /** Dernière synchronisation (date de FETCH_HEAD) en millisecondes ; 0 si inconnue. */
    public long lastSyncMillis() {
        try (org.eclipse.jgit.api.Git git = org.eclipse.jgit.api.Git.open(
                repositoryRoot.toFile())) {
            java.io.File fetchHead = new java.io.File(
                    git.getRepository().getDirectory(), "FETCH_HEAD");
            return fetchHead.isFile() ? fetchHead.lastModified() : 0L;
        } catch (IOException e) {
            return 0L;
        }
    }

    /** Statut du dépôt : fichiers modifiés/ajoutés/supprimés, avec compte de lignes. */
    public List<FileStatus> status() throws IOException {
        List<FileStatus> out = new ArrayList<>();
        try (org.eclipse.jgit.api.Git git = org.eclipse.jgit.api.Git.open(
                repositoryRoot.toFile())) {
            org.eclipse.jgit.api.Status st = git.status().call();
            st.getModified().forEach(p -> out.add(counted(p, ChangeKind.MODIFIED)));
            st.getAdded().forEach(p -> out.add(counted(p, ChangeKind.ADDED)));
            st.getRemoved().forEach(p -> out.add(counted(p, ChangeKind.DELETED)));
            st.getUntracked().forEach(p -> out.add(
                    new FileStatus(p, ChangeKind.UNTRACKED, 0, 0)));
        } catch (org.eclipse.jgit.api.errors.GitAPIException e) {
            throw new IOException(e.getMessage(), e);
        }
        return out;
    }

    private FileStatus counted(String path, ChangeKind kind) {
        int added = 0;
        int removed = 0;
        try (org.eclipse.jgit.api.Git git = org.eclipse.jgit.api.Git.open(
                repositoryRoot.toFile())) {
            if (kind == ChangeKind.ADDED) {
                added = (int) lineCount(path);
            } else {
                List<DiffLine> lines = diff(path);
                for (DiffLine l : lines) {
                    if (l.kind() == LineKind.ADD) {
                        added++;
                    } else if (l.kind() == LineKind.REMOVE) {
                        removed++;
                    }
                }
            }
        } catch (IOException e) {
            // Comptage indisponible : l'état reste juste, les compteurs à zéro.
        }
        return new FileStatus(path, kind, added, removed);
    }

    private long lineCount(String path) throws IOException {
        Path file = repositoryRoot.resolve(path);
        if (!Files.isRegularFile(file)) {
            return 0;
        }
        return Files.readAllLines(file, java.nio.charset.StandardCharsets.UTF_8).size();
    }

    /** Diff d'un fichier : HEAD (via l'index) contre le workdir — lignes colorées. */
    public List<DiffLine> diff(String path) throws IOException {
        List<DiffLine> out = new ArrayList<>();
        try (org.eclipse.jgit.api.Git git = org.eclipse.jgit.api.Git.open(
                repositoryRoot.toFile())) {
            org.eclipse.jgit.lib.Repository repo = git.getRepository();
            org.eclipse.jgit.lib.ObjectId headId = repo.resolve("HEAD^{tree}");
            if (headId == null) {
                return out; // aucun commit : pas de diff de référence.
            }
            // HEAD (index) contre le répertoire de travail : les lignes modifiées
            // depuis le dernier commit (la voie DirectDiffCommand échoue sur les
            // blobs du workdir ; DirCacheIterator↔FileTreeIterator est la voie sûre).
            org.eclipse.jgit.dircache.DirCache cache =
                    org.eclipse.jgit.dircache.DirCache.read(repo);
            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
            org.eclipse.jgit.diff.DiffFormatter fmt =
                    new org.eclipse.jgit.diff.DiffFormatter(buf);
            fmt.setRepository(repo);
            for (org.eclipse.jgit.diff.DiffEntry entry : fmt.scan(
                    new org.eclipse.jgit.dircache.DirCacheIterator(cache),
                    new org.eclipse.jgit.treewalk.FileTreeIterator(repo))) {
                if (!entry.getNewPath().equals(path)
                        && !entry.getOldPath().equals(path)) {
                    continue;
                }
                fmt.format(entry);
                break;
            }
            fmt.close();
            String[] lines = buf.toString(
                    java.nio.charset.StandardCharsets.UTF_8).split("\n");
            for (String line : lines) {
                if (line.startsWith("+") && !line.startsWith("+++")) {
                    out.add(new DiffLine(LineKind.ADD, line.substring(1)));
                } else if (line.startsWith("-") && !line.startsWith("---")) {
                    out.add(new DiffLine(LineKind.REMOVE, line.substring(1)));
                } else if (line.startsWith(" ")) {
                    out.add(new DiffLine(LineKind.CONTEXT, line.substring(1)));
                }
            }
        }
        return out;
    }

    /** Historique : identifiant court, auteur, date relative, message. */
    public List<CommitLog> log(int max) throws IOException {
        List<CommitLog> out = new ArrayList<>();
        try (org.eclipse.jgit.api.Git git = org.eclipse.jgit.api.Git.open(
                repositoryRoot.toFile())) {
            Iterable<org.eclipse.jgit.revwalk.RevCommit> commits =
                    git.log().setMaxCount(max).call();
            for (org.eclipse.jgit.revwalk.RevCommit c : commits) {
                out.add(new CommitLog(c.abbreviate(7).name(),
                        c.getAuthorIdent().getName(),
                        c.getAuthorIdent().getWhenAsInstant(),
                        c.getShortMessage()));
            }
        } catch (org.eclipse.jgit.api.errors.GitAPIException e) {
            throw new IOException(e.getMessage(), e);
        }
        return out;
    }

    /** Commit des chemins donnés (tout le dépôt si la liste est vide). */
    public String commit(List<String> paths, String message) throws IOException {
        try (org.eclipse.jgit.api.Git git = org.eclipse.jgit.api.Git.open(
                repositoryRoot.toFile())) {
            org.eclipse.jgit.api.AddCommand add = git.add();
            if (paths.isEmpty()) {
                add.addFilepattern(".");
            } else {
                for (String p : paths) {
                    add.addFilepattern(p);
                }
            }
            add.call();
            return git.commit().setMessage(message).call().abbreviate(7).name();
        } catch (org.eclipse.jgit.api.errors.GitAPIException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    /** Clonage (popup ancrée de l'accueil ; détection du type par l'app). */
    public static Path clone(String url, Path target) throws IOException {
        return cloneInto(url, target);
    }

    /** Clonage effectif (retourne la racine clonée). */
    public static Path cloneInto(String url, Path target) throws IOException {
        try {
            org.eclipse.jgit.api.Git.cloneRepository().setURI(url)
                    .setDirectory(target.toFile()).setDepth(1).call();
            return target;
        } catch (org.eclipse.jgit.api.errors.GitAPIException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    /** Nature d'un changement (pastille de l'écran git). */
    /** Suivi distant : branche distante, commits en avance et en retard. */
    public record Tracking(String remoteBranch, int ahead, int behind) { }

    public enum ChangeKind { MODIFIED, ADDED, DELETED, UNTRACKED }

    /** Nature d'une ligne de diff (couleur rouge/vert). */
    public enum LineKind { ADD, REMOVE, CONTEXT }

    /** Un fichier changé avec ses compteurs (+n/−m). */
    public record FileStatus(String path, ChangeKind kind, int added, int removed) { }

    /** Une ligne de diff (type + texte). */
    public record DiffLine(LineKind kind, String text) { }

    /** Une entrée d'historique (identifiant court, auteur, instant, message). */
    public record CommitLog(String id, String author,
            java.time.Instant when, String message) { }
}
