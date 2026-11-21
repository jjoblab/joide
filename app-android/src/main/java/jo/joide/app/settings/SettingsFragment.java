package jo.joide.app.settings;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import java.io.IOException;
import java.util.List;
import jo.joide.ai.AiProvider;
import jo.joide.ai.InteractivePermissionGate;
import jo.joide.ai.KeyVault;
import jo.joide.ai.PermissionGate;
import jo.joide.ai.HttpLlmTransport;
import jo.joide.app.R;
import jo.joide.app.BuildConfig;
import jo.joide.app.ModeSetupFragment;
import jo.joide.app.ai.EncryptedKeyVault;
import jo.joide.corestore.CrashJournal;
import jo.joide.corestore.DiagnosticReport;
import jo.joide.corestore.ModeManager;
import jo.joide.corestore.ModePreferences;
import jo.joide.workbench.WorkMode;

/**
 * Écran Paramètres (lot L8, conception §11) : trois volets — IA (fournisseur,
 * modèle par fournisseur, clé au coffre chiffré, mode de permission + notice
 * honnête de ce qui part de l'appareil), Modes (bascule / réparation /
 * désinstallation, ADR-002 : le changement de mode vit ici), Diagnostic
 * (rapport copiable avec secrets rédigés, §10).
 */
public final class SettingsFragment extends Fragment {

    private KeyVault vault;
    private InteractivePermissionGate gate;
    private ModeManager modes;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        Context context = requireContext();
        vault = new EncryptedKeyVault(context);
        gate = sharedGate();
        modes = new ModeManager(context.getFilesDir().toPath().resolve("modes"),
                new ModePreferences(ModeSetupFragment.preferencesFile(context)));

