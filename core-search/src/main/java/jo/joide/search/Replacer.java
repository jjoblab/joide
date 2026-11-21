package jo.joide.search;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import jo.joide.files.FileTree;

/**
 * Remplacement dans le projet (Lot 4 du tiroir) : **aperçu** de chaque
 * occurrence (avant → après, cochable côté UI), application groupée par
 * fichier (écritures temporaires + renommement atomique, jamais de fichier
 * à moitié réécrit) et **instantané** mémoire pour l'annulation « Annuler »
 * de l'UI. Les groupes de capture ($1) sont utilisables en mode regex ;
 * en mode littéral, le remplacement n'est jamais réinterprété.
 */
public final class Replacer {

    /** Un remplacement proposé : fichier, position, avant/après, bornes ligne. */
    public record Replacement(String path, int line, int column,
            String before, String after, int start, int end) {}

    /** Bilan d'application : fichiers touchés, remplacements posés. */
    public record Applied(int files, int replacements) {}

    /** Instantané mémoire des fichiers avant remplacement (annulation). */
    public static final class Snapshot {
        private final Map<String, byte[]> originals;

        private Snapshot(Map<String, byte[]> originals) {
            this.originals = originals;
        }

        /** Chemins photographiés. */
        public List<String> paths() {
            return new ArrayList<>(originals.keySet());
        }
    }

    private Replacer() {}

    /**
     * Aperçu des remplacements (mêmes options que la recherche ; le
     * remplacement développe $1… en mode regex, littéral sinon).
     */
    public static List<Replacement> preview(Path root, String scope,
            ProjectSearch.Options options, String replacement,
            List<String> includeGlobs, List<String> excludeGlobs,
            int maxMatches, FileTree.Cancelled cancelled) throws IOException {
        String literal = replacement == null ? "" : replacement;
        if (options.regex()) {
            return previewRegex(root, scope, options, literal,
                    includeGlobs, excludeGlobs, maxMatches, cancelled);
        }
        List<Replacement> out = new ArrayList<>();
        ProjectSearch.search(root, scope, options, includeGlobs, excludeGlobs,
                maxMatches, cancelled, match -> out.add(new Replacement(
                        match.path(), match.line(), match.column(),
                        match.lineText().substring(match.start(), match.end()),
                        literal, match.start(), match.end())));
        return out;
    }

    /**
     * Applique les remplacements **sélectionnés** : groupés par fichier,
     * posés du bas vers le haut (les bornes restent valides), écriture
     * atomique (temporaire + move).
     */
    public static Applied apply(Path root, List<Replacement> selected,
            FileTree.Cancelled cancelled) throws IOException {
        Map<String, List<Replacement>> byFile = new HashMap<>();
        for (Replacement replacement : selected) {
            byFile.computeIfAbsent(replacement.path(),
                    path -> new ArrayList<>()).add(replacement);
        }
        int replacements = 0;
        for (Map.Entry<String, List<Replacement>> entry : byFile.entrySet()) {
            if (cancelled != null && cancelled.isCancelled()) {
                return new Applied(byFile.size(), replacements);
            }
            replacements += applyInFile(root, entry.getKey(), entry.getValue());
        }
        return new Applied(byFile.size(), replacements);
    }

    /** Photographie les fichiers concernés (octets d'origine, en mémoire). */
    public static Snapshot snapshotOf(Path root, List<String> paths)
            throws IOException {
        Map<String, byte[]> originals = new HashMap<>();
        for (String path : paths) {
            Path file = FileTree.resolve(root, path);
            if (Files.isRegularFile(file)) {
                originals.put(path, Files.readAllBytes(file));
            }
        }
        return new Snapshot(originals);
    }

    /** Restaure un instantané (écriture atomique, un fichier à la fois). */
    public static void restore(Path root, Snapshot snapshot) throws IOException {
        for (Map.Entry<String, byte[]> entry : snapshot.originals.entrySet()) {
            writeAtomically(FileTree.resolve(root, entry.getKey()), entry.getValue());
        }
    }

    // ---- Mécanique interne --------------------------------------------------

