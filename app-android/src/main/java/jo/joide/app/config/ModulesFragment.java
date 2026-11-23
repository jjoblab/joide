package jo.joide.app.config;

import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import jo.joide.app.R;
import jo.joide.app.WorkbenchFragment;
import jo.joide.app.databinding.ModulesFragmentBinding;
import jo.joide.coremodel.ForgeJson;

/**
 * Écran Modules (lot L10, d'après ModuleConfigScreen de la référence —
 * REF-127, parcours et champs adaptés à notre modèle réel) : liste des
 * modules du {@code forge.json} RÉEL (tuile type, menu ⋮ renommer/supprimer,
 * bouton + « Nouveau module », état vide), détail à onglets
 * <b>Réglages / Dépendances</b> (cartes Général, Ensembles de sources,
 * Facette Android), barre de sauvegarde épinglée (Abandonner / Enregistrer —
 * comme la SaveBar de la référence, jamais un bouton perdu au fil du
 * formulaire).
 *
 * <p>Les mutations se font champ par champ SUR l'arbre réel
 * ({@link ForgeJson.Model}) — les champs inconnus du moteur survivent (banc
 * de fidélité) ; l'écriture disque est atomique et unique (« Enregistrer ») ;
 * « Abandonner » relit le fichier. Toute la suite en feuilles et popups,
 * jamais de dialogue (règle du propriétaire).</p>
 */
public class ModulesFragment extends Fragment implements ModelHost {

    private static final String ARG_PROJECT = "project";

    /** Onglet du détail. */
    private int onglet = 0; // 0 = Réglages, 1 = Dépendances

    private ModulesFragmentBinding binding;

    private Path projectRoot;
    private ForgeJson.Model modèle;
    private String moduleOuvert;
    private boolean nonEnregistré;

    /** Instance sur un projet (chemin absolu de la racine). */
    public static ModulesFragment forProject(String projectPath) {
        ModulesFragment fragment = new ModulesFragment();
        Bundle args = new Bundle();
        args.putString(ARG_PROJECT, projectPath);
        fragment.setArguments(args);
        return fragment;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
            @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = ModulesFragmentBinding.inflate(inflater, container, false);
        projectRoot = Paths.get(projectPath());

        binding.modulesBack.setOnClickListener(v ->
                getParentFragmentManager().popBackStack());
        binding.modulesAddButton.setOnClickListener(v ->
                new NewModuleSheet().show(getChildFragmentManager(), "nouveau-module"));
        binding.modulesDetailBack.setOnClickListener(v -> fermerDétail());
        binding.modulesDiscardButton.setOnClickListener(v -> abandonner());
        binding.modulesSaveButton.setOnClickListener(v -> enregistrer());

        try {
            modèle = ForgeJson.Model.read(projectRoot.resolve("forge.json"));
        } catch (Exception e) {
            // Projet sans forge.json lisible : dit, jamais muet (écran honnête).
            binding.modulesTitle.setText(R.string.modules_titre);
            TextView erreur = new TextView(requireContext());
            erreur.setText(getString(R.string.modules_illisible, e.getMessage()));
            erreur.setTextColor(ContextCompat.getColor(requireContext(), R.color.erreur));
            erreur.setTextSize(13f);
            erreur.setPadding(dip(8), dip(16), dip(8), dip(8));
            binding.modulesList.addView(erreur);
            return binding.getRoot();
        }
        rendreListe();
        rendreOnglets();
        return binding.getRoot();
    }

    @Nullable
    private String projectPath() {
        return getArguments() != null ? getArguments().getString(ARG_PROJECT) : null;
    }

    // ---- ModelHost : les feuilles mutent, l'écran suit -----------------------

    @Override
    public ForgeJson.Model model() {
        return modèle;
    }

    @Override
    public void mutate(String description) {
        nonEnregistré = true;
        binding.modulesSavebar.setVisibility(View.VISIBLE);
        if (moduleOuvert != null) {
            rendreDétail();
        } else {
            rendreListe();
        }
    }

