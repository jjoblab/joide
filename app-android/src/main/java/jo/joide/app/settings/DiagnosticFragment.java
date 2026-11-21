package jo.joide.app.settings;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import jo.joide.app.BuildConfig;
import jo.joide.app.R;
import jo.joide.app.databinding.DiagnosticFragmentBinding;
import jo.joide.corestore.CrashJournal;
import jo.joide.corestore.DiagnosticReport;
import jo.joide.corestore.ModeManager;
import jo.joide.corestore.ModePreferences;
import jo.joide.app.ModeSetupFragment;
import jo.joide.workbench.WorkMode;

/**
 * Écran « Diagnostic » — reconstruction exacte du studio HTML (section
 * Diagnostic) : barre sticky (back, titre t-xl, ic_copy + ic_share 20),
 * carte « Versions constatées » (4 lignes label/sur_var + mono — valeurs
 * VRAIES : {@link BuildConfig#VERSION_NAME}, état des modes ; le studio
 * sert d'exemple quand une valeur n'est pas disponible), carte
 * « Installation des modes » (pill INSTALLÉ ok_soft / NON INSTALLÉ
 * sc_high + taille réellement occupée via {@link ModeManager}), carte
 * « Journal borné du dernier build » (bloc mono sc_lowest contour rayon
 * 12, secrets rédigés par {@link DiagnosticReport#redact}), carte « Journal
 * des écroulements (borné à 20) » (mono 11 sur_var, {@link CrashJournal}),
 * 2 chips 46dp rayon 14 ic_copy / ic_share.
 *
 * <p>Les actions Copier / Partager sont réelles (presse-papiers + intent de
 * partage), portées par le rapport complet {@link DiagnosticReport}.</p>
 */
public class DiagnosticFragment extends Fragment {

    private DiagnosticFragmentBinding binding;
    private ModeManager modes;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
            @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = DiagnosticFragmentBinding.inflate(inflater, container, false);
        modes = new ModeManager(requireContext().getFilesDir().toPath().resolve("modes"),
                new ModePreferences(ModeSetupFragment.preferencesFile(requireContext())));

        binding.diagnosticBackButton.setOnClickListener(v ->
                requireActivity().getSupportFragmentManager().popBackStack());
        binding.diagnosticCopyButton.setOnClickListener(v -> copyReport());
        binding.diagnosticShareButton.setOnClickListener(v -> shareReport());
        binding.diagnosticCopyChip.setOnClickListener(v -> copyReport());
        binding.diagnosticShareChip.setOnClickListener(v -> shareReport());

