package jo.joide.app.drawer;

import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.SpannableStringBuilder;
import android.text.TextWatcher;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.StyleSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.snackbar.Snackbar;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.PatternSyntaxException;
import jo.joide.app.R;
import jo.joide.app.databinding.SearchTabFragmentBinding;
import jo.joide.files.FileKinds;
import jo.joide.search.FileFinder;
import jo.joide.search.ProjectSearch;
import jo.joide.search.Replacer;
import jo.joide.workbench.WorkspaceEvents;

/**
 * Onglet Chercher du tiroir gauche (Lot 4 de la refonte) : recherche dans
 * le projet sur le moteur {@link ProjectSearch} — texte littéral protégé ou
 * expression régulière, casse, mot entier, portée (menu ancré), résultats
 * **poussés au fur et à mesure** (compteur en direct, Stop), groupés par
 * fichier et repliables ; appui → le fichier s'ouvre **à l'occurrence,
 * sélectionnée** (contrat {@link WorkbenchHost#openFileAt}). Remplacement
 * avec **aperçu cochable par occurrence**, confirmation aux nombres exacts,
 * annulation par instantané (Snackbar « Annuler »). Mode « Aller au
 * fichier » : correspondance floue instantanée ({@link FileFinder}),
 * ouverts récents d'abord.
 *
 * <p>Tout se passe hors fil UI (génération par tâche : une frappe annule la
 * recherche précédente, 300 ms de délai après la dernière frappe), les
 * états se disent (initial, en cours, résultats, aucun, plafond, regex
 * invalide avec le message du moteur) et les remplacements publient sur le
 * bus {@link WorkspaceEvents} : l'éditeur et l'arbre se rafraîchissent —
 * un seul chemin de mise à jour.</p>
 */
public final class SearchTabFragment extends Fragment {

    private SearchTabFragmentBinding binding;
    private WorkspaceViewModel viewModel;
    private Path root;
    private ResultsAdapter adapter;

    /** Générations : une frappe ou un Stop annule la recherche en vol. */
    private final AtomicInteger generation = new AtomicInteger();

    /** Recherche différée (300 ms après la dernière frappe). */
    private final Handler main = new Handler(Looper.getMainLooper());
    private Runnable pendingSearch;

    // ---- État de recherche (survit aux changements d'onglet) ---------------

    private boolean gotoMode;
    private boolean caseSensitive = true;
    private boolean wholeWord;
    private boolean regexMode;
    private boolean scopeToActive;
    private boolean replaceVisible;
    private boolean searching;
    private boolean capped;
    private String regexError;

    /** Résultats courants (mode Chercher). */
    private List<ProjectSearch.Match> matches = new ArrayList<>();

    /** Candidats courants (mode Aller au fichier). */
    private List<FileFinder.Candidate> candidates = new ArrayList<>();

    /** Groupes de fichiers repliés. */
    private final Set<String> collapsedFiles = new HashSet<>();

    private Runnable unsubscribeEvents;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        binding = SearchTabFragmentBinding.inflate(inflater, container, false);
        viewModel = new ViewModelProvider(requireParentFragment())
                .get(WorkspaceViewModel.class);
        String path = viewModel.projectPath();
        root = path != null ? Paths.get(path) : null;

        setupModes();
        setupField();
        setupToggles();
        setupReplace();

        adapter = new ResultsAdapter();
        binding.searchResults.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.searchResults.setAdapter(adapter);

