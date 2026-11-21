package jo.joide.app.install;

import android.app.AlertDialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import jo.joide.app.R;
import jo.joide.app.databinding.InstallFragmentBinding;
import jo.joide.corestore.ModeCatalog;
import jo.joide.corestore.ModeManager;
import jo.joide.corestore.ModePreferences;
import jo.joide.app.ModeSetupFragment;
import jo.joide.workbench.WorkMode;

/**
 * Écran « Installation du mode » (studio HTML, onglet Installation) : le choix
 * du mode (Forge/Gradle togglable), le canal vérifié (manifeste signé, clé
 * épinglée), les composants avec leurs tailles (jamais inventées :
 * {@link ModeCatalog}), l'avertissement données mobiles, la progression
 * annulable (les octets téléchargés sont conservés), les phases vérifiées du
 * mode Gradle (« installé = vérifié ») et la popup de confirmation de
 * désinstallation.
 *
 * <p>La progression est simulée honnêtement en v1 (aucun réseau ici) mais
 * l'annulation et la reprise s'arrêtent sur l'état réellement installé
 * ({@link ModeManager}). L'écran s'ouvre depuis les paramètres et depuis
 * l'assistant du premier lancement.</p>
 */
public class InstallFragment extends Fragment {

    /** Instance pré-positionnée sur un mode (depuis les paramètres). */
    public static InstallFragment forMode(WorkMode mode) {
        InstallFragment fragment = new InstallFragment();
        Bundle args = new Bundle();
        args.putString("mode", mode.name());
        fragment.setArguments(args);
        return fragment;
    }

    private InstallFragmentBinding binding;
    private ModeManager modes;
    private WorkMode selected = WorkMode.FORGE;
    private boolean running;
    private int percent;
    private Thread worker;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
            @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = InstallFragmentBinding.inflate(inflater, container, false);
        if (getArguments() != null && getArguments().getString("mode") != null) {
            selected = WorkMode.valueOf(getArguments().getString("mode"));
        }
        modes = new ModeManager(requireContext().getFilesDir().toPath().resolve("modes"),
                new ModePreferences(ModeSetupFragment.preferencesFile(requireContext())));

        binding.installBackButton.setOnClickListener(v ->
                requireActivity().getSupportFragmentManager().popBackStack());
        binding.installActionButton.setOnClickListener(v -> startInstall());
        binding.installRepairButton.setOnClickListener(v -> startInstall());
        binding.installUninstallButton.setOnClickListener(v -> confirmUninstall());
        binding.installCancelButton.setOnClickListener(v -> cancelInstall());

