package jo.joide.app;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import jo.joide.app.R;
import jo.joide.app.databinding.CreateProjectFragmentBinding;
import jo.joide.corestore.ProjectStore;
import jo.joide.corestore.ProjectTemplates;

/**
 * Création (studio HTML, onglet « Création », rendu 1:1) : écran complet à
 * deux étapes — galerie de modèles (recherche, chips de catégorie, cartes à
 * pastille) puis configuration (nom, paquet dérivé, fournisseur de vues) avec
 * CTA asymétrique.
 *
 * <p>Portefeuille réel ({@link ProjectTemplates}) : cinq modèles Forge au
 * format {@code forge.json} vérifié (bibliothèque Java, bibliothèque Kotlin,
 * application Android XML + ViewBinding, application Compose, multi-modules)
 * et le modèle Gradle conservé pour le mode à venir (ADR-009). Les chips
 * filtrent VRAIMENT (Forge / Gradle / Bibliothèque), la recherche filtre par
 * titre, et le paquet saisi aligne namespaces et arborescence du projet créé.</p>
 */
public class CreateProjectFragment extends Fragment {

    /** Argument optionnel (Lot 0 Assistant/Agent) : nom de projet suggéré. */
    private static final String ARG_SUGGESTED_NAME = "suggested_name";

    private CreateProjectFragmentBinding binding;
    private ProjectTemplates.Template chosenTemplate;
    private ProjectStore store;

    /** Catégorie courante de la galerie (studio : Tous par défaut). */
    private int category = 0;

    /** Le paquet a-t-il été modifié à la main ? (sinon dérivation auto du nom). */
    private boolean packageEdited;

    /** Chips de catégorie (gardées pour le re-rendu de sélection). */
    private TextView[] categoryChips;

    /** Chips de fournisseur de vues (reflètent le modèle choisi). */
    private TextView[] viewChips;

    /**
     * Entrée avec nom suggéré (« Créer un projet depuis cette conversation ») :
     * le champ nom de l'étape 2 est prérempli si l'utilisateur n'a rien saisi.
     * L'entrée sans argument (accueil) est inchangée.
     */
    public static CreateProjectFragment withSuggestedName(String suggestedName) {
        CreateProjectFragment fragment = new CreateProjectFragment();
        Bundle args = new Bundle();
        args.putString(ARG_SUGGESTED_NAME, suggestedName);
        fragment.setArguments(args);
        return fragment;
    }

    /** Nom suggéré (argument optionnel) ; null si absent. */
    @Nullable
    private String suggestedName() {
        return getArguments() != null
                ? getArguments().getString(ARG_SUGGESTED_NAME, null) : null;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        binding = CreateProjectFragmentBinding.inflate(inflater, container, false);
        store = new ProjectStore(HomeFragment.projectsRoot(requireContext()));

        binding.createBack.setOnClickListener(v ->
                getParentFragmentManager().popBackStack());
        setupCategories();
        setupSearch();
        setupTemplates();
        setupViews();
        setupPackageDerivation();
        binding.createGo.setOnClickListener(v -> createChosenProject());
        return binding.getRoot();
    }

    // ---- Étape 1 : catégories (studio : Tous · Forge · Gradle · Bibliothèque) --

