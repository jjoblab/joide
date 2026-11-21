package jo.joide.app.settings;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import java.util.Arrays;
import java.util.List;
import jo.joide.app.R;
import jo.joide.app.databinding.SettingsIaChipBinding;
import jo.joide.app.databinding.SettingsIaPageBinding;

/**
 * Page « IA · Comportement » (Lot 7) : effort de raisonnement, Recherche web,
 * Serveur MCP, plafonds (tokens max, itérations max) — chaque contrôle écrit
 * ET est lu (matrice de câblage : docs/analyse-ia.md).
 */
public final class IaBehaviorPageFragment extends Fragment {

    private static final List<String> EFFORTS = Arrays.asList(
            "defaut", "aucun", "faible", "moyen", "eleve");
    private static final List<Integer> TOKEN_STEPS = Arrays.asList(
            2048, 4096, 8192, 16384);
    private static final List<Integer> ITERATION_STEPS = Arrays.asList(
            8, 16, 24, 32);

    private SettingsIaPageBinding binding;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        binding = SettingsIaPageBinding.inflate(inflater, container, false);
        binding.backButton.setOnClickListener(v ->
                requireActivity().getSupportFragmentManager().popBackStack());
        wireEffortChips();
        wireSwitches();
        wireAdvanced();
        return binding.getRoot();
    }

    private void wireEffortChips() {
        effortChip(binding.chipEffortDefault, R.string.settings_ia_effort_default, "defaut");
        effortChip(binding.chipEffortNone, R.string.settings_ia_effort_none, "aucun");
        effortChip(binding.chipEffortLow, R.string.settings_ia_effort_low, "faible");
        effortChip(binding.chipEffortMedium, R.string.settings_ia_effort_medium, "moyen");
        effortChip(binding.chipEffortHigh, R.string.settings_ia_effort_high, "eleve");
        renderEffortChips();
    }

    private void effortChip(SettingsIaChipBinding view, int label, String id) {
        view.iaChip.setText(getString(label));
        view.iaChip.setTag(id);
        view.iaChip.setOnClickListener(v -> {
            iaPrefs().edit().putString("effort", id).apply();
            renderEffortChips();
        });
    }

    private void renderEffortChips() {
        String active = iaPrefs().getString("effort", "moyen");
        for (SettingsIaChipBinding chip : Arrays.asList(
                binding.chipEffortDefault, binding.chipEffortNone,
                binding.chipEffortLow, binding.chipEffortMedium,
                binding.chipEffortHigh)) {
            boolean on = active.equals(chip.iaChip.getTag());
            chip.iaChip.setBackgroundResource(on
                    ? R.drawable.fond_chip_on : R.drawable.fond_chip);
            chip.iaChip.setTextColor(getResources().getColor(
                    on ? R.color.sur_sc2 : R.color.sur));
        }
    }

    private void wireSwitches() {
        binding.webSwitch.setChecked(iaPrefs().getBoolean("recherche_web", true));
        binding.webSwitch.setOnCheckedChangeListener((button, checked) ->
                iaPrefs().edit().putBoolean("recherche_web", checked).apply());
        binding.mcpSwitch.setChecked(iaPrefs().getBoolean("serveur_mcp", false));
        binding.mcpSwitch.setOnCheckedChangeListener((button, checked) ->
                iaPrefs().edit().putBoolean("serveur_mcp", checked).apply());
    }

    private void wireAdvanced() {
        binding.tokensPill.setText(String.valueOf(iaPrefs().getInt("tokens_max", 8192)));
        binding.tokensRow.setOnClickListener(v -> {
            int current = iaPrefs().getInt("tokens_max", 8192);
            int index = Math.max(0, TOKEN_STEPS.indexOf(current));
            int next = TOKEN_STEPS.get((index + 1) % TOKEN_STEPS.size());
            iaPrefs().edit().putInt("tokens_max", next).apply();
            binding.tokensPill.setText(String.valueOf(next));
        });
        binding.iterationsPill.setText(String.valueOf(iaPrefs().getInt("iterations_max", 16)));
        binding.iterationsRow.setOnClickListener(v -> {
            int current = iaPrefs().getInt("iterations_max", 16);
            int index = Math.max(0, ITERATION_STEPS.indexOf(current));
            int next = ITERATION_STEPS.get((index + 1) % ITERATION_STEPS.size());
            iaPrefs().edit().putInt("iterations_max", next).apply();
            binding.iterationsPill.setText(String.valueOf(next));
        });
    }

    private SharedPreferences iaPrefs() {
        return requireContext().getSharedPreferences(
                SettingsIaPageFragment.IA_PREFS, Context.MODE_PRIVATE);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
