package jo.joide.outline;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Plan du fichier actif (Lot 5 du tiroir) : symboles par langage — Java
 * (package, imports repliés, types imbriqués, champs, constructeurs,
 * méthodes avec signature courte et visibilité), XML (éléments avec
 * {@code android:id}), Markdown (titres), JSON (clés).
 *
 * <p>Analyseur **en lignes**, tolérant au code incomplet : aucune
 * exception, un fichier invalide rend simplement moins de symboles — le
 * plan se reconstruit à chaque frappe (débouncé côté UI), jamais de crash.
 * Pur JVM, testé ; l'UI ne connaît ni regex ni langage, seulement des
 * symboles.</p>
 */
public final class Outline {

    /** Nature d'un symbole (l'icône et l'indentation s'en déduisent). */
    public enum Kind {
        PACKAGE, IMPORTS, CLASS, INTERFACE, ENUM, ANNOTATION_TYPE,
        FIELD, CONSTRUCTOR, METHOD, ELEMENT, HEADING, KEY
    }

    /**
     * Un symbole du plan.
     *
     * @param kind      nature (icône).
     * @param name      nom affiché.
     * @param signature précision (paramètres raccourcis, android:id, niveau
     *                  de titre…), chaîne vide si rien.
     * @param visibility + public, − private, # protected, ~ package, ' '
     *                  pour les symboles sans visibilité (XML, titres…).
     * @param statik    membre statique.
     * @param line      ligne 1-based de la déclaration.
     * @param depth     profondeur d'imbrication (indentation du plan).
     */
    public record Symbol(Kind kind, String name, String signature,
            char visibility, boolean statik, int line, int depth) {}

    private Outline() {}

    // ---- Java ----------------------------------------------------------------

    /** Modificateurs autorisés devant une déclaration. */
    private static final String MODIFIERS =
            "(?:public\\s+|protected\\s+|private\\s+)?(?:static\\s+)?"
            + "(?:final\\s+)?(?:abstract\\s+)?(?:sealed\\s+|non-sealed\\s+)?"
            + "(?:default\\s+|transient\\s+|volatile\\s+|synchronized\\s+)?";

    private static final Pattern PACKAGE_DECL =
            Pattern.compile("^\\s*package\\s+([\\w.]+)\\s*;?");
    private static final Pattern TYPE_DECL = Pattern.compile("^\\s*" + MODIFIERS
            + "(class|interface|enum|@interface|record)\\s+(\\w+)");
    private static final Pattern METHOD_DECL = Pattern.compile("^\\s*" + MODIFIERS
            + "[\\w<>\\[\\],.?\\s]+?\\s(\\w+)\\s*\\(([^)]*)\\)");
    private static final Pattern FIELD_DECL = Pattern.compile("^\\s*" + MODIFIERS
            + "[\\w<>\\[\\],.?\\s]+?\\s(\\w+)\\s*(?:=[^;]*)?;");
    private static final Pattern VISIBILITY =
            Pattern.compile("^\\s*(public|protected|private)");

