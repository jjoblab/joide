package jo.joide.app.settings;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import jo.joide.ai.InteractivePermissionGate;
import jo.joide.ai.KeyVault;
import jo.joide.ai.PermissionGate;
import jo.joide.ai.HttpLlmTransport;
import jo.joide.app.R;
import jo.joide.app.BuildConfig;
import jo.joide.app.ModeSetupFragment;
import jo.joide.app.ai.EncryptedKeyVault;
import jo.joide.app.databinding.SettingsFragmentBinding;
import jo.joide.app.databinding.SettingsHubRowBinding;
import jo.joide.corestore.ModeManager;
import jo.joide.corestore.ModePreferences;
import jo.joide.workbench.WorkMode;

/**
 * Hub Paramètres (studio HTML : settingsScreen) : barre sticky, carte profil
 * conteneur_primaire, quatre sections de rangées (Éditeur, IA, Construction,
 * Système) — pastille + titre + desc + chevR, séparateurs inset.
 *
 * <p>Câblage réel conservé (lot L8, conception §11) : IA → page Réglage IA
 * (fournisseur, clé au coffre, porte de permission), Moteurs de build →
 * installation / bascule / réparation / désinstallation réelles (ADR-002),
 * Diagnostic → rapport copiable avec secrets rédigés (§10).</p>
 */
public final class SettingsFragment extends Fragment {

    private KeyVault vault;
    private InteractivePermissionGate gate;
    private ModeManager modes;
    private SettingsFragmentBinding binding;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        Context context = requireContext();
        vault = new EncryptedKeyVault(context);
        gate = sharedGate();
        modes = new ModeManager(context.getFilesDir().toPath().resolve("modes"),
                new ModePreferences(ModeSetupFragment.preferencesFile(context)));
        binding = SettingsFragmentBinding.inflate(inflater, container, false);

        // Barre : retour.
        binding.backButton.setOnClickListener(v ->
                requireActivity().getSupportFragmentManager().popBackStack());

        // Carte profil : mode actif réel + nombre de projets récents du stockage.
        binding.profileSubtitle.setText(getString(
                R.string.settings_profile_mode_active,
                activeMode().name(),
                recentProjectCount()));

