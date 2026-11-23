package jo.joide.lsp;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import jo.codeide.tooling.protocol.ClasspathEntry;
import jo.codeide.tooling.protocol.ClasspathKind;
import jo.codeide.tooling.protocol.ClasspathModule;
import jo.codeide.tooling.protocol.ClasspathResult;
import jo.forge.json.Json;
import jo.forge.json.JsonException;
import jo.joide.coremodel.ForgeJson;

/**
 * La traduction du périmètre RÉEL d'un projet Forge vers le
 * {@link ClasspathResult} du protocole tooling (v8) que l'hôte LSP
 * (jo.codeide.lsp.host, brique code-lsp) consomme à chaque sync.
 *
 * <p>Même honnêteté que {@link jo.joide.index.IndexScope} (L10) : les
 * racines sources viennent du {@code forge.json} (conventions du codec),
 * les entrées du classpath des fichiers
 * {@code build/<module>/resolved/classpath.json} écrits par la résolution
 * du moteur Forge (format v2 — chaque AAR y contribue son
 * {@code classes.jar} et son répertoire {@code exploded}). Un projet
 * jamais construit n'a pas de classpath résolu : les entrées sont vides,
 * le module reste déclaré avec ses sources et son {@code androidJar} —
 * l'index des bibliothèques scanne alors la seule plateforme, comme la
 * référence scanne le SDK avant la première sync Gradle.</p>
 */
public final class ForgeClasspath {

    /** Version du protocole tooling attendue par l'hôte LSP (notation v8). */
    private static final int VERSION_PROTOCOLE = 8;

    private ForgeClasspath() {}

    /**
     * Le ClasspathResult d'un projet Forge.
     *
     * @param projectRoot la racine du projet (celle qui porte forge.json).
     * @param androidJar  le jar de la plateforme Android du projet
     *                    (découvert côté hôte — moteur Forge ou SDK local),
     *                    null si absent : honnête, le module vit sans.
     * @return le résultat, toujours construit — un forge.json illisible
     *         donne un résultat sans module, jamais d'exception.
     */
    public static ClasspathResult of(Path projectRoot, Path androidJar) {
        List<ClasspathModule> modules = new ArrayList<>();
        try {
            ForgeJson.Model modèle = ForgeJson.Model.read(
                    projectRoot.resolve("forge.json"));
            for (ForgeJson.ModuleView module : modèle.modules()) {
                modules.add(moduleDe(projectRoot, module, androidJar));
            }
        } catch (Exception illisible) {
            // forge.json absent/illisible : résultat vide — l'atelier se dit,
            // l'hôte LSP ne crashe pas (même règle que IndexScope).
        }
        return new ClasspathResult(
                "joide",
                VERSION_PROTOCOLE,
                projectRoot.toString(),
                modules);
    }

    /** Un module : ses racines sources, ses entrées résolues, sa plateforme. */
    private static ClasspathModule moduleDe(Path projectRoot,
            ForgeJson.ModuleView module, Path androidJar) {
        List<String> sourceDirs = new ArrayList<>();
        for (String source : module.sources()) {
            sourceDirs.add(projectRoot.resolve(module.name()).resolve(source)
                    .toString());
        }
        List<ClasspathEntry> entrées = new ArrayList<>();
        int nbJars = 0;
        int nbAars = 0;
        Path classpath = projectRoot.resolve("build").resolve(module.name())
                .resolve("resolved").resolve("classpath.json");
        if (Files.isRegularFile(classpath)) {
            for (EntréeRésolue entrée : lireClasspath(classpath, projectRoot)) {
                entrées.add(new ClasspathEntry(entrée.jar().toString(),
                        entrée.aar() ? ClasspathKind.AAR : ClasspathKind.JAR,
                        "compile", null, null));
                if (entrée.aar()) {
                    nbAars++;
                } else {
                    nbJars++;
                }
            }
        }
        return new ClasspathModule(
                module.name(),
                sourceDirs,
                entrées,
                List.of(),
                nbJars,
                nbAars,
                sourceDirs.size(),
                null,
                0,
                List.of(),
                androidJar != null ? androidJar.toString() : null,
                false,
                null,
                List.of(),
                null,
                null);
    }

    /** Une entrée du classpath.json résolu (format v2 du moteur Forge). */
    private record EntréeRésolue(Path jar, boolean aar) {}

    /**
     * Les entrées de compilation du classpath résolu — champ
     * {@code compile}, chaque entrée portant son {@code packaging}
     * ({@code aar} depuis la v2 du format, {@code jar} par défaut).
     */
    private static List<EntréeRésolue> lireClasspath(Path fichier,
            Path projectRoot) {
        List<EntréeRésolue> entrées = new ArrayList<>();
        try {
            Object racine = Json.parse(Files.readString(fichier));
            if (racine instanceof Map<?, ?> carte
                    && carte.get("compile") instanceof List<?> liste) {
                for (Object élément : liste) {
                    if (élément instanceof Map<?, ?> champ
                            && champ.get("jar") instanceof String jar) {
                        String packaging = champ.get("packaging")
                                instanceof String p ? p : "jar";
                        entrées.add(new EntréeRésolue(
                                résoudre(jar, projectRoot),
                                "aar".equals(packaging)));
                    }
                }
            }
        } catch (IOException | JsonException illisible) {
            // classpath illisible : entrées déjà lues — honnête, jamais d'échec.
        }
        return entrées;
    }

    private static Path résoudre(String chemin, Path projectRoot) {
        Path jar = Path.of(chemin);
        return jar.isAbsolute() ? jar : projectRoot.resolve(jar);
    }
}
