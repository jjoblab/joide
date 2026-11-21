package jo.joide.ai.markdown;

import java.util.ArrayList;
import java.util.List;

/**
 * Parseur Markdown du module ai (Lot 5) : **streaming-safe** — conçu pour
 * rendre un flux en cours de génération. Pour TOUT préfixe d'un document
 * (fence non fermée, emphase ouverte, tableau incomplet), {@link #parse}
 * ne lève jamais d'exception et rend un arbre provisoire correct : le
 * résultat final est identique quel que soit le découpage en deltas
 * (propriété testée sur préfixes).
 *
 * <p>Blocs reconnus : titres, paragraphes, listes (à puces, ordonnées, cases
 * à cocher, un niveau d'imbrication), citations, séparateurs, blocs de code
 * clôturés (langage + attribut {@code title="chemin"}), tableaux, et le bloc
 * spécial {@code suivi}. Le gras/italique/barré/code en ligne restent dans
 * le texte des blocs : le rendu en spans vit côté UI (un seul chemin de
 * rendu partagé Assistant/Agent).</p>
 */
public final class MarkdownParser {

    private MarkdownParser() {}

    /** Un bloc du document. */
    public sealed interface Block {

        /** Titre (niveau 1-6). */
        record Heading(int level, String text) implements Block {}

        /** Paragraphe (texte brut, styles en ligne non développés). */
        record Paragraph(String text) implements Block {}

        /** Élément de liste (niveau 0 ou 1 d'imbrication, coché pour les cases). */
        record ListItem(int level, boolean ordered, boolean checkbox,
                boolean checked, String text) implements Block {}

        /** Citation (chaque ligne citée). */
        record Quote(String text) implements Block {}

        /** Séparateur horizontal. */
        record Separator() implements Block {}

        /**
         * Bloc de code. {@code closed} = faux quand la fence est encore
         * ouverte (flux en cours) : le rendu provisoire reste correct.
         */
        record CodeBlock(String language, String title, String code, boolean closed)
                implements Block {}

        /** Tableau (première ligne = en-têtes ; rangées suivantes = cellules). */
        record Table(List<String> headers, List<List<String>> rows) implements Block {}
    }

