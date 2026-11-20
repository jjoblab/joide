package jo.joide.app;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.core.content.FileProvider;
import jo.joide.app.databinding.FragmentAccueilBinding;

/** Accueil : projets récents, création, import (fiche ecrans/accueil.md). */
public class FragmentAccueil extends Fragment {

    @Override
    public View onCreateView(@NonNull LayoutInflater gonfler, ViewGroup conteneur,
            Bundle etat) {
        FragmentAccueilBinding vue = FragmentAccueilBinding.inflate(gonfler, conteneur, false);
        vue.boutonNouveau.setOnClickListener(v ->
                getParentFragmentManager().beginTransaction()
                        .replace(((ViewGroup) requireView().getParent()).getId(),
                                new FragmentAtelier())
                        .addToBackStack(null).commit());
        return vue.getRoot();
    }
}
