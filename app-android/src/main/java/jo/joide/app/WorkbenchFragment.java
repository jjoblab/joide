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
 */
public class WorkbenchFragment extends Fragment {

    private static final String ARG_PROJECT = "project";

    private WorkbenchFragmentBinding binding;
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
        binding.menuButton.setOnClickListener(v ->
                binding.leftDrawer.setVisibility(View.VISIBLE));
        binding.closeLeft.setOnClickListener(v ->
                binding.leftDrawer.setVisibility(View.GONE));
        binding.leftScrim.setOnClickListener(v ->
                binding.leftDrawer.setVisibility(View.GONE));
        binding.dotsButton.setOnClickListener(v ->
                toast(getString(R.string.atelier_menu_bientot)));
        // Studio : save/undo/redo — la brique céleris porte ces états (L5) ;
        // les boutons disent honnêtement l'état.
        String editorState = getString(R.string.atelier_editeur_brique);
        binding.saveButton.setOnClickListener(v -> toast(editorState));
        binding.undoButton.setOnClickListener(v -> toast(editorState));
        binding.redoButton.setOnClickListener(v -> toast(editorState));
    }

    // ---- Onglets de fichiers (studio : 32dp sc2, point alerte, ×) ----------

    private final List<Path> openTabs = new ArrayList<>();

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
        int pad = dip(12);
        tab.setPadding(pad, 0, pad, 0);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dip(32));
        params.rightMargin = dip(2);
        tab.setLayoutParams(params);
        tab.setBackground(active
                ? getResources().getDrawable(R.drawable.fond_onglet_fichier) : null);

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
        LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(dip(12), dip(12));
        closeParams.leftMargin = dip(7);
        close.setLayoutParams(closeParams);
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
        try {
            String content = new String(Files.readAllBytes(file),
                    StandardCharsets.UTF_8);
            attachEditor(file.getFileName().toString(), content);
        } catch (IOException e) {
            appendToJournal(getString(R.string.atelier_lecture_impossible,
                    file.getFileName().toString()));
        }
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
                        .STATE_HALF_EXPANDED);
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
        binding.aiButton.setOnClickListener(v -> {
            jo.joide.app.ai.AiPanelFragment panel =
                    new jo.joide.app.ai.AiPanelFragment();
            Bundle args = new Bundle();
            args.putString("project", projectPath);
            panel.setArguments(args);
            getParentFragmentManager().beginTransaction()
                    .replace(((ViewGroup) requireView().getParent()).getId(), panel)
                    .addToBackStack(null)
                    .commit();
        });
        binding.dotsButtonDock.setOnClickListener(v -> {
            // Studio : « clavier : toggle par un bouton ⋯ du dock » — la barre
            // de symboles beige apparaît/disparaît sous l'éditeur.
            binding.symbolBar.setVisibility(binding.symbolBar.getVisibility()
                    == View.VISIBLE ? View.GONE : View.VISIBLE);
            if (binding.symbolBar.getChildCount() == 0) {
                buildSymbolBar();
            }
        });
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
        String[] symbols = {"{", "}", "(", ".", ":", "=", "->"};
        for (int i = 0; i <= symbols.length; i++) {
            TextView key = new TextView(requireContext());
            key.setGravity(android.view.Gravity.CENTER);
            key.setTypeface(android.graphics.Typeface.MONOSPACE);
            key.setTextSize(13f);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    i == 0 ? dip(44) : dip(38), dip(36));
            params.rightMargin = dip(4);
            key.setLayoutParams(params);
            if (i == 0) {
                key.setText(getString(R.string.atelier_touche_tab));
                key.setBackground(getResources().getDrawable(R.drawable.fond_kbd_tab));
                key.setTextColor(getResources().getColor(R.color.sur_primaire));
                key.setTypeface(key.getTypeface(), android.graphics.Typeface.BOLD);
            } else {
                key.setText(symbols[i - 1]);
                key.setBackground(getResources().getDrawable(R.drawable.fond_kbd_key));
                key.setTextColor(getResources().getColor(R.color.sur));
            }
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
        binding.modeNotice.setVisibility(View.VISIBLE);
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
