package jo.joide.app;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import jo.joide.app.databinding.WorkbenchFragmentBinding;

/** Atelier : barre, zone éditeur, bottom sheet à quatre sections (fiche ecrans/atelier.md). */
public class WorkbenchFragment extends Fragment {

    private static final String ARG_PROJECT = "project";

    /** Instance sur un projet (chemin absolu du dossier racine du projet). */
    public static WorkbenchFragment forProject(String projectPath) {
        WorkbenchFragment fragment = new WorkbenchFragment();
        Bundle args = new Bundle();
        args.putString(ARG_PROJECT, projectPath);
        fragment.setArguments(args);
        return fragment;
    }

    /** Chemin du projet ouvert (null : gabarit de prévisualisation). */
    @Nullable
    protected String projectPath() {
        return getArguments() != null ? getArguments().getString(ARG_PROJECT) : null;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        WorkbenchFragmentBinding binding =
                WorkbenchFragmentBinding.inflate(inflater, container, false);
        // Le câblage complet des sections arrive en L5 (lots restants, docs/limites-et-suite.md).
        return binding.getRoot();
    }
}
