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
import jo.joide.corestore.ModeChoice;
import jo.joide.corestore.ModePreferences;
import jo.joide.workbench.WorkMode;

/** Assistant du premier lancement : choix Forge / Gradle sans défaut caché (ADR-002). */
public class ModeSetupFragment extends Fragment {

    private WorkMode selection = WorkMode.FORGE;

    /** Fichier du choix de mode propre à ce contexte (stockage privé de l'application). */
    static Path preferencesFile(Context context) {
        return context.getFilesDir().toPath().resolve("modes.properties");
    }

    /** Vrai tant qu'aucun mode n'a été choisi (l'assistant remplace l'accueil). */
    static boolean isRequired(Context context) {
        return new ModePreferences(preferencesFile(context)).read() == null;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        ModeSetupFragmentBinding binding =
                ModeSetupFragmentBinding.inflate(inflater, container, false);
        binding.forgeCard.setOnClickListener(v -> selection = WorkMode.FORGE);
        binding.gradleCard.setOnClickListener(v -> selection = WorkMode.GRADLE);
        binding.selectButton.setOnClickListener(v -> {
            try {
                new ModePreferences(preferencesFile(requireContext()))
                        .write(new ModeChoice(selection, false));
            } catch (Exception e) {
                // journal de l'incident : jamais d'échec silencieux (§4.7) — câblage L8.
            }
            getParentFragmentManager().beginTransaction()
                    .replace(((ViewGroup) requireView().getParent()).getId(),
                            new HomeFragment())
                    .commit();
        });
        return binding.getRoot();
    }
}