        wireEditorRows();
        wireIaRows();
        wireBuildRows(context);
        wireSystemRows(context);
        return binding.getRoot();
    }

    /** Mode actif réel (ModePreferences — même lecture que l'atelier). */
    private WorkMode activeMode() {
        ModePreferences preferences = new ModePreferences(
                ModeSetupFragment.preferencesFile(requireContext()));
        jo.joide.corestore.ModeChoice choice = preferences.read();
        return choice != null && choice.chosenMode() != null
                ? choice.chosenMode() : WorkMode.FORGE;
    }

    /** Projets récents (répertoire projects du stockage interne, borné à 9). */
    private int recentProjectCount() {
        java.io.File projects = new java.io.File(
                requireContext().getFilesDir(), "projects");
        java.io.File[] entries = projects.listFiles();
        return entries != null ? Math.min(entries.length, 9) : 0;
    }

    // ---- Section Éditeur (rangées studio : Apparence, Éditeur & clavier, Raccourcis)

    private void wireEditorRows() {
        row(binding.rowAppearance, R.drawable.ic_wb,
                R.string.settings_row_appearance, R.string.settings_row_appearance_desc);
        row(binding.rowEditorKeyboard, R.drawable.ic_code,
                R.string.settings_row_editor_keyboard,
                R.string.settings_row_editor_keyboard_desc);
        row(binding.rowShortcuts, R.drawable.ic_book,
                R.string.settings_row_shortcuts, R.string.settings_row_shortcuts_desc);
        // Rangées descriptives du studio : rien à régler encore (lots suivants) —
        // le retour honnête est le toast.
        binding.rowAppearance.getRoot().setOnClickListener(v ->
                notYet(R.string.settings_row_appearance));
        binding.rowEditorKeyboard.getRoot().setOnClickListener(v ->
                notYet(R.string.settings_row_editor_keyboard));
        binding.rowShortcuts.getRoot().setOnClickListener(v ->
                notYet(R.string.settings_row_shortcuts));
    }

    // ---- Section IA (rangées studio : Fournisseurs, Permissions de l'agent) ----

    private void wireIaRows() {
        row(binding.rowProviders, R.drawable.ic_spark,
                R.string.settings_row_providers, R.string.settings_row_providers_desc);
        row(binding.rowAgentPermissions, R.drawable.ic_person,
                R.string.settings_row_agent_permissions,
                R.string.settings_row_agent_permissions_desc);
        // Fournisseurs → page Réglage IA (fournisseur actif, clé, modèle, effort).
        binding.rowProviders.getRoot().setOnClickListener(v ->
                getParentFragmentManager().beginTransaction()
                        .replace(((ViewGroup) requireView().getParent()).getId(),
                                new SettingsIaHubFragment())
                        .addToBackStack(null)
                        .commit());
        // Permissions de l'agent → la porte réelle, réglée depuis la page IA.
        binding.rowAgentPermissions.getRoot().setOnClickListener(v ->
                getParentFragmentManager().beginTransaction()
                        .replace(((ViewGroup) requireView().getParent()).getId(),
                                new SettingsIaHubFragment())
                        .addToBackStack(null)
                        .commit());
    }

    // ---- Section Construction (moteurs, runtime, terminal) -------------------

    private void wireBuildRows(Context context) {
        row(binding.rowBuildEngines, R.drawable.ic_play,
                R.string.settings_row_build_engines,
                R.string.settings_row_build_engines_desc);
        row(binding.rowRuntime, R.drawable.ic_gear,
                R.string.settings_row_runtime, R.string.settings_row_runtime_desc);
        row(binding.rowTerminal, R.drawable.ic_terminal,
                R.string.settings_row_terminal, R.string.settings_row_terminal_desc);

        // Moteurs de build : la rangée porte le bascuule réel du mode actif
        // (ADR-002 : le changement de mode vit ici) ; l'installation vit sur
        // l'écran dédié InstallFragment.
        binding.rowBuildEngines.getRoot().setOnClickListener(v ->
                getParentFragmentManager().beginTransaction()
                        .replace(((ViewGroup) requireView().getParent()).getId(),
                                jo.joide.app.install.InstallFragment.forMode(activeMode()))
                        .addToBackStack(null)
                        .commit());
        // Runtime de build : tas du moteur, mode basse mémoire, constats
        // honnêtes (processus séparé, NEWEST) — lot L10.
        binding.rowRuntime.getRoot().setOnClickListener(v ->
                getParentFragmentManager().beginTransaction()
                        .replace(((ViewGroup) requireView().getParent()).getId(),
                                new jo.joide.app.config.BuildRuntimeFragment())
                        .addToBackStack(null)
                        .commit());
        // Terminal : sessions réelles du mode Gradle.
        binding.rowTerminal.getRoot().setOnClickListener(v ->
                getParentFragmentManager().beginTransaction()
                        .replace(((ViewGroup) requireView().getParent()).getId(),
                                new jo.joide.app.terminal.TerminalFragment())
                        .addToBackStack(null)
                        .commit());
    }

    // ---- Section Système (stockage, diagnostic, à propos) ---------------------

    private void wireSystemRows(Context context) {
        row(binding.rowStorage, R.drawable.ic_folder,
                R.string.settings_row_storage, R.string.settings_row_storage_desc);
        row(binding.rowDiagnostic, R.drawable.ic_gear,
                R.string.settings_row_diagnostic, R.string.settings_row_diagnostic_desc);
        row(binding.rowAbout, R.drawable.ic_star,
                R.string.settings_row_about, R.string.settings_row_about_desc);

        // Diagnostic : l'écran dédié (studio HTML) — versions constatées, modes,
        // journaux bornés, copier/partager réels (secrets rédigés, §10).
        binding.rowDiagnostic.getRoot().setOnClickListener(v ->
                getParentFragmentManager().beginTransaction()
                        .replace(((ViewGroup) requireView().getParent()).getId(),
                                new DiagnosticFragment())
                        .addToBackStack(null)
                        .commit());
        // À propos : version réelle de la coquille.
        binding.rowAbout.getRoot().setOnClickListener(v ->
                Toast.makeText(context, BuildConfig.VERSION_NAME, Toast.LENGTH_SHORT).show());
        // Stockage : rien de destructif sans écran dédié — honnête.
        binding.rowStorage.getRoot().setOnClickListener(v ->
                notYet(R.string.settings_row_storage));
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

    /** Remplit une rangée incluse (icône, titre, description — jamais de texte dur). */
    private void row(SettingsHubRowBinding row, int icon, int title, int desc) {
        row.rowIcon.setImageResource(icon);
        row.rowTitle.setText(getString(title));
        row.rowDesc.setText(getString(desc));
    }

    /** Rangées dont l'écran dédié n'existe pas encore : retour honnête. */
    private void notYet(int which) {
        Toast.makeText(requireContext(), getString(which), Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
