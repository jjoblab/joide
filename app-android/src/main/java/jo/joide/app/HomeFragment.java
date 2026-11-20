package jo.joide.app;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
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

        binding.newProjectButton.setOnClickListener(v -> createFromTemplate("forge-java"));
        binding.fabNewProject.setOnClickListener(v -> createFromTemplate("forge-java"));
        binding.importProjectButton.setOnClickListener(v -> {
            // Import SAF (fiche accueil) : câblé avec les permissions en L8 ; le
            // bouton reste honnête (action annoncée non câblée, jamais un échec muet).
            showTransientNotice(getString(R.string.import_non_cable_encore));
        });
        return binding.getRoot();
    }

    /** Recharge la liste (après création, suppression, retour à l'accueil). */
    private void refresh() {
        List<ProjectStore.ProjectCard> list = store.list();
        cards = list.toArray(new ProjectStore.ProjectCard[0]);
        binding.projectsRecycler.setAdapter(new CardsAdapter());
        boolean empty = cards.length == 0;
        binding.emptyStateView.setVisibility(empty ? View.VISIBLE : View.GONE);
        binding.projectsRecycler.setVisibility(empty ? View.GONE : View.VISIBLE);
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
