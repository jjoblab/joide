package jo.joide.app.drawer;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
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
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.snackbar.Snackbar;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import jo.joide.app.R;
import jo.joide.app.databinding.GitTabFragmentBinding;
import jo.joide.coregit.GitOps;
import jo.joide.workbench.WorkspaceEvents;

/**
 * Onglet Git du tiroir gauche (Lot 7, fidélité preview tiroir-gauche.html) :
 * le client git **dans le tiroir**, sur le moteur robuste {@link GitOps}.
 *
 * <p>En-tête : chip branche (⎇, appui = sélecteur réel), suivi distant ↓↑ et
 * « synchronisé il y a… », Fetch · Pull · Push (jeton au coffre chiffré par
 * hôte, dialogue à la demande). Sous-navigation **Changements · Branches ·
 * Historique · Plus** (une vue à la fois, comme le preview) : Changements
 * (conflits nôtre/leur/les deux/contenu + abandon de fusion, zones exactes
 * avec compteurs +/− réels, chemin parent, tout indexer/désindexer) et zone
 * de commit collée en bas (sujet, compteur 0/72, Amend, Signoff — réels) ;
 * Branches (locales avec suivi et fusion, distantes, créer/basculer) ;
 * Historique (graphe à voies colorées dessinées, pastilles HEAD/branche/
 * étiquette, pagination) ; Plus (identité de commit, stash, .gitignore,
 * revert, reset soft, jeton, écran complet). La visionneuse de diff est une
 * feuille du bas : hunks @@, numéros de ligne, bascule indexé/non indexé.</p>
 *
 * <p>Toutes les opérations roulent hors fil UI et publient sur le bus
 * {@link WorkspaceEvents} ; les erreurs remontent le message du moteur —
 * jamais muettes. Les jetons vivent dans le coffre chiffré
 * {@link GitCredentialsStore}, par hôte, jamais en clair.</p>
 */
public final class GitTabFragment extends Fragment {

    private GitTabFragmentBinding binding;
    private WorkspaceViewModel viewModel;
    private Path root;
    private GitCredentialsStore credentialsStore;

    private final Handler main = new Handler(Looper.getMainLooper());

    /** Sous-navigation (preview : Changements · Branches · Historique · Plus). */
    private enum GitView { CHANGEMENTS, BRANCHES, HISTORIQUE, PLUS }

    private GitView currentView = GitView.CHANGEMENTS;

    /** Dernière page d'historique demandée (pagination « Charger 50 de plus »). */
    private int historyLimit = 50;

    /** Compteurs +/− du dernier snapshot (affichage immédiat, rafraîchi). */
    private Map<String, Counters> snapshotCounters;

    /** Une rangée de changement enrichie (compteurs +/− réels du diff). */
    private record Counters(int added, int removed) {}

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

