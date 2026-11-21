package jo.joide.app.drawer;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;
import androidx.lifecycle.ViewModelProvider;
import jo.joide.app.R;
import jo.joide.app.databinding.LeftDrawerFragmentBinding;

/**
 * Tiroir gauche de l'atelier (Lot 0 : extraction sans régression) : le
 * sélecteur Files · Chercher · Structure · Git et le contenu par onglet,
 * sortis de {@code WorkbenchFragment} sans changement de comportement.
 *
 * <p>L'arborescence vit dans {@link FilesTabFragment} (déplacée telle
 * quelle) ; Chercher et Structure affichent un état « à venir » honnête ;
 * Git ouvre l'écran Git plein écran comme avant. Les onglets sont des
 * fragments enfants **conservés** (show/hide : pas de rechargement, l'état
 * de défilement survit aux changements d'onglet) ; l'onglet choisi survit à
 * la rotation. Le tiroir reste **poussant** (contenu décalé, jamais de
 * voile) — la mécanique vit dans {@code WorkbenchFragment.setupDrawers()}.</p>
 */
public final class LeftDrawerFragment extends Fragment {

    private static final String ARG_PROJECT = "project";
    private static final String STATE_TAB = "current_tab";

    /** Tiroir sur le projet ouvert. */
    public static LeftDrawerFragment forProject(String projectPath) {
        LeftDrawerFragment fragment = new LeftDrawerFragment();
        Bundle args = new Bundle();
        args.putString(ARG_PROJECT, projectPath);
        fragment.setArguments(args);
        return fragment;
    }

    private LeftDrawerFragmentBinding binding;
    private WorkspaceViewModel viewModel;
    private int currentTab = R.id.drawer_tab_files;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        binding = LeftDrawerFragmentBinding.inflate(inflater, container, false);
        viewModel = new ViewModelProvider(this).get(WorkspaceViewModel.class);
        // Lot 8 : bus partagé de l'atelier — l'arbre voit les mutations de
        // l'agent et les changements de fichiers, pas seulement les siennes.
        if (requireParentFragment() instanceof jo.joide.app.WorkbenchFragment workbench) {
            viewModel.attachEvents(workbench.workspaceEvents());
        }
        viewModel.init(projectPath());

        // Sélecteur : trois onglets hébergés ; Git ouvre l'écran Git (comme
        // avant — le client Git dans le tiroir arrive au lot Git).
        binding.drawerTabFiles.setOnClickListener(v -> selectTab(R.id.drawer_tab_files));
        binding.drawerTabSearch.setOnClickListener(v -> selectTab(R.id.drawer_tab_search));
        binding.drawerTabStructure.setOnClickListener(v -> selectTab(R.id.drawer_tab_structure));
        binding.drawerTabGit.setOnClickListener(v -> host().openGitScreen());
        binding.closeLeft.setOnClickListener(v -> host().closeLeftDrawer());

        int restored = savedInstanceState != null
                ? savedInstanceState.getInt(STATE_TAB, R.id.drawer_tab_files)
                : R.id.drawer_tab_files;
        selectTab(restored);
        return binding.getRoot();
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(STATE_TAB, currentTab);
    }

    /** Onglet hébergé visible ; les autres sont cachés (état conservé). */
    private void selectTab(int tabId) {
        if (tabId != R.id.drawer_tab_files && tabId != R.id.drawer_tab_search
                && tabId != R.id.drawer_tab_structure) {
            tabId = R.id.drawer_tab_files;
        }
        currentTab = tabId;
        renderTabChips();
        String tag = String.valueOf(tabId);
        Fragment target = getChildFragmentManager().findFragmentByTag(tag);
        if (target == null) {
            target = fragmentFor(tabId);
            getChildFragmentManager().beginTransaction()
                    .add(R.id.tab_container, target, tag)
                    .commitNow();
        }
        FragmentTransaction transaction = getChildFragmentManager().beginTransaction();
        for (Fragment fragment : getChildFragmentManager().getFragments()) {
            if (fragment == target) {
                transaction.show(fragment);
            } else {
                transaction.hide(fragment);
            }
        }
        transaction.commitNow();
    }

    /**
     * Ouvre un onglet précis depuis l'atelier (Lot 4 : le bouton de
     * recherche du dock ouvre « Chercher » — finis les toasts).
     */
    public void openTab(int tabId) {
        if (tabId == R.id.drawer_tab_search || tabId == R.id.drawer_tab_structure
                || tabId == R.id.drawer_tab_files) {
            selectTab(tabId);
        }
    }

    /** Fabrique l'onglet hébergé (Git n'est jamais hébergé au Lot 0). */
    private Fragment fragmentFor(int tabId) {
        if (tabId == R.id.drawer_tab_search) {
            return new SearchTabFragment();
        }
        if (tabId == R.id.drawer_tab_structure) {
            return new StructureTabFragment();
        }
        return FilesTabFragment.newInstance();
    }

    /** Chip de l'onglet actif en ON (sc2/sur_sc2), les autres au repos. */
    private void renderTabChips() {
        styleTab(binding.drawerTabFiles, currentTab == R.id.drawer_tab_files);
        styleTab(binding.drawerTabSearch, currentTab == R.id.drawer_tab_search);
        styleTab(binding.drawerTabStructure, currentTab == R.id.drawer_tab_structure);
        // Git n'est pas un onglet hébergé : sa chip reste au repos (comportement
        // conservé — elle ouvre l'écran Git).
        styleTab(binding.drawerTabGit, false);
    }

    private void styleTab(TextView chip, boolean on) {
        chip.setBackground(getResources().getDrawable(
                on ? R.drawable.fond_chip_on : R.drawable.fond_chip));
        chip.setTextColor(getResources().getColor(
                on ? R.color.sur_sc2 : R.color.sur));
    }

    /** L'hôte du tiroir est l'atelier (contrat WorkbenchHost). */
    private WorkbenchHost host() {
        return (WorkbenchHost) requireParentFragment();
    }

    @Nullable
    private String projectPath() {
        return getArguments() != null ? getArguments().getString(ARG_PROJECT, null) : null;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