        subscribeEvents();
        renderToggles();
        renderState();
        return binding.getRoot();
    }

    // ---- Modes Chercher / Aller au fichier -----------------------------------

    private void setupModes() {
        binding.searchModeText.setOnClickListener(v -> setGotoMode(false));
        binding.searchModeFile.setOnClickListener(v -> setGotoMode(true));
    }

    private void setGotoMode(boolean gotoFile) {
        if (gotoMode == gotoFile) {
            return;
        }
        gotoMode = gotoFile;
        cancelSearch();
        renderToggles();
        renderState();
        startSearch();
    }

    private void renderToggles() {
        binding.searchModeText.setBackground(getResources().getDrawable(
                gotoMode ? R.drawable.fond_chip : R.drawable.fond_chip_on));
        binding.searchModeText.setTextColor(getResources().getColor(
                gotoMode ? R.color.sur : R.color.sur_sc2));
        binding.searchModeFile.setBackground(getResources().getDrawable(
                gotoMode ? R.drawable.fond_chip_on : R.drawable.fond_chip));
        binding.searchModeFile.setTextColor(getResources().getColor(
                gotoMode ? R.color.sur_sc2 : R.color.sur));
        // Bascules et remplacement : mode Chercher seulement.
        binding.searchToggles.setVisibility(gotoMode ? View.GONE : View.VISIBLE);
        binding.searchReplaceBox.setVisibility(
                gotoMode || !replaceVisible ? View.GONE : View.VISIBLE);
        binding.searchField.setHint(gotoMode
                ? R.string.tiroir_rech_indice_fichier : R.string.tiroir_rech_indice);
        styleToggle(binding.searchCase, caseSensitive);
        styleToggle(binding.searchWord, wholeWord);
        styleToggle(binding.searchRegex, regexMode);
        binding.searchScope.setText(getString(R.string.tiroir_rech_portee,
                getString(scopeToActive
                        ? R.string.tiroir_rech_portee_active
                        : R.string.tiroir_rech_portee_projet)));
    }

    private void styleToggle(TextView toggle, boolean on) {
        toggle.setBackground(getResources().getDrawable(
                on ? R.drawable.fond_chip_on : R.drawable.fond_chip));
        toggle.setTextColor(getResources().getColor(
                on ? R.color.sur_sc2 : R.color.sur));
    }

    // ---- Champ + recherche différée ------------------------------------------

    private void setupField() {
        binding.searchField.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                scheduleSearch();
            }
        });
        binding.searchStop.setOnClickListener(v -> {
            cancelSearch();
            renderState();
        });
    }

    /** Recherche différée : 300 ms après la dernière frappe. */
    private void scheduleSearch() {
        if (pendingSearch != null) {
            main.removeCallbacks(pendingSearch);
        }
        pendingSearch = this::startSearch;
        main.postDelayed(pendingSearch, 300);
    }

    private void cancelSearch() {
        if (pendingSearch != null) {
            main.removeCallbacks(pendingSearch);
            pendingSearch = null;
        }
        generation.incrementAndGet();
        searching = false;
    }

    private void startSearch() {
        pendingSearch = null;
        String query = binding.searchField.getText().toString();
        if (root == null || query.trim().isEmpty()) {
            searching = false;
            matches = new ArrayList<>();
            candidates = new ArrayList<>();
            regexError = null;
            renderState();
            return;
        }
        generation.incrementAndGet();
        int gen = generation.get();
        searching = true;
        renderState();
        if (gotoMode) {
            searchFiles(query, gen);
        } else {
            searchText(query, gen);
        }
    }

    /** Mode Chercher : ProjectSearch, flux poussé au compteur. */
    private void searchText(String query, int gen) {
        ProjectSearch.Options options = new ProjectSearch.Options(query,
                regexMode, caseSensitive, wholeWord);
        String scope = scopeDir();
        Thread worker = new Thread(() -> {
            List<ProjectSearch.Match> found = new ArrayList<>();
            ProjectSearch.Result result = null;
            String error = null;
            try {
                result = ProjectSearch.search(root, scope, options, null, null,
                        ProjectSearch.DEFAULT_MAX_MATCHES,
                        () -> generation.get() != gen,
                        match -> {
                            found.add(match);
                            if (found.size() % 25 == 0) {
                                int count = found.size();
                                main.post(() -> {
                                    if (binding != null && generation.get() == gen) {
                                        binding.searchCounter.setText(getString(
                                                R.string.tiroir_rech_en_cours, count));
                                    }
                                });
                            }
                        });
            } catch (PatternSyntaxException e) {
                error = e.getMessage();
            } catch (IOException e) {
                error = e.getMessage();
            }
            ProjectSearch.Result finalResult = result;
            String failure = error;
            main.post(() -> {
                if (binding == null || generation.get() != gen) {
                    return;
                }
                searching = false;
                if (failure != null) {
                    regexError = failure;
                    matches = new ArrayList<>();
                } else {
                    regexError = null;
                    matches = found;
                    capped = finalResult != null && finalResult.capped();
                }
                renderState();
            });
        }, "tiroir-search");
        worker.setDaemon(true);
        worker.start();
    }

    /** Mode Aller au fichier : FileFinder, récents d'abord. */
    private void searchFiles(String query, int gen) {
        List<String> recents = new ArrayList<>();
        WorkbenchHost host = host();
        if (host != null) {
            for (Path file : host.recentFiles()) {
                if (file.startsWith(root)) {
                    recents.add(root.relativize(file).toString()
                            .replace(java.io.File.separatorChar, '/'));
                }
            }
        }
        Thread worker = new Thread(() -> {
            List<FileFinder.Candidate> found;
            try {
                found = FileFinder.fuzzy(root, query.trim(), 30, recents,
                        () -> generation.get() != gen);
            } catch (IOException e) {
                found = new ArrayList<>();
            }
            List<FileFinder.Candidate> result = found;
            main.post(() -> {
                if (binding == null || generation.get() != gen) {
                    return;
                }
                searching = false;
                candidates = result;
                renderState();
            });
        }, "tiroir-goto");
        worker.setDaemon(true);
        worker.start();
    }

    /** Portée courante : dossier du fichier actif, ou tout le projet. */
    private String scopeDir() {
        if (scopeToActive) {
            WorkbenchHost host = host();
            Path active = host != null ? host.activeFile() : null;
            if (active != null && active.startsWith(root)
                    && active.getParent() != null) {
                return root.relativize(active.getParent()).toString()
                        .replace(java.io.File.separatorChar, '/');
            }
        }
        return ".";
    }

    // ---- Bascules et portée ---------------------------------------------------

    private void setupToggles() {
        binding.searchCase.setOnClickListener(v -> {
            caseSensitive = !caseSensitive;
            renderToggles();
            scheduleSearch();
        });
        binding.searchWord.setOnClickListener(v -> {
            wholeWord = !wholeWord;
            renderToggles();
            scheduleSearch();
        });
        binding.searchRegex.setOnClickListener(v -> {
            regexMode = !regexMode;
            renderToggles();
            scheduleSearch();
        });
        binding.searchScope.setOnClickListener(v -> showScopeMenu());
    }

    private void showScopeMenu() {
        androidx.appcompat.widget.PopupMenu menu =
                new androidx.appcompat.widget.PopupMenu(requireContext(),
                        binding.searchScope);
        menu.getMenu().add(getString(R.string.tiroir_rech_portee_projet));
        menu.getMenu().add(getString(R.string.tiroir_rech_portee_active));
        menu.setOnMenuItemClickListener(item -> {
            scopeToActive = item.getTitle().equals(
                    getString(R.string.tiroir_rech_portee_active));
            renderToggles();
            startSearch();
            return true;
        });
        menu.show();
    }

    // ---- Remplacement : aperçu, confirmation, annulation ---------------------

    private void setupReplace() {
        // La ligne Remplacer se déplie par un appui long sur la bascule .*
        // (geste discret, documenté à l'écran par l'indice du champ).
        binding.searchRegex.setOnLongClickListener(v -> {
            toggleReplaceRow();
            return true;
        });
        binding.searchReplaceGo.setOnClickListener(v -> previewReplacement());
    }

    private void toggleReplaceRow() {
        replaceVisible = !replaceVisible;
        renderToggles();
    }

    /** Aperçu du remplacement (cochable), puis confirmation aux nombres exacts. */
    private void previewReplacement() {
        if (root == null || matches.isEmpty()) {
            return;
        }
        String query = binding.searchField.getText().toString();
        String replacement = binding.searchReplaceField.getText().toString();
        ProjectSearch.Options options = new ProjectSearch.Options(query,
                regexMode, caseSensitive, wholeWord);
        int gen = generation.get();
        Thread worker = new Thread(() -> {
            List<Replacer.Replacement> preview = null;
            try {
                preview = Replacer.preview(root, scopeDir(), options,
                        replacement, null, null,
                        ProjectSearch.DEFAULT_MAX_MATCHES,
                        () -> generation.get() != gen);
            } catch (IOException | IllegalArgumentException e) {
                preview = null;
            }
            List<Replacer.Replacement> result = preview;
            main.post(() -> {
                if (binding == null) {
                    return;
                }
                if (result == null) {
                    toast(getString(R.string.tiroir_rech_regex_invalide));
                    return;
                }
                showReplacementPreview(result);
            });
        }, "tiroir-replace-preview");
        worker.setDaemon(true);
        worker.start();
    }

    /** Dialogue d'aperçu : chaque occurrence cochable, avant → après. */
    private void showReplacementPreview(List<Replacer.Replacement> preview) {
        Set<Replacer.Replacement> selected = new LinkedHashSet<>(preview);
        LinearLayout list = new LinearLayout(requireContext());
        list.setOrientation(LinearLayout.VERTICAL);
        int padding = dip(16);
        list.setPadding(padding, dip(8), padding, 0);
        for (Replacer.Replacement replacement : preview) {
            list.addView(replacementRow(replacement, selected));
        }
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.tiroir_rech_remplacement_apercu)
                .setView(scrollOf(list))
                .setPositiveButton(R.string.tiroir_rech_tout_remplacer,
                        (dialog, which) -> confirmReplacement(selected))
                .setNegativeButton(R.string.tiroir_action_annuler, null)
                .show();
    }

    /** Une occurrence de l'aperçu : case + avant → après (monospace). */
    private View replacementRow(Replacer.Replacement replacement,
            Set<Replacer.Replacement> selected) {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(0, dip(6), 0, dip(6));
        CheckBox check = new CheckBox(requireContext());
        check.setChecked(selected.contains(replacement));
        check.setOnCheckedChangeListener((button, checked) -> {
            if (checked) {
                selected.add(replacement);
            } else {
                selected.remove(replacement);
            }
        });
        row.addView(check);
        TextView text = new TextView(requireContext());
        SpannableStringBuilder builder = new SpannableStringBuilder();
        builder.append(replacement.before());
        int beforeLength = builder.length();
        builder.append(" → ").append(replacement.after());
        builder.setSpan(new StrikethroughSpan(), 0,
                replacement.before().length(), 0);
        builder.setSpan(new ForegroundColorSpan(
                getResources().getColor(R.color.erreur)), 0,
                replacement.before().length(), 0);
        builder.setSpan(new ForegroundColorSpan(
                getResources().getColor(R.color.ok)), beforeLength + 3,
                builder.length(), 0);
        text.setText(builder);
        text.setTypeface(Typeface.MONOSPACE);
        text.setTextSize(11.5f);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.leftMargin = dip(6);
        row.addView(text, params);
        return row;
    }

    /** Confirmation avec les nombres exacts, puis application + Annuler. */
    private void confirmReplacement(Set<Replacer.Replacement> selected) {
        if (selected.isEmpty()) {
            return;
        }
        Set<String> files = new LinkedHashSet<>();
        for (Replacer.Replacement replacement : selected) {
            files.add(replacement.path());
        }
        new AlertDialog.Builder(requireContext())
                .setMessage(getString(R.string.tiroir_rech_remplacer_confirmer,
                        selected.size(), files.size()))
                .setPositiveButton(R.string.tiroir_rech_remplacer,
                        (dialog, which) -> applyReplacement(new ArrayList<>(selected)))
                .setNegativeButton(R.string.tiroir_action_annuler, null)
                .show();
    }

    private void applyReplacement(List<Replacer.Replacement> selected) {
        List<String> files = pathsOf(selected);
        Thread worker = new Thread(() -> {
            try {
                Replacer.Snapshot snapshot = Replacer.snapshotOf(root, files);
                Replacer.Applied applied = Replacer.apply(root, selected, null);
                main.post(() -> {
                    if (binding == null) {
                        return;
                    }
                    for (String path : files) {
                        viewModel.events().publish(
                                new WorkspaceEvents.FileChangedOnDisk(
                                        root.resolve(path)));
                    }
                    viewModel.events().publish(new WorkspaceEvents.GitStateChanged());
                    Snackbar.make(binding.getRoot(),
                            getString(R.string.tiroir_rech_remplaces,
                                    applied.replacements(), applied.files()),
                            Snackbar.LENGTH_LONG)
                            .setAction(R.string.tiroir_action_annuler,
                                    v -> undoReplacement(snapshot))
                            .show();
                    startSearch();
                });
            } catch (IOException e) {
                main.post(() -> {
                    if (binding != null) {
                        toast(getString(R.string.tiroir_erreur_op,
                                e.getMessage() != null ? e.getMessage() : ""));
                    }
                });
            }
        }, "tiroir-replace");
        worker.setDaemon(true);
        worker.start();
    }

    private static List<String> pathsOf(List<Replacer.Replacement> selected) {
        Set<String> files = new LinkedHashSet<>();
        for (Replacer.Replacement replacement : selected) {
            files.add(replacement.path());
        }
        return new ArrayList<>(files);
    }

    /** « Annuler » : l'instantané rend les fichiers d'origine, atomiquement. */
    private void undoReplacement(Replacer.Snapshot snapshot) {
        Thread worker = new Thread(() -> {
            try {
                Replacer.restore(root, snapshot);
                main.post(() -> {
                    if (binding == null) {
                        return;
                    }
                    for (String path : snapshot.paths()) {
                        viewModel.events().publish(
                                new WorkspaceEvents.FileChangedOnDisk(
                                        root.resolve(path)));
                    }
                    viewModel.events().publish(new WorkspaceEvents.GitStateChanged());
                    startSearch();
                });
            } catch (IOException e) {
                main.post(() -> {
                    if (binding != null) {
                        toast(getString(R.string.tiroir_erreur_op,
                                e.getMessage() != null ? e.getMessage() : ""));
                    }
                });
            }
        }, "tiroir-replace-undo");
        worker.setDaemon(true);
        worker.start();
    }

    // ---- Rendu des états -------------------------------------------------------

    private void renderState() {
        if (binding == null) {
            return;
        }
        String query = binding.searchField.getText().toString();
        boolean empty = query.trim().isEmpty();
        binding.searchStop.setVisibility(searching ? View.VISIBLE : View.GONE);
        if (searching) {
            binding.searchCounter.setVisibility(View.VISIBLE);
            binding.searchCounter.setText(getString(
                    R.string.tiroir_rech_en_cours, 0));
        } else if (!empty && regexError != null) {
            binding.searchCounter.setVisibility(View.GONE);
        } else if (!empty && !gotoMode) {
            binding.searchCounter.setVisibility(View.VISIBLE);
            binding.searchCounter.setText(getString(
                    R.string.tiroir_rech_resultats, matches.size(),
                    countFiles(matches)));
        } else if (!empty) {
            binding.searchCounter.setVisibility(View.VISIBLE);
            binding.searchCounter.setText(getString(
                    R.string.tiroir_goto_resultats, candidates.size()));
        } else {
            binding.searchCounter.setVisibility(View.GONE);
        }
        renderToggles();
        if (adapter != null) {
            adapter.reload();
        }
    }

    private static int countFiles(List<ProjectSearch.Match> matches) {
        Set<String> files = new HashSet<>();
        for (ProjectSearch.Match match : matches) {
            files.add(match.path());
        }
        return files.size();
    }

    // ---- Adaptateur des résultats ----------------------------------------------

    /** Un groupe de fichier (en-tête repliable) ou une correspondance. */
    private record GroupRow(String path, String name, int count, boolean collapsed) {}

    private final class ResultsAdapter extends RecyclerView.Adapter<Holder> {

        private final List<Object> items = new ArrayList<>();

        void reload() {
            List<Object> next = new ArrayList<>();
            if (gotoMode) {
                next.addAll(candidates);
            } else if (regexError != null) {
                next.add(regexError);
            } else if (matches.isEmpty()) {
                next.add(Boolean.TRUE.equals(true) && emptyQuery());
            } else {
                String current = null;
                List<ProjectSearch.Match> group = new ArrayList<>();
                for (ProjectSearch.Match match : matches) {
                    if (current != null && !match.path().equals(current)) {
                        appendGroup(next, current, group);
                        group = new ArrayList<>();
                    }
                    current = match.path();
                    group.add(match);
                }
                if (current != null) {
                    appendGroup(next, current, group);
                }
                if (capped) {
                    next.add(getString(R.string.tiroir_rech_plafond,
                            ProjectSearch.DEFAULT_MAX_MATCHES));
                }
            }
            List<Object> old = new ArrayList<>(items);
            items.clear();
            items.addAll(next);
            DiffUtil.calculateDiff(new DiffUtil.Callback() {
                @Override
                public int getOldListSize() {
                    return old.size();
                }

                @Override
                public int getNewListSize() {
                    return next.size();
                }

                @Override
                public boolean areItemsTheSame(int oldPos, int newPos) {
                    return keyOf(old.get(oldPos)).equals(keyOf(next.get(newPos)));
                }

                @Override
                public boolean areContentsTheSame(int oldPos, int newPos) {
                    return old.get(oldPos).equals(next.get(newPos));
                }
            }).dispatchUpdatesTo(this);
        }

        /** Clé de diff stable par nature de rangée. */
        private String keyOf(Object item) {
            if (item instanceof GroupRow group) {
                return "G:" + group.path();
            }
            if (item instanceof FileFinder.Candidate candidate) {
                return "F:" + candidate.relativePath();
            }
            if (item instanceof ProjectSearch.Match match) {
                return "M:" + match.path() + ":" + match.line() + ":" + match.column();
            }
            if (item instanceof String message) {
                return "S:" + message;
            }
            return "E:" + item; // état vide (initial ou aucun résultat)
        }

        private boolean emptyQuery() {
            return binding != null
                    && binding.searchField.getText().toString().trim().isEmpty();
        }

        private void appendGroup(List<Object> out, String path,
                List<ProjectSearch.Match> group) {
            boolean collapsed = collapsedFiles.contains(path);
            out.add(new GroupRow(path, lastName(path), group.size(), collapsed));
            if (!collapsed) {
                out.addAll(group);
            }
        }

        @Override
        public int getItemViewType(int position) {
            Object item = items.get(position);
            if (item instanceof GroupRow || item instanceof FileFinder.Candidate) {
                return 0;
            }
            if (item instanceof ProjectSearch.Match) {
                return 1;
            }
            return 2;
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LinearLayout view = new LinearLayout(parent.getContext());
            view.setOrientation(LinearLayout.VERTICAL);
            view.setLayoutParams(new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            return new Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            holder.bind(items.get(position));
        }

        @Override
        public int getItemCount() {
            return items.size();
        }
    }

    private final class Holder extends RecyclerView.ViewHolder {
        private final LinearLayout box;

        Holder(@NonNull LinearLayout view) {
            super(view);
            box = view;
        }

        void bind(Object item) {
            box.removeAllViews();
            box.setPadding(0, 0, 0, 0);
            box.setOnClickListener(null);
            if (item instanceof GroupRow group) {
                bindGroup(group);
            } else if (item instanceof FileFinder.Candidate candidate) {
                bindCandidate(candidate);
            } else if (item instanceof ProjectSearch.Match match) {
                bindMatch(match);
            } else if (item instanceof String message) {
                bindMessage(message);
            } else if (item instanceof Boolean initial) {
                bindEmptyState(initial);
            }
        }

        /** En-tête de fichier : icône par type, nom, compte, repli au clic. */
        private void bindGroup(GroupRow group) {
            LinearLayout row = new LinearLayout(box.getContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(0, dip(8), 0, dip(2));
            android.widget.ImageView icon = new android.widget.ImageView(box.getContext());
            icon.setImageResource(FileTreeAdapter.iconRes(
                    FileKinds.kindOf(group.path(), false)));
            row.addView(icon, new LinearLayout.LayoutParams(dip(18), dip(18)));
            TextView name = new TextView(box.getContext());
            name.setText(group.collapsed()
                    ? group.name() + " ▸" : group.name() + " ▾");
            name.setTextSize(12.5f);
            name.setTypeface(Typeface.DEFAULT_BOLD);
            name.setTextColor(getResources().getColor(R.color.sur));
            LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            nameParams.leftMargin = dip(8);
            row.addView(name, nameParams);
            TextView count = new TextView(box.getContext());
            count.setText(getString(R.string.tiroir_rech_occurrences, group.count()));
            count.setTextSize(10.5f);
            count.setTextColor(getResources().getColor(R.color.sur_var));
            row.addView(count);
            box.addView(row);
            box.setOnClickListener(v -> {
                if (collapsedFiles.contains(group.path())) {
                    collapsedFiles.remove(group.path());
                } else {
                    collapsedFiles.add(group.path());
                }
                adapter.reload();
            });
        }

        /** Candidat « aller au fichier » : nom + chemin, ouverture au clic. */
        private void bindCandidate(FileFinder.Candidate candidate) {
            LinearLayout row = new LinearLayout(box.getContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(dip(26), dip(8), 0, dip(8));
            android.widget.ImageView icon = new android.widget.ImageView(box.getContext());
            icon.setImageResource(FileTreeAdapter.iconRes(
                    FileKinds.kindOf(candidate.name(), false)));
            row.addView(icon, new LinearLayout.LayoutParams(dip(18), dip(18)));
            TextView name = new TextView(box.getContext());
            SpannableStringBuilder builder = new SpannableStringBuilder(
                    candidate.name());
            builder.append("  ·  ").append(candidate.relativePath());
            builder.setSpan(new StyleSpan(Typeface.BOLD), 0,
                    candidate.name().length(), 0);
            builder.setSpan(new ForegroundColorSpan(
                    getResources().getColor(R.color.contour)),
                    candidate.name().length(), builder.length(), 0);
            name.setText(builder);
            name.setTextSize(12.5f);
            name.setTextColor(getResources().getColor(R.color.sur));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            params.leftMargin = dip(8);
            row.addView(name, params);
            box.addView(row);
            box.setOnClickListener(v -> {
                WorkbenchHost host = host();
                if (host != null) {
                    host.openFile(root.resolve(candidate.relativePath()));
                }
            });
        }

        /** Une correspondance : numéro de ligne + occurrence en évidence. */
        private void bindMatch(ProjectSearch.Match match) {
            LinearLayout row = new LinearLayout(box.getContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.TOP);
            row.setPadding(dip(26), dip(4), 0, dip(4));
            TextView number = new TextView(box.getContext());
            number.setText(String.valueOf(match.line()));
            number.setTypeface(Typeface.MONOSPACE);
            number.setTextSize(11f);
            number.setTextColor(getResources().getColor(R.color.contour));
            number.setMinWidth(dip(28));
            row.addView(number);
            TextView text = new TextView(box.getContext());
            String stripped = match.lineText().strip();
            int shift = leading(match.lineText());
            SpannableStringBuilder builder = new SpannableStringBuilder(stripped);
            int start = match.start() - shift;
            int end = start + (match.end() - match.start());
            if (start >= 0 && end <= builder.length()) {
                builder.setSpan(new StyleSpan(Typeface.BOLD), start, end, 0);
                builder.setSpan(new BackgroundColorSpan(
                        getResources().getColor(R.color.conteneur_primaire)),
                        start, end, 0);
                builder.setSpan(new ForegroundColorSpan(
                        getResources().getColor(R.color.sur_conteneur_primaire)),
                        start, end, 0);
            }
            text.setText(builder);
            text.setTypeface(Typeface.MONOSPACE);
            text.setTextSize(11f);
            text.setTextColor(getResources().getColor(R.color.sur_var));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            params.leftMargin = dip(6);
            row.addView(text, params);
            box.addView(row);
            box.setOnClickListener(v -> {
                WorkbenchHost host = host();
                if (host != null) {
                    host.openFileAt(root.resolve(match.path()), match.line(),
                            match.column(), match.end() - match.start());
                }
            });
        }

        /** Message d'état (plafond atteint, erreur regex). */
        private void bindMessage(String message) {
            TextView text = new TextView(box.getContext());
            text.setText(message);
            text.setTextSize(11.5f);
            text.setTextColor(getResources().getColor(R.color.sur_var));
            text.setPadding(0, dip(10), 0, dip(10));
            box.addView(text);
        }

        /** État vide honnête : initial (explication) ou aucun résultat. */
        private void bindEmptyState(Boolean initial) {
            TextView title = new TextView(box.getContext());
            title.setText(initial ? R.string.tiroir_rech_initial
                    : R.string.tiroir_rech_aucun);
            title.setTextSize(13f);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            title.setTextColor(getResources().getColor(R.color.sur_var));
            box.addView(title);
            if (initial) {
                TextView hint = new TextView(box.getContext());
                hint.setText(R.string.tiroir_rech_initial_detail);
                hint.setTextSize(12f);
                hint.setTextColor(getResources().getColor(R.color.contour));
                box.addView(hint);
            }
            box.setPadding(0, dip(16), 0, dip(16));
        }
    }

    /** Espaces de tête d'une ligne (recale la sélection sur la ligne nettoyée). */
    private static int leading(String line) {
        int count = 0;
        while (count < line.length() && Character.isWhitespace(line.charAt(count))) {
            count++;
        }
        return count;
    }

    private static String lastName(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    // ---- Bus : le disque bouge → la recherche courante repart ----------------

    private void subscribeEvents() {
        unsubscribeEvents = viewModel.events().subscribe(event ->
                main.post(() -> {
                    if (binding == null) {
                        return;
                    }
                    if (event instanceof WorkspaceEvents.FileChangedOnDisk
                            || event instanceof WorkspaceEvents.FileRenamed) {
                        if (!binding.searchField.getText().toString().trim()
                                .isEmpty() && !searching) {
                            scheduleSearch();
                        }
                    }
                }));
    }

    // ---- Petites aides ---------------------------------------------------------

    private WorkbenchHost host() {
        Fragment parent = requireParentFragment();
        Fragment grandParent = parent != null ? parent.getParentFragment() : null;
        return grandParent instanceof WorkbenchHost host ? host : null;
    }

    private View scrollOf(View content) {
        ScrollView scroll = new ScrollView(requireContext());
        scroll.addView(content);
        return scroll;
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
        cancelSearch();
        if (unsubscribeEvents != null) {
            unsubscribeEvents.run();
            unsubscribeEvents = null;
        }
        binding = null;
        adapter = null;
    }
}
