package jo.joide.coremodel;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import jo.joide.workbench.ModeTravail;

/**
 * Détection du type d'un projet (cahier des charges §4.5) : un projet Forge est
 * reconnaissable à {@code forge.json}, un projet Gradle aux scripts de build.
 */
public final class DetecteurProjet {

    private static final List<String> MARQUEURS_GRADLE = List.of(
            "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts");

    private DetecteurProjet() {}

    /**
     * Détecte le type du dossier racine.
     *
     * @return FORGE si {@code forge.json} existe, GRADLE si un script de build existe,
     *         null si le dossier n'est reconnu par aucun des deux.
     */
    public static ModeTravail detecter(Path racine) {
        if (Files.isRegularFile(racine.resolve("forge.json"))) {
            return ModeTravail.FORGE;
        }
        for (String marqueur : MARQUEURS_GRADLE) {
            if (Files.isRegularFile(racine.resolve(marqueur))) {
                return ModeTravail.GRADLE;
            }
        }
        return null;
    }

    /** Modes compatibles avec le dossier (les deux si les deux familles de marqueurs). */
    public static List<ModeTravail> modesCompatibles(Path racine) {
        boolean forge = Files.isRegularFile(racine.resolve("forge.json"));
        boolean gradle = MARQUEURS_GRADLE.stream()
                .anyMatch(m -> Files.isRegularFile(racine.resolve(m)));
        if (forge && gradle) return List.of(ModeTravail.FORGE, ModeTravail.GRADLE);
        if (forge) return List.of(ModeTravail.FORGE);
        if (gradle) return List.of(ModeTravail.GRADLE);
        return List.of();
    }
}
