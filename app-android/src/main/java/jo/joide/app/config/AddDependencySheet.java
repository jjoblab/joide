package jo.joide.app.config;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
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
import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import jo.joide.app.R;
import jo.joide.app.databinding.SheetAjoutDepBinding;
import jo.joide.buildforge.MavenCentralSearch;
import jo.joide.coremodel.ForgeJson;

/**
 * Feuille « Ajouter une dépendance » (lot L10, d'après le parcours d'ajout de
 * la référence — REF-129) : **recherche en ligne Maven Central** (le solr
 * public, debounce 320 ms — &lt; 2 caractères : rien), coordonnée directe
 * ({@code g:a:v} détectée dès 2 segments), cible module (chips), portée
 * (chips). Fichier local : limite honnête affichée (forge.json ne porte pas
 * encore les dépendances de fichier local — F-07 ouvert). Google Maven
 * n'est PAS interrogé — la feuille le dit.
 */
public class AddDependencySheet extends BottomSheetDialogFragment {

    /** Portées réelles de forge.json (ModelCodec). */
    private static final String[] PORTÉES = {"implementation", "api",
            "compileOnly", "runtimeOnly"};

    private static final long DÉBOUNCE_MS = 320;

    private SheetAjoutDepBinding binding;

    private final Handler fil = new Handler(Looper.getMainLooper());
    private final AtomicInteger génération = new AtomicInteger();

    private final MavenCentralSearch recherche = new MavenCentralSearch();

    /** Modules du projet (cibles possibles). */
    private List<String> modules;

    /** Module cible choisi (défaut : argument, sinon le premier). */
    private String cible;

    /** Portée choisie (défaut : implementation). */
    private int portée = 0;

    /** La coordonnée sélectionnée (résultat tapé ou saisie directe). */
    private MavenCentralSearch.Artifact sélection;

    private TextView[] chipsPortée;