        binding.gitBranchChip.setOnClickListener(v -> showBranchSelector());
        binding.gitFetch.setOnClickListener(v -> sync(FetchMode.FETCH));
        binding.gitPull.setOnClickListener(v -> sync(FetchMode.PULL));
        binding.gitPush.setOnClickListener(v -> sync(FetchMode.PUSH));
        binding.gitNavChanges.setOnClickListener(v -> switchView(GitView.CHANGEMENTS));
        binding.gitNavBranches.setOnClickListener(v -> switchView(GitView.BRANCHES));
        binding.gitNavHistory.setOnClickListener(v -> switchView(GitView.HISTORIQUE));
        binding.gitNavMore.setOnClickListener(v -> switchView(GitView.PLUS));
        setupCommitBox();
        render();
        return binding.getRoot();
    }

    /** Zone de commit : compteur 0/72 en direct, bouton plein. */
    private void setupCommitBox() {
        binding.gitCommitSubject.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) { }

            @Override
            public void afterTextChanged(Editable s) {
                binding.gitCommitCounter.setText(s.length() + "/72");
            }
        });
        binding.gitCommitButton.setOnClickListener(v -> {
            String message = binding.gitCommitSubject.getText().toString().trim();
            if (message.isEmpty()) {
                toast(getString(R.string.git_tiroir_message_vide));
                return;
            }
            boolean amend = binding.gitCommitAmend.isChecked();
            boolean signoff = binding.gitCommitSignoff.isChecked();
            opAsync(() -> GitOps.open(root).commitStaged(message, amend, signoff),
                    id -> {
                        binding.gitCommitSubject.setText("");
                        publishTreeAndGit();
                        snackbar(getString(R.string.git_tiroir_commit_ok, id));
                    });
        });
    }

    private void switchView(GitView view) {
        currentView = view;
        render();
    }

    /** Chips de sous-navigation : l'active sur sc2 (preview .chip.on). */
    private void renderNav() {
        styleNav(binding.gitNavChanges, currentView == GitView.CHANGEMENTS);
        styleNav(binding.gitNavBranches, currentView == GitView.BRANCHES);
        styleNav(binding.gitNavHistory, currentView == GitView.HISTORIQUE);
        styleNav(binding.gitNavMore, currentView == GitView.PLUS);
        binding.gitCommitBox.setVisibility(
                currentView == GitView.CHANGEMENTS ? View.VISIBLE : View.GONE);
    }

    private void styleNav(TextView chipView, boolean on) {
        chipView.setBackground(getResources().getDrawable(
                on ? R.drawable.fond_chip_on : R.drawable.fond_chip));
        chipView.setTextColor(getResources().getColor(
                on ? R.color.sur_sc2 : R.color.sur));
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
        render();
    }

    // ---- Rendu ------------------------------------------------------------------

    /** Recharge tout l'état hors fil UI puis redessine la vue active. */
    private void render() {
        if (root == null || binding == null) {
            return;
        }
        renderNav();
        opAsync(this::readSnapshot, this::renderSnapshot);
    }

    /** État complet du dépôt, lu en une fois hors fil UI. */
    private record Snapshot(List<GitOps.ChangeEntry> status,
            Map<String, Counters> counters,
            List<GitOps.BranchInfo> branches,
            Map<String, GitOps.Tracking> branchTracking,
            List<GitOps.BranchInfo> remoteBranches,
            List<GitOps.CommitNode> history,
            Map<String, List<GitOps.RefBadge>> refsByCommit,
            List<GitOps.StashEntry> stash,
            GitOps.Tracking tracking, String remoteUrl, String branch,
            GitOps.Identity identity, boolean mergeInProgress) {}

    private Snapshot readSnapshot() throws IOException {
        if (!Files.isDirectory(root.resolve(".git"))) {
            return null; // pas un dépôt : état dédié.
        }
        GitOps ops = GitOps.open(root);
        List<GitOps.ChangeEntry> status = ops.exactStatus();
        Map<String, Counters> counters = new HashMap<>();
        int counted = 0;
        for (GitOps.ChangeEntry entry : status) {
            if (counted >= 60) {
                break; // garde-fou : au-delà, les compteurs seuls manquent.
            }
            String key = entry.area() + ":" + entry.path();
            if (entry.area() == GitOps.Area.STAGED
                    || entry.area() == GitOps.Area.UNSTAGED) {
                List<GitOps.DiffLine> lines =
                        ops.diff(entry.path(), entry.area() == GitOps.Area.STAGED);
                int added = 0;
                int removed = 0;
                for (GitOps.DiffLine line : lines) {
                    if (line.kind() == GitOps.LineKind.ADD) {
                        added++;
                    } else if (line.kind() == GitOps.LineKind.REMOVE) {
                        removed++;
                    }
                }
                counters.put(key, new Counters(added, removed));
                counted++;
            } else if (entry.area() == GitOps.Area.UNTRACKED) {
                Path file = root.resolve(entry.path());
                if (Files.isRegularFile(file)) {
                    counters.put(key, new Counters(
                            (int) Files.lines(file).count(), 0));
                    counted++;
                }
            }
        }
        List<GitOps.BranchInfo> branches = ops.branches();
        Map<String, GitOps.Tracking> branchTracking = new HashMap<>();
        for (GitOps.BranchInfo branch : branches) {
            try {
                GitOps.Tracking tracking = ops.trackingOf(branch.name());
                if (tracking != null) {
                    branchTracking.put(branch.name(), tracking);
                }
            } catch (IOException ignored) {
                // pas d'amont pour cette branche : simplement absent.
            }
        }
        List<GitOps.CommitNode> history = ops.graph(historyLimit);
        Map<String, List<GitOps.RefBadge>> refsByCommit = new HashMap<>();
        for (GitOps.CommitNode commit : history) {
            refsByCommit.put(commit.id(), ops.refsFor(commit.id()));
        }
        GitOps.Tracking tracking;
        String remoteUrl;
        try {
            tracking = ops.tracking();
            remoteUrl = ops.remoteUrl();
        } catch (IOException e) {
            tracking = null;
            remoteUrl = null;
        }
        return new Snapshot(status, counters, branches, branchTracking,
                ops.remoteBranches(), history, refsByCommit, ops.stashList(),
                tracking, remoteUrl, ops.currentBranch(), ops.identity(),
                !ops.conflicts().isEmpty());
    }

    private void renderSnapshot(Snapshot snapshot) {
        if (binding == null) {
            return;
        }
        LinearLayout sections = binding.gitSections;
        sections.removeAllViews();
        if (snapshot == null) {
            binding.gitBranchName.setText("");
            binding.gitTracking.setText("");
            binding.gitLastSync.setText("");
            binding.gitCommitBox.setVisibility(View.GONE);
            renderNotARepository(sections);
            return;
        }
        snapshotCounters = snapshot.counters();
        renderHeader(snapshot);
        switch (currentView) {
            case BRANCHES -> renderBranches(sections, snapshot);
            case HISTORIQUE -> renderHistory(sections, snapshot);
            case PLUS -> renderMore(sections, snapshot);
            default -> renderChanges(sections, snapshot);
        }
    }

    private void renderHeader(Snapshot snapshot) {
        binding.gitBranchName.setText(snapshot.branch());
        GitOps.Tracking tracking = snapshot.tracking();
        binding.gitTracking.setText(tracking == null
                ? getString(R.string.git_tiroir_sans_amont)
                : tracking.remoteBranch() + " · ↓" + tracking.behind()
                        + " ↑" + tracking.ahead());
        long lastSync = viewModel.gitLastSyncMillis();
        binding.gitLastSync.setText(lastSync < 0 ? ""
                : getString(R.string.git_tiroir_synchronise, relativeWhen(lastSync)));
    }

    private void renderNotARepository(LinearLayout sections) {
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
        TextView init = contourButton(getString(R.string.git_tiroir_init),
                v -> showInitDialog());
        android.widget.LinearLayout.LayoutParams initParams =
                new android.widget.LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, dip(32));
        initParams.topMargin = dip(8);
        init.setLayoutParams(initParams);
        sections.addView(init);
    }

    /** Init avec .gitignore de départ Android/Gradle (preview), au choix. */
    private void showInitDialog() {
        LinearLayout box = new LinearLayout(requireContext());
        box.setOrientation(LinearLayout.VERTICAL);
        int padding = dip(20);
        box.setPadding(padding, dip(8), padding, 0);
        android.widget.CheckBox starter = new android.widget.CheckBox(requireContext());
        starter.setText(R.string.git_tiroir_init_ignore);
        starter.setChecked(true);
        starter.setTextSize(12.5f);
        starter.setTextColor(getResources().getColor(R.color.sur));
        box.addView(starter);
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.git_tiroir_init)
                .setView(box)
                .setPositiveButton(R.string.git_tiroir_init,
                        (dialog, which) -> opAsync(() -> {
                            GitOps.init(root, starter.isChecked());
                            return null;
                        }, ignored -> publishTreeAndGit()))
                .setNegativeButton(R.string.tiroir_action_annuler, null)
                .show();
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
            sectionHeader(sections,
                    getString(R.string.git_tiroir_conflits, conflicts.size()),
                    true, null, null);
            for (GitOps.ChangeEntry entry : conflicts) {
                sections.addView(conflictRow(entry));
            }
            TextView abort = contourButton(getString(
                    R.string.git_tiroir_abandonner_fusion));
            abort.setOnClickListener(v ->
                    new AlertDialog.Builder(requireContext())
                            .setMessage(R.string.git_tiroir_abandonner_fusion)
                            .setPositiveButton(R.string.git_tiroir_abandonner_fusion,
                                    (dialog, which) -> opAsync(() -> {
                                        GitOps.open(root).abortMerge();
                                        return null;
                                    }, ignored -> {
                                        viewModel.events().publish(
                                                new WorkspaceEvents.FileChangedOnDisk(root));
                                        publishTreeAndGit();
                                    }))
                            .setNegativeButton(R.string.tiroir_action_annuler, null)
                            .show());
            sections.addView(abort);
        }
        changeSection(sections, getString(R.string.git_tiroir_indexe, staged.size()),
                staged, getString(R.string.git_tiroir_tout_desindexer));
        changeSection(sections, getString(R.string.git_tiroir_non_indexe, unstaged.size()),
                unstaged, getString(R.string.git_tiroir_tout_indexer));
        changeSection(sections, getString(R.string.git_tiroir_non_suivi, untracked.size()),
                untracked, getString(R.string.git_tiroir_tout_indexer));
        if (snapshot.status().isEmpty()) {
            TextView clean = new TextView(requireContext());
            clean.setText(R.string.git_tiroir_vide);
            clean.setTextSize(12f);
            clean.setTextColor(getResources().getColor(R.color.ok));
            clean.setPadding(0, dip(8), 0, dip(8));
            sections.addView(clean);
        }
    }

    /** Une section de zone avec son action de masse (preview : tout indexer). */
    private void changeSection(LinearLayout parent, String title,
            List<GitOps.ChangeEntry> entries, String massAction) {
        View.OnClickListener mass = null;
        if (!entries.isEmpty()) {
            List<String> paths = entries.stream()
                    .map(GitOps.ChangeEntry::path).toList();
            boolean unstage = massAction.equals(
                    getString(R.string.git_tiroir_tout_desindexer));
            mass = v -> opAsync(() -> {
                GitOps ops = GitOps.open(root);
                if (unstage) {
                    ops.unstage(paths);
                } else {
                    ops.stage(paths);
                }
                return null;
            }, ignored -> publishTreeAndGit());
        }
        sectionHeader(parent, title, false, mass, massAction);
        for (GitOps.ChangeEntry entry : entries) {
            parent.addView(changeRow(entry));
        }
    }

    /** Titre de section (preview .tête-section) avec action à droite. */
    private void sectionHeader(LinearLayout parent, String title, boolean danger,
            View.OnClickListener action, String actionLabel) {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView label = new TextView(requireContext());
        label.setText(title);
        label.setTextSize(11.5f);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        label.setTextColor(getResources().getColor(
                danger ? R.color.erreur : R.color.sur_var));
        row.addView(label, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (action != null && actionLabel != null) {
            TextView actionView = new TextView(requireContext());
            actionView.setText(actionLabel);
            actionView.setTextSize(10.5f);
            actionView.setTextColor(getResources().getColor(R.color.primaire));
            actionView.setPadding(dip(8), dip(4), 0, dip(4));
            actionView.setOnClickListener(action);
            row.addView(actionView);
        }
        row.setPadding(0, dip(10), 0, dip(4));
        parent.addView(row);
    }

    /** Une rangée de changement conforme au preview : lettre, nom + chemin
     *  parent, compteurs +/− réels, action d'indexation, diff au clic. */
    private View changeRow(GitOps.ChangeEntry entry) {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(0, dip(6), 0, dip(6));
        row.addView(letterBadge(entry));
        LinearLayout texts = new LinearLayout(requireContext());
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        params.leftMargin = dip(8);
        TextView name = new TextView(requireContext());
        name.setText(Paths.get(entry.path()).getFileName().toString());
        name.setTextSize(12.5f);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setTextColor(getResources().getColor(nameColor(entry)));
        texts.addView(name);
        TextView parent = new TextView(requireContext());
        parent.setTextSize(10.5f);
        parent.setTextColor(getResources().getColor(R.color.sur_var));
        parent.setSingleLine(true);
        parent.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        if (entry.area() == GitOps.Area.UNSTAGED
                && entry.change() == GitOps.Change.REMOVED) {
            parent.setText(R.string.git_tiroir_supprime_dossier);
        } else {
            Path dir = Paths.get(entry.path()).getParent();
            parent.setText(dir == null ? "" : dir.toString());
        }
        texts.addView(parent);
        row.addView(texts, params);
        Counters counters = snapshotCounters == null ? null
                : snapshotCounters.get(entry.area() + ":" + entry.path());
        if (counters != null && (counters.added() > 0 || counters.removed() > 0)) {
            TextView counter = new TextView(requireContext());
            counter.setTypeface(Typeface.MONOSPACE);
            counter.setTextSize(10.5f);
            counter.setText("+" + counters.added() + " −" + counters.removed());
            counter.setTextColor(getResources().getColor(R.color.sur_var));
            LinearLayout.LayoutParams counterParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            counterParams.rightMargin = dip(6);
            row.addView(counter, counterParams);
        }
        TextView action = chip(entry.area() == GitOps.Area.STAGED ? "−" : "+");
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

    /** Lettre M/A/D/U/! du preview : couleurs réelles, D barré. */
    private TextView letterBadge(GitOps.ChangeEntry entry) {
        TextView letter = new TextView(requireContext());
        letter.setTextSize(10f);
        letter.setTypeface(Typeface.DEFAULT_BOLD);
        letter.setGravity(android.view.Gravity.CENTER);
        letter.setBackground(getResources().getDrawable(R.drawable.fond_badge));
        letter.setMinWidth(dip(18));
        letter.setMinHeight(dip(18));
        String text;
        int color;
        switch (entry.area()) {
            case CONFLICT -> {
                text = "U";
                color = R.color.erreur;
            }
            case UNTRACKED -> {
                text = "!";
                color = R.color.info;
            }
            default -> {
                if (entry.change() == GitOps.Change.REMOVED) {
                    text = "D";
                    color = R.color.sur_var;
                    letter.setPaintFlags(letter.getPaintFlags()
                            | Paint.STRIKE_THRU_TEXT_FLAG);
                } else if (entry.change() == GitOps.Change.ADDED) {
                    text = "A";
                    color = R.color.ok;
                } else {
                    text = "M";
                    color = R.color.git_modifie;
                }
            }
        }
        letter.setText(text);
        letter.setTextColor(getResources().getColor(color));
        return letter;
    }

    /** Couleur du nom selon l'état git (preview : modifié bleu, ajouté vert…). */
    private int nameColor(GitOps.ChangeEntry entry) {
        if (entry.area() == GitOps.Area.CONFLICT) {
            return R.color.erreur;
        }
        if (entry.area() == GitOps.Area.UNTRACKED) {
            return R.color.info;
        }
        if (entry.change() == GitOps.Change.REMOVED) {
            return R.color.contour;
        }
        return entry.area() == GitOps.Area.STAGED
                ? R.color.ok : R.color.git_modifie;
    }

    /** Rangée de conflit : nôtre / leur / les deux / contenu (preview). */
    private View conflictRow(GitOps.ChangeEntry entry) {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dip(8), 0, dip(8));
        TextView path = new TextView(requireContext());
        path.setText(entry.path());
        path.setTextSize(12.5f);
        path.setTypeface(Typeface.DEFAULT_BOLD);
        path.setTextColor(getResources().getColor(R.color.erreur));
        row.addView(path);
        LinearLayout buttons = new LinearLayout(requireContext());
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.addView(resolveChip(getString(R.string.git_tiroir_notre),
                () -> resolveConflict(entry.path(), GitOps.Resolution.OURS, null)));
        buttons.addView(margined(resolveChip(getString(R.string.git_tiroir_leur),
                () -> resolveConflict(entry.path(), GitOps.Resolution.THEIRS, null))));
        buttons.addView(margined(resolveChip(getString(R.string.git_tiroir_les_deux),
                () -> resolveConflict(entry.path(), GitOps.Resolution.BOTH, null))));
        buttons.addView(margined(resolveChip(getString(R.string.git_tiroir_contenu),
                () -> askConflictContent(entry.path()))));
        row.addView(buttons);
        return row;
    }

    private View margined(View child) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        params.leftMargin = dip(6);
        child.setLayoutParams(params);
        return child;
    }

    private View resolveChip(String label, Runnable action) {
        TextView chipView = chip(label);
        chipView.setOnClickListener(v -> action.run());
        return chipView;
    }

    private void askConflictContent(String path) {
        EditText input = new EditText(requireContext());
        input.setTextSize(13f);
        int padding = dip(20);
        input.setPadding(padding, dip(8), padding, 0);
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.git_tiroir_contenu_titre)
                .setView(input)
                .setPositiveButton(R.string.git_tiroir_notre,
                        (dialog, which) -> resolveConflict(path,
                                GitOps.Resolution.CONTENT, input.getText().toString()))
                .setNegativeButton(R.string.tiroir_action_annuler, null)
                .show();
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

    // ---- Branches -----------------------------------------------------------------

    private void renderBranches(LinearLayout sections, Snapshot snapshot) {
        sectionHeader(sections, getString(R.string.git_tiroir_branches_locales),
                false, null, null);
        for (GitOps.BranchInfo branch : snapshot.branches()) {
            sections.addView(branchRow(branch, snapshot));
        }
        LinearLayout actions = new LinearLayout(requireContext());
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, dip(8), 0, dip(4));
        actions.addView(contourButton(getString(R.string.git_tiroir_nouvelle_branche),
                v -> showCreateBranchDialog()));
        actions.addView(margined(contourButton(
                getString(R.string.git_tiroir_basculer), v -> showBranchSelector())));
        sections.addView(actions);
        sectionHeader(sections, getString(R.string.git_tiroir_branches_distantes),
                false, null, null);
        for (GitOps.BranchInfo remote : snapshot.remoteBranches()) {
            LinearLayout row = new LinearLayout(requireContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(0, dip(5), 0, dip(5));
            TextView remoteChip = chip(remote.name());
            remoteChip.setAlpha(0.65f);
            row.addView(remoteChip);
            sections.addView(row);
        }
        if (snapshot.remoteBranches().isEmpty()) {
            TextView none = new TextView(requireContext());
            none.setText(R.string.git_tiroir_sans_amont);
            none.setTextSize(11f);
            none.setTextColor(getResources().getColor(R.color.contour));
            sections.addView(none);
        }
    }

    /** Une branche locale : pastille, suivi ↓↑, fusion (preview .pastille-réf). */
    private View branchRow(GitOps.BranchInfo branch, Snapshot snapshot) {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(0, dip(6), 0, dip(6));
        if (branch.current()) {
            row.addView(refBadge("HEAD", "tête"));
            row.addView(margined(refBadge(branch.name(), "br")));
        } else {
            row.addView(refBadge(branch.name(), "br"));
        }
        TextView info = new TextView(requireContext());
        info.setTextSize(10.5f);
        info.setTextColor(getResources().getColor(R.color.sur_var));
        LinearLayout.LayoutParams infoParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        infoParams.leftMargin = dip(8);
        GitOps.Tracking tracking = snapshot.branchTracking().get(branch.name());
        info.setText(tracking == null ? "" : "↓" + tracking.behind()
                + " ↑" + tracking.ahead() + " · " + tracking.remoteBranch());
        row.addView(info, infoParams);
        if (branch.current()) {
            row.addView(chip(getString(R.string.git_tiroir_courante)));
        } else {
            row.addView(contourButton(getString(R.string.git_tiroir_fusionner),
                    v -> new AlertDialog.Builder(requireContext())
                            .setMessage(getString(R.string.git_tiroir_fusion_confirm,
                                    branch.name()))
                            .setPositiveButton(R.string.git_tiroir_fusionner,
                                    (dialog, which) -> opAsync(() -> {
                                        GitOps.open(root).merge(branch.name());
                                        return null;
                                    }, ignored -> {
                                        viewModel.events().publish(
                                                new WorkspaceEvents.FileChangedOnDisk(root));
                                        publishTreeAndGit();
                                    }))
                            .setNegativeButton(R.string.tiroir_action_annuler, null)
                            .show()));
            row.setOnLongClickListener(v -> {
                confirmDeleteBranch(branch.name());
                return true;
            });
            row.setOnClickListener(v -> opAsync(() -> {
                GitOps.open(root).switchBranch(branch.name());
                return null;
            }, ignored -> {
                viewModel.events().publish(
                        new WorkspaceEvents.FileChangedOnDisk(root));
                publishTreeAndGit();
                snackbar(getString(R.string.git_tiroir_bascule_ok, branch.name()));
            }));
        }
        return row;
    }

    /** Pastille de référence (preview .pastille-réf : tête/br/tag). */
    private TextView refBadge(String name, String kind) {
        TextView badge = new TextView(requireContext());
        badge.setText(name);
        badge.setTextSize(9.5f);
        badge.setTypeface(Typeface.DEFAULT_BOLD);
        badge.setPadding(dip(7), 0, dip(7), 0);
        badge.setMinHeight(dip(16));
        badge.setGravity(android.view.Gravity.CENTER);
        int background;
        int color;
        switch (kind) {
            case "tête" -> {
                background = R.drawable.fond_badge_primaire;
                color = R.color.sur_conteneur_primaire;
            }
            case "tag" -> {
                background = R.drawable.fond_badge_alerte;
                color = R.color.alerte;
            }
            case "br" -> {
                background = R.drawable.fond_badge;
                color = R.color.sur_sc2;
            }
            default -> {
                background = R.drawable.fond_badge;
                color = R.color.sur_var;
            }
        }
        badge.setBackgroundResource(background);
        badge.setTextColor(getResources().getColor(color));
        return badge;
    }

    private void showCreateBranchDialog() {
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
                            }, ignored -> publishTreeAndGit());
                        })
                .setNegativeButton(R.string.tiroir_action_annuler, null)
                .show();
    }

    /** Sélecteur de branches (preview : la chip branche s'ouvre en menu). */
    private void showBranchSelector() {
        opAsync(() -> GitOps.open(root).branches(), branches -> {
            String[] names = branches.stream()
                    .map(GitOps.BranchInfo::name).toArray(String[]::new);
            String current = branches.stream().filter(GitOps.BranchInfo::current)
                    .map(GitOps.BranchInfo::name).findFirst().orElse("");
            int checked = -1;
            for (int i = 0; i < names.length; i++) {
                if (names[i].equals(current)) {
                    checked = i;
                }
            }
            new AlertDialog.Builder(requireContext())
                    .setTitle(R.string.git_tiroir_basculer)
                    .setSingleChoiceItems(names, checked, (dialog, which) -> {
                        dialog.dismiss();
                        if (!names[which].equals(current)) {
                            opAsync(() -> {
                                GitOps.open(root).switchBranch(names[which]);
                                return null;
                            }, ignored -> {
                                viewModel.events().publish(
                                        new WorkspaceEvents.FileChangedOnDisk(root));
                                publishTreeAndGit();
                                snackbar(getString(
                                        R.string.git_tiroir_bascule_ok, names[which]));
                            });
                        }
                    })
                    .setNegativeButton(R.string.tiroir_action_annuler, null)
                    .show();
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

    // ---- Historique avec graphe ------------------------------------------------

    /** Couleurs des voies du graphe (preview : bleu, vert, tertiaire…). */
    private static final int[] LANE_COLORS = {
            0xFF3B82C4, 0xFF29A847, 0xFF6C3E85, 0xFFD98300, 0xFF2399B8 };

    /** Une rangée de graphe : voie du commit, voies avant/après. */
    private record LaneRow(int lane, TreeSet<Integer> before, TreeSet<Integer> after) {}

    private void renderHistory(LinearLayout sections, Snapshot snapshot) {
        List<LaneRow> lanes = computeLanes(snapshot.history());
        for (int i = 0; i < snapshot.history().size(); i++) {
            sections.addView(historyRow(snapshot.history().get(i),
                    lanes.get(i), snapshot));
        }
        TextView more = contourButton(getString(R.string.git_tiroir_charger_plus),
                dip(28), 11f);
        more.setOnClickListener(v -> {
            historyLimit += 50;
            render();
        });
        android.widget.LinearLayout.LayoutParams moreParams =
                new android.widget.LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, dip(28));
        moreParams.topMargin = dip(6);
        more.setLayoutParams(moreParams);
        sections.addView(more);
    }

    /**
     * Voies du graphe : chaque commit hérite de la voie réservée par son
     * enfant ; le premier parent continue la voie, les autres parents en
     * ouvrent de nouvelles — les fusions se lisent comme dans le preview.
     */
    private List<LaneRow> computeLanes(List<GitOps.CommitNode> history) {
        List<LaneRow> rows = new ArrayList<>();
        Map<String, Integer> reserved = new HashMap<>();
        int nextLane = 0;
        TreeSet<Integer> before = new TreeSet<>();
        for (GitOps.CommitNode commit : history) {
            Integer lane = reserved.remove(commit.id());
            if (lane == null) {
                lane = nextLane++;
            }
            if (!commit.parents().isEmpty()) {
                reserved.put(commit.parents().get(0), lane);
                for (int p = 1; p < commit.parents().size(); p++) {
                    reserved.put(commit.parents().get(p), nextLane++);
                }
            }
            TreeSet<Integer> after = new TreeSet<>(reserved.values());
            rows.add(new LaneRow(lane, new TreeSet<>(before), after));
            before = after;
        }
        return rows;
    }

    private View historyRow(GitOps.CommitNode commit, LaneRow lane, Snapshot snapshot) {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.TOP);
        row.setPadding(0, dip(2), 0, dip(2));
        row.addView(new LaneView(requireContext(), lane));
        LinearLayout text = new LinearLayout(requireContext());
        text.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        params.leftMargin = dip(8);
        LinearLayout titleLine = new LinearLayout(requireContext());
        titleLine.setOrientation(LinearLayout.HORIZONTAL);
        titleLine.setGravity(android.view.Gravity.CENTER_VERTICAL);
        for (GitOps.RefBadge ref : snapshot.refsByCommit()
                .getOrDefault(commit.id(), List.of())) {
            titleLine.addView(refBadge(ref.name(), ref.kind()));
            View gap = new View(requireContext());
            gap.setLayoutParams(new LinearLayout.LayoutParams(dip(4), 1));
            titleLine.addView(gap);
        }
        TextView message = new TextView(requireContext());
        message.setText(commit.message());
        message.setTextSize(12f);
        message.setTypeface(Typeface.DEFAULT_BOLD);
        message.setTextColor(getResources().getColor(R.color.sur));
        message.setSingleLine(true);
        message.setEllipsize(android.text.TextUtils.TruncateAt.END);
        titleLine.addView(message, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        text.addView(titleLine);
        TextView detail = new TextView(requireContext());
        detail.setText(commit.author() + " · "
                + relativeWhen(commit.when().toEpochMilli())
                + " · " + commit.id());
        detail.setTextSize(10.5f);
        detail.setTextColor(getResources().getColor(R.color.sur_var));
        text.addView(detail);
        row.addView(text, params);
        row.setOnClickListener(v -> showCommitDiff(commit.id()));
        return row;
    }

    /** Dessine les voies verticales et le nœud d'un commit (Canvas). */
    private static final class LaneView extends View {

        private final LaneRow lane;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        LaneView(Context context, LaneRow lane) {
            super(context);
            this.lane = lane;
        }

        @Override
        protected void onDraw(@NonNull Canvas canvas) {
            super.onDraw(canvas);
            float density = getResources().getDisplayMetrics().density;
            int maxLane = lane.lane();
            for (int laneIndex : lane.after()) {
                maxLane = Math.max(maxLane, laneIndex);
            }
            for (int laneIndex : lane.before()) {
                maxLane = Math.max(maxLane, laneIndex);
            }
            float spacing = maxLane <= 0 ? 0
                    : Math.min(10 * density, (getWidth() - 12 * density) / maxLane);
            float centerY = getHeight() / 2f;
            paint.setStrokeWidth(2.5f * density);
            paint.setStrokeCap(Paint.Cap.ROUND);
            for (int laneIndex : lane.before()) {
                float x = 6 * density + laneIndex * spacing;
                float bottom = laneIndex == lane.lane() ? centerY : getHeight();
                paint.setColor(laneColor(laneIndex));
                canvas.drawLine(x, 0, x, bottom, paint);
            }
            for (int laneIndex : lane.after()) {
                float x = 6 * density + laneIndex * spacing;
                float top = laneIndex == lane.lane() ? centerY : 0;
                paint.setColor(laneColor(laneIndex));
                canvas.drawLine(x, top, x, getHeight(), paint);
            }
            float nodeX = 6 * density + lane.lane() * spacing;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(getResources().getColor(R.color.sc));
            canvas.drawCircle(nodeX, centerY, 5 * density, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2.5f * density);
            paint.setColor(laneColor(lane.lane()));
            canvas.drawCircle(nodeX, centerY, 5 * density, paint);
        }

        private int laneColor(int index) {
            int color = LANE_COLORS[index % LANE_COLORS.length];
            return (color & 0xFFFFFF) | 0xFF000000;
        }
    }

    // ---- Plus ------------------------------------------------------------------------

    private void renderMore(LinearLayout sections, Snapshot snapshot) {
        // Identité de commit (preview : « Qui êtes-vous ? ») — carte si absente.
        if (snapshot.identity() == null) {
            LinearLayout card = new LinearLayout(requireContext());
            card.setOrientation(LinearLayout.HORIZONTAL);
            card.setGravity(android.view.Gravity.CENTER_VERTICAL);
            card.setPadding(dip(11), dip(11), dip(11), dip(11));
            card.setBackground(getResources().getDrawable(R.drawable.fond_field));
            LinearLayout texts = new LinearLayout(requireContext());
            texts.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            TextView title = new TextView(requireContext());
            title.setText(R.string.git_tiroir_identite);
            title.setTextSize(12.5f);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            title.setTextColor(getResources().getColor(R.color.sur));
            texts.addView(title);
            TextView detail = new TextView(requireContext());
            detail.setText(R.string.git_tiroir_identite_detail);
            detail.setTextSize(10.5f);
            detail.setTextColor(getResources().getColor(R.color.sur_var));
            texts.addView(detail);
            card.addView(texts, params);
            card.addView(contourButton(getString(R.string.git_tiroir_definir),
                    v -> showIdentityDialog()));
            sections.addView(card);
        }
        if (!snapshot.stash().isEmpty()) {
            sectionHeader(sections, getString(R.string.git_tiroir_stash,
                    snapshot.stash().size()), false, null, null);
            for (GitOps.StashEntry stash : snapshot.stash()) {
                sections.addView(stashRow(stash));
            }
        }
        sections.addView(action(getString(R.string.git_tiroir_remiser), v -> {
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
        sections.addView(action(getString(R.string.git_tiroir_gitignore), v -> {
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
        sections.addView(action(getString(R.string.git_tiroir_revert), v ->
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
        sections.addView(action(getString(R.string.git_tiroir_reset), v ->
                opAsync(() -> {
                    GitOps.open(root).reset(GitOps.ResetMode.SOFT, "HEAD~1");
                    return null;
                }, ignored -> {
                    viewModel.events().publish(
                            new WorkspaceEvents.FileChangedOnDisk(root));
                    publishTreeAndGit();
                })));
        sections.addView(action(getString(R.string.git_tiroir_oublier_jeton), v ->
                opAsync(() -> GitOps.open(root).remoteUrl(), remote -> {
                    if (remote == null) {
                        toast(getString(R.string.git_tiroir_pas_depot));
                        return;
                    }
                    credentialsStore.remove(remote);
                    snackbar(getString(R.string.git_tiroir_jeton_oublie, remote));
                })));
        sections.addView(action(getString(R.string.git_tiroir_ecran_complet), v -> {
            WorkbenchHost host = host();
            if (host != null) {
                host.openGitScreen();
            }
        }));
    }

    private View stashRow(GitOps.StashEntry stash) {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(0, dip(6), 0, dip(6));
        TextView label = new TextView(requireContext());
        label.setText(stash.message() + " · "
                + relativeWhen(stash.when().toEpochMilli()));
        label.setTextSize(12f);
        label.setTextColor(getResources().getColor(R.color.sur));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(label, params);
        TextView apply = contourButton(getString(R.string.git_tiroir_appliquer),
                dip(24), 10f);
        apply.setOnClickListener(v -> opAsync(() -> {
            GitOps.open(root).stashApply(stash.index(), false);
            return null;
        }, ignored -> {
            viewModel.events().publish(new WorkspaceEvents.FileChangedOnDisk(root));
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
        return row;
    }

    /** Carte « Qui êtes-vous ? » : nom + e-mail réels dans la config du dépôt. */
    private void showIdentityDialog() {
        LinearLayout box = new LinearLayout(requireContext());
        box.setOrientation(LinearLayout.VERTICAL);
        int padding = dip(20);
        box.setPadding(padding, dip(8), padding, 0);
        EditText name = new EditText(requireContext());
        name.setHint(R.string.git_tiroir_identite_nom);
        name.setTextSize(13f);
        box.addView(name);
        EditText email = new EditText(requireContext());
        email.setHint(R.string.git_tiroir_identite_email);
        email.setTextSize(13f);
        box.addView(email);
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.git_tiroir_identite)
                .setView(box)
                .setPositiveButton(R.string.git_tiroir_definir,
                        (dialog, which) -> opAsync(() -> {
                            GitOps.open(root).setIdentity(
                                    name.getText().toString().trim(),
                                    email.getText().toString().trim());
                            return null;
                        }, ignored -> publishTreeAndGit()))
                .setNegativeButton(R.string.tiroir_action_annuler, null)
                .show();
    }

    // ---- Visionneuse de diff (feuille du bas, preview) ------------------------------

    /** Diff d'un fichier : feuille du bas avec hunks, numéros, bascule. */
    private void showDiff(String path, boolean staged) {
        opAsync(() -> GitOps.open(root).diff(path, staged), lines ->
                showDiffSheet(path, path, lines, staged, true));
    }

    /** Diff complet d'un commit (fichiers en titres). */
    private void showCommitDiff(String commitId) {
        opAsync(() -> GitOps.open(root).commitDiffs(commitId), files -> {
            List<GitOps.DiffLine> lines = new ArrayList<>();
            for (GitOps.FileDiff file : files) {
                if (!lines.isEmpty()) {
                    lines.add(new GitOps.DiffLine(GitOps.LineKind.CONTEXT, ""));
                }
                lines.add(new GitOps.DiffLine(GitOps.LineKind.HUNK,
                        "—— " + file.path() + " ——"));
                lines.addAll(file.lines());
            }
            showDiffSheet(commitId, commitId, lines, false, false);
        });
    }

    private void showDiffSheet(String title, String diffPath,
            List<GitOps.DiffLine> lines, boolean staged, boolean toggleable) {
        BottomSheetDialog sheet = new BottomSheetDialog(requireContext());
        LinearLayout content = new LinearLayout(requireContext());
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dip(14), dip(6), dip(14), dip(14));
        content.addView(diffHeader(title, lines, staged, toggleable, diffPath, sheet));
        android.widget.ScrollView scroll = new android.widget.ScrollView(requireContext());
        LinearLayout list = new LinearLayout(requireContext());
        list.setOrientation(LinearLayout.VERTICAL);
        int[] numbers = {0, 0}; // [ancien, nouveau] — suivis depuis les hunks.
        for (GitOps.DiffLine line : lines) {
            if (line.kind() == GitOps.LineKind.HUNK) {
                if (line.text().startsWith("@@")) {
                    parseHunk(line.text(), numbers);
                }
                TextView hunk = new TextView(requireContext());
                hunk.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
                hunk.setTextSize(10.5f);
                hunk.setTextColor(getResources().getColor(R.color.info));
                hunk.setPadding(dip(10), dip(4), dip(10), dip(2));
                hunk.setText(line.text());
                list.addView(hunk);
                continue;
            }
            list.addView(diffLineRow(line, numbers));
        }
        scroll.addView(list);
        content.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        sheet.setContentView(content, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (int) (getResources().getDisplayMetrics().heightPixels * 0.72f)));
        sheet.show();
    }

    /** En-tête de la feuille : titre, compteurs +/−, bascule indexé/non indexé. */
    private View diffHeader(String title, List<GitOps.DiffLine> lines,
            boolean staged, boolean toggleable, String diffPath,
            BottomSheetDialog sheet) {
        LinearLayout header = new LinearLayout(requireContext());
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(android.view.Gravity.CENTER_VERTICAL);
        int added = 0;
        int removed = 0;
        for (GitOps.DiffLine line : lines) {
            if (line.kind() == GitOps.LineKind.ADD) {
                added++;
            } else if (line.kind() == GitOps.LineKind.REMOVE) {
                removed++;
            }
        }
        TextView titleView = new TextView(requireContext());
        titleView.setText(getString(R.string.git_tiroir_diff, title));
        titleView.setTextSize(12.5f);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        titleView.setTextColor(getResources().getColor(R.color.sur));
        titleView.setSingleLine(true);
        titleView.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        header.addView(titleView, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (toggleable) {
            TextView toggle = contourButton(getString(staged
                    ? R.string.git_tiroir_diff_indexe
                    : R.string.git_tiroir_diff_non_indexe), dip(24), 10f);
            toggle.setOnClickListener(v -> {
                sheet.dismiss();
                showDiff(diffPath, !staged);
            });
            header.addView(toggle);
        }
        TextView counters = new TextView(requireContext());
        counters.setTypeface(Typeface.MONOSPACE);
        counters.setTextSize(10.5f);
        counters.setText("+" + added + " −" + removed);
        LinearLayout.LayoutParams counterParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        counterParams.leftMargin = dip(8);
        header.addView(counters, counterParams);
        return header;
    }

    /** Une ligne de diff avec son numéro (ancien pour −, nouveau pour +). */
    private View diffLineRow(GitOps.DiffLine line, int[] numbers) {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView number = new TextView(requireContext());
        number.setTypeface(Typeface.MONOSPACE);
        number.setTextSize(9.5f);
        number.setTextColor(getResources().getColor(R.color.contour));
        number.setMinWidth(dip(26));
        number.setGravity(android.view.Gravity.END);
        TextView text = new TextView(requireContext());
        text.setTypeface(Typeface.MONOSPACE);
        text.setTextSize(10.5f);
        switch (line.kind()) {
            case ADD -> {
                numbers[1]++;
                number.setText(String.valueOf(numbers[1]));
                text.setBackground(getResources().getDrawable(R.drawable.fond_diff_plus));
                text.setTextColor(getResources().getColor(R.color.ok));
            }
            case REMOVE -> {
                numbers[0]++;
                number.setText(String.valueOf(numbers[0]));
                text.setBackground(getResources().getDrawable(R.drawable.fond_diff_moins));
                text.setTextColor(getResources().getColor(R.color.erreur));
            }
            default -> {
                numbers[0]++;
                numbers[1]++;
                number.setText(String.valueOf(numbers[1]));
                text.setTextColor(getResources().getColor(R.color.sur_var));
            }
        }
        text.setText(line.text().isEmpty() ? " " : line.text());
        text.setPadding(dip(8), 0, dip(8), 0);
        row.addView(number);
        row.addView(text, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    /** Lit « @@ -a,b +c,d @@ » pour remettre les compteurs de numéros. */
    private static void parseHunk(String hunk, int[] numbers) {
        try {
            java.util.regex.Matcher matcher = java.util.regex.Pattern
                    .compile("@@ -(\\d+)[,\\d]* \\+(\\d+)[,\\d]* @@")
                    .matcher(hunk);
            if (matcher.find()) {
                numbers[0] = Integer.parseInt(matcher.group(1));
                numbers[1] = Integer.parseInt(matcher.group(2));
            }
        } catch (RuntimeException ignored) {
            // en-tête illisible : les numéros continuent de courir.
        }
    }

    // ---- Réseau : fetch / pull / push (jeton au coffre) -------------------------------

    private enum FetchMode { FETCH, PULL, PUSH }

    /** fetch, pull ou push : jeton du coffre par hôte, dialogue si absent. */
    private void sync(FetchMode mode) {
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
                askCredentials(remote, mode);
                return;
            }
            runSync(mode, credentials);
        });
    }

    /** Dialogue d'identifiants (stockés chiffrés à la confirmation). */
    private void askCredentials(String remote, FetchMode mode) {
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
                            runSync(mode, credentialsStore.of(remote));
                        })
                .setNegativeButton(R.string.tiroir_action_annuler, null)
                .show();
    }

    private void runSync(FetchMode mode, GitOps.Credentials credentials) {
        opAsync(() -> {
            GitOps ops = GitOps.open(root);
            return switch (mode) {
                case FETCH -> ops.fetch(credentials);
                case PULL -> ops.pull(credentials);
                case PUSH -> ops.push(credentials);
            };
        }, result -> {
            viewModel.setGitLastSyncMillis(System.currentTimeMillis());
            publishTreeAndGit();
            snackbar(result.summary());
        });
    }

    // ---- Petites briques d'UI --------------------------------------------------------

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

    /** Bouton contour (preview .bouton.contour). */
    private TextView contourButton(String label, View.OnClickListener listener) {
        TextView button = contourButton(label, dip(32), 11.5f);
        button.setOnClickListener(listener);
        return button;
    }

    private TextView contourButton(String label) {
        return contourButton(label, dip(32), 11.5f);
    }

    private TextView contourButton(String label, int height, float size) {
        TextView button = new TextView(requireContext());
        button.setText(label);
        button.setTextSize(size);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setTextColor(getResources().getColor(R.color.sur));
        button.setBackground(getResources().getDrawable(R.drawable.fond_bouton_contour));
        button.setPadding(dip(12), 0, dip(12), 0);
        button.setMinHeight(height);
        button.setGravity(android.view.Gravity.CENTER);
        return button;
    }

    /** Chip 30dp du studio. */
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

    /** « il y a 12 min / 3 h / 2 j » — borné au jour. */
    private String relativeWhen(long epochMillis) {
        long minutes = Math.max(0,
                (System.currentTimeMillis() - epochMillis) / 60000L);
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
