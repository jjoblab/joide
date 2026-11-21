package jo.joide.app.drawer;

import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import jo.joide.app.R;
import jo.joide.app.databinding.FilesTabFragmentBinding;
import jo.joide.coregit.GitService;
import jo.joide.files.FileKind;
import jo.joide.files.FileKinds;
import jo.joide.files.FileTree;
import jo.joide.workbench.Diagnostic;
import jo.joide.workbench.WorkspaceEvents;

/**
 * Onglet Fichiers du tiroir gauche (Lot 2 de la refonte) : l'arborescence
 * **réelle** du projet sur le moteur {@link FileTree} du module core-files —
 * listage à la demande (jamais tout l'arbre), **borné** (troncature dite
 * honnêtement), **annulable** (génération de tâches), **hors fil UI**.
 *
 * <p>Chaque rangée porte : guides d'indentation ({@link IndentGuidesView},
 * celui du dossier du fichier actif est mis en évidence), chevron (dossiers,
 * animé au clic), icône par type ({@link FileKinds} → drawables originaux,
 * l'affinage Java lit un en-tête borné en tâche de fond), nom, décorations
 * **git** (point coloré + nom : état réel du dépôt) et **diagnostics**
 * (pastille erreur/avertissement, héritée par les dossiers parents), fichier
 * actif en gras sur sc_highest. Les états se disent toujours : chargement
 * (spinner par dossier), erreur avec Réessayer, dossier vide, projet vide,
 * listing tronqué. Les secrets ne s'ouvrent jamais en contenu.</p>
 *
 * <p>La navigation passe par le contrat {@link WorkbenchHost} : le tiroir ne
 * touche jamais l'éditeur. L'état (dossiers ouverts, listings) vit dans le
 * {@link WorkspaceViewModel} : il survit aux rotations et au va-et-vient
 * vers l'écran Git. Le bus {@link WorkspaceEvents} rafraîchit l'arbre
 * (mutations de l'agent, build, git) — un seul chemin de mise à jour.</p>
 */
public final class FilesTabFragment extends Fragment {

    private FilesTabFragmentBinding binding;
    private WorkspaceViewModel viewModel;
    private Path root;

    /** Décorations git par chemin relatif (état réel du dépôt, Lot 2). */
    private Map<String, GitService.ChangeKind> gitStates = Map.of();

    /** Sévérité max par chemin, héritée par les dossiers parents (Lot 2). */
    private Map<String, Diagnostic.Severity> problemStates = Map.of();

    /** Générations : incrémenter annule listings et lectures git en vol. */
    private final AtomicInteger generation = new AtomicInteger();

    /** Dossiers dont le listing est en vol (rangée de chargement affichée). */
    private final Set<String> inFlight = new HashSet<>();

    /** Erreurs de listing par dossier (rangée « Réessayer »). */
    private final Map<String, String> listErrors = new HashMap<>();

    /** Listings demandés pendant le rendu (démarrent après, jamais imbriqués). */
    private final List<String> pendingListings = new ArrayList<>();

    /** « Tout déplier » en cours : chaque niveau visible s'ouvre à mesure. */
    private boolean expandingAll;

    /** Suivre le fichier actif (ouvrir la filiation + défiler vers lui). */
    private boolean followActive = true;

    private Runnable unsubscribeEvents;
    private final Handler main = new Handler(Looper.getMainLooper());

    /** Onglet du tiroir (le projet vient du ViewModel partagé). */
    public static FilesTabFragment newInstance() {
        return new FilesTabFragment();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        binding = FilesTabFragmentBinding.inflate(inflater, container, false);
        viewModel = new ViewModelProvider(requireParentFragment())
                .get(WorkspaceViewModel.class);
        String path = viewModel.projectPath();
        root = path != null ? Paths.get(path) : null;

        setupTools();
        setupBottomActions();
        subscribeEvents();

        // Premier rendu : racine en chargement (le listing démarre au rendu),
        // décorations demandées.
        renderTree();
        if (root != null) {
            refreshGitStates();
            refreshProblemStates();
        }
        return binding.getRoot();
    }