    /** Analyse un document (complet ou préfixe de flux) : jamais d'exception. */
    public static List<Block> parse(String document) {
        List<Block> blocks = new ArrayList<>();
        if (document == null || document.isEmpty()) {
            return blocks;
        }
        String[] lines = document.split("\n", -1);
        int i = 0;
        while (i < lines.length) {
            String line = lines[i];
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                i++;
                continue;
            }
            // Fence ouvrante : bloc de code (jusqu'à la fence fermante, ou fin).
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                String fence = trimmed.substring(0, 3);
                String info = trimmed.substring(3).trim();
                int end = -1;
                for (int j = i + 1; j < lines.length; j++) {
                    // Fence fermante : la ligne EST la fence, rien d'autre.
                    if (lines[j].trim().equals(fence)) {
                        end = j;
                        break;
                    }
                }
                boolean closed = end >= 0;
                List<String> codeLines = new ArrayList<>();
                int stop = closed ? end : lines.length;
                for (int j = i + 1; j < stop; j++) {
                    codeLines.add(lines[j]);
                }
                blocks.add(new Block.CodeBlock(languageOf(info), titleOf(info),
                        join(codeLines), closed));
                i = closed ? end + 1 : lines.length;
                continue;
            }
            // Titre.
            if (trimmed.startsWith("#")) {
                int level = 0;
                while (level < trimmed.length() && trimmed.charAt(level) == '#'
                        && level < 6) {
                    level++;
                }
                if (level < trimmed.length() && trimmed.charAt(level) == ' ') {
                    blocks.add(new Block.Heading(level,
                            trimmed.substring(level + 1).trim()));
                    i++;
                    continue;
                }
            }
            // Séparateur.
            if (trimmed.equals("---") || trimmed.equals("***") || trimmed.equals("___")) {
                blocks.add(new Block.Separator());
                i++;
                continue;
            }
            // Citation.
            if (trimmed.startsWith(">")) {
                StringBuilder quote = new StringBuilder(trimmed.substring(1).trim());
                int j = i + 1;
                while (j < lines.length && lines[j].trim().startsWith(">")) {
                    quote.append('\n').append(lines[j].trim().substring(1).trim());
                    j++;
                }
                blocks.add(new Block.Quote(quote.toString()));
                i = j;
                continue;
            }
            // Élément de liste (puce, ordonné, case à cocher), avec imbrication.
            Block.ListItem parsed = listItemOf(line);
            if (parsed != null) {
                blocks.add(parsed);
                i++;
                continue;
            }
            // Tableau : ligne | … | suivie d'un séparateur |---|---|.
            if (trimmed.startsWith("|") && i + 1 < lines.length
                    && lines[i + 1].trim().matches("\\|?\\s*:?-{2,}.*")) {
                List<String> headers = cellsOf(trimmed);
                i += 2;
                List<List<String>> rows = new ArrayList<>();
                while (i < lines.length && lines[i].trim().startsWith("|")) {
                    rows.add(cellsOf(lines[i].trim()));
                    i++;
                }
                // Tableau incomplet (flux) : les rangées déjà reçues se rendent.
                blocks.add(new Block.Table(headers, rows));
                continue;
            }
            // Paragraphe : lignes consécutives non vides jointes.
            StringBuilder paragraph = new StringBuilder(trimmed);
            int j = i + 1;
            while (j < lines.length && !lines[j].trim().isEmpty()
                    && !lines[j].trim().startsWith("```")
                    && !lines[j].trim().startsWith("#")
                    && !lines[j].trim().startsWith(">")
                    && listItemOf(lines[j]) == null) {
                paragraph.append(' ').append(lines[j].trim());
                j++;
            }
            blocks.add(new Block.Paragraph(paragraph.toString()));
            i = j;
        }
        return blocks;
    }

    /** Langage depuis l'info de fence (```java title="x" → java). */
    static String languageOf(String info) {
        if (info == null || info.isEmpty()) {
            return "";
        }
        int space = info.indexOf(' ');
        return space < 0 ? info : info.substring(0, space);
    }

    /** Attribut title="…" depuis l'info de fence (cible du bloc de code). */
    static String titleOf(String info) {
        if (info == null) {
            return null;
        }
        int at = info.indexOf("title=\"");
        if (at < 0) {
            return null;
        }
        int start = at + "title=\"".length();
        int end = info.indexOf('"', start);
        return end > start ? info.substring(start, end) : info.substring(start);
    }

    /** Élément de liste reconnu (puce/chiffre/case), avec niveau d'imbrication. */
    private static Block.ListItem listItemOf(String line) {
        int indent = line.length() - line.trim().length();
        int level = indent >= 2 ? 1 : 0;
        String trimmed = line.trim();
        if (trimmed.startsWith("- ") || trimmed.startsWith("* ")
                || trimmed.startsWith("+ ")) {
            return itemBody(level, false, trimmed.substring(2));
        }
        int dot = trimmed.indexOf(". ");
        if (dot > 0 && dot <= 3) {
            String number = trimmed.substring(0, dot);
            if (number.chars().allMatch(Character::isDigit)) {
                return itemBody(level, true, trimmed.substring(dot + 2));
            }
        }
        return null;
    }

    private static Block.ListItem itemBody(int level, boolean ordered, String body) {
        String trimmed = body.trim();
        if (trimmed.startsWith("[ ] ")) {
            return new Block.ListItem(level, ordered, true, false, trimmed.substring(4).trim());
        }
        if (trimmed.startsWith("[x] ") || trimmed.startsWith("[X] ")) {
            return new Block.ListItem(level, ordered, true, true, trimmed.substring(4).trim());
        }
        return new Block.ListItem(level, ordered, false, false, trimmed);
    }

    /** Cellules d'une ligne de tableau. */
    private static List<String> cellsOf(String row) {
        String body = row.startsWith("|") ? row.substring(1) : row;
        if (body.endsWith("|")) {
            body = body.substring(0, body.length() - 1);
        }
        List<String> cells = new ArrayList<>();
        for (String cell : body.split("\\|", -1)) {
            cells.add(cell.trim());
        }
        return cells;
    }

    private static String join(List<String> lines) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                text.append('\n');
            }
            text.append(lines.get(i));
        }
        return text.toString();
    }
}
