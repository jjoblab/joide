package jo.joide.coregit;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.jgit.api.CreateBranchCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.dircache.DirCacheIterator;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.FileTreeIterator;

/**
 * Moteur git robuste du tiroir gauche (Lot 6) : statut **exact par zone**
 * (indexé contre HEAD, non indexé contre le workdir, non suivi, conflits),
 * zone de préparation (stage/unstage), diff **indexé et non indexé**,
 * branches (créer, basculer, supprimer), réseau (fetch/pull/push avec
 * jeton passé par l'app — jamais stocké ici), stash, historique avec
 * parents (graphe), correctifs (revert, cherry-pick, reset), résolution de
 * conflits (nôtre/leur/contenu), .gitignore et init.
 *
 * <p>Pur JVM sur JGit, testé avec des dépôts locaux (un bare sert de
 * distant : fetch/pull/push roulent sans réseau). Chaque appel ouvre le
 * dépôt et le referme — sans état partagé, sûr pour l'UI. Les erreurs
 * remontent en {@link IOException} avec le message du moteur, jamais
 * muettes.</p>
 */
public final class GitOps {

    /** Racine du dépôt de travail. */
    private final Path root;

    private GitOps(Path root) {
        this.root = root;
    }

    /** Ouvre le dépôt du projet (lève si absent ou cassé : honnête). */
    public static GitOps open(Path repositoryRoot) throws IOException {
        GitOps ops = new GitOps(repositoryRoot);
        try (Git git = Git.open(repositoryRoot.toFile())) {
            return ops;
        } catch (IOException e) {
            throw new IOException("Dépôt git introuvable : "
                    + e.getMessage(), e);
        }
    }