    // ---- Liste des modules ----------------------------------------------------

    private void rendreListe() {
        binding.modulesList.removeAllViews();
        List<ForgeJson.ModuleView> modules = modèle.modules();
        if (modules.isEmpty()) {
            LinearLayout vide = new LinearLayout(requireContext());
            vide.setOrientation(LinearLayout.VERTICAL);
            vide.setGravity(Gravity.CENTER);
            vide.setPadding(dip(16), dip(36), dip(16), dip(36));
            vide.setBackground(getResources().getDrawable(R.drawable.fond_vide));
            TextView titre = new TextView(requireContext());
            titre.setText(R.string.modules_vide_titre);
            titre.setTextColor(ContextCompat.getColor(requireContext(), R.color.sur));
            titre.setTextSize(15f);
            titre.setTypeface(titre.getTypeface(), android.graphics.Typeface.BOLD);
            titre.setGravity(Gravity.CENTER);
            vide.addView(titre);
            TextView desc = new TextView(requireContext());
            desc.setText(R.string.modules_vide_desc);
            desc.setTextColor(ContextCompat.getColor(requireContext(), R.color.sur_var));
            desc.setTextSize(12.5f);
            desc.setGravity(Gravity.CENTER);
            vide.addView(desc);
            binding.modulesList.addView(vide);
            return;
        }
        for (ForgeJson.ModuleView module : modules) {
            binding.modulesList.addView(rangéeModule(module));
        }
    }

    /** Une rangée de module : pastille type + nom + type + menu ⋮. */
    private View rangéeModule(ForgeJson.ModuleView module) {
        LinearLayout carte = new LinearLayout(requireContext());
        carte.setOrientation(LinearLayout.VERTICAL);
        carte.setBackground(getResources().getDrawable(R.drawable.fond_carte_bas));
        LinearLayout.LayoutParams paramsCarte = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        paramsCarte.topMargin = dip(10);
        carte.setLayoutParams(paramsCarte);

        LinearLayout rangée = new LinearLayout(requireContext());
        rangée.setOrientation(LinearLayout.HORIZONTAL);
        rangée.setGravity(Gravity.CENTER_VERTICAL);
        rangée.setPadding(dip(12), dip(14), dip(8), dip(14));
        rangée.setOnClickListener(v -> ouvrirDétail(module.name()));

        boolean estAndroid = module.android().isPresent();
        ImageView pastille = new ImageView(requireContext());
        pastille.setBackground(getResources().getDrawable(
                estAndroid ? R.drawable.fond_pastille_tertiaire
                        : R.drawable.fond_pastille_sc_highest));
        pastille.setImageResource(estAndroid ? R.drawable.ic_wb : R.drawable.ic_code);
        pastille.setColorFilter(getResources().getColor(
                estAndroid ? R.color.sur_conteneur_tertiaire : R.color.sur_var));
        rangée.addView(pastille, new LinearLayout.LayoutParams(dip(40), dip(40)));

        LinearLayout textes = new LinearLayout(requireContext());
        textes.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams paramsTextes = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        paramsTextes.leftMargin = dip(14);
        textes.setLayoutParams(paramsTextes);
        TextView nom = new TextView(requireContext());
        nom.setText(module.name());
        nom.setTextColor(getResources().getColor(R.color.sur));
        nom.setTextSize(15f);
        nom.setTypeface(nom.getTypeface(), android.graphics.Typeface.BOLD);
        textes.addView(nom);
        TextView type = new TextView(requireContext());
        type.setText(typeAffichable(module));
        type.setTextColor(getResources().getColor(R.color.sur_var));
        type.setTextSize(12.5f);
        textes.addView(type);
        rangée.addView(textes);

        ImageView menu = new ImageView(requireContext());
        menu.setImageResource(R.drawable.ic_dots);
        menu.setColorFilter(getResources().getColor(R.color.sur_var));
        LinearLayout.LayoutParams paramsMenu = new LinearLayout.LayoutParams(
                dip(40), dip(40));
        menu.setLayoutParams(paramsMenu);
        menu.setOnClickListener(v -> menuModule(module, menu));
        rangée.addView(menu);

        carte.addView(rangée);
        return carte;
    }

