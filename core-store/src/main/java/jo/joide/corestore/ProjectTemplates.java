package jo.joide.corestore;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Création de projets depuis modèles (conception §5) : les modèles vivent dans
 * les assets de la coquille ({@code templates/}) et sont copiés dans le stockage
 * privé à la création. Chaque modèle est un dossier de fichiers relatifs ; le
 * marqueur de mode est le critère d'acceptation L4 : « création depuis un modèle
 * produit un projet buildable ».
 *
 * <p>Modèles livrés : {@code forge-java} (projet Java pur buildable par Forge —
 * compatible aapt2 non publié, F-02) et {@code gradle-app} (projet Gradle
 * minimal, buildable par le mode Gradle).</p>
 */
public final class ProjectTemplates {

    /** Contenu des modèles (la coquille Android fournit les mêmes fichiers depuis les assets). */
    private final Map<String, Map<String, String>> templates;

    /** Modèles standard (testables sur JVM sans assets Android). */
    public static ProjectTemplates standard() {
        return new ProjectTemplates(Map.of(
                "forge-java", Map.of(
                        "forge.json", """
                                {
                                  "nom": "projet",
                                  "module": "app"
                                }
                                """,
                        "src/Main.java", """
                                public class Main {
                                    public static void main(String[] args) {
                                        System.out.println("Bonjour depuis Forge");
                                    }
                                }
                                """),
                "gradle-app", Map.of(
                        "settings.gradle", """
                                rootProject.name = 'projet'
                                include ':app'
                                """,
                        "build.gradle", """
                                plugins { id 'java' }
                                """,
                        "app/build.gradle", """
                                plugins { id 'java' }
                                repositories { mavenCentral() }
                                """,
                        "app/src/main/java/Main.java", """
                                public class Main {
                                    public static void main(String[] args) {
                                        System.out.println("Bonjour depuis Gradle");
                                    }
                                }
                                """)));
    }

    ProjectTemplates(Map<String, Map<String, String>> templates) {
        this.templates = Map.copyOf(templates);
    }

    /** Noms des modèles disponibles (l'assistant de création affiche cette liste). */
    public List<String> names() {
        return List.copyOf(templates.keySet());
    }

    /**
     * Instancie un modèle dans le stockage.
     *
     * @param store  stockage des projets.
     * @param templateName nom du modèle.
     * @param projectName  nom du projet à créer.
     * @return le dossier du projet créé, buildable (marqueurs présents).
     */
    public Path instantiate(ProjectStore store, String templateName, String projectName)
            throws IOException {
        Map<String, String> files = templates.get(templateName);
        if (files == null) {
            throw new IllegalArgumentException("Modèle inconnu : " + templateName);
        }
        Path project = store.createProject(projectName);
        for (var entry : files.entrySet()) {
            Path target = project.resolve(entry.getKey());
            Files.createDirectories(target.getParent());
            Files.writeString(target, entry.getValue(), StandardCharsets.UTF_8);
        }
        return project;
    }

    /** Contenu d'un fichier de modèle (diagnostic, tests). */
    public String fileContent(String templateName, String relativePath) {
        Map<String, String> files = templates.get(templateName);
        if (files == null) {
            throw new IllegalArgumentException("Modèle inconnu : " + templateName);
        }
        String content = files.get(relativePath);
        if (content == null) {
            throw new IllegalArgumentException(
                    "Fichier absent du modèle " + templateName + " : " + relativePath);
        }
        return content;
    }
}
