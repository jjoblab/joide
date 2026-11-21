package jo.joide.app.git;

import android.graphics.Color;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spannable;
import android.text.style.ForegroundColorSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
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
        binding.gitSyncText.setText(getString(R.string.git_sync_hint, "12 min"));
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
        GitService.FileStatus first = status.get(0);
        binding.gitChangedFile.setText(fileTitle(first));
        binding.gitDiffView.setText(coloredDiff(first));
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
            TextView row = new TextView(requireContext());
            row.setTextAppearance(requireContext(),
                    com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
            row.setText(logLine(c));
            row.setPadding(0, i == 0 ? 0 : 16, 0, 16);
            binding.gitLogList.addView(row);
        }
    }

    private String logLine(GitService.CommitLog c) {
        return c.id() + " · " + c.author() + " · "
                + relative(Instant.now().minusSeconds(3600)) + "\n" + c.message();
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
                String id = git.commit(List.of(), message);
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