    // ---- Outils de l'arbre (studio : replier, déplier, suivre, actualiser) --

    private void setupTools() {
        binding.treeCollapseAll.setOnClickListener(v -> {
            expandingAll = false;
            viewModel.expandedDirs().clear();
            renderTree();
        });
        binding.treeExpandAll.setOnClickListener(v -> {
            expandingAll = true;
            renderTree();
        });
        binding.treeFollow.setOnClickListener(v -> {
            followActive = !followActive;
            tintFollow();
            if (followActive) {
                renderTree();
            }
        });
        binding.treeRefresh.setOnClickListener(v -> {
            generation.incrementAndGet();
            inFlight.clear();
            viewModel.listingCache().clear();
            viewModel.kindCache().clear();
            listErrors.clear();
            renderTree(); // redemande la racine (listings démarrent au rendu)
            refreshGitStates();
            refreshProblemStates();
        });
        tintFollow();
    }

    /** Le bouton « suivre » est allumé (primaire) quand actif. */
    private void tintFollow() {
        if (binding != null) {
            binding.treeFollow.setColorFilter(getResources().getColor(
                    followActive ? R.color.primaire : R.color.sur_var));
        }
    }

    /** Actions basses : honnêtes tant que le Lot 3 ne les livre pas. */
    private void setupBottomActions() {
        if (binding == null) {
            return;
        }
        binding.treeNewFile.setOnClickListener(v -> toast(R.string.tiroir_actions_lot_suivant));
        binding.treeDelete.setOnClickListener(v -> toast(R.string.tiroir_actions_lot_suivant));
    }

    // ---- Bus de l'espace de travail (un seul chemin de mise à jour) --------

    private void subscribeEvents() {
        unsubscribeEvents = viewModel.events().subscribe(event -> main.post(() -> {
            if (binding == null) {
                return;
            }
            if (event instanceof WorkspaceEvents.GitStateChanged) {
                refreshGitStates();
            } else if (event instanceof WorkspaceEvents.DiagnosticsChanged) {
                refreshProblemStates();
            } else if (event instanceof WorkspaceEvents.FileChangedOnDisk e) {
                invalidate(e.file());
            } else if (event instanceof WorkspaceEvents.FileCreated e) {
                invalidate(e.file());
            } else if (event instanceof WorkspaceEvents.FileDeleted e) {
                invalidate(e.file());
            } else if (event instanceof WorkspaceEvents.FileRenamed e) {
                invalidate(e.from());
                invalidate(e.to());
            }
        }));
    }

    /** Un fichier a changé : le listing de son dossier repart (si visible). */
    private void invalidate(Path file) {
        if (root == null || file == null || !file.startsWith(root)) {
            return;
        }
        String rel = relativeOf(file);
        viewModel.listingCache().remove(parentOf(rel));
        viewModel.kindCache().remove(rel);
        // Le rendu redemandera le listing si le dossier est visible.
        renderTree();
    }

    // ---- Listing paresseux, borné, annulable, hors fil UI ------------------

    /**
     * Lance le listing d'un dossier (génération courante uniquement). Ne
     * rend RIEN : les appelants rendent — un rendu imbriqué dupliquerait
     * les rangées.
     */
    private void listAsync(String relative) {
        if (root == null || binding == null) {
            return;
        }
        if (!inFlight.add(relative)) {
            return; // déjà en vol
        }
        int gen = generation.get();
        Thread worker = new Thread(() -> {
            FileTree.Listing listing;
            Map<String, FileKind> kinds = new HashMap<>();
            String error = null;
            try {
                listing = FileTree.list(root, relative, true,
                        FileTree.DEFAULT_MAX_ENTRIES,
                        () -> generation.get() != gen);
                for (FileTree.Entry entry : listing.entries()) {
                    FileKind kind = FileKinds.kindOf(
                            entry.relativePath(), entry.directory());
                    if (kind == FileKind.JAVA_CLASS) {
                        // Affinage Java : en-tête borné (8 Kio), jamais tout.
                        kind = FileKinds.refineJavaKind(kind,
                                root.resolve(entry.relativePath()));
                    }
                    kinds.put(entry.relativePath(), kind);
                }
            } catch (IOException e) {
                listing = null;
                error = e.getMessage() != null ? e.getMessage() : "";
            }
            FileTree.Listing result = listing;
            String failure = error;
            post(gen, () -> {
                inFlight.remove(relative);
                if (result != null) {
                    viewModel.listingCache().put(relative, result);
                    viewModel.kindCache().putAll(kinds);
                    listErrors.remove(relative);
                } else {
                    listErrors.put(relative, failure);
                }
                renderTree();
            });
        }, "tiroir-listing");
        worker.setDaemon(true);
        worker.start();
    }

