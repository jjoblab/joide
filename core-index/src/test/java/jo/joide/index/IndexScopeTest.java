package jo.joide.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Banc du scope réel d'indexation (L10) : racines sources depuis forge.json
 * (conventions comprises), jars depuis build/&lt;module&gt;/resolved/classpath.json
 * (AAR : classes.jar + libs/*.jar), tolérance honnête (jamais construit,
 * classpath pourri, forge.json absent).
 */
class IndexScopeTest {

    @TempDir
    Path temp;

    @Test
    void racinesSourcesDepuisForgeJson() throws Exception {
        Files.writeString(temp.resolve("forge.json"), """
                {"modules":[
                  {"name":"app","sources":["src/main/java","src/gen/java"]},
                  {"name":"core","language":"kotlin"}]}
                """);
        List<Path> racines = IndexScope.racinesSources(temp);
        assertEquals(3, racines.size()); // les deux de app + la convention kotlin de core
        assertTrue(racines.contains(temp.resolve("app/src/main/java")));
        assertTrue(racines.contains(temp.resolve("app/src/gen/java")));
        assertTrue(racines.contains(temp.resolve("core/src/main/kotlin")));
    }

    @Test
    void jarsDepuisLesFichiersClasspathRésolus() throws Exception {
        Path bibliothèque = jar(temp.resolve("cache").resolve("outils-1.0.jar"));
        Path aarClasses = jar(temp.resolve("cache").resolve("material-1.3.0.jar"));
        Path libAar = jar(temp.resolve("cache").resolve("imbriqué-1.0.jar"));
        Path résolu = temp.resolve("build").resolve("app").resolve("resolved");
        Files.createDirectories(résolu);
        Files.createDirectories(temp.resolve("cache").resolve("explodé").resolve("libs"));
        Files.copy(bibliothèque, temp.resolve("cache").resolve("explodé")
                .resolve("classes.jar"));
        Files.copy(libAar, temp.resolve("cache").resolve("explodé")
                .resolve("libs").resolve("imbriqué-1.0.jar"));
        Files.writeString(résolu.resolve("classpath.json"), """
                {"version":2,"compile":[
                  {"coordinate":"com.exemple:outils:1.0","jar":"%s"},
                  {"coordinate":"androidx.compose:material:1.3.0","jar":"%s",
                   "packaging":"aar","exploded":"%s"}],
                 "runtime":[]}
                """.formatted(bibliothèque, aarClasses,
                        temp.resolve("cache").resolve("explodé")));
        List<Path> jars = IndexScope.jarsDuClasspathRésolu(temp);
        assertEquals(3, jars.size());
        assertTrue(jars.contains(bibliothèque));
        assertTrue(jars.contains(aarClasses));
        // libs/*.jar de l'AAR explosé (le jar pointé par l'entrée est le classes.jar
        // copié dans l'explosé ; les libs sont lus dans l'explosé).
        assertTrue(jars.contains(temp.resolve("cache")
                .resolve("explodé").resolve("libs").resolve("imbriqué-1.0.jar")));
    }

    @Test
    void jamaisConstruitOuIllisibleResteHonnête() throws Exception {
        // Sans build/ : zéro jar, zéro exception.
        assertTrue(IndexScope.jarsDuClasspathRésolu(temp).isEmpty());
        // Un classpath pourri est sauté (pas d'exception, les autres servent).
        Path résolu = temp.resolve("build").resolve("app").resolve("resolved");
        Files.createDirectories(résolu);
        Files.writeString(résolu.resolve("classpath.json"), "{ pas du json");
        assertTrue(IndexScope.jarsDuClasspathRésolu(temp).isEmpty());
        // forge.json absent : sources vides.
        assertTrue(IndexScope.racinesSources(temp).isEmpty());
    }

    /** Un petit jar réel (une entrée) pour le test. */
    private static Path jar(Path cible) throws Exception {
        Files.createDirectories(cible.getParent());
        try (ZipOutputStream zip = new ZipOutputStream(
                Files.newOutputStream(cible))) {
            zip.putNextEntry(new ZipEntry("com/exemple/Exemple.class"));
            zip.write(new byte[] {1, 2, 3});
            zip.closeEntry();
        }
        return cible;
    }
}
