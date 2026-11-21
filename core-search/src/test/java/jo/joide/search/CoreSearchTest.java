package jo.joide.search;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lot 4 du tiroir : recherche dans le projet — littéral protégé, regex,
 * casse, mot entier, portée, globs, bornes, annulation, binaires sautés ;
 * remplacement avec aperçu, groupes $1, application atomique et
 * instantané d'annulation ; correspondance floue pour « aller au fichier ».
 */
class CoreSearchTest {

    @TempDir
    Path temp;

    private Path project() throws IOException {
        Path root = temp.resolve("projet");
        Files.createDirectories(root.resolve("app/src/main/java"));
        Files.write(root.resolve("app/src/main/java/Main.java"),
                ("public class Main {\n"
                        + "  Object o = Objects.requireNonNull(name, \"name\");\n"
                        + "  Object p = Objects.requireNonNull(view);\n"
                        + "}\n").getBytes());
        Files.write(root.resolve("app/src/main/java/Util.java"),
                ("interface Util {\n"
                        + "  int requireNonNullCounter = 0;\n"
                        + "}\n").getBytes());
        Files.write(root.resolve("notes.md"), "# requireNonNull notes".getBytes());
        Files.createDirectories(root.resolve("build/generated"));
        Files.write(root.resolve("build/generated/Out.java"),
                "requireNonNull build".getBytes());
        Files.write(root.resolve("image.bin"), new byte[] {0, 1, 0, 2});
        return root;
    }

    // ---- ProjectSearch -------------------------------------------------------

    @Test
    void literalSearchFindsAllOccurrences() throws IOException {
        List<ProjectSearch.Match> matches = new java.util.ArrayList<>();
        ProjectSearch.Result result = ProjectSearch.search(project(), ".",
                new ProjectSearch.Options("requireNonNull", false, true, false),
                null, null, 100, null, matches::add);
        // Main.java : 2 ; Util.java : 1 (requireNonNullCounter) ; notes.md : 1 ;
        // build ignoré ; image.bin binaire.
        assertEquals(4, result.matches());
        assertEquals(3, result.files());
        assertFalse(result.capped());
        ProjectSearch.Match first = matches.get(0);
        assertEquals("app/src/main/java/Main.java", first.path());
        assertEquals(2, first.line());
        assertTrue(first.lineText().contains("requireNonNull(name"));
    }

    @Test
    void caseInsensitiveAndWholeWord() throws IOException {
        Path root = project();
        List<ProjectSearch.Match> all = new java.util.ArrayList<>();
        ProjectSearch.search(root, ".",
                new ProjectSearch.Options("REQUIRENONNULL", false, false, false),
                null, null, 100, null, all::add);
        assertEquals(4, all.size(), "insensible à la casse");
        List<ProjectSearch.Match> words = new java.util.ArrayList<>();
        ProjectSearch.search(root, ".",
                new ProjectSearch.Options("requireNonNull", false, true, true),
                null, null, 100, null, words::add);
        // requireNonNullCounter ne compte pas (mot entier).
        assertEquals(3, words.size());
    }

    @Test
    void regexSearchWithGroups() throws IOException {
        List<ProjectSearch.Match> matches = new java.util.ArrayList<>();
        ProjectSearch.Result result = ProjectSearch.search(project(), ".",
                new ProjectSearch.Options("requireNonNull\\((\\w+)", true, true, false),
                null, null, 100, null, matches::add);
        assertEquals(2, result.matches());
        assertTrue(matches.get(0).lineText().contains("name"));
        assertTrue(matches.get(1).lineText().contains("view"));
    }

    @Test
    void invalidRegexSurfacesEngineMessage() throws IOException {
        assertThrows(java.util.regex.PatternSyntaxException.class,
                () -> ProjectSearch.search(project(), ".",
                        new ProjectSearch.Options("getText(", true, true, false),
                        null, null, 100, null, m -> { }));
    }

    @Test
    void scopeAndGlobs() throws IOException {
        Path root = project();
        List<ProjectSearch.Match> scoped = new java.util.ArrayList<>();
        ProjectSearch.search(root, "app/src/main/java",
                new ProjectSearch.Options("requireNonNull", false, true, false),
                null, null, 100, null, scoped::add);
        assertEquals(3, scoped.size(), "portée : seulement app/src/main/java");
        List<ProjectSearch.Match> globbed = new java.util.ArrayList<>();
        ProjectSearch.search(root, ".",
                new ProjectSearch.Options("requireNonNull", false, true, false),
                List.of("**/*.md"), null, 100, null, globbed::add);
        assertEquals(1, globbed.size());
        assertEquals("notes.md", globbed.get(0).path());
        List<ProjectSearch.Match> excluded = new java.util.ArrayList<>();
        ProjectSearch.search(root, ".",
                new ProjectSearch.Options("requireNonNull", false, true, false),
                null, List.of("**/*.md"), 100, null, excluded::add);
        assertEquals(3, excluded.size(), "notes.md exclu");
    }

