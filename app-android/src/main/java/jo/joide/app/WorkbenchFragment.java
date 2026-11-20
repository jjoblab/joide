package jo.joide.app;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import jo.joide.app.databinding.WorkbenchFragmentBinding;
import jo.joide.app.databinding.FileListItemBinding;
import jo.joide.buildforge.ForgeBackend;
import jo.joide.workbench.BuildEvent;
import jo.joide.workbench.BuildRequest;

/**
 * Atelier (fiche ecrans/atelier.md) : barre, zone éditeur (brique céleris),
 * tiroir fichiers, bottom sheet à en-tête unique et quatre sections — Console et
 * Problèmes consomment le `BuildBackend` du mode actif (conception §4).
 *
 * <p>J2 : un projet s'édite et se construit en mode Forge — le journal du build
 * alimente la Console ; les diagnostics (sévérité, position) alimentent la
 * section Problèmes avec saut au fichier.</p>
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
            setupFilesList();
            setupSections();
            // J2 : construire — le build Forge alimente la Console (journal,
            // progression) et la section Problèmes (diagnostics avec position).
            binding.buildButton.setOnClickListener(v -> runForgeBuild());
        }
        return binding.getRoot();
    }

    // ---- Build (J2 : le projet se construit en mode Forge) ------------------

    private ForgeBackend forgeBackend;

    private void runForgeBuild() {
        if (forgeBackend == null) {
            forgeBackend = new ForgeBackend(
                    requireContext().getFilesDir().toPath().resolve("forge"),
                    new jo.forge.client.JavaVmLauncher());
        }
        String buildId = "joide-" + System.currentTimeMillis();
        appendToConsole("— build " + buildId + " —");
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

    /** Événement de build → sections (Console pour le flux, Problèmes pour les diagnostics). */
    private void onBuildEvent(BuildEvent event) {
        requireActivity().runOnUiThread(() -> {
            if (event instanceof BuildEvent.Log log) {
                appendToConsole(log.message());
            } else if (event instanceof BuildEvent.DiagnosticEvent diagnostic) {
                binding.problemsList.append(diagnosticSummary(diagnostic.diagnostic())
                        + "\n");
            } else if (event instanceof BuildEvent.TaskState task) {
                appendToConsole(task.taskName() + " : " + task.status());
            } else if (event instanceof BuildEvent.Progress progress) {
                appendToConsole("progression : " + progress.tasksDone()
                        + "/" + progress.tasksTotal());
            } else if (event instanceof BuildEvent.Finished finished) {
                appendToConsole(finished.success()
                        ? getString(R.string.atelier_build_reussi)
                        : getString(R.string.atelier_build_echoue,
                                finished.failedTask() != null ? finished.failedTask() : "?"));
            }
        });
    }

    private String diagnosticSummary(jo.joide.workbench.Diagnostic diagnostic) {
        return diagnostic.severity() + " · " + diagnostic.file()
                + (diagnostic.line() > 0 ? ":" + diagnostic.line() : "")
                + " · " + diagnostic.message();
    }

    // ---- Fichiers (tiroir → onglet actif) -----------------------------------

    private final List<Path> projectFiles = new ArrayList<>();

    /** Liste plate des fichiers du projet (tiroir fichiers). */
    private void setupFilesList() {
        Path root = Path.of(projectPath);
        try (var stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> !p.getFileName().toString().startsWith("."))
                    .limit(50)
                    .forEach(projectFiles::add);
        } catch (IOException e) {
            // Projet illisible : journal IDE, l'atelier reste ouvert (jamais muet).
            appendToJournal("Lecture du projet impossible : " + e.getMessage());
        }
        binding.filesRecycler.setLayoutManager(new LinearLayoutManager(getContext()));
        binding.filesRecycler.setAdapter(new FilesAdapter());
        if (!projectFiles.isEmpty()) {
            openFile(projectFiles.get(0));
        }
    }

    /** Ouvre un fichier dans l'éditeur (onglet actif). */
    private void openFile(Path file) {
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            attachEditor(file.getFileName().toString(), content);
        } catch (IOException e) {
            appendToJournal("Ouverture de " + file.getFileName()
                    + " impossible : " + e.getMessage());
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

    // ---- Sections (Console / Problèmes / Journal / Logcat) -----------------

    private void setupSections() {
        binding.sectionsTabs.addOnTabSelectedListener(
                new com.google.android.material.tabs.TabLayout.OnTabSelectedListener() {
                    @Override public void onTabSelected(
                            com.google.android.material.tabs.TabLayout.Tab tab) {
                        showSection(tab.getPosition());
                    }
                    @Override public void onTabUnselected(
                            com.google.android.material.tabs.TabLayout.Tab tab) { }
                    @Override public void onTabReselected(
                            com.google.android.material.tabs.TabLayout.Tab tab) { }
                });
        // Console par défaut.
        showSection(0);
    }

    private void showSection(int position) {
        binding.consoleOutput.setVisibility(position == 0 ? View.VISIBLE : View.GONE);
        binding.problemsList.setVisibility(position == 1 ? View.VISIBLE : View.GONE);
        binding.ideJournal.setVisibility(position == 2 ? View.VISIBLE : View.GONE);
        binding.logcatOutput.setVisibility(position == 3 ? View.VISIBLE : View.GONE);
    }

    /** Ajoute une ligne au journal IDE (section Journal, jamais muet). */
    private void appendToJournal(String line) {
        binding.ideJournal.append(line + "\n");
    }

    /** Ajoute une ligne à la Console (section du build). */
    private void appendToConsole(String line) {
        binding.consoleOutput.append(line + "\n");
    }

    // ---- Adaptateur fichiers ------------------------------------------------

    private final class FilesAdapter
            extends RecyclerView.Adapter<FilesAdapter.FileHolder> {

        @NonNull
        @Override
        public FileHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new FileHolder(FileListItemBinding.inflate(
                    LayoutInflater.from(parent.getContext()), parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull FileHolder holder, int position) {
            Path file = projectFiles.get(position);
            holder.binding.fileName.setText(relativize(file));
            holder.itemView.setOnClickListener(v -> openFile(file));
        }

        @Override
        public int getItemCount() {
            return projectFiles.size();
        }

        final class FileHolder extends RecyclerView.ViewHolder {
            final FileListItemBinding binding;

            FileHolder(FileListItemBinding binding) {
                super(binding.getRoot());
                this.binding = binding;
            }
        }
    }

    private String relativize(Path file) {
        try {
            return Path.of(projectPath).relativize(file).toString();
        } catch (IllegalArgumentException e) {
            return file.getFileName().toString();
        }
    }
}
