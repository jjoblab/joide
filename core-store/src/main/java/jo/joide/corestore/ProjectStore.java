package jo.joide.corestore;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import jo.joide.coremodel.ProjectDetector;

/**
 * Stockage des projets de l'utilisateur : {@code filesDir/projects/} (ADR-010,
 * stockage privé par défaut). La liste de l'accueil est dérivée du contenu réel
 * du dossier (aucun index à désynchroniser) : nom, date de dernière modification,
 * modes compatibles détectés.
 *
 * <p>Un dossier est considéré projet s'il est reconnu par le détecteur (forge.json
 * ou scripts Gradle) — les dossiers de fichiers bruts ne sont pas listés comme
 * projets.</p>
 */
public final class ProjectStore {

    private final Path projectsRoot;

    /** Nouveau stockage sur la racine des projets (appareil : filesDir/projects). */
    public ProjectStore(Path projectsRoot) {
        this.projectsRoot = projectsRoot;
    }

    /** Racine du stockage (tests, diagnostic). */
    public Path root() {
        return projectsRoot;
    }

    /** Fiche d'un projet, telle que l'accueil l'affiche. */
    public record ProjectCard(String name, Path path, long lastModified,
            List<jo.joide.workbench.WorkMode> compatibleModes) {}

    /** Projets reconnus, triés par dernière modification (plus récent en tête). */
    public List<ProjectCard> list() {
        List<ProjectCard> cards = new ArrayList<>();
        if (!Files.isDirectory(projectsRoot)) {
            return List.of();
        }
        try (var stream = Files.list(projectsRoot)) {
            stream.filter(Files::isDirectory)
                    .filter(dir -> !dir.getFileName().toString().startsWith("."))
                    .forEach(dir -> {
                        List<jo.joide.workbench.WorkMode> modes =
                                ProjectDetector.compatibleModes(dir);
                        if (!modes.isEmpty()) {
                            cards.add(new ProjectCard(
                                    dir.getFileName().toString(), dir,
                                    lastModified(dir), modes));
                        }
                    });
        } catch (IOException e) {
            // Répertoire illisible : liste vide + journal (jamais de crash de
            // l'accueil sur un problème de stockage — §4.7).
            return List.of();
        }
        cards.sort(Comparator.comparingLong(ProjectCard::lastModified).reversed());
        return List.copyOf(cards);
    }

    private static long lastModified(Path dir) {
        try {
            return Files.getLastModifiedTime(dir).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    /** Crée un dossier de projet vide (la création depuis modèle remplit ensuite). */
    public Path createProject(String name) throws IOException {
        Path dir = projectsRoot.resolve(safeName(name));
        Files.createDirectories(dir);
        return dir;
    }

    /** Renomme un projet (retourne le nouveau chemin, ou null si introuvable). */
    public Path renameProject(Path project, String newName) throws IOException {
        Path target = projectsRoot.resolve(safeName(newName));
        if (!Files.isDirectory(project) || Files.exists(target)) {
            return null;
        }
        return Files.move(project, target);
    }

    /** Supprime un projet (retourne vrai si quelque chose a été retiré). */
    public boolean deleteProject(Path project) throws IOException {
        if (!Files.isDirectory(project)
                || !project.getParent().equals(projectsRoot)) {
            return false;
        }
        deleteRecursively(project);
        return true;
    }

    private static void deleteRecursively(Path dir) throws IOException {
        try (var stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException e) {
                    // La suppression en profondeur échoue sur le fichier verrouillé :
                    // l'appelant voit l'exception du premier échec (jamais muet).
                    throw new RuntimeException("Suppression de " + p + " : " + e.getMessage(), e);
                }
            });
        }
    }

    /** Nom de dossier sûr (lettres/chiffres/tirets/underscores/points, pas de ..). */
    static String safeName(String name) {
        // Un nom tout-espace devient un underscore : invalide (dossier vide).
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Nom de projet invalide : " + name);
        }
        String cleaned = name.strip().replaceAll("[^\\w.-]", "_");
        if (cleaned.isBlank() || cleaned.equals("..") || cleaned.equals(".")) {
            throw new IllegalArgumentException("Nom de projet invalide : " + name);
        }
        return cleaned;
    }

    /** Lecture utilitaire (modèles, tests) : contenu UTF-8 d'un fichier du projet. */
    public static String readFile(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8);
    }
}
