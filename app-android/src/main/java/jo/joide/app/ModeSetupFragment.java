package jo.joide.app;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import java.nio.file.Path;
import jo.joide.app.databinding.ModeSetupFragmentBinding;
import jo.joide.corestore.ModeSetupModel;
import jo.joide.workbench.WorkMode;

/**
 * Assistant du premier lancement : quatre pages (bienvenue → Forge → Gradle →
 * confirmation, ADR-002) sans défaut caché. La sélection est provisoire jusqu'à la
 * confirmation, qui persiste le choix via {@link ModeSetupModel} ; l'installation du
 * mode choisi est un acte séparé (paramètres, L8) — affichée honnêtement, jamais
 * devinée. Les quatre pages vivent dans un seul gabarit dont les sections changent
 * de visibilité : aucune dépendance nouvelle pour un pager (règle de dépendances).
 */
public class ModeSetupFragment extends Fragment {

    private ModeSetupFragmentBinding binding;
    private ModeSetupModel model;
    private WorkMode provisionalSelection;

    /** Fichier du choix de mode propre à ce contexte (stockage privé de l'application). */
    /** Fichier des préférences de mode (partagé avec les paramètres L8 : ModeManager). */
    public static Path preferencesFile(Context context) {
        return context.getFilesDir().toPath().resolve("modes.properties");
    }

    /** Vrai tant qu'aucun mode n'a été choisi (l'assistant remplace l'accueil). */
    static boolean isRequired(Context context) {
        return new ModeSetupModel(preferencesFile(context)).isSetupRequired();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        binding = ModeSetupFragmentBinding.inflate(inflater, container, false);
        model = new ModeSetupModel(preferencesFile(requireContext()));

        // Page bienvenue : bouton « Commencer » vers la présentation des modes.
        binding.welcomeNextButton.setOnClickListener(v -> showPage(ModeSetupModel.Page.FORGE_PAGE));
        // Page Forge : apports et limites honnêtes (ressources), sélection possible.
        binding.forgeNextButton.setOnClickListener(v -> showPage(ModeSetupModel.Page.GRADLE_PAGE));
        binding.forgeSelectButton.setOnClickListener(v -> {
            provisionalSelection = WorkMode.FORGE;
            showPage(ModeSetupModel.Page.CONFIRM);
        });
        // Page Gradle : apports et limites honnêtes, sélection possible.
        binding.gradleSelectButton.setOnClickListener(v -> {
            provisionalSelection = WorkMode.GRADLE;
            showPage(ModeSetupModel.Page.CONFIRM);
        });
        binding.gradleBackButton.setOnClickListener(v -> showPage(ModeSetupModel.Page.FORGE_PAGE));
        // Confirmation : persiste le choix puis bascule vers l'accueil.
        binding.confirmButton.setOnClickListener(v -> confirmSelection());
        binding.confirmBackButton.setOnClickListener(v -> showPage(ModeSetupModel.Page.GRADLE_PAGE));

        showPage(ModeSetupModel.Page.WELCOME);
        return binding.getRoot();
    }

    /** Affiche une page de l'assistant (une seule visible, l'état est purement local). */
    private void showPage(ModeSetupModel.Page page) {
        binding.welcomeSection.setVisibility(page == ModeSetupModel.Page.WELCOME ? View.VISIBLE : View.GONE);
        binding.forgeSection.setVisibility(page == ModeSetupModel.Page.FORGE_PAGE ? View.VISIBLE : View.GONE);
        binding.gradleSection.setVisibility(page == ModeSetupModel.Page.GRADLE_PAGE ? View.VISIBLE : View.GONE);
        binding.confirmSection.setVisibility(page == ModeSetupModel.Page.CONFIRM ? View.VISIBLE : View.GONE);
    }

    /** Confirmation : persiste le choix, puis bascule vers l'accueil. */
    private void confirmSelection() {
        if (provisionalSelection == null) {
            // Aucun mode sélectionné : retour à la présentation (pas de défaut caché).
            showPage(ModeSetupModel.Page.FORGE_PAGE);
            return;
        }
        try {
            model.select(provisionalSelection);
            model.confirmChoice();
        } catch (Exception e) {
            // Échec de persistance : l'assistant reste affiché (jamais d'échec
            // silencieux §4.7) ; le journal d'incident est câblé en L8.
            return;
        }
        // Studio HTML : la confirmation mène à l'écran d'installation du mode
        // (composants à tailles, progression annulable, phases vérifiées).
        getParentFragmentManager().beginTransaction()
                .replace(((ViewGroup) requireView().getParent()).getId(),
                        jo.joide.app.install.InstallFragment.forMode(provisionalSelection))
                .commit();
    }
}
