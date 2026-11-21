package jo.joide.app.drawer;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
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
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import com.google.android.material.snackbar.Snackbar;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import jo.joide.app.R;
import jo.joide.app.databinding.FilesTabFragmentBinding;
import jo.joide.coregit.GitService;
import jo.joide.files.AndroidView;
import jo.joide.files.FileKind;
import jo.joide.files.FileKinds;
import jo.joide.files.FileTemplates;
import jo.joide.files.FileTree;
import jo.joide.files.FsOperations;
import jo.joide.workbench.Diagnostic;
import jo.joide.workbench.WorkspaceEvents;

/**
 * Onglet Fichiers du tiroir gauche (Lots 2-3 de la refonte) : l'arborescence
 * **réelle** du projet sur le moteur {@link FileTree} — listage à la demande,
 * borné (troncature dite), annulable (génération de tâches), hors fil UI —
 * rendue en liste aplatie recyclée ({@link FileTreeAdapter}, DiffUtil).
 *
 * <p>Lot 3 par-dessus le Lot 2 : dossiers de package **compacts**
 * (« com.example.app » sur une ligne), badges d'éléments des dossiers repliés
 * (compte récursif borné), filtre en direct (parcourt l'arbre étendu, jamais
 * le disque entier), menu contextuel (sélectionner, renommer, dupliquer,
 * copier le chemin, déplacer, supprimer), sélection multiple avec barre
 * d'actions, création de fichiers et dossiers (modèles honnêtes), corbeille
 * **restaurable** avec Snackbar « Annuler ». Les opérations passent par
 * {@link FsOperations} (atomiques, confinées, jamais d'écrasement silencieux)
 * et publient sur le bus {@link WorkspaceEvents} : un seul chemin de mise à
 * jour pour l'arbre, l'agent et l'éditeur.</p>
 *
 * <p>Le tiroir ne touche jamais l'éditeur : tout passe par
 * {@link WorkbenchHost}. L'état (dossiers ouverts, listings, chaînes, comptes)
 * vit dans le {@link WorkspaceViewModel} : il survit aux rotations et au
 * va-et-vient vers l'écran Git.</p>
 */
public final class FilesTabFragment extends Fragment implements FileTreeAdapter.Listener {

    private FilesTabFragmentBinding binding;
    private WorkspaceViewModel viewModel;
    private Path root;
    private FileTreeAdapter adapter;

    /** Décorations git par chemin relatif (état réel du dépôt). */
    private Map<String, GitService.ChangeKind> gitStates = Map.of();

    /** Sévérité max par chemin, héritée par les dossiers parents. */
    private Map<String, Diagnostic.Severity> problemStates = Map.of();

    /** Générations : incrémenter annule listings et lectures git en vol. */
    private final AtomicInteger generation = new AtomicInteger();

    /** Dossiers dont le listing est en vol (rangée de chargement affichée). */
    private final Set<String> inFlight = new HashSet<>();

    /** Comptes en vol (badges des dossiers repliés). */
    private final Set<String> countsInFlight = new HashSet<>();

    /** Erreurs de listing par dossier (rangée « Réessayer »). */
    private final Map<String, String> listErrors = new HashMap<>();

    /** Listings demandés pendant le rendu (démarrent après, jamais imbriqués). */
    private final List<String> pendingListings = new ArrayList<>();

    /** Comptes demandés pendant le rendu (idem). */
    private final Set<String> pendingCounts = new LinkedHashSet<>();

    /** « Tout déplier » en cours : chaque niveau visible s'ouvre à mesure. */
    private boolean expandingAll;

    /** Suivre le fichier actif (ouvrir la filiation + défiler vers lui). */
    private boolean followActive = true;

    /** Fichiers cachés montrés (préférence Lot 8, lue au rendu). */
    private boolean showHiddenFiles = true;

    /** Packages compacts (préférence Lot 8). */
    private boolean compactPackages = true;

    /** Badges d'éléments des dossiers repliés (préférence Lot 8). */
    private boolean showBadges = true;

    /** Filtre en direct (vide = arbre normal). */
    private String filterQuery = "";

    /** Champ de filtre affiché. */
    private boolean filterVisible;

    /** Mode sélection multiple (appui long → menu → Sélectionner). */
    private boolean selectionMode;

    /** Chemins sélectionnés (chemins réels d'opération). */
    private final Set<String> selectedPaths = new LinkedHashSet<>();

    /** Dialogue de corbeille ouvert (rafraîchi après une restauration). */
    private AlertDialog trashDialog;

    /** Vue Android active (Lot 3 : regroupement par module). */
    private boolean androidView;

    /** Scan du squelette Android en vol. */
    private final Set<String> androidScanInFlight = new HashSet<>();

    private Runnable unsubscribeEvents;
    private final Handler main = new Handler(Looper.getMainLooper());

    /** Préférences de l'explorateur (Lot 8 : câblées, matrice + CI). */
    public static final String EXPLORER_PREFS = "explorateur";
    public static final String KEY_HIDDEN = "explorateur_fichiers_caches";
    public static final String KEY_COMPACT = "explorateur_packages_compacts";
    public static final String KEY_BADGES = "explorateur_badges";
    public static final String KEY_FOLLOW = "explorateur_suivi_actif";

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
        androidView = viewModel.androidView();
        android.content.SharedPreferences prefs = requireContext()
                .getSharedPreferences(EXPLORER_PREFS, android.content.Context.MODE_PRIVATE);
        showHiddenFiles = prefs.getBoolean(KEY_HIDDEN, true);
        compactPackages = prefs.getBoolean(KEY_COMPACT, true);
        showBadges = prefs.getBoolean(KEY_BADGES, true);
        followActive = prefs.getBoolean(KEY_FOLLOW, true);

        setupTools();
        setupFilter();
        setupSelectionBar();
        setupBottomActions();
        subscribeEvents();