    /** Initialise un nouveau dépôt dans le dossier (main par défaut). */
    public static GitOps init(Path directory) throws IOException {
        try (Git git = Git.init().setDirectory(directory.toFile())
                .setInitialBranch("main").call()) {
            return new GitOps(directory);
        } catch (GitAPIException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    // ---- Statut exact par zone ------------------------------------------------

    /** Zone d'un changement : où il vit dans la mécanique index/HEAD/workdir. */
    public enum Area { STAGED, UNSTAGED, UNTRACKED, CONFLICT }

    /** Nature d'un changement. */
    public enum Change { ADDED, MODIFIED, REMOVED, CONFLICT }

    /** Un changement vu par le statut exact. */
    public record ChangeEntry(Area area, Change change, String path) {}

    /**
     * Statut exact : indexé (index contre HEAD), non indexé (workdir contre
     * index), non suivi, conflits — le reflet réel de
     * {@code git status}, pas une approximation fusionnée.
     */
    public List<ChangeEntry> exactStatus() throws IOException {
        List<ChangeEntry> out = new ArrayList<>();
        try (Git git = Git.open(root.toFile())) {
            org.eclipse.jgit.api.Status status = git.status().call();
            for (String path : status.getAdded()) {
                out.add(new ChangeEntry(Area.STAGED, Change.ADDED, path));
            }
            for (String path : status.getChanged()) {
                out.add(new ChangeEntry(Area.STAGED, Change.MODIFIED, path));
            }
            for (String path : status.getRemoved()) {
                out.add(new ChangeEntry(Area.STAGED, Change.REMOVED, path));
            }
            for (String path : status.getModified()) {
                out.add(new ChangeEntry(Area.UNSTAGED, Change.MODIFIED, path));
            }
            for (String path : status.getMissing()) {
                out.add(new ChangeEntry(Area.UNSTAGED, Change.REMOVED, path));
            }
            for (String path : status.getUntracked()) {
                out.add(new ChangeEntry(Area.UNTRACKED, Change.ADDED, path));
            }
            for (String path : status.getConflicting()) {
                out.add(new ChangeEntry(Area.CONFLICT, Change.CONFLICT, path));
            }
        } catch (GitAPIException e) {
            throw new IOException(e.getMessage(), e);
        }
        out.sort(java.util.Comparator.comparing(ChangeEntry::path));
        return out;
    }

    /** Compte d'une zone (pastille d'en-tête de l'UI). */
    public int countOf(Area area) throws IOException {
        int count = 0;
        for (ChangeEntry entry : exactStatus()) {
            if (entry.area() == area) {
                count++;
            }
        }
        return count;
    }

    // ---- Zone de préparation ---------------------------------------------------

    /** Ajoute des chemins à l'index (stage). Vide = tout. */
    public void stage(List<String> paths) throws IOException {
        try (Git git = Git.open(root.toFile())) {
            org.eclipse.jgit.api.AddCommand add = git.add();
            if (paths.isEmpty()) {
                add.addFilepattern(".");
            } else {
                for (String path : paths) {
                    add.addFilepattern(path);
                }
            }
            add.call();
        } catch (GitAPIException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    /**
     * Retire des chemins de l'index (unstage) : {@code git reset HEAD –
     * chemin}, ou retrait direct de l'index avant le premier commit.
     */
    public void unstage(List<String> paths) throws IOException {
        if (paths.isEmpty()) {
            return;
        }
        try (Git git = Git.open(root.toFile())) {
            boolean hasHead = git.getRepository().resolve(Constants.HEAD) != null;
            if (hasHead) {
                // JGit refuse la combinaison chemins + mode : les chemins seuls
                // font le reset d'index (mode MIXED implicite de git reset HEAD).
                org.eclipse.jgit.api.ResetCommand reset = git.reset()
                        .setRef(Constants.HEAD);
                for (String path : paths) {
                    reset.addPath(path);
                }
                reset.call();
            } else {
                org.eclipse.jgit.api.RmCommand rm = git.rm().setCached(true);
                for (String path : paths) {
                    rm.addFilepattern(path);
                }
                rm.call();
            }
        } catch (GitAPIException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    // ---- Diffs indexé et non indexé ---------------------------------------------

    /** Nature d'une ligne de diff. */
    public enum LineKind { ADD, REMOVE, CONTEXT }

    /** Une ligne de diff (type + texte). */
    public record DiffLine(LineKind kind, String text) {}

    /** Le diff d'un fichier entre deux côtés (lignes colorées). */
    public List<DiffLine> diff(String path, boolean staged) throws IOException {
        List<DiffLine> out = new ArrayList<>();
        try (Git git = Git.open(root.toFile())) {
            org.eclipse.jgit.lib.Repository repo = git.getRepository();
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            DiffFormatter formatter = new DiffFormatter(buffer);
            formatter.setRepository(repo);
            List<DiffEntry> entries;
            if (staged) {
                // HEAD contre index : ce que contiendra le prochain commit.
                ObjectId headId = repo.resolve(Constants.HEAD + "^{tree}");
                CanonicalTreeParser headTree = new CanonicalTreeParser();
                if (headId != null) {
                    try (ObjectReader reader = repo.newObjectReader()) {
                        headTree.reset(reader, headId);
                    }
                }
                DirCache cache = DirCache.read(repo);
                entries = formatter.scan(headTree, new DirCacheIterator(cache));
            } else {
                // Index contre workdir : ce qui bouge sous le nez de l'index.
                DirCache cache = DirCache.read(repo);
                entries = formatter.scan(new DirCacheIterator(cache),
                        new FileTreeIterator(repo));
            }
            for (DiffEntry entry : entries) {
                if (!entry.getNewPath().equals(path)
                        && !entry.getOldPath().equals(path)) {
                    continue;
                }
                formatter.format(entry);
                break;
            }
            formatter.close();
            parseLines(buffer.toString(StandardCharsets.UTF_8), out);
        }
        return out;
    }

    /** Le diff complet d'un commit (contre son premier parent). */
    public record FileDiff(String path, List<DiffLine> lines) {}

    public List<FileDiff> commitDiffs(String commitId) throws IOException {
        try (Git git = Git.open(root.toFile())) {
            org.eclipse.jgit.lib.Repository repo = git.getRepository();
            try (RevWalk walk = new RevWalk(repo)) {
                RevCommit commit = walk.parseCommit(
                        repo.resolve(commitId));
                CanonicalTreeParser newTree = new CanonicalTreeParser();
                CanonicalTreeParser oldTree = new CanonicalTreeParser();
                try (ObjectReader reader = repo.newObjectReader()) {
                    newTree.reset(reader, commit.getTree());
                    if (commit.getParentCount() > 0) {
                        oldTree.reset(reader,
                                walk.parseCommit(commit.getParent(0)).getTree());
                    }
                }
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                DiffFormatter formatter = new DiffFormatter(buffer);
                formatter.setRepository(repo);
                List<FileDiff> out = new ArrayList<>();
                for (DiffEntry entry : formatter.scan(oldTree, newTree)) {
                    List<DiffLine> lines = new ArrayList<>();
                    formatter.format(entry);
                    parseLines(buffer.toString(StandardCharsets.UTF_8), lines);
                    buffer.reset();
                    out.add(new FileDiff(entry.getNewPath(), lines));
                }
                formatter.close();
                return out;
            }
        } catch (IOException e) {
            throw e;
        }
    }

    private static void parseLines(String patch, List<DiffLine> out) {
        for (String line : patch.split("\n")) {
            if (line.startsWith("+") && !line.startsWith("+++")) {
                out.add(new DiffLine(LineKind.ADD, line.substring(1)));
            } else if (line.startsWith("-") && !line.startsWith("---")) {
                out.add(new DiffLine(LineKind.REMOVE, line.substring(1)));
            } else if (line.startsWith(" ")) {
                out.add(new DiffLine(LineKind.CONTEXT, line.substring(1)));
            }
        }
    }

    // ---- Zone de commit -----------------------------------------------------------

    /** Committe l'index tel quel (la zone de préparation décide, pas l'API). */
    public String commitStaged(String message) throws IOException {
        try (Git git = Git.open(root.toFile())) {
            RevCommit commit = git.commit().setMessage(message).call();
            return commit.abbreviate(7).name();
        } catch (GitAPIException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    /** Ajoute tout puis committe (voie rapide, écran git historique). */
    public String commitAll(String message) throws IOException {
        stage(List.of());
        return commitStaged(message);
    }

    // ---- Branches ------------------------------------------------------------------

    /** Une branche locale (courante en gras côté UI). */
    public record BranchInfo(String name, boolean current) {}

    /** Branches locales, la courante d'abord. */
    public List<BranchInfo> branches() throws IOException {
        List<BranchInfo> out = new ArrayList<>();
        try (Git git = Git.open(root.toFile())) {
            String current = git.getRepository().getBranch();
            for (org.eclipse.jgit.lib.Ref ref : git.branchList().call()) {
                String name = ref.getName().replace("refs/heads/", "");
                out.add(new BranchInfo(name, name.equals(current)));
            }
        } catch (GitAPIException e) {
            throw new IOException(e.getMessage(), e);
        }
        out.sort(java.util.Comparator
                .comparing(BranchInfo::current)
                .reversed()
                .thenComparing(BranchInfo::name));
        return out;
    }

    /** Crée une branche (sans basculer). */
    public void createBranch(String name) throws IOException {
        try (Git git = Git.open(root.toFile())) {
            git.branchCreate().setName(name).call();
        } catch (GitAPIException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    /** Bascule vers une branche (checkout — les changements suivent s'ils ne conflissent pas). */
    public void switchBranch(String name) throws IOException {
        try (Git git = Git.open(root.toFile())) {
            git.checkout().setName(name).call();
        } catch (GitAPIException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    /** Crée puis bascule vers une branche (le geste « nouvelle branche » des IDE). */
    public void createAndSwitch(String name) throws IOException {
        try (Git git = Git.open(root.toFile())) {
            git.checkout().setCreateBranch(true).setName(name)
                    .setUpstreamMode(CreateBranchCommand.SetupUpstreamMode.TRACK)
                    .call();
        } catch (GitAPIException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    /**
     * Supprime une branche. La courante est toujours refusée ; une branche
     * non fusionnée exige {@code force} — le message dit pourquoi, jamais muet.
     */
    public void deleteBranch(String name, boolean force) throws IOException {
        if (name.equals(currentBranch())) {
            throw new IOException("La branche courante ne se supprime pas : "
                    + name);
        }
        try (Git git = Git.open(root.toFile())) {
            List<String> deleted = git.branchDelete().setBranchNames(name)
                    .setForce(force).call();
            if (deleted.isEmpty()) {
                throw new IOException("Branche non supprimée (" + name
                        + ") : non fusionnée — forcez si assumé");
            }
        } catch (GitAPIException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    /** Nom de la branche courante (HEAD détaché : l'identifiant court). */
    public String currentBranch() throws IOException {
        try (Git git = Git.open(root.toFile())) {
            return git.getRepository().getBranch();
        }
    }

    // ---- Réseau : fetch / pull / push --------------------------------------------

    /** Identifiants distants (jeton gardé par le coffre de l'app, jamais ici). */
    public record Credentials(String username, String token) {

        /** Fournisseur JGit ; sans jeton, authentification anonyme. */
        UsernamePasswordCredentialsProvider provider() {
            return new UsernamePasswordCredentialsProvider(
                    username == null ? "" : username,
                    token == null ? "" : token);
        }
    }

    /** Bilan de synchronisation : messages humains, jamais muets. */
    public record SyncResult(String summary) {}

    /** URL du distant origin (null si aucun distant configuré). */
    public String remoteUrl() throws IOException {
        try (Git git = Git.open(root.toFile())) {
            String url = git.getRepository().getConfig()
                    .getString("remote", "origin", "url");
            return url;
        }
    }

    /** Ajoute le distant origin (préparation, tests locaux et réglages). */
    public void setRemote(String url) throws IOException {
        try (Git git = Git.open(root.toFile())) {
            var config = git.getRepository().getConfig();
            config.setString("remote", "origin", "url", url);
            config.setString("remote", "origin", "fetch",
                    "+refs/heads/*:refs/remotes/origin/*");
            config.save();
        }
    }

    /** fetch : récupère l'état distant (comptes de mises à jour dans le résumé). */
    public SyncResult fetch(Credentials credentials) throws IOException {
        try (Git git = Git.open(root.toFile())) {
            var fetch = git.fetch().setRemote("origin");
            if (credentials != null) {
                fetch.setCredentialsProvider(credentials.provider());
            }
            org.eclipse.jgit.transport.FetchResult result = fetch.call();
            int updated = result.getTrackingRefUpdates().size();
            return new SyncResult(updated == 0
                    ? "À jour du distant" : updated + " référence(s) récupérée(s)");
        } catch (GitAPIException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    /** pull : fetch + merge (ou rebase selon la config du dépôt). */
    public SyncResult pull(Credentials credentials) throws IOException {
        try (Git git = Git.open(root.toFile())) {
            var pull = git.pull();
            if (credentials != null) {
                pull.setCredentialsProvider(credentials.provider());
            }
            org.eclipse.jgit.api.PullResult result = pull.call();
            if (result.getMergeResult() != null
                    && !result.getMergeResult().getMergeStatus().isSuccessful()) {
                throw new IOException("Fusion en conflit : résolvez puis commitez");
            }
            return new SyncResult(result.getFetchResult()
                    .getTrackingRefUpdates().isEmpty()
                    ? "Rien à tirer" : "Fusion terminée");
        } catch (GitAPIException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    /** push : envoie les commits de la branche courante vers son amont. */
    public SyncResult push(Credentials credentials) throws IOException {
        try (Git git = Git.open(root.toFile())) {
            var push = git.push();
            if (credentials != null) {
                push.setCredentialsProvider(credentials.provider());
            }
            StringBuilder summary = new StringBuilder();
            for (org.eclipse.jgit.transport.PushResult result : push.call()) {
                for (org.eclipse.jgit.transport.RemoteRefUpdate update
                        : result.getRemoteUpdates()) {
                    if (summary.length() > 0) {
                        summary.append(" · ");
                    }
                    summary.append(update.getNewObjectId().abbreviate(7).name())
                            .append(" → ").append(update.getRemoteName());
                    if (update.getStatus() != org.eclipse.jgit.transport.RemoteRefUpdate
                            .Status.OK) {
                        summary.append(" (").append(update.getStatus()).append(")");
                    }
                }
            }
            return new SyncResult(summary.length() == 0
                    ? "Rien à pousser" : summary.toString());
        } catch (GitAPIException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    // ---- Stash -----------------------------------------------------------------------

    /** Une entrée de stash (référence, message, instant). */
    public record StashEntry(int index, String message,
            java.time.Instant when) {}

    /** Remise de côté (working dir + index) ; null si rien à remiser. */
    public String stashCreate(String message) throws IOException {
        try (Git git = Git.open(root.toFile())) {
            RevCommit stash = git.stashCreate()
                    .setWorkingDirectoryMessage(message).call();
            return stash == null ? null : stash.abbreviate(7).name();
        } catch (GitAPIException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    /** Liste des stash (stash@{0} en tête). */
    public List<StashEntry> stashList() throws IOException {
        List<StashEntry> out = new ArrayList<>();
        try (Git git = Git.open(root.toFile())) {
            int index = 0;
            for (RevCommit stash : git.stashList().call()) {
                out.add(new StashEntry(index++, stash.getShortMessage(),
                        stash.getAuthorIdent().getWhenAsInstant()));
            }
        } catch (GitAPIException e) {
            throw new IOException(e.getMessage(), e);
        }
        return out;
    }

    /** Applique un stash (le garde par défaut, l'efface si drop). */
    public void stashApply(int index, boolean drop) throws IOException {
        try (Git git = Git.open(root.toFile())) {
            git.stashApply().setStashRef("stash@{" + index + "}").call();
            if (drop) {
                git.stashDrop().setStashRef(index).call();
            }
        } catch (GitAPIException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    /** Efface un stash. */
    public void stashDrop(int index) throws IOException {
        try (Git git = Git.open(root.toFile())) {
            git.stashDrop().setStashRef(index).call();
        } catch (GitAPIException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    // ---- Historique + graphe ------------------------------------------------------------

    /** Un commit avec ses parents (le graphe se dessine côté UI). */
    public record CommitNode(String id, String author, java.time.Instant when,
            String message, List<String> parents) {}

    /** Historique de la branche courante, parents compris, borné. */
    public List<CommitNode> graph(int max) throws IOException {
        List<CommitNode> out = new ArrayList<>();
        try (Git git = Git.open(root.toFile())) {
            for (RevCommit commit : git.log().setMaxCount(max).call()) {
                List<String> parents = new ArrayList<>();
                for (RevCommit parent : commit.getParents()) {
                    parents.add(parent.abbreviate(7).name());
                }
                out.add(new CommitNode(commit.abbreviate(7).name(),
                        commit.getAuthorIdent().getName(),
                        commit.getAuthorIdent().getWhenAsInstant(),
                        commit.getShortMessage(), parents));
            }
        } catch (GitAPIException e) {
            throw new IOException(e.getMessage(), e);
        }
        return out;
    }

    // ---- Correctifs ----------------------------------------------------------------------

    /** Annule un commit par un commit inverse (sûr, partagé). */
    public String revert(String commitId) throws IOException {
        try (Git git = Git.open(root.toFile())) {
            RevCommit reverted = git.revert()
                    .include(git.getRepository().resolve(commitId)).call();
            if (reverted == null) {
                throw new IOException("Revert en conflit : résolvez puis commitez");
            }
            return reverted.abbreviate(7).name();
        } catch (GitAPIException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    /** Rejoue un commit par-dessus la branche courante (cherry-pick). */
    public void cherryPick(String commitId) throws IOException {
        try (Git git = Git.open(root.toFile())) {
            var result = git.cherryPick()
                    .include(git.getRepository().resolve(commitId)).call();
            if (result.getStatus() != org.eclipse.jgit.api.CherryPickResult
                    .CherryPickStatus.OK) {
                throw new IOException("Cherry-pick en conflit ("
                        + result.getStatus() + ")");
            }
        } catch (GitAPIException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    /** Modes de reset (soft garde l'index, mixed le workdir, hard rien). */
    public enum ResetMode { SOFT, MIXED, HARD }

    /**
     * Reset vers une cible (par ex. « HEAD~1 » pour un retour arrière ;
     * confirmation obligatoire côté UI pour HARD).
     */
    public void reset(ResetMode mode, String ref) throws IOException {
        try (Git git = Git.open(root.toFile())) {
            git.reset().setMode(org.eclipse.jgit.api.ResetCommand.ResetType
                            .valueOf(mode.name()))
                    .setRef(ref).call();
        } catch (GitAPIException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    // ---- Conflits --------------------------------------------------------------------------

    /** Chemins en conflit (fusion/rebase en cours). */
    public List<String> conflicts() throws IOException {
        List<String> out = new ArrayList<>();
        for (ChangeEntry entry : exactStatus()) {
            if (entry.area() == Area.CONFLICT) {
                out.add(entry.path());
            }
        }
        return out;
    }

    /** Résolution d'un conflit : garder un côté, ou un contenu fourni. */
    public enum Resolution { OURS, THEIRS, CONTENT }

    /**
     * Résout un conflit : écrit le contenu choisi (nôtre = étape 2, leur =
     * étape 3) dans le fichier de travail puis l'indexe (marque résolu).
     */
    public void resolveConflict(String path, Resolution resolution,
            String content) throws IOException {
        try (Git git = Git.open(root.toFile())) {
            org.eclipse.jgit.lib.Repository repo = git.getRepository();
            DirCache cache = repo.readDirCache();
            String chosen = content;
            if (resolution != Resolution.CONTENT) {
                int stage = resolution == Resolution.OURS ? 2 : 3;
                String found = null;
                for (int i = 0; i < cache.getEntryCount(); i++) {
                    DirCacheEntry entry = cache.getEntry(i);
                    if (entry.getPathString().equals(path)
                            && entry.getStage() == stage) {
                        try (ObjectReader reader = repo.newObjectReader()) {
                            byte[] bytes = reader.open(entry.getObjectId())
                                    .getBytes();
                            found = new String(bytes, StandardCharsets.UTF_8);
                        }
                        break;
                    }
                }
                if (found == null) {
                    throw new IOException("Étape " + stage
                            + " absente pour " + path);
                }
                chosen = found;
            }
            Files.writeString(root.resolve(path), chosen);
            stage(List.of(path));
        }
    }

    // ---- .gitignore et suivi -----------------------------------------------------------------

    /** Ajoute un motif au .gitignore (créé au besoin, jamais de doublon). */
    public void ignore(String pattern) throws IOException {
        Path ignoreFile = root.resolve(".gitignore");
        String current = Files.isRegularFile(ignoreFile)
                ? Files.readString(ignoreFile, StandardCharsets.UTF_8) : "";
        for (String line : current.split("\n")) {
            if (line.trim().equals(pattern)) {
                return; // déjà ignoré : rien à écrire.
            }
        }
        String prefix = current.isEmpty() || current.endsWith("\n") ? "" : "\n";
        Files.writeString(ignoreFile, current + prefix + pattern + "\n",
                StandardCharsets.UTF_8);
        stage(List.of(".gitignore"));
    }

    /** Le motif est-il déjà ignoré (l'UI ne propose pas deux fois) ? */
    public boolean isIgnored(String pattern) throws IOException {
        Path ignoreFile = root.resolve(".gitignore");
        if (!Files.isRegularFile(ignoreFile)) {
            return false;
        }
        for (String line : Files.readAllLines(ignoreFile, StandardCharsets.UTF_8)) {
            if (line.trim().equals(pattern)) {
                return true;
            }
        }
        return false;
    }

    /** Suivi distant de la branche courante (null sans amont). */
    public record Tracking(String remoteBranch, int ahead, int behind) {}

    public Tracking tracking() throws IOException {
        try (Git git = Git.open(root.toFile())) {
            org.eclipse.jgit.lib.Repository repo = git.getRepository();
            org.eclipse.jgit.lib.BranchTrackingStatus status =
                    org.eclipse.jgit.lib.BranchTrackingStatus.of(repo,
                            repo.getBranch());
            if (status == null) {
                return null;
            }
            return new Tracking(
                    org.eclipse.jgit.lib.Repository.shortenRefName(
                            status.getRemoteTrackingBranch()),
                    status.getAheadCount(), status.getBehindCount());
        }
    }
}