    /** Feuille pour un projet donné, module cible par défaut. */
    public static AddDependencySheet pour(String moduleParDéfaut) {
        AddDependencySheet sheet = new AddDependencySheet();
        Bundle args = new Bundle();
        args.putString("module", moduleParDéfaut);
        sheet.setArguments(args);
        return sheet;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
            @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = SheetAjoutDepBinding.inflate(inflater, container, false);
        cible = getArguments() != null ? getArguments().getString("module") : null;

        binding.depChamp.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }

            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }

            @Override public void afterTextChanged(Editable s) {
                planifierRecherche();
            }
        });
        binding.depAjouterBouton.setOnClickListener(v -> ajouter());
        return binding.getRoot();
    }

    @Override
    public void onStart() {
        super.onStart();
        // Les chips dépendent du modèle (cibles réelles) : rendues à l'ouverture.
        Fragment parent = getParentFragment();
        if (parent instanceof ModelHost hôte) {
            modules = hôte.model().modules().stream()
                    .map(ForgeJson.ModuleView::name).toList();
            if (cible == null && !modules.isEmpty()) {
                cible = modules.get(0);
            }
            rendreChipsModules();
        }
        rendreChipsPortées();
    }

    // ---- Recherche (debounce 320 ms, une requête par frappe stable) --------

    private void planifierRecherche() {
        fil.removeCallbacksAndMessages(null);
        String texte = binding.depChamp.getText().toString().trim();
        sélection = null;
        // Coordonnée directe : 2-3 segments non vides → sélection immédiate.
        String[] segments = texte.split(":");
        if (segments.length >= 2 && segments.length <= 3
                && !segments[0].isBlank() && !segments[1].isBlank()) {
            sélection = new MavenCentralSearch.Artifact(segments[0], segments[1],
                    segments.length == 3 ? segments[2] : "", "jar");
            état(getString(R.string.dep_coordonnee_directe, sélection.coordinate()),
                    R.color.sur_conteneur_tertiaire, R.drawable.fond_pastille_tertiaire);
            binding.depResultats.removeAllViews();
            return;
        }
        if (texte.length() < 2) {
            binding.depResultats.removeAllViews();
            état(getString(R.string.dep_recherche_min), R.color.sur_var,
                    R.drawable.fond_carte_bas);
            return;
        }
        état(getString(R.string.dep_recherche_en_cours), R.color.sur_var,
                R.drawable.fond_carte_bas);
        int ticket = génération.incrementAndGet();
        fil.postDelayed(() -> chercher(texte, ticket), DÉBOUNCE_MS);
    }

    private void chercher(String terme, int ticket) {
        new Thread(() -> {
            List<MavenCentralSearch.Artifact> trouvés;
            IOException échec = null;
            try {
                trouvés = recherche.search(terme, 12);
            } catch (IOException e) {
                trouvés = List.of();
                échec = e;
            }
            List<MavenCentralSearch.Artifact> résultats = trouvés;
            IOException erreur = échec;
            if (binding == null) {
                return;
            }
            binding.depResultats.post(() -> {
                if (binding == null || ticket != génération.get()) {
                    return; // une frappe plus récente a repris la main.
                }
                binding.depResultats.removeAllViews();
                if (erreur != null) {
                    // Index injoignable (réseau) — distinct d'« aucun résultat ».
                    état(getString(R.string.dep_index_injoignable,
                            erreur.getMessage()), R.color.sur_conteneur_erreur,
                            R.drawable.fond_carte_erreur);
                    return;
                }
                if (résultats.isEmpty()) {
                    état(getString(R.string.dep_aucun_resultat), R.color.sur_var,
                            R.drawable.fond_carte_bas);
                    return;
                }
                état(getString(R.string.dep_resultats_source), R.color.sur_var,
                        R.drawable.fond_carte_bas);
                for (MavenCentralSearch.Artifact artefact : résultats) {
                    binding.depResultats.addView(rangéeArtefact(artefact));
                }
            });
        }, "recherche-maven").start();
    }

    /** Une rangée de résultat : g:a:v, tap = sélection. */
    private View rangéeArtefact(MavenCentralSearch.Artifact artefact) {
        LinearLayout rangée = new LinearLayout(requireContext());
        rangée.setOrientation(LinearLayout.VERTICAL);
        rangée.setPadding(dip(14), dip(10), dip(14), dip(10));
        rangée.setBackground(getResources().getDrawable(R.drawable.fond_carte_bas));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dip(6);
        rangée.setLayoutParams(params);
        rangée.setOnClickListener(v -> {
            sélection = artefact;
            binding.depChamp.setText(artefact.coordinate());
            binding.depChamp.setSelection(binding.depChamp.getText().length());
        });
        TextView nom = new TextView(requireContext());
        nom.setText(artefact.artifact() + " " + artefact.version());
        nom.setTextColor(getResources().getColor(R.color.sur));
        nom.setTextSize(14f);
        nom.setTypeface(nom.getTypeface(), android.graphics.Typeface.BOLD);
        rangée.addView(nom);
        TextView groupe = new TextView(requireContext());
        groupe.setText(artefact.group());
        groupe.setTextColor(getResources().getColor(R.color.sur_var));
        groupe.setTextSize(12f);
        rangée.addView(groupe);
        return rangée;
    }

    /** Message d'état (recherche, aucun résultat, injoignable, directe). */
    private void état(String message, int couleur, int fond) {
        binding.depEtat.setText(message);
        binding.depEtat.setTextColor(ContextCompat.getColor(requireContext(), couleur));
        binding.depEtat.setBackground(
                ContextCompat.getDrawable(requireContext(), fond));
        binding.depEtat.setVisibility(View.VISIBLE);
    }

    // ---- Chips de cible et de portée ----------------------------------------

    private void rendreChipsModules() {
        binding.depModules.removeAllViews();
        if (modules == null) {
            return;
        }
        for (String module : modules) {
            TextView chip = chip(":" + module, module.equals(cible));
            chip.setOnClickListener(v -> {
                cible = module;
                rendreChipsModules();
            });
            binding.depModules.addView(chip);
        }
    }

    private void rendreChipsPortées() {
        if (chipsPortée != null) {
            return; // rendues une fois (portées fixes).
        }
        chipsPortée = new TextView[PORTÉES.length];
        for (int i = 0; i < PORTÉES.length; i++) {
            final int index = i;
            TextView chip = chip(PORTÉES[i], i == portée);
            chip.setOnClickListener(v -> {
                portée = index;
                for (int k = 0; k < chipsPortée.length; k++) {
                    chipsPortée[k].setBackground(getResources().getDrawable(
                            k == portée ? R.drawable.fond_chip_on : R.drawable.fond_chip));
                    chipsPortée[k].setTextColor(getResources().getColor(
                            k == portée ? R.color.sur_sc2 : R.color.sur));
                }
            });
            chipsPortée[i] = chip;
            binding.depPortees.addView(chip);
        }
    }

    private TextView chip(String libellé, boolean on) {
        TextView chip = new TextView(requireContext());
        chip.setText(libellé);
        chip.setTextColor(getResources().getColor(on ? R.color.sur_sc2 : R.color.sur));
        chip.setTextSize(13f);
        chip.setTypeface(chip.getTypeface(), android.graphics.Typeface.BOLD);
        chip.setGravity(android.view.Gravity.CENTER);
        chip.setPadding(dip(15), 0, dip(15), 0);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dip(38));
        params.rightMargin = dip(8);
        chip.setLayoutParams(params);
        chip.setBackground(getResources().getDrawable(
                on ? R.drawable.fond_chip_on : R.drawable.fond_chip));
        return chip;
    }

    // ---- Ajout ----------------------------------------------------------------

    private void ajouter() {
        Fragment parent = getParentFragment();
        if (!(parent instanceof ModelHost hôte)) {
            dismiss();
            return;
        }
        if (cible == null) {
            erreur(getString(R.string.dep_pas_de_module));
            return;
        }
        if (sélection == null || sélection.group() == null
                || sélection.group().isBlank() || sélection.artifact().isBlank()
                || sélection.version().isBlank()) {
            erreur(getString(R.string.dep_rien_selectionne));
            return;
        }
        boolean ajoutée = hôte.model().addDependency(cible, new ForgeJson.Dependency(
                "maven", null, sélection.group(), sélection.artifact(),
                sélection.version(), PORTÉES[portée]));
        if (!ajoutée) {
            erreur(getString(R.string.dep_doublon, sélection.coordinate()));
            return;
        }
        hôte.mutate("dépendance " + sélection.coordinate() + " → " + cible);
        dismiss();
    }

    private void erreur(String message) {
        binding.depErreur.setText(message);
        binding.depErreur.setVisibility(View.VISIBLE);
    }

    private int dip(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        fil.removeCallbacksAndMessages(null);
        binding = null;
    }
}
