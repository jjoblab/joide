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
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import jo.joide.app.R;
import jo.joide.app.databinding.SheetEditionDepBinding;
import jo.joide.buildforge.MavenCentralSearch;
import jo.joide.coremodel.ForgeJson;

/**
 * Feuille « Éditer une dépendance » (lot L10, d'après la feuille d'édition de
 * la référence) : version (champ + **versions publiées** chargées du solr
 * Maven Central — tap pour choisir), portée (chips), suppression derrière
 * une confirmation rouge (feuille {@link ConfirmSheet}, jamais un dialogue).
 * Une dépendance de projet n'a pas de version : la feuille le constate.
 */
public class EditDependencySheet extends BottomSheetDialogFragment {

    private static final String[] PORTÉES = {"implementation", "api",
            "compileOnly", "runtimeOnly"};

    private SheetEditionDepBinding binding;

    private final MavenCentralSearch recherche = new MavenCentralSearch();

    private final String module;
    private final ForgeJson.Dependency dépendance;

    private int portéeChoisie;

    /** Feuille pour une dépendance d'un module (portée courante présélectionnée). */
    public EditDependencySheet(String module, ForgeJson.Dependency dépendance) {
        this.module = module;
        this.dépendance = dépendance;
        for (int i = 0; i < PORTÉES.length; i++) {
            if (PORTÉES[i].equals(dépendance.scope())) {
                portéeChoisie = i;
            }
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
            @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = SheetEditionDepBinding.inflate(inflater, container, false);

        binding.editeurDepCoordonnee.setText(dépendance.display());
        boolean maven = "maven".equals(dépendance.kind());
        binding.editeurDepSousTitre.setText(getString(maven
                ? R.string.dep_editer_sous_titre : R.string.dep_editer_projet));
        binding.editeurDepVersion.setEnabled(maven);
        binding.editeurDepVersions.setVisibility(maven ? View.VISIBLE : View.GONE);
        binding.editeurDepVersion.setText(dépendance.version() == null
                ? "" : dépendance.version());
        rendreChipsPortées();
        if (maven) {
            chargerVersionsPubliées();
        }

        binding.editeurDepEnregistrer.setOnClickListener(v -> enregistrer());
        binding.editeurDepSupprimer.setOnClickListener(v -> confirmerSuppression());
        return binding.getRoot();
    }

    /** Versions publiées de la coordonnée (tap = version choisie). */
    private void chargerVersionsPubliées() {
        new Thread(() -> {
            List<MavenCentralSearch.Version> publiées;
            try {
                publiées = recherche.versions(dépendance.group(),
                        dépendance.artifact(), 8);
            } catch (IOException e) {
                publiées = List.of();
            }
            List<MavenCentralSearch.Version> versions = publiées;
            if (binding == null) {
                return;
            }
            binding.editeurDepVersions.post(() -> {
                if (binding == null) {
                    return;
                }
                binding.editeurDepVersions.removeAllViews();
                if (versions.isEmpty()) {
                    TextView indisponible = new TextView(requireContext());
                    indisponible.setText(R.string.dep_versions_indisponibles);
                    indisponible.setTextColor(
                            getResources().getColor(R.color.sur_var));
                    indisponible.setTextSize(12f);
                    binding.editeurDepVersions.addView(indisponible);
                    return;
                }
                for (MavenCentralSearch.Version version : versions) {
                    TextView rangée = new TextView(requireContext());
                    rangée.setText(version.version());
                    rangée.setTextColor(getResources().getColor(R.color.sur));
                    rangée.setTextSize(13f);
                    rangée.setTypeface(rangée.getTypeface(),
                            android.graphics.Typeface.BOLD);
                    rangée.setPadding(dip(12), dip(9), dip(12), dip(9));
                    rangée.setBackground(
                            getResources().getDrawable(R.drawable.fond_carte_bas));
                    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT);
                    params.topMargin = dip(6);
                    rangée.setLayoutParams(params);
                    rangée.setOnClickListener(v -> {
                        binding.editeurDepVersion.setText(version.version());
                    });
                    binding.editeurDepVersions.addView(rangée);
                }
            });
        }, "versions-maven").start();
    }

    private void rendreChipsPortées() {
        binding.editeurDepPortees.removeAllViews();
        for (int i = 0; i < PORTÉES.length; i++) {
            final int index = i;
            TextView chip = new TextView(requireContext());
            chip.setText(PORTÉES[i]);
            chip.setTextColor(getResources().getColor(
                    i == portéeChoisie ? R.color.sur_sc2 : R.color.sur));
            chip.setTextSize(13f);
            chip.setTypeface(chip.getTypeface(), android.graphics.Typeface.BOLD);
            chip.setGravity(android.view.Gravity.CENTER);
            chip.setPadding(dip(15), 0, dip(15), 0);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dip(38));
            params.rightMargin = dip(8);
            chip.setLayoutParams(params);
            chip.setBackground(getResources().getDrawable(
                    i == portéeChoisie ? R.drawable.fond_chip_on : R.drawable.fond_chip));
            chip.setOnClickListener(v -> {
                portéeChoisie = index;
                rendreChipsPortées();
            });
            binding.editeurDepPortees.addView(chip);
        }
    }

    private void enregistrer() {
        Fragment parent = getParentFragment();
        if (!(parent instanceof ModelHost hôte)) {
            dismiss();
            return;
        }
        if ("maven".equals(dépendance.kind())) {
            String version = binding.editeurDepVersion.getText().toString().trim();
            if (!version.isEmpty()) {
                hôte.model().setDependencyVersion(module, dépendance.group(),
                        dépendance.artifact(), version);
            }
        }
        // Portée : retirer/ré-ajouter la dépendance avec la nouvelle portée
        // (le codec n'a pas de mutation de portée isolée — honnête et sûr).
        if (dépendance.scope() == null
                || !PORTÉES[portéeChoisie].equals(dépendance.scope())) {
            hôte.model().removeDependency(module, dépendance);
            hôte.model().addDependency(module, new ForgeJson.Dependency(
                    dépendance.kind(), dépendance.module(), dépendance.group(),
                    dépendance.artifact(), versionCourante(), PORTÉES[portéeChoisie]));
        }
        hôte.mutate("dépendance " + dépendance.display() + " éditée");
        dismiss();
    }

    private String versionCourante() {
        return binding != null && "maven".equals(dépendance.kind())
                ? binding.editeurDepVersion.getText().toString().trim()
                : dépendance.version();
    }

    private void confirmerSuppression() {
        Fragment parent = getParentFragment();
        if (!(parent instanceof ModelHost hôte)) {
            dismiss();
            return;
        }
        dismiss(); // la confirmation remplace la feuille d'édition.
        ConfirmSheet.destructif(
                getString(R.string.dep_supprimer_titre),
                getString(R.string.dep_supprimer_message, dépendance.display(), module),
                getString(R.string.dep_supprimer_bouton),
                () -> {
                    hôte.model().removeDependency(module, dépendance);
                    hôte.mutate("dépendance " + dépendance.display() + " retirée");
                })
                .show(parent.getChildFragmentManager(), "confirme-suppression");
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
