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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import jo.joide.app.R;
import jo.joide.app.databinding.SheetNouveauModuleBinding;

/**
 * Feuille « Nouveau module » (lot L10, d'après le dialogue de la référence) :
 * nom, type (les types RÉELS de Forge : bibliothèque Java, bibliothèque
 * Kotlin, application Android), niveau Java (8/11/17/21). Crée les
 * répertoires réels (les racines « primaires » seulement — pas de forêt de
 * dossiers vides, comme la référence), la facette Android minimale
 * (namespace dérivé, minSdk 28 — ADR-009, targetSdk 34) pour une
 * application, et inscrit le module dans {@code forge.json}.
 */
public class NewModuleSheet extends BottomSheetDialogFragment {

    /** Types proposés (index des chips) — libellés et valeurs réels. */
    private static final String[] TYPES = {"java-library", "java-library", "application"};
    private static final boolean[] KOTLIN = {false, true, false};
    private static final int[] LIBELLÉS = {R.string.modules_type_java,
            R.string.modules_type_kotlin, R.string.modules_type_android};
    private static final int[] NIVEAUX = {8, 11, 17, 21};

    private SheetNouveauModuleBinding binding;

    private int typeChoisi = 2; // application Android par défaut (studio).
    private int javaChoisi = 2; // 17 par défaut (convention Forge).

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
            @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = SheetNouveauModuleBinding.inflate(inflater, container, false);
        rendreChipsType();
        rendreChipsJava();
        binding.nouveauModuleCreer.setOnClickListener(v -> créer());
        return binding.getRoot();
    }

    private void rendreChipsType() {
        binding.nouveauModuleTypes.removeAllViews();
        for (int i = 0; i < LIBELLÉS.length; i++) {
            final int index = i;
            TextView chip = chip(getString(LIBELLÉS[i]), i == typeChoisi);
            chip.setOnClickListener(v -> {
                typeChoisi = index;
                rendreChipsType();
            });
            binding.nouveauModuleTypes.addView(chip);
        }
    }

    private void rendreChipsJava() {
        binding.nouveauModuleJava.removeAllViews();
        for (int i = 0; i < NIVEAUX.length; i++) {
            final int index = i;
            TextView chip = chip(String.valueOf(NIVEAUX[i]), i == javaChoisi);
            chip.setOnClickListener(v -> {
                javaChoisi = index;
                rendreChipsJava();
            });
            binding.nouveauModuleJava.addView(chip);
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

    /** Crée le module : modèle + répertoires réels + facette éventuelle. */
    private void créer() {
        Fragment parent = getParentFragment();
        if (!(parent instanceof ModelHost hôte)) {
            dismiss();
            return;
        }
        String nom = binding.nouveauModuleNom.getText().toString().trim();
        if (nom.isEmpty() || !nomValide(nom)) {
            erreur(getString(R.string.modules_nouveau_invalide));
            return;
        }
        if (hôte.model().module(nom) != null) {
            erreur(getString(R.string.modules_nouveau_doublon, nom));
            return;
        }
        String type = TYPES[typeChoisi];
        boolean kotlin = KOTLIN[typeChoisi];
        hôte.model().addModule(nom, type, NIVEAUX[javaChoisi]);
        if (kotlin) {
            hôte.model().setModuleField(nom, "language", "kotlin");
        }
        // Répertoires réels : la racine source primaire, les ressources, et
        // pour une application la facette minimale + le manifeste.
        Path racine = hôte.model().file().getParent().resolve(nom);
        try {
            Path sources = racine.resolve(kotlin ? "src/main/kotlin" : "src/main/java");
            Files.createDirectories(sources);
            Files.createDirectories(racine.resolve("src/main/resources"));
            if (type.equals("application")) {
                Files.createDirectories(racine.resolve("src/main/res"));
                écrireManifeste(racine, namespaceDe(nom));
                hôte.model().setAndroidField(nom, "namespace", namespaceDe(nom));
                hôte.model().setAndroidField(nom, "manifest", "src/main/AndroidManifest.xml");
                hôte.model().setAndroidField(nom, "minSdk", 28L);
                hôte.model().setAndroidField(nom, "targetSdk", 34L);
                hôte.model().setAndroidField(nom, "versionCode", 1L);
                hôte.model().setAndroidField(nom, "versionName", "1.0");
            }
        } catch (IOException e) {
            // Le modèle est inscrit ; les répertoires échouent = dit, pas muet.
            erreur(getString(R.string.modules_nouveau_dossiers, e.getMessage()));
            hôte.mutate("nouveau module " + nom);
            return;
        }
        hôte.mutate("nouveau module " + nom);
        dismiss();
    }

    /** Manifeste Android minimal (comme le gabarit de création de projet). */
    private static void écrireManifeste(Path racine, String namespace) throws IOException {
        Path manifeste = racine.resolve("src/main/AndroidManifest.xml");
        if (Files.exists(manifeste)) {
            return;
        }
        Files.writeString(manifeste, """
                <?xml version="1.0" encoding="utf-8"?>
                <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                        package="%s">
                    <application android:label="@string/app_name" />
                </manifest>
                """.formatted(namespace));
    }

    /** Namespace dérivé du nom (minuscules, segments sûrs). */
    static String namespaceDe(String nom) {
        String nettoyé = nom.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_]", "");
        return "io.joide." + (nettoyé.isEmpty() ? "module" : nettoyé);
    }

    /** Nom de module valide (même règle que la référence : lettres/chiffres/-/_). */
    static boolean nomValide(String nom) {
        return nom.matches("[A-Za-z][A-Za-z0-9_-]*");
    }

    private void erreur(String message) {
        binding.nouveauModuleErreur.setText(message);
        binding.nouveauModuleErreur.setVisibility(View.VISIBLE);
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
