package jo.joide.search;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import jo.joide.files.FileTree;

/**
 * « Aller au fichier » (Lot 4 du tiroir) : correspondance **floue** sur le
 * nom (sous-séquence, insensible à la casse), instantanée, bornée et
 * annulable. « mnact » trouve « MainActivity.java ». Classement : ouverts
 * récents d'abord (l'UI les fournit), puis score (départ de mot, suite de
 * caractères), puis proximité (chemin court d'abord). Pur JVM, testé.
 */
public final class FileFinder {

    /** Un candidat : chemin relatif, nom, score (0 = récent sans requête). */
    public record Candidate(String relativePath, String name, int score) {}

    /** Plafond de fichiers parcourus (l'index reste léger). */
    public static final int DEFAULT_MAX_FILES = 5000;

    private FileFinder() {}

    /**
     * Cherche les fichiers dont le nom correspond flouement à la requête.
     *
     * @param root    racine du projet.
     * @param query   requête (vide = seulement les récents, dans l'ordre donné).
     * @param limit   nombre maximal de candidats retournés.
     * @param recents chemins récemment ouverts, du plus récent au plus ancien.
     */
    public static List<Candidate> fuzzy(Path root, String query, int limit,
            List<String> recents, FileTree.Cancelled cancelled)
            throws IOException {
        if (query == null || query.isBlank()) {
            // Sans requête : les récents seuls, jamais de liste fabriquée.
            List<Candidate> out = new ArrayList<>();
            Set<String> seen = new LinkedHashSet<>(recents == null
                    ? List.of() : recents);
            for (String path : seen) {
                if (out.size() >= limit) {
                    break;
                }
                if (Files.isRegularFile(root.resolve(path))) {
                    out.add(new Candidate(path, lastName(path), 0));
                }
            }
            return out;
        }
        String needle = query.toLowerCase(Locale.ROOT);
        List<Candidate> out = new ArrayList<>();
        ArrayDeque<Path> queue = new ArrayDeque<>();
        queue.add(root);
        int seen = 0;
        while (!queue.isEmpty()) {
            if (cancelled != null && cancelled.isCancelled()) {
                break;
            }
            Path dir = queue.poll();
            try (var stream = Files.list(dir)) {
                for (Path child : (Iterable<Path>) stream::iterator) {
                    if (++seen > DEFAULT_MAX_FILES) {
                        return ranked(out, recents, limit); // garde-fou : borné.
                    }
                    String name = child.getFileName().toString();
                    if (Files.isDirectory(child)) {
                        if (!FileTree.isIgnoredDir(name)) {
                            queue.add(child);
                        }
                        continue;
                    }
                    int score = score(name.toLowerCase(Locale.ROOT), needle);
                    if (score > 0) {
                        String relative = root.relativize(child).toString()
                                .replace(java.io.File.separatorChar, '/');
                        out.add(new Candidate(relative, name, score));
                    }
                }
            }
        }
        return ranked(out, recents, limit);
    }

    /** Trie : score décroissant, récents devant à score égal, chemin court devant. */
    private static List<Candidate> ranked(List<Candidate> found,
            List<String> recents, int limit) {
        Set<String> recentSet = new LinkedHashSet<>(recents == null
                ? List.of() : recents);
        found.sort(Comparator
                .comparingInt((Candidate candidate) -> -candidate.score())
                .thenComparing(candidate -> !recentSet.contains(candidate.relativePath()))
                .thenComparing(candidate -> candidate.relativePath().length()));
        return found.size() > limit
                ? new ArrayList<>(found.subList(0, limit)) : found;
    }

    /**
     * Score de sous-séquence : chaque caractère de la requête apparaît dans
     * le nom, dans l'ordre. Bonus départ de mot (après . _ - ou en tête),
     * bonus suite. 0 = ne correspond pas.
     */
    static int score(String name, String needle) {
        int score = 0;
        int nameIndex = 0;
        int previous = -1;
        for (int i = 0; i < needle.length(); i++) {
            char c = needle.charAt(i);
            int found = name.indexOf(c, nameIndex);
            if (found < 0) {
                return 0; // un caractère absent : pas de correspondance.
            }
            score += found == 0 || isSeparator(name.charAt(found - 1)) ? 3 : 1;
            if (previous >= 0 && found == previous + 1) {
                score += 2; // suite de caractères : plus proche de la cible.
            }
            previous = found;
            nameIndex = found + 1;
        }
        return score;
    }

    private static boolean isSeparator(char c) {
        return c == '.' || c == '_' || c == '-' || c == '/' || c == ' ';
    }

    private static String lastName(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }
}
