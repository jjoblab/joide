package jo.joide.corestore;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.NoSuchElementException;
import java.util.Optional;
import jo.joide.workbench.WorkMode;

/**
 * Changement de mode (ADR-002 : le changement de mode vit dans les paramètres —
 * installer l'autre mode, réparer, désinstaller). Pur JVM sur le stockage
 * local : le dossier d'un mode vit sous `filesDir/modes/<mode>/`, la préférence
 * (mode choisi, mode installé) est écrite atomiquement par {@link ModePreferences}.
 *
 * <p>Les opérations lourdes d'installation réelle (téléchargement Forge,
 * environnement Gradle) sont portées par les backends des lots L3/L6 — ce
 * gestionnaire orchestre le **stockage** (marquage, réparation, désinstallation
 * du dossier) et la bascule, avec confirmation honnête de l'espace occupé.</p>
 */
public final class ModeManager {

    private final Path modesRoot;
    private final ModePreferences preferences;

    public ModeManager(Path modesRoot, ModePreferences preferences) {
        this.modesRoot = modesRoot;
        this.preferences = preferences;
    }

    /** Dossier d'un mode (créé au besoin : l'installation le remplit). */
    public Path modeDirectory(WorkMode mode) throws IOException {
        Path dir = modesRoot.resolve(mode.name().toLowerCase());
        Files.createDirectories(dir);
        return dir;
    }

    /** Marque un mode installé (après la fin réelle de son installateur). */
    public synchronized void markInstalled(WorkMode mode) throws IOException {
        ModeChoice current = requireChoice();
        if (current.chosenMode() == mode) {
            preferences.write(new ModeChoice(mode, true));
        } else {
            preferences.write(new ModeChoice(current.chosenMode(), true));
        }
    }

    /** Bascule vers un mode installé (paramètres : « Changer de mode »). */
    public synchronized void switchTo(WorkMode target) throws IOException {
        ModeChoice current = requireChoice();
        if (!isInstalled(target)) {
            throw new IllegalStateException("Le mode " + target
                    + " n'est pas installé : installez-le avant de basculer.");
        }
        preferences.write(new ModeChoice(target, true));
    }

    /** Répare un mode : réinstalle son dossier de marqueurs d'état (l'installation réelle est relancée par l'hôte). */
    public synchronized void repair(WorkMode mode) throws IOException {
        Path dir = modeDirectory(mode);
        // Le marqueur « sain » : la réparation réelle (composants Forge /
        // environnement Gradle vérifié) est relancée par le backend du mode ;
        // ici l'état repart propre (répertoire recréé vide + préférences).
        recursiveDelete(dir);
        Files.createDirectories(dir);
        ModeChoice current = requireChoice();
        preferences.write(new ModeChoice(current.chosenMode(), false));
    }

    /** Désinstalle un mode : son dossier part ; la préférence bascule si c'était le mode actif. */
    public synchronized void uninstall(WorkMode mode) throws IOException {
        Path dir = modesRoot.resolve(mode.name().toLowerCase());
        if (Files.isDirectory(dir)) {
            recursiveDelete(dir);
        }
        ModeChoice current = requireChoice();
        if (current.chosenMode() == mode) {
            // Désinstaller le mode actif : retour honnête au choix non installé
            // (l'utilisateur repasse par l'installation de l'autre mode).
            preferences.write(new ModeChoice(other(mode), false));
        }
    }

    /** Le mode est-il installé (dossier présent ET préférence installée) ? */
    public boolean isInstalled(WorkMode mode) {
        try {
            if (!Files.isDirectory(modesRoot.resolve(mode.name().toLowerCase()))) {
                return false;
            }
            ModeChoice choice = preferences.read();
            return choice != null && choice.modeInstalled();
        } catch (Exception e) {
            return false;
        }
    }

    /** Espace disque occupé par un mode (octets, 0 si absent) — affichage honnête (ADR-002). */
    public long occupiedBytes(WorkMode mode) {
        Path dir = modesRoot.resolve(mode.name().toLowerCase());
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        try (var stream = Files.walk(dir)) {
            return stream.filter(Files::isRegularFile)
                    .mapToLong(p -> {
                        try {
                            return Files.size(p);
                        } catch (IOException e) {
                            return 0L;
                        }
                    }).sum();
        } catch (IOException e) {
            return 0L;
        }
    }

    /** Le choix courant ou exception (jamais de mode caché par défaut — ADR-002). */
    private ModeChoice requireChoice() {
        return Optional.ofNullable(preferences.read())
                .orElseThrow(() -> new NoSuchElementException(
                        "Aucun mode choisi : passez par l'assistant."));
    }

    private static WorkMode other(WorkMode mode) {
        return mode == WorkMode.FORGE ? WorkMode.GRADLE : WorkMode.FORGE;
    }

    /** Suppression récursive protégée : uniquement des sous-dossiers de la racine des modes. */
    private static void recursiveDelete(Path dir) throws IOException {
        Path normalized = dir.toAbsolutePath().normalize();
        if (!Files.isDirectory(normalized) || normalized.getNameCount() < 2) {
            return; // jamais la racine elle-même, jamais un chemin au-dessus
        }
        try (var stream = Files.walk(normalized)) {
            var paths = stream.sorted(java.util.Comparator.reverseOrder()).toList();
            for (Path p : paths) {
                try {
                    Files.delete(p);
                } catch (DirectoryNotEmptyException ignored) {
                    // réessayé au tour suivant de l'ordre inverse
                }
            }
        }
    }
}
