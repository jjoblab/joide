package jo.joide.app.install;

import android.content.Context;
import android.os.Bundle;
import android.os.StatFs;
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
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import androidx.activity.OnBackPressedCallback;
import jo.forge.setup.download.CancellationToken;
import jo.forge.setup.install.InstalledState;
import jo.forge.setup.manifest.ComponentManifest;
import jo.forge.setup.manifest.EngineManifest;
import jo.joide.app.HomeFragment;
import jo.joide.app.R;
import jo.joide.app.databinding.InstallFragmentBinding;
import jo.joide.app.ModeSetupFragment;
import jo.joide.buildforge.ForgeBackend;
import jo.joide.buildforge.ForgeEngineManager;
import jo.joide.buildforge.ForgeInstaller;
import jo.joide.corestore.ModeManager;
import jo.joide.corestore.ModePreferences;
import jo.joide.workbench.InstallState;
import jo.joide.workbench.WorkMode;

/**
 * Écran « Installation du moteur » — le design du studio HTML (barre, cartes,
 * pills, CTA asymétrique) branché sur le VRAI canal Forge : manifeste signé
 * téléchargé et vérifié à l'entrée (clé épinglée, anti-recul v0.2.24),
 * composants et tailles réels, badge « déjà téléchargé » du cache local,
 * progression en octets réelle, annulation qui conserve les octets, réparation
 * (réinstallation atomique) et désinstallation réelles.
 *
 * <p>Mode Forge seul (ADR-009) : la carte Gradle reste affichée pour la
 * transparence du portefeuille mais marquée « à venir » — le terminal intégré
 * et le serveur d'outils Gradle ne sont pas encore activés.</p>
 */
public class InstallFragment extends Fragment {

    /** Instance pré-positionnée sur un mode (depuis les paramètres ; Forge actif). */
    public static InstallFragment forMode(WorkMode mode) {
        InstallFragment fragment = new InstallFragment();
        Bundle args = new Bundle();
        args.putString("mode", mode.name());
        fragment.setArguments(args);
        return fragment;
    }

    private InstallFragmentBinding binding;
    private ModeManager modes;
    private ForgeBackend backend;
    private CancellationToken installToken;

    /** Manifeste vérifié du canal (null tant que non chargé : catalogue de repli). */
    private EngineManifest manifest;
    /** Composants du manifeste déjà dans le cache local (badge, économie). */
    private Set<String> cachedIds = Set.of();
    /** Téléchargement du manifeste en cours (évite le double clic CTA). */
    private CompletableFuture<Void> manifestFuture;
    /**
     * État d'installation connu (cache). La vérification SHA-256 de tout le
     * moteur est LOURDE (des centaines de Mo) : elle vit hors fil UI, sinon
     * l'écran gèle et même le bouton retour ne répond plus (bug réel du
     * premier lancement). Première valeur optimiste : fichier d'état lu sans
     * vérification, corrigée dès que la vérification de fond arrive.
     */
    private volatile InstallState installState = InstallState.NOT_INSTALLED;
    /** Vérification d'état en vol (une seule à la fois, jamais cumulées). */
    private CompletableFuture<Void> stateFuture;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
            @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = InstallFragmentBinding.inflate(inflater, container, false);
        modes = new ModeManager(requireContext().getFilesDir().toPath().resolve("modes"),
                new ModePreferences(ModeSetupFragment.preferencesFile(requireContext())));
        backend = new ForgeBackend(
                requireContext().getFilesDir().toPath().resolve("forge"),
                new jo.forge.client.DeviceVmLauncher(),
                deviceProfile());
        // Première valeur honnête sans vérification lourde : un moteur inscrit
        // dans l'état est présumé installé (la vérification de fond affine).
        installState = backend.engineManager().installer().currentState().isPresent()
                ? InstallState.INSTALLED : InstallState.NOT_INSTALLED;