    /** Type affichable : type réel + langage + niveau Java + nb de dépendances. */
    private String typeAffichable(ForgeJson.ModuleView module) {
        StringBuilder libellé = new StringBuilder(module.type());
        if (module.language() != null && !"java".equals(module.language())) {
            libellé.append(" · ").append(module.language());
        }
        libellé.append(" · Java ").append(module.javaRelease());
        if (!module.dependencies().isEmpty()) {
            libellé.append(" · ").append(module.dependencies().size())
                    .append(' ').append(getString(R.string.modules_dep_count));
        }
        return libellé.toString();
    }

    /** Menu ⋮ d'un module : Renommer / Supprimer (popup, pas de dialogue). */
    private void menuModule(ForgeJson.ModuleView module, View ancre) {
        android.widget.PopupMenu menu = new android.widget.PopupMenu(
                requireContext(), ancre);
        menu.inflate(R.menu.module_row);
        menu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.option_renommer_module) {
                renommer(module);
                return true;
            } else if (item.getItemId() == R.id.option_supprimer_module) {
                supprimer(module);
                return true;
            }
            return false;
        });
        menu.show();
    }

    private void renommer(ForgeJson.ModuleView module) {
        TextSheet.forText(
                getString(R.string.modules_renommer_titre),
                getString(R.string.modules_renommer_message, module.name()),
                module.name(),
                getString(R.string.modules_renommer_ok),
                nouveau -> {
                    if (!NewModuleSheet.nomValide(nouveau)) {
                        toast(getString(R.string.modules_nouveau_invalide));
                        return;
                    }
                    if (modèle.renameModule(module.name(), nouveau)) {
                        if (module.name().equals(moduleOuvert)) {
                            moduleOuvert = nouveau;
                        }
                        mutate("module renommé : " + module.name() + " → " + nouveau);
                    } else {
                        toast(getString(R.string.modules_renommer_doublon, nouveau));
                    }
                })
                .show(getChildFragmentManager(), "renommer-module");
    }

    private void supprimer(ForgeJson.ModuleView module) {
        ConfirmSheet.destructif(
                getString(R.string.modules_supprimer_titre),
                getString(R.string.modules_supprimer_message, module.name()),
                getString(R.string.modules_supprimer_bouton),
                () -> {
                    modèle.removeModule(module.name());
                    if (module.name().equals(moduleOuvert)) {
                        fermerDétail();
                    }
                    // Le dossier du module part aussi (décision du lot, confirmée) ;
                    // best-effort : un fichier verrouillé est dit, pas muet.
                    try {
                        Path dossier = projectRoot.resolve(module.name());
                        if (Files.exists(dossier)) {
                            supprimerRécursivement(dossier);
                        }
                    } catch (IOException e) {
                        toast(getString(R.string.modules_supprimer_dossier, e.getMessage()));
                    }
                    mutate("module supprimé : " + module.name());
                })
                .show(getChildFragmentManager(), "supprimer-module");
    }

    private static void supprimerRécursivement(Path dossier) throws IOException {
        try (var parcours = Files.walk(dossier)) {
            parcours.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }

    // ---- Détail : ouverture et onglets ---------------------------------------

    private void ouvrirDétail(String nom) {
        moduleOuvert = nom;
        binding.modulesListScroll.setVisibility(View.GONE);
        binding.modulesDetail.setVisibility(View.VISIBLE);
        binding.modulesTitle.setText(R.string.modules_titre);
        rendreEnTêteDétail();
        rendreOnglets();
        rendreDétail();
    }

    private void fermerDétail() {
        moduleOuvert = null;
        binding.modulesDetail.setVisibility(View.GONE);
        binding.modulesListScroll.setVisibility(View.VISIBLE);
        rendreListe();
    }

    private void rendreEnTêteDétail() {
        ForgeJson.ModuleView module = modèle.module(moduleOuvert);
        if (module == null) {
            fermerDétail();
            return;
        }
        binding.modulesDetailName.setText(module.name());
        binding.modulesDetailType.setText(typeAffichable(module));
    }

    private void rendreOnglets() {
        binding.modulesTabs.removeAllViews();
        String[] onglets = {getString(R.string.modules_onglet_reglages),
                getString(R.string.modules_onglet_dependances)};
        for (int i = 0; i < onglets.length; i++) {
            final int index = i;
            TextView chip = new TextView(requireContext());
            chip.setText(onglets[i]);
            chip.setTextColor(getResources().getColor(
                    i == onglet ? R.color.sur_sc2 : R.color.sur));
            chip.setTextSize(13f);
            chip.setTypeface(chip.getTypeface(), android.graphics.Typeface.BOLD);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(dip(15), 0, dip(15), 0);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dip(38));
            params.rightMargin = dip(8);
            chip.setLayoutParams(params);
            chip.setBackground(getResources().getDrawable(
                    i == onglet ? R.drawable.fond_chip_on : R.drawable.fond_chip));
            chip.setOnClickListener(v -> {
                onglet = index;
                rendreOnglets();
                rendreDétail();
            });
            binding.modulesTabs.addView(chip);
        }
    }

    private void rendreDétail() {
        if (binding == null || moduleOuvert == null) {
            return;
        }
        rendreEnTêteDétail();
        binding.modulesDetailContent.removeAllViews();
        ForgeJson.ModuleView module = modèle.module(moduleOuvert);
        if (module == null) {
            fermerDétail();
            return;
        }
        if (onglet == 0) {
            rendreRéglages(module);
        } else {
            rendreDépendances(module);
        }
    }

    // ---- Onglet Réglages : cartes ----------------------------------------------

    private void rendreRéglages(ForgeJson.ModuleView module) {
        LinearLayout contenu = binding.modulesDetailContent;

        // Carte Général : type (chips), langage (chips), Java (chips), sortie (constat).
        LinearLayout général = carte(getString(R.string.modules_carte_general));
        rangéeChips(général, getString(R.string.modules_champ_type),
                new String[]{"java-library", "application"},
                module.type(), valeur -> {
                    modèle.setModuleField(module.name(), "type", valeur);
                    mutate("type de " + module.name() + " = " + valeur);
                });
        rangéeChips(général, getString(R.string.modules_champ_langage),
                new String[]{"java", "kotlin"}, module.language(), valeur -> {
                    modèle.setModuleField(module.name(), "language", valeur);
                    mutate("langage de " + module.name() + " = " + valeur);
                });
        rangéeChips(général, getString(R.string.modules_champ_java),
                new String[]{"8", "11", "17", "21"},
                String.valueOf(module.javaRelease()), valeur -> {
                    modèle.setModuleField(module.name(), "javaRelease",
                            Long.parseLong(valeur));
                    mutate("javaRelease de " + module.name() + " = " + valeur);
                });
        String sortie = modèle.champModule(module.name(), "output");
        rangéeConstat(général, getString(R.string.modules_champ_sortie),
                sortie != null ? sortie
                        : getString(R.string.modules_sortie_convention));
        contenu.addView(général);

        // Carte Ensembles de sources : chips amovibles + ajout (préréglages).
        LinearLayout sources = carte(getString(R.string.modules_carte_sources));
        LinearLayout chips = new LinearLayout(requireContext());
        chips.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams paramsChips = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        chips.setLayoutParams(paramsChips);
        for (String racine : module.sources()) {
            chips.addView(chipAmovible(sources, module.name(), racine));
        }
        if (module.sources().isEmpty()) {
            TextView vide = new TextView(requireContext());
            vide.setText(R.string.modules_sources_vide);
            vide.setTextColor(getResources().getColor(R.color.sur_var));
            vide.setTextSize(12.5f);
            sources.addView(vide);
        } else {
            HorizontalScrollView défilement = new HorizontalScrollView(requireContext());
            défilement.setFillViewport(true);
            défilement.addView(chips);
            sources.addView(défilement);
        }
        TextView ajouter = boutonTexte(getString(R.string.modules_sources_ajouter));
        ajouter.setOnClickListener(v -> menuAjoutSource(module, ajouter));
        sources.addView(ajouter);
        contenu.addView(sources);

        // Carte Facette Android (si présente) : champs réels éditables.
        Optional<ForgeJson.AndroidFacet> facette = module.android();
        if (facette.isPresent()) {
            LinearLayout android = carte(getString(R.string.modules_carte_android));
            ForgeJson.AndroidFacet f = facette.get();
            rangéeÉditable(android, "namespace", f.namespace(), valeur -> {
                modèle.setAndroidField(module.name(), "namespace", valeur);
                mutate("namespace de " + module.name());
            });
            rangéeÉditableNumérique(android, "minSdk",
                    String.valueOf(f.minSdk()), valeur -> {
                modèle.setAndroidField(module.name(), "minSdk",
                        Long.parseLong(valeur));
                mutate("minSdk de " + module.name());
            });
            rangéeÉditableNumérique(android, "targetSdk",
                    String.valueOf(f.targetSdk()), valeur -> {
                modèle.setAndroidField(module.name(), "targetSdk",
                        Long.parseLong(valeur));
                mutate("targetSdk de " + module.name());
            });
            rangéeÉditableNumérique(android, "versionCode",
                    String.valueOf(f.versionCode()), valeur -> {
                modèle.setAndroidField(module.name(), "versionCode",
                        Long.parseLong(valeur));
                mutate("versionCode de " + module.name());
            });
            rangéeÉditable(android, "versionName", f.versionName(), valeur -> {
                modèle.setAndroidField(module.name(), "versionName", valeur);
                mutate("versionName de " + module.name());
            });
            rangéeConstat(android, "manifest", f.manifest() == null
                    ? "src/main/AndroidManifest.xml (convention)" : f.manifest());
            if (f.res() != null && !f.res().isEmpty()) {
                rangéeConstat(android, "res", String.join(", ", f.res()));
            }
            contenu.addView(android);
        }
    }

    /** Préréglages d'ajout de racine source (popup, comme la référence). */
    private void menuAjoutSource(ForgeJson.ModuleView module, View ancre) {
        String[][] préréglages = {
                {"Java — src/main/java", "src/main/java"},
                {"Kotlin — src/main/kotlin", "src/main/kotlin"},
                {"Ressources — src/main/resources", "src/main/resources"},
                {"res Android — src/main/res", "src/main/res"},
        };
        android.widget.PopupMenu menu = new android.widget.PopupMenu(
                requireContext(), ancre);
        for (String[] préréglage : préréglages) {
            menu.getMenu().add(préréglage[0]);
        }
        menu.getMenu().add(getString(R.string.modules_sources_autre));
        menu.setOnMenuItemClickListener(item -> {
            for (String[] préréglage : préréglages) {
                if (préréglage[0].equals(item.getTitle())) {
                    if (modèle.addSourceRoot(module.name(), préréglage[1])) {
                        mutate("racine source " + préréglage[1] + " → " + module.name());
                    }
                    return true;
                }
            }
            // Autre : saisie libre (feuille).
            TextSheet.forText(
                    getString(R.string.modules_sources_autre_titre),
                    getString(R.string.modules_sources_autre_message),
                    "", getString(R.string.modules_sources_ajouter),
                    chemin -> {
                        if (!chemin.isEmpty()
                                && modèle.addSourceRoot(module.name(), chemin)) {
                            mutate("racine source " + chemin + " → " + module.name());
                        }
                    })
                    .show(getChildFragmentManager(), "racine-source");
            return true;
        });
        menu.show();
    }

    /** Une chip de racine source amovible (icône dossier + ×). */
    private TextView chipAmovible(LinearLayout parent, String module, String racine) {
        TextView chip = new TextView(requireContext());
        chip.setText(racine + "  ✕");
        chip.setTextColor(getResources().getColor(R.color.sur));
        chip.setTextSize(12f);
        chip.setTypeface(android.graphics.Typeface.MONOSPACE);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(dip(12), dip(8), dip(12), dip(8));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.rightMargin = dip(8);
        chip.setLayoutParams(params);
        chip.setBackground(getResources().getDrawable(R.drawable.fond_chip));
        chip.setOnClickListener(v -> {
            if (modèle.removeSourceRoot(module, racine)) {
                parent.removeView(chip);
                mutate("racine source retirée : " + racine);
            }
        });
        return chip;
    }

    // ---- Onglet Dépendances -----------------------------------------------------

    private void rendreDépendances(ForgeJson.ModuleView module) {
        LinearLayout contenu = binding.modulesDetailContent;

        // Actions : Ajouter / Dépôts / Re-résoudre.
        LinearLayout actions = new LinearLayout(requireContext());
        actions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams paramsActions = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        paramsActions.bottomMargin = dip(12);
        actions.setLayoutParams(paramsActions);
        TextView ajouter = boutonCta(getString(R.string.dep_ajouter_bouton));
        ajouter.setOnClickListener(v ->
                AddDependencySheet.pour(module.name())
                        .show(getChildFragmentManager(), "ajout-dependance"));
        actions.addView(ajouter, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView dépôts = boutonTexte(getString(R.string.depots_titre_court));
        dépôts.setOnClickListener(v ->
                new RepositoriesSheet().show(getChildFragmentManager(), "depots"));
        LinearLayout.LayoutParams paramsDépôts = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        paramsDépôts.leftMargin = dip(8);
        dépôts.setLayoutParams(paramsDépôts);
        actions.addView(dépôts);
        contenu.addView(actions);

        TextView re_résoudre = boutonTexte(getString(R.string.dep_reresoudre));
        re_résoudre.setOnClickListener(v -> {
            // La résolution vit dans le moteur : on enregistre, on rend la main
            // à l'atelier et le build repart (la console le montre).
            enregistrer();
            WorkbenchFragment.requestRebuild();
            getParentFragmentManager().popBackStack();
        });
        contenu.addView(re_résoudre);

        // Liste déclarée : badge portée + coordonnée, tap = édition.
        if (module.dependencies().isEmpty()) {
            TextView vide = new TextView(requireContext());
            vide.setText(R.string.dep_vide);
            vide.setTextColor(getResources().getColor(R.color.sur_var));
            vide.setTextSize(12.5f);
            vide.setPadding(dip(8), dip(16), dip(8), dip(8));
            contenu.addView(vide);
            return;
        }
        LinearLayout carte = carte(getString(R.string.dep_declarees, module.name()));
        for (ForgeJson.Dependency dépendance : module.dependencies()) {
            carte.addView(rangéeDépendance(module.name(), dépendance));
        }
        contenu.addView(carte);
    }

    /** Une rangée de dépendance : badge portée + display + ⋮ (édition). */
    private View rangéeDépendance(String module, ForgeJson.Dependency dépendance) {
        LinearLayout rangée = new LinearLayout(requireContext());
        rangée.setOrientation(LinearLayout.HORIZONTAL);
        rangée.setGravity(Gravity.CENTER_VERTICAL);
        rangée.setPadding(dip(4), dip(10), dip(4), dip(10));
        rangée.setOnClickListener(v ->
                new EditDependencySheet(module, dépendance)
                        .show(getChildFragmentManager(), "edition-dependance"));

        TextView badge = new TextView(requireContext());
        badge.setText(dépendance.scope() == null ? "implementation" : dépendance.scope());
        badge.setTextColor(getResources().getColor(R.color.sur_conteneur_tertiaire));
        badge.setTextSize(10.5f);
        badge.setTypeface(badge.getTypeface(), android.graphics.Typeface.BOLD);
        badge.setGravity(Gravity.CENTER);
        badge.setPadding(dip(8), dip(4), dip(8), dip(4));
        badge.setBackground(getResources().getDrawable(R.drawable.fond_pastille_tertiaire));
        rangée.addView(badge, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView nom = new TextView(requireContext());
        nom.setText(dépendance.display());
        nom.setTextColor(getResources().getColor(R.color.sur));
        nom.setTextSize(13f);
        nom.setTypeface(android.graphics.Typeface.MONOSPACE);
        LinearLayout.LayoutParams paramsNom = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        paramsNom.leftMargin = dip(10);
        nom.setLayoutParams(paramsNom);
        rangée.addView(nom);

        ImageView menu = new ImageView(requireContext());
        menu.setImageResource(R.drawable.ic_dots);
        menu.setColorFilter(getResources().getColor(R.color.sur_var));
        menu.setOnClickListener(v ->
                new EditDependencySheet(module, dépendance)
                        .show(getChildFragmentManager(), "edition-dependance"));
        rangée.addView(menu, new LinearLayout.LayoutParams(dip(32), dip(32)));
        return rangée;
    }

    // ---- Barre de sauvegarde ----------------------------------------------------

    private void enregistrer() {
        try {
            modèle.writeAtomically();
            nonEnregistré = false;
            binding.modulesSavebar.setVisibility(View.GONE);
            // L'atelier republie le modèle changé à sa reprise (arbre, index).
            WorkbenchFragment.notifyModelChanged();
            toast(getString(R.string.modules_enregistre));
        } catch (IOException e) {
            toast(getString(R.string.modules_erreur_ecriture, e.getMessage()));
        }
    }

    private void abandonner() {
        try {
            modèle = ForgeJson.Model.read(projectRoot.resolve("forge.json"));
            nonEnregistré = false;
            binding.modulesSavebar.setVisibility(View.GONE);
            rendreListe();
            rendreDétail();
        } catch (Exception e) {
            toast(getString(R.string.modules_illisible, e.getMessage()));
        }
    }

    // ---- Petits composants (même grammaire que l'accueil et Git) ----------------

    /** Une carte titrée (fond carte du studio). */
    private LinearLayout carte(String titre) {
        LinearLayout carte = new LinearLayout(requireContext());
        carte.setOrientation(LinearLayout.VERTICAL);
        carte.setBackground(getResources().getDrawable(R.drawable.fond_carte_bas));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dip(12);
        carte.setLayoutParams(params);
        carte.setPadding(dip(14), dip(12), dip(14), dip(12));
        TextView libellé = new TextView(requireContext());
        libellé.setText(titre);
        libellé.setTextColor(getResources().getColor(R.color.sur));
        libellé.setTextSize(14f);
        libellé.setTypeface(libellé.getTypeface(), android.graphics.Typeface.BOLD);
        carte.addView(libellé);
        return carte;
    }

    /** Une rangée label + chips de choix exclusif (type, langage, Java). */
    private void rangéeChips(LinearLayout parent, String libellé, String[] valeurs,
            String courante, java.util.function.Consumer<String> auChoix) {
        TextView label = new TextView(requireContext());
        label.setText(libellé);
        label.setTextColor(getResources().getColor(R.color.sur_var));
        label.setTextSize(12.5f);
        label.setPadding(0, dip(10), 0, dip(4));
        parent.addView(label);
        LinearLayout chips = new LinearLayout(requireContext());
        chips.setOrientation(LinearLayout.HORIZONTAL);
        parent.addView(chips);
        for (String valeur : valeurs) {
            final String choix = valeur;
            TextView chip = new TextView(requireContext());
            chip.setText(valeur);
            chip.setTextColor(getResources().getColor(
                    valeur.equals(courante) ? R.color.sur_sc2 : R.color.sur));
            chip.setTextSize(12.5f);
            chip.setTypeface(chip.getTypeface(), android.graphics.Typeface.BOLD);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(dip(13), dip(7), dip(13), dip(7));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.rightMargin = dip(8);
            chip.setLayoutParams(params);
            chip.setBackground(getResources().getDrawable(
                    valeur.equals(courante) ? R.drawable.fond_chip_on
                            : R.drawable.fond_chip));
            chip.setOnClickListener(v -> auChoix.accept(choix));
            chips.addView(chip);
        }
    }

    /** Une rangée label + valeur éditable (tap → feuille de texte). */
    private void rangéeÉditable(LinearLayout parent, String libellé, String valeur,
            java.util.function.Consumer<String> àLEcriture) {
        View rangée = rangéeValeur(parent, libellé, valeur == null ? "—" : valeur);
        rangée.setOnClickListener(v ->
                TextSheet.forText(libellé,
                        getString(R.string.modules_editer_message, libellé),
                        valeur == null ? "" : valeur,
                        getString(R.string.modules_enregistrer), àLEcriture::accept)
                        .show(getChildFragmentManager(), "edition-" + libellé));
    }

    /** Version numérique de la rangée éditable (clavier chiffres). */
    private void rangéeÉditableNumérique(LinearLayout parent, String libellé,
            String valeur, java.util.function.Consumer<String> àLEcriture) {
        View rangée = rangéeValeur(parent, libellé, valeur);
        rangée.setOnClickListener(v ->
                TextSheet.forNumber(libellé,
                        getString(R.string.modules_editer_message, libellé),
                        valeur, getString(R.string.modules_enregistrer), àLEcriture::accept)
                        .show(getChildFragmentManager(), "edition-" + libellé));
    }

    /** Une rangée label + valeur en lecture seule (constat honnête). */
    private void rangéeConstat(LinearLayout parent, String libellé, String valeur) {
        rangéeValeur(parent, libellé, valeur);
    }

    private View rangéeValeur(LinearLayout parent, String libellé, String valeur) {
        LinearLayout rangée = new LinearLayout(requireContext());
        rangée.setOrientation(LinearLayout.HORIZONTAL);
        rangée.setGravity(Gravity.CENTER_VERTICAL);
        rangée.setPadding(0, dip(8), 0, dip(8));
        TextView label = new TextView(requireContext());
        label.setText(libellé);
        label.setTextColor(getResources().getColor(R.color.sur_var));
        label.setTextSize(13f);
        rangée.addView(label, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView val = new TextView(requireContext());
        val.setText(valeur == null ? "—" : valeur);
        val.setTextColor(getResources().getColor(R.color.sur));
        val.setTextSize(13f);
        val.setTypeface(android.graphics.Typeface.MONOSPACE);
        rangée.addView(val, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        parent.addView(rangée);
        return rangée;
    }

    /** Un bouton de texte tonal (actions secondaires). */
    private TextView boutonTexte(String libellé) {
        TextView bouton = new TextView(requireContext());
        bouton.setText(libellé);
        bouton.setTextColor(getResources().getColor(R.color.sur_sc2));
        bouton.setTextSize(13f);
        bouton.setTypeface(bouton.getTypeface(), android.graphics.Typeface.BOLD);
        bouton.setGravity(Gravity.CENTER);
        bouton.setPadding(dip(16), dip(11), dip(16), dip(11));
        bouton.setBackground(getResources().getDrawable(R.drawable.fond_tonal64));
        return bouton;
    }

    /** Un bouton CTA (action principale). */
    private TextView boutonCta(String libellé) {
        TextView bouton = new TextView(requireContext());
        bouton.setText(libellé);
        bouton.setTextColor(getResources().getColor(R.color.sur_primaire));
        bouton.setTextSize(13f);
        bouton.setTypeface(bouton.getTypeface(), android.graphics.Typeface.BOLD);
        bouton.setGravity(Gravity.CENTER);
        bouton.setPadding(dip(16), dip(11), dip(16), dip(11));
        bouton.setBackground(getResources().getDrawable(R.drawable.fond_cta));
        return bouton;
    }

    private void toast(String message) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show();
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
