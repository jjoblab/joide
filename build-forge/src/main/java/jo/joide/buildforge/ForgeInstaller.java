package jo.joide.buildforge;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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
            "joide.forge.manifestKey", "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEZ2I0By86IiBVR1cB1wKeWYG25wWcpg1RJ360V6sl");

    /** Canal du manifeste du moteur Forge (release GitHub, ADR-011). */
    public static final String MANIFEST_CHANNEL_URL =
            "https://github.com/jjoblab/forge/releases/latest/download/manifest.json";

    private final ForgeHome home;
    private final EngineInstaller installer;

    /** Nouvel installateur sur un ForgeHome donné (appareil : filesDir/forge). */
    public ForgeInstaller(Path forgeRoot) {
        this.home = new ForgeHome(forgeRoot);
        // Source réseau : téléchargements vers forge/downloads (reprise par plages).
        // Extracteur : plafond par défaut de Forge (512 Mo).
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
     * Télécharge et vérifie le manifeste du canal (signature ECDSA de la clé épinglée).
     *
     * @throws IOException si le canal est injoignable ou la signature invalide.
     */
    public EngineManifest downloadManifest() throws IOException {
        CanalMoteur channel = new CanalMoteur(MANIFEST_PUBLIC_KEY, Set.of("https"));
        CanalMoteur.ManifesteSigne signed = channel.telecharger(MANIFEST_CHANNEL_URL);
        // La vérification est déjà faite par le canal ; on la refait explicitement
        // pour documenter le refus (manifeste falsifié => IOException, jamais muet).
        if (!new ManifestVerifier(MANIFEST_PUBLIC_KEY)
                .verify(signed.octets(), signed.signatureBase64())) {
            throw new IOException("Manifeste Forge falsifié : signature invalide (refus)");
        }
        return signed.manifeste();
    }

    /**
     * Installe le moteur décrit par le manifeste (atomique, reprenable).
     *
     * @param manifest     manifeste vérifié.
     * @param abi          ABI cible (appareil : {@code arm64-v8a} ; JVM : {@code all}).
     * @param progress     écouteur de progression (peut être nul).
     * @throws IOException si l'installation échoue (état précédent intact).
     */
    public void install(EngineManifest manifest, String abi,
            jo.forge.setup.download.Downloader.ProgressListener progress) throws IOException {
        try {
            // Jeton jamais annulé par défaut : l'annulation (réglage L8) branchera un
            // vrai CancellationToken partagé avec l'interface d'installation.
            installer.install(manifest, abi, progress,
                    new jo.forge.setup.download.CancellationToken());
        } catch (jo.forge.setup.download.InstallException e) {
            throw new IOException("Installation Forge échouée : " + e.getMessage(), e);
        }
        // InstallCancelledException ne sort pas : l'annulation est un choix, pas une erreur.
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
}
