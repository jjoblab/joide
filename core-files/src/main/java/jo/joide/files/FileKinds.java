package jo.joide.files;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Classification fine des entrées de l'arborescence (Lot 2 du tiroir) :
 * {@link #kindOf} regarde le chemin relatif complet — le parent d'un XML
 * ({@code res/layout} contre {@code res/values}), les racines de sources,
 * les packages — puis délègue à {@link FileIcons} pour la catégorie par
 * extension. Les fichiers Java sont affinés par {@link #refineJavaKind} en
 * lisant un en-tête **borné** (8 Kio, jamais le fichier entier).
 *
 * <p>Pur JVM, aucune dépendance ; les règles sont stables et testées —
 * l'UI ne codera ni extension ni nom de fichier en dur.</p>
 */
public final class FileKinds {

    private FileKinds() {}

    /** Racine des sources principales reconnue (segment de chemin). */
    private static final String MAIN_JAVA = "src/main/java";

    /** Racines des sources de test reconnues (segments de chemin). */
    private static final String[] TEST_JAVA = {"src/test/java", "src/androidTest/java"};

    /** Taille de l'en-tête lue pour classifier un fichier Java (garde-fou). */
    private static final int HEAD_LIMIT = 8 * 1024;

    /**
     * Type d'une entrée par son chemin relatif (racine = « . » ou vide).
     *
     * @param relativePath chemin relatif à la racine du projet.
     * @param directory    l'entrée est-elle un dossier ?
     */
    public static FileKind kindOf(String relativePath, boolean directory) {
        String path = normalize(relativePath);
        if (directory) {
            if (isJavaRoot(path, MAIN_JAVA)) {
                return FileKind.SRC_ROOT;
            }
            for (String root : TEST_JAVA) {
                if (isJavaRoot(path, root)) {
                    return FileKind.TEST_ROOT;
                }
            }
            if (containsSegment(path, MAIN_JAVA)) {
                return FileKind.PACKAGE;
            }
            for (String root : TEST_JAVA) {
                if (containsSegment(path, root)) {
                    return FileKind.PACKAGE;
                }
            }
            return FileKind.FOLDER;
        }
        String name = fileName(path);
        if (name.equals("AndroidManifest.xml")) {
            return FileKind.MANIFEST;
        }
        if (name.endsWith(".java")) {
            return FileKind.JAVA_CLASS; // affiné par refineJavaKind (contenu).
        }
        if (name.endsWith(".kt") || name.endsWith(".kts")) {
            return FileKind.KOTLIN;
        }
        if (name.endsWith(".xml")) {
            String parent = parentName(path);
            if (parent.startsWith("layout")) {
                return FileKind.RES_LAYOUT;
            }
            if (parent.startsWith("values")) {
                return FileKind.RES_VALUES;
            }
            if (parent.startsWith("drawable")) {
                return FileKind.RES_DRAWABLE;
            }
            return FileKind.XML;
        }
        if (name.startsWith("gradlew") || name.endsWith(".gradle")
                || name.endsWith(".toml")) {
            return FileKind.GRADLE;
        }
        if (name.equals(".gitignore") || name.equals(".gitattributes")) {
            return FileKind.GITIGNORE;
        }
        // Documents lisibles d'usage universel, même sans extension.
        String upper = name.toUpperCase(java.util.Locale.ROOT);
        if (upper.startsWith("README") || upper.startsWith("LICENSE")
                || upper.startsWith("NOTICE")) {
            return FileKind.TEXT;
        }
        String category = FileIcons.categoryOf(name, false);
        switch (category) {
            case "build":
                return FileKind.GRADLE;
            case "text":
                return FileKind.TEXT;
            case "config":
                return FileKind.CONFIG;
            case "image":
                return FileKind.IMAGE;
            case "archive":
                return FileKind.ARCHIVE;
            case "secret":
                return FileKind.SECRET;
            case "vcs":
                return FileKind.GITIGNORE;
            default:
                return FileKind.UNKNOWN;
        }
    }

    /**
     * Affine un type Java par le contenu du fichier : classe, classe
     * abstraite, interface, énumération, annotation — tolérant au code
     * incomplet (on cherche la première déclaration de type de l'en-tête,
     * commentaires inclus : une heuristique honnête, pas un parseur).
     * Lecture bornée à 8 Kio ; toute erreur d'E/S rend la classe simple
     * (jamais de crash pour une icône).
     *
     * @param kind type retourné par {@link #kindOf} (seul JAVA_CLASS est affiné).
     * @param file chemin absolu du fichier.
     */
    public static FileKind refineJavaKind(FileKind kind, Path file) {
        if (kind != FileKind.JAVA_CLASS) {
            return kind;
        }
        try {
            return javaKindOf(readHead(file));
        } catch (IOException e) {
            return FileKind.JAVA_CLASS;
        }
    }

    /**
     * Classifie un en-tête de fichier Java (texte déjà lu, borné par
     * l'appelant). La déclaration est cherchée **en tête de ligne**
     * (modificateurs autorisés devant) : un mot-clé dans un commentaire ou
     * noyé dans un identifiant ne compte pas. Tolérant au code incomplet —
     * une heuristique honnête, pas un parseur. Ordre : annotation,
     * interface, énumération, classe abstraite, classe.
     */
    public static FileKind javaKindOf(String head) {
        if (head == null || head.isEmpty()) {
            return FileKind.JAVA_CLASS;
        }
        if (ANNOTATION_DECL.matcher(head).find()) {
            return FileKind.JAVA_ANNOTATION;
        }
        if (INTERFACE_DECL.matcher(head).find()) {
            return FileKind.JAVA_INTERFACE;
        }
        if (ENUM_DECL.matcher(head).find()) {
            return FileKind.JAVA_ENUM;
        }
        if (ABSTRACT_CLASS_DECL.matcher(head).find()) {
            return FileKind.JAVA_ABSTRACT;
        }
        return FileKind.JAVA_CLASS;
    }

    /** Modificateurs autorisés devant une déclaration de type. */
    private static final String MODIFIERS =
            "(?:public\\s+|protected\\s+|private\\s+)?(?:static\\s+)?(?:final\\s+)?"
            + "(?:abstract\\s+)?(?:sealed\\s+|non-sealed\\s+)?(?:strictfp\\s+)?";

    /** Déclaration d'annotation, en tête de ligne. */
    private static final java.util.regex.Pattern ANNOTATION_DECL =
            java.util.regex.Pattern.compile("(?m)^\\s*" + MODIFIERS + "@interface\\s+\\w");

    /** Déclaration d'interface, en tête de ligne. */
    private static final java.util.regex.Pattern INTERFACE_DECL =
            java.util.regex.Pattern.compile("(?m)^\\s*" + MODIFIERS + "interface\\s+\\w");

    /** Déclaration d'énumération, en tête de ligne. */
    private static final java.util.regex.Pattern ENUM_DECL =
            java.util.regex.Pattern.compile("(?m)^\\s*" + MODIFIERS + "enum\\s+\\w");

    /** Déclaration de classe abstraite (abstract requis), en tête de ligne. */
    private static final java.util.regex.Pattern ABSTRACT_CLASS_DECL =
            java.util.regex.Pattern.compile("(?m)^\\s*"
                    + "(?:public\\s+|protected\\s+|private\\s+)?(?:static\\s+)?"
                    + "(?:final\\s+)?abstract\\s+class\\s+\\w");

    /** Lit au plus 8 Kio du fichier en UTF-8 (jamais le fichier entier). */
    static String readHead(Path file) throws IOException {
        try (var in = Files.newInputStream(file)) {
            byte[] buffer = new byte[HEAD_LIMIT];
            int read = in.readNBytes(buffer, 0, buffer.length);
            return new String(buffer, 0, read, StandardCharsets.UTF_8);
        }
    }

    /** Le chemin EST la racine donnée (dernier segment exact). */
    private static boolean isJavaRoot(String path, String root) {
        return path.equals(root) || path.endsWith("/" + root);
    }

    /** Le chemin contient-il le segment complet (racine) n'importe où ? */
    private static boolean containsSegment(String path, String segment) {
        return path.equals(segment) || path.startsWith(segment + "/")
                || path.endsWith("/" + segment) || path.contains("/" + segment + "/");
    }

    /** Chemin normalisé : « . » et vide deviennent chaîne vide, pas de / final. */
    private static String normalize(String relativePath) {
        if (relativePath == null) {
            return "";
        }
        String p = relativePath;
        if (p.equals(".") || p.isEmpty()) {
            return "";
        }
        while (p.startsWith("./")) {
            p = p.substring(2);
        }
        if (p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }

    private static String fileName(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    /** Nom du dossier parent du fichier (chaîne vide à la racine). */
    private static String parentName(String path) {
        int slash = path.lastIndexOf('/');
        if (slash < 0) {
            return "";
        }
        String parent = path.substring(0, slash);
        int parentSlash = parent.lastIndexOf('/');
        return parentSlash < 0 ? parent : parent.substring(parentSlash + 1);
    }
}
