package jo.joide.app.config;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import java.util.function.Runnable;
import jo.joide.app.R;
import jo.joide.app.databinding.SheetConfirmeBinding;

/**
 * Feuille de confirmation (lot L10) : suppression d'un module ou d'une
 * dépendance — carte de message + Annuler / action destructrice (rouge),
 * comme la carte « verre » de la référence. Jamais un AlertDialog.
 */
public class ConfirmSheet extends BottomSheetDialogFragment {

    private static final String ARG_TITRE = "titre";
    private static final String ARG_MESSAGE = "message";
    private static final String ARG_ACTION = "action";

    private SheetConfirmeBinding binding;

    private Runnable onConfirm;

    /** Confirmation destructrice (l'action est en rouge). */
    public static ConfirmSheet destructif(String titre, String message,
            String libelléAction, Runnable onConfirm) {
        ConfirmSheet sheet = new ConfirmSheet();
        Bundle args = new Bundle();
        args.putString(ARG_TITRE, titre);
        args.putString(ARG_MESSAGE, message);
        args.putString(ARG_ACTION, libelléAction);
        sheet.setArguments(args);
        sheet.onConfirm = onConfirm;
        return sheet;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
            @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = SheetConfirmeBinding.inflate(inflater, container, false);
        Bundle args = getArguments() != null ? getArguments() : new Bundle();
        binding.confirmeTitre.setText(args.getString(ARG_TITRE, ""));
        binding.confirmeMessage.setText(args.getString(ARG_MESSAGE, ""));
        binding.confirmeActionText.setText(
                args.getString(ARG_ACTION, getString(R.string.modules_confirme_action)));
        binding.confirmeAnnuler.setOnClickListener(v -> dismiss());
        binding.confirmeAction.setOnClickListener(v -> {
            dismiss();
            if (onConfirm != null) {
                onConfirm.run();
            }
        });
        return binding.getRoot();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
