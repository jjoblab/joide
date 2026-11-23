package jo.joide.app.config;

import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import java.io.IOException;
import jo.joide.app.R;
import jo.joide.app.databinding.RuntimeFragmentBinding;
import jo.joide.buildforge.EngineRuntimePrefs;
import jo.joide.buildforge.EngineSettings;

/**
 * Écran « Runtime de build » (lot L10, d'après la page Build Runtime de la
 * référence — REF-130) : des réglages RÉELS, bornés par les décisions du
 * moteur, et des constats honnêtes là où il n'y a rien à régler.
 *
 * <ul>
 *   <li><b>Tas du moteur</b> : glissière bornée [256, 1024] Mo (pas 64 — ce
 *       que le moteur accepte), ou « Auto » (décision Forge : 1024 Mo, 256 Mo
 *       sous 2,5 Go de RAM).</li>
 *   <li><b>Mode basse mémoire</b> : Auto / Activé / Désactivé (seuil réel
 *       2,5 Go).</li>
 *   <li><b>Constats</b> : le moteur Forge EST un processus séparé (toujours —
 *       un OOM du moteur ne tue jamais l'IDE) ; la médiation de conflits est
 *       NEWEST (v0.2.28), non réglable.</li>
 * </ul>
 *
 * <p>Persistance : {@link EngineRuntimePrefs} (fichier Properties) ;
 * application au PROCHAIN lancement du moteur — un moteur lancé garde son
 * tas, l'écran le dit.</p>
 */
public class BuildRuntimeFragment extends Fragment {

    private RuntimeFragmentBinding binding;

    private EngineRuntimePrefs prefs;
    private EngineSettings réglages = EngineSettings.auto();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
            @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = RuntimeFragmentBinding.inflate(inflater, container, false);
        prefs = new EngineRuntimePrefs(requireContext().getFilesDir()
                .toPath().resolve("runtime.properties"));
        réglages = prefs.read();

        binding.runtimeBack.setOnClickListener(v ->
                getParentFragmentManager().popBackStack());

        setupTas();
        setupBasseMémoire();
        return binding.getRoot();
    }

    // ---- Tas du moteur : glissière bornée + chip Auto ------------------------

    private void setupTas() {
        int max = (EngineSettings.TAS_MAX - EngineSettings.TAS_MIN) / 64;
        binding.runtimeTasBarre.setMax(max);
        if (réglages.heapMbOverride() > 0) {
            int position = (réglages.heapMbOverride() - EngineSettings.TAS_MIN) / 64;
            binding.runtimeTasBarre.setProgress(position);
            binding.runtimeTasBarre.setEnabled(true);
            rendreTas(réglages.heapMbOverride());
        } else {
            // Auto : la glissière au maximum, désactivée (aucun faux réglage).
            binding.runtimeTasBarre.setProgress(max);
            binding.runtimeTasBarre.setEnabled(false);
            rendreTas(0);
        }
        binding.runtimeTasBarre.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar barre, int position, boolean fromUser) {
                if (!fromUser) {
                    return;
                }
                int tas = EngineSettings.TAS_MIN + position * 64;
                rendreTas(tas);
                enregistrer(new EngineSettings(tas, réglages.mode()));
            }

            @Override public void onStartTrackingTouch(SeekBar barre) { }

            @Override public void onStopTrackingTouch(SeekBar barre) { }
        });
        binding.runtimeTasAuto.setOnClickListener(v -> {
            binding.runtimeTasBarre.setEnabled(false);
            binding.runtimeTasBarre.setProgress(binding.runtimeTasBarre.getMax());
            rendreTas(0);
            enregistrer(new EngineSettings(0, réglages.mode()));
        });
    }

    /** « Auto — 1024 Mo » (ou 256 sous le seuil) / « 512 Mo ». */
    private void rendreTas(int tas) {
        String texte;
        if (tas > 0) {
            texte = getString(R.string.runtime_tas_explicite, tas);
        } else {
            boolean basseMémoire = switch (réglages.mode()) {
                case FORCÉ -> true;
                case DÉSACTIVÉ -> false;
                case AUTO -> totalRamMb() > 0 && totalRamMb() < 2500;
            };
            texte = getString(R.string.runtime_tas_auto_valeur,
                    basseMémoire ? 256 : 1024);
        }
        binding.runtimeTasValeur.setText(texte);
    }

    /** RAM totale de l'appareil en Mo (0 : inconnue). */
    private long totalRamMb() {
        android.app.ActivityManager mémoire = (android.app.ActivityManager)
                requireContext().getSystemService(android.content.Context.ACTIVITY_SERVICE);
        android.app.ActivityManager.MemoryInfo infos =
                new android.app.ActivityManager.MemoryInfo();
        if (mémoire == null) {
            return 0;
        }
        mémoire.getMemoryInfo(infos);
        return infos.totalMem / (1024L * 1024L);
    }

    // ---- Mode basse mémoire : chips Auto / Activé / Désactivé ---------------

    private void setupBasseMémoire() {
        EngineSettings.LowMemory[] modes = EngineSettings.LowMemory.values();
        int[] libellés = {R.string.runtime_basse_memoire_auto,
                R.string.runtime_basse_memoire_force, R.string.runtime_basse_memoire_off};
        binding.runtimeBasseMemoireChips.removeAllViews();
        for (int i = 0; i < modes.length; i++) {
            final EngineSettings.LowMemory mode = modes[i];
            TextView chip = new TextView(requireContext());
            chip.setText(libellés[i]);
            chip.setTextColor(getResources().getColor(
                    mode == réglages.mode() ? R.color.sur_sc2 : R.color.sur));
            chip.setTextSize(13f);
            chip.setTypeface(chip.getTypeface(), android.graphics.Typeface.BOLD);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(dip(15), 0, dip(15), 0);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dip(38));
            params.rightMargin = dip(8);
            chip.setLayoutParams(params);
            chip.setBackground(getResources().getDrawable(
                    mode == réglages.mode() ? R.drawable.fond_chip_on : R.drawable.fond_chip));
            chip.setOnClickListener(v -> {
                réglages = new EngineSettings(réglages.heapMbOverride(), mode);
                enregistrer(réglages);
                setupBasseMémoire();
                rendreTas(réglages.heapMbOverride());
            });
            binding.runtimeBasseMemoireChips.addView(chip);
        }
    }

    // ---- Persistance (immédiate, application au prochain lancement) ---------

    private void enregistrer(EngineSettings nouveaux) {
        réglages = nouveaux;
        try {
            prefs.write(nouveaux);
        } catch (IOException e) {
            Toast.makeText(requireContext(),
                    getString(R.string.runtime_ecriture_impossible, e.getMessage()),
                    Toast.LENGTH_SHORT).show();
        }
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
