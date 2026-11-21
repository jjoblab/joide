package jo.joide.files;

import java.util.Locale;

/**
 * Catégories de fichiers (Lot 1 du tiroir) : l'extension (et le statut
 * dossier) donnent une catégorie stable ; l'UI mappe catégorie → drawable.
 * Jamais d'extension codée en dur côté UI.
 */
public final class FileIcons {

    private FileIcons() {}

    /** Catégorie d'un nom de fichier (dossiers inclus). */
    public static String categoryOf(String fileName, boolean directory) {
        if (directory) {
            return "folder";
        }
        String lower = fileName.toLowerCase(Locale.ROOT);
        int dot = lower.lastIndexOf('.');
        String ext = dot < 0 ? "" : lower.substring(dot + 1);
        switch (ext) {
            case "java":
                return "java";
            case "kt":
                return "kotlin";
            case "xml":
                return "xml";
            case "gradle":
            case "toml":
                return "build";
            case "md":
            case "txt":
            case "rst":
                return "text";
            case "json":
            case "yaml":
            case "yml":
            case "properties":
            case "ini":
            case "cfg":
                return "config";
            case "png":
            case "jpg":
            case "jpeg":
            case "gif":
            case "webp":
            case "svg":
                return "image";
            case "jar":
            case "zip":
            case "gz":
            case "7z":
            case "rar":
                return "archive";
            case "jks":
            case "keystore":
            case "p12":
            case "pem":
            case "env":
                return "secret";
            default:
                if (fileName.equals(".gitignore")
                        || fileName.equals(".gitattributes")) {
                    return "vcs";
                }
                return "file";
        }
    }

    /** Le fichier est-il « secret » (jamais affiché en contenu par l'UI) ? */
    public static boolean isSecretCategory(String category) {
        return "secret".equals(category);
    }
}
