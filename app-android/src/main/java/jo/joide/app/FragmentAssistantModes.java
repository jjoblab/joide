package jo.joide.app;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import java.nio.file.Path;
import jo.joide.corestore.ChoixMode;
import jo.joide.corestore.PreferencesModes;
import jo.joide.workbench.ModeTravail;
import jo.joide.app.databinding.FragmentAssistantModesBinding;

/** Assistant du premier lancement : choix Forge / Gradle sans défaut caché (ADR-002). */
public class FragmentAssistantModes extends Fragment {

    private ModeTravail selection = ModeTravail.FORGE;

    /** Vrai tant qu'aucun mode n'a été choisi (l'assistant remplace l'accueil). */
    static boolean nouveauSiPremierLancement(Context contexte) {
        Path fichier = contexte.getFilesDir().toPath().resolve("modes.properties");
        return new PreferencesModes(fichier).lire() == null;
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater gonfler, ViewGroup conteneur,
            Bundle etat) {
        FragmentAssistantModesBinding vue =
                FragmentAssistantModesBinding.inflate(gonfler, conteneur, false);
        vue.carteForge.setOnClickListener(v -> selection = ModeTravail.FORGE);
        vue.carteGradle.setOnClickListener(v -> selection = ModeTravail.GRADLE);
        vue.boutonChoisir.setOnClickListener(v -> {
            try {
                new PreferencesModes(requireContext().getFilesDir().toPath()
                        .resolve("modes.properties"))
                        .ecrire(new ChoixMode(selection, false));
            } catch (Exception e) {
                // journal de l'incident : jamais d'échec silencieux (§4.7)
            }
            getParentFragmentManager().beginTransaction()
                    .replace(((ViewGroup) requireView().getParent()).getId(),
                            new FragmentAccueil())
                    .commit();
        });
        return vue.getRoot();
    }
}
