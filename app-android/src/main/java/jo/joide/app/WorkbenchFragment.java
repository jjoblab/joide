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

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        WorkbenchFragmentBinding binding =
                WorkbenchFragmentBinding.inflate(inflater, container, false);
        // Le câblage des sections (Console/Problèmes/Journal/Logcat) au BuildBackend
        // arrive en L3-L5 (lots restants, docs/limites-et-suite.md).
        return binding.getRoot();
    }
}
