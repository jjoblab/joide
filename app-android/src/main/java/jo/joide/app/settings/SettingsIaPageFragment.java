package jo.joide.app.settings;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import java.util.Arrays;
import java.util.List;
import jo.joide.app.R;
import jo.joide.app.ai.EncryptedKeyVault;
import jo.joide.app.databinding.SettingsIaChipBinding;
import jo.joide.app.databinding.SettingsIaProvidersBinding;
import jo.joide.ai.HttpLlmTransport;
import jo.joide.ai.LlmError;
import jo.joide.ai.provider.ProviderMigration;
import jo.joide.ai.provider.ProviderRegistry;
import jo.joide.ai.provider.ProviderSpec;

/**
 * Page « IA · Fournisseurs » (Lot 7) : chips du fournisseur actif (registre
 * réel), carte clé au coffre (masquée, afficher/masquer, **Tester la
 * connexion**), URL de base éditable pour la passerelle personnalisable
 * (avec Réinitialiser), options OpenRouter (attribution X-Title, routage
 * documenté : sort, allow_fallbacks, require_parameters, data_collection).
 */
public final class SettingsIaPageFragment extends Fragment {

    /** Préférences IA de la coquille (fournisseur, modèle, effort, plafonds). */
    public static final String IA_PREFS = "joide_reglages_ia";

