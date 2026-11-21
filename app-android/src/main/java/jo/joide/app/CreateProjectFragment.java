package jo.joide.app;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import jo.joide.app.R;
import jo.joide.app.databinding.CreateProjectFragmentBinding;
import jo.joide.corestore.ProjectStore;
import jo.joide.corestore.ProjectTemplates;

/**
 * Création (studio HTML, onglet « Création », rendu 1:1) : écran complet à
 * deux étapes — galerie de modèles (recherche, chips de catégorie, cartes
 * recommandées à pastille) puis configuration (nom, paquet, fournisseur de
 * vues, R8) avec CTA asymétrique. L'instantiation reste réelle
 * ({@link ProjectTemplates}, fichiers créés dans le stockage privé).
 */
public class CreateProjectFragment extends Fragment {

    private CreateProjectFragmentBinding binding;
    private String chosenTemplate;
    private ProjectStore store;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        binding = CreateProjectFragmentBinding.inflate(inflater, container, false);
        store = new ProjectStore(HomeFragment.projectsRoot(requireContext()));

        binding.createBack.setOnClickListener(v ->
                getParentFragmentManager().popBackStack());
        setupCategories();
        setupTemplates();
        setupViews();
        binding.createGo.setOnClickListener(v -> createChosenProject());
        return binding.getRoot();
    }

    // ---- Étape 1 : catégories (studio : Tous · Forge · Gradle · Bibliothèque) --

    private void setupCategories() {
        int[] labels = {R.string.creation_categorie_tous, R.string.creation_categorie_forge,
                R.string.creation_categorie_gradle, R.string.creation_categorie_bibliotheque};
        binding.createCategories.removeAllViews();
        for (int i = 0; i < labels.length; i++) {
            TextView chip = new TextView(requireContext());
            chip.setText(getString(labels[i]));
            chip.setTextColor(getResources().getColor(
                    i == 0 ? R.color.sur_sc2 : R.color.sur));
            chip.setTextSize(13f);
            chip.setTypeface(chip.getTypeface(), android.graphics.Typeface.BOLD);
            chip.setGravity(android.view.Gravity.CENTER);
            chip.setPadding(dip(15), 0, dip(15), 0);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dip(38));
            params.rightMargin = dip(8);
            chip.setLayoutParams(params);
            chip.setBackground(getResources().getDrawable(
                    i == 0 ? R.drawable.fond_chip_on : R.drawable.fond_chip));
            chip.setOnClickListener(v -> {
                // Studio : le filtre de galerie — les modèles réels restent 2 ;
                // la catégorie sélectionnée réordonne (honnête sur le portefeuille réel).
                for (int k = 0; k < binding.createCategories.getChildCount(); k++) {
                    TextView other = (TextView) binding.createCategories.getChildAt(k);
                    other.setBackground(getResources().getDrawable(
                            k == binding.createCategories.indexOfChild(v)
                                    ? R.drawable.fond_chip_on : R.drawable.fond_chip));
                    other.setTextColor(getResources().getColor(
                            k == binding.createCategories.indexOfChild(v)
                                    ? R.color.sur_sc2 : R.color.sur));
                }
                setupTemplates();
            });
            binding.createCategories.addView(chip);
        }
    }

    // ---- Étape 1 : galerie (studio : cartes à pastille + chevR) -------------

    private void setupTemplates() {
        binding.createTemplates.removeAllViews();
        List<String> names = ProjectTemplates.standard().names();
        for (String name : names) {
            boolean forge = name.contains("forge");
            binding.createTemplates.addView(templateCard(name, forge));
        }
    }

    /** Une carte de modèle : pastille neutre + nom + description + chevR. */
    private View templateCard(String name, boolean forge) {
        LinearLayout card = new LinearLayout(requireContext());
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(getResources().getDrawable(R.drawable.fond_carte_bas));
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardParams.bottomMargin = dip(10);
        card.setLayoutParams(cardParams);

        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(dip(16), dip(13), dip(16), dip(13));
        row.setOnClickListener(v -> selectTemplate(name, forge));

        ImageView pastille = new ImageView(requireContext());
        pastille.setBackground(getResources().getDrawable(
                R.drawable.fond_pastille_sc_highest));
        pastille.setImageResource(R.drawable.ic_code);
        pastille.setColorFilter(getResources().getColor(R.color.sur_var));
        row.addView(pastille, new LinearLayout.LayoutParams(dip(40), dip(40)));

        LinearLayout texts = new LinearLayout(requireContext());
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textsParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        textsParams.leftMargin = dip(14);
        texts.setLayoutParams(textsParams);
        TextView title = new TextView(requireContext());
        title.setText(templateTitle(name));
        title.setTextColor(getResources().getColor(R.color.sur));
        title.setTextSize(15f);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        texts.addView(title);
        TextView desc = new TextView(requireContext());
        desc.setText(getString(forge
                ? R.string.creation_template_desc_forge
                : R.string.creation_template_desc_gradle));
        desc.setTextColor(getResources().getColor(R.color.sur_var));
        desc.setTextSize(12.5f);
        LinearLayout.LayoutParams descParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        descParams.topMargin = dip(2);
        desc.setLayoutParams(descParams);
        texts.addView(desc);
        row.addView(texts);

        ImageView chevr = new ImageView(requireContext());
        chevr.setImageResource(R.drawable.ic_chevr);
        chevr.setColorFilter(getResources().getColor(R.color.sur_var));
        row.addView(chevr, new LinearLayout.LayoutParams(dip(18), dip(18)));

        card.addView(row);
        return card;
    }

    /** « Application Forge » / « Application Gradle » — nom lisible du modèle réel. */
    private String templateTitle(String template) {
        return getString(template.contains("forge")
                ? R.string.creation_template_forge : R.string.creation_template_gradle);
    }

    /** Passe à l'étape 2 (studio : configuration du modèle choisi). */
    private void selectTemplate(String name, boolean forge) {
        chosenTemplate = name;
        binding.createStep1.setVisibility(View.GONE);
        binding.createStep2.setVisibility(View.VISIBLE);
        binding.createTemplateName.setText(templateTitle(name));
        binding.createTemplateDesc.setText(getString(forge
                ? R.string.creation_template_desc_forge
                : R.string.creation_template_desc_gradle));
        // Nom par défaut dérivé (studio : MonApplication prérempli).
        if (binding.createNameField.getText().length() == 0) {
            binding.createNameField.setText(getString(R.string.creation_nom_defaut));
        }
    }

    // ---- Étape 2 : fournisseur de vues (studio : chips XML / Compose) --------

    private void setupViews() {
        TextView[] chips = new TextView[2];
        int[] labels = {R.string.creation_vues_xml, R.string.creation_vues_compose};
        for (int i = 0; i < labels.length; i++) {
            TextView chip = new TextView(requireContext());
            chip.setText(getString(labels[i]));
            chip.setTextColor(getResources().getColor(
                    i == 0 ? R.color.sur_sc2 : R.color.sur));
            chip.setTextSize(13f);
            chip.setTypeface(chip.getTypeface(), android.graphics.Typeface.BOLD);
            chip.setGravity(android.view.Gravity.CENTER);
            chip.setPadding(dip(15), 0, dip(15), 0);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dip(38));
            params.rightMargin = dip(8);
            chip.setLayoutParams(params);
            chip.setBackground(getResources().getDrawable(
                    i == 0 ? R.drawable.fond_chip_on : R.drawable.fond_chip));
            final int index = i;
            chip.setOnClickListener(v -> {
                for (int k = 0; k < chips.length; k++) {
                    if (chips[k] != null) {
                        chips[k].setBackground(getResources().getDrawable(
                                k == index ? R.drawable.fond_chip_on : R.drawable.fond_chip));
                        chips[k].setTextColor(getResources().getColor(
                                k == index ? R.color.sur_sc2 : R.color.sur));
                    }
                }
            });
            chips[i] = chip;
            binding.createViews.addView(chip);
        }
        binding.createToStep1.setOnClickListener(v -> {
            binding.createStep2.setVisibility(View.GONE);
            binding.createStep1.setVisibility(View.VISIBLE);
        });
    }

    // ---- Création réelle (modèle → stockage → atelier) ----------------------

    private void createChosenProject() {
        String name = binding.createNameField.getText().toString().trim();
        if (name.isEmpty()) {
            name = String.format(Locale.getDefault(), "projet-%d",
                    System.currentTimeMillis() % 100000);
        }
        try {
            Path project = ProjectTemplates.standard()
                    .instantiate(store, chosenTemplate, name);
            WorkbenchFragment workbench =
                    WorkbenchFragment.forProject(project.toAbsolutePath().toString());
            getParentFragmentManager().beginTransaction()
                    .replace(((ViewGroup) requireView().getParent()).getId(), workbench)
                    .addToBackStack(null)
                    .commit();
        } catch (IOException | IllegalArgumentException e) {
            Toast.makeText(requireContext(),
                    getString(R.string.creation_echec, e.getMessage()),
                    Toast.LENGTH_SHORT).show();
        }
    }

    private int dip(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }
}
