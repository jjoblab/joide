package jo.joide.ai;

import java.util.ArrayList;
import java.util.List;

/**
 * Diff unifié réel (Lot 3) : l'aperçu d'approbation montrait les 400 premiers
 * caractères du fichier AVANT — ce n'était pas un diff. Ici : diff par lignes
 * (LCS simple, borné), en-têtes {@code @@} avec numéros, compteurs +N −M
 * exacts. Pur, testé.
 */
public final class DiffBuilder {

    /** Plafond de lignes par fichier : au-delà, le diff se refuse (garde-fou). */
    public static final int MAX_LINES = 4_000;

    private DiffBuilder() {}

    /** Résultat d'un diff : compteurs exacts + texte unifié. */
    public record Diff(int added, int removed, String unified) {

        /** Résumé « +N −M » (compteurs réels, jamais inventés). */
        public String counters() {
            return "+" + added + " −" + removed;
        }
    }

    /**
     * Diff unifié avant/après. Retourne null si le fichier dépasse le
     * plafond (l'appelant le dit honnêtement).
     */
    public static Diff unified(String before, String after) {
        List<String> oldLines = lines(before);
        List<String> newLines = lines(after);
        if (oldLines.size() > MAX_LINES || newLines.size() > MAX_LINES) {
            return null;
        }
        List<int[]> matches = lcs(oldLines, newLines);
        StringBuilder out = new StringBuilder();
        int[] counters = {0, 0};
        int oldAt = 0;
        int newAt = 0;
        for (int[] match : matches) {
            emitChange(out, oldLines, newLines, oldAt, match[0], newAt, match[1], counters);
            // Ligne de contexte commune.
            out.append(' ').append(oldLines.get(match[0])).append('\n');
            oldAt = match[0] + 1;
            newAt = match[1] + 1;
        }
        emitChange(out, oldLines, newLines, oldAt, oldLines.size(), newAt,
                newLines.size(), counters);
        return new Diff(counters[0], counters[1], out.toString());
    }

    /** Émet une plage de changements avec son en-tête @@ si non vide. */
    private static void emitChange(StringBuilder out, List<String> oldLines,
            List<String> newLines, int oldFrom, int oldTo, int newFrom, int newTo,
            int[] counters) {
        if (oldFrom == oldTo && newFrom == newTo) {
            return;
        }
        out.append("@@ -").append(oldFrom + 1).append(',').append(oldTo - oldFrom)
                .append(" +").append(newFrom + 1).append(',').append(newTo - newFrom)
                .append(" @@\n");
        for (int i = oldFrom; i < oldTo; i++) {
            out.append('-').append(oldLines.get(i)).append('\n');
            counters[1]++;
        }
        for (int j = newFrom; j < newTo; j++) {
            out.append('+').append(newLines.get(j)).append('\n');
            counters[0]++;
        }
    }

    /**
     * Plus longue sous-séquence commune (dynamic programming borné) : paires
     * (index ancien, index nouveau) des lignes inchangées.
     */
    private static List<int[]> lcs(List<String> oldLines, List<String> newLines) {
        int n = oldLines.size();
        int m = newLines.size();
        int[][] table = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                table[i][j] = oldLines.get(i).equals(newLines.get(j))
                        ? table[i + 1][j + 1] + 1
                        : Math.max(table[i + 1][j], table[i][j + 1]);
            }
        }
        List<int[]> matches = new ArrayList<>();
        int i = 0;
        int j = 0;
        while (i < n && j < m) {
            if (oldLines.get(i).equals(newLines.get(j))) {
                matches.add(new int[] {i, j});
                i++;
                j++;
            } else if (table[i + 1][j] >= table[i][j + 1]) {
                i++;
            } else {
                j++;
            }
        }
        return matches;
    }

    private static List<String> lines(String text) {
        List<String> lines = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return lines;
        }
        int start = 0;
        for (int at = 0; at < text.length(); at++) {
            if (text.charAt(at) == '\n') {
                lines.add(text.substring(start, at));
                start = at + 1;
            }
        }
        lines.add(text.substring(start));
        return lines;
    }
}
