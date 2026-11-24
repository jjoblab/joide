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
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import jo.joide.app.BuildConfig;
import jo.joide.app.R;
import jo.joide.app.databinding.DiagnosticFragmentBinding;
import jo.joide.app.diag.DiagApp;
import jo.joide.app.diag.DeviceSnapshot;
import jo.joide.corestore.DiagnosticReport;
import jo.joide.corestore.ModeManager;
import jo.joide.corestore.ModePreferences;
import jo.joide.app.ModeSetupFragment;
import jo.joide.diag.CrashReport;
import jo.joide.diag.CrashReportStore;
import jo.joide.diag.CrashReportSummary;
import jo.joide.diag.CrashType;
import jo.joide.diag.JsonlLogStore;
import jo.joide.diag.LogEntry;
import jo.joide.diag.LogLevel;
import jo.joide.diag.LoggingLimits;
import jo.joide.workbench.WorkMode;

/**
 * Écran « Diagnostic » — reconstruction exacte du studio HTML (section
 * Diagnostic) : barre sticky (back, titre t-xl, ic_copy + ic_share 20),
 * carte « Versions constatées » (4 lignes label/sur_var + mono — valeurs
 * VRAIES : {@link BuildConfig#VERSION_NAME}, état des modes), carte
 * « Installation des modes » (pill INSTALLÉ ok_soft / NON INSTALLÉ
 * sc_high + taille réelle), carte « Journal borné du dernier build »
 * (secrets rédigés), carte « <b>Rapports de plantage</b> » (ADR-021 : la
 * liste des rapports de core-diag, tap = dépliage de la trace complète,
 * marquage consulté, suppression), carte « <b>Journal applicatif</b> »
 * (pipeline ADR-021 : queue bornée + réglage du niveau minimal persisté),
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
        renderCrashReports();
        renderJournal();
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

    // ---- Carte « Rapports de plantage » (core-diag, ADR-021) --------------------

    /** Dépôt des rapports du processus (filesDir/crashes). */
    private CrashReportStore crashStore() {
        return new CrashReportStore(
                requireContext().getFilesDir().toPath().resolve("crashes"));
    }

    /** Liste des rapports : alerte non consultés + rangées dépliables. */
    private void renderCrashReports() {
        binding.diagnosticCrashList.removeAllViews();
        List<CrashReportSummary> summaries = crashStore().listSummaries();

        long unreviewed = summaries.stream().filter(s -> !s.reviewed()).count();
        binding.diagnosticCrashUnreviewed.setVisibility(
                unreviewed > 0 ? View.VISIBLE : View.GONE);
        binding.diagnosticCrashUnreviewed.setText(
                getString(R.string.diagnostic_crash_unreviewed, (int) unreviewed));

        if (summaries.isEmpty()) {
            TextView empty = new TextView(requireContext());
            empty.setText(R.string.diagnostic_crash_none_recent);
            empty.setTextColor(color(R.color.sur_var));
            empty.setTextSize(12);
            binding.diagnosticCrashList.addView(empty);
            return;
        }
        // Les 5 plus récents : la carte reste lisible, le rapport complet
        // (Copier) emporte tout.
        int from = Math.max(0, summaries.size() - 5);
        for (int i = from; i < summaries.size(); i++) {
            binding.diagnosticCrashList.addView(crashRow(summaries.get(i)));
        }
    }

    /** Une rangée de rapport : pill type + classe + âge ; tap = détail. */
    private View crashRow(CrashReportSummary summary) {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(6), 0, dp(6));

        TextView type = new TextView(requireContext());
        type.setText(crashTypeLabel(summary.type()));
        type.setBackgroundResource(summary.type() == CrashType.ANR
                ? R.drawable.fond_pill_warn : R.drawable.fond_pill_err);
        type.setTextColor(color(summary.type() == CrashType.ANR
                ? R.color.alerte : R.color.erreur));
        type.setTextSize(10);
        type.setPadding(dp(8), dp(2), dp(8), dp(2));
        row.addView(type);

        TextView name = new TextView(requireContext());
        name.setText(summary.exceptionClassName()
                + (summary.reviewed() ? "" : getString(R.string.activite_crash_non_lu)));
        name.setTextColor(color(R.color.sur));
        name.setTextSize(12.5f);
        LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        nameParams.leftMargin = dp(10);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        name.setLayoutParams(nameParams);
        row.addView(name);

        TextView age = new TextView(requireContext());
        age.setText(relatif(Instant.ofEpochMilli(summary.timestampMillis())));
        age.setTextColor(color(R.color.sur_var));
        age.setTextSize(11);
        row.addView(age);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        LinearLayout container = new LinearLayout(requireContext());
        container.setOrientation(LinearLayout.VERTICAL);
        container.setLayoutParams(params);

        TextView detail = new TextView(requireContext());
        detail.setTextColor(color(R.color.sur_var));
        detail.setTextSize(11);
        detail.setTypeface(android.graphics.Typeface.MONOSPACE);
        detail.setPadding(dp(4), dp(6), dp(4), dp(2));
        detail.setVisibility(View.GONE);

        LinearLayout actions = new LinearLayout(requireContext());
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, 0, 0, dp(6));
        actions.setVisibility(View.GONE);

        container.addView(row);
        container.addView(detail);
        container.addView(actions);
        row.setOnClickListener(v -> {
            boolean expand = detail.getVisibility() != View.VISIBLE;
            if (expand && detail.length() == 0) {
                detail.setText(crashDetail(summary.id()));
            }
            detail.setVisibility(expand ? View.VISIBLE : View.GONE);
            actions.setVisibility(expand ? View.VISIBLE : View.GONE);
        });
        fillCrashActions(summary.id(), actions);
        return container;
    }

    /** Actions d'un rapport : Marquer consulté · Supprimer (petites pills). */
    private void fillCrashActions(String reportId, LinearLayout actions) {
        TextView markReviewed = pillAction(getString(R.string.diagnostic_crash_mark_reviewed));
        markReviewed.setOnClickListener(v -> {
            if (crashStore().markReviewed(reportId)) {
                Toast.makeText(requireContext(),
                        R.string.diagnostic_crash_reviewed_toast, Toast.LENGTH_SHORT).show();
                renderCrashReports();
            }
        });
        actions.addView(markReviewed);

        TextView delete = pillAction(getString(R.string.diagnostic_crash_delete));
        delete.setOnClickListener(v -> {
            crashStore().delete(reportId);
            Toast.makeText(requireContext(),
                    R.string.diagnostic_crash_deleted, Toast.LENGTH_SHORT).show();
            renderCrashReports();
        });
        LinearLayout.LayoutParams deleteParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        deleteParams.leftMargin = dp(10);
        actions.addView(delete, deleteParams);
    }

    /** Petite pill d'action (texte 11, contour, clic). */
    private TextView pillAction(String label) {
        TextView pill = new TextView(requireContext());
        pill.setText(label);
        pill.setBackgroundResource(R.drawable.fond_bouton_contour);
        pill.setTextColor(color(R.color.sur));
        pill.setTextSize(11);
        pill.setPadding(dp(12), dp(5), dp(12), dp(5));
        return pill;
    }

    /** Détail complet d'un rapport : méta, trace aplatie, derniers filons. */
    private String crashDetail(String reportId) {
        CrashReport report = crashStore().get(reportId);
        if (report == null) {
            return getString(R.string.diagnostic_crash_reports_empty);
        }
        StringBuilder text = new StringBuilder(512);
        text.append(crashTypeLabel(report.type()))
                .append(" · fil « ").append(report.threadName()).append(" »");
        if (report.lastScreen() != null) {
            text.append(" · ").append(report.lastScreen());
        }
        text.append(" · ").append(report.processUptimeMs()).append(" ms");
        if (report.isCrashLoop()) {
            text.append(" · ").append(getString(R.string.diagnostic_crash_boucle));
        }
        text.append('\n').append(report.exception().formatAsText());
        if (!report.breadcrumbs().isEmpty()) {
            text.append('\n').append(getString(R.string.diagnostic_crash_breadcrumbs));
            List<LogEntry> crumbs = report.breadcrumbs();
            int from = Math.max(0, crumbs.size() - 5);
            for (int i = from; i < crumbs.size(); i++) {
                text.append('\n').append(journalLine(crumbs.get(i)));
            }
        }
        return DiagnosticReport.redact(text.toString());
    }

    // ---- Carte « Journal applicatif » (pipeline ADR-021) -------------------------

    /** Queue bornée du journal + réglage du niveau minimal persisté. */
    private void renderJournal() {
        renderJournalLevels();
        JsonlLogStore store = new JsonlLogStore(
                requireContext().getFilesDir().toPath().resolve(
                        LoggingLimits.LOGS_DIR_NAME));
        List<LogEntry> entries = store.readAll();
        if (entries.isEmpty()) {
            binding.diagnosticJournal.setText(getString(R.string.diagnostic_journal_empty));
            return;
        }
        StringBuilder text = new StringBuilder(1024);
        List<LogEntry> tail = entries.size() > LoggingLimits.DIAGNOSTIC_JOURNAL_TAIL
                ? entries.subList(entries.size() - LoggingLimits.DIAGNOSTIC_JOURNAL_TAIL,
                        entries.size())
                : entries;
        for (LogEntry entry : tail) {
            text.append(journalLine(entry)).append('\n');
        }
        binding.diagnosticJournal.setText(text.toString().trim());
    }

    /** 4 pills de niveau : la courante est active — le choix est immédiat et persisté. */
    private void renderJournalLevels() {
        LinearLayout levels = binding.diagnosticJournalLevels;
        levels.removeAllViews();
        LogLevel current = currentLevel();
        for (LogLevel level : LogLevel.values()) {
            TextView pill = new TextView(requireContext());
            pill.setText(level.name().substring(0, 1));
            pill.setGravity(Gravity.CENTER);
            pill.setTextSize(11);
            pill.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            pill.setBackgroundResource(level == current
                    ? R.drawable.fond_segment_actif : R.drawable.fond_pill_sc_highest);
            pill.setTextColor(color(level == current ? R.color.sur : R.color.sur_var));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    dp(34), dp(26));
            if (levels.getChildCount() > 0) {
                params.leftMargin = dp(8);
            }
            pill.setLayoutParams(params);
            pill.setOnClickListener(v -> {
                DiagApp.setLevel(requireContext(), level);
                renderJournalLevels();
                Toast.makeText(requireContext(), level.name(), Toast.LENGTH_SHORT).show();
            });
            levels.addView(pill);
        }
    }

    /** Niveau minimal courant (config à chaud, défaut DEBUG en debug). */
    private LogLevel currentLevel() {
        jo.joide.diag.LogConfigHolder holder = DiagApp.configHolder();
        return holder != null ? holder.read().minLevel() : LogLevel.INFO;
    }

    /** Une ligne de journal : HH:mm:ss NIVEAU/Tag : message. */
    private static String journalLine(LogEntry entry) {
        return ZonedDateTime.ofInstant(Instant.ofEpochMilli(entry.timestampMillis()),
                        java.time.ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("HH:mm:ss"))
                + " " + entry.level() + "/" + entry.tag() + " : " + entry.message();
    }

    // ---- Actions réelles : copier / partager -------------------------------------

    private String report() {
        CrashReportStore crashes = crashStore();
        JsonlLogStore logs = new JsonlLogStore(
                requireContext().getFilesDir().toPath().resolve(
                        LoggingLimits.LOGS_DIR_NAME));
        List<LogEntry> entries = logs.readAll();
        List<LogEntry> tail = entries.size() > LoggingLimits.DIAGNOSTIC_JOURNAL_TAIL
                ? entries.subList(entries.size() - LoggingLimits.DIAGNOSTIC_JOURNAL_TAIL,
                        entries.size())
                : entries;
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
                lastBuildLog(),
                crashes.listSummaries(),
                tail,
                DeviceSnapshot.take(requireContext()));
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

    /** « il y a 2 min » — âge relatif court. */
    private static String relatif(Instant quand) {
        long minutes = Math.max(0, Duration.between(quand, Instant.now()).toMinutes());
        if (minutes < 60) {
            return String.format(Locale.getDefault(), "%d min", Math.max(1, minutes));
        }
        if (minutes < 24 * 60) {
            return String.format(Locale.getDefault(), "%d h", minutes / 60);
        }
        return String.format(Locale.getDefault(), "%d j", minutes / (24 * 60));
    }

    /** Libellé de pill du type de plantage. */
    private String crashTypeLabel(CrashType type) {
        return getString(type == CrashType.EXCEPTION
                ? R.string.diagnostic_crash_type_exception
                : type == CrashType.ANR
                ? R.string.diagnostic_crash_type_anr
                : R.string.diagnostic_crash_type_native);
    }
}
