package jo.joide.app.settings;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import jo.joide.app.R;
import jo.joide.app.databinding.SettingsIaPermissionsBinding;
import jo.joide.ai.PermissionGate;

/**
 * Page « IA · Permissions » (Lot 7) : le mode de la porte unique, enfin
 * **persisté** (avant : le mode vivait en mémoire, perdu à chaque
 * démarrage — la Javadoc promettait une persistance qui n\'existait pas).
 * Trois modes : demander à chaque fois (défaut sûr), accepter
 * automatiquement, proposer seulement.
 */
public final class IaPermissionsPageFragment extends Fragment {

    private SettingsIaPermissionsBinding binding;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        binding = SettingsIaPermissionsBinding.inflate(inflater, container, false);
        binding.backButton.setOnClickListener(v ->
                requireActivity().getSupportFragmentManager().popBackStack());
        renderModes();
        return binding.getRoot();
    }

    private SharedPreferences iaPrefs() {
        return requireContext().getSharedPreferences(
                SettingsIaPageFragment.IA_PREFS, Context.MODE_PRIVATE);
    }

    private void renderModes() {
        binding.modeChips.removeAllViews();
        PermissionGate.Mode active = SettingsIaHubFragment.modeOf(iaPrefs());
        for (PermissionGate.Mode mode : PermissionGate.Mode.values()) {
            TextView chip = new TextView(requireContext());
            chip.setText(labelOf(mode));
            chip.setTextSize(12.5f);
            chip.setTypeface(chip.getTypeface(), android.graphics.Typeface.BOLD);
            chip.setPadding(dip(14), dip(8), dip(14), dip(8));
            chip.setMinHeight(dip(36));
            boolean on = mode == active;
            chip.setBackgroundResource(on ? R.drawable.fond_chip_on : R.drawable.fond_chip);
            chip.setTextColor(getResources().getColor(on ? R.color.sur_sc2 : R.color.sur));
            chip.setOnClickListener(v -> {
                iaPrefs().edit().putString("mode_permission", mode.name()).apply();
                SettingsFragment.sharedGate().setMode(mode);
                renderModes();
            });
            android.widget.LinearLayout.LayoutParams params =
                    new android.widget.LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT);
            params.setMarginEnd(dip(6));
            binding.modeChips.addView(chip, params);
        }
        binding.modeDescription.setText(descriptionOf(active));
    }

    private int labelOf(PermissionGate.Mode mode) {
        return switch (mode) {
            case ASK_EACH -> R.string.settings_ia_mode_ask_each;
            case AUTO_ACCEPT -> R.string.settings_ia_mode_auto_accept;
            case PLAN_ONLY -> R.string.settings_ia_mode_plan_only;
        };
    }

    private int descriptionOf(PermissionGate.Mode mode) {
        return switch (mode) {
            case ASK_EACH -> R.string.settings_ia_mode_ask_each_desc;
            case AUTO_ACCEPT -> R.string.settings_ia_mode_auto_accept_desc;
            case PLAN_ONLY -> R.string.settings_ia_mode_plan_only_desc;
        };
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
