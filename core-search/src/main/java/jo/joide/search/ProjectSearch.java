package jo.joide.search;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CodingErrorAction;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.FileSystems;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import jo.joide.files.FileTree;

/**
 * Recherche dans le projet (Lot 4 du tiroir) : texte littéral ou expression
 * régulière, casse, mot entier, portée (dossier), globs d'inclusion et
 * d'exclusion. Le parcours est **borné** (fichiers et correspondances), le
 * flux est **poussé au fur et à mesure** (compteur en direct côté UI) et
 * **annulable** à tout instant. Les fichiers générés/VCS sont ignorés, les
 * binaires sautés honnêtement (sonde d'octet nul).
 *
 * <p>Pur JVM, testé ; une expression régulière invalide remonte
 * {@link PatternSyntaxException} — le message du moteur s'affichera tel
 * quel, jamais de crash.</p>
 */
public final class ProjectSearch {

    /** Options de recherche (le littéral est protégé : jamais réinterprété). */
    public record Options(String query, boolean regex, boolean caseSensitive,
            boolean wholeWord) {}

    /** Une correspondance : position 1-based dans le fichier, bornes dans la ligne. */
    public record Match(String path, int line, int column, String lineText,
            int start, int end) {}

    /** Consommateur de correspondances (appelé sur le fil de la recherche). */
    public interface MatchSink {
        void accept(Match match);
    }

    /** Bilan : fichiers touchés, correspondances, plafond atteint (dit). */
    public record Result(int files, int matches, boolean capped) {}

    /** Plafond de correspondances (garde-fou des très gros projets). */
    public static final int DEFAULT_MAX_MATCHES = 2000;

    /** Plafond de fichiers parcourus. */
    public static final int DEFAULT_MAX_FILES = 5000;

    /** Au-delà, un fichier n'est pas lu (binaire ou trop gros). */
    public static final long MAX_FILE_BYTES = 2L * 1024 * 1024;

    /** Sonde de binarité : au plus 8 Kio lus. */
    private static final int BINARY_PROBE_BYTES = 8 * 1024;

    private ProjectSearch() {}

    /**
     * Cherche dans le projet.
     *
     * @param root   racine du projet.
     * @param scope  dossier relatif de recherche (« . » = tout le projet).
     * @param options requête et modes (littéral protégé, ou regex).
     * @param includeGlobs  globs d'inclusion (chemins relatifs), vide = tout.
     * @param excludeGlobs  globs d'exclusion (chemins relatifs).
     * @param maxMatches plafond de correspondances.
     * @param cancelled vérification d'annulation (par ligne et par fichier).
     * @param sink   consommateur poussé au fil de la découverte.
     */
    public static Result search(Path root, String scope, Options options,
            List<String> includeGlobs, List<String> excludeGlobs,
            int maxMatches, FileTree.Cancelled cancelled, MatchSink sink)
            throws IOException {
        if (options == null || options.query() == null || options.query().isEmpty()) {
            return new Result(0, 0, false);
        }
        Pattern pattern = compile(options);
        List<PathMatcher> include = matchers(includeGlobs);
        List<PathMatcher> exclude = matchers(excludeGlobs);
        Path scopeDir = FileTree.resolve(root, scope == null || scope.isEmpty()
                ? "." : scope);
        if (!Files.isDirectory(scopeDir)) {
            throw new IOException("Dossier introuvable : " + scope);
        }
        List<String> files = collectFiles(root, scopeDir, include, exclude,
                cancelled);
        if (cancelled != null && cancelled.isCancelled()) {
            return new Result(0, 0, true); // annulé : incomplet, jamais « rien ».
        }
        int filesWithMatches = 0;
        int matches = 0;
        boolean capped = false;
        for (String relative : files) {
            if (cancelled != null && cancelled.isCancelled()) {
                return new Result(filesWithMatches, matches, true);
            }
            List<Match> inFile = searchFile(root, relative, pattern,
                    options.wholeWord(), maxMatches - matches);
            if (!inFile.isEmpty()) {
                filesWithMatches++;
                for (Match match : inFile) {
                    sink.accept(match);
                }
                matches += inFile.size();
                if (matches >= maxMatches) {
                    capped = true;
                    break; // plafond atteint : dit honnêtement, jamais caché.
                }
            }
        }
        return new Result(filesWithMatches, matches, capped);
    }

    /** Compile la requête : littéral protégé, ou regex (erreur du moteur remontée). */
    private static Pattern compile(Options options) {
        int flags = options.caseSensitive() ? 0
                : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
        return options.regex()
                ? Pattern.compile(options.query(), flags)
                : Pattern.compile(Pattern.quote(options.query()), flags);
    }

