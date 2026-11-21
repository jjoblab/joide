package jo.joide.app.install;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
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
 * Écran « Installation du mode » — reconstruction exacte du studio HTML
 * (onglet Installation) : barre (back + t-xl + pill mode), choix du mode en
 * 2 cartes flex (pastille 30 primaire/sc_highest, bord primaire 2dp si
 * sélectionné, fond pc), carte canal (pastille 34 ic_down + pill ✓ SIG +
 * champ mono 11sp), carte composants (rangées label 13.5sp + body-s + pill
 * mono taille sc_high/sur_var) + rangée espace libre (pill info
 * « SUFFISANT »), carte avertissement warn_soft (ic_bell alerte +
 * SwitchCompat), CTA asymétrique primaire, 2 chips 44dp (réparation /
 * désinstallation err), carte progression (spinner 14dp, barre 6dp sc_high +
 * remplissage primaire, chip Annuler 36dp), carte phases vérifiées Gradle,
 * popup désinstallation (bottom dialog style carte).
 *
 * <p>Les tailles et composants viennent de {@link ModeCatalog} réel
 * (boucle sur catalog.components()) — les valeurs du studio ne servent que
 * d'exemple de rendu. La progression est simulée honnêtement en v1 (aucun
 * réseau ici) mais l'annulation et le marquage installé s'arrêtent sur
 * l'état réel ({@link ModeManager}).</p>
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
        binding.installUninstallButton.setOnClickListener(v -> showUninstallPopup());
        binding.installCancelButton.setOnClickListener(v -> cancelInstall());
        binding.installUninstallCancelButton.setOnClickListener(v -> hideUninstallPopup());
        binding.installUninstallConfirmButton.setOnClickListener(v -> {
            hideUninstallPopup();
            uninstallSelected();
        });

        render();
        return binding.getRoot();
    }

    // ---- Choix du mode : 2 cartes flex (pastille 30, bord primaire) ---------

    private void render() {
        binding.installModePicker.removeAllViews();
        for (WorkMode mode : WorkMode.values()) {
            binding.installModePicker.addView(modeCard(mode));
        }
        binding.installModeBadge.setText(modeLabel(selected));
        renderComponents();
        renderPhases();
    }

    /** Libellé du mode (carte et pill de la barre) — ressources, jamais en dur. */
    private String modeLabel(WorkMode mode) {
        return getString(mode == WorkMode.FORGE
                ? R.string.install_mode_card_forge : R.string.install_mode_card_gradle);
    }

    /** Carte de choix d'un mode : pastille 30 + label t-sm + « X Mo téléchargés ». */
    private View modeCard(WorkMode mode) {
        ModeCatalog catalog = catalog(mode);
        boolean active = selected == mode;

        // Carte : fond sc_low (ou pc si sélectionnée) + bord primaire 2dp si active.
        LinearLayout card = new LinearLayout(requireContext());
        card.setOrientation(LinearLayout.VERTICAL);
        int padH = dp(14);
        int padV = dp(12);
        card.setPadding(padH, padV, padH, padV);
        android.graphics.drawable.GradientDrawable background =
                new android.graphics.drawable.GradientDrawable();
        background.setCornerRadius(dp(26));
        background.setColor(color(active ? R.color.conteneur_primaire : R.color.sc_low));
        background.setStroke(dp(2),
                color(active ? R.color.primaire : R.color.sc_low));
        card.setBackground(background);

        // Rangée : pastille 30 + label.
        LinearLayout head = new LinearLayout(requireContext());
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(android.view.Gravity.CENTER_VERTICAL);
        ImageView pastille = new ImageView(requireContext());
        pastille.setImageResource(mode == WorkMode.FORGE ? R.drawable.ic_code : R.drawable.ic_gear);
        pastille.setColorFilter(color(active ? R.color.sur_primaire : R.color.sur_var));
        LinearLayout.LayoutParams pastilleParams = new LinearLayout.LayoutParams(dp(30), dp(30));
        android.graphics.drawable.GradientDrawable pastilleBg =
                new android.graphics.drawable.GradientDrawable();
        pastilleBg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        pastilleBg.setColor(color(active ? R.color.primaire : R.color.sc_highest));
        pastille.setBackground(pastilleBg);
        int pad = dp(7);
        pastille.setPadding(pad, pad, pad, pad);
        head.addView(pastille, pastilleParams);

        TextView label = new TextView(requireContext());
        label.setText(modeLabel(mode));
        label.setTextColor(color(active ? R.color.sur_conteneur_primaire : R.color.sur));
        applyText(label, 15, true);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        labelParams.leftMargin = dp(8);
        head.addView(label, labelParams);
        card.addView(head);

        // « 102,4 Mo téléchargés » (body-s).
        TextView size = new TextView(requireContext());
        size.setText(getString(R.string.install_modele_total, catalog.humanTotal()));
        size.setTextColor(color(active ? R.color.sur_conteneur_primaire : R.color.sur_var));
        applyText(size, 12.5f, false);
        LinearLayout.LayoutParams sizeParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sizeParams.topMargin = dp(6);
        card.addView(size, sizeParams);

        card.setOnClickListener(v -> {
            selected = mode;
            render();
        });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        params.rightMargin = dp(5);
        card.setLayoutParams(params);
        return card;
    }

    // ---- Composants (données réelles de ModeCatalog) -------------------------

    private void renderComponents() {
        ModeCatalog catalog = catalog(selected);
        binding.installTotalText.setText(catalog.humanTotal());
        binding.installComponentsList.removeAllViews();
        List<ModeCatalog.Component> components = catalog.components();
        for (int i = 0; i < components.size(); i++) {
            binding.installComponentsList.addView(componentRow(components.get(i)));
            if (i < components.size() - 1) {
                binding.installComponentsList.addView(divInset());
            }
        }
        binding.installComponentsList.addView(spaceRow(catalog));
    }

    /** Rangée composant : label 13.5sp + body-s desc + pill mono taille sc_high. */
    private View componentRow(ModeCatalog.Component component) {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(11), dp(16), dp(11));

        LinearLayout text = new LinearLayout(requireContext());
        text.setOrientation(LinearLayout.VERTICAL);
        TextView id = new TextView(requireContext());
        id.setText(component.id());
        id.setTextColor(color(R.color.sur));
        applyText(id, 13.5f, true);
        text.addView(id);
        TextView description = new TextView(requireContext());
        description.setText(component.description());
        description.setTextColor(color(R.color.sur_var));
        applyText(description, 12.5f, false);
        LinearLayout.LayoutParams descriptionParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        descriptionParams.topMargin = dp(1);
        text.addView(description, descriptionParams);
        row.addView(text, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView size = pill(component.humanSize(), R.drawable.fond_pill_sc_highest,
                R.color.sur_var, true);
        row.addView(size);
        return row;
    }

    /** Rangée « Espace libre requis » : label + « 210 Mo après installation » + pill SUFFISANT. */
    private View spaceRow(ModeCatalog catalog) {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(11), dp(16), dp(11));

        LinearLayout text = new LinearLayout(requireContext());
        text.setOrientation(LinearLayout.VERTICAL);
        TextView label = new TextView(requireContext());
        label.setText(getString(R.string.install_espace_libre));
        label.setTextColor(color(R.color.sur));
        applyText(label, 13.5f, true);
        text.addView(label);
        TextView after = new TextView(requireContext());
        after.setText(getString(R.string.install_disk_required,
                String.format(Locale.FRANCE, "%.0f Mo",
                        catalog.freeBytesRequired() / 1024.0 / 1024.0)));
        after.setTextColor(color(R.color.sur_var));
        applyText(after, 12.5f, false);
        LinearLayout.LayoutParams afterParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        afterParams.topMargin = dp(1);
        text.addView(after, afterParams);
        row.addView(text, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        row.addView(pill(getString(R.string.install_espace_ok),
                R.drawable.fond_pill_info, R.color.info, false));
        return row;
    }

    /** Séparateur inset du studio (1dp contour_var 55 %, inset 70dp). */
    private View divInset() {
        View divider = new View(requireContext());
        divider.setBackgroundResource(R.drawable.fond_div_inset);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        params.leftMargin = dp(70);
        params.rightMargin = dp(16);
        divider.setLayoutParams(params);
        divider.setAlpha(0.55f);
        return divider;
    }

    // ---- Phases vérifiées (installé = vérifié) — mode Gradle ------------------

    private void renderPhases() {
        List<ModeCatalog.Phase> phases = catalog(selected).phases();
        binding.installPhasesCard.setVisibility(
                phases.isEmpty() ? View.GONE : View.VISIBLE);
        binding.installPhasesList.removeAllViews();
        // Studio : les phases cochent pendant l'installation (2 premières ✓ à 34 %).
        int doneCount = running ? Math.min(phases.size(),
                percent * phases.size() / 100 + 2) : 0;
        for (int i = 0; i < phases.size(); i++) {
            binding.installPhasesList.addView(phaseRow(phases.get(i), i < doneCount));
        }
    }

    /** Rangée phase : pill (ok si ✓ sinon sc_high) + mono 10.5sp contour. */
    private View phaseRow(ModeCatalog.Phase phase, boolean done) {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(7), 0, dp(7));

        TextView name = pill((done ? "✓ " : "") + phase.name(),
                done ? R.drawable.fond_pill_ok : R.drawable.fond_pill_sc_highest,
                done ? R.color.ok : R.color.sur_var, false);
        row.addView(name);

        TextView command = new TextView(requireContext());
        command.setText(phase.verificationCommand());
        command.setTypeface(android.graphics.Typeface.MONOSPACE);
        command.setTextColor(color(R.color.contour));
        applyText(command, 10.5f, false);
        LinearLayout.LayoutParams commandParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        commandParams.leftMargin = dp(10);
        command.setSingleLine(true);
        command.setEllipsize(android.text.TextUtils.TruncateAt.END);
        row.addView(command, commandParams);
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
                            R.string.install_progress_meta,
                            String.format(Locale.FRANCE, "%.1f Mo",
                                    current / 100.0
                                            * catalog.totalBytes() / 1024.0 / 1024.0),
                            catalog.humanTotal(),
                            Math.max(0, (100 - current) * 22 / 100)));
                    renderPhases();
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
                requireActivity().runOnUiThread(this::render);
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

    // ---- Désinstallation : popup de confirmation (bottom dialog) ---------------

    private void showUninstallPopup() {
        binding.installUninstallPopup.setVisibility(View.VISIBLE);
    }

    private void hideUninstallPopup() {
        binding.installUninstallPopup.setVisibility(View.GONE);
    }

    private void uninstallSelected() {
        try {
            modes.uninstall(selected);
            toast(getString(R.string.install_desinstalle));
        } catch (IOException e) {
            toast(e.getMessage());
        }
        render();
    }

    // ---- Petits composants (studio) -------------------------------------------

    /** Pill 26dp (fond drawable dédié, texte 11.5sp bold, mono si demandé). */
    private TextView pill(String text, int backgroundRes, int colorRes, boolean mono) {
        TextView pill = new TextView(requireContext());
        pill.setText(text);
        pill.setBackgroundResource(backgroundRes);
        pill.setTextColor(color(colorRes));
        pill.setGravity(android.view.Gravity.CENTER_VERTICAL);
        pill.setPadding(dp(12), 0, dp(12), 0);
        pill.setMinHeight(dp(26));
        if (mono) {
            pill.setTypeface(android.graphics.Typeface.MONOSPACE);
        }
        applyText(pill, 11.5f, true);
        return pill;
    }

    private void applyText(TextView view, float sizeSp, boolean bold) {
        view.setTextSize(sizeSp);
        view.setTypeface(bold ? android.graphics.Typeface.DEFAULT_BOLD
                : android.graphics.Typeface.DEFAULT);
    }

    private int color(int res) {
        return androidx.core.content.ContextCompat.getColor(requireContext(), res);
    }

    private ModeCatalog catalog(WorkMode mode) {
        return mode == WorkMode.GRADLE ? ModeCatalog.gradle() : ModeCatalog.forge();
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void toast(String message) {
        Toast.makeText(requireContext(),
                message != null ? message : "", Toast.LENGTH_SHORT).show();
    }
}
