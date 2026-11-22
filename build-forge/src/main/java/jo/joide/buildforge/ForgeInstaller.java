package jo.joide.buildforge;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import jo.forge.setup.download.CancellationToken;
import jo.forge.setup.download.Downloader;
import jo.forge.setup.download.InstallException;
import jo.forge.setup.install.EngineInstaller;
import jo.forge.setup.install.ForgeHome;
import jo.forge.setup.install.InstalledState;
import jo.forge.setup.manifest.CanalMoteur;
import jo.forge.setup.manifest.EngineManifest;
import jo.forge.setup.manifest.ManifestVerifier;
import jo.joide.workbench.InstallState;

/**
 * Installation du moteur Forge : réutilise l'installateur du module {@code setup}
 * de Forge (manifeste signé, reprise par plages HTTP, installation atomique —
 * conception §4.2, ADR-011). JoIDE n'écrit aucune logique d'installation : le canal
 * signé de Forge est la source de vérité, la clé publique est épinglée ici.
 *
 * <p>Forge v0.2.24 ajoute deux protections que ce port active d'office : l'<b>anti-recul</b>
 * (un manifeste signé mais plus ancien que celui installé — miroir périmé, canal
 * compromis — est refusé avant tout téléchargement) et la <b>réparation au
 * démarrage</b> d'une installation interrompue (l'ancien moteur mis à l'écart est
 * rétabli automatiquement).</p>
 *
 * <p>Les tailles affichées par l'assistant viennent du manifeste vérifié (jamais
 * inventées, ADR-002).</p>
 */
public final class ForgeInstaller {

    /**
     * Clé publique ECDSA (X.509, base64) du canal manifeste de Forge, épinglée —
     * identique à celle que Forge épingle dans sa coquille ({@code ClesForge},
     * module app). Clé publique, pas un secret : remplacer la signature d'un
     * manifeste exige la clé privée qui reste chez Forge. Propriété
     * {@code joide.forge.manifestKey} pour la surcharger (tests, autre canal).
     */
    public static final String MANIFEST_PUBLIC_KEY = System.getProperty(
            "joide.forge.manifestKey",
            "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEZ2I0By86IiBVR1cB1wKeWYG25wWcpg1RJ360V6sl"
                    + "OQlacWA6T4jNi7U1KXFFNyDL/gyCxrBikr0h2eNQzocPDw==");

    /**
     * Clé publique de ROTATION (Forge v0.2.24) : vide tant qu'aucune rotation n'est
     * engagée par Forge. Le jour où Forge remplace sa clé, la nouvelle vient ici et
     * les manifestes signés par l'une ou l'autre sont acceptés — remplacer une clé
     * compromise n'orphanera pas les installations existantes.
     */
    public static final String MANIFEST_PUBLIC_KEY_NEXT = System.getProperty(
            "joide.forge.manifestKeyNext", "");

    /** Canal du manifeste du moteur Forge (release GitHub, ADR-011). */
    public static final String MANIFEST_CHANNEL_URL =
            "https://github.com/jjoblab/forge/releases/latest/download/manifest.json";

    private final ForgeHome home;
    private final EngineInstaller installer;

    /** Nouvel installateur sur un ForgeHome donné (appareil : filesDir/forge). */
    public ForgeInstaller(Path forgeRoot) {
        this.home = new ForgeHome(forgeRoot);
        // Source réseau : téléchargements vers forge/downloads (reprise par plages).
        // Extracteur : plafond par défaut de Forge (512 Mo, contrôlé en flux).
        this.installer = new EngineInstaller(home,
                new jo.forge.setup.install.ComponentSource.Network(home.downloads()),
                new jo.forge.setup.install.ArchiveExtractor());
    }

    /** Installation locale hors ligne (tests, miroir sur l'appareil). */
    public ForgeInstaller(Path forgeRoot, Path localComponentsDirectory) {
        this.home = new ForgeHome(forgeRoot);
        this.installer = new EngineInstaller(home,
                new jo.forge.setup.install.ComponentSource.LocalDirectory(localComponentsDirectory),
                new jo.forge.setup.install.ArchiveExtractor());
    }

    /** Racine ForgeHome (moteur lancé depuis ce répertoire installé). */
    ForgeHome home() {
        return home;
    }

    /**
     * Répare au démarrage une installation interrompue (Forge v0.2.24) : un moteur
     * mis à l'écart par une réinstallation tuée entre deux renommages est rétabli à
     * sa place, et les préparations orphelines sont purgées. À appeler une fois au
     * lancement de l'application, avant tout écran.
     *
     * @return vrai si un moteur a été rétabli.
     */
    public boolean recoverInterruptedInstall() {
        return installer.rétablirSiInterrompue();
    }