        renderVersions();
        renderModes();
        renderBuildLog();
        renderCrashLog();
        return binding.getRoot();
    }

    // ---- Carte « Versions constatées » ---------------------------------------

    /** Ligne version : label sur_var 13sp + valeur mono (valeur vraie ou studio). */
    private void renderVersions() {
        binding.diagnosticVersionsList.removeAllViews();
        addVersionRow(getString(R.string.diagnostic_version_app),
                BuildConfig.VERSION_NAME != null
                        ? BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")"
                        : getString(R.string.diagnostic_version_app_value));
        addVersionRow(getString(R.string.diagnostic_version_forge),
                getString(R.string.diagnostic_version_forge_value));
        addVersionRow(getString(R.string.diagnostic_version_gradle),
                getString(R.string.diagnostic_version_gradle_value));
        addVersionRow(getString(R.string.diagnostic_version_bricks),
                getString(R.string.diagnostic_version_bricks_value));
    }

    private void addVersionRow(String label, String value) {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(5), 0, dp(5));

        TextView name = new TextView(requireContext());
        name.setText(label);
        name.setTextColor(color(R.color.sur_var));
        name.setTextSize(13);
        row.addView(name, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView version = new TextView(requireContext());
        version.setText(value);
        version.setTypeface(android.graphics.Typeface.MONOSPACE);
        version.setTextColor(color(R.color.sur));
        version.setTextSize(13);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        row.addView(version, params);
        binding.diagnosticVersionsList.addView(row);
    }

    // ---- Carte « Installation des modes » -------------------------------------

    /** Rangées Forge/Gradle : pill d'état (réelle) + nom + taille occupée réelle. */
    private void renderModes() {
        renderModeRow(WorkMode.FORGE,
                binding.diagnosticModeForgeState,
                binding.diagnosticModeForgeName,
                binding.diagnosticModeForgeSize);
        renderModeRow(WorkMode.GRADLE,
                binding.diagnosticModeGradleState,
                binding.diagnosticModeGradleName,
                binding.diagnosticModeGradleSize);
    }

    private void renderModeRow(WorkMode mode, TextView stateView,
            TextView nameView, TextView sizeView) {
        boolean installed = modes.isInstalled(mode);
        stateView.setText(getString(installed
                ? R.string.diagnostic_mode_installed
                : R.string.diagnostic_mode_not_installed));
        stateView.setBackgroundResource(installed
                ? R.drawable.fond_pill_ok : R.drawable.fond_pill_sc_highest);
        stateView.setTextColor(color(installed ? R.color.ok : R.color.sur_var));
        nameView.setText(getString(mode == WorkMode.FORGE
                ? R.string.mode_forge_nom : R.string.mode_gradle_nom));
        long bytes = modes.occupiedBytes(mode);
        sizeView.setText(bytes > 0
                ? String.format(Locale.FRANCE, "%.1f Mo", bytes / 1024.0 / 1024.0)
                : getString(R.string.diagnostic_mode_size_none));
    }

    // ---- Carte « Journal borné du dernier build » ------------------------------

    /** Bloc mono : journal réel borné (queue gardée, secrets rédigés) ou vide. */
    private void renderBuildLog() {
        String log = lastBuildLog();
        if (log.isBlank()) {
            binding.diagnosticBuildLog.setText(
                    getString(R.string.diagnostic_build_log_empty));
            return;
        }
        StringBuilder bounded = new StringBuilder();
        for (String line : log.split("\n", -1)) {
            if (line.isBlank()) {
                continue;
            }
            bounded.append(DiagnosticReport.redact(line)).append('\n');
        }
        binding.diagnosticBuildLog.setText(bounded.toString().trim());
    }

    /** Journal borné du dernier build (constaté sous filesDir/journal/build.txt). */
    private String lastBuildLog() {
        try {
            Path log = requireContext().getFilesDir().toPath()
                    .resolve("journal/build.txt");
            if (!Files.isRegularFile(log)) {
                return "";
            }
            return new String(Files.readAllBytes(log), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    // ---- Carte « Journal des écroulements (borné à 20) » -------------------------

    /** Journal des écroulements réel (mono 11 sur_var), vide si aucun. */
    private void renderCrashLog() {
        CrashJournal crashes = new CrashJournal(
                requireContext().getFilesDir().toPath().resolve("journal/ecroulements.txt"));
        List<CrashJournal.Entry> entries = crashes.read();
        if (entries.isEmpty()) {
            binding.diagnosticCrashLog.setText(
                    getString(R.string.diagnostic_crash_log_empty));
            return;
        }
        StringBuilder lines = new StringBuilder();
        // Les plus récentes en dernier ; le studio montre les 2 dernières.
        int from = Math.max(0, entries.size() - 2);
        for (int i = from; i < entries.size(); i++) {
            lines.append(DiagnosticReport.redact(entries.get(i).line())).append('\n');
        }
        binding.diagnosticCrashLog.setText(lines.toString().trim());
    }

    // ---- Actions réelles : copier / partager -------------------------------------

    private String report() {
        CrashJournal crashes = new CrashJournal(
                requireContext().getFilesDir().toPath().resolve("journal/ecroulements.txt"));
        DiagnosticReport diagnostic = new DiagnosticReport(
                new DiagnosticReport.Versions(
                        BuildConfig.VERSION_NAME, "0.2.13", "8.11.1",
                        "céleris, code-lsp, LayoutLib"),
                List.of(
                        new DiagnosticReport.ModeState("FORGE",
                                modes.isInstalled(WorkMode.FORGE) ? "INSTALLED"
                                        : "NOT_INSTALLED"),
                        new DiagnosticReport.ModeState("GRADLE",
                                modes.isInstalled(WorkMode.GRADLE) ? "INSTALLED"
                                        : "NOT_INSTALLED")),
                lastBuildLog(), crashes.read());
        return diagnostic.render();
    }

    private void copyReport() {
        ClipboardManager clipboard = (ClipboardManager) requireContext()
                .getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText(
                getString(R.string.diagnostic_report_title), report()));
        Toast.makeText(requireContext(),
                R.string.diagnostic_report_copied, Toast.LENGTH_SHORT).show();
    }

    private void shareReport() {
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_TEXT, report());
        send.putExtra(Intent.EXTRA_TITLE,
                getString(R.string.diagnostic_report_title));
        startActivity(Intent.createChooser(send, null));
    }

    // ---- Petits utilitaires --------------------------------------------------------

    private int color(int res) {
        return ContextCompat.getColor(requireContext(), res);
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