        // Retour du premier lancement : la pile est VIDE (l'assistant a
        // remplacé sans empiler) — popBackStack ne ferait rien. Le retour mène
        // alors à l'accueil, jamais à un bouton mort ni à une sortie muette.
        binding.installBackButton.setOnClickListener(v -> backOrHome());
        requireActivity().getOnBackPressedDispatcher().addCallback(
                getViewLifecycleOwner(), new OnBackPressedCallback(true) {
                    @Override
                    public void handleOnBackPressed() {
                        backOrHome();
                    }
                });
        binding.installActionButton.setOnClickListener(v -> startInstall());
        binding.installRepairButton.setOnClickListener(v -> startInstall());
        binding.installUninstallButton.setOnClickListener(v -> showUninstallPopup());
        binding.installCancelButton.setOnClickListener(v -> cancelInstall());
        binding.installUninstallCancelButton.setOnClickListener(v -> hideUninstallPopup());
        binding.installUninstallConfirmButton.setOnClickListener(v -> {
            hideUninstallPopup();
            uninstallEngine();
        });
        // L'installation ne part jamais sans l'accord explicite (interrupteur
        // données mobiles du studio) : le CTA suit l'interrupteur.
        binding.installWifiSwitch.setOnCheckedChangeListener((v, checked) ->
                binding.installActionButton.setEnabled(checked && manifest != null));

