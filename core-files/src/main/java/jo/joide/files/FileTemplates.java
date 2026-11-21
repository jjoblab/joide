package jo.joide.files;

/**
 * Modèles de fichiers du tiroir (Lot 3) : un contenu de départ honnête par
 * extension — squelette de classe Java (package déduit du dossier), fichier
 * Kotlin, XML, Markdown ; vide pour le reste. Pur JVM, testé ; l'UI choisit
 * le nom, le cœur fournit le contenu.
 */
public final class FileTemplates {

    private FileTemplates() {}

    /**
     * Contenu de départ d'un nouveau fichier.
     *
     * @param fileName   nom du fichier à créer (avec extension).
     * @param packageDir dossier de création relatif (« com/example/app » ;
     *                   ignoré hors fichiers Java/Kotlin).
     */
    public static String contentFor(String fileName, String packageDir) {
        String pkg = packageDir(packageDir);
        String lower = fileName.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".java")) {
            String simple = fileName.substring(0, fileName.length() - 5);
            return (pkg.isEmpty() ? "" : "package " + pkg + ";\n\n")
                    + "public class " + simple + " {\n}\n";
        }
        if (lower.endsWith(".kt")) {
            String simple = fileName.substring(0, fileName.length() - 3);
            return (pkg.isEmpty() ? "" : "package " + pkg + "\n\n")
                    + "class " + simple + "\n";
        }
        if (lower.endsWith(".xml")) {
            return "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n";
        }
        if (lower.endsWith(".md")) {
            String simple = fileName.substring(0, fileName.length() - 3);
            return "# " + simple + "\n";
        }
        return "";
    }

    /** « com/example/app » → « com.example.app » ; racine ou null → vide. */
    private static String packageDir(String packageDir) {
        if (packageDir == null || packageDir.isEmpty() || packageDir.equals(".")) {
            return "";
        }
        return packageDir.replace('/', '.');
    }
}
