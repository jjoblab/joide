package jo.joide.app.settings;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import java.util.Arrays;
import java.util.List;
import jo.joide.app.R;
import jo.joide.app.databinding.SettingsIaChipBinding;
import jo.joide.app.databinding.SettingsIaPageBinding;
import jo.joide.app.ai.EncryptedKeyVault;
import jo.joide.ai.AiProvider;
import jo.joide.ai.HttpLlmTransport;
import jo.joide.ai.PermissionGate;

/**
 * Page « IA · Fournisseurs » (studio HTML : settingsPageScreen) : chips du
 * fournisseur actif, carte clé au coffre chiffré, modèle, effort de
 * raisonnement, switchs Recherche web / Serveur MCP, carte Avancé (pills
 * conteneur_primaire), notice honnête warn_soft.
 *
 * <p>Câblage réel conservé (lot L8, conception §8) : fournisseur actif et clé
 * au coffre {@link EncryptedKeyVault} (« laboratoire » / « passerelle » — les
 * identifiants historiques), modèle par fournisseur, effort, switchs et
 * plafonds persistés en SharedPreferences ({@code joide_reglages_ia}) comme
 * pour le mode de permission de la porte partagée.</p>
 */
public final class SettingsIaPageFragment extends Fragment {

    /** Préférences IA de la coquille (fournisseur, modèle, effort, plafonds). */
    public static final String IA_PREFS = "joide_reglages_ia";
    private static final List<String> EFFORTS = Arrays.asList(
            "defaut", "aucun", "faible", "moyen", "eleve");
    private static final List<Integer> TOKEN_STEPS = Arrays.asList(
            2048, 4096, 8192, 16384);
    private static final List<Integer> ITERATION_STEPS = Arrays.asList(
            8, 16, 24, 32);

    private SettingsIaPageBinding binding;
    private EncryptedKeyVault vault;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        binding = SettingsIaPageBinding.inflate(inflater, container, false);
        vault = new EncryptedKeyVault(requireContext());

        binding.backButton.setOnClickListener(v ->
                requireActivity().getSupportFragmentManager().popBackStack());