        binding.installChannelUrl.setText(ForgeInstaller.MANIFEST_CHANNEL_URL);
        render();
        loadManifest();
        return binding.getRoot();
    }

    /** Profil appareil réel : ABI principale + RAM totale (Mo). */
    private ForgeEngineManager.DeviceProfile deviceProfile() {
        android.app.ActivityManager manager = (android.app.ActivityManager)
                requireContext().getSystemService(Context.ACTIVITY_SERVICE);
        android.app.ActivityManager.MemoryInfo infos =
                new android.app.ActivityManager.MemoryInfo();
        manager.getMemoryInfo(infos);
        return new ForgeEngineManager.DeviceProfile(
                android.os.Build.SUPPORTED_ABIS.length > 0
                        ? android.os.Build.SUPPORTED_ABIS[0] : "all",
                infos.totalMem / (1024L * 1024L));
    }

    // ---- Manifeste du canal : signature + anti-recul ------------------------

    /** Charge le manifeste signé du canal (fond) : composants et tailles réels. */
    private void loadManifest() {
        if (manifestFuture != null && !manifestFuture.isDone()) {
            return;
        }
        binding.installSigBadge.setText(getString(R.string.install_canal_chargement));
        binding.installSigBadge.setBackgroundResource(R.drawable.fond_pill_sc_highest);
        binding.installSigBadge.setTextColor(color(R.color.sur_var));
        manifestFuture = CompletableFuture.runAsync(() -> {
            try {
                ForgeEngineManager.ChannelManifest canal =
                        backend.engineManager().downloadManifestForAssistant();
                manifest = canal.manifest();
                cachedIds = canal.cachedComponentIds();
            } catch (IOException | RuntimeException e) {
                // Canal injoignable, signature invalide ou anti-recul : l'écran
                // reste honnête — catalogue de repli + message visible, jamais
                // une taille inventée.
                manifest = null;
                onUi(() -> {
                    binding.installSigBadge.setText(getString(R.string.install_canal_sig_ko));
                    binding.installSigBadge.setBackgroundResource(R.drawable.fond_pill_err);
                    binding.installSigBadge.setTextColor(color(R.color.erreur));
                    toast(getString(R.string.install_echec,
                            e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
                });
            }
            onUi(this::render);
        });
    }

    // ---- Navigation : retour honnête selon la pile ----------------------------

    /**
     * Retour : ferme la popup de désinstallation si ouverte, dépile s'il y a
     * une entrée (paramètres, atelier), sinon mène à l'accueil (premier
     * lancement : la pile est vide et le retour ne doit jamais être mort).
     */
    private void backOrHome() {
        if (binding.installUninstallPopup.getVisibility() == View.VISIBLE) {
            hideUninstallPopup();
            return;
        }
        if (getParentFragmentManager().getBackStackEntryCount() > 0) {
            getParentFragmentManager().popBackStack();
        } else {
            goHome();
        }
    }

    /** Poursuit vers l'accueil (premier lancement : racine neuve, sans pile). */
    private void goHome() {
        getParentFragmentManager().beginTransaction()
                .replace(((ViewGroup) requireView().getParent()).getId(), new HomeFragment())
                .commit();
    }

    /** Exécute sur le fil UI si l'écran vit encore (jamais d'activité morte). */
    private void onUi(Runnable action) {
        if (isAdded()) {
            requireActivity().runOnUiThread(() -> {
                if (isAdded() && binding != null) {
                    action.run();
                }
            });
        }
    }

    // ---- Rendu (design studio, données réelles) ------------------------------

    private void render() {
        renderModeCards();
        binding.installModeBadge.setText(getString(R.string.mode_forge_nom));
        renderComponents();
        // Phases vérifiées : concept du mode Gradle (installé = vérifié par
        // commandes) — le moteur Forge vérifie par sommes SHA-256, la carte
        // n'a rien à afficher en mode Forge.
        binding.installPhasesCard.setVisibility(View.GONE);
        renderInstallState();
    }

    /** Cartes de mode : Forge sélectionnée ; Gradle affichée « à venir » (ADR-009). */
    private void renderModeCards() {
        binding.installModePicker.removeAllViews();
        binding.installModePicker.addView(modeCard(WorkMode.FORGE, true));
        binding.installModePicker.addView(modeCard(WorkMode.GRADLE, false));
    }

    /** Carte de mode : active (sélectionnable) ou différée (« à venir »). */
    private View modeCard(WorkMode mode, boolean active) {
        boolean forge = mode == WorkMode.FORGE;
        LinearLayout card = new LinearLayout(requireContext());
        card.setOrientation(LinearLayout.VERTICAL);
        int padH = dp(14);
        int padV = dp(12);
        card.setPadding(padH, padV, padH, padV);
        android.graphics.drawable.GradientDrawable background =
                new android.graphics.drawable.GradientDrawable();
        background.setCornerRadius(dp(26));
        background.setColor(color(active && forge
                ? R.color.conteneur_primaire : R.color.sc_low));
        background.setStroke(dp(2), color(active && forge
                ? R.color.primaire : R.color.sc_low));
        card.setBackground(background);
        card.setAlpha(active ? 1f : 0.55f);

        LinearLayout head = new LinearLayout(requireContext());
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(android.view.Gravity.CENTER_VERTICAL);
        ImageView pastille = new ImageView(requireContext());
        pastille.setImageResource(forge ? R.drawable.ic_code : R.drawable.ic_terminal);
        pastille.setColorFilter(color(forge && active
                ? R.color.sur_primaire : R.color.sur_var));
        LinearLayout.LayoutParams pastilleParams = new LinearLayout.LayoutParams(dp(30), dp(30));
        android.graphics.drawable.GradientDrawable pastilleBg =
                new android.graphics.drawable.GradientDrawable();
        pastilleBg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        pastilleBg.setColor(color(forge && active
                ? R.color.primaire : R.color.sc_highest));
        pastille.setBackground(pastilleBg);
        int pad = dp(7);
        pastille.setPadding(pad, pad, pad, pad);
        head.addView(pastille, pastilleParams);

        TextView label = new TextView(requireContext());
        label.setText(getString(forge
                ? R.string.mode_forge_nom : R.string.install_gradle_bientot));
        label.setTextColor(color(forge && active
                ? R.color.sur_conteneur_primaire : R.color.sur));
        applyText(label, 15, true);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        labelParams.leftMargin = dp(8);
        head.addView(label, labelParams);
        card.addView(head);

        TextView size = new TextView(requireContext());
        size.setText(forge
                ? getString(R.string.install_modele_total, humanBytes(totalBytes()))
                : getString(R.string.install_gradle_desc));
        size.setTextColor(color(forge && active
                ? R.color.sur_conteneur_primaire : R.color.sur_var));
        applyText(size, 12.5f, false);
        LinearLayout.LayoutParams sizeParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sizeParams.topMargin = dp(6);
        card.addView(size, sizeParams);

        if (active) {
            // La carte Forge est déjà l'unique mode actif : la pression re-rend
            // (retour visuel) sans rien changer — Gradle reste informatif.
            card.setOnClickListener(v -> render());
        }
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        params.rightMargin = dp(5);
        card.setLayoutParams(params);
        return card;
    }

    /** Total réel à télécharger : manifeste vérifié, sinon catalogue de repli. */
    private long totalBytes() {
        if (manifest != null) {
            return ForgeInstaller.downloadSize(manifest, deviceAbi());
        }
        return fallbackCatalog().totalBytes();
    }

    /** ABI de l'appareil (composants natifs : aapt2-arm64-v8a). */
    private String deviceAbi() {
        return android.os.Build.SUPPORTED_ABIS.length > 0
                ? android.os.Build.SUPPORTED_ABIS[0] : "all";
    }

    /** Catalogue de repli hors ligne (tailles de la release publiée). */
    private jo.joide.corestore.ModeCatalog fallbackCatalog() {
        return jo.joide.corestore.ModeCatalog.forge();
    }

    /** Composants réels du manifeste (ou repli), avec badge cache et espace libre. */
    private void renderComponents() {
        binding.installTotalText.setText(humanBytes(totalBytes()));
        binding.installMobileText.setText(getString(
                R.string.install_mobiles, humanBytes(Math.max(0, totalBytes()
                        - cachedBytes()))));
        binding.installComponentsList.removeAllViews();

        if (manifest != null) {
            List<ComponentManifest> components = manifest.forAbi(deviceAbi());
            for (int i = 0; i < components.size(); i++) {
                binding.installComponentsList.addView(
                        componentRow(components.get(i)));
                if (i < components.size() - 1) {
                    binding.installComponentsList.addView(divInset());
                }
            }
        } else {
            // Catalogue de repli (canal injoignable) : composants de la dernière
            // release connue, honnêtement marqués.
            List<jo.joide.corestore.ModeCatalog.Component> components =
                    fallbackCatalog().components();
            for (int i = 0; i < components.size(); i++) {
                binding.installComponentsList.addView(componentRow(components.get(i)));
                if (i < components.size() - 1) {
                    binding.installComponentsList.addView(divInset());
                }
            }
        }
        binding.installComponentsList.addView(spaceRow());
    }

    /** Octets déjà dans le cache local (économisés — badge et avertissement). */
    private long cachedBytes() {
        if (manifest == null) {
            return 0;
        }
        long total = 0;
        for (ComponentManifest component : manifest.forAbi(deviceAbi())) {
            if (cachedIds.contains(component.id())) {
                total += component.size();
            }
        }
        return total;
    }

    /** Rangée composant (manifeste réel) : id + taille + badge cache éventuel. */
    private View componentRow(ComponentManifest component) {
        LinearLayout row = baseComponentRow(component.id(), component.size());
        if (cachedIds.contains(component.id())) {
            row.addView(pill(getString(R.string.install_en_cache),
                    R.drawable.fond_pill_ok, R.color.ok, false));
        }
        return row;
    }

    /** Rangée composant (catalogue de repli). */
    private View componentRow(jo.joide.corestore.ModeCatalog.Component component) {
        return baseComponentRow(component.id(), component.bytes());
    }

    /** Rangée composant commune : label + taille mono. */
    private LinearLayout baseComponentRow(String id, long bytes) {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(11), dp(16), dp(11));

        TextView name = new TextView(requireContext());
        name.setText(id);
        name.setTextColor(color(R.color.sur));
        applyText(name, 13.5f, true);
        row.addView(name, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView size = pill(humanBytes(bytes),
                R.drawable.fond_pill_sc_highest, R.color.sur_var, true);
        row.addView(size);
        return row;
    }

    /** Rangée « Espace libre requis » : mesure RÉELLE du stockage (StatFs). */
    private View spaceRow() {
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
        after.setText(getString(R.string.install_espace_apres));
        after.setTextColor(color(R.color.sur_var));
        applyText(after, 12.5f, false);
        LinearLayout.LayoutParams afterParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        afterParams.topMargin = dp(1);
        text.addView(after, afterParams);
        row.addView(text, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // Espace libre réel du stockage privé + marge : le moteur installé
        // pèse environ le double du téléchargement (archives + contenu extrait).
        StatFs stat = new StatFs(requireContext().getFilesDir().getAbsolutePath());
        long libre = stat.getAvailableBytes();
        long requis = (long) (totalBytes() * 2.2);
        boolean suffisant = libre > requis;
        row.addView(pill(getString(suffisant
                        ? R.string.install_espace_ok : R.string.install_espace_ko),
                suffisant ? R.drawable.fond_pill_info : R.drawable.fond_pill_err,
                suffisant ? R.color.info : R.color.erreur, false));
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

    // ---- État d'installation (moteur réel) -----------------------------------

    /**
     * Rafraîchit version installée / CTA / chips selon l'état CONNU (cache) et
     * lance la vérification réelle (SHA-256 de tout le moteur) hors fil UI.
     * {@code render} reste instantané : aucun gel, même juste après une
     * installation de plusieurs centaines de Mo.
     */
    private void renderInstallState() {
        renderInstallStateBits(installedVersion());
        verifyInstallStateAsync();
    }

    /** Version installée (lecture du petit fichier d'état — rapide, fil UI ok). */
    private String installedVersion() {
        return backend.engineManager().installer().currentState()
                .map(InstalledState::engineVersion).orElse(null);
    }

    /** Rendu depuis le cache — AUCUNE E/S lourde ici (fil UI). */
    private void renderInstallStateBits(String version) {
        String detail;
        if (installState == InstallState.INSTALLED) {
            detail = getString(R.string.install_version_installee, version);
        } else if (installState == InstallState.BROKEN) {
            detail = getString(R.string.install_version_casse, version);
        } else {
            detail = getString(R.string.install_version_aucune);
        }
        binding.installActionButton.setEnabled(
                binding.installWifiSwitch.isChecked() && manifest != null);
        // Le libellé du CTA vit dans son TextView interne (le conteneur est le
        // LinearLayout cliquable du studio) : « Installer » puis « Réinstaller ».
        TextView actionLabel = (TextView) ((LinearLayout) binding.installActionButton)
                .getChildAt(1);
        actionLabel.setText(getString(
                installState == InstallState.NOT_INSTALLED
                        ? R.string.install_bouton : R.string.install_reparer));
        binding.installRepairButton.setVisibility(
                installState == InstallState.NOT_INSTALLED ? View.GONE : View.VISIBLE);
        binding.installUninstallButton.setVisibility(
                installState == InstallState.NOT_INSTALLED ? View.GONE : View.VISIBLE);
        // Ligne d'état sous le badge du canal : version disponible / installée.
        binding.installTotalText.setText(String.format(Locale.FRANCE, "%s · %s",
                manifest != null
                        ? getString(R.string.install_version_dispo,
                                manifest.engineVersion()) : "—", detail));
    }

    /**
     * Vérification réelle HORS fil UI (SHA-256 de chaque fichier du moteur :
     lourde). Un seul vol à la fois ; le résultat raffine le cache puis
     * re-rend les chips — sans jamais bloquer l'interface ni le retour.
     */
    private void verifyInstallStateAsync() {
        if (stateFuture != null && !stateFuture.isDone()) {
            return; // déjà en vol
        }
        stateFuture = CompletableFuture.runAsync(() -> {
            try {
                installState = backend.installState();
            } catch (RuntimeException e) {
                // Aucun moteur à vérifier (état pivoté entre-temps) : honnête.
                installState = InstallState.NOT_INSTALLED;
            }
        });
        stateFuture.thenRun(() -> onUi(() ->
                renderInstallStateBits(installedVersion())));
    }

    // ---- Installation réelle (progression en octets, annulable) --------------

    private void startInstall() {
        if (manifest == null) {
            loadManifest();
            return;
        }
        installToken = new CancellationToken();
        binding.installProgressCard.setVisibility(View.VISIBLE);
        binding.installActionButton.setEnabled(false);
        binding.installStepText.setText(getString(
                R.string.install_etape, manifest.engineVersion()));
        backend.engineManager().installFromChannel(progress -> {
            long total = progress.bytesTotal() > 0 ? progress.bytesTotal() : 1;
            int percent = (int) Math.min(100,
                    progress.bytesReceived() * 100 / total);
            onUi(() -> {
                binding.installProgressBar.setProgress(percent);
                binding.installPercentText.setText(percent + " %");
                binding.installMetaText.setText(getString(R.string.install_telecharge,
                        humanBytes(progress.bytesReceived()),
                        humanBytes(progress.bytesTotal())));
            });
        }, installToken).whenComplete((rien, échec) -> {
            // Le marqueur de mode persiste hors fil UI (écriture disque) — le
            // fil UI ne reçoit que le rendu.
            String persistance = null;
            if (échec == null) {
                try {
                    modes.markInstalled(WorkMode.FORGE);
                } catch (IOException e) {
                    persistance = e.getMessage();
                }
            }
            final String avertissement = persistance;
            if (!isAdded()) {
                return; // l'utilisateur a quitté l'écran : l'état moteur reste la vérité
            }
            requireActivity().runOnUiThread(() -> {
                if (binding == null) {
                    return;
                }
                binding.installProgressCard.setVisibility(View.GONE);
                if (échec instanceof CancellationException) {
                    toast(getString(R.string.install_annule));
                } else if (échec != null) {
                    toast(getString(R.string.install_echec,
                            échec.getMessage() != null ? échec.getMessage()
                                    : échec.getClass().getSimpleName()));
                } else {
                    if (avertissement != null) {
                        toast(avertissement);
                    }
                    toast(getString(R.string.install_termine, manifest.engineVersion()));
                    if (getParentFragmentManager().getBackStackEntryCount() == 0) {
                        // Premier lancement : l'installation réussie POURSUIT
                        // vers l'accueil — l'utilisateur n'a plus rien à faire
                        // ici et le retour n'est plus un cul-de-sac.
                        goHome();
                        return;
                    }
                }
                loadManifest();
                render();
            });
        });
    }

    private void cancelInstall() {
        if (installToken != null) {
            installToken.cancel();
        }
    }

    // ---- Désinstallation réelle -----------------------------------------------

    private void showUninstallPopup() {
        binding.installUninstallPopup.setVisibility(View.VISIBLE);
    }

    private void hideUninstallPopup() {
        binding.installUninstallPopup.setVisibility(View.GONE);
    }

    private void uninstallEngine() {
        CompletableFuture.runAsync(() -> {
            try {
                backend.engineManager().installer().uninstall();
                modes.uninstall(WorkMode.FORGE);
            } catch (IOException e) {
                onUi(() -> toast(e.getMessage()));
            }
            onUi(() -> {
                toast(getString(R.string.install_desinstalle));
                loadManifest();
                render();
            });
        });
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

    /** Taille lisible (« 51,0 Mo » ; « 1,2 Go » au-delà du gigaoctet). */
    private String humanBytes(long bytes) {
        if (bytes >= 1024L * 1024 * 1024) {
            return String.format(Locale.FRANCE, "%.1f Go",
                    bytes / 1024.0 / 1024.0 / 1024.0);
        }
        return String.format(Locale.FRANCE, "%.1f Mo", bytes / 1024.0 / 1024.0);
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5);
    }

    private void toast(String message) {
        Toast.makeText(requireContext(),
                message != null ? message : "", Toast.LENGTH_SHORT).show();
    }
}