        ScrollView scroll = new ScrollView(context);
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 32, 32, 32);
        scroll.addView(root);
        addAiSection(root, context);
        addModesSection(root, context);
        addDiagnosticSection(root, context);
        return scroll;
    }

    // ---- IA -----------------------------------------------------------------

    private void addAiSection(LinearLayout root, Context context) {
        root.addView(sectionTitle(context, context.getString(R.string.parametres_section_ia)));

        // Fournisseur (laboratoire / passerelle) + modèle par fournisseur.
        RadioGroup provider = new RadioGroup(context);
        AiProvider laboratory = AiProvider.laboratory(new HttpLlmTransport());
        AiProvider gateway = AiProvider.gateway(new HttpLlmTransport());
        RadioButton labButton = radioButton(context, laboratory.displayName(), true);
        RadioButton gatewayButton = radioButton(context, gateway.displayName(), false);
        provider.addView(labButton);
        provider.addView(gatewayButton);
        root.addView(provider);

        // Clé au coffre (laboratoire ; la passerelle locale n'en demande pas).
        android.widget.EditText keyField = new android.widget.EditText(context);
        keyField.setHint(context.getString(R.string.parametres_ia_cle_hint));
        String existing = vault.apiKey("laboratoire");
        if (existing != null) {
            keyField.setText(existing);
        }
        root.addView(keyField);
        Button saveKey = new Button(context);
        saveKey.setText(context.getString(R.string.parametres_ia_cle_poser));
        saveKey.setOnClickListener(v -> {
            String value = keyField.getText().toString().trim();
            if (value.isEmpty()) {
                vault.removeApiKey("laboratoire");
                Toast.makeText(context, R.string.parametres_ia_cle_retiree,
                        Toast.LENGTH_SHORT).show();
            } else {
                vault.putApiKey("laboratoire", value);
                Toast.makeText(context, R.string.parametres_ia_cle_coffre,
                        Toast.LENGTH_SHORT).show();
            }
        });
        root.addView(saveKey);

        // Mode de permission (porte unique, trois modes persistés — REF-117).
        root.addView(label(context, context.getString(R.string.parametres_ia_permission)));
        RadioGroup permission = new RadioGroup(context);
        RadioButton ask = radioButton(context,
                context.getString(R.string.parametres_ia_permission_ask), true);
        RadioButton auto = radioButton(context,
                context.getString(R.string.parametres_ia_permission_auto), false);
        RadioButton plan = radioButton(context,
                context.getString(R.string.parametres_ia_permission_plan), false);
        permission.addView(ask);
        permission.addView(auto);
        permission.addView(plan);
        PermissionGate.Mode current = gate.mode();
        ask.setChecked(current == PermissionGate.Mode.ASK_EACH);
        auto.setChecked(current == PermissionGate.Mode.AUTO_ACCEPT);
        plan.setChecked(current == PermissionGate.Mode.PLAN_ONLY);
        permission.setOnCheckedChangeListener((g, checkedId) -> {
            if (checkedId == ask.getId()) {
                gate.setMode(PermissionGate.Mode.ASK_EACH);
            } else if (checkedId == auto.getId()) {
                gate.setMode(PermissionGate.Mode.AUTO_ACCEPT);
            } else if (checkedId == plan.getId()) {
                gate.setMode(PermissionGate.Mode.PLAN_ONLY);
            }
        });
        root.addView(permission);

        // Notice honnête (conception §9 : ce qui sort de l'appareil).
        TextView notice = new TextView(context);
        notice.setText(context.getString(R.string.parametres_ia_notice));
        notice.setPadding(0, 16, 0, 0);
        root.addView(notice);
    }

    // ---- Modes (bascule, réparation, désinstallation — ADR-002) -------------

    private void addModesSection(LinearLayout root, Context context) {
        root.addView(sectionTitle(context, context.getString(R.string.parametres_section_modes)));
        for (WorkMode mode : WorkMode.values()) {
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.VERTICAL);
            String state = modes.isInstalled(mode)
                    ? context.getString(R.string.parametres_mode_installe)
                    : context.getString(R.string.parametres_mode_absent);
            long bytes = modes.occupiedBytes(mode);
            String size = bytes > 0
                    ? String.format(java.util.Locale.getDefault(), " — %.1f Mo",
                            bytes / 1024.0 / 1024.0)
                    : "";
            row.addView(label(context, mode + " : " + state + size));

            Button install = new Button(context);
            install.setText(context.getString(R.string.parametres_mode_installer));
            install.setOnClickListener(v -> {
                try {
                    modes.modeDirectory(mode);
                    modes.markInstalled(mode);
                    Toast.makeText(context, R.string.parametres_mode_fait,
                            Toast.LENGTH_SHORT).show();
                } catch (IOException e) {
                    Toast.makeText(context, e.getMessage(), Toast.LENGTH_LONG).show();
                }
            });
            row.addView(install);

            Button use = new Button(context);
            use.setText(context.getString(R.string.parametres_mode_basculer));
            use.setOnClickListener(v -> {
                try {
                    modes.switchTo(mode);
                    Toast.makeText(context, R.string.parametres_mode_bascule_ok,
                            Toast.LENGTH_SHORT).show();
                } catch (Exception e) {
                    Toast.makeText(context, e.getMessage(), Toast.LENGTH_LONG).show();
                }
            });
            row.addView(use);

            Button repair = new Button(context);
            repair.setText(context.getString(R.string.parametres_mode_reparer));
            repair.setOnClickListener(v -> {
                try {
                    modes.repair(mode);
                    Toast.makeText(context, R.string.parametres_mode_fait,
                            Toast.LENGTH_SHORT).show();
                } catch (IOException e) {
                    Toast.makeText(context, e.getMessage(), Toast.LENGTH_LONG).show();
                }
            });
            row.addView(repair);

            Button uninstall = new Button(context);
            uninstall.setText(context.getString(R.string.parametres_mode_desinstaller));
            uninstall.setOnClickListener(v -> {
                try {
                    modes.uninstall(mode);
                    Toast.makeText(context, R.string.parametres_mode_desinstalle,
                            Toast.LENGTH_SHORT).show();
                } catch (IOException e) {
                    Toast.makeText(context, e.getMessage(), Toast.LENGTH_LONG).show();
                }
            });
            row.addView(uninstall);
            root.addView(row);
        }
    }

    // ---- Diagnostic (§10 : rapport copiable, secrets rédigés) ---------------

    private void addDiagnosticSection(LinearLayout root, Context context) {
        root.addView(sectionTitle(context,
                context.getString(R.string.parametres_section_diagnostic)));
        Button copy = new Button(context);
        copy.setText(context.getString(R.string.parametres_diagnostic_copier));
        copy.setOnClickListener(v -> {
            String report = diagnosticReport(context);
            ClipboardManager clipboard = (ClipboardManager) context
                    .getSystemService(Context.CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newPlainText("JoIDE diagnostic", report));
            Toast.makeText(context, R.string.parametres_diagnostic_copie,
                    Toast.LENGTH_SHORT).show();
        });
        root.addView(copy);
        Button share = new Button(context);
        share.setText(context.getString(R.string.parametres_diagnostic_partager));
        share.setOnClickListener(v -> {
            android.content.Intent send = new android.content.Intent(
                    android.content.Intent.ACTION_SEND);
            send.setType("text/plain");
            send.putExtra(android.content.Intent.EXTRA_TEXT, diagnosticReport(context));
            send.putExtra(android.content.Intent.EXTRA_TITLE,
                    context.getString(R.string.parametres_diagnostic_titre));
            startActivity(android.content.Intent.createChooser(send, null));
        });
        root.addView(share);
    }

    /** Le rapport réel : versions (constatées), états de modes, journal borné du dernier build, écroulements. */
    private String diagnosticReport(Context context) {
        CrashJournal crashes = new CrashJournal(
                context.getFilesDir().toPath().resolve("journal/ecroulements.txt"));
        String lastBuildLog = lastBuildLog(context);
        DiagnosticReport report = new DiagnosticReport(
                new DiagnosticReport.Versions(
                        BuildConfig.VERSION_NAME, "0.2.6", "8.11.1",
                        "céleris, code-lsp, LayoutLib"),
                List.of(
                        new DiagnosticReport.ModeState("FORGE",
                                modes.isInstalled(WorkMode.FORGE) ? "INSTALLED"
                                        : "NOT_INSTALLED"),
                        new DiagnosticReport.ModeState("GRADLE",
                                modes.isInstalled(WorkMode.GRADLE) ? "INSTALLED"
                                        : "NOT_INSTALLED")),
                lastBuildLog, crashes.read());
        return report.render();
    }

    /** Journal borné du dernier build (constaté sous filesDir/journal/build.txt). */
    private String lastBuildLog(Context context) {
        try {
            java.nio.file.Path log = context.getFilesDir().toPath()
                    .resolve("journal/build.txt");
            if (!java.nio.file.Files.isRegularFile(log)) {
                return "";
            }
            return new String(java.nio.file.Files.readAllBytes(log),
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    // ---- La porte interactive partagée avec le tiroir IA ---------------------

    /** Porte partagée : le tiroir IA et les paramètres utilisent la même instance (porte unique). */
    public static InteractivePermissionGate sharedGate() {
        return GateHolder.instance;
    }

    private static final class GateHolder {
        static final InteractivePermissionGate instance = new InteractivePermissionGate();
    }

    // ---- Petits composants ---------------------------------------------------

    private static TextView sectionTitle(Context context, String text) {
        TextView title = new TextView(context, null, android.R.attr.listSeparatorTextViewStyle);
        title.setText(text);
        return title;
    }

    private static TextView label(Context context, String text) {
        TextView label = new TextView(context);
        label.setText(text);
        label.setPadding(0, 12, 0, 4);
        return label;
    }

    private static RadioButton radioButton(Context context, String text, boolean checked) {
        RadioButton button = new RadioButton(context);
        button.setText(text);
        button.setChecked(checked);
        return button;
    }
}