    private static List<PathMatcher> matchers(List<String> globs) {
        List<PathMatcher> out = new ArrayList<>();
        if (globs == null) {
            return out;
        }
        for (String glob : globs) {
            if (glob == null || glob.isEmpty()) {
                continue;
            }
            out.add(FileSystems.getDefault().getPathMatcher("glob:" + glob));
            // Convention d'usage : « **/*.x » doit aussi attraper les
            // fichiers à la racine (le glob Java exige un séparateur).
            if (glob.startsWith("**/")) {
                out.add(FileSystems.getDefault().getPathMatcher(
                        "glob:" + glob.substring(3)));
            }
        }
        return out;
    }

    /** Parcours en largeur borné, dossiers ignorés exclus. */
    private static List<String> collectFiles(Path root, Path scopeDir,
            List<PathMatcher> include, List<PathMatcher> exclude,
            FileTree.Cancelled cancelled) throws IOException {
        List<String> files = new ArrayList<>();
        ArrayDeque<Path> queue = new ArrayDeque<>();
        queue.add(scopeDir);
        while (!queue.isEmpty()) {
            if (cancelled != null && cancelled.isCancelled()) {
                break;
            }
            Path dir = queue.poll();
            try (var stream = Files.list(dir)) {
                for (Path child : (Iterable<Path>) stream::iterator) {
                    if (files.size() >= DEFAULT_MAX_FILES) {
                        return files; // garde-fou : le parcours s'arrête, dit.
                    }
                    String name = child.getFileName().toString();
                    if (Files.isDirectory(child)) {
                        if (!FileTree.isIgnoredDir(name)) {
                            queue.add(child);
                        }
                        continue;
                    }
                    String relative = root.relativize(child).toString()
                            .replace(java.io.File.separatorChar, '/');
                    if (!include.isEmpty() && !anyMatch(include, relative)) {
                        continue;
                    }
                    if (anyMatch(exclude, relative)) {
                        continue;
                    }
                    files.add(relative);
                }
            }
        }
        files.sort(String::compareTo);
        return files;
    }

    private static boolean anyMatch(List<PathMatcher> matchers, String relative) {
        for (PathMatcher matcher : matchers) {
            if (matcher.matches(Path.of(relative))) {
                return true;
            }
        }
        return false;
    }

    /** Cherche dans un fichier (null de binaire/trop gros, jamais d'erreur muette). */
    private static List<Match> searchFile(Path root, String relative,
            Pattern pattern, boolean wholeWord, int budget) throws IOException {
        Path file = root.resolve(relative);
        List<Match> out = new ArrayList<>();
        if (Files.size(file) > MAX_FILE_BYTES || isBinary(file)) {
            return out;
        }
        String text = readText(file);
        Matcher matcher = pattern.matcher(text);
        int line = 1;
        int lineStart = 0;
        int nextBreak = text.indexOf('\n');
        while (matcher.find()) {
            while (lineStart >= 0 && matcher.start() >= (nextBreak < 0
                    ? text.length() + 1 : nextBreak)) {
                lineStart = nextBreak + 1;
                line++;
                nextBreak = text.indexOf('\n', lineStart);
            }
            if (wholeWord && !wordBounded(text, matcher.start(), matcher.end())) {
                continue;
            }
            int lineEnd = nextBreak < 0 ? text.length()
                    : (text.charAt(nextBreak - 1) == '\r' ? nextBreak - 1 : nextBreak);
            String lineText = text.substring(lineStart, lineEnd);
            int start = matcher.start() - lineStart;
            out.add(new Match(relative, line, start + 1, lineText,
                    start, start + (matcher.end() - matcher.start())));
            if (out.size() >= Math.max(1, budget)) {
                break;
            }
        }
        return out;
    }

    /** Bords de mot : pas d'identifiant Java collé avant ni après. */
    private static boolean wordBounded(String text, int start, int end) {
        boolean before = start == 0
                || !Character.isJavaIdentifierPart(text.charAt(start - 1));
        boolean after = end >= text.length()
                || !Character.isJavaIdentifierPart(text.charAt(end));
        return before && after;
    }

    /** Sonde de binarité : un octet nul dans les 8 Kio → binaire. */
    private static boolean isBinary(Path file) throws IOException {
        byte[] probe = new byte[BINARY_PROBE_BYTES];
        try (var in = Files.newInputStream(file)) {
            int read = in.readNBytes(probe, 0, probe.length);
            for (int i = 0; i < read; i++) {
                if (probe[i] == 0) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Lecture UTF-8 tolérante (octets invalides remplacés, jamais de crash ;
     *  Files.readString est proscrit : API 33 sans désucrage, ADR-009). */
    private static String readText(Path file) throws IOException {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }
}
