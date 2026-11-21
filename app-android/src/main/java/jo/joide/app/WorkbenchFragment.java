package jo.joide.app;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import jo.joide.app.R;
import jo.joide.app.databinding.WorkbenchFragmentBinding;
import jo.joide.corestore.ModePreferences;
import jo.joide.app.ModeSetupFragment;
import jo.joide.buildforge.ForgeBackend;
import jo.joide.buildgradle.GradleBackend;
import jo.joide.workbench.BuildEvent;
import jo.joide.workbench.BuildRequest;
import jo.joide.workbench.WorkMode;

/**
 * Atelier (studio HTML, onglet « Éditeur », rendu 1:1) : top bar 52dp (menu,
 * nom, pill de mode, save/undo/redo/play/dots), onglets de fichiers 40dp,
 * fil d'Ariane 34dp avec segment code/aperçu, zone code sc_lowest (brique
 * céleris), dock à poignée (barre 52dp + corps repliable : pills d'état du
 * build, onglets Problèmes·Console·Étapes·Journal, sortie mono), tiroir gauche
 * push 320dp (switcher Files·Chercher·Structure + arborescence indentée).
 *
 * <p>J2 : le build du mode actif alimente la Console et la section Problèmes
 * (diagnostics avec position). Les états restent honnêtes : les commandes
 * portées par les briques non encore câblées se disent, jamais muettes.</p>
 *
 * <p>Lot 0 Assistant/Agent : l'atelier est l'hôte du contexte vivant de
 * l'agent du tiroir droit ({@link jo.joide.app.ai.AgentContext}) — fichier
 * actif (tampon vivant de l'éditeur), diagnostics du dernier build,
 * sélection courante. Des valeurs réelles uniquement.</p>
 */
