package jo.joide.app.drawer;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import jo.joide.app.R;

/**
 * Onglet Structure du tiroir gauche (Lot 0) : état « à venir » honnête — le
 * plan du fichier actif (Java, Kotlin, XML) arrive au lot Structure ; rien
 * n'est affiché qui ne soit réel.
 */
public final class StructureTabFragment extends Fragment {

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.structure_tab_fragment, container, false);
    }
}