        wireProviderChips();
        wireKeyCard();
        wireModelRow();
        wireEffortChips();
        wireSwitches();
        wireAdvanced();
        return binding.getRoot();
    }

    // ---- Fournisseur actif (chips studio) -------------------------------------

    /** Fournisseur persisté : « laboratoire » par défaut (identifiant du coffre). */
    private String providerId() {
        return iaPrefs().getString("fournisseur", "laboratoire");
    }

    private void wireProviderChips() {
        // Les chips du studio (Anthropic, Gemini, OpenAI, Passerelle) mappent les
        // deux fournisseurs réels du port : laboratoire et passerelle.
        chip(binding.chipAnthropic, R.string.settings_ia_provider_anthropic, "laboratoire");
        chip(binding.chipGemini, R.string.settings_ia_provider_gemini, "laboratoire");
        chip(binding.chipOpenai, R.string.settings_ia_provider_openai, "laboratoire");
        chip(binding.chipGateway, R.string.settings_ia_provider_gateway, "passerelle");
        renderProviderChips();
    }

    /** Pose le texte et le clic d'une chip fournisseur. */
    private void chip(SettingsIaChipBinding view, int label, String id) {
        view.iaChip.setText(getString(label));
        view.iaChip.setTag(id);
        view.iaChip.setOnClickListener(v -> {
            iaPrefs().edit().putString("fournisseur", id).apply();
            renderProviderChips();
            renderKeyCard();
        });
    }

    /** Sélection visible : ON = sc2/sur_sc2 (studio), OFF = chip normal. */
    private void renderProviderChips() {
        String active = providerId();
        for (SettingsIaChipBinding chip : Arrays.asList(
                binding.chipAnthropic, binding.chipGemini,
                binding.chipOpenai, binding.chipGateway)) {
            boolean on = active.equals(chip.iaChip.getTag());
            chip.iaChip.setBackgroundResource(on
                    ? R.drawable.fond_chip_on : R.drawable.fond_chip);
            chip.iaChip.setTextColor(getResources().getColor(
                    on ? R.color.sur_sc2 : R.color.sur));
        }
    }

    // ---- Clé au coffre (carte studio + champ mono) ---------------------------

    private void wireKeyCard() {
        renderKeyCard();
        binding.keySaveButton.setOnClickListener(v -> {
            String value = binding.keyField.getText().toString().trim();
            String provider = providerId();
            if (value.isEmpty()) {
                vault.removeApiKey(provider);
                Toast.makeText(requireContext(),
                        R.string.settings_ia_key_removed, Toast.LENGTH_SHORT).show();
            } else {
                vault.putApiKey(provider, value);
                Toast.makeText(requireContext(),
                        R.string.settings_ia_key_stored, Toast.LENGTH_SHORT).show();
            }
            renderKeyCard();
        });
    }

    /** Champ mono : la clé existante masquée (sk-ant-••••…), jamais affichée. */
    private void renderKeyCard() {
        String existing = vault.apiKey(providerId());
        if (existing != null) {
            binding.keyField.setHint(masked(existing));
            binding.keyField.setText("");
        } else {
            binding.keyField.setHint(getString(R.string.settings_ia_key_hint));
            binding.keyField.setText("");
        }
    }

    /** Masque la clé (seuls le préfixe et les 4 derniers caractères restent). */
    private static String masked(String key) {
        String prefix = key.length() >= 7 ? key.substring(0, 7) : key;
        return prefix + "•••••••••••••••";
    }

    // ---- Modèle (rangée + chevD) ---------------------------------------------

    private void wireModelRow() {
        String provider = providerId();
        AiProvider ai = "passerelle".equals(provider)
                ? AiProvider.gateway(new HttpLlmTransport())
                : AiProvider.laboratory(new HttpLlmTransport());
        List<String> models = ai.staticModels();
        String current = iaPrefs().getString("modele_" + provider,
                models.isEmpty() ? "claude-4-sonnet" : models.get(0));
        binding.modelName.setText(current);
        binding.modelRow.setOnClickListener(v -> {
            int next = (models.indexOf(current) + 1) % Math.max(models.size(), 1);
            String value = models.isEmpty() ? current : models.get(next);
            iaPrefs().edit().putString("modele_" + provider, value).apply();
            binding.modelName.setText(value);
        });
    }

    // ---- Effort de raisonnement (chips) --------------------------------------

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
        String active = effort();
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

    /** Effort persisté (moyen par défaut, comme l'écran du studio). */
    static String effort() {
        return effortOf(null);
    }

    /** Effort d'un contexte (null : préférences de ce processus). */
    static String effortOf(Context context) {
        if (context == null) {
            return "moyen";
        }
        return context.getSharedPreferences(IA_PREFS, Context.MODE_PRIVATE)
                .getString("effort", "moyen");
    }

    // ---- Switchs (Recherche web / Serveur MCP) -------------------------------

    private void wireSwitches() {
        binding.webSwitch.setChecked(
                iaPrefs().getBoolean("recherche_web", true));
        binding.webSwitch.setOnCheckedChangeListener((button, checked) ->
                iaPrefs().edit().putBoolean("recherche_web", checked).apply());
        binding.mcpSwitch.setChecked(
                iaPrefs().getBoolean("serveur_mcp", false));
        binding.mcpSwitch.setOnCheckedChangeListener((button, checked) ->
                iaPrefs().edit().putBoolean("serveur_mcp", checked).apply());
    }

    // ---- Avancé (tokens max / itérations max) ---------------------------------

    private void wireAdvanced() {
        binding.tokensPill.setText(String.valueOf(
                iaPrefs().getInt("tokens_max", 8192)));
        binding.tokensRow.setOnClickListener(v -> {
            int current = iaPrefs().getInt("tokens_max", 8192);
            int next = TOKEN_STEPS.get(
                    (TOKEN_STEPS.indexOf(current) + 1) % TOKEN_STEPS.size());
            iaPrefs().edit().putInt("tokens_max", next).apply();
            binding.tokensPill.setText(String.valueOf(next));
        });

        binding.iterationsPill.setText(String.valueOf(
                iaPrefs().getInt("iterations_max", 16)));
        binding.iterationsRow.setOnClickListener(v -> {
            int current = iaPrefs().getInt("iterations_max", 16);
            int next = ITERATION_STEPS.get(
                    (ITERATION_STEPS.indexOf(current) + 1) % ITERATION_STEPS.size());
            iaPrefs().edit().putInt("iterations_max", next).apply();
            binding.iterationsPill.setText(String.valueOf(next));
        });
    }

    // ---- Préférences ----------------------------------------------------------

    private SharedPreferences iaPrefs() {
        return requireContext().getSharedPreferences(IA_PREFS, Context.MODE_PRIVATE);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
