package jo.joide.app.drawer;

import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import com.google.android.material.snackbar.Snackbar;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import jo.joide.app.R;
import jo.joide.app.databinding.GitTabFragmentBinding;
import jo.joide.coregit.GitOps;
import jo.joide.workbench.WorkspaceEvents;

/**
 * Onglet Git du tiroir gauche (Lot 7 de la refonte) : le client git **dans
 * le tiroir**, sur le moteur robuste {@link GitOps} — en-tête (branche,
 * suivi distant ↓↑, tirer/pousser avec jeton au coffre par hôte),
 * Changements (zones exactes : conflits, indexé, non indexé, non suivi ;
 * indexer/désindexer par rangée, zone de commit collée en bas, visionneuse
 * de diff), Branches (basculer, créer, supprimer), Historique (parents =
 * fusions, diff par commit), Plus (stash, .gitignore, revert, reset soft,
 * écran complet).
 *
 * <p>Toutes les opérations roulent hors fil UI et publient sur le bus
 * {@link WorkspaceEvents} (un seul chemin de mise à jour : l'arbre
 * rafraîchit ses décorations et ses listings) ; les erreurs remontent le
 * message du moteur — jamais muettes. Les jetons vivent dans le coffre
 * chiffré ({@link GitCredentialsStore}), par hôte, jamais en clair.</p>
 */
public final class GitTabFragment extends Fragment {

    private GitTabFragmentBinding binding;
    private WorkspaceViewModel viewModel;
    private Path root;
    private GitCredentialsStore credentialsStore;

    private final Handler main = new Handler(Looper.getMainLooper());

    /** Sections repliables. */
    private final Set<String> collapsedSections = new HashSet<>();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        binding = GitTabFragmentBinding.inflate(inflater, container, false);
        viewModel = new ViewModelProvider(requireParentFragment())
                .get(WorkspaceViewModel.class);
        String path = viewModel.projectPath();
        root = path != null ? Paths.get(path) : null;
        credentialsStore = new GitCredentialsStore(requireContext());

