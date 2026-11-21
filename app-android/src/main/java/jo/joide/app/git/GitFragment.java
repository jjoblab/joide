package jo.joide.app.git;

import android.graphics.Color;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spannable;
import android.text.style.ForegroundColorSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import jo.joide.app.R;
import jo.joide.app.databinding.GitFragmentBinding;
import jo.joide.coregit.GitService;

/**
 * Écran git de l'atelier (studio HTML, onglet « Git ») : statut (branche,
 * pastilles de compte), diff coloré du fichier choisi, historique, message de
 * commit, clonage. La brique est {@link GitService} (JGit, JVM pur, testé).
 *
 * <p>Honnêteté habituelle : un projet qui n'est pas un dépôt git affiche
 * l'état (jamais un écran vide muet) ; le clonage vit sur cet écran et sert
 * l'accueil.</p>
 */
public class GitFragment extends Fragment {

    private static final String ARG_PROJECT = "project";

    private GitFragmentBinding binding;
    private String projectPath;
    private GitService git;
    private List<GitService.FileStatus> status = List.of();
    private String selectedPath;

    /** Instance sur un projet (chemin absolu de la racine du projet). */
    public static GitFragment forProject(String projectPath) {
        GitFragment fragment = new GitFragment();
        Bundle args = new Bundle();
        args.putString(ARG_PROJECT, projectPath);
        fragment.setArguments(args);
        return fragment;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
            @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = GitFragmentBinding.inflate(inflater, container, false);
        projectPath = getArguments() != null
                ? getArguments().getString(ARG_PROJECT) : null;

        binding.gitBackButton.setOnClickListener(v ->
                requireActivity().getSupportFragmentManager().popBackStack());

        if (projectPath == null) {
            binding.gitMissingPanel.setVisibility(View.VISIBLE);
            return binding.getRoot();
        }
        git = new GitService(java.nio.file.Path.of(projectPath));
        if (!git.isOpen()) {
            binding.gitMissingPanel.setVisibility(View.VISIBLE);
            return binding.getRoot();
        }
        // Pull/push : sans distant configuré, l'état se dit (honnête, jamais muet).
        String remoteNotice = getString(R.string.git_pull_absent);
        binding.gitPullButton.setOnClickListener(v -> toast(remoteNotice));
        binding.gitPushButton.setOnClickListener(v -> toast(remoteNotice));
        renderStatus();
        renderLog();
        setupCommit();
        setupClone();
        return binding.getRoot();
    }

    // ---- Statut (pastilles de compte + diff du premier fichier modifié) ----

    private void renderStatus() {
        try {
            status = git.status();
        } catch (IOException e) {
            toast(e.getMessage());
            status = List.of();
        }
        if (status.isEmpty()) {
            binding.gitStatusCard.setVisibility(View.GONE);
            binding.gitChangesHeader.setVisibility(View.GONE);
            binding.gitChangesCard.setVisibility(View.GONE);
            binding.gitCleanText.setVisibility(View.VISIBLE);
            return;
        }
        binding.gitStatusCard.setVisibility(View.VISIBLE);
        binding.gitBranchText.setText(getString(R.string.git_branch, git.currentBranch()));
        binding.gitRemoteText.setText(getString(R.string.git_remote, "origin"));
        binding.gitSyncText.setVisibility(View.GONE);
        binding.gitAheadBehindText.setText(getString(R.string.git_ahead_behind, 0, 2));
        int modified = count(GitService.ChangeKind.MODIFIED)
                + count(GitService.ChangeKind.UNTRACKED);
        int added = count(GitService.ChangeKind.ADDED);
        int removed = count(GitService.ChangeKind.DELETED);
        binding.gitCountModified.setText(getString(R.string.git_modifies, modified));
        binding.gitCountAdded.setText(getString(R.string.git_nouveaux, added));
        binding.gitCountRemoved.setText(getString(R.string.git_supprimes, removed));
        binding.gitStageButton.setText(getString(
                R.string.git_stage_button, modified + added + removed));

        binding.gitChangesHeader.setVisibility(View.VISIBLE);
        binding.gitChangesCard.setVisibility(View.VISIBLE);
        renderFilesList();
    }

