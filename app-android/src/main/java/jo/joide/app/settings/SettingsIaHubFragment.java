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
import jo.joide.app.R;
import jo.joide.app.ai.EncryptedKeyVault;
import jo.joide.app.databinding.SettingsIaHubBinding;
import jo.joide.ai.PermissionGate;
import jo.joide.ai.provider.ProviderRegistry;
import jo.joide.ai.provider.ProviderSpec;

/**
 * Hub « Réglages IA » (Lot 7) : quatre sous-pages réelles — Fournisseurs,
 * Modèles, Comportement, Permissions — chacune résumée par un état vivant
 * (clé présente, modèle choisi, effort, mode de la porte). Cohérent avec le
 * hub des paramètres généraux (mêmes cartes, icône + titre + état).
 */
public final class SettingsIaHubFragment extends Fragment {

    private SettingsIaHubBinding binding;
    private EncryptedKeyVault vault;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        binding = SettingsIaHubBinding.inflate(inflater, container, false);
        vault = new EncryptedKeyVault(requireContext());
        renderStates();
        binding.rowProviders.setOnClickListener(v ->
                open(new SettingsIaPageFragment()));
        binding.rowModels.setOnClickListener(v ->
                open(new IaModelsPageFragment()));
        binding.rowBehavior.setOnClickListener(v ->
                open(new IaBehaviorPageFragment()));
        binding.rowPermissions.setOnClickListener(v ->
                open(new IaPermissionsPageFragment()));
        return binding.getRoot();
    }

    /** États résumés : valeurs lues des préférences et du coffre, rien de figé. */
    private void renderStates() {
        SharedPreferences prefs = requireContext().getSharedPreferences(
                SettingsIaPageFragment.IA_PREFS, Context.MODE_PRIVATE);
        ProviderSpec spec = ProviderRegistry.byId(
                prefs.getString("fournisseur", null))
                .orElseGet(ProviderRegistry::defaultProvider);
        boolean keyPresent = vault.apiKey(spec.id()) != null;
        binding.providersState.setText(getString(
                keyPresent && !spec.keyOptional()
                        ? R.string.settings_ia_state_key_present
                        : R.string.settings_ia_state_key_missing)
                + " · " + spec.displayName());
        String model = prefs.getString("modele_" + spec.id(), null);
        binding.modelsState.setText(model == null || model.isEmpty()
                ? getString(R.string.settings_ia_model_none) : model);
        String effort = prefs.getString("effort", "moyen");
        binding.behaviorState.setText(getString(R.string.settings_ia_effort_of, effortLabel(effort))
                + " · " + prefs.getInt("tokens_max", 8192) + " tokens max");
        binding.permissionsState.setText(modeLabel(modeOf(prefs)));
    }

    private String effortLabel(String persisted) {
        int res = switch (persisted == null ? "defaut" : persisted) {
            case "aucun" -> R.string.settings_ia_effort_none;
            case "faible" -> R.string.settings_ia_effort_low;
            case "eleve" -> R.string.settings_ia_effort_high;
            case "defaut" -> R.string.settings_ia_effort_default;
            default -> R.string.settings_ia_effort_medium;
        };
        return getString(res);
    }

    /** Mode de la porte, restauré des préférences (persisté au Lot 7). */
    public static PermissionGate.Mode modeOf(SharedPreferences prefs) {
        String name = prefs.getString("mode_permission", null);
        if (name != null) {
            try {
                return PermissionGate.Mode.valueOf(name);
            } catch (IllegalArgumentException ignored) {
                // Valeur inconnue : le défaut sûr s'applique.
            }
        }
        return PermissionGate.Mode.ASK_EACH;
    }

    private String modeLabel(PermissionGate.Mode mode) {
        int res = switch (mode) {
            case ASK_EACH -> R.string.settings_ia_mode_ask_each;
            case AUTO_ACCEPT -> R.string.settings_ia_mode_auto_accept;
            case PLAN_ONLY -> R.string.settings_ia_mode_plan_only;
        };
        return getString(res);
    }

    private void open(Fragment page) {
        requireActivity().getSupportFragmentManager().beginTransaction()
                .replace(R.id.container, page)
                .addToBackStack(null)
                .commit();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