    /** Publie sur le fil UI si la génération est toujours courante. */
    private void post(int gen, Runnable action) {
        main.post(() -> {
            if (binding != null && generation.get() == gen) {
                action.run();
            }
        });
    }

    // ---- Décorations git (état réel du dépôt, hors fil UI) -----------------

    private void refreshGitStates() {
        if (root == null || binding == null) {
            return;
        }
        int gen = generation.get();
        Thread worker = new Thread(() -> {
            Map<String, GitService.ChangeKind> states = new HashMap<>();
            try {
                for (GitService.FileStatus status : new GitService(root).status()) {
                    states.put(status.path(), status.kind());
                }
            } catch (IOException e) {
                // Pas un dépôt (ou état illisible) : aucune décoration, honnête.
            }
            post(gen, () -> {
                gitStates = states;
                renderTree();
            });
        }, "tiroir-git");
        worker.setDaemon(true);
        worker.start();
    }

    // ---- Décorations de diagnostics (héritées par les parents) -------------

    private void refreshProblemStates() {
        WorkbenchHost host = host();
        if (host == null || binding == null) {
            return;
        }
        Map<String, Diagnostic.Severity> byFile = new HashMap<>();
        for (Diagnostic diagnostic : host.diagnostics()) {
            if (diagnostic.file() == null) {
                continue;
            }
            byFile.merge(diagnostic.file(), diagnostic.severity(),
                    FilesTabFragment::stronger);
        }
        Map<String, Diagnostic.Severity> inherited = new HashMap<>(byFile);
        for (Map.Entry<String, Diagnostic.Severity> entry : byFile.entrySet()) {
            String path = entry.getKey();
            int slash = path.lastIndexOf('/');
            while (slash > 0) {
                inherited.merge(path.substring(0, slash), entry.getValue(),
                        FilesTabFragment::stronger);
                slash = path.lastIndexOf('/', slash - 1);
            }
        }
        problemStates = inherited;
        renderTree();
    }

    /** La sévérité la plus forte des deux. */
    private static Diagnostic.Severity stronger(Diagnostic.Severity a,
            Diagnostic.Severity b) {
        if (a == Diagnostic.Severity.ERROR || b == Diagnostic.Severity.ERROR) {
            return Diagnostic.Severity.ERROR;
        }
        if (a == Diagnostic.Severity.WARNING || b == Diagnostic.Severity.WARNING) {
            return Diagnostic.Severity.WARNING;
        }
        return Diagnostic.Severity.INFO;
    }

    // ---- Rendu de l'arbre (fil UI, depuis les caches) -----------------------

    /** Une rangée d'entrée (dossier ou fichier) avec ses décorations. */
    private record Row(String relativePath, String name, boolean directory,
            int depth, FileKind kind, GitService.ChangeKind git,
            Diagnostic.Severity problem, boolean active) {}

    /** Nature d'un état inséré dans l'arbre (jamais d'état muet). */
    private enum State { LOADING, ERROR, EMPTY_DIR, EMPTY_PROJECT, TRUNCATED }

    /** Une rangée d'état (chargement, erreur, vide, troncature). */
    private record StateRow(State state, String relativePath, String message,
            int depth) {}

