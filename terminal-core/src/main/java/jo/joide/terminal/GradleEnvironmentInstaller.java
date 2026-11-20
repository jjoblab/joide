package jo.joide.terminal;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Installateur de l'environnement Gradle (mode Gradle, conception §7 et §9) :
 * {@code filesDir/gradle-env/} avec le préfixe bootstrap (ADR-005/ADR-011).
 * Le principe central est repris de l'ADR-0085 de CodeIDE (consigne du
 * propriétaire) : « installé = vérifié » — chaque phase est vérifiée par
 * exécution avant de passer à la suivante ; l'état d'avancement est persisté
 * ({@code install-state.json}) pour reprendre une installation interrompue.
 *
 * <p>Phases : BOOTSTRAP (archive Linux de base), OUTILS (paquets : tar, xz,
 * curl), JAVA (JDK), GRADLE (distribution Gradle). La vérification se fait
 * par exécution réelle dans le préfixe installé.</p>
 */
public final class GradleEnvironmentInstaller {

    /** Racine de l'environnement Gradle (appareil : filesDir/gradle-env). */
    private final Path root;

    /** Vérificateur de commandes dans le préfixe (exécution réelle). */
    private final CommandVerifier verifier;

    /** État persisté : phase atteinte, reprise d'une installation interrompue. */
    private final Path stateFile;

    /** Phases de l'installation, dans l'ordre. */
    public enum Phase { BOOTSTRAP, TOOLS, JAVA, GRADLE }

    /** Vérificateur d'une commande du préfixe (retourne vrai si elle réussit). */
    public interface CommandVerifier {
        boolean verify(String commandInPrefix);
    }

    /** Source des composants de l'environnement (réseau ou dossier local). */
    public interface ComponentSource {
        /** Copie l'archive du composant vers un fichier local ; sa vérification (taille/empreinte) est à la charge de la source. */
        Path fetch(String componentName) throws IOException;
    }

    /** Nouvel installateur sur une racine (créée si absente). */
    public GradleEnvironmentInstaller(Path root, CommandVerifier verifier) {
        this.root = root;
        this.verifier = verifier;
        this.stateFile = root.resolve("install-state.json");
    }

    /** Préfixe de l'environnement (bootstrap usr/, ADR-005). */
    public Path prefix() {
        return root.resolve("usr");
    }

    /** HOME de l'environnement (projets et .gradle). */
    public Path home() {
        return root.resolve("home");
    }

    /** État d'installation : dernière phase vérifiée (null : rien). */
    public Phase installedPhase() throws IOException {
        if (!Files.isRegularFile(stateFile)) {
            return null;
        }
        String raw = Files.readString(stateFile);
        // Format minimaliste clé/valeur : phase=GRADLE
        for (String line : raw.split("\n")) {
            if (line.startsWith("phase=")) {
                return Phase.valueOf(line.substring("phase=".length()).trim());
            }
        }
        return null;
    }

    private void persistPhase(Phase phase) throws IOException {
        Files.writeString(stateFile, "phase=" + phase + "\n");
    }

    /**
     * Exécute (ou reprend) l'installation phase par phase : chaque phase est
     * vérifiée par exécution avant d'être persistée — une interruption laisse
     * l'état sur la dernière phase vérifiée, la reprise repart de là.
     *
     * @param source source des composants (réseau ou miroir local).
     * @throws IOException si une phase échoue (état inchangé).
     */
    public void install(ComponentSource source) throws IOException {
        Files.createDirectories(root);
        // Reprise : repasser les phases déjà vérifiées sans re-télécharger.
        Phase reached = installedPhase();
        for (Phase phase : Phase.values()) {
            if (reached != null && reached.ordinal() >= phase.ordinal()) {
                continue; // déjà vérifiée (installation reprise)
            }
            switch (phase) {
                case BOOTSTRAP -> extract(source.fetch("bootstrap"), root);
                case TOOLS -> extract(source.fetch("outils"), prefix());
                case JAVA -> extract(source.fetch("jdk"), prefix());
                case GRADLE -> extract(source.fetch("gradle"), prefix());
            }
            if (!verifier.verify(verificationCommand(phase))) {
                throw new IOException("Phase " + phase
                        + " installée mais NON vérifiée (installé = vérifié, échec refusé)");
            }
            persistPhase(phase);
        }
    }

    /** Commande de vérification d'une phase (exécution réelle dans le préfixe). */
    static String verificationCommand(Phase phase) {
        return switch (phase) {
            case BOOTSTRAP -> "bin/sh -c 'echo ok'";
            case TOOLS -> "bin/tar --version";
            case JAVA -> "bin/java -version";
            case GRADLE -> "bin/gradle --version";
        };
    }

    /** Vérification profonde (ADR-0085) : build réel sur un projet factice. */
    public boolean deepVerify() {
        return verifier.verify("bin/gradle -q build");
    }

    /** État lisible pour le port BuildBackend. */
    public jo.joide.workbench.InstallState installState() throws IOException {
        Phase phase = installedPhase();
        if (phase == null) {
            return jo.joide.workbench.InstallState.NOT_INSTALLED;
        }
        return phase == Phase.GRADLE
                ? jo.joide.workbench.InstallState.INSTALLED
                : jo.joide.workbench.InstallState.BROKEN;
    }

    /** Extraction protégée (traversée de chemin refusée — conception §9). */
    private static void extract(Path archive, Path target) throws IOException {
        java.util.zip.ZipInputStream zip = new java.util.zip.ZipInputStream(
                Files.newInputStream(archive));
        java.util.zip.ZipEntry entry;
        while ((entry = zip.getNextEntry()) != null) {
            Path file = target.resolve(entry.getName()).normalize();
            if (!file.startsWith(target)) {
                throw new IOException("Entrée hors cible refusée : " + entry.getName());
            }
            if (entry.isDirectory()) {
                Files.createDirectories(file);
            } else {
                Files.createDirectories(file.getParent());
                Files.copy(zip, file,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    /** Environnement des sous-processus (repéré d'EnvironnementProcessus de CodeIDE). */
    public Map<String, String> processEnvironment(String androidClasspath) {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("HOME", home().toString());
        env.put("PREFIX", prefix().toString());
        env.put("TMPDIR", prefix().resolve("tmp").toString());
        env.put("LANG", "fr_FR.UTF-8");
        env.put("PATH", prefix().resolve("bin").toString());
        env.put("GRADLE_USER_HOME", home().resolve(".gradle").toString());
        if (androidClasspath != null) {
            // CLASSPATH hérité d'Android : retiré (bug documenté du bootstrap),
            // la variable n'est posée QUE pour le moteur Forge.
            env.remove("CLASSPATH");
        }
        return env;
    }
}
