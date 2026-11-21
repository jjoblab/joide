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

    // ---- Dock (studio : poignée replie/déplie, onglets, pills d'état) ------

    private void setupDock() {
        binding.dockHandle.setOnClickListener(v -> toggleDock());
        binding.terminalButton.setOnClickListener(v -> toggleDock());
        binding.dockSearch.setOnClickListener(v ->
                toast(getString(R.string.atelier_recherche_bientot)));
        binding.dockCode.setOnClickListener(v -> {
            // Studio : le bouton code du dock ouvre la barre de symboles.
            binding.symbolBar.setVisibility(binding.symbolBar.getVisibility()
                    == View.VISIBLE ? View.GONE : View.VISIBLE);
            if (binding.symbolBar.getChildCount() == 0) {
                buildSymbolBar();
            }
        });
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
        binding.dotsButtonDock.setOnClickListener(v ->
                toast(getString(R.string.atelier_menu_bientot)));
        binding.dockTabProblems.setOnClickListener(v -> showDockSection(0));
        binding.dockTabConsole.setOnClickListener(v -> showDockSection(1));
        binding.dockTabSteps.setOnClickListener(v -> showDockSection(2));
        binding.dockTabJournal.setOnClickListener(v -> showDockSection(3));
        showDockSection(1); // Console par défaut (studio : Problèmes, réels par build).
    }

    /** Replie/déplie le corps du dock (studio : detent 52dp → contenu). */
    private void toggleDock() {
        binding.dockBody.setVisibility(binding.dockBody.getVisibility()
                == View.VISIBLE ? View.GONE : View.VISIBLE);
    }

    /** Section visible du dock (studio : chips 32dp, ON = sc2). */
    private void showDockSection(int position) {
        binding.problemsList.setVisibility(position == 0 ? View.VISIBLE : View.GONE);
        binding.consoleOutput.setVisibility(position == 1 ? View.VISIBLE : View.GONE);
        binding.ideJournal.setVisibility(position == 3 ? View.VISIBLE : View.GONE);
        binding.dockTabProblems.setBackground(getResources().getDrawable(
                position == 0 ? R.drawable.fond_chip_on : R.drawable.fond_chip));
        binding.dockTabConsole.setBackground(getResources().getDrawable(
                position == 1 ? R.drawable.fond_chip_on : R.drawable.fond_chip));
        binding.dockTabSteps.setBackground(getResources().getDrawable(
                position == 2 ? R.drawable.fond_chip_on : R.drawable.fond_chip));
        binding.dockTabJournal.setBackground(getResources().getDrawable(
                position == 3 ? R.drawable.fond_chip_on : R.drawable.fond_chip));
        // Étapes : aucune donnée réelle dans ce lot — section honnête (journal).
        if (position == 2) {
            binding.ideJournal.setVisibility(View.VISIBLE);
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

    /** Événement de build → dock (Console pour le flux, Problèmes pour les diagnostics). */
    private void onBuildEvent(BuildEvent event) {
        requireActivity().runOnUiThread(() -> {
            if (event instanceof BuildEvent.Log log) {
                appendToConsole(log.message());
            } else if (event instanceof BuildEvent.DiagnosticEvent diagnostic) {
                binding.problemsList.append(diagnosticSummary(diagnostic.diagnostic())
                        + "\n");
            } else if (event instanceof BuildEvent.TaskState task) {
                appendToConsole(task.task() + " : " + task.status());
            } else if (event instanceof BuildEvent.Progress progress) {
                appendToConsole(getString(R.string.atelier_progression,
                        progress.tasksDone(), progress.tasksTotal()));
            } else if (event instanceof BuildEvent.Finished finished) {
                appendToConsole(finished.success()
                        ? getString(R.string.atelier_build_reussi)
                        : getString(R.string.atelier_build_echoue,
                                finished.failedTask() != null ? finished.failedTask() : "?"));
                binding.buildStateOk.setVisibility(
                        finished.success() ? View.VISIBLE : View.GONE);
                if (finished.success()) {
                    binding.buildStateOk.setText(getString(R.string.atelier_build_ok_pill));
                }
                binding.dockBody.setVisibility(View.VISIBLE);
            }
        });
    }

    private String diagnosticSummary(jo.joide.workbench.Diagnostic diagnostic) {
        return diagnostic.severity() + " · " + diagnostic.file()
                + (diagnostic.line() > 0 ? ":" + diagnostic.line() : "")
                + " · " + diagnostic.message();
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
        // Capacités honnêtes : le terminal n'existe qu'en mode Gradle.
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
