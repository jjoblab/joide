package jo.joide.app;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import jo.joide.app.databinding.FragmentAtelierBinding;

/** Atelier : barre, zone éditeur, bottom sheet à quatre sections (fiche ecrans/atelier.md). */
public class FragmentAtelier extends Fragment {

    @Override
    public View onCreateView(@NonNull LayoutInflater gonfler, ViewGroup conteneur,
            Bundle etat) {
        FragmentAtelierBinding vue = FragmentAtelierBinding.inflate(gonfler, conteneur, false);
        // Le câblage des sections (Console/Problèmes/Journal/Logcat) au BuildBackend
        // arrive en L3-L5 (lots restants, docs/limites-et-suite.md).
        return vue.getRoot();
    }
}