    private void renderTree() {
        if (binding == null) {
            return;
        }
        LinearLayout tree = binding.filesTree;
        tree.removeAllViews();
        pendingListings.clear();
        String activeRel = activeRelative();
        List<Object> rows = new ArrayList<>();
        appendDir(rows, ".", 0, activeRel);
        for (Object item : rows) {
            tree.addView(item instanceof Row row
                    ? entryRowView(row) : stateRowView((StateRow) item));
        }
        // Les listings demandés pendant le rendu démarrent maintenant :
        // aucun rendu imbriqué (ils dupliqueraient les rangées).
        for (String relative : new ArrayList<>(pendingListings)) {
            listAsync(relative);
        }
        if (expandingAll) {
            expandOneMoreLevel(rows);
        }
        if (followActive && activeRel != null) {
            revealActive(rows, activeRel);
        }
    }

    /** Ajoute les rangées d'un dossier et, récursivement, de ses enfants ouverts. */
    private void appendDir(List<Object> rows, String relative, int depth,
            String activeRel) {
        if (inFlight.contains(relative)) {
            rows.add(new StateRow(State.LOADING, relative, relative, depth));
            return;
        }
        String failure = listErrors.get(relative);
        if (failure != null) {
            rows.add(new StateRow(State.ERROR, relative, failure, depth));
            return;
        }
        FileTree.Listing listing = viewModel.listingCache().get(relative);
        if (listing == null) {
            // Visible mais pas encore listé : on le demande (une seule fois),
            // après le rendu — jamais imbriqué.
            rows.add(new StateRow(State.LOADING, relative, relative, depth));
            if (!inFlight.contains(relative)) {
                pendingListings.add(relative);
            }
            return;
        }
        if (listing.entries().isEmpty()) {
            rows.add(new StateRow(
                    ".".equals(relative) ? State.EMPTY_PROJECT : State.EMPTY_DIR,
                    relative, null, depth));
            return;
        }
        for (FileTree.Entry entry : listing.entries()) {
            boolean dir = entry.directory();
            boolean open = dir && viewModel.expandedDirs()
                    .contains(entry.relativePath());
            FileKind kind = viewModel.kindCache().getOrDefault(
                    entry.relativePath(),
                    FileKinds.kindOf(entry.relativePath(), dir));
            if (dir && kind == FileKind.FOLDER && open) {
                kind = FileKind.FOLDER_OPEN;
            }
            rows.add(new Row(entry.relativePath(), entry.name(), dir, depth,
                    kind, gitStates.get(entry.relativePath()),
                    problemStates.get(entry.relativePath()),
                    entry.relativePath().equals(activeRel)));
            if (open) {
                appendDir(rows, entry.relativePath(), depth + 1, activeRel);
            }
        }
        if (listing.truncated()) {
            rows.add(new StateRow(State.TRUNCATED, relative,
                    String.valueOf(FileTree.DEFAULT_MAX_ENTRIES), depth + 1));
        }
    }

    /** « Tout déplier » : ouvre le niveau visible suivant, jusqu'au bout. */
    private void expandOneMoreLevel(List<Object> rows) {
        boolean opened = false;
        for (Object item : rows) {
            if (item instanceof Row row && row.directory()
                    && !viewModel.expandedDirs().contains(row.relativePath())) {
                viewModel.expandedDirs().add(row.relativePath());
                opened = true;
            }
        }
        if (opened) {
            renderTree();
        } else if (inFlight.isEmpty()) {
            expandingAll = false; // plus rien à ouvrir : terminé.
        }
    }