    private SettingsIaProvidersBinding binding;
    private EncryptedKeyVault vault;
    private boolean keyVisible;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        binding = SettingsIaProvidersBinding.inflate(inflater, container, false);
        vault = new EncryptedKeyVault(requireContext());
        migrateProviders(requireContext());
        warnLegacyLaboratoireKeyOnce();
        binding.backButton.setOnClickListener(v ->
                requireActivity().getSupportFragmentManager().popBackStack());
        wireProviderChips();
        wireKeyCard();
        wireBaseUrl();
        wireOpenRouterOptions();
        return binding.getRoot();
    }

    // ---- Fournisseur actif ----------------------------------------------------

    /** Fournisseur persisté : OpenRouter par défaut (premier du registre). */
    public static String providerId(android.content.Context context) {
        String id = context.getSharedPreferences(IA_PREFS,
                android.content.Context.MODE_PRIVATE).getString("fournisseur", null);
        return id != null && ProviderRegistry.isKnown(id)
                ? id : ProviderRegistry.defaultProvider().id();
    }

    private String providerId() {
        return providerId(requireContext());
    }

    private ProviderSpec spec() {
        return ProviderRegistry.byId(providerId())
                .orElseGet(ProviderRegistry::defaultProvider);
    }

    private void wireProviderChips() {
        chip(binding.chipOpenrouter, R.string.settings_ia_provider_openrouter, "openrouter");
        chip(binding.chipOpenai, R.string.settings_ia_provider_openai, "openai");
        chip(binding.chipGemini, R.string.settings_ia_provider_gemini, "gemini");
        chip(binding.chipCustom, R.string.settings_ia_provider_custom, "custom");
        renderProviderChips();
    }

    private void chip(SettingsIaChipBinding view, int label, String id) {
        view.iaChip.setText(getString(label));
        view.iaChip.setTag(id);
        view.iaChip.setOnClickListener(v -> {
            iaPrefs().edit().putString("fournisseur", id).apply();
            renderProviderChips();
            renderKeyCard();
            renderBaseUrlCard();
            renderOpenRouterOptions();
        });
    }

    private void renderProviderChips() {
        String active = providerId();
        for (SettingsIaChipBinding chip : Arrays.asList(
                binding.chipOpenrouter, binding.chipOpenai,
                binding.chipGemini, binding.chipCustom)) {
            boolean on = active.equals(chip.iaChip.getTag());
            chip.iaChip.setBackgroundResource(on
                    ? R.drawable.fond_chip_on : R.drawable.fond_chip);
            chip.iaChip.setTextColor(getResources().getColor(
                    on ? R.color.sur_sc2 : R.color.sur));
        }
    }

    // ---- Clé : coffre, masquage, test de connexion -----------------------------

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
        binding.keyShowButton.setOnClickListener(v -> {
            keyVisible = !keyVisible;
            binding.keyShowButton.setText(keyVisible
                    ? R.string.settings_ia_key_hide : R.string.settings_ia_key_show);
            applyKeyVisibility();
        });
        binding.keyTestButton.setOnClickListener(v -> testConnection());
    }

    private void applyKeyVisibility() {
        binding.keyField.setTransformationMethod(keyVisible
                ? null : android.text.method.PasswordTransformationMethod.getInstance());
    }

    private void renderKeyCard() {
        String existing = vault.apiKey(providerId());
        binding.keyField.setText("");
        if (existing != null) {
            binding.keyField.setHint(masked(existing));
        } else {
            binding.keyField.setHint(getString(R.string.settings_ia_key_hint));
        }
        keyVisible = false;
        binding.keyShowButton.setText(R.string.settings_ia_key_show);
        applyKeyVisibility();
    }

    /** Masque la clé (préfixe court + billes — jamais la clé complète). */
    private static String masked(String key) {
        String prefix = key.length() >= 7 ? key.substring(0, 7) : key;
        return prefix + "\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022";
    }

    /**
     * Test réel de la connexion (Lot 7) : GET l'endpoint des modèles du
     * fournisseur avec la clé du coffre — réseau hors fil UI, résultat en
     * Snackbar et dans la carte (nombre de modèles, ou l'erreur typée).
     */
    private void testConnection() {
        binding.keyTestState.setVisibility(View.VISIBLE);
        binding.keyTestState.setText(R.string.settings_ia_test_running);
        String provider = providerId();
        ProviderSpec spec = spec();
        String baseUrl = spec.baseUrlEditable()
                ? iaPrefs().getString("base_url_custom", null) : null;
        String key = vault.apiKey(provider);
        java.util.Map<String, String> headers = attributionHeaders(spec);
        new Thread(() -> {
            String outcome;
            int modelCount = 0;
            try {
                String body = new HttpLlmTransport().fetchModelsJson(
                        spec.endpoint(baseUrl, key, headers));
                modelCount = jo.joide.ai.provider.ModelCatalog.parseIds(body).size();
                outcome = null;
            } catch (Exception e) {
                outcome = e instanceof LlmError error ? error.getMessage()
                        : (e.getMessage() != null ? e.getMessage() : String.valueOf(e));
            }
            final String failure = outcome;
            final int count = modelCount;
            requireActivity().runOnUiThread(() -> {
                if (binding == null) {
                    return;
                }
                if (failure == null) {
                    binding.keyTestState.setText(
                            getString(R.string.settings_ia_test_ok, count));
                } else {
                    binding.keyTestState.setText(
                            getString(R.string.settings_ia_test_fail, failure));
                }
            });
        }, "ia-test-connexion").start();
    }

    /** En-têtes d'attribution (X-Title : JoIDE — doc OpenRouter, Lot 7). */
    private java.util.Map<String, String> attributionHeaders(ProviderSpec spec) {
        if ("openrouter".equals(spec.id())
                && iaPrefs().getBoolean("or_attribution", true)) {
            return java.util.Collections.singletonMap("X-Title", "JoIDE");
        }
        return null;
    }

    // ---- URL de base (passerelle personnalisable seulement) --------------------

    private void wireBaseUrl() {
        binding.baseUrlReset.setOnClickListener(v -> {
            binding.baseUrlField.setText(spec().defaultBaseUrl());
            saveBaseUrl();
        });
        binding.baseUrlField.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) {
                saveBaseUrl();
            }
        });
        renderBaseUrlCard();
    }

    private void renderBaseUrlCard() {
        binding.baseUrlCard.setVisibility(
                spec().baseUrlEditable() ? View.VISIBLE : View.GONE);
        binding.baseUrlField.setText(iaPrefs().getString(
                "base_url_custom", spec().defaultBaseUrl()));
    }

    private void saveBaseUrl() {
        String value = binding.baseUrlField.getText().toString().trim();
        if (!value.isEmpty()) {
            iaPrefs().edit().putString("base_url_custom", value).apply();
        }
    }

    // ---- Options OpenRouter (routage documenté) ---------------------------------

    private static final List<String> SORTS = Arrays.asList(
            "", "price", "throughput", "latency");
    private static final List<Integer> SORT_LABELS = Arrays.asList(
            R.string.settings_ia_sort_default, R.string.settings_ia_sort_price,
            R.string.settings_ia_sort_throughput, R.string.settings_ia_sort_latency);

    private void wireOpenRouterOptions() {
        binding.orAttribution.setChecked(iaPrefs().getBoolean("or_attribution", true));
        binding.orAttribution.setOnCheckedChangeListener((b, checked) ->
                iaPrefs().edit().putBoolean("or_attribution", checked).apply());
        binding.orAllowFallbacks.setChecked(iaPrefs().getBoolean("or_allow_fallbacks", true));
        binding.orAllowFallbacks.setOnCheckedChangeListener((b, checked) ->
                iaPrefs().edit().putBoolean("or_allow_fallbacks", checked).apply());
        binding.orRequireParameters.setChecked(
                iaPrefs().getBoolean("or_require_parameters", false));
        binding.orRequireParameters.setOnCheckedChangeListener((b, checked) ->
                iaPrefs().edit().putBoolean("or_require_parameters", checked).apply());
        binding.orDataCollection.setChecked(
                iaPrefs().getBoolean("or_data_collection", false));
        binding.orDataCollection.setOnCheckedChangeListener((b, checked) ->
                iaPrefs().edit().putBoolean("or_data_collection", checked).apply());
        renderSortChips();
        renderOpenRouterOptions();
    }

    private void renderSortChips() {
        binding.orSortChips.removeAllViews();
        String active = iaPrefs().getString("or_sort", "");
        for (int i = 0; i < SORTS.size(); i++) {
            String value = SORTS.get(i);
            TextView chip = new TextView(requireContext());
            chip.setText(SORT_LABELS.get(i));
            chip.setTextSize(12.5f);
            chip.setTypeface(chip.getTypeface(), android.graphics.Typeface.BOLD);
            chip.setPadding(dip(14), dip(8), dip(14), dip(8));
            chip.setMinHeight(dip(36));
            boolean on = value.equals(active);
            chip.setBackgroundResource(on ? R.drawable.fond_chip_on : R.drawable.fond_chip);
            chip.setTextColor(getResources().getColor(on ? R.color.sur_sc2 : R.color.sur));
            chip.setOnClickListener(v -> {
                iaPrefs().edit().putString("or_sort", value).apply();
                renderSortChips();
            });
            android.widget.LinearLayout.LayoutParams params =
                    new android.widget.LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT);
            params.setMarginEnd(dip(6));
            binding.orSortChips.addView(chip, params);
        }
    }

    private void renderOpenRouterOptions() {
        binding.openrouterOptions.setVisibility(
                "openrouter".equals(providerId()) ? View.VISIBLE : View.GONE);
    }

    private int dip(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }

    // ---- Migration + avertissement hérités --------------------------------------

    public static void migrateProviders(android.content.Context context) {
        android.content.SharedPreferences prefs = context.getSharedPreferences(
                IA_PREFS, Context.MODE_PRIVATE);
        ProviderMigration.migrateIds(key -> prefs.getString(key, null), (key, value) ->
                prefs.edit().putString(key, value).apply());
    }

    private void warnLegacyLaboratoireKeyOnce() {
        if (iaPrefs().getBoolean("laboratoire_averti", false)) {
            return;
        }
        if (ProviderMigration.hasLegacyLaboratoireKey(vault::apiKey)) {
            iaPrefs().edit().putBoolean("laboratoire_averti", true).apply();
            com.google.android.material.snackbar.Snackbar.make(
                    binding.getRoot(), R.string.settings_ia_legacy_key_notice,
                    com.google.android.material.snackbar.Snackbar.LENGTH_LONG)
                    .setAction(R.string.settings_ia_legacy_key_remove, v ->
                            vault.removeApiKey("laboratoire"))
                    .show();
        }
    }

    private SharedPreferences iaPrefs() {
        return requireContext().getSharedPreferences(IA_PREFS, Context.MODE_PRIVATE);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
