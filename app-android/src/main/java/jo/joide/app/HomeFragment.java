package jo.joide.app;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import jo.joide.app.R;
import jo.joide.app.databinding.HomeFragmentBinding;
import jo.joide.corestore.ProjectStore;
import jo.joide.corestore.ProjectTemplates;
import jo.joide.workbench.WorkMode;

/**
 * Accueil (studio HTML, onglet « Accueil », rendu 1:1) : titre + bell + avatar,
 * chips de filtre avec compteurs, cartes de projets à pastille (prune Forge /
 * conteneur secondaire Gradle) + méta de dernière ouverture, CTA asymétrique
 * 64 dp + bouton tonal « Plus », état vide pointillé, navbar du bas. Le
 * stockage reste le vrai ({@link ProjectStore}, ADR-010) ; le clonage git vit
 * sur l'écran Git (studio : popup ancrée à l'accueil).
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

        // Avatar du studio : entrée Paramètres (hub en sections).
        binding.avatarButton.setOnClickListener(v ->
                getParentFragmentManager().beginTransaction()
                        .replace(((ViewGroup) requireView().getParent()).getId(),
                                new jo.joide.app.settings.SettingsFragment())
                        .addToBackStack(null).commit());
        // Cloche du studio : honnête (aucune notification câblée dans ce lot).
        binding.bellButton.setOnClickListener(v ->
                showTransientNotice(getString(R.string.accueil_nav_bientot)));

        binding.newProjectButton.setOnClickListener(v ->
                getParentFragmentManager().beginTransaction()
                        .replace(((ViewGroup) requireView().getParent()).getId(),
                                new CreateProjectFragment())
                        .addToBackStack(null)
                        .commit());
        binding.moreButton.setOnClickListener(v ->
                // « Plus » du studio : import + clonage annoncés là où ils vivent.
                showTransientNotice(getString(R.string.import_non_cable_encore)));
        setupFilters();
        setupNavbar();
        refresh();
        return binding.getRoot();
    }

    // ---- Filtres par mode (studio : chips 38dp avec compteurs, ON = sc2) ----

    private void setupFilters() {
        List<ProjectStore.ProjectCard> all = store.list();
        binding.filterRow.setVisibility(all.isEmpty() ? View.GONE : View.VISIBLE);
        binding.filterAllText.setText(getString(R.string.accueil_filtre_tous_sans));
        binding.filterAllCount.setText(String.valueOf(all.size()));
        int forge = 0;
        int gradle = 0;
        for (ProjectStore.ProjectCard card : all) {
            if (card.compatibleModes().contains(WorkMode.FORGE)) {
                forge++;
            }
            if (card.compatibleModes().contains(WorkMode.GRADLE)) {
                gradle++;
            }
        }
        binding.filterForgeCount.setText(String.valueOf(forge));
        binding.filterGradleCount.setText(String.valueOf(gradle));
        binding.filterAll.setOnClickListener(v -> applyFilter(null));
        binding.filterForge.setOnClickListener(v -> applyFilter(WorkMode.FORGE));
        binding.filterGradle.setOnClickListener(v -> applyFilter(WorkMode.GRADLE));
    }

    private void applyFilter(WorkMode mode) {
        filter = mode;
        List<ProjectStore.ProjectCard> list = store.list();
        cards = list.stream()
                .filter(c -> mode == null || c.compatibleModes().contains(mode))
                .toArray(ProjectStore.ProjectCard[]::new);
        // Chip sélectionnée : fond sc2 (studio .chip.on).
        binding.filterAll.setBackground(
                mode == null ? getResources().getDrawable(R.drawable.fond_chip_on) : null);
        binding.filterForge.setBackground(
                mode == WorkMode.FORGE ? getResources().getDrawable(R.drawable.fond_chip_on) : null);
        binding.filterGradle.setBackground(
                mode == WorkMode.GRADLE ? getResources().getDrawable(R.drawable.fond_chip_on) : null);
        renderCards();
    }

    /** Recharge la liste (le filtre courant s'applique de nouveau). */
    private void refresh() {
        applyFilter(filter);
    }

    // ---- Cartes de projets (studio : sc_low, pastille tc/sc2, méta) ---------

    private void renderCards() {
        binding.projectsList.removeAllViews();
        for (ProjectStore.ProjectCard card : cards) {
            binding.projectsList.addView(projectCard(card));
        }
        boolean empty = cards.length == 0;
        binding.emptyStateView.setVisibility(empty ? View.VISIBLE : View.GONE);
    }

    /** Une carte projet : pastille au ton du mode (prune Forge / sc2 Gradle). */
    private View projectCard(ProjectStore.ProjectCard card) {
        LinearLayout cardView = new LinearLayout(requireContext());
        cardView.setOrientation(LinearLayout.VERTICAL);
        cardView.setBackground(getResources().getDrawable(R.drawable.fond_carte_bas));
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardParams.topMargin = dip(10);
        cardView.setLayoutParams(cardParams);

        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(dip(12), dip(14), dip(12), dip(12));
        row.setOnClickListener(v ->
                openWorkbench(card.path().toAbsolutePath().toString()));

        boolean forge = card.compatibleModes().contains(WorkMode.FORGE)
                && !card.compatibleModes().contains(WorkMode.GRADLE);
        ImageView pastille = new ImageView(requireContext());
        pastille.setBackground(getResources().getDrawable(forge
                ? R.drawable.fond_pastille_tertiaire : R.drawable.fond_pastille_sc2));
        pastille.setImageResource(forge ? R.drawable.ic_code : R.drawable.ic_wb);
        pastille.setColorFilter(getResources().getColor(
                forge ? R.color.sur_conteneur_tertiaire : R.color.sur_sc2));
        row.addView(pastille, new LinearLayout.LayoutParams(dip(40), dip(40)));

        LinearLayout texts = new LinearLayout(requireContext());
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textsParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        textsParams.leftMargin = dip(14);
        texts.setLayoutParams(textsParams);
        TextView name = new TextView(requireContext());
        name.setText(card.name());
        name.setTextColor(getResources().getColor(R.color.sur));
        name.setTextSize(15f);
        name.setTypeface(name.getTypeface(), android.graphics.Typeface.BOLD);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        texts.addView(name);
        TextView meta = new TextView(requireContext());
        meta.setText(getString(R.string.projet_meta_studio,
                modesLabel(card.compatibleModes()),
                relative(Instant.ofEpochMilli(card.lastModified()))));
        meta.setTextColor(getResources().getColor(R.color.sur_var));
        meta.setTextSize(12.5f);
        meta.setSingleLine(true);
        meta.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams metaParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        metaParams.topMargin = dip(2);
        meta.setLayoutParams(metaParams);
        texts.addView(meta);
        row.addView(texts);

        // Menu par projet (studio : ⋯ en fin de rangée).
        ImageView menu = new ImageView(requireContext());
        menu.setImageResource(R.drawable.ic_dots);
        menu.setColorFilter(getResources().getColor(R.color.sur_var));
        LinearLayout.LayoutParams menuParams = new LinearLayout.LayoutParams(dip(40), dip(40));
        menuParams.leftMargin = dip(8);
        menu.setLayoutParams(menuParams);
        menu.setOnClickListener(v ->
                showTransientNotice(getString(R.string.projet_menu_bientot)));
        row.addView(menu);

        cardView.addView(row);
        return cardView;
    }

    /** « Forge · 2 modules » — modes compatibles réels du détecteur. */
    private String modesLabel(List<WorkMode> modes) {
        StringBuilder label = new StringBuilder();
        for (WorkMode mode : modes) {
            if (label.length() > 0) {
                label.append(" · ");
            }
            label.append(mode == WorkMode.FORGE
                    ? getString(R.string.mode_forge_nom) : getString(R.string.mode_gradle_nom));
        }
        return label.length() > 0 ? label.toString()
                : getString(R.string.projet_modes_inconnu);
    }

    /** « ouvert il y a 2 h » — âge relatif de la dernière modification réelle. */
    private static String relative(Instant when) {
        long minutes = Duration.between(when, Instant.now()).toMinutes();
        if (minutes < 60) {
            return String.format(Locale.getDefault(), "%d min", Math.max(1, minutes));
        }
        if (minutes < 24 * 60) {
            return String.format(Locale.getDefault(), "%d h", minutes / 60);
        }
        return String.format(Locale.getDefault(), "%d j", minutes / (24 * 60));
    }

    // ---- Navbar (studio : Projets actif, IA → assistant, Apprendre/Défis) ----

    private void setupNavbar() {
        binding.navIa.setOnClickListener(v ->
                getParentFragmentManager().beginTransaction()
                        .replace(((ViewGroup) requireView().getParent()).getId(),
                                jo.joide.app.ai.AiPanelFragment.assistant())
                        .addToBackStack(null).commit());
        binding.navApprendre.setOnClickListener(v ->
                showTransientNotice(getString(R.string.accueil_nav_bientot)));
        binding.navDefis.setOnClickListener(v ->
                showTransientNotice(getString(R.string.accueil_nav_bientot)));
    }

    // ---- Création (studio : écran « Création », plein écran à 2 étapes) ------

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

    private int dip(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }
}
