package jo.joide.app;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import jo.joide.app.databinding.HomeFragmentBinding;
import jo.joide.app.databinding.ProjectCardItemBinding;
import jo.joide.corestore.ProjectStore;
import jo.joide.corestore.ProjectTemplates;
import jo.joide.workbench.WorkMode;

/**
 * Accueil (fiche ecrans/accueil.md) : liste des projets du stockage privé (cartes
 * à une ligne de méta + badge des modes compatibles), création depuis modèles,
 * import (SAF, câblé en L8 avec les permissions documentées), ouverture de
 * l'atelier sur le projet choisi. L'état vide est porté par le gabarit : il
 * disparaît dès qu'une carte existe.
 */
public class HomeFragment extends Fragment {

    private HomeFragmentBinding binding;
    private ProjectStore store;
    private ProjectStore.ProjectCard[] cards = new ProjectStore.ProjectCard[0];
    private WorkMode filter; // null : tous les projets (studio HTML, chips d'accueil).

    /** Racine du stockage des projets (filesDir/projects, ADR-010). */
    static Path projectsRoot(android.content.Context context) {
        return context.getFilesDir().toPath().resolve("projects");
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        binding = HomeFragmentBinding.inflate(inflater, container, false);
        store = new ProjectStore(projectsRoot(requireContext()));

        binding.projectsRecycler.setLayoutManager(new LinearLayoutManager(getContext()));
        refresh();

        // L8 : entrée Paramètres dans le menu de la toolbar (IA, modes, diagnostic).
        binding.toolbar.inflateMenu(R.menu.home_menu);
        binding.toolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.action_settings) {
                getParentFragmentManager().beginTransaction()
                        .replace(((ViewGroup) requireView().getParent()).getId(),
                                new jo.joide.app.settings.SettingsFragment())
                        .addToBackStack(null)
                        .commit();
                return true;
            }
            return false;
        });

        binding.newProjectButton.setOnClickListener(v -> showCreateDialog());
        binding.fabNewProject.setOnClickListener(v -> showCreateDialog());
        binding.importProjectButton.setOnClickListener(v -> {
            // Import SAF (fiche accueil) : câblé avec les permissions en L8 ; le
            // bouton reste honnête (action annoncée non câblée, jamais un échec muet).
            showTransientNotice(getString(R.string.import_non_cable_encore));
        });
        setupFilters();
        setupCloneCard();
        setupBottomNav();
        return binding.getRoot();
    }

    /** Navbar du studio : Projets actif ; IA ouvre l'assistant ; Apprendre/Défis honnêtes. */
    private void setupBottomNav() {
        binding.bottomNav.setSelectedItemId(R.id.nav_projects);
        binding.bottomNav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            if (id == R.id.nav_ia) {
                getParentFragmentManager().beginTransaction()
                        .replace(((ViewGroup) requireView().getParent()).getId(),
                                new jo.joide.app.ai.AiPanelFragment())
                        .addToBackStack(null)
                        .commit();
                return true;
            }
            if (id == R.id.nav_apprendre || id == R.id.nav_defis) {
                showTransientNotice(getString(R.string.accueil_nav_bientot));
                return false; // la sélection ne s'installe pas (pas d'écran derrière).
            }
            return true;
        });
    }

    // ---- Filtres par mode (studio HTML : Tous / Forge / Gradle) ----------------

    private void setupFilters() {
        binding.filterRow.setVisibility(
                store.list().isEmpty() ? View.GONE : View.VISIBLE);
        binding.filterAll.setText(getString(R.string.accueil_filtre_tous,
                store.list().size()));
        binding.filterForge.setText(getString(R.string.mode_forge_nom));
        binding.filterGradle.setText(getString(R.string.mode_gradle_nom));
        binding.filterAll.setOnClickListener(v -> applyFilter(null));
        binding.filterForge.setOnClickListener(v -> applyFilter(WorkMode.FORGE));
        binding.filterGradle.setOnClickListener(v -> applyFilter(WorkMode.GRADLE));
        applyFilter(null);
    }

    private void applyFilter(WorkMode mode) {
        filter = mode;
        List<ProjectStore.ProjectCard> list = store.list();
        cards = list.stream()
                .filter(c -> mode == null || c.compatibleModes().contains(mode))
                .toArray(ProjectStore.ProjectCard[]::new);
        binding.filterAll.setSelected(mode == null);
        binding.filterForge.setSelected(mode == WorkMode.FORGE);
        binding.filterGradle.setSelected(mode == WorkMode.GRADLE);
        binding.projectsRecycler.setAdapter(new CardsAdapter());
        boolean empty = cards.length == 0;
        binding.emptyStateView.setVisibility(empty ? View.VISIBLE : View.GONE);
        binding.projectsRecycler.setVisibility(empty ? View.GONE : View.VISIBLE);
    }

    // ---- Clonage git (popup ancrée de l'accueil → carte dépliable) ------------

    private void setupCloneCard() {
        // Le clonage se déplie depuis la carte (l'import garde sa place) : la
        // brique JGit vit dans core-git (testée JVM).
        binding.cloneCard.setVisibility(View.VISIBLE);
        binding.cloneButton.setOnClickListener(v -> {
            String url = binding.cloneUrlField.getText().toString().trim();
            if (url.isEmpty()) {
                return;
            }
            new Thread(() -> {
                try {
                    java.nio.file.Path target = projectsRoot(requireContext())
                            .resolve(url.replaceAll(".*/", "").replace(".git", ""));
                    jo.joide.coregit.GitService.cloneInto(url, target);
                    requireActivity().runOnUiThread(() -> {
                        showTransientNotice(getString(R.string.git_clonage_ok,
                                target.getFileName().toString()));
                        refresh();
                    });
                } catch (IOException e) {
                    requireActivity().runOnUiThread(
                            () -> showTransientNotice(e.getMessage()));
                }
            }, "clone-accueil").start();
        });
    }

    /** Recharge la liste (le filtre courant s'applique de nouveau). */
    private void refresh() {
        applyFilter(filter);
    }

    /** Dialog de création (studio HTML, onglet Création) : nom, paquet, modèles réels. */
    private void showCreateDialog() {
        java.util.List<String> names = ProjectTemplates.standard().names();
        CharSequence[] items = names.toArray(new CharSequence[0]);
        android.app.AlertDialog.Builder builder =
                new android.app.AlertDialog.Builder(requireContext());
        builder.setTitle(getString(R.string.creation_titre));
        LinearLayout body = new LinearLayout(requireContext());
        body.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        body.setPadding(pad, pad / 2, pad, 0);
        final android.widget.EditText nameField = new android.widget.EditText(requireContext());
        nameField.setHint(getString(R.string.creation_modele_hint));
        body.addView(nameField);
        final android.widget.EditText packageField = new android.widget.EditText(requireContext());
        packageField.setHint(getString(R.string.creation_paquet_hint));
        packageField.setTypeface(android.graphics.Typeface.MONOSPACE);
        body.addView(packageField);
        builder.setView(body);
        builder.setItems(items, (d, which) ->
                createFrom(names.get(which), nameField.getText().toString()));
        builder.show();
    }

    /** Crée le projet depuis le modèle et nom choisis, ouvre l'atelier. */
    private void createFrom(String template, String name) {
        if (name == null || name.isBlank()) {
            name = String.format(Locale.getDefault(), "projet-%d",
                    System.currentTimeMillis() % 100000);
        }
        try {
            Path project = ProjectTemplates.standard()
                    .instantiate(store, template, name);
            openWorkbench(project.toAbsolutePath().toString());
        } catch (IOException | IllegalArgumentException e) {
            showTransientNotice(getString(R.string.creation_echec, e.getMessage()));
        }
    }

    /** Crée le projet depuis le modèle et ouvre l'atelier dessus. */
    private void createFromTemplate(String template) {
        String name = String.format(Locale.getDefault(), "projet-%d",
                System.currentTimeMillis() % 100000);
        try {
            Path project = ProjectTemplates.standard()
                    .instantiate(store, template, name);
            openWorkbench(project.toAbsolutePath().toString());
        } catch (IOException | IllegalArgumentException e) {
            showTransientNotice(getString(R.string.creation_echec, e.getMessage()));
        }
    }

    /** Ouvre l'atelier sur le projet (le mode reste le choix de l'utilisateur). */
    private void openWorkbench(String projectPath) {
        WorkbenchFragment workbench = WorkbenchFragment.forProject(projectPath);
        getParentFragmentManager().beginTransaction()
                .replace(((ViewGroup) requireView().getParent()).getId(), workbench)
                .addToBackStack(null)
                .commit();
    }

    private void showTransientNotice(String message) {
        binding.transientNotice.setText(message);
        binding.transientNotice.setVisibility(View.VISIBLE);
        binding.transientNotice.postDelayed(
                () -> binding.transientNotice.setVisibility(View.GONE), 4000);
    }

    /** Adaptateur des cartes de projets (une ligne de méta + badge de compatibilité). */
    private final class CardsAdapter
            extends RecyclerView.Adapter<CardsAdapter.CardHolder> {

        @NonNull
        @Override
        public CardHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new CardHolder(ProjectCardItemBinding.inflate(
                    LayoutInflater.from(parent.getContext()), parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull CardHolder holder, int position) {
            ProjectStore.ProjectCard card = cards[position];
            holder.binding.projectName.setText(card.name());
            holder.binding.projectModesBadge.setText(modesLabel(card.compatibleModes()));
            holder.itemView.setOnClickListener(v ->
                    openWorkbench(card.path().toAbsolutePath().toString()));
        }

        @Override
        public int getItemCount() {
            return cards.length;
        }

        final class CardHolder extends RecyclerView.ViewHolder {
            final ProjectCardItemBinding binding;

            CardHolder(ProjectCardItemBinding binding) {
                super(binding.getRoot());
                this.binding = binding;
            }
        }
    }

    private String modesLabel(List<WorkMode> modes) {
        if (modes.isEmpty()) {
            return getString(R.string.projet_modes_inconnu);
        }
        StringBuilder label = new StringBuilder();
        for (WorkMode mode : modes) {
            if (label.length() > 0) {
                label.append(" · ");
            }
            label.append(mode == WorkMode.FORGE
                    ? getString(R.string.mode_forge_nom) : getString(R.string.mode_gradle_nom));
        }
        return label.toString();
    }
}