    /** Ouvre la filiation du fichier actif puis défile vers sa rangée. */
    private void revealActive(List<Object> rows, String activeRel) {
        boolean changed = false;
        int slash = activeRel.lastIndexOf('/');
        while (slash > 0) {
            if (viewModel.expandedDirs().add(activeRel.substring(0, slash))) {
                changed = true;
            }
            slash = activeRel.lastIndexOf('/', slash - 1);
        }
        if (changed) {
            renderTree();
            return;
        }
        int index = -1;
        int height = rowHeight();
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i) instanceof Row row && row.active()) {
                index = i;
                break;
            }
        }
        if (index < 0 || binding == null) {
            return; // pas encore visible : les listings en vol l'amèneront.
        }
        ScrollView scroll = binding.treeScroll;
        int target = index * height;
        int top = scroll.getScrollY();
        int bottom = top + scroll.getHeight();
        if (target < top || target + height > bottom) {
            scroll.post(() -> {
                if (binding != null && followActive) {
                    binding.treeScroll.smoothScrollTo(0, Math.max(0, target));
                }
            });
        }
    }

    // ---- Vues de rangées ----------------------------------------------------

    /** Rangée d'entrée : guides, chevron, icône, nom, décorations. */
    private View entryRowView(Row row) {
        LinearLayout view = (LinearLayout) LayoutInflater.from(requireContext())
                .inflate(R.layout.file_tree_row, binding.filesTree, false);
        IndentGuidesView guides = view.findViewById(R.id.tree_guides);
        guides.setDepth(row.depth());
        guides.setHighlightedLevel(row.active() ? row.depth() - 1 : -1);

        ImageView chevron = view.findViewById(R.id.tree_chevron);
        boolean open = row.directory() && viewModel.expandedDirs()
                .contains(row.relativePath());
        if (row.directory()) {
            chevron.setVisibility(View.VISIBLE);
            chevron.setRotation(open ? 0f : -90f);
        } else {
            chevron.setVisibility(View.INVISIBLE);
        }

        ImageView icon = view.findViewById(R.id.tree_icon);
        icon.setImageResource(iconRes(row.kind()));

        TextView name = view.findViewById(R.id.tree_name);
        name.setText(row.name());
        name.setTypeface(row.active() ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        name.setTextColor(nameColor(row.git()));

        view.setActivated(row.active());
        bindDecorations(view, row);
        view.setOnClickListener(v -> onEntryClick(row, v));
        return view;
    }

    private void bindDecorations(LinearLayout view, Row row) {
        ImageView gitDot = view.findViewById(R.id.tree_git_dot);
        if (row.git() != null && gitDotRes(row.git()) != 0) {
            gitDot.setVisibility(View.VISIBLE);
            gitDot.setImageResource(gitDotRes(row.git()));
        } else {
            gitDot.setVisibility(View.GONE);
        }
        ImageView problemDot = view.findViewById(R.id.tree_problem_dot);
        if (row.problem() == Diagnostic.Severity.ERROR) {
            problemDot.setVisibility(View.VISIBLE);
            problemDot.setImageResource(R.drawable.pastille_diagnostic_erreur);
        } else if (row.problem() == Diagnostic.Severity.WARNING) {
            problemDot.setVisibility(View.VISIBLE);
            problemDot.setImageResource(R.drawable.pastille_diagnostic_avert);
        } else {
            problemDot.setVisibility(View.GONE);
        }
    }

    /** Clic : dossier → ouverture animée ; fichier → ouverture (jamais secret). */
    private void onEntryClick(Row row, View clicked) {
        if (row.directory()) {
            boolean opening = !viewModel.expandedDirs()
                    .contains(row.relativePath());
            ImageView chevron = clicked.findViewById(R.id.tree_chevron);
            if (chevron != null) {
                chevron.animate().rotation(opening ? 0f : -90f)
                        .setDuration(150).start();
            }
            if (opening) {
                viewModel.expandedDirs().add(row.relativePath());
                listErrors.remove(row.relativePath());
            } else {
                viewModel.expandedDirs().remove(row.relativePath());
            }
            main.postDelayed(this::renderTree, 150);
            return;
        }
        if (row.kind() == FileKind.SECRET) {
            toast(R.string.tiroir_fichier_secret);
            return;
        }
        WorkbenchHost host = host();
        if (host != null) {
            host.openFile(root.resolve(row.relativePath()));
        }
    }

    /** Rangée d'état : chargement, erreur (+ Réessayer), vide, tronqué. */
    private View stateRowView(StateRow state) {
        LinearLayout context = new LinearLayout(requireContext());
        context.setOrientation(LinearLayout.VERTICAL);
        int indent = dip(6) + state.depth() * indentPx() + dip(22);
        switch (state.state()) {
            case LOADING -> {
                LinearLayout line = new LinearLayout(requireContext());
                line.setOrientation(LinearLayout.HORIZONTAL);
                line.setGravity(android.view.Gravity.CENTER_VERTICAL);
                ProgressBar spinner = new ProgressBar(requireContext());
                line.addView(spinner, new LinearLayout.LayoutParams(
                        dip(16), dip(16)));
                TextView reading = new TextView(requireContext());
                reading.setText(getString(R.string.tiroir_lecture,
                        displayName(state.relativePath())));
                reading.setTextSize(12f);
                reading.setTextColor(getResources().getColor(R.color.sur_var));
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                params.leftMargin = dip(8);
                line.addView(reading, params);
                context.addView(line);
            }
            case ERROR -> {
                TextView message = new TextView(requireContext());
                message.setText(getString(R.string.tiroir_erreur_lecture,
                        state.message()));
                message.setTextSize(12f);
                message.setTextColor(getResources().getColor(R.color.sur_var));
                context.addView(message);
                TextView retry = retryButton(state.relativePath());
                context.addView(retry);
            }
            case EMPTY_DIR, EMPTY_PROJECT -> {
                TextView title = new TextView(requireContext());
                title.setText(state.state() == State.EMPTY_PROJECT
                        ? R.string.tiroir_projet_vide : R.string.tiroir_dossier_vide);
                title.setTextSize(13f);
                title.setTypeface(Typeface.DEFAULT_BOLD);
                title.setTextColor(getResources().getColor(R.color.sur_var));
                context.addView(title);
                TextView hint = new TextView(requireContext());
                hint.setText(state.state() == State.EMPTY_PROJECT
                        ? R.string.tiroir_projet_vide_conseil
                        : R.string.tiroir_dossier_vide_conseil);
                hint.setTextSize(12f);
                hint.setTextColor(getResources().getColor(R.color.contour));
                context.addView(hint);
            }
            case TRUNCATED -> {
                TextView truncated = new TextView(requireContext());
                truncated.setText(getString(R.string.tiroir_listing_tronque,
                        Integer.parseInt(state.message())));
                truncated.setTextSize(11.5f);
                truncated.setTextColor(getResources().getColor(R.color.contour));
                context.addView(truncated);
            }
        }
        context.setPadding(indent, dip(8), dip(6), dip(8));
        return context;
    }

    /** Bouton « Réessayer » (chip 32dp du studio). */
    private TextView retryButton(String relative) {
        TextView button = new TextView(requireContext());
        button.setText(R.string.tiroir_reessayer);
        button.setTextSize(12f);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setTextColor(getResources().getColor(R.color.sur));
        button.setBackground(getResources().getDrawable(R.drawable.fond_chip));
        button.setPadding(dip(15), 0, dip(15), 0);
        button.setMinHeight(dip(32));
        button.setGravity(android.view.Gravity.CENTER);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dip(32));
        params.topMargin = dip(6);
        button.setLayoutParams(params);
        button.setOnClickListener(v -> {
            listErrors.remove(relative);
            listAsync(relative);
            renderTree(); // rangée de chargement immédiate
        });
        return button;
    }

    // ---- Petites aides ------------------------------------------------------

    /** L'hôte du tiroir est l'atelier (grand-parent ; contrat WorkbenchHost). */
    @Nullable
    private WorkbenchHost host() {
        Fragment parent = requireParentFragment();
        Fragment grandParent = parent != null ? parent.getParentFragment() : null;
        return grandParent instanceof WorkbenchHost host ? host : null;
    }

    /** Chemin relatif (séparateurs /) d'un fichier absolu du projet. */
    private String relativeOf(Path file) {
        return root.relativize(file).toString()
                .replace(java.io.File.separatorChar, '/');
    }

    /** Dossier parent d'un chemin relatif (« . » à la racine). */
    private static String parentOf(String relative) {
        int slash = relative.lastIndexOf('/');
        return slash < 0 ? "." : relative.substring(0, slash);
    }

    /** Fichier actif en chemin relatif, ou null (hors projet / aucun). */
    @Nullable
    private String activeRelative() {
        WorkbenchHost host = host();
        if (host == null || root == null) {
            return null;
        }
        Path active = host.activeFile();
        if (active == null || !active.startsWith(root)) {
            return null;
        }
        String relative = relativeOf(active);
        return relative.isEmpty() ? null : relative;
    }

    /** Nom affichable d'un dossier relatif (la racine porte le nom du projet). */
    private String displayName(String relative) {
        if (".".equals(relative) || relative.isEmpty()) {
            return root != null && root.getFileName() != null
                    ? root.getFileName().toString() : relative;
        }
        int slash = relative.lastIndexOf('/');
        return slash < 0 ? relative : relative.substring(slash + 1);
    }

    /** Drawable d'icône par type (aucune extension codée en dur côté UI). */
    private static int iconRes(FileKind kind) {
        switch (kind) {
            case FOLDER: return R.drawable.ic_tree_folder;
            case FOLDER_OPEN: return R.drawable.ic_tree_folder_open;
            case SRC_ROOT: return R.drawable.ic_tree_src;
            case TEST_ROOT: return R.drawable.ic_tree_test;
            case PACKAGE: return R.drawable.ic_tree_package;
            case JAVA_CLASS: return R.drawable.ic_tree_java_class;
            case JAVA_ABSTRACT: return R.drawable.ic_tree_java_abstract;
            case JAVA_INTERFACE: return R.drawable.ic_tree_java_interface;
            case JAVA_ENUM: return R.drawable.ic_tree_java_enum;
            case JAVA_ANNOTATION: return R.drawable.ic_tree_java_annotation;
            case KOTLIN: return R.drawable.ic_tree_kotlin;
            case MANIFEST: return R.drawable.ic_tree_manifest;
            case RES_LAYOUT: return R.drawable.ic_tree_res_layout;
            case RES_VALUES: return R.drawable.ic_tree_res_values;
            case RES_DRAWABLE: return R.drawable.ic_tree_res_drawable;
            case XML: return R.drawable.ic_tree_xml;
            case GRADLE: return R.drawable.ic_tree_gradle;
            case GITIGNORE: return R.drawable.ic_tree_gitignore;
            case TEXT: return R.drawable.ic_tree_text;
            case CONFIG: return R.drawable.ic_tree_config;
            case IMAGE: return R.drawable.ic_tree_image;
            case ARCHIVE: return R.drawable.ic_tree_archive;
            case SECRET: return R.drawable.ic_tree_secret;
            default: return R.drawable.ic_tree_unknown;
        }
    }

    /** Point de décoration git (0 = aucune : état absent ou supprimé). */
    private static int gitDotRes(GitService.ChangeKind kind) {
        switch (kind) {
            case MODIFIED: return R.drawable.dot_git_modifie;
            case ADDED: return R.drawable.dot_git_ajoute;
            case UNTRACKED: return R.drawable.dot_git_non_suivi;
            default: return 0;
        }
    }

    /** Couleur du nom selon l'état git (maquette : modifié bleu, ajouté ok…). */
    private int nameColor(@Nullable GitService.ChangeKind kind) {
        if (kind == null) {
            return getResources().getColor(R.color.sur);
        }
        switch (kind) {
            case MODIFIED: return getResources().getColor(R.color.git_modifie);
            case ADDED: return getResources().getColor(R.color.ok);
            case UNTRACKED: return getResources().getColor(R.color.info);
            default: return getResources().getColor(R.color.sur);
        }
    }

    private int rowHeight() {
        return getResources().getDimensionPixelSize(R.dimen.tree_row_hauteur);
    }

    private int indentPx() {
        return getResources().getDimensionPixelSize(R.dimen.tree_indent);
    }

    private void toast(int resId) {
        Toast.makeText(requireContext(), resId, Toast.LENGTH_SHORT).show();
    }

    private int dip(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        generation.incrementAndGet(); // annule listings et git en vol.
        if (unsubscribeEvents != null) {
            unsubscribeEvents.run();
            unsubscribeEvents = null;
        }
        binding = null;
    }
}
