package jo.joide.app.config;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import java.util.List;
import jo.joide.app.R;
import jo.joide.app.databinding.SheetSymbolesBinding;
import jo.joide.app.drawer.WorkbenchHost;
import jo.joide.index.IndexService;

/**
 * Feuille « Symboles du projet » (lot L10) : le CONSOMMATEUR de l'index à
 * l'ouverture — recherche par préfixe des symboles déclarés (classes,
 * interfaces, méthodes, champs…), tap = saut à la ligne dans l'éditeur.
 *
 * <p>Repli honnête : tant qu'aucun pass complet n'a fini (index non prêt),
 * les requêtes rendent VIDE — la feuille le dit (« index en préparation »),
 * jamais de faux résultats ; le tiroir de recherche plein texte reste la
 * porte de secours.</p>
 */
public class SymbolsSheet extends BottomSheetDialogFragment {

    private SheetSymbolesBinding binding;

    private final IndexService index;
    private final WorkbenchHost hôte;

    /** Feuille sur l'index du projet ouvert (peut être null : repli dit). */
    public SymbolsSheet(@Nullable IndexService index, @Nullable WorkbenchHost hôte) {
        this.index = index;
        this.hôte = hôte;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
            @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = SheetSymbolesBinding.inflate(inflater, container, false);
        binding.symbolesChamp.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }

            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }

            @Override public void afterTextChanged(Editable s) {
                rendre();
            }
        });
        rendre();
        return binding.getRoot();
    }

    private void rendre() {
        binding.symbolesListe.removeAllViews();
        String préfixe = binding.symbolesChamp.getText().toString().trim();
        if (préfixe.isEmpty()) {
            état(getString(R.string.symboles_prefixe_vide), R.color.sur_var,
                    R.drawable.fond_carte_bas);
            return;
        }
        if (index == null || !index.status().ready()) {
            // Repli honnête : pas de pass complet → pas de résultats.
            état(getString(index == null
                            ? R.string.symboles_index_absent
                            : R.string.symboles_index_en_preparation),
                    R.color.sur_conteneur_erreur, R.drawable.fond_carte_alerte);
            return;
        }
        List<IndexService.SymbolHit> symboles = index.searchSymbols(préfixe, 40);
        if (symboles.isEmpty()) {
            état(getString(R.string.symboles_aucun), R.color.sur_var,
                    R.drawable.fond_carte_bas);
            return;
        }
        binding.symbolesEtat.setVisibility(View.GONE);
        for (IndexService.SymbolHit symbole : symboles) {
            binding.symbolesListe.addView(rangée(symbole));
        }
    }

    /** Une rangée de symbole : nom + signature mono + fichier:ligne ; tap = saut. */
    private View rangée(IndexService.SymbolHit symbole) {
        LinearLayout rangée = new LinearLayout(requireContext());
        rangée.setOrientation(LinearLayout.VERTICAL);
        rangée.setPadding(dip(14), dip(10), dip(14), dip(10));
        rangée.setBackground(getResources().getDrawable(R.drawable.fond_carte_bas));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dip(6);
        rangée.setLayoutParams(params);
        rangée.setOnClickListener(v -> {
            if (hôte != null) {
                hôte.openFileAt(symbole.file(), symbole.line(), 1, 0);
            }
            dismiss();
        });

        TextView nom = new TextView(requireContext());
        nom.setText(symbole.name());
        nom.setTextColor(getResources().getColor(R.color.sur));
        nom.setTextSize(14f);
        nom.setTypeface(nom.getTypeface(), android.graphics.Typeface.BOLD);
        rangée.addView(nom);

        TextView signature = new TextView(requireContext());
        signature.setText(symbole.signature());
        signature.setTextColor(getResources().getColor(R.color.sur_var));
        signature.setTextSize(11.5f);
        signature.setTypeface(android.graphics.Typeface.MONOSPACE);
        signature.setSingleLine(true);
        signature.setEllipsize(android.text.TextUtils.TruncateAt.END);
        rangée.addView(signature);

        TextView lieu = new TextView(requireContext());
        lieu.setText(symbole.file().getFileName() + " · ligne " + symbole.line());
        lieu.setTextColor(ContextCompat.getColor(requireContext(), R.color.sur_var));
        lieu.setTextSize(11f);
        lieu.setGravity(Gravity.END);
        rangée.addView(lieu);
        return rangée;
    }

    private void état(String message, int couleur, int fond) {
        binding.symbolesEtat.setText(message);
        binding.symbolesEtat.setTextColor(
                ContextCompat.getColor(requireContext(), couleur));
        binding.symbolesEtat.setBackground(
                ContextCompat.getDrawable(requireContext(), fond));
        binding.symbolesEtat.setVisibility(View.VISIBLE);
    }

    private int dip(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }

    /** L'hôte (fragment parent) auquel la feuille appartient. */
    @Nullable
    protected Fragment hôteFragment() {
        return getParentFragment();
    }
}