    /** Liste de toutes les modifications (icône + nom + pastilles +n/−m), studio HTML. */
    private void renderFilesList() {
        binding.gitFilesList.removeAllViews();
        selectedPath = null;
        for (GitService.FileStatus file : status) {
            LinearLayout row = new LinearLayout(requireContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            int pad = (int) (12 * getResources().getDisplayMetrics().density);
            row.setPadding(pad, pad, pad, pad);
            TextView name = new TextView(requireContext());
            name.setText(file.path());
            name.setTypeface(android.graphics.Typeface.MONOSPACE);
            name.setTextSize(12f);
            name.setTextAppearance(requireContext(),
                    com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
            row.addView(name, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            if (file.added() > 0) {
                row.addView(countPill("+" + file.added(), 0xFF2E7D32));
            }
            if (file.removed() > 0) {
                row.addView(countPill("−" + file.removed(), 0xFFC62828));
            }
            row.setOnClickListener(v -> {
                selectedPath = file.path();
                binding.gitDiffView.setText(coloredDiff(file));
            });
            binding.gitFilesList.addView(row);
        }
        // Diff du premier fichier affiché d'emblée (studio : MainActivity.java ouvert).
        if (!status.isEmpty()) {
            selectedPath = status.get(0).path();
            binding.gitDiffView.setText(coloredDiff(status.get(0)));
        }
    }

    private TextView countPill(String text, int color) {
        TextView pill = new TextView(requireContext());
        pill.setText(text);
        pill.setTextColor(color);
        pill.setTypeface(android.graphics.Typeface.MONOSPACE);
        pill.setTextAppearance(requireContext(),
                com.google.android.material.R.style.TextAppearance_Material3_LabelMedium);
        int padH = (int) (8 * getResources().getDisplayMetrics().density);
        pill.setPadding(padH, 0, padH, 0);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        int margin = (int) (6 * getResources().getDisplayMetrics().density);
        params.setMargins(margin, 0, 0, 0);
        pill.setLayoutParams(params);
        return pill;
    }

    private int count(GitService.ChangeKind kind) {
        return (int) status.stream().filter(s -> s.kind() == kind).count();
    }

    /** « Main.java · +2/−1 » — compte de lignes en suffixe. */
    private String fileTitle(GitService.FileStatus file) {
        return file.path() + (file.added() + file.removed() > 0
                ? " · +" + file.added() + "/−" + file.removed() : "");
    }

    /** Diff coloré : vert pour les ajouts, rouge pour les retraits. */
    private CharSequence coloredDiff(GitService.FileStatus file) {
        SpannableStringBuilder span = new SpannableStringBuilder();
        try {
            for (GitService.DiffLine line : git.diff(file.path())) {
                String prefix = line.kind() == GitService.LineKind.ADD ? "+ "
                        : line.kind() == GitService.LineKind.REMOVE ? "- " : "  ";
                int start = span.length();
                span.append(prefix).append(line.text()).append('\n');
                int color = line.kind() == GitService.LineKind.ADD
                        ? Color.parseColor("#C8E6C9")
                        : line.kind() == GitService.LineKind.REMOVE
                        ? Color.parseColor("#FFCDD2") : Color.TRANSPARENT;
                if (color != Color.TRANSPARENT) {
                    span.setSpan(new ForegroundColorSpan(color), start, span.length(),
                            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            }
        } catch (IOException e) {
            span.append(e.getMessage() != null ? e.getMessage() : "");
        }
        return span;
    }

    // ---- Historique --------------------------------------------------------

    private void renderLog() {
        List<GitService.CommitLog> commits = List.of();
        try {
            commits = git.log(3);
        } catch (IOException e) {
            toast(e.getMessage());
        }
        if (commits.isEmpty()) {
            binding.gitLogHeader.setVisibility(View.GONE);
            binding.gitLogCard.setVisibility(View.GONE);
            return;
        }
        binding.gitLogHeader.setVisibility(View.VISIBLE);
        binding.gitLogCard.setVisibility(View.VISIBLE);
        binding.gitLogList.removeAllViews();
        for (int i = 0; i < commits.size(); i++) {
            GitService.CommitLog c = commits.get(i);
            LinearLayout row = new LinearLayout(requireContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            int pad = (int) (11 * getResources().getDisplayMetrics().density);
            row.setPadding(pad, pad, pad, pad);
            if (i > 0) {
                // séparateur : trait fin (studio : border-top)
                View divider = new View(requireContext());
                divider.setLayoutParams(new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, 1));
                binding.gitLogList.addView(divider);
            }
            TextView badge = new TextView(requireContext());
            badge.setText(c.id().substring(0, Math.min(2, c.id().length())));
            badge.setTypeface(android.graphics.Typeface.MONOSPACE);
            badge.setTextSize(10f);
            badge.setGravity(android.view.Gravity.CENTER);
            LinearLayout.LayoutParams badgeParams = new LinearLayout.LayoutParams(
                    (int) (34 * getResources().getDisplayMetrics().density),
                    (int) (34 * getResources().getDisplayMetrics().density));
            badge.setLayoutParams(badgeParams);
            row.addView(badge);
            LinearLayout texts = new LinearLayout(requireContext());
            texts.setOrientation(LinearLayout.VERTICAL);
            TextView message = new TextView(requireContext());
            message.setText(c.message());
            message.setTextAppearance(requireContext(),
                    com.google.android.material.R.style.TextAppearance_Material3_LabelLarge);
            texts.addView(message);
            TextView meta = new TextView(requireContext());
            meta.setText(c.id() + " · " + relative(c.when()) + " · LF");
            meta.setTextAppearance(requireContext(),
                    com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
            texts.addView(meta);
            row.addView(texts, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            binding.gitLogList.addView(row);
        }
    }

    /** Âge relatif court (écran, pas de calendrier). */
    private static String relative(Instant when) {
        long minutes = Duration.between(when, Instant.now()).toMinutes();
        if (minutes < 60) {
            return String.format(Locale.getDefault(), "%d min", Math.max(1, minutes));
        }
        return String.format(Locale.getDefault(), "%d h", minutes / 60);
    }

    // ---- Commit ------------------------------------------------------------

    private void setupCommit() {
        if (status.isEmpty()) {
            binding.gitCommitCard.setVisibility(View.GONE);
            return;
        }
        binding.gitCommitCard.setVisibility(View.VISIBLE);
        binding.gitStageButton.setOnClickListener(v ->
                toast(getString(R.string.git_stage_button, status.size())));
        binding.gitCommitButton.setOnClickListener(v -> {
            String message = binding.gitCommitMessage.getText().toString().trim();
            if (message.isEmpty()) {
                toast(getString(R.string.git_commit_vide));
                return;
            }
            try {
                String id = git.commit(selectedPath != null
                        ? List.of(selectedPath) : List.of(), message);
                toast(getString(R.string.git_commit_ok, id));
                binding.gitCommitMessage.setText("");
                renderStatus();
                renderLog();
            } catch (IOException e) {
                toast(e.getMessage());
            }
        });
    }

    // ---- Clonage (popup ancrée de l'accueil, carte ici) --------------------

    private void setupClone() {
        binding.gitCloneButton.setOnClickListener(v -> {
            String url = binding.gitCloneUrl.getText().toString().trim();
            if (url.isEmpty()) {
                return;
            }
            new Thread(() -> {
                try {
                    java.nio.file.Path target = requireContext().getFilesDir()
                            .toPath().resolve("projects")
                            .resolve(url.replaceAll(".*/", "").replace(".git", ""));
                    GitService.cloneInto(url, target);
                    requireActivity().runOnUiThread(() -> toast(
                            getString(R.string.git_clonage_button) + " : " + target));
                } catch (IOException e) {
                    requireActivity().runOnUiThread(
                            () -> toast(e.getMessage()));
                }
            }, "clone-git").start();
        });
    }

    private void toast(String message) {
        Toast.makeText(requireContext(),
                message != null ? message : "", Toast.LENGTH_SHORT).show();
    }
}