    private static int applyInFile(Path root, String relative,
            List<Replacement> replacements) throws IOException {
        Path file = FileTree.resolve(root, relative);
        String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        // Offsets absolus : une passe sur les lignes.
        List<int[]> lineOffsets = lineOffsets(text);
        List<int[]> edits = new ArrayList<>(); // {start, end, ordre d'origine}
        for (int i = 0; i < replacements.size(); i++) {
            Replacement replacement = replacements.get(i);
            int[] offsets = lineOffsets.get(replacement.line() - 1);
            int start = offsets[0] + replacement.start();
            int end = offsets[0] + replacement.end();
            edits.add(new int[] {start, end, i});
        }
        // Du bas vers le haut : les bornes amont restent exactes.
        edits.sort(Comparator.comparingInt((int[] edit) -> edit[0]).reversed());
        StringBuilder builder = new StringBuilder(text);
        for (int[] edit : edits) {
            builder.replace(edit[0], edit[1],
                    replacements.get(edit[2]).after());
        }
        writeAtomically(file, builder.toString().getBytes(StandardCharsets.UTF_8));
        return edits.size();
    }

    /** Départ et fin (sans séparateur) de chaque ligne (1-based index - 1). */
    private static List<int[]> lineOffsets(String text) {
        List<int[]> offsets = new ArrayList<>();
        int lineStart = 0;
        int breakIndex = text.indexOf('\n');
        while (breakIndex >= 0) {
            int lineEnd = breakIndex > 0 && text.charAt(breakIndex - 1) == '\r'
                    ? breakIndex - 1 : breakIndex;
            offsets.add(new int[] {lineStart, lineEnd});
            lineStart = breakIndex + 1;
            breakIndex = text.indexOf('\n', lineStart);
        }
        offsets.add(new int[] {lineStart, text.length()});
        return offsets;
    }

    /** Écriture atomique : temporaire dans le même dossier, puis move. */
    private static void writeAtomically(Path file, byte[] content)
            throws IOException {
        Path temporary = file.resolveSibling(
                file.getFileName().toString() + ".joide-tmp");
        Files.write(temporary, content);
        try {
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Aperçu en mode regex : le remplacement développe $0-$9 (Matcher positionné). */
    static List<Replacement> previewRegex(Path root, String scope,
            ProjectSearch.Options options, String replacement,
            List<String> includeGlobs, List<String> excludeGlobs,
            int maxMatches, FileTree.Cancelled cancelled) throws IOException {
        Pattern pattern = Pattern.compile(options.query(),
                options.caseSensitive() ? 0
                        : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        List<ProjectSearch.Match> matches = new ArrayList<>();
        ProjectSearch.search(root, scope, options, includeGlobs, excludeGlobs,
                maxMatches, cancelled, matches::add);
        List<Replacement> out = new ArrayList<>();
        for (ProjectSearch.Match match : matches) {
            Matcher matcher = pattern.matcher(match.lineText());
            String after = replacement;
            if (matcher.find(match.start()) && matcher.start() == match.start()) {
                after = expandGroups(replacement, matcher);
            }
            out.add(new Replacement(match.path(), match.line(), match.column(),
                    match.lineText().substring(match.start(), match.end()),
                    after, match.start(), match.end()));
        }
        return out;
    }

    /** Développe $0-$9 et \\$ sur un Matcher positionné. */
    static String expandGroups(String replacement, Matcher matcher) {
        StringBuilder builder = new StringBuilder();
        int i = 0;
        while (i < replacement.length()) {
            char c = replacement.charAt(i);
            if (c == '\\' && i + 1 < replacement.length()) {
                builder.append(replacement.charAt(i + 1));
                i += 2;
                continue;
            }
            if (c == '$' && i + 1 < replacement.length()
                    && Character.isDigit(replacement.charAt(i + 1))) {
                int j = i + 1;
                while (j < replacement.length()
                        && Character.isDigit(replacement.charAt(j))) {
                    j++;
                }
                int group = Integer.parseInt(replacement.substring(i + 1, j));
                if (group > matcher.groupCount()) {
                    throw new IllegalArgumentException(
                            "Groupe de capture invalide : $" + group);
                }
                String value = matcher.group(group);
                builder.append(value == null ? "" : value);
                i = j;
                continue;
            }
            builder.append(c);
            i++;
        }
        return builder.toString();
    }
}
