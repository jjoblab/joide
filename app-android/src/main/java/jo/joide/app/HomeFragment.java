package jo.joide.app;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import jo.joide.app.databinding.HomeFragmentBinding;

/** Accueil : projets récents, création, import (fiche ecrans/accueil.md). */
public class HomeFragment extends Fragment {

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        HomeFragmentBinding binding =
                HomeFragmentBinding.inflate(inflater, container, false);
        // Ouvre l'atelier (placeholder jusqu'à la sélection réelle d'un projet, L4).
        binding.newProjectButton.setOnClickListener(v ->
                getParentFragmentManager().beginTransaction()
                        .replace(((ViewGroup) requireView().getParent()).getId(),
                                new WorkbenchFragment())
                        .addToBackStack(null)
                        .commit());
        return binding.getRoot();
    }
}