        adapter = new FileTreeAdapter(this, indentPx());
        binding.treeList.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.treeList.setAdapter(adapter);

        // Premier rendu : racine en chargement (le listing démarre au rendu).
        renderTree();
        if (root != null) {
            refreshGitStates();
            refreshProblemStates();
        }
        return binding.getRoot();
    }

    // ---- Outils de l'arbre ---------------------------------------------------

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
            countsInFlight.clear();
            viewModel.resetTreeState();
            listErrors.clear();
            renderTree();
            refreshGitStates();
            refreshProblemStates();
        });
        binding.treeFilter.setOnClickListener(v -> toggleFilter());
        binding.treeSettings.setOnClickListener(v -> showExplorerSettings());
        binding.treeViewProject.setOnClickListener(v -> setAndroidView(false));
        binding.treeViewAndroid.setOnClickListener(v -> setAndroidView(true));
        tintFollow();
    }

    /**
     * Réglages de l'explorateur (Lot 8) : quatre interrupteurs **réels**
     * (préférences lues au rendu — matrice docs/analyse-tiroir.md, contrôlés
     * par tools/check-cablage-tiroir.sh en CI).
     */
    private void showExplorerSettings() {
        android.content.Context context = requireContext();
        android.content.SharedPreferences prefs = context
                .getSharedPreferences(EXPLORER_PREFS, android.content.Context.MODE_PRIVATE);
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        int padding = dip(20);
        box.setPadding(padding, dip(8), padding, 0);
        box.addView(switchRow(context, prefs, R.string.tiroir_reglages_fichiers_caches,
                KEY_HIDDEN, showHiddenFiles, value -> {
                    showHiddenFiles = value;
                    generation.incrementAndGet();
                    inFlight.clear();
                    viewModel.listingCache().clear();
                    renderTree();
                }));
        box.addView(switchRow(context, prefs, R.string.tiroir_reglages_packages,
                KEY_COMPACT, compactPackages, value -> {
                    compactPackages = value;
                    viewModel.chainCache().clear();
                    viewModel.listingCache().clear();
                    renderTree();
                }));
        box.addView(switchRow(context, prefs, R.string.tiroir_reglages_badges,
                KEY_BADGES, showBadges, value -> {
                    showBadges = value;
                    renderTree();
                }));
        box.addView(switchRow(context, prefs, R.string.tiroir_reglages_suivi,
                KEY_FOLLOW, followActive, value -> {
                    followActive = value;
                    renderTree();
                }));
        new AlertDialog.Builder(context)
                .setTitle(R.string.tiroir_reglages)
                .setView(box)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    /** Une rangée d'interrupteur : écrit la préférence et applique l'effet. */
    private View switchRow(android.content.Context context,
            android.content.SharedPreferences prefs, int labelRes, String key,
            boolean current, java.util.function.Consumer<Boolean> onChange) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(0, dip(10), 0, dip(10));
        TextView label = new TextView(context);
        label.setText(labelRes);
        label.setTextSize(13f);
        label.setTextColor(getResources().getColor(R.color.sur));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(label, params);
        androidx.appcompat.widget.SwitchCompat toggle = new androidx.appcompat.widget.SwitchCompat(context);
        toggle.setChecked(current);
        toggle.setOnCheckedChangeListener((button, checked) -> {
            prefs.edit().putBoolean(key, checked).apply();
            onChange.accept(checked);
        });
        row.addView(toggle);
        return row;
    }

    /** ＋ Indexer (preview) : stage Git des fichiers sélectionnés, réel. */
    private void stageSelection() {
        if (root == null || !java.nio.file.Files.isDirectory(root.resolve(".git"))) {
            toast(getString(R.string.git_tiroir_pas_depot));
            return;
        }
        List<String> paths = new ArrayList<>(selectedPaths);
        Thread worker = new Thread(() -> {
            try {
                jo.joide.coregit.GitOps.open(root).stage(paths);
                main.post(() -> {
                    exitSelection();
                    viewModel.events().publish(
                            new WorkspaceEvents.GitStateChanged());
                });
            } catch (IOException e) {
                main.post(() -> toast(e.getMessage()));
            }
        }, "tiroir-stage");
        worker.setDaemon(true);
        worker.start();
    }

    /** Bascule des vues Projet / Android (l'état survit aux rotations). */
    private void setAndroidView(boolean android) {
        if (androidView == android) {
            return;
        }
        androidView = android;
        viewModel.setAndroidView(android);
        tintFollow();
        renderTree();
    }

    /** Le bouton « suivre » est allumé (primaire) quand actif. */
    private void tintFollow() {
        if (binding != null) {
            binding.treeFollow.setColorFilter(getResources().getColor(
                    followActive ? R.color.primaire : R.color.sur_var));
            binding.treeFilter.setColorFilter(getResources().getColor(
                    filterVisible ? R.color.primaire : R.color.sur_var));
            binding.treeViewProject.setBackground(getResources().getDrawable(
                    androidView ? R.drawable.fond_chip : R.drawable.fond_chip_on));
            binding.treeViewProject.setTextColor(getResources().getColor(
                    androidView ? R.color.sur : R.color.sur_sc2));
            binding.treeViewAndroid.setBackground(getResources().getDrawable(
                    androidView ? R.drawable.fond_chip_on : R.drawable.fond_chip));
            binding.treeViewAndroid.setTextColor(getResources().getColor(
                    androidView ? R.color.sur_sc2 : R.color.sur));
        }
    }

    // ---- Filtre en direct (l'arbre étendu, jamais tout le disque) -----------

    private void toggleFilter() {
        filterVisible = !filterVisible;
        binding.treeFilterBox.setVisibility(filterVisible ? View.VISIBLE : View.GONE);
        if (!filterVisible) {
            binding.treeFilterField.setText("");
            filterQuery = "";
        } else {
            binding.treeFilterField.requestFocus();
        }
        tintFollow();
        renderTree();
    }

    private void setupFilter() {
        binding.treeFilterField.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                filterQuery = s.toString().trim();
                renderTree();
            }
        });
    }

    // ---- Barre de sélection multiple ----------------------------------------

    private void setupSelectionBar() {
        binding.treeSelectionTrash.setOnClickListener(v ->
                deletePaths(new ArrayList<>(selectedPaths)));
        binding.treeSelectionCopy.setOnClickListener(v ->
                copyPaths(new ArrayList<>(selectedPaths)));
        binding.treeSelectionMove.setOnClickListener(v ->
                showMoveDialog(new ArrayList<>(selectedPaths)));
        binding.treeSelectionClose.setOnClickListener(v -> exitSelection());
        binding.treeSelectionStage.setOnClickListener(v -> stageSelection());
    }

    private void enterSelection(String first) {
        selectionMode = true;
        selectedPaths.clear();
        selectedPaths.add(first);
        renderTree();
    }

    private void toggleSelected(TreeRow row) {
        if (!selectedPaths.remove(row.opPath())) {
            selectedPaths.add(row.opPath());
        }
        if (selectedPaths.isEmpty()) {
            selectionMode = false;
        }
        renderTree();
    }

    private void exitSelection() {
        selectionMode = false;
        selectedPaths.clear();
        renderTree();
    }

    // ---- Actions basses : Nouveau + Corbeille -------------------------------

    private void setupBottomActions() {
        binding.treeNewFile.setOnClickListener(v -> showNewDialog());
        binding.treeDelete.setOnClickListener(v -> showTrashDialog());
    }

    /** Dialogue de création : nom + nature (fichier avec modèle, ou dossier). */
    private void showNewDialog() {
        if (root == null) {
            return;
        }
        String targetDir = singleSelectedDirectory();
        Context context = requireContext();
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        int padding = dip(20);
        box.setPadding(padding, dip(8), padding, 0);

        TextView where = new TextView(context);
        where.setText(getString(R.string.tiroir_nouveau_dans, displayName(targetDir)));
        where.setTextSize(12f);
        where.setTextColor(getResources().getColor(R.color.sur_var));
        box.addView(where);

        EditText name = new EditText(context);
        name.setHint(R.string.tiroir_nouveau_nom);
        name.setTextSize(14f);
        box.addView(name);

        RadioGroup nature = new RadioGroup(context);
        RadioButton file = new RadioButton(context);
        file.setText(R.string.tiroir_nouveau_fichier_libelle);
        file.setTextSize(14f);
        RadioButton folder = new RadioButton(context);
        folder.setText(R.string.tiroir_nouveau_dossier_libelle);
        folder.setTextSize(14f);
        nature.addView(file);
        nature.addView(folder);
        file.setChecked(true);
        box.addView(nature);

        new AlertDialog.Builder(context)
                .setTitle(R.string.tiroir_nouveau_titre)
                .setView(box)
                .setPositiveButton(R.string.tiroir_creer, (dialog, which) -> {
                    String value = name.getText().toString().trim();
                    if (value.isEmpty() || value.contains("/")) {
                        toast(getString(R.string.tiroir_erreur_op,
                                getString(R.string.tiroir_nouveau_nom)));
                        return;
                    }
                    createEntry(targetDir, value, file.isChecked());
                })
                .setNegativeButton(R.string.tiroir_action_annuler, null)
                .show();
    }

    /** Crée le fichier (modèle de départ) ou le dossier, puis publie. */
    private void createEntry(String targetDir, String name, boolean asFile) {
        String relative = targetDir + "/" + name;
        opAsync(() -> {
            if (asFile) {
                FsOperations.createFile(root, relative,
                        FileTemplates.contentFor(name, packageDirFor(targetDir)));
            } else {
                FsOperations.createDirectory(root, relative);
            }
            return relative;
        }, created -> {
            viewModel.events().publish(
                    new WorkspaceEvents.FileCreated(root.resolve(created)));
            viewModel.events().publish(new WorkspaceEvents.GitStateChanged());
            snackbar(getString(R.string.tiroir_cree, name));
        });
    }

    /** Le dossier cible : l'unique dossier sélectionné, sinon la racine. */
    private String singleSelectedDirectory() {
        if (selectedPaths.size() == 1) {
            String only = selectedPaths.iterator().next();
            if (Files.isDirectory(root.resolve(only))) {
                return only;
            }
        }
        return ".";
    }

    /** Chemin de package pour les modèles (dossier sous une racine java). */
    private String packageDirFor(String dir) {
        FileKind kind = FileKinds.kindOf(dir, true);
        if (kind == FileKind.PACKAGE || kind == FileKind.SRC_ROOT
                || kind == FileKind.TEST_ROOT) {
            return dir;
        }
        return ".";
    }

    // ---- Corbeille restaurable ----------------------------------------------

    /** Dialogue de la corbeille : restauration, suppression, vidage. */
    private void showTrashDialog() {
        if (root == null) {
            return;
        }
        opAsync(() -> FsOperations.listTrash(root), this::renderTrashDialog);
    }

    private void renderTrashDialog(List<FsOperations.TrashEntry> entries) {
        if (binding == null) {
            return;
        }
        Context context = requireContext();
        if (entries.isEmpty()) {
            new AlertDialog.Builder(context)
                    .setTitle(R.string.tiroir_corbeille_titre)
                    .setMessage(R.string.tiroir_corbeille_vide)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }
        LinearLayout list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);
        int padding = dip(16);
        list.setPadding(padding, dip(8), padding, 0);
        for (FsOperations.TrashEntry entry : entries) {
            list.addView(trashRow(context, entry));
        }
        trashDialog = new AlertDialog.Builder(context)
                .setTitle(R.string.tiroir_corbeille_titre)
                .setView(wrapScroll(list))
                .setNeutralButton(R.string.tiroir_corbeille_vider, (dialog, which) ->
                        opAsync(() -> {
                            FsOperations.emptyTrash(root);
                            return null;
                        }, ignored -> {
                            viewModel.events().publish(
                                    new WorkspaceEvents.GitStateChanged());
                            renderTree();
                        }))
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    /** Une entrée de corbeille : nom, méta, Restaurer / Supprimer définitivement. */
    private View trashRow(Context context, FsOperations.TrashEntry entry) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dip(10), 0, dip(10));

        TextView title = new TextView(context);
        title.setText(new java.io.File(entry.originalPath()).getName());
        title.setTextSize(13.5f);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setTextColor(getResources().getColor(R.color.sur));
        row.addView(title);

        TextView meta = new TextView(context);
        meta.setText(entry.originalPath() + " · " + relativeWhen(entry.whenMillis())
                + " · " + humanSize(entry.size()));
        meta.setTextSize(11.5f);
        meta.setTextColor(getResources().getColor(R.color.sur_var));
        row.addView(meta);

        LinearLayout buttons = new LinearLayout(context);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        TextView restore = chipButton(context,
                getString(R.string.tiroir_restaurer_libelle));
        restore.setOnClickListener(v -> opAsync(() -> {
            FsOperations.restore(root, entry);
            return entry;
        }, restored -> {
            viewModel.events().publish(new WorkspaceEvents.FileCreated(
                    root.resolve(restored.originalPath())));
            viewModel.events().publish(new WorkspaceEvents.GitStateChanged());
            snackbar(getString(R.string.tiroir_restaure,
                    new java.io.File(restored.originalPath()).getName()));
            renderTree();
            if (trashDialog != null) {
                trashDialog.dismiss();
                showTrashDialog(); // contenu rafraîchi, jamais d'entrée fantôme.
            }
        }));
        buttons.addView(restore);
        TextView forever = chipButton(context,
                getString(R.string.tiroir_supprimer_definitivement));
        forever.setTextColor(getResources().getColor(R.color.erreur));
        forever.setOnClickListener(v -> confirmDeleteForever(entry));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.leftMargin = dip(8);
        buttons.addView(forever, params);
        row.addView(buttons);
        return row;
    }

    private void confirmDeleteForever(FsOperations.TrashEntry entry) {
        new AlertDialog.Builder(requireContext())
                .setTitle(new java.io.File(entry.originalPath()).getName())
                .setMessage(R.string.tiroir_supprimer_definitivement)
                .setPositiveButton(R.string.tiroir_supprimer_definitivement,
                        (dialog, which) -> opAsync(() -> {
                            FsOperations.deleteForever(root, entry);
                            return null;
                        }, ignored -> renderTree()))
                .setNegativeButton(R.string.tiroir_action_annuler, null)
                .show();
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
        viewModel.chainCache().remove(rel);
        viewModel.countCache().remove(rel);
        viewModel.countCache().remove(parentOf(rel));
        renderTree();
    }

    // ---- Listing paresseux, borné, annulable, hors fil UI -------------------

    /**
     * Lance le listing d'un dossier (génération courante uniquement) : les
     * types Java sont affinés (en-tête borné) et les chaînes compactes des
     * packages sont calculées dans la même tâche. Ne rend RIEN : les
     * appelants rendent — un rendu imbriqué dupliquerait les rangées.
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
            FileTree.Listing listing = null;
            Map<String, FileKind> kinds = new HashMap<>();
            Map<String, FileTree.Chain> chains = new HashMap<>();
            String error = null;
            try {
                listing = FileTree.list(root, relative, showHiddenFiles,
                        FileTree.DEFAULT_MAX_ENTRIES,
                        () -> generation.get() != gen);
                for (FileTree.Entry entry : listing.entries()) {
                    FileKind kind = FileKinds.kindOf(
                            entry.relativePath(), entry.directory());
                    if (kind == FileKind.JAVA_CLASS) {
                        kind = FileKinds.refineJavaKind(kind,
                                root.resolve(entry.relativePath()));
                    }
                    kinds.put(entry.relativePath(), kind);
                    if (compactPackages && entry.directory()
                            && kind == FileKind.PACKAGE) {
                        chains.put(entry.relativePath(), FileTree.compactChain(
                                root, entry.relativePath(), 10,
                                () -> generation.get() != gen));
                    }
                }
            } catch (IOException e) {
                error = e.getMessage() != null ? e.getMessage() : "";
            }
            FileTree.Listing result = listing;
            String failure = error;
            post(gen, () -> {
                inFlight.remove(relative);
                if (result != null) {
                    viewModel.listingCache().put(relative, result);
                    viewModel.kindCache().putAll(kinds);
                    viewModel.chainCache().putAll(chains);
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

    /** Compte les entrées d'un dossier replié (badge), hors fil UI. */
    private void countAsync(String relative) {
        if (root == null || binding == null) {
            return;
        }
        if (!countsInFlight.add(relative)) {
            return;
        }
        int gen = generation.get();
        Thread worker = new Thread(() -> {
            FileTree.Count count = null;
            try {
                count = FileTree.countAll(root, relative, 2000,
                        () -> generation.get() != gen);
            } catch (IOException e) {
                count = null; // pas de badge menteur : rien.
            }
            FileTree.Count result = count;
            post(gen, () -> {
                countsInFlight.remove(relative);
                if (result != null) {
                    viewModel.countCache().put(relative, result);
                }
                renderTree();
            });
        }, "tiroir-count");
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

    private void renderTree() {
        if (binding == null || adapter == null) {
            return;
        }
        pendingListings.clear();
        pendingCounts.clear();
        String activeRel = activeRelative();
        List<Object> rows = new ArrayList<>();
        if (androidView) {
            appendAndroidView(rows, activeRel);
        } else {
            appendDir(rows, ".", 0, activeRel);
        }
        List<Object> shown = applyFilter(rows);
        adapter.submit(shown, filterQuery);
        renderFilterCount(shown);
        renderSelectionBar();
        for (String relative : new ArrayList<>(pendingListings)) {
            listAsync(relative);
        }
        for (String relative : new ArrayList<>(pendingCounts)) {
            countAsync(relative);
        }
        if (expandingAll) {
            expandOneMoreLevel(rows);
        } else if (followActive && activeRel != null && filterQuery.isEmpty()) {
            revealActive(shown, activeRel);
        }
    }

    /** Ajoute les rangées d'un dossier et de ses enfants ouverts. */
    private void appendDir(List<Object> rows, String relative, int depth,
            String activeRel) {
        if (inFlight.contains(relative)) {
            rows.add(new TreeStateRow(TreeState.LOADING, relative,
                    displayName(relative), depth));
            return;
        }
        String failure = listErrors.get(relative);
        if (failure != null) {
            rows.add(new TreeStateRow(TreeState.ERROR, relative, failure, depth));
            return;
        }
        FileTree.Listing listing = viewModel.listingCache().get(relative);
        if (listing == null) {
            rows.add(new TreeStateRow(TreeState.LOADING, relative,
                    displayName(relative), depth));
            if (!inFlight.contains(relative)) {
                pendingListings.add(relative);
            }
            return;
        }
        if (listing.entries().isEmpty()) {
            rows.add(new TreeStateRow(
                    ".".equals(relative) ? TreeState.EMPTY_PROJECT : TreeState.EMPTY_DIR,
                    relative, null, depth));
            return;
        }
        for (FileTree.Entry entry : listing.entries()) {
            boolean dir = entry.directory();
            String childRel = entry.relativePath();
            FileKind kind = viewModel.kindCache().getOrDefault(childRel,
                    FileKinds.kindOf(childRel, dir));
            // Chaîne compacte : « com.example » sur une ligne (Lot 3).
            FileTree.Chain chain = viewModel.chainCache().get(childRel);
            boolean compact = compactPackages && dir && chain != null
                    && chain.levels() > 1;
            String listRel = compact ? chain.lastRelative() : childRel;
            String display = compact ? chain.display() : entry.name();
            boolean open = dir && viewModel.expandedDirs().contains(listRel);
            if (dir && kind == FileKind.FOLDER && open) {
                kind = FileKind.FOLDER_OPEN;
            }
            String badge = showBadges && dir && !open && filterQuery.isEmpty()
                    ? badgeFor(listRel) : null;
            rows.add(new TreeRow(listRel, childRel, display, dir, depth, kind,
                    open, gitStates.get(childRel),
                    problemStates.get(childRel),
                    childRel.equals(activeRel),
                    selectedPaths.contains(childRel), selectionMode, badge));
            if (open) {
                appendDir(rows, listRel, depth + 1, activeRel);
            }
        }
        if (listing.truncated()) {
            rows.add(new TreeStateRow(TreeState.TRUNCATED, relative,
                    String.valueOf(FileTree.DEFAULT_MAX_ENTRIES), depth + 1));
        }
    }

    // ---- Vue Android (regroupement par module, adossée aux fichiers réels) --

    /** Squelette de la vue Android : scan en vol → rangée de chargement. */
    private void appendAndroidView(List<Object> rows, String activeRel) {
        List<AndroidView.Node> nodes = viewModel.androidNodes();
        if (nodes == null) {
            rows.add(new TreeStateRow(TreeState.LOADING, ".",
                    getString(R.string.tiroir_vue_android_scan), 0));
            if (androidScanInFlight.isEmpty()) {
                scanAndroidAsync();
            }
            return;
        }
        if (nodes.isEmpty()) {
            rows.add(new TreeStateRow(TreeState.ANDROID_EMPTY, ".", null, 0));
            return;
        }
        for (AndroidView.Node node : nodes) {
            appendAndroidNode(rows, node, 0, activeRel);
        }
    }

    /**
     * Un nœud du squelette : groupe virtuel ou chemin réel. Les nœuds avec
     * enfants (groupes, modules) s'ouvrent sur leur squelette ; les dossiers
     * réels sans enfants (java, res) s'ouvrent par le pipeline de listing
     * normal — packages compacts et décorations compris.
     */
    private void appendAndroidNode(List<Object> rows, AndroidView.Node node,
            int depth, String activeRel) {
        boolean group = node.group();
        String key = group || node.relativePath() == null
                ? node.id() : node.relativePath();
        boolean open = viewModel.expandedDirs().contains(key);
        boolean directory = group || node.directory();
        String badge = !group && directory && !open && filterQuery.isEmpty()
                ? badgeFor(key) : null;
        rows.add(new TreeRow(key, key, androidLabel(node), directory, depth,
                node.kind(), open,
                group || node.relativePath() == null
                        ? null : gitStates.get(node.relativePath()),
                node.relativePath() == null
                        ? null : problemStates.get(node.relativePath()),
                node.relativePath() != null
                        && node.relativePath().equals(activeRel),
                selectedPaths.contains(key), selectionMode, badge));
        if (!open) {
            return;
        }
        if (!node.children().isEmpty()) {
            for (AndroidView.Node child : node.children()) {
                appendAndroidNode(rows, child, depth + 1, activeRel);
            }
        } else if (directory && node.relativePath() != null) {
            appendDir(rows, node.relativePath(), depth + 1, activeRel);
        }
    }

    /** Libellé d'un nœud : clé neutre traduite en ressource, jamais en dur. */
    private String androidLabel(AndroidView.Node node) {
        return switch (node.display()) {
            case AndroidView.LABEL_SCRIPTS -> getString(R.string.tiroir_groupe_scripts);
            case AndroidView.LABEL_MANIFESTS -> getString(R.string.tiroir_groupe_manifestes);
            case AndroidView.LABEL_JAVA -> getString(R.string.tiroir_groupe_java);
            case AndroidView.LABEL_JAVA_TEST -> getString(R.string.tiroir_groupe_java_test);
            case AndroidView.LABEL_RES -> getString(R.string.tiroir_groupe_res);
            default -> node.qualifier() == null ? node.display()
                    : getString(R.string.tiroir_module_suffixe,
                            node.display(), node.qualifier());
        };
    }

    /** Scanne le squelette Android (modules réels), hors fil UI, annulable. */
    private void scanAndroidAsync() {
        if (root == null || binding == null) {
            return;
        }
        if (!androidScanInFlight.add("scan")) {
            return;
        }
        int gen = generation.get();
        Thread worker = new Thread(() -> {
            List<AndroidView.Node> nodes = null;
            try {
                nodes = AndroidView.build(root, 20,
                        () -> generation.get() != gen);
            } catch (IOException e) {
                nodes = null; // squelette indisponible : l'état se dira.
            }
            List<AndroidView.Node> result = nodes;
            post(gen, () -> {
                androidScanInFlight.clear();
                if (result != null) {
                    viewModel.setAndroidNodes(result);
                }
                renderTree();
            });
        }, "tiroir-android-view");
        worker.setDaemon(true);
        worker.start();
    }

    /** Badge d'éléments d'un dossier replié (compte connu, sinon demandé). */
    private String badgeFor(String listRel) {
        FileTree.Count count = viewModel.countCache().get(listRel);
        if (count != null) {
            return count.capped()
                    ? getString(R.string.tiroir_badge_plus, count.entries())
                    : String.valueOf(count.entries());
        }
        if (!countsInFlight.contains(listRel)) {
            pendingCounts.add(listRel);
        }
        return null;
    }

    /** Filtre en direct : ne garde que les noms correspondants de l'arbre étendu. */
    private List<Object> applyFilter(List<Object> rows) {
        if (filterQuery.isEmpty()) {
            return rows;
        }
        String query = filterQuery.toLowerCase(Locale.ROOT);
        List<Object> out = new ArrayList<>();
        for (Object item : rows) {
            if (item instanceof TreeRow row
                    && row.display().toLowerCase(Locale.ROOT).contains(query)) {
                out.add(row);
            }
        }
        if (out.isEmpty()) {
            out.add(new TreeStateRow(TreeState.FILTER_EMPTY, ".", null, 0));
        }
        return out;
    }

    private void renderFilterCount(List<Object> shown) {
        if (filterQuery.isEmpty()) {
            binding.treeFilterCount.setVisibility(View.GONE);
            return;
        }
        int matches = 0;
        for (Object item : shown) {
            if (item instanceof TreeRow) {
                matches++;
            }
        }
        binding.treeFilterCount.setVisibility(View.VISIBLE);
        binding.treeFilterCount.setText(getString(
                R.string.tiroir_filtre_resultats, matches));
    }

    private void renderSelectionBar() {
        binding.treeSelectionBar.setVisibility(
                selectionMode ? View.VISIBLE : View.GONE);
        if (selectionMode) {
            binding.treeSelectionCount.setText(getString(
                    R.string.tiroir_selection_count, selectedPaths.size()));
        }
    }

    /** « Tout déplier » : ouvre le niveau visible suivant, jusqu'au bout. */
    private void expandOneMoreLevel(List<Object> rows) {
        boolean opened = false;
        for (Object item : rows) {
            if (item instanceof TreeRow row && row.directory()
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
    private void revealActive(List<Object> shown, String activeRel) {
        boolean changed = false;
        // Vue Android : le module racine est un nœud virtuel à ouvrir aussi.
        if (androidView && viewModel.expandedDirs().add("module:.")) {
            changed = true;
        }
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
        for (int i = 0; i < shown.size(); i++) {
            if (shown.get(i) instanceof TreeRow row && row.active()) {
                index = i;
                break;
            }
        }
        if (index < 0) {
            return; // pas encore visible : les listings en vol l'amèneront.
        }
        LinearLayoutManager layout =
                (LinearLayoutManager) binding.treeList.getLayoutManager();
        int first = layout.findFirstVisibleItemPosition();
        int last = layout.findLastVisibleItemPosition();
        if (index < first || index > last) {
            layout.scrollToPositionWithOffset(index,
                    Math.max(0, binding.treeList.getHeight() / 3));
        }
    }

    // ---- Contrat FileTreeAdapter.Listener -----------------------------------

    @Override
    public void onRowClick(TreeRow row, View clicked) {
        if (selectionMode) {
            if (!isVirtualKey(row.opPath())) {
                toggleSelected(row);
            }
            return;
        }
        if (row.directory()) {
            boolean opening = !viewModel.expandedDirs().contains(row.relativePath());
            animateChevron(clicked, opening);
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

    @Override
    public void onRowLongClick(TreeRow row, View clicked) {
        if (selectionMode) {
            toggleSelected(row);
            return;
        }
        if (isVirtualKey(row.opPath())) {
            return; // groupes et modules virtuels : pas d'opération dessus.
        }
        showContextMenu(row, clicked);
    }

    /** Clé d'un nœud virtuel de la vue Android (groupe ou module racine). */
    private static boolean isVirtualKey(String key) {
        return key.startsWith("group:") || key.startsWith("module:");
    }

    @Override
    public void onRetry(String relative) {
        listErrors.remove(relative);
        listAsync(relative);
        renderTree();
    }

    private void animateChevron(View rowView, boolean opening) {
        View chevron = rowView.findViewById(R.id.tree_chevron);
        if (chevron != null) {
            chevron.animate().rotation(opening ? 0f : -90f).setDuration(150).start();
        }
    }

    // ---- Menu contextuel -----------------------------------------------------

    private void showContextMenu(TreeRow row, View anchor) {
        androidx.appcompat.widget.PopupMenu menu =
                new androidx.appcompat.widget.PopupMenu(requireContext(), anchor);
        menu.inflate(R.menu.file_tree_context);
        menu.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            List<String> paths = List.of(row.opPath());
            if (id == R.id.action_select) {
                enterSelection(row.opPath());
            } else if (id == R.id.action_rename) {
                showRenameDialog(row);
            } else if (id == R.id.action_duplicate) {
                duplicate(row);
            } else if (id == R.id.action_copy_path) {
                copyPaths(paths);
            } else if (id == R.id.action_move) {
                showMoveDialog(paths);
            } else if (id == R.id.action_delete) {
                deletePaths(paths);
            } else if (id == R.id.action_gitignore) {
                ignoreEntry(row);
            } else if (id == R.id.action_git_history) {
                openGitTab();
            } else {
                return false;
            }
            return true;
        });
        menu.show();
    }

    /** ∓ Ajouter à .gitignore (preview) : le nom du fichier, motif réel. */
    private void ignoreEntry(TreeRow row) {
        if (root == null) {
            return;
        }
        String pattern = java.nio.file.Paths.get(row.opPath())
                .getFileName().toString();
        if (pattern == null || pattern.isEmpty()) {
            return;
        }
        if (!java.nio.file.Files.isDirectory(root.resolve(".git"))) {
            toast(getString(R.string.git_tiroir_pas_depot));
            return;
        }
        Thread worker = new Thread(() -> {
            try {
                jo.joide.coregit.GitOps.open(root).ignore(pattern);
                main.post(() -> {
                    viewModel.events().publish(
                            new WorkspaceEvents.GitStateChanged());
                });
            } catch (IOException e) {
                main.post(() -> toast(e.getMessage()));
            }
        }, "tiroir-gitignore");
        worker.setDaemon(true);
        worker.start();
    }

    /** ⌥ Git : Historique (preview) : ouvre l'onglet Git du tiroir. */
    private void openGitTab() {
        Fragment parent = requireParentFragment();
        if (parent instanceof LeftDrawerFragment drawer) {
            drawer.openTab(R.id.drawer_tab_git);
        }
    }

    /** Renommage : dialogue prérempli, mouvement atomique, jamais d'écrasement. */
    private void showRenameDialog(TreeRow row) {
        if (root == null) {
            return;
        }
        Context context = requireContext();
        String currentName = new java.io.File(row.opPath()).getName();
        EditText input = new EditText(context);
        input.setText(currentName);
        input.setTextSize(14f);
        int padding = dip(20);
        input.setPadding(padding, dip(8), padding, 0);
        new AlertDialog.Builder(context)
                .setTitle(getString(R.string.tiroir_renommer_titre, currentName))
                .setView(input)
                .setPositiveButton(R.string.tiroir_renommer_ok, (dialog, which) -> {
                    String value = input.getText().toString().trim();
                    if (value.isEmpty() || value.contains("/") || value.equals(currentName)) {
                        return;
                    }
                    String from = row.opPath();
                    String to = parentOf(from) + "/" + value;
                    opAsync(() -> {
                        FsOperations.move(root, from, to);
                        return to;
                    }, renamed -> {
                        viewModel.events().publish(new WorkspaceEvents.FileRenamed(
                                root.resolve(from), root.resolve(renamed)));
                        viewModel.events().publish(new WorkspaceEvents.GitStateChanged());
                        snackbar(getString(R.string.tiroir_renomme, value));
                    });
                })
                .setNegativeButton(R.string.tiroir_action_annuler, null)
                .show();
    }

    /** Duplication : « Copie de X », puis « Copie de X (2) »… si conflit. */
    private void duplicate(TreeRow row) {
        if (root == null) {
            return;
        }
        opAsync(() -> {
            String name = new java.io.File(row.opPath()).getName();
            String base = getString(R.string.tiroir_dupliquer_prefixe, name);
            String parent = parentOf(row.opPath());
            String target = parent + "/" + base;
            int suffix = 2;
            while (Files.exists(root.resolve(target))) {
                target = parent + "/" + base + " (" + suffix++ + ")";
            }
            FsOperations.copy(root, row.opPath(), target);
            return target;
        }, created -> {
            viewModel.events().publish(
                    new WorkspaceEvents.FileCreated(root.resolve(created)));
            viewModel.events().publish(new WorkspaceEvents.GitStateChanged());
        });
    }

    /** Copie de chemin(s) relatif(s) au presse-papiers. */
    private void copyPaths(List<String> paths) {
        if (paths.isEmpty()) {
            return;
        }
        StringBuilder builder = new StringBuilder();
        for (String path : paths) {
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append(path);
        }
        ClipboardManager clipboard = (ClipboardManager) requireContext()
                .getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText(
                getString(R.string.tiroir_menu_copier_chemin), builder));
        if (paths.size() == 1) {
            toast(R.string.tiroir_chemin_copie);
        } else {
            toast(getString(R.string.tiroir_chemins_copies, paths.size()));
        }
    }

    /** Déplacement : destination saisie (chemin relatif), conflits suffixés. */
    private void showMoveDialog(List<String> paths) {
        if (root == null || paths.isEmpty()) {
            return;
        }
        Context context = requireContext();
        EditText input = new EditText(context);
        input.setText(parentOf(paths.get(0)));
        input.setTextSize(14f);
        int padding = dip(20);
        input.setPadding(padding, dip(8), padding, 0);
        new AlertDialog.Builder(context)
                .setTitle(R.string.tiroir_deplacer_titre)
                .setView(input)
                .setPositiveButton(R.string.tiroir_deplacer_ok, (dialog, which) -> {
                    String destination = input.getText().toString().trim();
                    movePaths(paths, destination);
                })
                .setNegativeButton(R.string.tiroir_action_annuler, null)
                .show();
    }

    private void movePaths(List<String> paths, String destination) {
        opAsync(() -> {
            Path dir = FileTree.resolve(root, destination);
            if (!Files.isDirectory(dir)) {
                throw new IOException(
                        getString(R.string.tiroir_deplacer_introuvable, destination));
            }
            int moved = 0;
            List<String[]> renames = new ArrayList<>();
            for (String path : paths) {
                String dest = destination.equals(".") ? "" : destination;
                if (dest.equals(path) || dest.startsWith(path + "/")) {
                    continue; // déplacer dans soi-même : ignoré honnêtement.
                }
                String name = new java.io.File(path).getName();
                String target = (destination.equals(".") ? name
                        : destination + "/" + name);
                int suffix = 2;
                while (Files.exists(root.resolve(target))) {
                    target = (destination.equals(".") ? name
                            : destination + "/" + name) + " (" + suffix++ + ")";
                }
                FsOperations.move(root, path, target);
                renames.add(new String[] {path, target});
                moved++;
            }
            for (String[] rename : renames) {
                viewModel.events().publish(new WorkspaceEvents.FileRenamed(
                        root.resolve(rename[0]), root.resolve(rename[1])));
            }
            return moved;
        }, moved -> {
            viewModel.events().publish(new WorkspaceEvents.GitStateChanged());
            exitSelection();
            snackbar(getString(R.string.tiroir_deplaces, moved));
        });
    }

    /** Suppression vers la corbeille, avec Snackbar « Annuler » (restauration). */
    private void deletePaths(List<String> paths) {
        if (root == null || paths.isEmpty()) {
            return;
        }
        opAsync(() -> {
            // Les chemins profonds d'abord : un parent corbeillé absorbe l'enfant.
            List<String> ordered = new ArrayList<>(paths);
            ordered.sort((a, b) -> Integer.compare(b.length(), a.length()));
            List<FsOperations.TrashEntry> trashed = new ArrayList<>();
            for (String path : ordered) {
                trashed.add(FsOperations.toTrash(root, path));
            }
            return trashed;
        }, trashed -> {
            exitSelection();
            for (FsOperations.TrashEntry entry : trashed) {
                viewModel.events().publish(new WorkspaceEvents.FileDeleted(
                        root.resolve(entry.originalPath())));
            }
            viewModel.events().publish(new WorkspaceEvents.GitStateChanged());
            String text = trashed.size() == 1
                    ? getString(R.string.tiroir_supprime,
                            new java.io.File(trashed.get(0).originalPath()).getName())
                    : getString(R.string.tiroir_supprimes, trashed.size());
            Snackbar.make(binding.getRoot(), text, Snackbar.LENGTH_LONG)
                    .setAction(R.string.tiroir_action_annuler,
                            v -> undoTrash(trashed))
                    .show();
        });
    }

    /** « Annuler » d'une suppression : restaure tout, dans l'ordre inverse. */
    private void undoTrash(List<FsOperations.TrashEntry> trashed) {
        opAsync(() -> {
            for (int i = trashed.size() - 1; i >= 0; i--) {
                FsOperations.restore(root, trashed.get(i));
            }
            return trashed;
        }, restored -> {
            for (FsOperations.TrashEntry entry : restored) {
                viewModel.events().publish(new WorkspaceEvents.FileCreated(
                        root.resolve(entry.originalPath())));
            }
            viewModel.events().publish(new WorkspaceEvents.GitStateChanged());
        });
    }

    // ---- Opérations en arrière-plan (erreurs honnêtes, jamais muettes) ------

    /** Une opération d'E/S exécutée hors fil UI. */
    private interface IoCall<T> {
        T run() throws IOException;
    }

    /** Exécute l'opération hors fil UI ; succès → consommation sur le fil UI. */
    private <T> void opAsync(IoCall<T> operation, Consumer<T> onSuccess) {
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
                    return;
                }
                onSuccess.accept(result);
            });
        }, "tiroir-op");
        worker.setDaemon(true);
        worker.start();
    }

    // ---- Petites aides -------------------------------------------------------

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

    /** « il y a 12 min / 3 h / 2 j » — honnête, borné au jour. */
    private String relativeWhen(long whenMillis) {
        long minutes = Math.max(0, (System.currentTimeMillis() - whenMillis)
                / 60000L);
        if (minutes < 60) {
            return getString(R.string.tiroir_quand_minutes, minutes);
        }
        if (minutes < 60 * 24) {
            return getString(R.string.tiroir_quand_heures, minutes / 60);
        }
        return getString(R.string.tiroir_quand_jours, minutes / (60 * 24));
    }

    /** Taille lisible : o / Ko / Mo (base 1024, arrondie). */
    private String humanSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " o";
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.FRANCE, "%.1f Ko", bytes / 1024.0);
        }
        return String.format(Locale.FRANCE, "%.1f Mo", bytes / (1024.0 * 1024.0));
    }

    /** Enveloppe défilante pour le contenu d'un dialogue. */
    private View wrapScroll(View content) {
        ScrollView scroll = new ScrollView(requireContext());
        scroll.addView(content);
        return scroll;
    }

    /** Bouton-chip textuel (32dp du studio). */
    private TextView chipButton(Context context, String label) {
        TextView button = new TextView(context);
        button.setText(label);
        button.setTextSize(12f);
        button.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        button.setTextColor(getResources().getColor(R.color.sur));
        button.setBackground(getResources().getDrawable(R.drawable.fond_chip));
        button.setPadding(dip(15), 0, dip(15), 0);
        button.setMinHeight(dip(32));
        button.setGravity(android.view.Gravity.CENTER);
        return button;
    }

    private void snackbar(String text) {
        if (binding != null) {
            Snackbar.make(binding.getRoot(), text, Snackbar.LENGTH_SHORT).show();
        }
    }

    private int indentPx() {
        return getResources().getDimensionPixelSize(R.dimen.tree_indent);
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
        generation.incrementAndGet(); // annule listings, comptes et git en vol.
        if (unsubscribeEvents != null) {
            unsubscribeEvents.run();
            unsubscribeEvents = null;
        }
        binding = null;
        adapter = null;
    }
}
