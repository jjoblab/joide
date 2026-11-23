package jo.joide.app.config;

import android.os.Bundle;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import java.util.function.Consumer;
import jo.joide.app.R;
import jo.joide.app.databinding.SheetTexteBinding;

/**
 * Feuille de saisie texte générique (lot L10) : une seule feuille pour
 * renommer un module, éditer le namespace, la version d'une facette, une
 * racine source personnalisée — jamais de dialogue (règle du propriétaire).
 *
 * <p>Factory {@link #forText(String, String, String, String, Consumer)} ;
 * le consommateur reçoit la valeur saisie (vide = refusé par l'appelant).</p>
 */
public class TextSheet extends BottomSheetDialogFragment {

    private static final String ARG_TITRE = "titre";
    private static final String ARG_MESSAGE = "message";
    private static final String ARG_PRÉREMPLI = "prérempli";
    private static final String ARG_LIBELLÉ_OK = "ok";
    private static final String ARG_NUMÉRIQUE = "numérique";

    private SheetTexteBinding binding;

    /** Saisie texte libre, préremplie, avec un libellé de validation. */
    public static TextSheet forText(String titre, String message, String prérempli,
            String libelléOk, Consumer<String> onOk) {
        return forValue(titre, message, prérempli, libelléOk, false, onOk);
    }

    /** Saisie numérique (versionCode, minSdk… — clavier chiffres). */
    public static TextSheet forNumber(String titre, String message, String prérempli,
            String libelléOk, Consumer<String> onOk) {
        return forValue(titre, message, prérempli, libelléOk, true, onOk);
    }

    private static TextSheet forValue(String titre, String message, String prérempli,
            String libelléOk, boolean numérique, Consumer<String> onOk) {
        TextSheet sheet = new TextSheet();
        Bundle args = new Bundle();
        args.putString(ARG_TITRE, titre);
        args.putString(ARG_MESSAGE, message);
        args.putString(ARG_PRÉREMPLI, prérempli);
        args.putString(ARG_LIBELLÉ_OK, libelléOk);
        args.putBoolean(ARG_NUMÉRIQUE, numérique);
        sheet.setArguments(args);
        sheet.onOk = onOk;
        return sheet;
    }

    private Consumer<String> onOk;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
            @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = SheetTexteBinding.inflate(inflater, container, false);
        Bundle args = getArguments() != null ? getArguments() : new Bundle();
        binding.texteSheetTitre.setText(args.getString(ARG_TITRE, ""));
        binding.texteSheetMessage.setText(args.getString(ARG_MESSAGE, ""));
        binding.texteSheetMessage.setVisibility(
                args.getString(ARG_MESSAGE, "").isEmpty() ? View.GONE : View.VISIBLE);
        binding.texteSheetChamp.setText(args.getString(ARG_PRÉREMPLI, ""));
        if (args.getBoolean(ARG_NUMÉRIQUE)) {
            binding.texteSheetChamp.setInputType(
                    InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED);
        }
        String libelléOk = args.getString(ARG_LIBELLÉ_OK,
                getString(R.string.modules_enregistrer));
        binding.texteSheetOkText.setText(libelléOk);
        binding.texteSheetOk.setOnClickListener(v -> {
            String valeur = binding.texteSheetChamp.getText().toString().trim();
            if (onOk != null) {
                onOk.accept(valeur);
            }
            dismiss();
        });
        return binding.getRoot();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