    /** Plan d'un fichier Java (tolérant : lignes, pas un parseur complet). */
    public static List<Symbol> javaOutline(String text) {
        List<Symbol> out = new ArrayList<>();
        String[] lines = text.split("\n", -1);
        int depth = 0;
        int importCount = 0;
        int importFirstLine = 0;
        boolean importOpen = false;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            int number = i + 1;
            Matcher pack = PACKAGE_DECL.matcher(line);
            if (pack.find() && out.stream().noneMatch(s -> s.kind() == Kind.PACKAGE)) {
                out.add(new Symbol(Kind.PACKAGE, pack.group(1), "", ' ', false,
                        number, 0));
            }
            if (line.trim().startsWith("import ")) {
                if (!importOpen) {
                    importOpen = true;
                    importFirstLine = number;
                }
                importCount++;
                depth += countChar(line, '{') - countChar(line, '}');
                continue;
            }
            if (importOpen && importCount > 0 && !line.trim().startsWith("import ")) {
                // Les imports se replient en une ligne (convention d'IDE).
                out.add(new Symbol(Kind.IMPORTS, importCount + " imports", "",
                        ' ', true, importFirstLine, 0));
                importOpen = false;
                importCount = 0;
            }
            Matcher type = TYPE_DECL.matcher(line);
            if (type.find()) {
                out.add(new Symbol(kindOfType(type.group(1)), type.group(2),
                        "", visibilityOf(line), line.contains("static"),
                        number, Math.min(depth, 8)));
                depth += countChar(line, '{') - countChar(line, '}');
                continue;
            }
            Matcher method = METHOD_DECL.matcher(line);
            if (method.find() && !line.trim().startsWith("//")
                    && !line.contains("=")
                    && line.contains("(") && (line.contains("{")
                        || line.trim().endsWith(";") || endsWithThrows(lines, i))) {
                String name = method.group(1);
                Kind kind = looksLikeConstructor(lines, name, depth)
                        ? Kind.CONSTRUCTOR : Kind.METHOD;
                out.add(new Symbol(kind, name, signatureOf(method.group(2)),
                        visibilityOf(line), line.contains("static"),
                        number, Math.min(depth, 8)));
            } else {
                // Un champ : pas de parenthèse dans la DÉCLARATION (avant « = »)
                // — un initialisateur qui appelle reste un champ.
                String declaration = line.contains("=")
                        ? line.substring(0, line.indexOf('=')) : line;
                Matcher field = FIELD_DECL.matcher(line);
                if (field.find() && !line.trim().startsWith("//")
                        && !declaration.contains("(")
                        && !line.trim().startsWith("return")
                        && !line.trim().startsWith("throw")) {
                    out.add(new Symbol(Kind.FIELD, field.group(1), "",
                            visibilityOf(line), line.contains("static"),
                            number, Math.min(depth, 8)));
                }
            }
            depth += countChar(line, '{') - countChar(line, '}');
        }
        if (importOpen && importCount > 0) {
            out.add(new Symbol(Kind.IMPORTS, importCount + " imports", "",
                    ' ', true, importFirstLine, 0));
        }
        return out;
    }

    private static Kind kindOfType(String keyword) {
        return switch (keyword) {
            case "interface" -> Kind.INTERFACE;
            case "enum" -> Kind.ENUM;
            case "@interface" -> Kind.ANNOTATION_TYPE;
            default -> Kind.CLASS; // class et record.
        };
    }

    /** Signature courte : types des paramètres, virgules serrées. */
    private static String signatureOf(String parameters) {
        if (parameters == null || parameters.isBlank()) {
            return "()";
        }
        StringBuilder builder = new StringBuilder("(");
        for (String parameter : parameters.split(",")) {
            String trimmed = parameter.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int lastSpace = trimmed.lastIndexOf(' ');
            String type = lastSpace < 0 ? trimmed : trimmed.substring(0, lastSpace);
            if (builder.length() > 1) {
                builder.append(", ");
            }
            builder.append(type.replaceAll("\\s+", " "));
        }
        return builder.append(')').toString();
    }

    private static char visibilityOf(String line) {
        Matcher matcher = VISIBILITY.matcher(line);
        if (!matcher.find()) {
            return '~'; // package : la convention d'IDE.
        }
        return switch (matcher.group(1)) {
            case "public" -> '+';
            case "protected" -> '#';
            default -> '-';
        };
    }

    /** Un constructeur : même nom que le type englobant, sans type de retour. */
    private static boolean looksLikeConstructor(String[] lines, String name, int depth) {
        // Retrouve le type ouvert le plus récent par une lecture en retour.
        for (int i = lines.length - 1; i >= 0; i--) {
            Matcher type = TYPE_DECL.matcher(lines[i]);
            if (type.find() && name.equals(type.group(2))) {
                return true;
            }
        }
        return false;
    }

    private static boolean endsWithThrows(String[] lines, int i) {
        String trimmed = lines[i].trim();
        if (trimmed.endsWith("throws") || trimmed.contains("throws ")) {
            return true;
        }
        return i + 1 < lines.length && lines[i + 1].trim().startsWith("throws ");
    }

    // ---- XML -----------------------------------------------------------------

    private static final Pattern XML_ELEMENT =
            Pattern.compile("<(\\?[\\w.-]+|[\\w.-]+)([^>]*?)(/?)>");
    private static final Pattern XML_ID =
            Pattern.compile("(?:android:)?id=\"@\\+?id/(\\w+)\"");

    /** Plan d'un XML : éléments avec android:id (le manifeste y compris). */
    public static List<Symbol> xmlOutline(String text) {
        List<Symbol> out = new ArrayList<>();
        String[] lines = text.split("\n", -1);
        int depth = 0;
        for (int i = 0; i < lines.length; i++) {
            Matcher element = XML_ELEMENT.matcher(lines[i]);
            while (element.find()) {
                String tag = element.group(1);
                if (tag.startsWith("?")) {
                    continue; // déclaration XML.
                }
                if (tag.startsWith("/")) {
                    depth = Math.max(0, depth - 1);
                    continue;
                }
                boolean selfClosed = "/".equals(element.group(3));
                Matcher id = XML_ID.matcher(element.group(2));
                out.add(new Symbol(Kind.ELEMENT, tag,
                        id.find() ? "@" + id.group(1) : "", ' ', false,
                        i + 1, Math.min(depth, 8)));
                if (!selfClosed && !tag.startsWith("!")) {
                    depth++;
                }
            }
            // Balises fermantes seules sur la ligne.
            for (int j = 0; j < lines[i].length(); j++) {
                if (lines[i].charAt(j) == '<' && j + 1 < lines[i].length()
                        && lines[i].charAt(j + 1) == '/') {
                    depth = Math.max(0, depth - 1);
                }
            }
        }
        return out;
    }

    // ---- Markdown et JSON ------------------------------------------------------

    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.*)$");

    /** Plan d'un Markdown : titres, la profondeur suit le niveau. */
    public static List<Symbol> markdownOutline(String text) {
        List<Symbol> out = new ArrayList<>();
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            Matcher heading = HEADING.matcher(lines[i].trim());
            if (heading.find()) {
                out.add(new Symbol(Kind.HEADING, heading.group(2),
                        "", ' ', false, i + 1, heading.group(1).length() - 1));
            }
        }
        return out;
    }

    /** Plan d'un JSON : clés, la profondeur suit les accolades (racine = 0). */
    public static List<Symbol> jsonOutline(String text) {
        List<Symbol> out = new ArrayList<>();
        String[] lines = text.split("\n", -1);
        int depth = 0;
        Pattern key = Pattern.compile("\"([^\"]+)\"\\s*:");
        for (int i = 0; i < lines.length; i++) {
            Matcher found = key.matcher(lines[i]);
            if (found.find()) {
                // Accolades AVANT la clé : elles ouvrent le contenant.
                depth = Math.max(0, depth
                        + netBraces(lines[i], 0, found.start()));
                out.add(new Symbol(Kind.KEY, found.group(1), "", ' ', false,
                        i + 1, Math.max(0, depth - 1)));
                depth = Math.max(0, depth
                        + netBraces(lines[i], found.end(), lines[i].length()));
            } else {
                depth = Math.max(0, depth
                        + netBraces(lines[i], 0, lines[i].length()));
            }
        }
        return out;
    }

    /** Solde d'accolades ouvrantes/fermantes d'une tranche de ligne. */
    private static int netBraces(String line, int from, int to) {
        int net = 0;
        for (int i = from; i < to && i < line.length(); i++) {
            if (line.charAt(i) == '{') {
                net++;
            } else if (line.charAt(i) == '}') {
                net--;
            }
        }
        return net;
    }

    private static int countChar(String line, char c) {
        int count = 0;
        for (int i = 0; i < line.length(); i++) {
            if (line.charAt(i) == c) {
                count++;
            }
        }
        return count;
    }
}
