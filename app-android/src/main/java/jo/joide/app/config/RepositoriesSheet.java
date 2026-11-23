package jo.joide.app.config;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import jo.joide.app.R;
import jo.joide.app.databinding.SheetDepotsBinding;

/**
 * Feuille « Dépôts Maven » (lot L10, d'après le dialogue Repositories de la
 * référence) : les dépôts RÉELS de {@code forge.json} (retrait direct),
 * quick-add des dépôts connus (Maven Central, Aliyun, JitPack), ajout d'URL
 * libre (une adresse invalide est rejetée — pas de faux dépôt).
 */
public class RepositoriesSheet extends BottomSheetDialogFragment {

    /** Dépôts connus (quick-add) : nom affiché → URL. */
    private static final String[][] DÉPÔTS_CONNUS = {
            {"Maven Central", "https://repo1.maven.org/maven2"},
            {"Aliyun", "https://maven.aliyun.com/repository/central"},
            {"JitPack", "https://jitpack.io"},
    };

    private SheetDepotsBinding binding;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
            @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = SheetDepotsBinding.inflate(inflater, container, false);
        binding.depotsAjouterBouton.setOnClickListener(v -> ajouterUrl());
        rendreConnus();
        rendreListe();
        return binding.getRoot();
    }

    private void rendreListe() {
        Fragment parent = getParentFragment();
        if (!(parent instanceof ModelHost hôte)) {
            return;
        }
        binding.depotsListe.removeAllViews();
        for (String url : hôte.model().repositories()) {
            LinearLayout rangée = new LinearLayout(requireContext());
            rangée.setOrientation(LinearLayout.HORIZONTAL);
            rangée.setGravity(android.view.Gravity.CENTER_VERTICAL);
            rangée.setPadding(dip(14), dip(10), dip(6), dip(10));
            rangée.setBackground(getResources().getDrawable(R.drawable.fond_carte_bas));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.topMargin = dip(6);
            rangée.setLayoutParams(params);

            TextView adresse = new TextView(requireContext());
            adresse.setText(url);
            adresse.setTextColor(getResources().getColor(R.color.sur));
            adresse.setTextSize(12.5f);
            adresse.setTypeface(android.graphics.Typeface.MONOSPACE);
            rangée.addView(adresse, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView retirer = new TextView(requireContext());
            retirer.setText(R.string.depots_retirer);
            retirer.setTextColor(getResources().getColor(R.color.erreur));
            retirer.setTextSize(12.5f);
            retirer.setTypeface(retirer.getTypeface(), android.graphics.Typeface.BOLD);
            retirer.setPadding(dip(10), dip(6), dip(10), dip(6));
            retirer.setOnClickListener(v -> {
                hôte.model().removeRepository(url);
                hôte.mutate("dépôt retiré : " + url);
                rendreListe();
            });
            rangée.addView(retirer);
            binding.depotsListe.addView(rangée);
        }
        if (hôte.model().repositories().isEmpty()) {
            TextView vide = new TextView(requireContext());
            vide.setText(R.string.depots_vide);
            vide.setTextColor(getResources().getColor(R.color.sur_var));
            vide.setTextSize(12.5f);
            binding.depotsListe.addView(vide);
        }
    }

    /** Chips des dépôts connus (quick-add, doublon refusé silencieusement). */
    private void rendreConnus() {
        binding.depotsConnusRow.removeAllViews();
        for (String[] connu : DÉPÔTS_CONNUS) {
            TextView chip = new TextView(requireContext());
            chip.setText(connu[0]);
            chip.setTextColor(getResources().getColor(R.color.sur));
            chip.setTextSize(13f);
            chip.setTypeface(chip.getTypeface(), android.graphics.Typeface.BOLD);
            chip.setGravity(android.view.Gravity.CENTER);
            chip.setPadding(dip(15), 0, dip(15), 0);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dip(38));
            params.rightMargin = dip(8);
            chip.setLayoutParams(params);
            chip.setBackground(getResources().getDrawable(R.drawable.fond_chip));
            chip.setOnClickListener(v -> {
                Fragment parent = getParentFragment();
                if (parent instanceof ModelHost hôte) {
                    if (hôte.model().addRepository(connu[1])) {
                        hôte.mutate("dépôt ajouté : " + connu[0]);
                    }
                    rendreListe();
                }
            });
            binding.depotsConnusRow.addView(chip);
        }
    }

    private void ajouterUrl() {
        Fragment parent = getParentFragment();
        if (!(parent instanceof ModelHost hôte)) {
            dismiss();
            return;
        }
        String url = binding.depotsUrl.getText().toString().trim();
        if (!url.matches("https?://\\S+")) {
            binding.depotsErreur.setText(R.string.depots_url_invalide);
            binding.depotsErreur.setVisibility(View.VISIBLE);
            return;
        }
        if (!hôte.model().addRepository(url)) {
            binding.depotsErreur.setText(R.string.depots_doublon);
            binding.depotsErreur.setVisibility(View.VISIBLE);
            return;
        }
        binding.depotsErreur.setVisibility(View.GONE);
        binding.depotsUrl.setText("");
        hôte.mutate("dépôt ajouté : " + url);
        rendreListe();
    }

    private int dip(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
