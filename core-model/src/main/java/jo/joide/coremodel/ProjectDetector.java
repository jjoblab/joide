package jo.joide.coremodel;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import jo.joide.workbench.WorkMode;

/**
 * Détection du type d'un projet (cahier des charges §4.5) : un projet Forge est
 * reconnaissable à {@code forge.json}, un projet Gradle aux scripts de build.
 */
public final class ProjectDetector {

    /** Marqueurs d'un projet Gradle (Groovy et Kotlin DSL). */
    private static final List<String> GRADLE_MARKERS = List.of(
            "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts");

    private ProjectDetector() {}

    /**
     * Détecte le type du dossier racine.
     *
     * @return FORGE si {@code forge.json} existe, GRADLE si un script de build existe,
     *         null si le dossier n'est reconnu par aucun des deux.
     */
    public static WorkMode detect(Path root) {
        if (Files.isRegularFile(root.resolve("forge.json"))) {
            return WorkMode.FORGE;
        }
        for (String marker : GRADLE_MARKERS) {
            if (Files.isRegularFile(root.resolve(marker))) {
                return WorkMode.GRADLE;
            }
        }
        return null;
    }

    /** Modes compatibles avec le dossier (les deux si les deux familles de marqueurs). */
    public static List<WorkMode> compatibleModes(Path root) {
        boolean forge = Files.isRegularFile(root.resolve("forge.json"));
        boolean gradle = GRADLE_MARKERS.stream()
                .anyMatch(m -> Files.isRegularFile(root.resolve(m)));
        List<WorkMode> modes = new ArrayList<>();
        if (forge) modes.add(WorkMode.FORGE);
        if (gradle) modes.add(WorkMode.GRADLE);
        return List.copyOf(modes);
    }
}