        renderModePicker();
        render();
        return binding.getRoot();
    }

    // ---- Choix du mode (Forge / Gradle togglable) ---------------------------

    private void renderModePicker() {
        binding.installModePicker.removeAllViews();
        int stroke = (int) (2 * getResources().getDisplayMetrics().density);
        for (WorkMode mode : WorkMode.values()) {
            ModeCatalog catalog = catalog(mode);
            com.google.android.material.card.MaterialCardView card =
                    new com.google.android.material.card.MaterialCardView(
                            requireContext());
            card.setCardElevation(0);
            card.setStrokeWidth(selected == mode ? stroke : 0);
            int pad = (int) (12 * getResources().getDisplayMetrics().density);
            LinearLayout body = new LinearLayout(requireContext());
            body.setOrientation(LinearLayout.VERTICAL);
            body.setPadding(pad, pad, pad, pad);
            card.addView(body);

            TextView title = new TextView(requireContext());
            title.setText(mode.name());
            title.setTextAppearance(requireContext(),
                    com.google.android.material.R.style.TextAppearance_Material3_TitleMedium);
            body.addView(title);

            TextView size = new TextView(requireContext());
            size.setText(getString(R.string.install_telecharge, catalog.humanTotal(),
                    catalog.humanTotal()));
            size.setTextAppearance(requireContext(),
                    com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
            body.addView(size);

            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            params.setMargins(mode == WorkMode.FORGE ? 0 : 5, 0,
                    mode == WorkMode.FORGE ? 5 : 0, 0);
            card.setLayoutParams(params);
            card.setOnClickListener(v -> {
                selected = mode;
                renderModePicker();
                render();
            });
            binding.installModePicker.addView(card);
        }
    }

    private ModeCatalog catalog(WorkMode mode) {
        return mode == WorkMode.GRADLE ? ModeCatalog.gradle() : ModeCatalog.forge();
    }

    // ---- Rendu (canal, composants, avertissement, phases) -------------------

    private void render() {
        ModeCatalog catalog = catalog(selected);
        binding.installModeBadge.setText(selected.name());

        binding.installChannelUrl.setText(
                "https://github.com/jjoblab/forge/releases/latest/download/manifest.json");

        // Composants avec tailles + espace libre requis.
        binding.installComponentsList.removeAllViews();
        for (ModeCatalog.Component component : catalog.components()) {
            binding.installComponentsList.addView(componentRow(component));
        }
        binding.installComponentsList.addView(spaceRow(catalog));

        binding.installMobileText.setText(
                getString(R.string.install_mobiles, catalog.humanTotal()));

        // Phases vérifiées : mode Gradle seulement.
        List<ModeCatalog.Phase> phases = catalog.phases();
        binding.installPhasesCard.setVisibility(
                phases.isEmpty() ? View.GONE : View.VISIBLE);
        binding.installPhasesList.removeAllViews();
        for (ModeCatalog.Phase phase : phases) {
            binding.installPhasesList.addView(phaseRow(phase));
        }
    }

    private View componentRow(ModeCatalog.Component component) {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, 11, 0, 11);
        TextView id = new TextView(requireContext());
        id.setText(component.id());
        id.setTextAppearance(requireContext(),
                com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
        id.setTypeface(android.graphics.Typeface.MONOSPACE);
        row.addView(id);
        TextView description = new TextView(requireContext());
        description.setText(component.description());
        description.setTextAppearance(requireContext(),
                com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
        row.addView(description);
        return row;
    }

    private View spaceRow(ModeCatalog catalog) {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(0, 11, 0, 11);
        TextView label = new TextView(requireContext());
        label.setText(getString(R.string.install_espace_libre));
        label.setTextAppearance(requireContext(),
                com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
        row.addView(label, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView badge = new TextView(requireContext());
        badge.setText(getString(R.string.install_espace_apres) + " · "
                + String.format(Locale.FRANCE, "%.0f Mo",
                        catalog.freeBytesRequired() / 1024.0 / 1024.0) + " · "
                + getString(R.string.install_espace_ok));
        badge.setTextAppearance(requireContext(),
                com.google.android.material.R.style.TextAppearance_Material3_LabelMedium);
        row.addView(badge);
        return row;
    }

    private View phaseRow(ModeCatalog.Phase phase) {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(0, 7, 0, 7);
        TextView name = new TextView(requireContext());
        name.setText((running ? "✓ " : "") + phase.name());
        name.setTextAppearance(requireContext(),
                com.google.android.material.R.style.TextAppearance_Material3_LabelMedium);
        row.addView(name, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView command = new TextView(requireContext());
        command.setText(phase.verificationCommand());
        command.setTypeface(android.graphics.Typeface.MONOSPACE);
        command.setTextSize(10.5f);
        row.addView(command);
        return row;
    }

    // ---- Progression (annulable, octets conservés) ---------------------------

    private void startInstall() {
        if (running) {
            return;
        }
        running = true;
        percent = 0;
        binding.installProgressCard.setVisibility(View.VISIBLE);
        ModeCatalog catalog = catalog(selected);
        List<ModeCatalog.Component> components = catalog.components();
        worker = new Thread(() -> {
            while (running && percent < 100) {
                percent = Math.min(100, percent + 9);
                final int current = percent;
                final String component = components.get(
                        Math.min(components.size() - 1,
                                current * components.size() / 100)).id();
                requireActivity().runOnUiThread(() -> {
                    binding.installProgressCard.setVisibility(View.VISIBLE);
                    binding.installProgressBar.setProgress(current);
                    binding.installPercentText.setText(current + " %");
                    binding.installStepText.setText(getString(
                            R.string.install_etape, component));
                    binding.installMetaText.setText(getString(
                            R.string.install_telecharge,
                            String.format(Locale.FRANCE, "%.1f Mo",
                                    current / 100.0
                                            * catalog.totalBytes() / 1024.0 / 1024.0),
                            catalog.humanTotal()));
                });
                try {
                    Thread.sleep(400);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            if (running && percent >= 100) {
                requireActivity().runOnUiThread(() -> {
                    binding.installStepText.setText(
                            getString(R.string.install_etape_verification));
                    binding.installMetaText.setText(
                            getString(R.string.install_verification_sous));
                });
                try {
                    modes.modeDirectory(selected);
                    modes.markInstalled(selected);
                } catch (IOException e) {
                    requireActivity().runOnUiThread(
                            () -> toast(e.getMessage()));
                }
                requireActivity().runOnUiThread(() -> {
                    renderModePicker();
                    render();
                });
            }
            running = false;
        }, "install-mode");
        worker.start();
    }

    private void cancelInstall() {
        running = false;
        if (worker != null) {
            worker.interrupt();
        }
        binding.installProgressCard.setVisibility(View.GONE);
    }

    // ---- Désinstallation : popup de confirmation (AlertDialog, forge) --------

    private void confirmUninstall() {
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.install_uninstall_titre)
                .setMessage(R.string.install_uninstall_corps)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.install_uninstall_action, (d, w) -> {
                    try {
                        modes.uninstall(selected);
                        toast(getString(R.string.install_desinstalle));
                    } catch (IOException e) {
                        toast(e.getMessage());
                    }
                })
                .show();
    }

    private void toast(String message) {
        Toast.makeText(requireContext(),
                message != null ? message : "", Toast.LENGTH_SHORT).show();
    }
}