    private void setupCategories() {
        int[] labels = {R.string.creation_categorie_tous, R.string.creation_categorie_forge,
                R.string.creation_categorie_gradle, R.string.creation_categorie_bibliotheque};
        categoryChips = new TextView[labels.length];
        binding.createCategories.removeAllViews();
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
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
            chip.setOnClickListener(v -> selectCategory(index));
            categoryChips[i] = chip;
            binding.createCategories.addView(chip);
        }
    }

    /** Sélectionne une catégorie : les chips se réordonnent, la galerie filtre. */
    private void selectCategory(int index) {
        category = index;
        for (int k = 0; k < categoryChips.length; k++) {
            categoryChips[k].setBackground(getResources().getDrawable(
                    k == index ? R.drawable.fond_chip_on : R.drawable.fond_chip));
            categoryChips[k].setTextColor(getResources().getColor(
                    k == index ? R.color.sur_sc2 : R.color.sur));
        }
        setupTemplates();
    }

    // ---- Étape 1 : recherche (filtre par titre) -------------------------------

    private void setupSearch() {
        binding.createSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }

            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }

            @Override public void afterTextChanged(Editable s) {
                setupTemplates();
            }
        });
    }

    // ---- Étape 1 : galerie (portefeuille réel, filtré) -------------------------

    private void setupTemplates() {
        binding.createTemplates.removeAllViews();
        String query = binding.createSearch.getText().toString()
                .trim().toLowerCase(Locale.ROOT);
        for (ProjectTemplates.Template template : ProjectTemplates.standard().all()) {
            if (!matchesCategory(template)) {
                continue;
            }
            String title = templateTitle(template).toLowerCase(Locale.ROOT);
            if (!query.isEmpty() && !title.contains(query)) {
                continue;
            }
            binding.createTemplates.addView(templateCard(template));
        }
    }

    /** Le modèle appartient-il à la catégorie courante ? */
    private boolean matchesCategory(ProjectTemplates.Template template) {
        return switch (category) {
            case 1 -> template.forge();
            case 2 -> template.kind() == ProjectTemplates.Kind.GRADLE;
            case 3 -> template.kind() == ProjectTemplates.Kind.LIBRARY;
            default -> true;
        };
    }

    /** Une carte de modèle : pastille neutre + nom + description + chevR. */
    private View templateCard(ProjectTemplates.Template template) {
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
        row.setOnClickListener(v -> selectTemplate(template));

        ImageView pastille = new ImageView(requireContext());
        pastille.setBackground(getResources().getDrawable(
                R.drawable.fond_pastille_sc_highest));
        pastille.setImageResource(template.kind() == ProjectTemplates.Kind.GRADLE
                ? R.drawable.ic_terminal : R.drawable.ic_code);
        pastille.setColorFilter(getResources().getColor(R.color.sur_var));
        row.addView(pastille, new LinearLayout.LayoutParams(dip(40), dip(40)));

        LinearLayout texts = new LinearLayout(requireContext());
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textsParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        textsParams.leftMargin = dip(14);
        texts.setLayoutParams(textsParams);
        TextView title = new TextView(requireContext());
        title.setText(templateTitle(template));
        title.setTextColor(getResources().getColor(R.color.sur));
        title.setTextSize(15f);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        texts.addView(title);
        TextView desc = new TextView(requireContext());
        desc.setText(getString(templateDescription(template)));
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

    /** Titre lisible d'un modèle (ressources — jamais en dur). */
    private String templateTitle(ProjectTemplates.Template template) {
        return getString(titleOf(template));
    }

    /** Ressource du titre selon l'identifiant du modèle. */
    private int titleOf(ProjectTemplates.Template template) {
        return switch (template.id()) {
            case "forge-android-xml" -> R.string.creation_titre_android_xml;
            case "forge-compose" -> R.string.creation_titre_compose;
            case "forge-kotlin" -> R.string.creation_titre_kotlin;
            case "forge-multimodule" -> R.string.creation_titre_multimodule;
            case "gradle-app" -> R.string.creation_titre_gradle;
            default -> R.string.creation_titre_java;
        };
    }

    /** Ressource de la description selon l'identifiant du modèle. */
    private int templateDescription(ProjectTemplates.Template template) {
        return switch (template.id()) {
            case "forge-android-xml" -> R.string.creation_desc_android_xml;
            case "forge-compose" -> R.string.creation_desc_compose;
            case "forge-kotlin" -> R.string.creation_desc_kotlin;
            case "forge-multimodule" -> R.string.creation_desc_multimodule;
            case "gradle-app" -> R.string.creation_desc_gradle;
            default -> R.string.creation_desc_java;
        };
    }

    /** Passe à l'étape 2 (studio : configuration du modèle choisi). */
    private void selectTemplate(ProjectTemplates.Template template) {
        chosenTemplate = template;
        binding.createStep1.setVisibility(View.GONE);
        binding.createStep2.setVisibility(View.VISIBLE);
        binding.createTemplateName.setText(templateTitle(template));
        binding.createTemplateDesc.setText(getString(templateDescription(template)));
        // Nom par défaut dérivé (studio : MonApplication prérempli) ; le nom
        // suggéré d'une conversation de l'assistant prime s'il existe.
        if (binding.createNameField.getText().length() == 0) {
            String suggested = suggestedName();
            binding.createNameField.setText(suggested != null && !suggested.isEmpty()
                    ? suggested : getString(R.string.creation_nom_defaut));
        }
        derivePackageIfPristine();
        renderViewChips();
        // R8 : Forge ne propose pas la minimisation (capacités honnêtes) — la
        // rangée reste masquée tant que le moteur ne la livre pas.
        binding.createR8Row.setVisibility(View.GONE);
    }

    // ---- Étape 2 : fournisseur de vues (reflète le modèle choisi) -------------

    private void setupViews() {
        int[] labels = {R.string.creation_vues_xml, R.string.creation_vues_compose};
        viewChips = new TextView[labels.length];
        for (int i = 0; i < labels.length; i++) {
            TextView chip = new TextView(requireContext());
            chip.setText(getString(labels[i]));
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
            viewChips[i] = chip;
            binding.createViews.addView(chip);
        }
        binding.createToStep1.setOnClickListener(v -> {
            binding.createStep2.setVisibility(View.GONE);
            binding.createStep1.setVisibility(View.VISIBLE);
        });
    }

    /** Les chips de vues montrent le fournisseur du modèle (sélection informative). */
    private void renderViewChips() {
        boolean hasViews = chosenTemplate != null
                && chosenTemplate.views() != ProjectTemplates.Views.NONE;
        binding.createViews.setVisibility(hasViews ? View.VISIBLE : View.GONE);
        if (!hasViews) {
            return;
        }
        boolean xml = chosenTemplate.views() == ProjectTemplates.Views.XML;
        for (int k = 0; k < viewChips.length; k++) {
            boolean on = k == 0 ? xml : !xml;
            viewChips[k].setBackground(getResources().getDrawable(
                    on ? R.drawable.fond_chip_on : R.drawable.fond_chip));
            viewChips[k].setTextColor(getResources().getColor(
                    on ? R.color.sur_sc2 : R.color.sur));
        }
    }

    // ---- Étape 2 : paquet (dérivation auto tant que non modifié) --------------

    /** Dérive le paquet du nom tant que l'utilisateur ne l'a pas touché (studio). */
    private void setupPackageDerivation() {
        binding.createPackageField.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }

            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }

            @Override public void afterTextChanged(Editable s) {
                packageEdited = true;
            }
        });
        binding.createNameField.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }

            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }

            @Override public void afterTextChanged(Editable s) {
                derivePackageIfPristine();
            }
        });
    }

    /** Recalcule com.example.<nom> si le paquet n'a pas été édité à la main. */
    private void derivePackageIfPristine() {
        if (packageEdited) {
            return;
        }
        String name = binding.createNameField.getText().toString();
        binding.createPackageField.setText(ProjectTemplates.defaultPackage(name));
        packageEdited = false; // la dérivation n'est pas une édition utilisateur.
    }

    // ---- Création réelle (modèle → stockage → atelier) ------------------------

    private void createChosenProject() {
        if (chosenTemplate == null) {
            return;
        }
        String name = binding.createNameField.getText().toString().trim();
        if (name.isEmpty()) {
            name = String.format(Locale.getDefault(), "projet-%d",
                    System.currentTimeMillis() % 100000);
        }
        String packageName = binding.createPackageField.getText().toString().trim();
        try {
            Path project = ProjectTemplates.standard()
                    .instantiate(store, chosenTemplate.id(), name, packageName);
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
