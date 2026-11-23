package jo.joide.app.config;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import java.io.PrintWriter;
import java.io.StringWriter;
import jo.joide.app.R;
import jo.joide.app.databinding.SheetErreurBinding;

/**
 * Feuille d'erreur non fatale (lot L10, d'après le dialogue d'erreur de la
 * référence — REF-130) : titre, message, « Voir les détails » qui déplie la
 * trace sélectionnable (monospace), « Ignorer ». Une feuille — jamais un
 * dialogue (règle du propriétaire). Réutilisable partout :
 * {@code ErrorSheet.montrer(getChildFragmentManager(), …)}.
 */
public class ErrorSheet extends BottomSheetDialogFragment {

    private SheetErreurBinding binding;

    /**
     * Montre une erreur non fatale (l'app reste vivante).
     *
     * @param erreur l'exception (trace dépliée), peut être null.
     */
    public static void montrer(androidx.fragment.app.FragmentManager manager,
            String titre, String message, @Nullable Throwable erreur) {
        ErrorSheet sheet = new ErrorSheet();
        Bundle args = new Bundle();
        args.putString("titre", titre);
        args.putString("message", message);
        if (erreur != null) {
            StringWriter trace = new StringWriter();
            erreur.printStackTrace(new PrintWriter(trace));
            args.putString("trace", trace.toString());
        }
        sheet.setArguments(args);
        sheet.show(manager, "erreur-non-fatale");
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
            @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = SheetErreurBinding.inflate(inflater, container, false);
        Bundle args = getArguments() != null ? getArguments() : new Bundle();
        binding.erreurFeuilleTitre.setText(args.getString("titre", ""));
        binding.erreurFeuilleMessage.setText(args.getString("message", ""));
        String trace = args.getString("trace");
        if (trace == null || trace.isBlank()) {
            binding.erreurFeuilleVoir.setVisibility(View.GONE);
        } else {
            binding.erreurFeuilleDetails.setText(trace);
            binding.erreurFeuilleVoir.setOnClickListener(v -> {
                boolean visible = binding.erreurFeuilleDetailsZone
                        .getVisibility() == View.VISIBLE;
                binding.erreurFeuilleDetailsZone.setVisibility(
                        visible ? View.GONE : View.VISIBLE);
                binding.erreurFeuilleVoir.setText(visible
                        ? R.string.erreur_voir_details : R.string.erreur_masquer_details);
            });
        }
        binding.erreurFeuilleIgnorer.setOnClickListener(v -> dismiss());
        return binding.getRoot();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