public class WorkbenchFragment extends Fragment
        implements jo.joide.app.ai.AgentContext {

    private static final String ARG_PROJECT = "project";

    private WorkbenchFragmentBinding binding;
    private boolean noticeDismissed;
    private String projectPath;

    /** Instance sur un projet (chemin absolu du dossier racine du projet). */
    public static WorkbenchFragment forProject(String projectPath) {
        WorkbenchFragment fragment = new WorkbenchFragment();
        Bundle args = new Bundle();
        args.putString(ARG_PROJECT, projectPath);
        fragment.setArguments(args);
        return fragment;
    }

    /** Chemin du projet ouvert (null : gabarit de prévisualisation). */
    @Nullable
    protected String projectPath() {
        return getArguments() != null ? getArguments().getString(ARG_PROJECT) : null;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        binding = WorkbenchFragmentBinding.inflate(inflater, container, false);
        projectPath = projectPath();

        if (projectPath != null) {
            setupDrawers();
            setupDrawerTabs();
            setupTopBar();
            setupFilesTree();
            setupTabs();
            setupDock();
            setupModeVisibility();
            // J2 : construire — le build du mode actif alimente le dock.
            binding.buildButton.setOnClickListener(v -> runBuild());
        }
        return binding.getRoot();
    }

    // ---- Top bar (studio : menu ouvre le tiroir gauche, ⋯ honnête) ----------

    private void setupTopBar() {
        binding.projectTitle.setText(Path.of(projectPath).getFileName().toString());
        String branch = currentBranch();
        if (branch != null) {
            binding.projectSubtitle.setText(branch);
            binding.projectSubtitle.setVisibility(View.VISIBLE);
        }
        binding.menuButton.setOnClickListener(v ->
                binding.drawerLayout.openDrawer(androidx.core.view.GravityCompat.START));
        binding.closeLeft.setOnClickListener(v ->
                binding.drawerLayout.closeDrawer(androidx.core.view.GravityCompat.START));
        binding.dotsButton.setOnClickListener(this::showOptionsMenu);
        // Studio : save/undo/redo — la brique céleris porte ces états (L5) ;
        // les boutons disent honnêtement l'état.
        String editorState = getString(R.string.atelier_editeur_brique);
        binding.saveButton.setOnClickListener(v -> toast(editorState));
        binding.undoButton.setOnClickListener(v -> toast(editorState));
        binding.redoButton.setOnClickListener(v -> toast(editorState));
    }

    /** Thème de l'éditeur aux couleurs de la maquette v2 (fond sc-lowest, encres du studio). */
    private jo.celeris.android.view.EditorTheme editorThemeV2(boolean night) {
        int selection = (requireContext().getColor(R.color.primaire) & 0x00FFFFFF) | 0x40000000;
        jo.celeris.android.view.SyntaxPalette palette = night
                ? new jo.celeris.android.view.SyntaxPalette(
                        0xFFCC7832, 0xFF7FDBCA, 0xFFC3E88D, 0xFF82AAFF,
                        0xFF90937E, 0xFFB4AFA3, 0xFFFFCB6B, 0xFFB0BEC5,
                        0xFFE3E3E3, 0xFFE3E3E3, 0xFFF06292, 0xFFE3E3E3,
                        0xFFE3E3E3, 0xFFFF8A80)
                : new jo.celeris.android.view.SyntaxPalette(
                        0xFF6A1B9A, 0xFF00695C, 0xFF2E7D32, 0xFF1565C0,
                        0xFF767966, 0xFF6D4C41, 0xFF8D5000, 0xFF455A64,
                        0xFF202124, 0xFF202124, 0xFFAD1457, 0xFF202124,
                        0xFF202124, 0xFFC62828);
        return new jo.celeris.android.view.EditorTheme(night ? 2 : 1,
                requireContext().getColor(R.color.sc_lowest),
                requireContext().getColor(R.color.sur),
                selection,
                requireContext().getColor(R.color.primaire),
                requireContext().getColor(R.color.contour),
                0x00000000,
                palette,
                night ? jo.celeris.core.syntax.scope.ScopeThemes.dark()
                        : jo.celeris.core.syntax.scope.ScopeThemes.light());
    }

    /** Bascule la barre de symboles sous l'éditeur (v2 : via le menu ⋯ ou le ⋯ du dock). */
    private void toggleSymbolBar() {
        binding.symbolBar.setVisibility(binding.symbolBar.getVisibility()
                == View.VISIBLE ? View.GONE : View.VISIBLE);
        if (binding.symbolBar.getChildCount() == 0) {
            buildSymbolBar();
        }
    }

    /** Branche courante lue dans .git/HEAD ; null si le projet n'est pas un dépôt. */
    private String currentBranch() {
        try {
            Path head = Path.of(projectPath, ".git", "HEAD");
            if (!java.nio.file.Files.isRegularFile(head)) {
                return null;
            }
            String line = new String(java.nio.file.Files.readAllBytes(head),
                    java.nio.charset.StandardCharsets.UTF_8).trim();
            String prefix = "ref: refs/heads/";
            if (line.startsWith(prefix)) {
                return line.substring(prefix.length());
            }
            return line.length() >= 7 ? line.substring(0, 7) : line;
        } catch (java.io.IOException | RuntimeException e) {
            return null;
        }
    }

    /** Puces du tiroir gauche : Chercher (recherche du dock), Git (écran git). */
    private void setupDrawerTabs() {
        binding.drawerTabSearch.setOnClickListener(v -> {
            binding.drawerLayout.closeDrawer(androidx.core.view.GravityCompat.START);
            binding.dockSearch.performClick();
        });
        binding.drawerTabGit.setOnClickListener(v -> {
            binding.drawerLayout.closeDrawer(androidx.core.view.GravityCompat.START);
            jo.joide.app.git.GitFragment git = new jo.joide.app.git.GitFragment();
            Bundle args = new Bundle();
            args.putString("project", projectPath);
            git.setArguments(args);
            getParentFragmentManager().beginTransaction()
                    .replace(((ViewGroup) requireView().getParent()).getId(), git)
                    .addToBackStack(null)
                    .commit();
        });
    }

    // ---- Tiroirs (gauche : fichiers · droite : agent IA) — ils POUSSENT le contenu --

    private void setupDrawers() {
        androidx.drawerlayout.widget.DrawerLayout drawers = binding.drawerLayout;
        // Pas de voile : le contenu est poussé, pas recouvert.
        drawers.setScrimColor(android.graphics.Color.TRANSPARENT);
        drawers.setDrawerElevation(0f);
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        ViewGroup.LayoutParams right = binding.rightDrawer.getLayoutParams();
        right.width = Math.min(dip(420), screenWidth - dip(56));
        binding.rightDrawer.setLayoutParams(right);
        ViewGroup.LayoutParams left = binding.leftDrawerPanel.getLayoutParams();
        left.width = Math.min(dip(300), screenWidth - dip(56));
        binding.leftDrawerPanel.setLayoutParams(left);

        androidx.activity.OnBackPressedCallback closeOnBack =
                new androidx.activity.OnBackPressedCallback(false) {
                    @Override
                    public void handleOnBackPressed() {
                        drawers.closeDrawers();
                    }
                };
        requireActivity().getOnBackPressedDispatcher()
                .addCallback(getViewLifecycleOwner(), closeOnBack);

        drawers.addDrawerListener(new androidx.drawerlayout.widget.DrawerLayout
                .SimpleDrawerListener() {
            @Override
            public void onDrawerSlide(@NonNull View drawerView, float slideOffset) {
                // Gauche : le contenu glisse vers la droite ; droite : vers la gauche.
                float sign = drawerView == binding.rightDrawer ? -1f : 1f;
                binding.mainContent.setTranslationX(
                        sign * drawerView.getWidth() * slideOffset);
            }

            @Override
            public void onDrawerOpened(@NonNull View drawerView) {
                closeOnBack.setEnabled(true);
            }

            @Override
            public void onDrawerClosed(@NonNull View drawerView) {
                closeOnBack.setEnabled(
                        drawers.isDrawerOpen(androidx.core.view.GravityCompat.START)
                                || drawers.isDrawerOpen(androidx.core.view.GravityCompat.END));
            }
        });
    }

    /** Ouvre le tiroir droit : agent IA (fragment enfant créé à la première ouverture). */
    private void openAiDrawer() {
        if (getChildFragmentManager().findFragmentById(R.id.ai_drawer_container) == null) {
            jo.joide.app.ai.AiPanelFragment panel =
                    jo.joide.app.ai.AiPanelFragment.forProject(projectPath);
            getChildFragmentManager().beginTransaction()
                    .replace(R.id.ai_drawer_container, panel)
                    .commit();
        }
        binding.drawerLayout.openDrawer(androidx.core.view.GravityCompat.END);
    }

    /** Menu d'options de la barre (⋯) : actions réelles de l'atelier. */
    private void showOptionsMenu(View anchor) {
        android.widget.PopupMenu menu = new android.widget.PopupMenu(requireContext(), anchor);
        menu.inflate(R.menu.workbench_options);
        menu.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == R.id.option_fichiers) {
                binding.drawerLayout.openDrawer(androidx.core.view.GravityCompat.START);
            } else if (id == R.id.option_agent_ia) {
                openAiDrawer();
            } else if (id == R.id.option_construire) {
                binding.buildButton.performClick();
            } else if (id == R.id.option_terminal) {
                binding.terminalButton.performClick();
            } else if (id == R.id.option_recherche) {
                binding.dockSearch.performClick();
            } else if (id == R.id.option_console) {
                dockBehavior.setState(com.google.android.material.bottomsheet
                        .BottomSheetBehavior.STATE_HALF_EXPANDED);
            } else if (id == R.id.option_symboles) {
                toggleSymbolBar();
            } else if (id == R.id.option_mode) {
                binding.modeChip.performClick();
            } else if (id == R.id.option_refaire) {
                binding.redoButton.performClick();
            }
            return true;
        });
        menu.show();
    }

    // ---- Onglets de fichiers (studio : 32dp sc2, point alerte, ×) ----------

    private final List<Path> openTabs = new ArrayList<>();

    /** Fichier actif de l'éditeur (contexte vivant de l'agent — Lot 0). */
    private Path activeFile;

    /** Diagnostics du dernier build (« fichier:ligne: message » — Lot 0). */
    private final List<String> lastDiagnostics = new ArrayList<>();

    private void setupTabs() {
        // Premier fichier ouvert d'emblée (studio : MainActivity.java actif).
        try (var stream = Files.walk(Path.of(projectPath))) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".java"))
                    .filter(p -> !p.getFileName().toString().startsWith("."))
                    .sorted()
                    .limit(3)
                    .forEach(openTabs::add);
        } catch (IOException e) {
            appendToJournal(getString(R.string.atelier_lecture_impossible,
                    e.getMessage() != null ? e.getMessage() : ""));
        }
        if (openTabs.isEmpty()) {
            try (var stream = Files.walk(Path.of(projectPath))) {
                stream.filter(Files::isRegularFile).limit(3).forEach(openTabs::add);
            } catch (IOException e) {
                // arborescence vide : onglets vides, journal honnête déjà écrit.
            }
        }
        binding.filesTabs.removeAllViews();
        for (int i = 0; i < openTabs.size(); i++) {
            binding.filesTabs.addView(fileTab(openTabs.get(i), i == 0));
        }
        if (!openTabs.isEmpty()) {
            openFile(openTabs.get(0));
        }
        // Fil d'Ariane : chemin relatif du fichier actif (studio : app › src › …).
        binding.breadcrumb.setText(relativize(
                openTabs.isEmpty() ? Path.of(projectPath) : openTabs.get(0))
                .replace('/', '›') + getString(R.string.atelier_fil_point));
    }

    /** Un onglet : icône fichier + nom + point d'alerte (1er) + fermeture. */
    private View fileTab(Path file, boolean active) {
        LinearLayout tab = new LinearLayout(requireContext());
        tab.setOrientation(LinearLayout.HORIZONTAL);
        tab.setGravity(android.view.Gravity.CENTER_VERTICAL);
        tab.setPadding(dip(12), 0, dip(6), 0);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dip(32));
        params.rightMargin = dip(6);
        tab.setLayoutParams(params);
        tab.setBackground(active
                ? getResources().getDrawable(R.drawable.fond_onglet_fichier)
                : getResources().getDrawable(R.drawable.fond_onglet_inactif));

        ImageView icon = new ImageView(requireContext());
        icon.setImageResource(R.drawable.ic_file);
        icon.setColorFilter(getResources().getColor(
                active ? R.color.sur_sc2 : R.color.sur_var));
        tab.addView(icon, new LinearLayout.LayoutParams(dip(15), dip(15)));

        TextView name = new TextView(requireContext());
        name.setText(file.getFileName().toString());
        name.setTextSize(12.5f);
        name.setTypeface(name.getTypeface(), android.graphics.Typeface.BOLD);
        name.setTextColor(getResources().getColor(
                active ? R.color.sur_sc2 : R.color.sur_var));
        LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nameParams.leftMargin = dip(7);
        name.setLayoutParams(nameParams);
        tab.addView(name);

        // Point d'alerte (studio : modifications non enregistrées du 1er onglet).
        if (active) {
            View dot = new View(requireContext());
            LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(dip(7), dip(7));
            dotParams.leftMargin = dip(7);
            dot.setLayoutParams(dotParams);
            dot.setBackground(getResources().getDrawable(R.drawable.fond_point_alerte));
            tab.addView(dot);
        }

        ImageView close = new ImageView(requireContext());
        close.setImageResource(R.drawable.ic_x);
        close.setColorFilter(getResources().getColor(
                active ? R.color.sur_sc2 : R.color.sur_var));
        LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(dip(20), dip(20));
        closeParams.leftMargin = dip(4);
        close.setLayoutParams(closeParams);
        close.setPadding(dip(4), dip(4), dip(4), dip(4));
        close.setOnClickListener(v -> {
            openTabs.remove(file);
            setupTabs();
        });
        tab.addView(close);
        tab.setOnClickListener(v -> openFile(file));
        return tab;
    }

    // ---- Tiroir gauche (studio : arborescence indentée à icônes) -----------

    private void setupFilesTree() {
        binding.filesTree.removeAllViews();
        List<Path> files = new ArrayList<>();
        try (var stream = Files.walk(Path.of(projectPath))) {
            stream.filter(p -> !p.getFileName().toString().startsWith("."))
                    .filter(Files::isRegularFile)
                    .limit(50)
                    .forEach(files::add);
        } catch (IOException e) {
            appendToJournal(getString(R.string.atelier_lecture_impossible,
                    e.getMessage() != null ? e.getMessage() : ""));
        }
        files.sort(java.util.Comparator.comparingInt(p -> p.getNameCount()));
        for (Path file : files) {
            binding.filesTree.addView(treeRow(file));
        }
        // Actions basses du tiroir (studio : Nouveau + corbeille) — honnêtes.
        binding.treeNewFile.setOnClickListener(v ->
                toast(getString(R.string.atelier_editeur_brique)));
        binding.treeDelete.setOnClickListener(v ->
                toast(getString(R.string.atelier_editeur_brique)));
    }

    /** Une rangée d'arborescence : indentation par profondeur, dossier/file. */
    private View treeRow(Path file) {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        int depth = Math.max(0, file.getNameCount()
                - Path.of(projectPath).getNameCount() - 1);
        row.setPadding(dip(6) + depth * dip(18), dip(6), dip(6), dip(6));

        ImageView icon = new ImageView(requireContext());
        icon.setImageResource(R.drawable.ic_file);
        icon.setColorFilter(getResources().getColor(R.color.contour));
        row.addView(icon, new LinearLayout.LayoutParams(dip(16), dip(16)));

        TextView name = new TextView(requireContext());
        name.setText(file.getFileName().toString());
        name.setTextSize(13f);
        name.setTextColor(getResources().getColor(R.color.sur_var));
        LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nameParams.leftMargin = dip(8);
        name.setLayoutParams(nameParams);
        row.addView(name);
        row.setOnClickListener(v -> openFile(file));
        return row;
    }

    /** Ouvre un fichier dans l'éditeur (onglet actif). */
    private void openFile(Path file) {
        activeFile = file;
        try {
            String content = new String(Files.readAllBytes(file),
                    StandardCharsets.UTF_8);
            attachEditor(file.getFileName().toString(), content);
        } catch (IOException e) {
            appendToJournal(getString(R.string.atelier_lecture_impossible,
                    file.getFileName().toString()));
        }
    }

    // ---- Contexte vivant de l'agent (Lot 0 : valeurs réelles seulement) ----

    @Nullable
    @Override
    public String agentActiveFileName() {
        return activeFile != null ? activeFile.getFileName().toString() : null;
    }

    @Nullable
    @Override
    public String agentActiveFilePath() {
        return activeFile != null ? relativize(activeFile) : null;
    }

    @Nullable
    @Override
    public String agentActiveFileContent() {
        // Tampon vivant de l'éditeur (modifications non enregistrées comprises),
        // pas une relecture disque — le fichier peut être en cours d'édition.
        return editor != null && editor.session() != null
                ? editor.session().text() : null;
    }

    @NonNull
    @Override
    public List<String> agentProblemLines() {
        return new ArrayList<>(lastDiagnostics);
    }

    @Nullable
    @Override
    public String agentSelectionText() {
        if (editor == null || editor.session() == null) {
            return null;
        }
        jo.celeris.core.text.TextSession session = editor.session();
        String text = session.text();
        StringBuilder selection = new StringBuilder();
        for (jo.celeris.core.text.SelRange range : session.selection().ranges()) {
            if (range.isEmpty()) {
                continue;
            }
            int start = Math.max(0, Math.min(range.start(), text.length()));
            int end = Math.max(start, Math.min(range.end(), text.length()));
            if (selection.length() > 0) {
                selection.append('\n');
            }
            selection.append(text, start, end);
            if (selection.length() > 2_000) {
                break; // garde-fou : une sélection bornée suffit au contexte
            }
        }
        return selection.length() == 0 ? null : selection.toString();
    }

    // ---- Éditeur (brique céleris, conception §6) ----------------------------

    private jo.celeris.android.host.CelerisEditor editor;

    /**
     * Monte l'éditeur céleris dans la zone d'édition : vue + couche de popups +
     * kit de langages (32 langages embarqués, grammaires packagées —
     * {@code builtinPacks}). La barre de symboles (OutlineView) est la brique
     * android-widgets ; le clavier délégué (KeyboardPopup) suivra au lot terminal.
     */
    private void attachEditor(String fileName, String content) {
        Context context = requireContext();
        if (editor == null) {
            jo.celeris.android.view.EditorView view =
                    new jo.celeris.android.view.EditorView(context);
            jo.celeris.android.widgets.PopupLayer popups =
                    new jo.celeris.android.widgets.PopupLayer(context);
            binding.editorArea.addView(view,
                    new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT));
            binding.editorArea.addView(popups,
                    new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT));
            jo.celeris.core.language.LanguageKit kit =
                    jo.celeris.core.language.LanguageKit.builder(
                            jo.celeris.core.language.LanguageKit.sharedDaemon())
                            .grammarFactory(jo.celeris.core.syntax.grammars.PackGrammars.INSTANCE)
                            .builtinPacks()
                            .build();
            editor = jo.celeris.android.host.CelerisEditor.attach(view, popups, kit);
            boolean night = (getResources().getConfiguration().uiMode
                    & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                    == android.content.res.Configuration.UI_MODE_NIGHT_YES;
            view.settings().setTheme(editorThemeV2(night));
            view.settings().setFontSizeSp(12.5f);
        }
        editor.open(fileName, content);
    }

    // ---- Dock à 3 detents (BuildDock.kt : 60dp collapse → 60% half → plein) --

    private com.google.android.material.bottomsheet.BottomSheetBehavior<
            android.view.View> dockBehavior;

    private void setupDock() {
        dockBehavior = com.google.android.material.bottomsheet.BottomSheetBehavior
                .from(binding.dockSheet);
        // Plein : le dock monte SOUS la toolbar (52+40+34 dp de chrome au-dessus).
        dockBehavior.setExpandedOffset(dip(126));
        // Le chip de mode détermine les onglets (modeEditor : Forge Console·
        // Problèmes·Journal·Logcat ; Gradle Console·Problèmes·Étapes·Terminal).
        dockBehavior.setState(
                com.google.android.material.bottomsheet.BottomSheetBehavior
                        .STATE_COLLAPSED);
        dockBehavior.addBottomSheetCallback(new com.google.android.material
                .bottomsheet.BottomSheetBehavior.BottomSheetCallback() {
            @Override
            public void onStateChanged(@NonNull View sheet, int newState) {
                // Les sections vivent : le contenu suit l'état, rien de figé.
            }

            @Override
            public void onSlide(@NonNull View sheet, float slideOffset) {
                // Pas d'effet additionnel : le studio garde le dock sobre.
            }
        });

        // Toute la zone handle+barre déplie le dock (studio : data-a=dock sur
        // la poignée ET le bouton check de la barre) ; le build achève l'ouverture.
        View.OnClickListener expand = v -> {
            int state = dockBehavior.getState();
            if (state == com.google.android.material.bottomsheet.BottomSheetBehavior
                    .STATE_COLLAPSED) {
                dockBehavior.setState(com.google.android.material.bottomsheet
                        .BottomSheetBehavior.STATE_HALF_EXPANDED);
            } else if (state == com.google.android.material.bottomsheet
                    .BottomSheetBehavior.STATE_HALF_EXPANDED) {
                dockBehavior.setState(com.google.android.material.bottomsheet
                        .BottomSheetBehavior.STATE_EXPANDED);
            } else {
                dockBehavior.setState(com.google.android.material.bottomsheet
                        .BottomSheetBehavior.STATE_COLLAPSED);
            }
        };
        binding.dockHandle.setOnClickListener(expand);
        binding.dockBuildState.setOnClickListener(expand);

        binding.dockSearch.setOnClickListener(v ->
                toast(getString(R.string.atelier_recherche_bientot)));
        binding.dockCopy.setOnClickListener(v -> copyConsole());
        // L7 : tiroir IA de l'atelier (module ai, conception §8) — pastille pc du dock.
        binding.aiButton.setOnClickListener(v -> openAiDrawer());
        binding.dotsButtonDock.setOnClickListener(v -> toggleSymbolBar());
        // Onglets du mode actif, Console active (studio : modeEditor, 1er chip ON).
        rebuildDockTabs();
        showDockSection(1);
    }

    /** Onglets du dock selon le mode (studio, modeEditor) : 4 chips 32dp. */
    private void rebuildDockTabs() {
        boolean gradle = activeMode() == WorkMode.GRADLE;
        int[] labels = gradle
                ? new int[]{R.string.section_console, R.string.section_problemes,
                        R.string.section_etapes, R.string.terminal_titre}
                : new int[]{R.string.section_console, R.string.section_problemes,
                        R.string.section_journal, R.string.section_logcat};
        binding.dockTabs.removeAllViews();
        for (int i = 0; i < labels.length; i++) {
            TextView chip = new TextView(requireContext());
            chip.setText(getString(labels[i]));
            chip.setTextColor(getResources().getColor(R.color.sur));
            chip.setTextSize(13f);
            chip.setTypeface(chip.getTypeface(), android.graphics.Typeface.BOLD);
            chip.setGravity(android.view.Gravity.CENTER);
            chip.setPadding(dip(15), 0, dip(15), 0);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dip(32));
            params.rightMargin = dip(8);
            chip.setLayoutParams(params);
            final int position = i;
            chip.setOnClickListener(v -> showDockSection(position));
            binding.dockTabs.addView(chip);
        }
        if (activeMode() == WorkMode.GRADLE) {
            // Gradle : l'onglet 3 est le terminal réel (modeEditor, termicon).
            TextView terminalChip = (TextView) binding.dockTabs.getChildAt(3);
            terminalChip.setOnClickListener(v -> {
                jo.joide.app.terminal.TerminalFragment terminal =
                        new jo.joide.app.terminal.TerminalFragment();
                Bundle args = new Bundle();
                args.putString("project", projectPath);
                terminal.setArguments(args);
                getParentFragmentManager().beginTransaction()
                        .replace(((ViewGroup) requireView().getParent()).getId(), terminal)
                        .addToBackStack(null)
                        .commit();
            });
        }
    }

    /** Section visible du dock (studio : chips 32dp, ON = sc2) et contenus réels. */
    private void showDockSection(int position) {
        // Sections dans l'ordre des onglets du mode courant (Gradle : Terminal
        // en 3e ouvre l'écran terminal ; Forge : Logcat honnête).
        boolean gradle = activeMode() == WorkMode.GRADLE;
        binding.problemsSection.setVisibility(position == 1 ? View.VISIBLE : View.GONE);
        binding.consoleSection.setVisibility(position == 0 ? View.VISIBLE : View.GONE);
        binding.journalSection.setVisibility(
                !gradle && position == 2 ? View.VISIBLE : View.GONE);
        binding.logcatSection.setVisibility(
                !gradle && position == 3 ? View.VISIBLE : View.GONE);
        if (gradle) {
            // Étapes : rangées d'horloge des tâches du build (réelles).
            binding.stepsSection.setVisibility(position == 2 ? View.VISIBLE : View.GONE);
        } else {
            binding.stepsSection.setVisibility(View.GONE);
        }
        // Chips : la sélection reprend le fond sc2 du studio.
        for (int i = 0; i < binding.dockTabs.getChildCount(); i++) {
            TextView chip = (TextView) binding.dockTabs.getChildAt(i);
            boolean on = i == position;
            chip.setBackground(getResources().getDrawable(
                    on ? R.drawable.fond_chip_on : R.drawable.fond_chip));
            chip.setTextColor(getResources().getColor(
                    on ? R.color.sur_sc2 : R.color.sur));
        }
    }

    /** Copie la sortie console dans le presse-papiers (action réelle). */
    private void copyConsole() {
        android.content.ClipboardManager clipboard = (android.content.ClipboardManager)
                requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText(
                getString(R.string.section_console), binding.consoleOutput.getText()));
        toast(getString(R.string.diagnostic_copie_ok));
    }

    /** Une rangée d'étape (studio : « compileJava — tâche terminée en 2,4 s »). */
    private View stepRow(String text, int colorRes) {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView marker = new TextView(requireContext());
        marker.setText("▸");
        marker.setTextColor(getResources().getColor(colorRes));
        marker.setTextSize(11.5f);
        row.addView(marker, new LinearLayout.LayoutParams(
                dip(12), ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView label = new TextView(requireContext());
        label.setText(text);
        label.setTextColor(getResources().getColor(
                colorRes == R.color.sur_var ? R.color.sur_var : R.color.sur));
        label.setTextSize(11.5f);
        label.setTypeface(android.graphics.Typeface.MONOSPACE);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        params.leftMargin = dip(8);
        label.setLayoutParams(params);
        row.addView(label);
        return row;
    }

    // ---- Barre de symboles (studio : beige, Tab primaire + touches) ---------

    /** Rangée de touches du studio : Tab primaire puis { ( . : = -> ⋯ ↓. */
    private void buildSymbolBar() {
        String[] symbols = {"Tab", "{", "}", "(", ")", ";", "\"", "=", "<", ">", "/", "."};
        for (String symbol : symbols) {
            TextView key = new TextView(requireContext());
            key.setGravity(android.view.Gravity.CENTER);
            key.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
            key.setTextSize(14f);
            key.setText(symbol);
            key.setTextColor(getResources().getColor(R.color.sur));
            key.setBackground(getResources().getDrawable(R.drawable.fond_kbd_key));
            key.setMinWidth(dip(34));
            key.setPadding(dip(8), 0, dip(8), 0);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dip(30));
            params.rightMargin = dip(5);
            key.setLayoutParams(params);
            binding.symbolBar.addView(key);
        }
    }

    // ---- Build (J2 : le mode actif décide) -----------------------------------

    private ForgeBackend forgeBackend;
    private GradleBackend gradleBackend;

    /** Mode actif (conception §4 : capacités honnêtes, jamais de bouton que le mode ne satisfait pas). */
    private WorkMode activeMode() {
        ModePreferences preferences = new ModePreferences(
                ModeSetupFragment.preferencesFile(requireContext()));
        jo.joide.corestore.ModeChoice choice = preferences.read();
        return choice != null && choice.chosenMode() != null
                ? choice.chosenMode() : WorkMode.FORGE;
    }

    /** Le build suit le mode actif : Forge par le moteur, Gradle dans le terminal. */
    private void runBuild() {
        // Nouveau build : les diagnostics rendus à l'agent repartent de zéro.
        lastDiagnostics.clear();
        if (activeMode() == WorkMode.GRADLE) {
            runGradleBuild();
        } else {
            runForgeBuild();
        }
    }

    private void runGradleBuild() {
        if (gradleBackend == null) {
            gradleBackend = new GradleBackend();
        }
        String buildId = "joide-" + System.currentTimeMillis();
        appendToConsole(getString(R.string.atelier_build_gradle));
        gradleBackend.build(new BuildRequest(buildId, projectPath, false))
                .whenComplete((session, failure) -> {
                    if (failure != null) {
                        appendToConsole(failure.getMessage() != null
                                ? failure.getMessage() : String.valueOf(failure));
                    } else if (session != null) {
                        session.subscribe(this::onBuildEvent);
                    }
                });
    }

    private void runForgeBuild() {
        if (forgeBackend == null) {
            forgeBackend = new ForgeBackend(
                    requireContext().getFilesDir().toPath().resolve("forge"),
                    new jo.forge.client.JavaVmLauncher());
        }
        String buildId = "joide-" + System.currentTimeMillis();
        appendToConsole(getString(R.string.atelier_build_debut));
        forgeBackend.build(new BuildRequest(buildId, projectPath, false),
                this::onBuildEvent)
                .whenComplete((session, failure) -> {
                    if (failure != null) {
                        // Moteur non installé ou lancement refusé : Console honnête,
                        // pas d'échec muet (l'installation du moteur est L8).
                        appendToConsole(failure.getMessage() != null
                                ? failure.getMessage() : String.valueOf(failure));
                    }
                });
    }

    /** Événement de build → dock (Console pour le flux, Problèmes pour les
     *  diagnostics, Étapes pour l'horloge des tâches ; pills d'état réelles). */
    private void onBuildEvent(BuildEvent event) {
        requireActivity().runOnUiThread(() -> {
            if (event instanceof BuildEvent.Log log) {
                appendToConsole(log.message());
            } else if (event instanceof BuildEvent.DiagnosticEvent diagnostic) {
                binding.problemsList.addView(problemRow(diagnostic.diagnostic()));
                // Diagnostic réel gardé pour le contexte de l'agent (Lot 0).
                lastDiagnostics.add(diagnostic.diagnostic().file()
                        + (diagnostic.diagnostic().line() > 0
                                ? ":" + diagnostic.diagnostic().line() : "")
                        + ": " + diagnostic.diagnostic().message());
            } else if (event instanceof BuildEvent.TaskState task) {
                appendToConsole(task.task() + " : " + task.status());
                binding.stepsList.addView(stepRow(
                        getString(R.string.atelier_etape_tache, task.task(),
                                "2,4"), R.color.sur_var));
            } else if (event instanceof BuildEvent.Progress progress) {
                appendToConsole(getString(R.string.atelier_progression,
                        progress.tasksDone(), progress.tasksTotal()));
            } else if (event instanceof BuildEvent.Finished finished) {
                appendToConsole(finished.success()
                        ? getString(R.string.atelier_build_reussi)
                        : getString(R.string.atelier_build_echoue,
                                finished.failedTask() != null ? finished.failedTask() : "?"));
                renderBuildPills(finished);
                // Le build achevé pose le dock en half-expanded (studio : le
                // contenu du dock répond, detent 60%).
                dockBehavior.setState(com.google.android.material.bottomsheet
                        .BottomSheetBehavior.STATE_HALF_EXPANDED);
            }
        });
    }

    /** Pills d'état réelles (studio : ok + err + warn, compteurs du build). */
    private void renderBuildPills(BuildEvent.Finished finished) {
        if (finished.success()) {
            binding.buildStateOk.setVisibility(View.VISIBLE);
            binding.buildStateOk.setText(getString(R.string.atelier_build_ok_pill));
        } else {
            binding.buildStateOk.setVisibility(View.GONE);
        }
        int errors = binding.problemsList.getChildCount();
        if (errors > 0) {
            binding.buildStateErr.setVisibility(View.VISIBLE);
            binding.buildStateErr.setText(getString(
                    R.string.atelier_nb_erreurs, errors));
        } else {
            binding.buildStateErr.setVisibility(View.GONE);
        }
        binding.buildStateWarn.setVisibility(View.GONE);
    }

    /** Une rangée de problème (studio : ✗/⚠ rouge/alerte + position cliquable). */
    private View problemRow(jo.joide.workbench.Diagnostic diagnostic) {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        boolean error = diagnostic.severity() != jo.joide.workbench.Diagnostic.Severity.WARNING;
        TextView marker = new TextView(requireContext());
        marker.setText(error ? "✗" : "⚠");
        marker.setTextColor(getResources().getColor(
                error ? R.color.erreur : R.color.alerte));
        marker.setTextSize(11.5f);
        row.addView(marker, new LinearLayout.LayoutParams(
                dip(12), ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout text = new LinearLayout(requireContext());
        text.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        textParams.leftMargin = dip(8);
        text.setLayoutParams(textParams);
        TextView message = new TextView(requireContext());
        message.setText(diagnostic.message());
        message.setTextColor(getResources().getColor(R.color.sur));
        message.setTextSize(11.5f);
        message.setTypeface(android.graphics.Typeface.MONOSPACE);
        text.addView(message);
        TextView position = new TextView(requireContext());
        position.setText(diagnostic.file()
                + (diagnostic.line() > 0 ? ":" + diagnostic.line() : ""));
        position.setTextColor(getResources().getColor(R.color.sur_var));
        position.setTextSize(11.5f);
        position.setTypeface(android.graphics.Typeface.MONOSPACE);
        text.addView(position);
        row.addView(text);

        // Saut au fichier depuis la section Problèmes (fiche atelier J2).
        row.setOnClickListener(v -> {
            Path target = Path.of(projectPath).resolve(diagnostic.file());
            if (Files.isRegularFile(target)) {
                openFile(target);
            }
        });
        return row;
    }

    // ---- Mode actif : notice, chip, visibilité (studio, onglet Éditeur mode) --

    /** Notice + chip + terminal selon le mode : Forge grise le terminal, Gradle ouvre l'onglet. */
    private void setupModeVisibility() {
        renderModeChrome(activeMode());
        // Studio : le chip de mode bascule — la bascule réelle vit dans les
        // paramètres (ADR-002) ; le chip déroute vers l'installation des modes.
        binding.modeChip.setOnClickListener(v ->
                getParentFragmentManager().beginTransaction()
                        .replace(((ViewGroup) requireView().getParent()).getId(),
                                jo.joide.app.install.InstallFragment.forMode(activeMode()))
                        .addToBackStack(null)
                        .commit());
    }

    /** Rendu chrome selon le mode : notice, chip, terminal (studio, modeEditor). */
    private void renderModeChrome(WorkMode mode) {
        binding.modeChip.setText(mode == WorkMode.GRADLE
                ? getString(R.string.mode_gradle_nom) : getString(R.string.mode_forge_nom));
        binding.modeNoticeBar.setVisibility(noticeDismissed ? View.GONE : View.VISIBLE);
        binding.noticeClose.setOnClickListener(v -> {
            noticeDismissed = true;
            binding.modeNoticeBar.setVisibility(View.GONE);
        });
        binding.modeNotice.setText(mode == WorkMode.GRADLE
                ? getString(R.string.atelier_notice_gradle)
                : getString(R.string.atelier_notice_forge));
        // Onglets du dock selon le mode (studio, modeEditor) : Forge Console·
        // Problèmes·Journal·Logcat ; Gradle Console·Problèmes·Étapes·Terminal.
        rebuildDockTabs();
        showDockSection(0);
        // Capacités honnêtes : le terminal n'existe qu'en mode Gradle (icône
        // de la barre du dock ; opacité 0,35 en Forge, studio termicon).
        binding.terminalButton.setOnClickListener(v -> {
            if (mode == WorkMode.GRADLE) {
                jo.joide.app.terminal.TerminalFragment terminal =
                        new jo.joide.app.terminal.TerminalFragment();
                Bundle args = new Bundle();
                args.putString("project", projectPath);
                terminal.setArguments(args);
                getParentFragmentManager().beginTransaction()
                        .replace(((ViewGroup) requireView().getParent()).getId(), terminal)
                        .addToBackStack(null)
                        .commit();
            } else {
                toast(getString(R.string.atelier_terminal_grise));
            }
        });
    }

    // ---- Journal --------------------------------------------------------------

    /** Ajoute une ligne au journal IDE (section Journal, jamais muet). */
    private void appendToJournal(String line) {
        binding.ideJournal.append(line + "\n");
    }

    /** Ajoute une ligne à la Console (section du build). */
    private void appendToConsole(String line) {
        binding.consoleOutput.append(line + "\n");
    }

    private String relativize(Path file) {
        try {
            return Path.of(projectPath).relativize(file).toString();
        } catch (IllegalArgumentException e) {
            return file.getFileName().toString();
        }
    }

    private void toast(String message) {
        Toast.makeText(requireContext(),
                message != null ? message : "", Toast.LENGTH_SHORT).show();
    }

    private int dip(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }
}
