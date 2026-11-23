package jo.joide.index;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import jo.forge.json.Json;
import jo.forge.json.JsonException;
import jo.joide.coremodel.ForgeJson;

/**
 * Le périmètre RÉEL d'un projet Forge (lot L10) : les racines sources viennent
 * du {@code forge.json} (conventions appliquées par le codec — un module sans
 * {@code sources} sert ses conventions), les jars du classpath RÉSOLU par le
 * moteur ({@code build/<module>/resolved/classpath.json}, format v2 de
 * ClasspathFile — chaque entrée pointe un jar, un AAR y ajoute son répertoire
 * explosé dont les {@code libs/*.jar}).
 *
 * <p>Honnête par construction : un projet jamais construit n'a pas de
 * classpath → la phase Bibliothèques scanne zéro jar (l'index sources reste
 * utile) ; un {@code forge.json} illisible → sources vides, jamais d'exception
 * vers l'atelier. Les chemins de jars du moteur sont absolus ; un chemin
 * relatif éventuel est résolu contre la racine du projet.</p>
 */
public final class IndexScope {

    private IndexScope() {}

    /**
     * Le scope d'indexation d'un projet Forge.
     *
     * @param projectRoot racine du projet (celle qui porte forge.json).
     */
    public static IndexService.Scope duProjet(Path projectRoot) {
        return new IndexService.Scope(
                racinesSources(projectRoot), jarsDuClasspathRésolu(projectRoot));
    }

    /**
     * Racines sources réelles de tous les modules ({@code forge.json}) — les
     * chemins déclarés sont **relatifs au répertoire du module** (règle du
     * moteur : {@code <module>/src/main/java}). Illisible : liste vide
     * (l'atelier se dit, l'index ne crashe pas).
     */
    public static List<Path> racinesSources(Path projectRoot) {
        List<Path> racines = new ArrayList<>();
        try {
            ForgeJson.Model modèle = ForgeJson.Model.read(
                    projectRoot.resolve("forge.json"));
            for (ForgeJson.ModuleView module : modèle.modules()) {
                for (String source : module.sources()) {
                    Path racine = projectRoot.resolve(module.name()).resolve(source);
                    if (!racines.contains(racine)) {
                        racines.add(racine);
                    }
                }
            }
        } catch (Exception illisible) {
            // forge.json absent/illisible : sources vides — honnête.
        }
        return List.copyOf(racines);
    }

    /**
     * Jars du classpath résolu : tous les fichiers
     * {@code build/<module>/resolved/classpath.json}
     * (classpaths de compilation — ce que la complétion voit), chaque AAR y
     * contribue par son {@code classes.jar} ET ses {@code libs/*.jar}.
     */
    public static List<Path> jarsDuClasspathRésolu(Path projectRoot) {
        List<Path> jars = new ArrayList<>();
        Path build = projectRoot.resolve("build");
        if (!Files.isDirectory(build)) {
            return List.copyOf(jars); // projet jamais construit : zéro jar.
        }
        try (var modules = Files.list(build)) {
            modules.filter(Files::isDirectory)
                    .map(module -> module.resolve("resolved").resolve("classpath.json"))
                    .filter(Files::isRegularFile)
                    .forEach(fichier -> jars.addAll(jarsDe(fichier, projectRoot)));
        } catch (IOException parcours) {
            // répertoire build illisible : jars déjà trouvés, jamais d'exception.
        }
        return List.copyOf(jars);
    }

    /** Les jars d'un fichier de classpath (clé « compile » + libs des AAR). */
    private static List<Path> jarsDe(Path fichier, Path projectRoot) {
        List<Path> jars = new ArrayList<>();
        try {
            Object racine = Json.parse(Files.readString(fichier));
            if (racine instanceof Map<?, ?> carte
                    && carte.get("compile") instanceof List<?> entrées) {
                for (Object entrée : entrées) {
                    if (entrée instanceof Map<?, ?> champ) {
                        ajouterJar(champ.get("jar"), projectRoot, jars);
                        // AAR explosé : ses libs/*.jar aussi (comme le moteur).
                        Object explosé = champ.get("exploded");
                        if (explosé instanceof String chemin) {
                            ajouterLibs(résoudre(chemin, projectRoot), jars);
                        }
                    }
                }
            }
        } catch (IOException | JsonException illisible) {
            // un classpath illisible est sauté — les autres servent.
        }
        return jars;
    }

    private static void ajouterJar(Object valeur, Path projectRoot, List<Path> jars) {
        if (valeur instanceof String chemin) {
            Path jar = résoudre(chemin, projectRoot);
            if (Files.isRegularFile(jar) && !jars.contains(jar)) {
                jars.add(jar);
            }
        }
    }

    private static void ajouterLibs(Path explosé, List<Path> jars) {
        Path libs = explosé.resolve("libs");
        if (!Files.isDirectory(libs)) {
            return;
        }
        try (var imbriqués = Files.list(libs)) {
            imbriqués.filter(jar -> jar.getFileName().toString().endsWith(".jar"))
                    .sorted()
                    .filter(jar -> !jars.contains(jar))
                    .forEach(jars::add);
        } catch (IOException illisible) {
            // libs absentes des AAR modernes — honnête, rien à ajouter.
        }
    }

    private static Path résoudre(String chemin, Path projectRoot) {
        Path jar = Path.of(chemin);
        return jar.isAbsolute() ? jar : projectRoot.resolve(jar);
    }
}