        binding.gitPull.setOnClickListener(v -> sync(false));
        binding.gitPush.setOnClickListener(v -> sync(true));
        render();
        return binding.getRoot();
    }

    // ---- Une opération git hors fil UI, erreurs honnêtes ---------------------

    private interface IoCall<T> {
        T run() throws IOException;
    }

    private <T> void opAsync(IoCall<T> operation, java.util.function.Consumer<T> onSuccess) {
        if (root == null) {
            return;
        }
        Thread worker = new Thread(() -> {
            T value = null;
            IOException failure = null;
            try {
                value = operation.run();
            } catch (IOException e) {
                failure = e;
            }
            IOException error = failure;
            T result = value;
            main.post(() -> {
                if (binding == null) {
                    return;
                }
                if (error != null) {
                    toast(getString(R.string.tiroir_erreur_op,
                            error.getMessage() != null ? error.getMessage() : ""));
                    render();
                    return;
                }
                onSuccess.accept(result);
            });
        }, "tiroir-git-op");
        worker.setDaemon(true);
        worker.start();
    }

    /** Après une opération qui touche des fichiers : l'arbre se rafraîchit. */
    private void publishTreeAndGit() {
        viewModel.events().publish(new WorkspaceEvents.GitStateChanged());
    }

    // ---- Rendu ------------------------------------------------------------------

    /** Recharge tout l'état hors fil UI puis redessine les sections. */
    private void render() {
        if (root == null || binding == null) {
            return;
        }
        opAsync(() -> {
            if (!java.nio.file.Files.isDirectory(root.resolve(".git"))) {
                return null; // pas un dépôt : état dédié.
            }
            GitOps ops = GitOps.open(root);
            List<GitOps.ChangeEntry> status = ops.exactStatus();
            List<GitOps.BranchInfo> branches = ops.branches();
            List<GitOps.CommitNode> history = ops.graph(30);
            List<GitOps.StashEntry> stash = ops.stashList();
            GitOps.Tracking tracking;
            String remoteUrl;
            try {
                tracking = ops.tracking();
                remoteUrl = ops.remoteUrl();
            } catch (IOException e) {
                tracking = null;
                remoteUrl = null;
            }
            return new Snapshot(status, branches, history, stash, tracking,
                    remoteUrl, ops.currentBranch());
        }, this::renderSnapshot);
    }

    /** État complet du dépôt, lu en une fois hors fil UI. */
    private record Snapshot(List<GitOps.ChangeEntry> status,
            List<GitOps.BranchInfo> branches, List<GitOps.CommitNode> history,
            List<GitOps.StashEntry> stash, GitOps.Tracking tracking,
            String remoteUrl, String branch) {}

    private void renderSnapshot(Snapshot snapshot) {
        if (binding == null) {
            return;
        }
        LinearLayout sections = binding.gitSections;
        sections.removeAllViews();
        if (snapshot == null) {
            renderNotARepository(sections);
            return;
        }
        renderHeader(snapshot);
        renderChanges(sections, snapshot);
        renderCommitBox(sections, snapshot);
        renderBranches(sections, snapshot);
        renderHistory(sections, snapshot);
        renderMore(sections, snapshot);
    }

    private void renderHeader(Snapshot snapshot) {
        binding.gitBranch.setText(snapshot.branch());
        GitOps.Tracking tracking = snapshot.tracking();
        binding.gitTracking.setText(tracking == null
                ? getString(R.string.git_tiroir_sans_amont)
                : tracking.remoteBranch() + " · ↓" + tracking.behind()
                        + " ↑" + tracking.ahead());
    }

    private void renderNotARepository(LinearLayout sections) {
        binding.gitBranch.setText("");
        binding.gitTracking.setText("");
        TextView title = new TextView(requireContext());
        title.setText(R.string.git_tiroir_pas_depot);
        title.setTextSize(13f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(getResources().getColor(R.color.sur_var));
        sections.addView(title);
        TextView detail = new TextView(requireContext());
        detail.setText(R.string.git_tiroir_pas_depot_detail);
        detail.setTextSize(12f);
        detail.setTextColor(getResources().getColor(R.color.contour));
        sections.addView(detail);
        TextView init = chip(getString(R.string.git_tiroir_init));
        init.setOnClickListener(v -> opAsync(() -> {
            GitOps.init(root);
            return null;
        }, ignored -> {
            publishTreeAndGit();
            snackbar(R.string.git_tiroir_init);
        }));
        sections.addView(init);
    }

    // ---- Changements ------------------------------------------------------------

    private void renderChanges(LinearLayout sections, Snapshot snapshot) {
        List<GitOps.ChangeEntry> conflicts = new ArrayList<>();
        List<GitOps.ChangeEntry> staged = new ArrayList<>();
        List<GitOps.ChangeEntry> unstaged = new ArrayList<>();
        List<GitOps.ChangeEntry> untracked = new ArrayList<>();
        for (GitOps.ChangeEntry entry : snapshot.status()) {
            switch (entry.area()) {
                case CONFLICT -> conflicts.add(entry);
                case STAGED -> staged.add(entry);
                case UNSTAGED -> unstaged.add(entry);
                default -> untracked.add(entry);
            }
        }
        if (!conflicts.isEmpty()) {
            section(sections, "conflits",
                    getString(R.string.git_tiroir_conflits, conflicts.size()),
                    true, box -> {
                        for (GitOps.ChangeEntry entry : conflicts) {
                            box.addView(conflictRow(entry));
                        }
                    });
        }
        section(sections, "indexe",
                getString(R.string.git_tiroir_indexe, staged.size()),
                false, box -> {
                    for (GitOps.ChangeEntry entry : staged) {
                        box.addView(changeRow(entry, true));
                    }
                });
        section(sections, "nonindexe",
                getString(R.string.git_tiroir_non_indexe, unstaged.size()),
                false, box -> {
                    for (GitOps.ChangeEntry entry : unstaged) {
                        box.addView(changeRow(entry, false));
                    }
                });
        section(sections, "nonsuivi",
                getString(R.string.git_tiroir_non_suivi, untracked.size()),
                false, box -> {
                    for (GitOps.ChangeEntry entry : untracked) {
                        box.addView(changeRow(entry, false));
                    }
                });
        if (snapshot.status().isEmpty()) {
            TextView clean = new TextView(requireContext());
            clean.setText(R.string.git_tiroir_vide);
            clean.setTextSize(12f);
            clean.setTextColor(getResources().getColor(R.color.ok));
            clean.setPadding(0, dip(8), 0, dip(8));
            sections.addView(clean);
        }
    }

    /** Une rangée de changement : lettre d'état, chemin, action, diff au clic. */
    private View changeRow(GitOps.ChangeEntry entry, boolean isStaged) {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(0, dip(6), 0, dip(6));
        TextView letter = new TextView(requireContext());
        letter.setText(changeLetter(entry.change()));
        letter.setTextSize(10.5f);
        letter.setTypeface(Typeface.DEFAULT_BOLD);
        letter.setGravity(android.view.Gravity.CENTER);
        letter.setTextColor(getResources().getColor(
                entry.change() == GitOps.Change.REMOVED ? R.color.erreur
                        : entry.area() == GitOps.Area.STAGED ? R.color.ok
                        : R.color.alerte));
        letter.setBackground(getResources().getDrawable(R.drawable.fond_badge));
        letter.setMinWidth(dip(17));
        letter.setMinHeight(dip(17));
        row.addView(letter);
        TextView path = new TextView(requireContext());
        path.setText(entry.path());
        path.setTextSize(12f);
        path.setTextColor(getResources().getColor(R.color.sur));
        LinearLayout.LayoutParams pathParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        pathParams.leftMargin = dip(8);
        row.addView(path, pathParams);
        TextView action = chip(entry.area() == GitOps.Area.STAGED
                ? "−" : "+");
        action.setOnClickListener(v -> {
            if (entry.area() == GitOps.Area.STAGED) {
                opAsync(() -> {
                    GitOps.open(root).unstage(List.of(entry.path()));
                    return null;
                }, ignored -> publishTreeAndGit());
            } else {
                opAsync(() -> {
                    GitOps.open(root).stage(List.of(entry.path()));
                    return null;
                }, ignored -> publishTreeAndGit());
            }
        });
        row.addView(action);
        row.setOnClickListener(v -> {
            if (entry.area() == GitOps.Area.UNTRACKED
                    || entry.area() == GitOps.Area.CONFLICT) {
                return; // pas de base de diff : l'action reste le bouton.
            }
            showDiff(entry.path(), entry.area() == GitOps.Area.STAGED);
        });
        return row;
    }

    /** Rangée de conflit : nôtre / leur / contenu, résolution immédiate. */
    private View conflictRow(GitOps.ChangeEntry entry) {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dip(8), 0, dip(8));
        TextView path = new TextView(requireContext());
        path.setText(entry.path());
        path.setTextSize(12f);
        path.setTypeface(Typeface.DEFAULT_BOLD);
        path.setTextColor(getResources().getColor(R.color.erreur));
        row.addView(path);
        LinearLayout buttons = new LinearLayout(requireContext());
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        TextView ours = chip(getString(R.string.git_tiroir_notre));
        ours.setOnClickListener(v -> resolveConflict(entry.path(),
                GitOps.Resolution.OURS, null));
        buttons.addView(ours);
        TextView theirs = chip(getString(R.string.git_tiroir_leur));
        theirs.setOnClickListener(v -> resolveConflict(entry.path(),
                GitOps.Resolution.THEIRS, null));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.leftMargin = dip(6);
        buttons.addView(theirs, params);
        TextView content = chip(getString(R.string.git_tiroir_contenu));
        content.setOnClickListener(v -> {
            EditText input = new EditText(requireContext());
            input.setTextSize(13f);
            int padding = dip(20);
            input.setPadding(padding, dip(8), padding, 0);
            new AlertDialog.Builder(requireContext())
                    .setTitle(R.string.git_tiroir_contenu_titre)
                    .setView(input)
                    .setPositiveButton(R.string.git_tiroir_notre,
                            (dialog, which) -> resolveConflict(entry.path(),
                                    GitOps.Resolution.CONTENT,
                                    input.getText().toString()))
                    .setNegativeButton(R.string.tiroir_action_annuler, null)
                    .show();
        });
        LinearLayout.LayoutParams contentParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        contentParams.leftMargin = dip(6);
        buttons.addView(content, contentParams);
        row.addView(buttons);
        return row;
    }

    private void resolveConflict(String path, GitOps.Resolution resolution,
            String content) {
        opAsync(() -> {
            GitOps.open(root).resolveConflict(path, resolution, content);
            return null;
        }, ignored -> {
            viewModel.events().publish(
                    new WorkspaceEvents.FileChangedOnDisk(root.resolve(path)));
            publishTreeAndGit();
        });
    }

    /** Zone de commit : message + committer (l'index décide). */
    private void renderCommitBox(LinearLayout sections, Snapshot snapshot) {
        boolean hasStaged = snapshot.status().stream()
                .anyMatch(entry -> entry.area() == GitOps.Area.STAGED);
        LinearLayout box = new LinearLayout(requireContext());
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dip(10), dip(10), dip(10), dip(10));
        box.setBackground(getResources().getDrawable(R.drawable.fond_field));
        EditText message = new EditText(requireContext());
        message.setHint(R.string.git_tiroir_message);
        message.setTextSize(12.5f);
        message.setTextColor(getResources().getColor(R.color.sur));
        box.addView(message);
        TextView commit = chip(getString(R.string.git_tiroir_committer));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dip(30));
        params.topMargin = dip(8);
        commit.setLayoutParams(params);
        commit.setTextColor(getResources().getColor(R.color.sur_primaire));
        commit.setBackground(getResources().getDrawable(R.drawable.fond_cta));
        if (hasStaged) {
            commit.setOnClickListener(v -> {
                String text = message.getText().toString().trim();
                if (text.isEmpty()) {
                    toast(R.string.git_tiroir_message_vide);
                    return;
                }
                opAsync(() -> GitOps.open(root).commitStaged(text), id -> {
                    publishTreeAndGit();
                    snackbar(getString(R.string.git_tiroir_commit_ok, id));
                });
            });
        } else {
            commit.setAlpha(0.5f);
        }
        box.addView(commit);
        sections.addView(box);
    }

    // ---- Branches -----------------------------------------------------------------

    private void renderBranches(LinearLayout sections, Snapshot snapshot) {
        section(sections, "branches",
                getString(R.string.git_tiroir_branches), false, box -> {
            for (GitOps.BranchInfo branch : snapshot.branches()) {
                LinearLayout row = new LinearLayout(requireContext());
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(android.view.Gravity.CENTER_VERTICAL);
                row.setPadding(0, dip(6), 0, dip(6));
                TextView name = new TextView(requireContext());
                name.setText(branch.name());
                name.setTextSize(12.5f);
                name.setTypeface(branch.current()
                        ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
                name.setTextColor(getResources().getColor(
                        branch.current() ? R.color.sur : R.color.sur_var));
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                row.addView(name, params);
                if (branch.current()) {
                    TextView current = chip(getString(R.string.git_tiroir_courante));
                    current.setBackground(getResources().getDrawable(
                            R.drawable.fond_chip_on));
                    row.addView(current);
                }
                row.setOnClickListener(v -> {
                    if (!branch.current()) {
                        opAsync(() -> {
                            GitOps.open(root).switchBranch(branch.name());
                            return null;
                        }, ignored -> {
                            publishTreeAndGit();
                            viewModel.events().publish(
                                    new WorkspaceEvents.FileChangedOnDisk(root));
                            snackbar(getString(R.string.git_tiroir_bascule_ok,
                                    branch.name()));
                        });
                    }
                });
                row.setOnLongClickListener(v -> {
                    if (!branch.current()) {
                        confirmDeleteBranch(branch.name());
                    }
                    return true;
                });
                box.addView(row);
            }
            TextView create = chip(getString(R.string.git_tiroir_nouvelle_branche));
            create.setOnClickListener(v -> {
                EditText input = new EditText(requireContext());
                input.setHint(R.string.git_tiroir_branche_nom);
                input.setTextSize(13f);
                int padding = dip(20);
                input.setPadding(padding, dip(8), padding, 0);
                new AlertDialog.Builder(requireContext())
                        .setTitle(R.string.git_tiroir_nouvelle_branche)
                        .setView(input)
                        .setPositiveButton(R.string.git_tiroir_creer_basculer,
                                (dialog, which) -> {
                                    String name = input.getText().toString().trim();
                                    if (name.isEmpty() || name.contains(" ")) {
                                        return;
                                    }
                                    opAsync(() -> {
                                        GitOps.open(root).createAndSwitch(name);
                                        return null;
                                    }, ignored -> {
                                        publishTreeAndGit();
                                        viewModel.events().publish(
                                                new WorkspaceEvents.FileChangedOnDisk(root));
                                    });
                                })
                        .setNegativeButton(R.string.tiroir_action_annuler, null)
                        .show();
            });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.topMargin = dip(6);
            create.setLayoutParams(params);
            box.addView(create);
        });
    }

    private void confirmDeleteBranch(String name) {
        new AlertDialog.Builder(requireContext())
                .setMessage(getString(R.string.git_tiroir_supprimer_branche, name))
                .setPositiveButton(R.string.git_tiroir_supprimer_branche,
                        (dialog, which) -> opAsync(() -> {
                            GitOps.open(root).deleteBranch(name, false);
                            return null;
                        }, ignored -> publishTreeAndGit()))
                .setNegativeButton(R.string.tiroir_action_annuler, null)
                .show();
    }

    // ---- Historique -----------------------------------------------------------------

    private void renderHistory(LinearLayout sections, Snapshot snapshot) {
        section(sections, "historique",
                getString(R.string.git_tiroir_historique), false, box -> {
            for (GitOps.CommitNode commit : snapshot.history()) {
                LinearLayout row = new LinearLayout(requireContext());
                row.setOrientation(LinearLayout.VERTICAL);
                row.setPadding(0, dip(6), 0, dip(6));
                LinearLayout line = new LinearLayout(requireContext());
                line.setOrientation(LinearLayout.HORIZONTAL);
                line.setGravity(android.view.Gravity.CENTER_VERTICAL);
                TextView id = new TextView(requireContext());
                id.setText(commit.id());
                id.setTypeface(Typeface.MONOSPACE);
                id.setTextSize(11f);
                id.setTextColor(getResources().getColor(R.color.primaire));
                line.addView(id);
                if (commit.parents().size() > 1) {
                    TextView merge = chip(getString(R.string.git_tiroir_fusion));
                    merge.setTextColor(getResources().getColor(R.color.tertiaire));
                    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT);
                    params.leftMargin = dip(6);
                    line.addView(merge, params);
                }
                TextView when = new TextView(requireContext());
                when.setText(relativeWhen(commit.when()));
                when.setTextSize(10.5f);
                when.setTextColor(getResources().getColor(R.color.contour));
                LinearLayout.LayoutParams whenParams = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                when.setGravity(android.view.Gravity.END);
                line.addView(when, whenParams);
                row.addView(line);
                TextView message = new TextView(requireContext());
                message.setText(commit.message());
                message.setTextSize(12f);
                message.setTextColor(getResources().getColor(R.color.sur));
                row.addView(message);
                TextView author = new TextView(requireContext());
                author.setText(commit.author());
                author.setTextSize(10.5f);
                author.setTextColor(getResources().getColor(R.color.sur_var));
                row.addView(author);
                row.setOnClickListener(v -> showCommitDiff(commit.id()));
                box.addView(row);
            }
        });
    }

    // ---- Plus ------------------------------------------------------------------------

    private void renderMore(LinearLayout sections, Snapshot snapshot) {
        section(sections, "plus", getString(R.string.git_tiroir_plus), true, box -> {
            if (!snapshot.stash().isEmpty()) {
                TextView stashTitle = new TextView(requireContext());
                stashTitle.setText(getString(R.string.git_tiroir_stash,
                        snapshot.stash().size()));
                stashTitle.setTextSize(12f);
                stashTitle.setTypeface(Typeface.DEFAULT_BOLD);
                stashTitle.setTextColor(getResources().getColor(R.color.sur_var));
                box.addView(stashTitle);
                for (GitOps.StashEntry stash : snapshot.stash()) {
                    LinearLayout row = new LinearLayout(requireContext());
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    row.setGravity(android.view.Gravity.CENTER_VERTICAL);
                    row.setPadding(0, dip(6), 0, dip(6));
                    TextView label = new TextView(requireContext());
                    label.setText(stash.message() + " · "
                            + relativeWhen(stash.when()));
                    label.setTextSize(12f);
                    label.setTextColor(getResources().getColor(R.color.sur));
                    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                    row.addView(label, params);
                    TextView apply = chip(getString(R.string.git_tiroir_appliquer));
                    apply.setOnClickListener(v -> opAsync(() -> {
                        GitOps.open(root).stashApply(stash.index(), false);
                        return null;
                    }, ignored -> {
                        viewModel.events().publish(
                                new WorkspaceEvents.FileChangedOnDisk(root));
                        publishTreeAndGit();
                    }));
                    row.addView(apply);
                    TextView drop = chip("−");
                    drop.setOnClickListener(v -> opAsync(() -> {
                        GitOps.open(root).stashDrop(stash.index());
                        return null;
                    }, ignored -> publishTreeAndGit()));
                    LinearLayout.LayoutParams dropParams = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT);
                    dropParams.leftMargin = dip(6);
                    row.addView(drop, dropParams);
                    box.addView(row);
                }
            }
            box.addView(action(getString(R.string.git_tiroir_remiser), v -> {
                EditText input = new EditText(requireContext());
                input.setHint(R.string.git_tiroir_stash_message);
                input.setTextSize(13f);
                int padding = dip(20);
                input.setPadding(padding, dip(8), padding, 0);
                new AlertDialog.Builder(requireContext())
                        .setTitle(R.string.git_tiroir_remiser)
                        .setView(input)
                        .setPositiveButton(R.string.git_tiroir_remiser,
                                (dialog, which) -> opAsync(() -> {
                                    GitOps.open(root).stashCreate(
                                            input.getText().toString().trim());
                                    return null;
                                }, ignored -> {
                                    viewModel.events().publish(
                                            new WorkspaceEvents.FileChangedOnDisk(root));
                                    publishTreeAndGit();
                                    snackbar(R.string.git_tiroir_stasher_ok);
                                }))
                        .setNegativeButton(R.string.tiroir_action_annuler, null)
                        .show();
            }));
            box.addView(action(getString(R.string.git_tiroir_gitignore), v -> {
                EditText input = new EditText(requireContext());
                input.setHint(R.string.git_tiroir_gitignore_indice);
                input.setTextSize(13f);
                int padding = dip(20);
                input.setPadding(padding, dip(8), padding, 0);
                new AlertDialog.Builder(requireContext())
                        .setTitle(R.string.git_tiroir_gitignore)
                        .setView(input)
                        .setPositiveButton(R.string.git_tiroir_gitignore,
                                (dialog, which) -> {
                                    String pattern = input.getText().toString().trim();
                                    if (pattern.isEmpty()) {
                                        return;
                                    }
                                    opAsync(() -> {
                                        GitOps.open(root).ignore(pattern);
                                        return null;
                                    }, ignored -> publishTreeAndGit());
                                })
                        .setNegativeButton(R.string.tiroir_action_annuler, null)
                        .show();
            }));
            box.addView(action(getString(R.string.git_tiroir_revert), v ->
                    opAsync(() -> GitOps.open(root).graph(1).get(0).message(),
                        lastMessage -> new AlertDialog.Builder(requireContext())
                                .setMessage(getString(
                                        R.string.git_tiroir_revert_confirm, lastMessage))
                                .setPositiveButton(R.string.git_tiroir_revert,
                                        (dialog, which) -> opAsync(() ->
                                                GitOps.open(root).revert("HEAD"),
                                                id -> {
                                                    viewModel.events().publish(
                                                            new WorkspaceEvents.FileChangedOnDisk(root));
                                                    publishTreeAndGit();
                                                }))
                                .setNegativeButton(R.string.tiroir_action_annuler, null)
                                .show())));
            box.addView(action(getString(R.string.git_tiroir_reset), v ->
                    opAsync(() -> {
                        GitOps.open(root).reset(GitOps.ResetMode.SOFT, "HEAD~1");
                        return null;
                    }, ignored -> {
                        viewModel.events().publish(
                                new WorkspaceEvents.FileChangedOnDisk(root));
                        publishTreeAndGit();
                    })));
            box.addView(action(getString(R.string.git_tiroir_ecran_complet), v -> {
                WorkbenchHost host = host();
                if (host != null) {
                    host.openGitScreen();
                }
            }));
        });
    }

    // ---- Visionneuse de diff -----------------------------------------------------------

    /** Diff d'un fichier (indexé ou non) en dialogue défilant coloré. */
    private void showDiff(String path, boolean staged) {
        opAsync(() -> GitOps.open(root).diff(path, staged), lines ->
                showDiffDialog(getString(R.string.git_tiroir_diff, path), lines));
    }

    /** Diff complet d'un commit (fichiers en titres). */
    private void showCommitDiff(String commitId) {
        opAsync(() -> GitOps.open(root).commitDiffs(commitId), files -> {
            List<GitOps.DiffLine> lines = new ArrayList<>();
            for (GitOps.FileDiff file : files) {
                if (!lines.isEmpty()) {
                    lines.add(new GitOps.DiffLine(GitOps.LineKind.CONTEXT, ""));
                }
                lines.add(new GitOps.DiffLine(GitOps.LineKind.CONTEXT,
                        "—— " + file.path() + " ——"));
                lines.addAll(file.lines());
            }
            showDiffDialog(commitId, lines);
        });
    }

    private void showDiffDialog(String title, List<GitOps.DiffLine> lines) {
        LinearLayout list = new LinearLayout(requireContext());
        list.setOrientation(LinearLayout.VERTICAL);
        int padding = dip(14);
        list.setPadding(padding, dip(8), padding, dip(8));
        for (GitOps.DiffLine line : lines) {
            TextView row = new TextView(requireContext());
            row.setText(line.text().isEmpty() ? " " : line.text());
            row.setTypeface(Typeface.MONOSPACE);
            row.setTextSize(10.5f);
            switch (line.kind()) {
                case ADD -> {
                    row.setBackground(getResources().getDrawable(
                            R.drawable.fond_diff_plus));
                    row.setTextColor(getResources().getColor(R.color.ok));
                }
                case REMOVE -> {
                    row.setBackground(getResources().getDrawable(
                            R.drawable.fond_diff_moins));
                    row.setTextColor(getResources().getColor(R.color.erreur));
                }
                default -> row.setTextColor(getResources().getColor(R.color.sur_var));
            }
            list.addView(row);
        }
        android.widget.ScrollView scroll = new android.widget.ScrollView(requireContext());
        scroll.addView(list);
        new AlertDialog.Builder(requireContext())
                .setTitle(title)
                .setView(scroll)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    // ---- Réseau : tirer / pousser (jeton au coffre, dialogue à la demande) --------------

    /** pull ou push : jeton du coffre par hôte, dialogue si absent. */
    private void sync(boolean push) {
        opAsync(() -> {
            GitOps ops = GitOps.open(root);
            String remote = ops.remoteUrl();
            if (remote == null) {
                throw new IOException("Aucun distant « origin » configuré");
            }
            return remote;
        }, remote -> {
            GitOps.Credentials credentials = credentialsStore.of(remote);
            if (credentials == null && remote.startsWith("http")) {
                askCredentials(remote, push);
                return;
            }
            runSync(push, credentials);
        });
    }

    /** Dialogue d'identifiants (stockés chiffrés à la confirmation). */
    private void askCredentials(String remote, boolean push) {
        LinearLayout box = new LinearLayout(requireContext());
        box.setOrientation(LinearLayout.VERTICAL);
        int padding = dip(20);
        box.setPadding(padding, dip(8), padding, 0);
        TextView detail = new TextView(requireContext());
        detail.setText(getString(R.string.git_tiroir_identifiants_detail, remote));
        detail.setTextSize(11.5f);
        detail.setTextColor(getResources().getColor(R.color.sur_var));
        box.addView(detail);
        EditText user = new EditText(requireContext());
        user.setHint(R.string.git_tiroir_utilisateur);
        user.setTextSize(13f);
        box.addView(user);
        EditText token = new EditText(requireContext());
        token.setHint(R.string.git_tiroir_jeton);
        token.setTextSize(13f);
        box.addView(token);
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.git_tiroir_identifiants)
                .setView(box)
                .setPositiveButton(R.string.git_tiroir_pousser,
                        (dialog, which) -> {
                            credentialsStore.put(remote,
                                    user.getText().toString().trim(),
                                    token.getText().toString().trim());
                            runSync(push, credentialsStore.of(remote));
                        })
                .setNegativeButton(R.string.tiroir_action_annuler, null)
                .show();
    }

    private void runSync(boolean push, GitOps.Credentials credentials) {
        opAsync(() -> {
            GitOps ops = GitOps.open(root);
            return push ? ops.push(credentials) : ops.pull(credentials);
        }, result -> {
            publishTreeAndGit();
            snackbar(result.summary());
        });
    }

    // ---- Petites briques d'UI --------------------------------------------------------

    /** Une section repliable avec titre et contenu construit en code. */
    private void section(LinearLayout parent, String id, String title,
            boolean danger, java.util.function.Consumer<LinearLayout> fill) {
        LinearLayout box = new LinearLayout(requireContext());
        box.setOrientation(LinearLayout.VERTICAL);
        TextView header = new TextView(requireContext());
        header.setText((collapsedSections.contains(id) ? "▸ " : "▾ ") + title);
        header.setTextSize(12.5f);
        header.setTypeface(Typeface.DEFAULT_BOLD);
        header.setTextColor(getResources().getColor(
                danger ? R.color.erreur : R.color.sur_var));
        header.setPadding(0, dip(10), 0, dip(4));
        header.setOnClickListener(v -> {
            if (collapsedSections.contains(id)) {
                collapsedSections.remove(id);
            } else {
                collapsedSections.add(id);
            }
            render();
        });
        parent.addView(header);
        if (!collapsedSections.contains(id)) {
            fill.accept(box);
            parent.addView(box);
        }
    }

    /** Bouton d'action de la section Plus. */
    private TextView action(String label, View.OnClickListener listener) {
        TextView button = new TextView(requireContext());
        button.setText(label);
        button.setTextSize(12f);
        button.setTextColor(getResources().getColor(R.color.sur));
        button.setPadding(0, dip(8), 0, dip(8));
        button.setOnClickListener(listener);
        return button;
    }

    /** Chip 32dp du studio. */
    private TextView chip(String label) {
        TextView button = new TextView(requireContext());
        button.setText(label);
        button.setTextSize(11.5f);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setTextColor(getResources().getColor(R.color.sur));
        button.setBackground(getResources().getDrawable(R.drawable.fond_chip));
        button.setPadding(dip(12), 0, dip(12), 0);
        button.setMinHeight(dip(30));
        button.setGravity(android.view.Gravity.CENTER);
        return button;
    }

    private String changeLetter(GitOps.Change change) {
        return switch (change) {
            case ADDED -> getString(R.string.git_tiroir_ajoute);
            case MODIFIED -> getString(R.string.git_tiroir_modifie);
            case REMOVED -> getString(R.string.git_tiroir_supprime);
            case CONFLICT -> "!";
        };
    }

    /** « il y a 12 min / 3 h / 2 j » — borné au jour. */
    private String relativeWhen(java.time.Instant when) {
        long minutes = Math.max(0,
                (System.currentTimeMillis() - when.toEpochMilli()) / 60000L);
        if (minutes < 60) {
            return getString(R.string.tiroir_quand_minutes, minutes);
        }
        if (minutes < 60 * 24) {
            return getString(R.string.tiroir_quand_heures, minutes / 60);
        }
        return getString(R.string.tiroir_quand_jours, minutes / (60 * 24));
    }

    private WorkbenchHost host() {
        Fragment parent = requireParentFragment();
        Fragment grandParent = parent != null ? parent.getParentFragment() : null;
        return grandParent instanceof WorkbenchHost host ? host : null;
    }

    private void snackbar(String text) {
        if (binding != null) {
            Snackbar.make(binding.getRoot(), text, Snackbar.LENGTH_SHORT).show();
        }
    }

    private void snackbar(int resId) {
        if (binding != null) {
            Snackbar.make(binding.getRoot(), resId, Snackbar.LENGTH_SHORT).show();
        }
    }

    private void toast(int resId) {
        Toast.makeText(requireContext(), resId, Toast.LENGTH_SHORT).show();
    }

    private void toast(String text) {
        Toast.makeText(requireContext(), text, Toast.LENGTH_SHORT).show();
    }

    private int dip(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
