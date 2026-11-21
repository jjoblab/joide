package jo.joide.app.settings;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import jo.joide.app.R;
import jo.joide.app.ai.EncryptedKeyVault;
import jo.joide.app.databinding.SettingsIaModelsBinding;
import jo.joide.ai.HttpLlmTransport;
import jo.joide.ai.LlmError;
import jo.joide.ai.provider.ModelCatalog;
import jo.joide.ai.provider.ProviderRegistry;
import jo.joide.ai.provider.ProviderSpec;

/**
 * Page « IA · Modèles » (Lot 7) : **catalogue vivant** du fournisseur
 * (GET modelsUrl), mis en cache 6 h sur disque (repli sur le cache si le
 * réseau échoue — jamais une liste vide muette), recherche, badges
 * (Gratuit / Outils / Raisonnement / Vision), prix et contexte si connus.
 * La sélection persiste par fournisseur ; la saisie manuelle reste possible
 * quand aucun catalogue n\'est joignable.
 */
public final class IaModelsPageFragment extends Fragment {

    private static final long CACHE_TTL_MS = 6 * 60 * 60 * 1000L;

    private SettingsIaModelsBinding binding;
    private EncryptedKeyVault vault;
    private List<ModelCatalog.ModelInfo> models = new ArrayList<>();
    private String filter = "";

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        binding = SettingsIaModelsBinding.inflate(inflater, container, false);
        vault = new EncryptedKeyVault(requireContext());
        binding.backButton.setOnClickListener(v ->
                requireActivity().getSupportFragmentManager().popBackStack());
        binding.modelsSearch.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(android.text.Editable editable) {
                filter = editable.toString().trim().toLowerCase();
                renderModels();
            }
        });
        binding.modelsRefresh.setOnClickListener(v -> loadCatalog(true));
        loadCatalog(false);
        return binding.getRoot();
    }

    private ProviderSpec spec() {
        return ProviderRegistry.byId(SettingsIaPageFragment.providerId(requireContext()))
                .orElseGet(ProviderRegistry::defaultProvider);
    }

    /** Charge le catalogue : cache frais, sinon réseau, sinon cache ancien. */
    private void loadCatalog(boolean forceRefresh) {
        ProviderSpec spec = spec();
        Path cache = cacheFile(spec.id());
        long cacheAge = cacheAgeMs(cache);
        if (!forceRefresh && Files.isRegularFile(cache) && cacheAge < CACHE_TTL_MS) {
            try {
                models = ModelCatalog.parse(new String(
                        Files.readAllBytes(cache), StandardCharsets.UTF_8));
                binding.modelsState.setText(getString(
                        R.string.settings_ia_models_cached, models.size(),
                        ageLabel(cacheAge)));
                renderModels();
                return;
            } catch (Exception ignored) {
                // Cache illisible : réseau.
            }
        }
        binding.modelsState.setText(R.string.settings_ia_test_running);
        String baseUrl = spec.baseUrlEditable()
                ? iaPrefs().getString("base_url_custom", null) : null;
        String key = vault.apiKey(spec.id());
        new Thread(() -> {
            List<ModelCatalog.ModelInfo> fetched = null;
            String failure = null;
            try {
                String body = new HttpLlmTransport().fetchModelsJson(
                        spec.endpoint(baseUrl, key, null));
                fetched = ModelCatalog.parse(body);
                saveCache(cache, body);
            } catch (Exception e) {
                failure = e instanceof LlmError error ? error.getMessage()
                        : (e.getMessage() != null ? e.getMessage() : String.valueOf(e));
            }
            final List<ModelCatalog.ModelInfo> result = fetched;
            final String error = failure;
            requireActivity().runOnUiThread(() -> {
                if (binding == null) {
                    return;
                }
                if (result != null) {
                    models = result;
                    binding.modelsState.setText(getString(
                            R.string.settings_ia_models_live, result.size()));
                } else {
                    // Réseau en échec : repli honnête sur le cache ancien.
                    try {
                        models = ModelCatalog.parse(new String(
                                Files.readAllBytes(cache), StandardCharsets.UTF_8));
                    } catch (Exception ignored) {
                        models = new ArrayList<>();
                    }
                    binding.modelsState.setText(getString(
                            R.string.settings_ia_models_fetch_error, error));
                }
                renderModels();
            });
        }, "ia-catalogue").start();
    }

    private Path cacheFile(String providerId) {
        return new File(requireContext().getFilesDir(),
                "ia-catalogue/" + providerId + ".json").toPath();
    }

    private long cacheAgeMs(Path cache) {
        try {
            return System.currentTimeMillis() - Files.getLastModifiedTime(cache).toMillis();
        } catch (Exception e) {
            return Long.MAX_VALUE;
        }
    }

    private void saveCache(Path cache, String body) {
        try {
            Files.createDirectories(cache.getParent());
            Files.write(cache, body.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
            // Cache best effort : jamais bloquant.
        }
    }

    private String ageLabel(long ms) {
        long minutes = ms / 60000L;
        if (minutes < 60) {
            return minutes + " min";
        }
        return (minutes / 60) + " h";
    }

    /** Rend la liste filtrée : id, nom, badges, prix/contexte si connus. */
    private void renderModels() {
        binding.modelsList.removeAllViews();
        String selected = iaPrefs().getString("modele_" + spec().id(), null);
        int shown = 0;
        for (ModelCatalog.ModelInfo model : models) {
            if (!filter.isEmpty()
                    && !model.id().toLowerCase().contains(filter)
                    && !model.displayName().toLowerCase().contains(filter)) {
                continue;
            }
            if (shown++ >= 120) {
                break; // garde-fou : affichage borné, recherche pour le reste
            }
            binding.modelsList.addView(modelRow(model, selected));
        }
        if (models.isEmpty()) {
            // Aucun catalogue joignable : la saisie manuelle reste possible.
            TextView manual = new TextView(requireContext());
            manual.setText(R.string.settings_ia_model_dialog_title);
            manual.setTextSize(13f);
            manual.setTypeface(manual.getTypeface(), android.graphics.Typeface.BOLD);
            manual.setTextColor(getResources().getColor(R.color.sur));
            manual.setPadding(dip(16), dip(14), dip(16), dip(14));
            manual.setMinHeight(dip(48));
            manual.setBackground(getResources().getDrawable(R.drawable.fond_carte));
            manual.setOnClickListener(v -> promptManualModel());
            binding.modelsList.addView(manual);
        }
    }

    private View modelRow(ModelCatalog.ModelInfo model, String selected) {
        android.widget.LinearLayout row = new android.widget.LinearLayout(requireContext());
        row.setOrientation(android.widget.LinearLayout.VERTICAL);
        row.setPadding(dip(16), dip(12), dip(16), dip(12));
        row.setBackground(getResources().getDrawable(R.drawable.fond_carte));
        boolean isSelected = model.id().equals(selected);
        TextView id = new TextView(requireContext());
        id.setText(model.id() + (isSelected
                ? "  \u2713 " + getString(R.string.settings_ia_models_selected) : ""));
        id.setTextSize(13f);
        id.setTypeface(id.getTypeface(), android.graphics.Typeface.BOLD);
        id.setTextColor(getResources().getColor(R.color.sur));
        id.setTextIsSelectable(true);
        row.addView(id);
        if (!model.displayName().equals(model.id())) {
            TextView name = new TextView(requireContext());
            name.setText(model.displayName());
            name.setTextSize(11.5f);
            name.setTextColor(getResources().getColor(R.color.sur_var));
            row.addView(name);
        }
        StringBuilder meta = new StringBuilder();
        if (model.free()) {
            meta.append(getString(R.string.settings_ia_badge_free));
        }
        if (model.supportsTools()) {
            appendBadge(meta, getString(R.string.settings_ia_badge_tools));
        }
        if (model.supportsReasoning()) {
            appendBadge(meta, getString(R.string.settings_ia_badge_reasoning));
        }
        if (model.supportsVision()) {
            appendBadge(meta, getString(R.string.settings_ia_badge_vision));
        }
        if (model.pricingKnown() && !model.free()) {
            appendBadge(meta, String.format(
                    getString(R.string.settings_ia_price_format),
                    String.format("%.2f", model.promptPricePerToken() * 1_000_000)));
        }
        if (model.contextLength() > 0) {
            appendBadge(meta, String.format(
                    getString(R.string.settings_ia_context_format),
                    String.valueOf(model.contextLength())));
        }
        if (meta.length() > 0) {
            TextView badges = new TextView(requireContext());
            badges.setText(meta.toString());
            badges.setTextSize(11f);
            badges.setTextColor(getResources().getColor(R.color.sur_var));
            row.addView(badges);
        }
        row.setOnClickListener(v -> {
            iaPrefs().edit().putString("modele_" + spec().id(), model.id()).apply();
            renderModels();
        });
        return row;
    }

    private void appendBadge(StringBuilder meta, String badge) {
        if (meta.length() > 0) {
            meta.append("  \u00b7  ");
        }
        meta.append(badge);
    }

    private void promptManualModel() {
        EditText input = new EditText(requireContext());
        input.setText(iaPrefs().getString("modele_" + spec().id(), ""));
        input.setHint(R.string.settings_ia_model_hint);
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.settings_ia_model_dialog_title)
                .setView(input)
                .setPositiveButton(R.string.commun_ok, (d, w) -> {
                    iaPrefs().edit()
                            .putString("modele_" + spec().id(),
                                    input.getText().toString().trim())
                            .apply();
                })
                .setNegativeButton(R.string.commun_annuler, null)
                .show();
    }

    private SharedPreferences iaPrefs() {
        return requireContext().getSharedPreferences(
                SettingsIaPageFragment.IA_PREFS, Context.MODE_PRIVATE);
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