    @Test
    void cappedAndCanceled() throws IOException {
        Path root = project();
        ProjectSearch.Result capped = ProjectSearch.search(root, ".",
                new ProjectSearch.Options("e", false, true, false),
                null, null, 5, null, m -> { });
        assertTrue(capped.capped());
        assertEquals(5, capped.matches());
        ProjectSearch.Result canceled = ProjectSearch.search(root, ".",
                new ProjectSearch.Options("requireNonNull", false, true, false),
                null, null, 100, () -> true, m -> { });
        assertTrue(canceled.capped());
    }

    // ---- Replacer ------------------------------------------------------------

    @Test
    void previewAndApplyLiteral() throws IOException {
        Path root = project();
        List<Replacer.Replacement> preview = Replacer.preview(root, ".",
                new ProjectSearch.Options("name", false, true, false),
                "label", null, null, 100, null);
        assertEquals(2, preview.size(), "name deux fois dans Main.java");
        Replacer.Snapshot snapshot = Replacer.snapshotOf(root,
                List.of("app/src/main/java/Main.java"));
        Replacer.Applied applied = Replacer.apply(root, preview, null);
        assertEquals(1, applied.files());
        assertEquals(2, applied.replacements());
        String after = new String(Files.readAllBytes(
                root.resolve("app/src/main/java/Main.java")));
        assertTrue(after.contains("requireNonNull(label, \"label\")"));
        // Annulation : l'instantané rend le fichier d'origine.
        Replacer.restore(root, snapshot);
        assertTrue(new String(Files.readAllBytes(
                root.resolve("app/src/main/java/Main.java")))
                .contains("requireNonNull(name, \"name\")"));
    }

    @Test
    void regexReplacementUsesCaptureGroups() throws IOException {
        Path root = project();
        List<Replacer.Replacement> preview = Replacer.preview(root, ".",
                new ProjectSearch.Options("requireNonNull\\((\\w+)", true, true, false),
                "check[$1]", null, null, 100, null);
        assertEquals(2, preview.size());
        assertEquals("check[name]", preview.get(0).after());
        assertEquals("check[view]", preview.get(1).after());
        Replacer.apply(root, preview, null);
        String after = new String(Files.readAllBytes(
                root.resolve("app/src/main/java/Main.java")));
        assertTrue(after.contains("check[name]"));
        assertTrue(after.contains("check[view]"));
    }

    @Test
    void invalidGroupIsAClearError() throws IOException {
        Path root = project();
        // Un groupe inconnu ($3 avec un seul groupe capturant) se dit dès
        // l'aperçu, en mode regex — jamais de remplacement silencieux.
        assertThrows(IllegalArgumentException.class, () -> Replacer.preview(
                root, ".",
                new ProjectSearch.Options("requireNonNull\\((\\w+)", true, true, false),
                "$3", null, null, 100, null));
    }

    // ---- FileFinder ----------------------------------------------------------

    @Test
    void fuzzyFindsBySubsequenceRanked() throws IOException {
        Path root = project();
        List<FileFinder.Candidate> found = FileFinder.fuzzy(root, "mjav", 10,
                List.of(), null);
        assertFalse(found.isEmpty());
        assertEquals("Main.java", found.get(0).name(),
                "mjav → Main.java est le meilleur (sous-séquence)");
        List<FileFinder.Candidate> exact = FileFinder.fuzzy(root, "notes", 10,
                List.of(), null);
        assertEquals("notes.md", exact.get(0).name());
    }

    @Test
    void recentsFirstAndWithoutQuery() throws IOException {
        Path root = project();
        List<FileFinder.Candidate> recents = FileFinder.fuzzy(root, "", 10,
                List.of("notes.md", "app/src/main/java/Util.java"), null);
        assertEquals(2, recents.size());
        assertEquals("notes.md", recents.get(0).name());
        // Avec requête, les récents passent devant à score égal.
        List<FileFinder.Candidate> tied = FileFinder.fuzzy(root, "java", 10,
                List.of("notes.md"), null);
        assertFalse(tied.isEmpty());
    }
}