    /** État courant du moteur installé (null si aucun). */
    public Optional<InstalledState> currentState() {
        return Optional.ofNullable(installer.currentState());
    }

    /** État pour le contrat BuildBackend. */
    public InstallState installState() {
        InstalledState state = installer.currentState();
        if (state == null) {
            return InstallState.NOT_INSTALLED;
        }
        // Altération constatée (vérification SHA-256 des fichiers) : CASSE, pas muet.
        return installer.verify().isEmpty() ? InstallState.INSTALLED : InstallState.BROKEN;
    }

    /**
     * Télécharge et vérifie le manifeste du canal (signature ECDSA de la clé
     * épinglée, clé de rotation acceptée aussi) — puis applique l'anti-recul :
     * un manifeste signé mais plus ancien que celui installé est refusé net.
     *
     * @throws IOException si le canal est injoignable, la signature invalide ou
     *                    le manifeste trop ancien (anti-recul).
     */
    public EngineManifest downloadManifest() throws IOException {
        CanalMoteur channel = new CanalMoteur(MANIFEST_PUBLIC_KEY,
                MANIFEST_PUBLIC_KEY_NEXT, Set.of("https"));
        CanalMoteur.ManifesteSigne signed = channel.telecharger(MANIFEST_CHANNEL_URL);
        // La vérification est déjà faite par le canal ; on la refait explicitement
        // pour documenter le refus (manifeste falsifié => IOException, jamais muet).
        if (!new ManifestVerifier(MANIFEST_PUBLIC_KEY)
                .verify(signed.octets(), signed.signatureBase64())) {
            throw new IOException("Manifeste Forge falsifié : signature invalide (refus)");
        }
        // Anti-recul (Forge v0.2.24) : refus AVANT tout téléchargement de composant.
        String rollbackRefusal = installer.refusAntiRecul(signed.manifeste());
        if (rollbackRefusal != null) {
            throw new IOException("Manifeste Forge refusé (anti-recul) : " + rollbackRefusal);
        }
        return signed.manifeste();
    }

    /**
     * Installe le moteur décrit par le manifeste (atomique, reprenable, annulable).
     *
     * @param manifest  manifeste vérifié.
     * @param abi       ABI cible (appareil : {@code arm64-v8a} ; JVM : {@code all}).
     * @param progress  écouteur de progression (peut être nul) : octets reçus / total.
     * @param cancel    jeton d'annulation (les octets déjà téléchargés sont conservés).
     * @throws IOException si l'installation échoue (état précédent intact).
     */
    public void install(EngineManifest manifest, String abi, ProgressListener progress,
            CancellationToken cancel) throws IOException {
        try {
            installer.install(manifest, abi,
                    progress == null ? null : progress::onProgress,
                    cancel == null ? new CancellationToken() : cancel);
        } catch (InstallException e) {
            throw new IOException("Installation Forge échouée : " + e.getMessage(), e);
        }
        // InstallCancelledException ne sort pas : l'annulation est un choix, pas une erreur.
    }

    /**
     * Porte anti-recul exposée (tests, affichage) : message de refus si le manifeste
     * est plus ancien que l'installation en cours, sinon null.
     */
    public String rollbackRefusal(EngineManifest manifest) {
        return installer.refusAntiRecul(manifest);
    }

    /**
     * Composants d'un manifeste déjà présents dans le cache local (empreinte
     * vérifiée) : l'assistant les affiche « déjà téléchargés — aucun octet ».
     */
    public Set<String> cachedComponentIds(EngineManifest manifest, String abi) {
        Set<String> cached = new HashSet<>();
        for (var component : manifest.forAbi(abi)) {
            if (installer.composantEnCache(component)) {
                cached.add(component.id());
            }
        }
        return cached;
    }

    /** Taille totale à télécharger pour un manifeste et une ABI (assistant, ADR-002). */
    public static long downloadSize(EngineManifest manifest, String abi) {
        long total = 0;
        for (var component : manifest.forAbi(abi)) {
            total += component.size();
        }
        return total;
    }

    /** Vérifie l'installation : liste des fichiers altérés (vide = saine). */
    public List<String> verify() {
        return installer.verify();
    }

    /** Désinstalle le moteur (retourne vrai si quelque chose a été retiré). */
    public boolean uninstall() {
        return installer.uninstall();
    }

    /** Progression de l'installation (octets reçus au total, total attendu, -1 si inconnu). */
    public interface ProgressListener {

        /** Appelé régulièrement pendant le téléchargement d'un composant. */
        void onProgress(long bytesReceived, long bytesTotal);
    }
}
